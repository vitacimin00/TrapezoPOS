package com.trapezo.pos.utils

import android.content.Context
import android.graphics.Bitmap
import android.net.Uri
import androidx.core.content.FileProvider
import java.io.File
import java.io.FileOutputStream
import java.util.UUID

/** Owns product-photo files copied into Trapezo POS private storage. */
object PhotoStorage {
    private const val TARGET_LONG_EDGE = 1600

    private fun directory(context: Context): File = File(context.filesDir, "product_photos").apply { mkdirs() }

    fun createCameraTarget(context: Context): Pair<File, Uri> {
        val file = File(directory(context), "product_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        val uri = FileProvider.getUriForFile(context, "${context.packageName}.fileprovider", file)
        return file to uri
    }

    /**
     * Copies gallery/camera content into app-private storage with defensive bounds checks
     * and JPEG re-encode, so arbitrary uploads cannot carry a giant decoded bitmap or a
     * malicious container. Returns a durable local path.
     */
    fun importFromUri(context: Context, uri: Uri): String? = try {
        val bitmap = ImageUtil.loadSampled(context, uri, TARGET_LONG_EDGE) ?: return null
        val target = File(directory(context), "product_${System.currentTimeMillis()}_${UUID.randomUUID()}.jpg")
        val ok = FileOutputStream(target).use { out -> bitmap.compress(Bitmap.CompressFormat.JPEG, 85, out) }
        bitmap.recycle()
        target.takeIf { ok && it.exists() && it.length() > 0 }?.absolutePath
    } catch (_: Exception) { null }

    /** Best-effort deletion of a managed photo file (only files under the photos dir). */
    fun deleteManaged(path: String?) {
        if (path.isNullOrBlank()) return
        val f = File(path)
        // Refuse to delete anything outside our private product_photos directory.
        try {
            if (f.exists() && f.name.startsWith("product_") && f.parentFile?.name == "product_photos") f.delete()
        } catch (_: Exception) { }
    }
}

