package com.openminis.app.ui

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import coil.request.ImageRequest
import coil.size.Precision

object DisplayBitmapLimits {

    const val MAX_DISPLAY_EDGE_PX = 4096

    fun ImageRequest.Builder.limitDisplaySize(): ImageRequest.Builder =
        size(MAX_DISPLAY_EDGE_PX, MAX_DISPLAY_EDGE_PX)
            .precision(Precision.INEXACT)

    const val MAX_THUMBNAIL_EDGE_PX = 512

    fun sampleSizeFor(width: Int, height: Int, maxEdge: Int): Int {
        if (width <= 0 || height <= 0 || maxEdge <= 0) return 1
        var sample = 1
        while (width / sample > maxEdge || height / sample > maxEdge) sample *= 2
        return sample
    }

    fun decodeFileBounded(path: String, maxEdge: Int = MAX_DISPLAY_EDGE_PX): Bitmap? = try {
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(path, bounds)
        if (bounds.outWidth <= 0 || bounds.outHeight <= 0) {
            null
        } else {
            val opts = BitmapFactory.Options().apply {
                inSampleSize = sampleSizeFor(bounds.outWidth, bounds.outHeight, maxEdge)
            }
            BitmapFactory.decodeFile(path, opts)
        }
    } catch (_: Throwable) {
        null
    }
}
