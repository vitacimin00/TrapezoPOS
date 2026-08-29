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

    /**
     * Best-effort deletion of a managed store-logo file.
     *
     * Only a DIRECT child of this installation's canonical `filesDir/store_media` whose name
     * starts with `store_logo_` may be deleted. A crafted path such as
     * `/some/other/place/store_media/store_logo_x.png` — or one using symlinks/`..` to escape —
     * is rejected, because containment is decided by comparing canonical parents, not directory
     * names.
     */
    fun deleteManaged(context: Context, path: String?) {
        if (path.isNullOrBlank()) return
        try {
            val root = File(context.filesDir, "store_media").canonicalFile
            val target = File(path).canonicalFile
            if (isManagedLogo(root, target) && target.exists() && target.isFile) target.delete()
        } catch (_: Exception) { }
    }

    /**
     * Pure path classifier: true only when [target] is a direct managed logo child of the
     * canonical [root]. Both arguments must already be canonical. Exposed for tests.
     */
    fun isManagedLogo(root: File, target: File): Boolean =
        target.parentFile == root && target.name.startsWith("store_logo_")
}
