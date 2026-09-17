package com.eza.hyperglow.root.aod

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.os.Handler
import android.os.Looper
import android.os.SystemClock
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_AUTO
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE
import com.eza.hyperglow.aod.AOD_ROTATION_MODE_LANDSCAPE_REVERSE
import com.eza.hyperglow.root.HookLogger
import kotlin.math.abs

/**
 * AOD 画布相对竖屏的刚性旋转步进(每种 step 都是整整 90°)。
 *
 * 画布的真值统一用这个枚举表达,监听器(AodSurfaceController)据此对画布做刚性
 * 旋转变换,而不是把 UI/约束再转一层。
 */
internal enum class AodOrientationStep {
    PORTRAIT,
    LANDSCAPE,
    REVERSE_LANDSCAPE
}

/**
 * 纯函数:由低通滤波后的重力矢量(gx/gy,单位 m/s²)解析目标朝向。
 *
 * `mode` 是被规范化的旋转模式(见 AodRenderPreferences.normalizeAodRotationMode)。
 * 返回 nullable:
 *  - 非 null → 明确的刚性 step,含 [AodOrientationStep.PORTRAIT]:设备回正(竖直)时也
 *    返回 [AodOrientationStep.PORTRAIT],`evaluate` 可经既有防抖路径从横屏回落竖屏
 *    (issue #30:此前竖直返回 null 被 `evaluate` 视作「无变化」,导致切横屏后无法回落);
 *  - null → 仅限无效/未知输入(非有限重力、portrait 模式、未知模式),画布保持当前朝向
 *    (避免因脏读数来回抖动)。
 *
 * 判定约定(传感器坐标,+Y 指向屏幕底、+X 指向屏幕右、设备竖直立起时 gy≈+9.8):
 *  - 竖直: |gy| > |gx|;横躺: |gx| > |gy|;
 *  - 逆时针 90°(右侧朝上)时 gx>0;顺时针 90°(左侧朝上)时 gx<0。
 */
internal fun resolveAodRotationStep(
    mode: String,
    gravityX: Float,
    gravityY: Float
): AodOrientationStep? {
    if (!gravityX.isFinite() || !gravityY.isFinite()) return null
    val absX = abs(gravityX)
    val absY = abs(gravityY)
    return when (mode) {
        AOD_ROTATION_MODE_LANDSCAPE ->
            // 横屏:只要长轴接近水平就转 90°,不区分正反;转正(竖直)回落竖屏。
            if (absX > absY) AodOrientationStep.LANDSCAPE else AodOrientationStep.PORTRAIT
        AOD_ROTATION_MODE_LANDSCAPE_REVERSE ->
            // 反向横屏:固定旋转 -90°(左侧朝上调头的那一侧);转正(竖直)回落竖屏。
            if (absX > absY) AodOrientationStep.REVERSE_LANDSCAPE else AodOrientationStep.PORTRAIT
        AOD_ROTATION_MODE_AUTO ->
            // 自动:跟随重力象限,两侧横屏区分对待;转正(竖直)回落竖屏。
            when {
                absX <= absY -> AodOrientationStep.PORTRAIT
                gravityX > 0f -> AodOrientationStep.LANDSCAPE
                else -> AodOrientationStep.REVERSE_LANDSCAPE
            }
        else -> null // portrait / 未知模式:永不旋转
    }
}

/**
 * 朝向稳定性判定(纯函数):只有当候选与当前保持一致达到 [requiredStableSamples]
 * 帧之后才允许发射,抑制加速度计噪声导致的来回闪。
 */
internal fun shouldEmitRotationStep(
    current: AodOrientationStep?,
    candidate: AodOrientationStep?,
    stableSamples: Int,
    requiredStableSamples: Int
): Boolean = candidate != null && candidate != current && stableSamples >= requiredStableSamples

/**
 * 加速度计生命周期 + 防抖 + 旋转 rebase。
 *
 * 通过 [SensorManager] 读取加速度计,对重力做低通滤波后交给 [resolveAodRotationStep]
 * 解析目标朝向;目标保持稳定达到防抖窗口后才回调监听器。trueValue 变化(rebase)也会
 * 立刻重新评估一次,避免框架旋转信号与传感器读值脱节。
 */
internal object AodOrientationMonitor {
    private const val TAG = "AodOrientationMonitor"

    private val mainHandler = Handler(Looper.getMainLooper())

    @Volatile private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var attached = false

    /** 低通滤波后的重力(x/y),用于抗传感器抖动。 */
    private var filteredX = 0f
    private var filteredY = 0f

    private var currentStep: AodOrientationStep? = null
    private var candidateStep: AodOrientationStep? = null
    private var candidateSinceElapsedMs = 0L
    private var stableSamples = 0

    private var mode = com.eza.hyperglow.aod.AOD_ROTATION_MODE_PORTRAIT
    private var settleMs = DEFAULT_SETTLE_MS
    private var listener: ((AodOrientationStep) -> Unit)? = null

