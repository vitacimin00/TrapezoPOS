package com.trapezo.pos.utils

import android.content.Context
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.util.UUID

/** Owns product-photo files copied into Trapezo POS private storage. */
object PhotoStorage {
    private fun directory(context: Context): File = File(context.filesDir, "product_photos").apply { mkdirs() }

    fun createCameraTarget(context: Context): Pair<File, Uri> {
        val file = File(directory(context), "product_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /** Copies gallery/camera content into app-private storage and returns a durable local path. */
    fun importFromUri(context: Context, uri: Uri): String? = try {
        val ext = context.contentResolver.getType(uri)?.substringAfterLast('/')?.takeIf { it.length <= 5 } ?: "jpg"
        val target = File(directory(context), "product_${System.currentTimeMillis()}_${UUID.randomUUID()}.$ext")
        context.contentResolver.openInputStream(uri)?.use { input -> target.outputStream().use { input.copyTo(it) } }
        if (target.exists() && target.length() > 0) target.absolutePath else null
    } catch (_: Exception) { null }
}
