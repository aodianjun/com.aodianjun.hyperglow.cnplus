package com.eza.hyperglow.bridge

import android.content.ContentProvider
import android.content.ContentValues
import android.database.Cursor
import android.net.Uri
import android.os.Bundle
import android.os.ParcelFileDescriptor
import com.eza.hyperglow.AppLog
import com.eza.hyperglow.root.aod.CustomFontContract
import java.io.File
import java.io.FileNotFoundException

class CustomFontProvider : ContentProvider() {

    override fun onCreate(): Boolean = true

    override fun openFile(uri: Uri, mode: String): ParcelFileDescriptor {
        val host = context ?: throw FileNotFoundException("no context")
        if (!CallerValidator.isSystemUi(host)) {
            AppLog.w(TAG, "Rejected font read")
            throw FileNotFoundException("unauthorized")
        }
        if (mode != "r") throw FileNotFoundException("read-only")
        val file = fontFile()
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
        val file = fontFile()
        if (!file.isFile) return Bundle.EMPTY
        return Bundle().apply {
            putString(CustomFontContract.EXTRA_VERSION, "${file.lastModified()}_${file.length()}")
        }
    }

    private fun fontFile(): File {
        val host = context ?: return File("")
        return File(host.filesDir, CustomFontContract.FONT_RELATIVE_PATH)
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
