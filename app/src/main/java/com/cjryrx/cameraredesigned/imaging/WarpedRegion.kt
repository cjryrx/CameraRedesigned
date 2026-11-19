package com.cjryrx.cameraredesigned.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/** Inverse warp into a reference rectangle. Invalid pixels have alpha zero; valid JPEG pixels are
 * opaque. Decode source tiles on demand so roll cannot expand one stripe into a full-frame bitmap. */
internal fun readWarpedRegion(
    decoder: BitmapRegionDecoder, region: Rect, t: Similarity, options: BitmapFactory.Options,
): IntArray {
    val width = region.width()
    val height = region.height()
    val pixels = IntArray(width * height)
    val sourceBounds = Rect(0, 0, decoder.width, decoder.height)
    if (t.a == 1.0 && t.b == 0.0 && t.tx == t.tx.roundToInt().toDouble() && t.ty == t.ty.roundToInt().toDouble()) {
        val shifted = Rect(region).apply { offset(t.tx.toInt(), t.ty.toInt()) }
        val source = Rect(shifted)
        if (!source.intersect(sourceBounds)) return pixels
        val bitmap = checkNotNull(decoder.decodeRegion(source, options)) { "Cannot decode source stripe" }
        try {
            val offset = (source.top - shifted.top) * width + source.left - shifted.left
            bitmap.getPixels(pixels, offset, width, 0, 0, source.width(), source.height())
        } finally { bitmap.recycle() }
        return pixels
    }

    val stripe = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
    try {
        val canvas = Canvas(stripe)
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val inv = 1.0 / (t.a*t.a + t.b*t.b)
        // Convert integer-centre coordinates to the corner coordinates expected by Canvas.
        val tx = t.tx + (1 - t.a + t.b)*.5
        val ty = t.ty + (1 - t.a - t.b)*.5
        val tileWidth = (height / max(abs(t.b), .001)).toInt().coerceIn(128, max(width,128))
        val matrix = Matrix()
        for (left in 0 until width step tileWidth) {
            val right = min(width, left + tileWidth)
            val x0 = (region.left + left).toDouble(); val x1 = (region.left + right).toDouble()
            val y0 = region.top.toDouble(); val y1 = region.bottom.toDouble()
            val minX = t.a * x0 + min(-t.b*y0, -t.b*y1) + tx
            val maxX = t.a * x1 + max(-t.b*y0, -t.b*y1) + tx
            val minY = min(t.b*x0,t.b*x1) + t.a*y0 + ty
            val maxY = max(t.b*x0,t.b*x1) + t.a*y1 + ty
            val source = Rect(floor(minX).toInt()-1, floor(minY).toInt()-1, ceil(maxX).toInt()+1, ceil(maxY).toInt()+1)
            if (!source.intersect(sourceBounds)) continue
            val tile = checkNotNull(decoder.decodeRegion(source, options)) { "Cannot decode transformed source tile" }
            try {
                val ox = source.left - tx; val oy = source.top - ty
                matrix.setValues(floatArrayOf(
                    (t.a*inv).toFloat(), (t.b*inv).toFloat(), ((t.a*ox+t.b*oy)*inv-region.left).toFloat(),
                    (-t.b*inv).toFloat(), (t.a*inv).toFloat(), ((-t.b*ox+t.a*oy)*inv-region.top).toFloat(),
                    0f, 0f, 1f,
                ))
                canvas.save()
                canvas.clipRect(left, 0, right, height)
                canvas.drawBitmap(tile, matrix, paint)
                canvas.restore()
            } finally { tile.recycle() }
        }
        stripe.getPixels(pixels, 0, width, 0, 0, width, height)
        return pixels
    } finally { stripe.recycle() }
}
