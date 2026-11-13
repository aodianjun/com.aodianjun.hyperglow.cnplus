package com.eza.hyperglow.root.spectrum

import android.content.Context
import android.content.pm.PackageManager
import android.media.AudioAttributes
import android.media.AudioFormat
import android.media.AudioManager
import android.media.AudioRecord
import android.os.Looper
import android.os.Process
import android.os.SystemClock
import com.eza.hyperglow.aod.SpectrumFrame
import com.eza.hyperglow.root.HookLogger
import com.eza.hyperglow.spectrum.SpectrumAnalyzer
import java.lang.reflect.InvocationTargetException
import java.util.concurrent.atomic.AtomicBoolean

/**
 * 移植自 leset0ng/hyperwave 的 PlaybackCapture（Apache-2.0），改为 HyperGlow 的
 * Kotlin FFT 与帧契约。
 *
 * 通过隐藏 AudioPolicy/AudioMix 注册一条 USAGE_MEDIA 的 playback copy，不重定向原输出。
 * 不申请 RECORD_AUDIO、不启 MediaProjection；完全依赖 LSPosed 的 framework hook。
 */
internal class PlaybackCapture(
    context: Context,
    private val listener: Listener
) : Runnable {
    interface Listener {
        fun onFrame(frame: SpectrumFrame)
        fun onUnavailable(reason: String)
    }

    private val appContext = context.applicationContext ?: context
    private val running = AtomicBoolean(true)
    private val thread = Thread(this, "HyperGlow-loopback")
    @Volatile
    private var record: AudioRecord? = null
    fun start() {
        try {
            thread.start()
        } catch (error: Throwable) {
            running.set(false)
            throw error
        }
    }

    fun stop() {
        if (!running.compareAndSet(true, false)) return
        // 主动 stop 让阻塞中的 read 尽早返回；不 join/release，避免在 SystemUI 主线程
        // 暂停音乐时卡住或与 read 线程竞争释放。AudioPolicy 的 unregister 由采集线程
        // 在 finally 里完成。
        runCatching { record?.stop() }
    }

    override fun run() {
        Process.setThreadPriority(Process.THREAD_PRIORITY_AUDIO)
        val manager = appContext.getSystemService(AudioManager::class.java)
        HookLogger.bootstrap(
            LoopbackCaptureContract.TAG,
            "session process=${appContext.packageName} uid=${Process.myUid()} " +
                "recordPermission=" +
                (appContext.checkSelfPermission(android.Manifest.permission.RECORD_AUDIO) ==
                    PackageManager.PERMISSION_GRANTED)
        )
        try {
            runCaptureWithFallback(
                isStopped = { !running.get() },
                capture = { privileged -> captureAttempt(manager, privileged) },
                onFallback = { error ->
                    HookLogger.w(
                        LoopbackCaptureContract.TAG,
                        "privileged capture rejected; retrying legacy 48k mono float",
                        captureCause(error)
                    )
                }
            )
        } catch (error: Throwable) {
            if (running.get()) {
                val cause = captureCause(error)
                HookLogger.w(
                    LoopbackCaptureContract.TAG,
                    "loopback unavailable: ${cause.javaClass.simpleName}: ${cause.message}",
                    cause
                )
                listener.onUnavailable("${cause.javaClass.simpleName}: ${cause.message}")
            }
        }
    }

    private fun captureAttempt(manager: AudioManager, privileged: Boolean) {
        if (!running.get()) return
        var policy: Any? = null
        var policyClass: Class<*>? = null
        var registered = false
        try {
            val ruleClass = Class.forName("android.media.audiopolicy.AudioMixingRule")
            val ruleBuilderClass = Class.forName("android.media.audiopolicy.AudioMixingRule\$Builder")
            val ruleBuilder = ruleBuilderClass.getConstructor().newInstance()
            runCatching {
                ruleBuilderClass.getMethod("setTargetMixRole", Int::class.java)
                    .invoke(ruleBuilder, LoopbackCaptureContract.TARGET_MIX_ROLE)
            }
            val attributesBuilder = AudioAttributes.Builder()
                .setUsage(AudioAttributes.USAGE_MEDIA)
            if (privileged) {
                runCatching {
                    AudioAttributes.Builder::class.java
                        .getMethod("addTag", String::class.java)
                        .invoke(attributesBuilder, LoopbackCaptureContract.RULE_TAG)
                }
            }
            val attributes = attributesBuilder.build()
            ruleBuilderClass.getMethod("addRule", AudioAttributes::class.java, Int::class.java)
                .invoke(ruleBuilder, attributes, 1)
            ruleBuilderClass.getMethod("allowPrivilegedPlaybackCapture", Boolean::class.java)
                .invoke(ruleBuilder, privileged)
            val rule = ruleBuilderClass.getMethod("build").invoke(ruleBuilder)

            val mixClass = Class.forName("android.media.audiopolicy.AudioMix")
            val mixBuilderClass = Class.forName("android.media.audiopolicy.AudioMix\$Builder")
            val mixBuilder = mixBuilderClass.getConstructor(ruleClass).newInstance(rule)
            val format = AudioFormat.Builder()
                .setSampleRate(LoopbackCaptureContract.SAMPLE_RATE)
                .setEncoding(LoopbackCaptureContract.ENCODING)
                .setChannelMask(LoopbackCaptureContract.CHANNEL_MASK)
                .build()
            mixBuilderClass.getMethod("setFormat", AudioFormat::class.java).invoke(mixBuilder, format)
            mixBuilderClass.getMethod("setRouteFlags", Int::class.java)
                .invoke(mixBuilder, LoopbackCaptureContract.ROUTE_FLAGS)
            val mix = mixBuilderClass.getMethod("build").invoke(mixBuilder)

            policyClass = Class.forName("android.media.audiopolicy.AudioPolicy")
            val policyBuilderClass = Class.forName("android.media.audiopolicy.AudioPolicy\$Builder")
            val policyBuilder = policyBuilderClass.getConstructor(Context::class.java)
                .newInstance(appContext)
            policyBuilderClass.getMethod("setLooper", Looper::class.java)
                .invoke(policyBuilder, Looper.getMainLooper())
            policyBuilderClass.getMethod("addMix", mixClass).invoke(policyBuilder, mix)
            policy = policyBuilderClass.getMethod("build").invoke(policyBuilder)
            if (!running.get()) return

            val result = AudioManager::class.java
                .getMethod("registerAudioPolicy", policyClass)
                .invoke(manager, policy) as Int
            if (result != 0) throw IllegalStateException("registerAudioPolicy=$result")
            registered = true
            val sink = policyClass.getMethod("createAudioRecordSink", mixClass)
                .invoke(policy, mix) as? AudioRecord
                ?: throw IllegalStateException("Playback AudioRecord not initialized")
            if (sink.state != AudioRecord.STATE_INITIALIZED) {
                throw IllegalStateException("Playback AudioRecord not initialized")
            }
            if (sink.sampleRate != LoopbackCaptureContract.SAMPLE_RATE ||
                sink.channelCount != 1 ||
                sink.audioFormat != LoopbackCaptureContract.ENCODING
            ) {
                throw IllegalStateException(
                    "Unexpected playback capture format " +
                        "${sink.sampleRate}/${sink.channelCount}/${sink.audioFormat}"
                )
            }
            record = sink
            sink.startRecording()
            if (sink.recordingState != AudioRecord.RECORDSTATE_RECORDING) {
                throw IllegalStateException("Playback AudioRecord did not start")
            }
            HookLogger.i(
                LoopbackCaptureContract.TAG,
                "capture started mode=${if (privileged) "privileged" else "legacy"} " +
                    "48000Hz mono float32"
            )
            readLoop(sink)
        } finally {
            val current = record
            record = null
            runCatching { current?.stop() }
            runCatching { current?.release() }
            if (registered && policy != null && policyClass != null) {
                runCatching {
                    AudioManager::class.java
                        .getMethod("unregisterAudioPolicy", policyClass)
                        .invoke(manager, policy)
                }.onFailure {
                    HookLogger.w(
                        LoopbackCaptureContract.TAG,
                        "unregisterAudioPolicy failed",
                        captureCause(it)
                    )
                }
            }
            HookLogger.i(LoopbackCaptureContract.TAG, "capture stopped")
        }
    }

    private fun readLoop(source: AudioRecord) {
        val analyzer = SpectrumAnalyzer(LoopbackCaptureContract.SAMPLE_RATE)
        val buffer = FloatArray(LoopbackCaptureContract.SAMPLE_SIZE)
        var lastPublished = 0L
        var sequence = 0L
        var loggedFirstFrame = false
        while (running.get()) {
            val read = source.read(buffer, 0, buffer.size, AudioRecord.READ_BLOCKING)
            if (read <= 0) {
                if (read < 0 && running.get()) {
                    HookLogger.w(
                        LoopbackCaptureContract.TAG,
                        "AudioRecord.read=$read"
                    )
                }
                break
            }
            val now = SystemClock.elapsedRealtime()
            val analyzed = analyzer.analyze(buffer, read, now)
            if (!loggedFirstFrame) {
                loggedFirstFrame = true
                HookLogger.i(
                    LoopbackCaptureContract.TAG,
                    "first frame rms=${analyzed.rms} active=${analyzed.active}"
                )
            }
            if (now - lastPublished < LoopbackCaptureContract.PUBLISH_INTERVAL_MS) continue
            lastPublished = now
            listener.onFrame(
                analyzed.copy(sequence = sequence++)
            )
        }
    }

    private fun captureCause(error: Throwable): Throwable =
        if (error is InvocationTargetException && error.cause != null) error.cause!! else error
}

private fun runCaptureWithFallback(
    isStopped: () -> Boolean,
    capture: (privileged: Boolean) -> Unit,
    onFallback: (Exception) -> Unit
) {
    if (isStopped()) return
    try {
        capture(true)
    } catch (first: Exception) {
        if (isStopped()) return
        onFallback(first)
        if (isStopped()) return
        try {
            capture(false)
        } catch (second: Exception) {
            if (second !== first) second.addSuppressed(first)
            throw second
        }
    }
}
