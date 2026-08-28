package com.trapezo.pos.utils

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri

/**
 * Defensive image decode/resize for untrusted user content.
 * Dimensions are inspected before allocating a full bitmap, so a tiny compressed
 * file that decodes to an enormous bitmap is rejected rather than exhausting memory.
 */
object ImageUtil {
    const val MAX_DIMENSION = 16_384
    const val MAX_PIXELS = 32_000_000L // 32 megapixels

    private fun bounds(context: Context, uri: Uri): BitmapFactory.Options? {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        val ok = context.contentResolver.openInputStream(uri)?.use { decodeBoundInputStream(it, opts) } ?: false
        return if (ok) opts else null
    }

    /** Reads only the dimensions (no full decode). */
    fun decodeBounds(context: Context, uri: Uri): Pair<Int, Int>? {
        val opts = bounds(context, uri) ?: return null
        if (opts.outWidth <= 0 || opts.outHeight <= 0) return null
        return opts.outWidth to opts.outHeight
    }

    /** Decodes, rejecting unreasonable dimensions and downsampling to targetLongEdge. */
    fun loadSampled(context: Context, uri: Uri, targetLongEdge: Int): Bitmap? {
        val opts = bounds(context, uri) ?: return null
        val w = opts.outWidth
        val h = opts.outHeight
        if (w <= 0 || h <= 0) return null
        if (w > MAX_DIMENSION || h > MAX_DIMENSION) return null
        if (w.toLong() * h > MAX_PIXELS) return null

        var sample = 1
        while (maxOf(w / sample, h / sample) > targetLongEdge || (w / sample).toLong() * (h / sample) > MAX_PIXELS) {
            sample *= 2
        }
        val decode = BitmapFactory.Options().apply { inSampleSize = sample }
        return context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, decode) }
    }

    private fun decodeBoundInputStream(input: java.io.InputStream, opts: BitmapFactory.Options): Boolean {
        try {
            BitmapFactory.decodeStream(input, null, opts)
            return opts.outWidth > 0
        } catch (_: Exception) {
            return false
        }
    }
}
