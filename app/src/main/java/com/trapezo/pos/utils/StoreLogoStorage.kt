package com.trapezo.pos.utils

import android.content.Context
import android.net.Uri
import java.io.File
import java.util.UUID

/** Copies a selected store logo into Trapezo POS private app storage. */
object StoreLogoStorage {
    private fun directory(context: Context): File = File(context.filesDir, "store_media").apply { mkdirs() }

    fun importFromUri(context: Context, uri: Uri): String? = try {
        val extension = context.contentResolver.getType(uri)
            ?.substringAfterLast('/')
            ?.takeIf { it.length in 2..5 }
            ?: "png"
        val target = File(directory(context), "store_logo_${System.currentTimeMillis()}_${UUID.randomUUID()}.$extension")
        context.contentResolver.openInputStream(uri)?.use { input ->
            target.outputStream().use { output -> input.copyTo(output) }
        }
        target.takeIf { it.exists() && it.length() > 0 }?.absolutePath
    } catch (_: Exception) {
        null
    }
}
