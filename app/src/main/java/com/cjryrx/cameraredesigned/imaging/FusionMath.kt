package com.cjryrx.cameraredesigned.imaging

import kotlin.math.abs
import kotlin.math.exp
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sqrt

data class Translation(val x: Int, val y: Int, val error: Float, val samples: Int)

/** Exposure-invariant census alignment. x/y map a reference pixel to the candidate. */
object TranslationAligner {
    fun align(reference: FloatArray, candidate: FloatArray, width: Int, height: Int, radius: Int = 16): Translation {
        return search(reference, candidate, width, height, radius, 2)
    }

    /**
     * Focus brackets change high-frequency structure. Correlate a shared Gaussian scale instead of
     * sharp-pixel census codes. Sigma≈2 suppresses detail that appears/disappears with focus while
     * keeping the position of broad scene boundaries. Registration does not alter fusion inputs.
     */
    fun alignFocus(reference: FloatArray, candidate: FloatArray, width: Int, height: Int, radius: Int = 16): Translation {
        require(width >= 8 && height >= 8 && reference.size == width * height && candidate.size == reference.size)
        var a = reference
        var b = candidate
        repeat(4) {
            a = gaussian(a, width, height)
            b = gaussian(b, width, height)
        }
        require(radius in 0..64)
        var result = Translation(0, 0, Float.POSITIVE_INFINITY, 0)
        val step = if (width * height > 100_000) 3 else 2
        val margin = min(6, min(width, height) / 4)
        for (y in -radius..radius) for (x in -radius..radius) {
            val score = correlation(a, b, width, height, x, y, step, margin)
            if (score.samples >= 32 && (score.error < result.error - 0.000001f ||
                    abs(score.error - result.error) <= 0.000001f && abs(x) + abs(y) < abs(result.x) + abs(result.y))) result = score
        }
        if (result.samples > 0) return result
        // Insufficient common structure is not evidence of successful alignment. Only truly flat
        // frames can safely use zero displacement: there is no detail to double in the first place.
        val flat = reference.maxOrNull()!! - reference.minOrNull()!! < 0.03f &&
            candidate.maxOrNull()!! - candidate.minOrNull()!! < 0.03f
        return result.copy(error = if (flat) 0f else 1f)
    }

    private fun correlation(a: FloatArray, b: FloatArray, width: Int, height: Int, dx: Int, dy: Int, step: Int, margin: Int): Translation {
        var sumA = 0.0; var sumB = 0.0; var sumAA = 0.0; var sumBB = 0.0; var sumAB = 0.0
        var count = 0
        for (y in max(margin, margin - dy) until min(height - margin, height - margin - dy) step step)
            for (x in max(margin, margin - dx) until min(width - margin, width - margin - dx) step step) {
                val left = a[y * width + x].toDouble()
                val right = b[(y + dy) * width + x + dx].toDouble()
                sumA += left; sumB += right; sumAA += left * left; sumBB += right * right; sumAB += left * right
                count++
            }
        if (count < 32) return Translation(dx, dy, 1f, 0)
        val varianceA = sumAA - sumA * sumA / count
        val varianceB = sumBB - sumB * sumB / count
        if (varianceA < count * 0.00001 || varianceB < count * 0.00001) return Translation(dx, dy, 1f, 0)
        val covariance = sumAB - sumA * sumB / count
        val score = (1.0 - covariance / sqrt(varianceA * varianceB)).toFloat().coerceIn(0f, 2f)
        return Translation(dx, dy, score, count)
    }

    private fun search(reference: FloatArray, candidate: FloatArray, width: Int, height: Int, radius: Int, margin: Int): Translation {
        require(width >= 8 && height >= 8 && reference.size == width * height && candidate.size == reference.size)
        require(radius in 0..64)
        val a = census(reference, width, height, margin)
        val b = census(candidate, width, height, margin)
        var best = Translation(0, 0, Float.POSITIVE_INFINITY, 0)
        val step = if (width * height > 100_000) 3 else 2
        for (y in -radius..radius) for (x in -radius..radius) {
            val score = compare(a, b, width, height, x, y, step)
            if (score.samples >= 32 && (score.error < best.error - 0.0001f ||
                    abs(score.error - best.error) <= 0.0001f && abs(x) + abs(y) < abs(best.x) + abs(best.y))) best = score
        }
        // Textureless scenes cannot be registered; retaining the reference position is deterministic.
        return if (best.samples == 0) Translation(0, 0, 0f, 0) else best
    }

    private fun census(image: FloatArray, width: Int, height: Int, margin: Int): IntArray {
        val output = IntArray(image.size) { -1 }
        for (y in margin until height - margin) for (x in margin until width - margin) {
            val center = image[y * width + x]
            var code = 0
            var contrast = 0f
            var bit = 0
            for (dy in -2..2 step 2) for (dx in -2..2 step 2) {
                if (dx != 0 || dy != 0) {
                    val value = image[(y + dy) * width + x + dx]
                    if (value > center) code = code or (1 shl bit)
                    contrast = max(contrast, abs(value - center))
                    bit++
                }
            }
            if (contrast > 0.015f && center in 0.025f..0.975f) output[y * width + x] = code
        }
        return output
    }

