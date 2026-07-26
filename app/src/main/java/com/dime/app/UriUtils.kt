package com.dime.app

import android.content.Context
import android.net.Uri
import java.io.File
import java.io.FileOutputStream

object UriUtils {
    fun uriToTempFile(ctx: Context, uriStr: String): File? {
        return try {
            val uri = Uri.parse(uriStr)
            val input = ctx.contentResolver.openInputStream(uri) ?: return null
            val tmp = File(ctx.cacheDir, "_portada_${System.currentTimeMillis()}.tmp")
            FileOutputStream(tmp).use { out ->
                val buf = ByteArray(8192)
                var r: Int
                while (true) {
                    r = input.read(buf)
                    if (r <= 0) break
                    out.write(buf, 0, r)
                }
            }
            input.close()
            tmp
        } catch (e: Exception) {
            e.printStackTrace()
            null
        }
    }

    fun guessMimeType(name: String): String? {
        val n = name.lowercase()
        return when {
            n.endsWith(".jpg") || n.endsWith(".jpeg") -> "image/jpeg"
            n.endsWith(".png") -> "image/png"
            n.endsWith(".webp") -> "image/webp"
            n.endsWith(".gif") -> "image/gif"
            else -> null
        }
    }
}
