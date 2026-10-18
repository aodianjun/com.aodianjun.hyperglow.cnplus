package com.eza.hyperglow.root

import java.util.Collections

/**
 * 常开 bootstrap 通道上的每进程一次的表面事实。
 *
 * 「本 ROM 上 AOD 视图与锁屏面板究竟有没有接上歌词表面」是 HyperOS 4 报告首先要回答的问题,
 * 而 attach 那几行本身是诊断门控的:只有表面挂载时日志已经开着它们才存在。这些阶段不依赖
 * 桥接投递的配置,且无条件写出,故普通报告无需复现窗口即可回答该问题。每个名字每进程只报
 * 一次。
 */
internal object SurfaceEvidence {
    private val reported = Collections.synchronizedSet(mutableSetOf<String>())

    fun once(name: String) {
        if (!reported.add(name)) return
        HookLogger.bootstrap(TAG, name)
    }

    private const val TAG = "SurfaceEvidence"
}
