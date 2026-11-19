package com.cjryrx.cameraredesigned.imaging

import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow
import kotlin.math.roundToInt

/** Parameters: exposure in EV [-5,5]; other controls normalized to [-1,1]. */
class ColorKernel(
    private val adjustments: FilterAdjustments = FilterAdjustments(),
    private val lut: CubeLut? = null,
    intensity: Float = 1f,
) {
    init {
        require(intensity.isFinite()) { "Filter intensity must be finite" }
        require(listOf(adjustments.exposure, adjustments.temperature, adjustments.tint, adjustments.saturation,
            adjustments.vibrance, adjustments.contrast, adjustments.highlights, adjustments.shadows,
            adjustments.sharpness, adjustments.blur).all { it.isFinite() }) { "Filter parameters must be finite" }
    }
    private val strength = intensity.coerceIn(0f, 1f)
    private val lutSample = FloatArray(3)
    private val gain = 2f.pow(adjustments.exposure.coerceIn(-5f, 5f))
    private val temperature = adjustments.temperature.coerceIn(-1f, 1f) * 0.22f
    private val tint = adjustments.tint.coerceIn(-1f, 1f) * 0.15f
    private val identity = lut == null && adjustments == FilterAdjustments() || strength == 0f

    fun apply(pixels: IntArray, count: Int = pixels.size) {
        require(count in 0..pixels.size)
        if (identity) return
        for (i in 0 until count) pixels[i] = apply(pixels[i])
    }

    fun apply(pixel: Int): Int {
        if (identity) return pixel
        val originalR = (pixel ushr 16 and 255) / 255f
        val originalG = (pixel ushr 8 and 255) / 255f
        val originalB = (pixel and 255) / 255f
        var r: Float
        var g: Float
        var b: Float
        // LUT and parameter presets are mutually exclusive by product definition.
        if (lut != null) {
            lut.sample(originalR, originalG, originalB, lutSample)
            r = lutSample[0]
            g = lutSample[1]
            b = lutSample[2]
        } else {
            r = linear(originalR) * gain * (1f + temperature + tint * 0.5f)
            g = linear(originalG) * gain * (1f - tint)
            b = linear(originalB) * gain * (1f - temperature + tint * 0.5f)
            val luma = luminance(r, g, b).coerceIn(0f, 1f)
            val shadow = adjustments.shadows.coerceIn(-1f, 1f) * (1f - luma).pow(3) * 0.32f
            val highlight = adjustments.highlights.coerceIn(-1f, 1f) * luma.pow(3) * 0.35f
            r = srgb((r + shadow + highlight).coerceAtLeast(0f))
            g = srgb((g + shadow + highlight).coerceAtLeast(0f))
            b = srgb((b + shadow + highlight).coerceAtLeast(0f))
            val contrast = 2f.pow(adjustments.contrast.coerceIn(-1f, 1f))
            r = (r - 0.5f) * contrast + 0.5f
            g = (g - 0.5f) * contrast + 0.5f
            b = (b - 0.5f) * contrast + 0.5f
            val gray = luminance(r, g, b)
            val chroma = max(r, max(g, b)) - min(r, min(g, b))
            val saturation = (1f + adjustments.saturation.coerceIn(-1f, 1f)) *
                (1f + adjustments.vibrance.coerceIn(-1f, 1f) * (1f - chroma.coerceIn(0f, 1f)))
            r = gray + (r - gray) * saturation
            g = gray + (g - gray) * saturation
            b = gray + (b - gray) * saturation
        }
        return pack(originalR + (r - originalR) * strength, originalG + (g - originalG) * strength,
            originalB + (b - originalB) * strength, pixel ushr 24)
    }

    companion object {
        fun linear(value: Float): Float = if (value <= 0.04045f) value / 12.92f else ((value + 0.055f) / 1.055f).pow(2.4f)
        fun srgb(value: Float): Float = if (value <= 0.0031308f) value * 12.92f else 1.055f * value.pow(1f / 2.4f) - 0.055f
        fun luminance(r: Float, g: Float, b: Float): Float = 0.2126f * r + 0.7152f * g + 0.0722f * b
        fun gray(pixel: Int): Float = luminance((pixel ushr 16 and 255) / 255f, (pixel ushr 8 and 255) / 255f, (pixel and 255) / 255f)
        fun pack(r: Float, g: Float, b: Float, alpha: Int = 255): Int =
            (alpha shl 24) or ((r.coerceIn(0f, 1f) * 255).roundToInt() shl 16) or
                ((g.coerceIn(0f, 1f) * 255).roundToInt() shl 8) or (b.coerceIn(0f, 1f) * 255).roundToInt()

        /** Separable box blur with edge replication; scratch is reusable and O(stripe size). */
        fun blur(source: IntArray, width: Int, height: Int, radius: Int, destination: IntArray, scratch: IntArray) {
            require(width > 0 && height > 0 && source.size >= width * height && destination.size >= width * height && scratch.size >= width * height)
            require(radius in 0..32)
            if (radius == 0) { source.copyInto(destination, endIndex = width * height); return }
            val span = radius * 2 + 1
            for (y in 0 until height) {
                var r = 0; var g = 0; var b = 0
                for (dx in -radius..radius) {
                    val p = source[y * width + dx.coerceIn(0, width - 1)]
                    r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255
                }
                for (x in 0 until width) {
                    scratch[y * width + x] = (0xff shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                    val remove = source[y * width + (x - radius).coerceIn(0, width - 1)]
                    val add = source[y * width + (x + radius + 1).coerceIn(0, width - 1)]
                    r += (add ushr 16 and 255) - (remove ushr 16 and 255)
                    g += (add ushr 8 and 255) - (remove ushr 8 and 255)
                    b += (add and 255) - (remove and 255)
                }
            }
            for (x in 0 until width) {
                var r = 0; var g = 0; var b = 0
                for (dy in -radius..radius) {
                    val p = scratch[dy.coerceIn(0, height - 1) * width + x]
                    r += p ushr 16 and 255; g += p ushr 8 and 255; b += p and 255
                }
                for (y in 0 until height) {
                    destination[y * width + x] = (0xff shl 24) or ((r / span) shl 16) or ((g / span) shl 8) or (b / span)
                    val remove = scratch[(y - radius).coerceIn(0, height - 1) * width + x]
                    val add = scratch[(y + radius + 1).coerceIn(0, height - 1) * width + x]
                    r += (add ushr 16 and 255) - (remove ushr 16 and 255)
                    g += (add ushr 8 and 255) - (remove ushr 8 and 255)
                    b += (add and 255) - (remove and 255)
                }
            }
        }

        fun sharpen(original: Int, softened: Int, amount: Float): Int {
            fun channel(shift: Int): Int {
                val a = original ushr shift and 255
                val b = softened ushr shift and 255
                val delta = a - b
                // A modest noise threshold avoids emphasizing JPEG noise in flat areas.
                return (a + if (abs(delta) > 2) delta * amount else 0f).roundToInt().coerceIn(0, 255)
            }
            return (original and (0xff shl 24)) or (channel(16) shl 16) or (channel(8) shl 8) or channel(0)
        }
    }
}
