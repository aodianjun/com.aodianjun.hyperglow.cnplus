package com.eza.hyperglow.plugin

import dalvik.system.InMemoryDexClassLoader
import dalvik.system.PathClassLoader
import java.nio.ByteBuffer

/**
 * 插件子优先 ClassLoader（issue #65 建议 ①）。
 *
 * 默认双亲委派下，插件 dex 自带的类（如 AMLL TTML 插件的
 * kotlin.collections.SetsKt__SetsKt）会被解析成宿主 R8 收紧后的同名类，
 * 跨 ClassLoader 访问抛 IllegalAccessError。上游宿主 dex 不含 kotlin 类所以
 * 无此问题；CN+ 宿主自带 R8 处理过的 Kotlin 运行时，必须在插件侧显式隔离。
 *
 * 必须是 BaseDexClassLoader 的子类而非包装器：ART 解析类引用用的是「定义
 * 该类的 ClassLoader」，只有子类实例亲自 defineClass，插件类对自身 dex 内
 * 其他类的引用才会回到这里重写的子优先 loadClass（包装模式下被包装者仍是
 * 定义者，引用解析依旧双亲优先，隔离不生效）。
 *
 * 策略见 [PluginClassLoaderPolicy]：共享类（平台 + API 契约）仍双亲优先，
 * 其余类插件 dex 里有就用插件的，没有再回退双亲。两条加载路径语义一致，
 * 实现机制不同（in-memory 侧受 SDK 限制只能组合而非继承），见各自注释。
 */
internal class PluginPathClassLoader(dexPath: String, parent: ClassLoader) :
    PathClassLoader(dexPath, parent) {

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (PluginClassLoaderPolicy.isSharedWithHost(name)) {
            return super.loadClass(name, resolve)
        }
        findLoadedClass(name)?.let { return it }
        val loaded = try {
            findClass(name)
        } catch (notInPlugin: ClassNotFoundException) {
            return super.loadClass(name, resolve)
        }
        if (resolve) resolveClass(loaded)
        return loaded
    }
}

/**
 * In-memory 回退路径的子优先实现，语义同 [PluginPathClassLoader]，机制不同。
 *
 * 子优先要求「定义插件类的 ClassLoader 重写过 loadClass」，而两条继承路线都被
 * 堵死：InMemoryDexClassLoader 是 final；BaseDexClassLoader 的 ByteBuffer 构造器
 * 在 compileSdk 37 的 android.jar 中已不可见（仅剩 4 参 String 构造器）。改为组合：
 * 内部持有一个以本加载器为双亲的 [definer]（InMemoryDexClassLoader）负责真正
 * define 插件类；插件类对自身 dex 内其他类的引用经 ART 走 definer 的双亲委派
 * 回到这里，由这里执行子优先策略——效果与继承等价。
 *
 * [definingGuard] 打破委派回环：definer 委派本加载器加载 X 时，若 X 已在「尝试
 * 用插件 dex 定义」的流程中，说明这是回环，直接抛 ClassNotFoundException 让
 * definer 落入自己的 findClass（插件 dex 优先）；插件 dex 没有 X 时回到下面的
 * catch 分支回退宿主双亲。守卫按线程 + 类名追踪，嵌套引用（定义 X 时触发其
 * 父类/签名类加载）不受影响：共享类命中双亲优先，插件类递归子优先，宿主独有
 * 类最终落到宿主双亲。
 */
internal class PluginInMemoryDexClassLoader(
    buffers: Array<ByteBuffer>,
    parent: ClassLoader
) : ClassLoader(parent) {

    private val definer = InMemoryDexClassLoader(buffers, this)
    private val definingGuard = ThreadLocal<MutableSet<String>>()

    @Throws(ClassNotFoundException::class)
    override fun loadClass(name: String, resolve: Boolean): Class<*> {
        if (PluginClassLoaderPolicy.isSharedWithHost(name)) {
            return super.loadClass(name, resolve)
        }
        val inFlight = definingGuard.get()
        if (inFlight != null && name in inFlight) {
            throw ClassNotFoundException(name)
        }
        val names = inFlight ?: mutableSetOf<String>().also { definingGuard.set(it) }
        names.add(name)
        try {
            val loaded = definer.loadClass(name)
            if (resolve) resolveClass(loaded)
            return loaded
        } catch (notInPlugin: ClassNotFoundException) {
            return super.loadClass(name, resolve)
        } finally {
            names.remove(name)
            if (names.isEmpty()) definingGuard.remove()
        }
    }
}
