package com.eza.hyperglow.audio

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioPlaybackCaptureConfiguration
import android.media.AudioRecord
import android.media.projection.MediaProjection
import android.media.projection.MediaProjectionManager
import android.os.Handler
import android.os.IBinder
import android.os.Looper
import android.os.SystemClock
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.R
import com.eza.hyperglow.aod.AodStateBridge
import com.eza.hyperglow.aod.SpectrumContract
import com.eza.hyperglow.aod.SpectrumFrame
import com.eza.hyperglow.spectrum.SpectrumAnalyzer

/**
 * 系统回放捕获 → FFT → 频谱帧。
 *
 * 走 MediaProjection + AudioPlaybackCaptureConfiguration（不是麦克风）：拿到的是媒体
 * 回放混音，扬声器/耳机音量不会影响频谱形状。仅当被采集的 App 允许回放捕获时可听到
 * 数据；DRM/禁止捕获的音频会得到静音，此时帧的 active=false，渲染端自然淡出。
 *
 * 服务常驻前台（mediaProjection 类型）。进程被杀后 MediaProjection 授权即失效，需要
 * 用户在设置页重新授权，不会静默重启。
 */
class SpectrumCaptureService : Service() {

    private val mainHandler = Handler(Looper.getMainLooper())
    private var projection: MediaProjection? = null
    private var audioRecord: AudioRecord? = null
    private var captureThread: Thread? = null
    private var analyzer: SpectrumAnalyzer? = null

    @Volatile
    private var running = false

    @Volatile
    private var lastPublishAtMs = 0L

    @Volatile
    private var sequence = 0L

    private val projectionCallback = object : MediaProjection.Callback() {
        override fun onStop() {
            AppLog.i(TAG, "MediaProjection stopped by system")
            mainHandler.post { stopSelf() }
        }
    }

