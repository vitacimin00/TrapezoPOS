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

    /**
     * Best-effort deletion of a managed product-photo file.
     *
     * Only a DIRECT child of this installation's canonical `filesDir/product_photos` whose name
     * starts with `product_` may be deleted. A crafted path such as
     * `/some/other/location/product_photos/product_x.jpg` — or one using symlinks/`..` to escape —
     * is rejected, because containment is decided by comparing canonical parents, not directory
     * names. Mirrors [StoreLogoStorage.deleteManaged].
     */
    fun deleteManaged(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val root = File(context.filesDir, "product_photos").canonicalFile
            val target = File(path).canonicalFile
            if (isManagedPhoto(root, target) && target.exists() && target.isFile) target.delete()
        } catch (_: Exception) { }
    }

    /**
     * Pure path classifier: true only when [target] is a direct managed photo child of the
     * canonical [root]. Both arguments must already be canonical. Exposed for tests.
     */
    fun isManagedPhoto(root: File, target: File): Boolean =
        target.parentFile == root && target.name.startsWith("product_")
}

