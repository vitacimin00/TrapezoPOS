package com.trapezo.pos.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Copies a selected store logo into Trapezo POS private app storage (defensively). */
object StoreLogoStorage {
    private const val TARGET_LONG_EDGE = 512

    private fun directory(context: Context): File = File(context.filesDir, "store_media").apply { mkdirs() }

    /**
     * Decodes with bounds checking, downsizes, and re-encodes to PNG (preserving
     * transparency) so untrusted images cannot carry huge bitmaps or metadata.
     */
    fun importFromUri(context: Context, uri: Uri): String? = try {
        val bitmap = ImageUtil.loadSampled(context, uri, TARGET_LONG_EDGE) ?: return null
        val target = File(directory(context), "store_logo_${System.currentTimeMillis()}_${UUID.randomUUID()}.png")
        val ok = FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.PNG, 100, out) }
        bitmap.recycle()
        target.takeIf { ok && it.exists() && it.length() > 0 }?.absolutePath
    } catch (_: Exception) {
        null
    }
}
