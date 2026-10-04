package com.eza.hyperglow.ui

import android.content.Context
import android.os.Build
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.eza.hyperglow.AppLog

/**
 * MIUI 长截屏(截长屏)在 CN+ 上失效的兼容层。
 *
 * 现象与根因(Redmi K80 Pro / HyperOS 3 实测,反编译 miui-framework.jar 佐证):
 * MIUI 在 App 进程内查找「主滚动视图」时,对类名**精确等于**
 * `androidx.compose.ui.platform.AndroidComposeView` 的 View 直接判为不可滚动 ——
 * `miui.util.LongScreenshotUtils$ContentPort.canScrollVertically(View)`:
 *
 * ```java
 * if (!"androidx.compose.ui.platform.AndroidComposeView".equals(view.getClass().getName())) {
 *     return view.canScrollVertically(1) || atWhiteList(view);
 * }
 * // 此处打一行 "can not run invoke canScrollVertically on background thread" 后:
 * return false;
 * ```
 *
 * 于是 `findScrollView()` 走遍整棵视图树都选不出可滚动视图,长截屏退化成「只截当前一屏」
 * (真机日志:`scrolledY == 0 isEnd:true` / `may reach end, count: 1`)。
 * 混淆过的 Compose 应用因为类名被 R8 改名而绕开了这条判断,所以同类应用长截屏正常;
 * CN+ 的 Compose 宿主保留原名,正好命中。对照实验:GKD(同为 Compose)正常,CN+ 失败;
 * 手动滑动与帧耗时(10ms/帧)均正常,排除滚动能力与性能因素。
 *
 * 兼容做法:在窗口内容层放一个透明代理 View(位于 Compose 宿主**之下**,不参与真实触摸分发):
 *  1. 向 MIUI 自报 `canScrollVertically(1) == true`,让它选中本视图作为主滚动视图;
 *  2. MIUI 会把模拟拖拽的 [MotionEvent] **直接** dispatch 到选中的视图(不走窗口),
 *     这里原样转发给 Compose 宿主 —— 滚动行为与 MIUI 驱动其他 Compose 应用时完全一致;
 *  3. MIUI 用 `getScrollY()` 的增量判断「是否还有可滚内容」(增量为 0 即判定到底并结束采集),
 *     这里用转发出去的拖拽位移累计值代替(见 [LongScreenshotDragAccumulator])。
 *
 * 仅在小米/HyperOS 设备安装(见 [installLongScreenshotProxy]):长截屏是 MIUI 自己的实现,
 * 其他系统不装,避免影响 AOSP ScrollCapture 的目标选择。
 */
internal class LongScreenshotScrollProxyView(context: Context) : View(context) {
    /** MIUI 假触摸的转发目标(Compose 宿主视图)。 */
    var touchTarget: View? = null

    private val drag = LongScreenshotDragAccumulator()

    init {
        // 只作为 MIUI 的滚动目标存在:不参与无障碍、不绘制、不抢焦点。
        importantForAccessibility = View.IMPORTANT_FOR_ACCESSIBILITY_NO
        isFocusable = false
        isClickable = false
        contentDescription = null
    }

    /** MIUI 的 findScrollView 只认这一项:代理恒报可垂直滚动。 */
    override fun canScrollVertically(direction: Int): Boolean = true

    /**
     * MIUI 以 `getScrollY()` 的增量判断是否还有可滚内容;这里把累计拖拽位移写进 mScrollY。
     *
     * `View.getScrollY()` 在 SDK 里是 final(CI 实证),不能覆盖,只能通过公开的 `scrollTo()`
     * 写入 —— 代理没有子视图与背景,位移不产生任何绘制效果。
     */
    private fun syncScrollOffset() {
        scrollTo(0, drag.scrollY)
    }

    /**
     * 返回 false:MIUI 不检查返回值;而真实触摸若落到这里也不应被吞掉。
     * 代理位于 Compose 宿主之下,正常触摸由宿主先消费,不会走到这里。
     */
    override fun dispatchTouchEvent(event: MotionEvent): Boolean {
        val target = touchTarget
        if (target != null) {
            when (event.actionMasked) {
                MotionEvent.ACTION_DOWN -> drag.onDown(event.y)
                MotionEvent.ACTION_MOVE -> drag.onMove(event.y)
                MotionEvent.ACTION_UP, MotionEvent.ACTION_CANCEL -> drag.onEnd()
            }
            syncScrollOffset()
            // 代理与宿主同处内容层,坐标系通常一致;仍按屏幕位置差偏移一次以防布局差异。
            val dx = (left - target.left).toFloat()
            val dy = (top - target.top).toFloat()
            event.offsetLocation(dx, dy)
            try {
                target.dispatchTouchEvent(event)
            } finally {
                event.offsetLocation(-dx, -dy)
            }
        }
        return false
    }
}

/**
 * 在 MIUI/HyperOS 上安装长截屏代理;其他系统直接跳过。
 *
 * 代理挂在 `android.R.id.content` 的**第 0 个子视图**(Compose 宿主之下):
 *  - 真实触摸由 Compose 宿主先消费,代理拿不到,不影响交互;
 *  - MIUI 的 findScrollView 自后向前遍历子视图,Compose 子树被拒后必然选中代理。
 */
internal fun ComponentActivity.installLongScreenshotProxy() {
    if (!Build.MANUFACTURER.equals("Xiaomi", ignoreCase = true)) return
    val content = findViewById<ViewGroup>(android.R.id.content) ?: return
    if (content.getChildAt(0) is LongScreenshotScrollProxyView) return
    val host = (content.childCount - 1 downTo 0)
        .map { content.getChildAt(it) }
        .firstOrNull { it !is LongScreenshotScrollProxyView }
        ?: return
    val proxy = LongScreenshotScrollProxyView(this).apply { touchTarget = host }
    content.addView(proxy, 0, ViewGroup.LayoutParams(MATCH_PARENT, MATCH_PARENT))
    AppLog.i(TAG, "long screenshot proxy installed host=${host.javaClass.name}")
}

private const val TAG = "MainActivity"
private const val MATCH_PARENT = ViewGroup.LayoutParams.MATCH_PARENT
