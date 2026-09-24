package com.eza.hyperglow.plugin

import dalvik.system.BaseDexClassLoader
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
 * 其余类插件 dex 里有就用插件的，没有再回退双亲。两个子类的 loadClass
 * 算法相同，受保护成员（findLoadedClass/resolveClass）只能在子类内访问，
 * 故各保留一份实现。
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
 * In-memory 回退路径的子优先实现，语义同 [PluginPathClassLoader]。
 * InMemoryDexClassLoader 是 final 无法继承，改用 API 27+ 的
 * [BaseDexClassLoader] 字节缓冲构造器（minSdk 33 满足），行为等价。
 */
internal class PluginInMemoryDexClassLoader(
    buffers: Array<ByteBuffer>,
    parent: ClassLoader
) : BaseDexClassLoader(buffers, parent) {

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
