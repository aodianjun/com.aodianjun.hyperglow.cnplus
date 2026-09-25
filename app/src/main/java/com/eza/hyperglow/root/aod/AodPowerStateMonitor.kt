package com.eza.hyperglow.root.aod

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.os.BatteryManager
import android.os.Handler
import android.os.Looper
import android.os.PowerManager
import com.eza.hyperglow.root.HookLogger

/**
 * 省电判定(纯谓词,可单测):未充电且电量 ≤ [AOD_POWER_SAVER_BATTERY_LEVEL_PCT]%,
 * 或设备热状态 ≥ [AOD_POWER_SAVER_THERMAL_STATUS]
 * (= PowerManager.THERMAL_STATUS_MODERATE)。两个条件任一命中即降帧;
 * 充电时电量低不算(插电功耗不是瓶颈)。
 */
internal fun isAodPowerSaverActive(
    batteryPercent: Int?,
    charging: Boolean?,
    thermalStatus: Int?
): Boolean =
    (batteryPercent != null && batteryPercent in 0..AOD_POWER_SAVER_BATTERY_LEVEL_PCT &&
        charging == false) ||
        (thermalStatus != null && thermalStatus >= AOD_POWER_SAVER_THERMAL_STATUS)

internal const val AOD_POWER_SAVER_BATTERY_LEVEL_PCT = 15
internal const val AOD_POWER_SAVER_THERMAL_STATUS = 2 // PowerManager.THERMAL_STATUS_MODERATE
private const val THERMAL_RECHECK_MS = 30_000L

/**
 * SystemUI 侧电量/温控状态缓存,驱动 AOD 歌词画布的省电降帧。
 *
 * 查询策略遵循热路径规则:attach 时读一次粘性电量广播与当前热状态,之后全部由系统推送
 * (ACTION_BATTERY_CHANGED 广播 + OnThermalStatusChangedListener)更新 volatile 缓存,
 * 帧循环只做一次 volatile 布尔读,绝不在 onDraw/帧回调里查询系统服务。
 *
 * 生命周期镜像 [AodOrientationMonitor]:surface 构建时 [attach],卸载时 [detach];
 * detach 注销广播接收器与热状态监听,不留泄漏。
 */
internal object AodPowerStateMonitor {
    private const val TAG = "AodPowerStateMonitor"

    @Volatile private var batteryPercent: Int? = null
    @Volatile private var charging: Boolean? = null
    @Volatile private var thermalStatus: Int? = null
    private var attached = false
    private var appContext: Context? = null
    private var receiver: BroadcastReceiver? = null
    private val mainHandler = Handler(Looper.getMainLooper())

    // 热状态用 30s 有界重读而非推送监听:低频后台读一次 currentThermalStatus 的开销
    // 可忽略,而电量广播只覆盖电池事件、不含温控事件。显式 Runnable 类型:
    // runnable 自引用 postDelayed,不标注会让类型推断递归。
    private val thermalRecheck: Runnable = Runnable {
        val app = appContext
        if (app != null) {
            val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
            if (powerManager != null) thermalStatus = powerManager.currentThermalStatus
            mainHandler.postDelayed(thermalRecheck, THERMAL_RECHECK_MS)
        }
    }

    @Synchronized
    fun attach(context: Context) {
        if (attached) return
        val app = context.applicationContext
        val battery = readBatteryState(app)
        batteryPercent = battery.first
        charging = battery.second
        val powerManager = app.getSystemService(Context.POWER_SERVICE) as? PowerManager
        if (powerManager == null) {
            HookLogger.w(TAG, "PowerManager unavailable; thermal saver disabled")
        } else {
            thermalStatus = powerManager.currentThermalStatus
            mainHandler.postDelayed(thermalRecheck, THERMAL_RECHECK_MS)
        }
        val batteryReceiver = object : BroadcastReceiver() {
            override fun onReceive(context: Context?, intent: Intent?) {
                intent ?: return
                batteryPercent = readBatteryPercent(intent)
                charging = readCharging(intent)
            }
        }
        receiver = batteryReceiver
        // ACTION_BATTERY_CHANGED 是受保护的系统广播:Android 14 的 RECEIVER_EXPORTED/
        // NOT_EXPORTED 强制只针对非系统广播,这里不需要指定导出标志。
        app.registerReceiver(batteryReceiver, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
        appContext = app
        attached = true
        HookLogger.i(TAG, "Power state monitor attached saver=${isPowerSaverActive()}")
    }

    @Synchronized
    fun detach() {
        if (!attached) return
        val app = appContext
        val batteryReceiver = receiver
        if (app != null && batteryReceiver != null) {
            runCatching { app.unregisterReceiver(batteryReceiver) }
        }
        mainHandler.removeCallbacks(thermalRecheck)
        receiver = null
        appContext = null
        batteryPercent = null
        charging = null
        thermalStatus = null
        attached = false
        HookLogger.i(TAG, "Power state monitor detached")
    }

    fun isPowerSaverActive(): Boolean =
        isAodPowerSaverActive(batteryPercent, charging, thermalStatus)

    private fun readBatteryState(context: Context): Pair<Int?, Boolean?> {
        val intent = context.registerReceiver(null, IntentFilter(Intent.ACTION_BATTERY_CHANGED))
            ?: return null to null
        return readBatteryPercent(intent) to readCharging(intent)
    }

    private fun readBatteryPercent(intent: Intent): Int? {
        val level = intent.getIntExtra(BatteryManager.EXTRA_LEVEL, -1)
        val scale = intent.getIntExtra(BatteryManager.EXTRA_SCALE, -1)
        if (level < 0 || scale <= 0) return null
        return (level * 100) / scale
    }

    private fun readCharging(intent: Intent): Boolean =
        intent.getIntExtra(BatteryManager.EXTRA_PLUGGED, 0) != 0
}
