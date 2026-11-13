package com.eza.hyperglow.root

/**
 * 在字段日志里标识一个 class loader。
 *
 * 每个 hook 家族对宿主构建的每个 loader 各安装一次,而宿主最早构建的 loader 通常还看不到
 * 插件 dex——AOD 插件在进程启动之后才挂载。因此单有一行「接缝未解析」无法说明真实 loader
 * 随后解析到了该成员,还是该成员真的消失了。这个 tag 让两种情形在一次日志里即可区分,无需
 * 设备会话。
 */
internal fun loaderTag(classLoader: ClassLoader): String =
    classLoader.javaClass.simpleName + "@" + System.identityHashCode(classLoader).toString(16)