    override fun onCreate() {
        super.onCreate()
        ensureNotificationChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_STOP -> {
                stopCaptureAndPublishIdle()
                stopSelf()
                return START_NOT_STICKY
            }

            ACTION_START -> {
                startForeground(
                    NOTIFICATION_ID,
                    buildNotification(),
                    ServiceInfo.FOREGROUND_SERVICE_TYPE_MEDIA_PROJECTION
                )
                val resultCode = intent.getIntExtra(EXTRA_RESULT_CODE, 0)
                val data = intent.getParcelableExtra(EXTRA_RESULT_DATA, Intent::class.java)
                if (resultCode == 0 || data == null) {
                    fail(getString(R.string.spectrum_error_no_grant))
                    return START_NOT_STICKY
                }
                startCapture(resultCode, data)
                // MediaProjection 授权不能跨进程重启恢复，被系统回收后重启服务只会得到
                // 一个没有投影的空壳；交给用户重新授权。
                return START_NOT_STICKY
            }
        }
        return START_NOT_STICKY
    }

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onDestroy() {
        stopCaptureAndPublishIdle()
        super.onDestroy()
    }

    private fun startCapture(resultCode: Int, data: Intent) {
        if (running) return
        val manager = getSystemService(MediaProjectionManager::class.java)
        if (manager == null) {
            fail(getString(R.string.spectrum_error_projection_unavailable))
            return
        }
        val created = try {
            manager.getMediaProjection(resultCode, data)
        } catch (error: Exception) {
            AppLog.w(TAG, "getMediaProjection failed", error)
            null
        }
        if (created == null) {
            fail(getString(R.string.spectrum_error_projection_unavailable))
            return
        }
        try {
            created.registerCallback(projectionCallback, mainHandler)
        } catch (error: Exception) {
            AppLog.w(TAG, "registerCallback failed", error)
        }
        projection = created

        val opened = openAudioRecord(created)
        if (opened == null) {
            fail(getString(R.string.spectrum_error_capture_init))
            return
        }
        analyzer = SpectrumAnalyzer(opened.sampleRate)
        lastPublishAtMs = 0L
        sequence = 0L
        audioRecord = opened.record
        running = true
        SpectrumCaptureState.setRunning(true)
        SpectrumCaptureState.setError(null)
        AppLog.i(
            TAG,
            "capture started sampleRate=${opened.sampleRate} encoding=${opened.encoding}"
        )
        captureThread = Thread({ captureLoop(opened) }, "hyperglow-spectrum").apply {
            priority = Thread.NORM_PRIORITY
            start()
        }
    }

    private fun openAudioRecord(projection: MediaProjection): OpenedCapture? {
        val configuration = try {
            AudioPlaybackCaptureConfiguration.Builder(projection)
                .addMatchingUsage(AudioAttributes.USAGE_MEDIA)
                .addMatchingUsage(AudioAttributes.USAGE_GAME)
                .addMatchingUsage(AudioAttributes.USAGE_UNKNOWN)
                .build()
        } catch (error: Exception) {
            AppLog.w(TAG, "playback capture configuration failed", error)
            return null
        }
        val encodings = intArrayOf(
            AudioFormat.ENCODING_PCM_FLOAT,
            AudioFormat.ENCODING_PCM_16BIT
        )
        val sampleRates = intArrayOf(48_000, 44_100)
        for (encoding in encodings) {
            for (sampleRate in sampleRates) {
                val channelMask = AudioFormat.CHANNEL_IN_MONO
                val minBuffer = try {
                    AudioRecord.getMinBufferSize(sampleRate, channelMask, encoding)
                } catch (error: Exception) {
                    -1
                }
                if (minBuffer <= 0) continue
                val bytesPerSample = if (encoding == AudioFormat.ENCODING_PCM_FLOAT) 4 else 2
                val bufferBytes = maxOf(
                    minBuffer,
                    SpectrumContract.FFT_SIZE * bytesPerSample * 2
                )
                val record = try {
                    AudioRecord.Builder()
                        .setAudioFormat(
                            AudioFormat.Builder()
                                .setEncoding(encoding)
                                .setSampleRate(sampleRate)
                                .setChannelMask(channelMask)
                                .build()
                        )
                        .setBufferSizeInBytes(bufferBytes)
                        .setAudioPlaybackCaptureConfig(configuration)
                        .build()
                } catch (error: Exception) {
                    AppLog.w(
                        TAG,
                        "AudioRecord build failed encoding=$encoding rate=$sampleRate",
                        error
                    )
                    null
                } ?: continue
                if (record.state != AudioRecord.STATE_INITIALIZED) {
                    record.release()
                    continue
                }
                return OpenedCapture(record, sampleRate, encoding)
            }
        }
        return null
    }

    private fun captureLoop(capture: OpenedCapture) {
        val activeAnalyzer = analyzer ?: return
        val floatBuffer = FloatArray(SpectrumContract.FFT_SIZE)
        val shortBuffer = ShortArray(SpectrumContract.FFT_SIZE)
        try {
            capture.record.startRecording()
            if (capture.record.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                mainHandler.post { fail(getString(R.string.spectrum_error_capture_init)) }
                return
            }
            while (running) {
                val read = try {
                    if (capture.encoding == AudioFormat.ENCODING_PCM_FLOAT) {
                        capture.record.read(
                            floatBuffer,
                            0,
                            floatBuffer.size,
                            AudioRecord.READ_BLOCKING
                        ).also { count ->
                            if (count > 0) publish(activeAnalyzer, floatBuffer, count)
                        }
                    } else {
                        capture.record.read(
                            shortBuffer,
                            0,
                            shortBuffer.size,
                            AudioRecord.READ_BLOCKING
                        ).also { count ->
                            if (count > 0) {
                                for (index in 0 until count) {
                                    floatBuffer[index] = shortBuffer[index] / 32_768f
                                }
                                publish(activeAnalyzer, floatBuffer, count)
                            }
                        }
                    }
                } catch (error: Exception) {
                    AppLog.w(TAG, "AudioRecord read failed", error)
                    -1
                }
                if (read < 0) break
            }
        } finally {
            if (running) {
                AppLog.w(TAG, "capture loop exited unexpectedly")
                mainHandler.post { fail(getString(R.string.spectrum_error_capture_stopped)) }
            }
        }
    }

    private fun publish(activeAnalyzer: SpectrumAnalyzer, samples: FloatArray, count: Int) {
        val now = SystemClock.elapsedRealtime()
        if (now - lastPublishAtMs < SpectrumContract.MIN_PUBLISH_INTERVAL_MS) return
        lastPublishAtMs = now
        val frame = activeAnalyzer.analyze(samples, count, now)
        sequence = frame.sequence
        AodStateBridge.publishSpectrum(frame)
    }

    private fun stopCaptureAndPublishIdle() {
        stopCapture()
        SpectrumCaptureState.setRunning(false)
        val now = SystemClock.elapsedRealtime()
        AodStateBridge.publishSpectrum(
            SpectrumFrame.idle(++sequence, now),
            force = true
        )
    }

    private fun stopCapture() {
        running = false
        val record = audioRecord
        audioRecord = null
        try {
            record?.stop()
        } catch (_: Exception) {
        }
        captureThread?.let { thread ->
            if (thread !== Thread.currentThread()) {
                try {
                    thread.join(800L)
                } catch (_: InterruptedException) {
                    Thread.currentThread().interrupt()
                }
            }
        }
        captureThread = null
        try {
            record?.release()
        } catch (_: Exception) {
        }
        try {
            projection?.unregisterCallback(projectionCallback)
        } catch (_: Exception) {
        }
        try {
            projection?.stop()
        } catch (_: Exception) {
        }
        projection = null
        analyzer = null
    }

    private fun fail(message: String) {
        AppLog.w(TAG, "capture failed: $message")
        SpectrumCaptureState.setError(message)
        stopCaptureAndPublishIdle()
        stopSelf()
    }

    private fun ensureNotificationChannel() {
        val manager = getSystemService(NotificationManager::class.java) ?: return
        if (manager.getNotificationChannel(CHANNEL_ID) != null) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.spectrum_notification_channel),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.spectrum_notification_text)
            setShowBadge(false)
            enableLights(false)
            enableVibration(false)
        }
        manager.createNotificationChannel(channel)
    }

    private fun buildNotification(): Notification {
        val stopIntent = Intent(this, SpectrumCaptureService::class.java).apply {
            action = ACTION_STOP
        }
        val stopPendingIntent = PendingIntent.getService(
            this,
            NOTIFICATION_ID,
            stopIntent,
            PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
        )
        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setSmallIcon(R.drawable.ic_lyric_notification)
            .setContentTitle(getString(R.string.spectrum_notification_title))
            .setContentText(getString(R.string.spectrum_notification_text))
            .setOngoing(true)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .setCategory(NotificationCompat.CATEGORY_SERVICE)
            .setShowWhen(false)
            .addAction(0, getString(R.string.spectrum_notification_stop), stopPendingIntent)
            .build()
    }

    private data class OpenedCapture(
        val record: AudioRecord,
        val sampleRate: Int,
        val encoding: Int
    )

    companion object {
        private const val TAG = "SpectrumCaptureService"
        private const val CHANNEL_ID = "spectrum_capture"
        private const val NOTIFICATION_ID = 2
        private const val ACTION_START = "com.eza.hyperglow.action.START_SPECTRUM_CAPTURE"
        private const val ACTION_STOP = "com.eza.hyperglow.action.STOP_SPECTRUM_CAPTURE"
        private const val EXTRA_RESULT_CODE = "resultCode"
        private const val EXTRA_RESULT_DATA = "resultData"

        /** 由 Activity 的 MediaProjection 授权结果调用。 */
        fun start(context: Context, resultCode: Int, data: Intent) {
            val intent = Intent(context, SpectrumCaptureService::class.java).apply {
                action = ACTION_START
                putExtra(EXTRA_RESULT_CODE, resultCode)
                putExtra(EXTRA_RESULT_DATA, data)
            }
            ContextCompat.startForegroundService(context, intent)
        }

        fun stop(context: Context) {
            val intent = Intent(context, SpectrumCaptureService::class.java).apply {
                action = ACTION_STOP
            }
            try {
                context.startService(intent)
            } catch (error: Exception) {
                AppLog.w(TAG, "stop service failed", error)
            }
        }
    }
}
