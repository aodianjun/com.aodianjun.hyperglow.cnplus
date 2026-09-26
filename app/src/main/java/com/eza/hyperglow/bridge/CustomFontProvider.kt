package com.eza.hyperglow.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.customization.CustomFontContract
import java.io.File
import java.io.FileNotFoundException

/**
 * 把已导入的自定义字体以只读方式交给 SystemUI 进程。
 *
 * 应用私有目录是 `0700`,SystemUI 既读不到字体本体,也写不进应用 cacheDir,
 * 因此取流与版本查询都必须走这里;字体按 id 寻址(`/font/<id>`),历史单槽 `/font/custom`。
 */
class CustomFontProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val host = context ?: throw FileNotFoundException("no context")
        if (!CallerValidator.isSystemUi(host)) {
            AppLog.w(TAG, "Rejected font read")
            throw FileNotFoundException("unauthorized")
        }
        if (mode != "r") throw FileNotFoundException("read-only")
        val file = fontFile(uri.lastPathSegment)
        if (!file.isFile) throw FileNotFoundException("not imported")
        return ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY)
    }

    override fun call(method: String, arg: String?, extras: Bundle?): Bundle {
        val host = context ?: return Bundle.EMPTY
        if (!CallerValidator.isSystemUi(host)) {
            AppLog.w(TAG, "Rejected font version query")
            return Bundle.EMPTY
        }
        if (method != CustomFontContract.METHOD_VERSION) return Bundle.EMPTY
        val file = fontFile(arg)
        if (!file.isFile) return Bundle.EMPTY
        return Bundle().apply {
            putString(CustomFontContract.EXTRA_VERSION, "${file.lastModified()}_${file.length()}")
        }
    }

    /**
     * id 一律过 [CustomFontContract.sanitizeFontId];空/非法 id 回落到历史单槽,
     * 避免 `../` 之类的路径片段被拼进私有目录。
     */
    private fun fontFile(requestedId: String?): File {
        val host = context ?: return File("")
        val id = CustomFontContract.sanitizeFontId(requestedId)
            ?: CustomFontContract.FAMILY_CUSTOM
        return CustomFontContract.fontFile(host.filesDir, id)
    }

    override fun query(uri: Uri, projection: Array<out String>?, selection: String?,
        selectionArgs: Array<out String>?, sortOrder: String?): Cursor? = null
    override fun getType(uri: Uri): String? = null
    override fun insert(uri: Uri, values: ContentValues?): Uri? = null
    override fun delete(uri: Uri, selection: String?, selectionArgs: Array<out String>?): Int = 0
    override fun update(uri: Uri, values: ContentValues?, selection: String?, selectionArgs: Array<out String>?): Int = 0

    companion object {
        private const val TAG = "CustomFontProvider"
    }
}