    private fun gaussian(image: FloatArray, width: Int, height: Int): FloatArray {
        val temporary = FloatArray(image.size)
        val output = FloatArray(image.size)
        val kernel = intArrayOf(1, 4, 6, 4, 1)
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (k in -2..2) value += image[y * width + (x + k).coerceIn(0, width - 1)] * kernel[k + 2]
            temporary[y * width + x] = value / 16f
        }
        for (y in 0 until height) for (x in 0 until width) {
            var value = 0f
            for (k in -2..2) value += temporary[(y + k).coerceIn(0, height - 1) * width + x] * kernel[k + 2]
            output[y * width + x] = value / 16f
        }
        return output
    }

    private fun compare(a: IntArray, b: IntArray, width: Int, height: Int, dx: Int, dy: Int, step: Int): Translation {
        var mismatch = 0
        var count = 0
        val startX = max(2, 2 - dx)
        val endX = min(width - 2, width - 2 - dx)
        val startY = max(2, 2 - dy)
        val endY = min(height - 2, height - 2 - dy)
        for (y in startY until endY step step) for (x in startX until endX step step) {
            val left = a[y * width + x]
            val right = b[(y + dy) * width + x + dx]
            if (left >= 0 && right >= 0) { mismatch += Integer.bitCount(left xor right); count++ }
        }
        return Translation(dx, dy, if (count == 0) 1f else mismatch.toFloat() / (count * 8), count)
    }
}

object FusionMath {
    fun isClipped(pixel: Int): Boolean {
        val r = (pixel ushr 16 and 255) / 255f
        val g = (pixel ushr 8 and 255) / 255f
        val b = (pixel and 255) / 255f
        return max(r, max(g, b)) >= .995f || ColorKernel.luminance(r, g, b) <= .008f
    }

    /** Weight well exposed pixels; clipped highlights and near-black samples contribute zero. */
    fun exposureWeight(pixel: Int): Float {
        val r = (pixel ushr 16 and 255) / 255f
        val g = (pixel ushr 8 and 255) / 255f
        val b = (pixel and 255) / 255f
        if (isClipped(pixel)) return 0f
        val distance = (r - 0.5f) * (r - 0.5f) + (g - 0.5f) * (g - 0.5f) + (b - 0.5f) * (b - 0.5f)
        return exp(-distance / 0.24f).coerceAtLeast(0.00001f)
    }

    /** Squared Laplacian energy, aggregated at two scales for coherent focus regions. */
    fun focusWeights(pixels: IntArray, width: Int, height: Int): FloatArray {
        require(pixels.size == width * height)
        val energy = FloatArray(pixels.size)
        for (y in 1 until height - 1) for (x in 1 until width - 1) {
            val i = y * width + x
            if ((pixels[i] and pixels[i - 1] and pixels[i + 1] and pixels[i - width] and pixels[i + width]) ushr 24 != 255) continue
            val laplace = 4f * ColorKernel.gray(pixels[i]) - ColorKernel.gray(pixels[i - 1]) -
                ColorKernel.gray(pixels[i + 1]) - ColorKernel.gray(pixels[i - width]) - ColorKernel.gray(pixels[i + width])
            energy[i] = max(0f, laplace * laplace - 0.00012f)
        }
        val fine = smooth(energy, width, height, 3)
        val coarse = smooth(energy, width, height, 7)
        for (i in fine.indices) {
            val contrast = fine[i] * 0.7f + coarse[i] * 0.3f
            // Do not give uninformative/defocused frames an absolute prior weight. Squared
            // low-contrast detail can legitimately score below 1e-6; adding that floor made a
            // blurred frame dilute a sharp one almost equally, increasingly with stack size.
            // The merge already falls back to its reference where every frame has zero evidence.
            fine[i] = contrast * contrast
        }
        return fine
    }

    fun smooth(values: FloatArray, width: Int, height: Int, radius: Int): FloatArray {
        require(values.size == width * height && radius in 0..32)
        val horizontal = FloatArray(values.size)
        val output = FloatArray(values.size)
        val span = radius * 2 + 1
        for (y in 0 until height) {
            var sum = 0f
            for (dx in -radius..radius) sum += values[y * width + dx.coerceIn(0, width - 1)]
            for (x in 0 until width) {
                horizontal[y * width + x] = sum / span
                sum += values[y * width + (x + radius + 1).coerceIn(0, width - 1)] -
                    values[y * width + (x - radius).coerceIn(0, width - 1)]
            }
        }
        for (x in 0 until width) {
            var sum = 0f
            for (dy in -radius..radius) sum += horizontal[dy.coerceIn(0, height - 1) * width + x]
            for (y in 0 until height) {
                output[y * width + x] = (sum / span).coerceAtLeast(0f)
                sum += horizontal[(y + radius + 1).coerceIn(0, height - 1) * width + x] -
                    horizontal[(y - radius).coerceIn(0, height - 1) * width + x]
            }
        }
        return output
    }

    /** Robust brightness ratio for motion tests; clipped pixels do not influence the estimate. */
    internal fun exposureRatio(reference: FloatArray, candidate: FloatArray, width: Int, height: Int, transform: Similarity): Float {
        val ratios = ArrayList<Float>()
        for (y in 0 until height step 3) for (x in 0 until width step 3) {
            val cx = transform.x(x.toDouble(), y.toDouble()).roundToInt()
            val cy = transform.y(x.toDouble(), y.toDouble()).roundToInt()
            if (cx in 0 until width && cy in 0 until height) {
                val a = reference[y * width + x]; val b = candidate[cy * width + cx]
                if (a in 0.1f..0.9f && b in 0.1f..0.9f) ratios.add(ColorKernel.linear(a) / ColorKernel.linear(b))
            }
        }
        if (ratios.size < 16) return 1f
        ratios.sort()
        return ratios[ratios.size / 2].coerceIn(1f / 256f, 256f)
    }
}