    private val flush = Runnable { flushPending() }

    private val sensorEventListener = object : SensorEventListener {
        override fun onSensorChanged(event: SensorEvent) {
            val x = event.values.getOrNull(0) ?: return
            val y = event.values.getOrNull(1) ?: return
            // 一阶低通,避免单帧毛刺。
            filteredX = (1f - FILTER_ALPHA) * filteredX + FILTER_ALPHA * x
            filteredY = (1f - FILTER_ALPHA) * filteredY + FILTER_ALPHA * y
            evaluate(resolveAodRotationStep(mode, filteredX, filteredY))
        }

        override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) = Unit
    }

    @Synchronized
    fun attach(
        context: Context,
        newMode: String,
        newSettleMs: Long,
        rotationListener: (AodOrientationStep) -> Unit
    ) {
        mode = newMode
        settleMs = newSettleMs.coerceAtLeast(0L)
        listener = rotationListener
        if (attached) {
            // 已附着:仅刷新配置并立即按当前模型重新评估一次(rebase)。
            evaluate(resolveAodRotationStep(mode, filteredX, filteredY))
            return
        }
        val manager = context.getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        val sensor = manager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)
        if (manager == null || sensor == null) {
            HookLogger.w(TAG, "Accelerometer unavailable; AOD rotation disabled")
            return
        }
        sensorManager = manager
        accelerometer = sensor
        filteredX = 0f
        filteredY = 0f
        currentStep = null
        candidateStep = null
        candidateSinceElapsedMs = 0L
        stableSamples = 0
        attached = manager.registerListener(
            sensorEventListener,
            sensor,
            SensorManager.SENSOR_DELAY_UI
        )
        if (attached) {
            HookLogger.i(TAG, "Accelerometer attached mode=$newMode settleMs=$newSettleMs")
        } else {
            HookLogger.w(TAG, "Accelerometer register failed")
        }
    }

    @Synchronized
    fun detach() {
        mainHandler.removeCallbacks(flush)
        val manager = sensorManager
        if (manager != null && attached) {
            runCatching { manager.unregisterListener(sensorEventListener) }
        }
        sensorManager = null
        accelerometer = null
        attached = false
        listener = null
        candidateStep = null
    }

    /**
     * 热重载退役:完整重置传感器与所有派生状态,让新一代从白板开始。
     * 比 [detach] 更强的清理,避免旧代遗留的回调/滤波值污染重装载机。
     */
    @Synchronized
    fun stop() {
        detach()
        filteredX = 0f
        filteredY = 0f
        currentStep = null
        settleMs = DEFAULT_SETTLE_MS
        stableSamples = 0
    }

    fun isAttached(): Boolean = attached

    @Synchronized
    fun rebase(referenceStep: AodOrientationStep?) {
        // 框架旋转信号(rebase)作为权威覆盖:直接采纳并重排防抖计数。
        if (referenceStep != null && referenceStep != currentStep) {
            currentStep = referenceStep
            candidateStep = null
            stableSamples = 0
            listener?.invoke(referenceStep)
        }
        // 无论是否采纳,都用当前滤波值重新评估一次,保证与传感器不脱节。
        evaluate(resolveAodRotationStep(mode, filteredX, filteredY))
    }

    private fun evaluate(target: AodOrientationStep?) {
        if (target == null) {
            stableSamples = 0
            return
        }
        if (target != currentStep) {
            if (target != candidateStep) {
                candidateStep = target
                candidateSinceElapsedMs = SystemClock.elapsedRealtime()
                stableSamples = 0
            } else {
                stableSamples = ((SystemClock.elapsedRealtime() - candidateSinceElapsedMs) /
                    STABLE_SAMPLE_MS).toInt().coerceAtMost(Int.MAX_VALUE - 1)
            }
            val required = ((settleMs / STABLE_SAMPLE_MS).toInt()).coerceAtLeast(1)
            if (shouldEmitRotationStep(currentStep, candidateStep, stableSamples, required)) {
                currentStep = target
                candidateStep = null
                stableSamples = 0
                listener?.invoke(target)
            }
        } else {
            candidateStep = null
            stableSamples = 0
        }
        rearmFlush()
    }

    private fun rearmFlush() {
        mainHandler.removeCallbacks(flush)
        mainHandler.postDelayed(flush, settleMs.coerceAtLeast(MIN_FLUSH_DELAY_MS))
    }

    private fun flushPending() {
        val target = candidateStep
        if (target == null || target == currentStep) return
        // 防抖窗口结束仍停留在候选:直接落地。
        currentStep = target
        candidateStep = null
        stableSamples = 0
        listener?.invoke(target)
    }

    private const val FILTER_ALPHA = 0.15f
    private const val STABLE_SAMPLE_MS = 75L
    private const val MIN_FLUSH_DELAY_MS = 120L
    private const val DEFAULT_SETTLE_MS = 1_000L
}