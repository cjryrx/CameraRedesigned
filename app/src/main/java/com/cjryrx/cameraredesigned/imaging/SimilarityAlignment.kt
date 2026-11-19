package com.cjryrx.cameraredesigned.imaging

import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/** Maps reference pixel centres (integer indices) into the candidate. */
internal data class Similarity(val a: Double = 1.0, val b: Double = 0.0, val tx: Double = 0.0, val ty: Double = 0.0) {
    fun x(x: Double, y: Double): Double = a * x - b * y + tx
    fun y(x: Double, y: Double): Double = b * x + a * y + ty

    fun fullResolution(sample: Int): Similarity {
        // JPEG sampling represents the centres of blocks of original pixels.
        val offset = (sample - 1) * .5
        return copy(tx = tx * sample + offset * (1 - a + b), ty = ty * sample + offset * (1 - a - b))
    }

    fun plausible() = hypot(a, b) in .90..1.10 && abs(b) <= .085
}

internal object SimilarityAlignment {
    private data class Score(val error: Double, val supported: Int)

    /** Optimize centred scale, roll and displacement using four independently normalized areas.
     * Sampling is sparse and deterministic. Local normalization tolerates bracket brightness changes
     * and comparing broad structures reduces bias towards whichever depth plane is currently sharp. */
    fun estimate(reference: FloatArray, candidate: FloatArray, width: Int, height: Int, focus: Boolean): Similarity {
        val initial = if (focus) TranslationAligner.alignFocus(reference, candidate, width, height, 12)
            else TranslationAligner.align(reference, candidate, width, height, 12)
        val translation = Similarity(tx = initial.x.toDouble(), ty = initial.y.toDouble())
        if (initial.samples == 0) {
            require(reference.maxOrNull()!! - reference.minOrNull()!! < .03f &&
                candidate.maxOrNull()!! - candidate.minOrNull()!! < .03f) { "Insufficient common structure for alignment" }
            return Similarity()
        }
        val ref = FusionMath.smooth(reference, width, height, if (focus) 3 else 2)
        val image = FusionMath.smooth(candidate, width, height, if (focus) 3 else 2)
        val cx = (width - 1) * .5
        val cy = (height - 1) * .5
        fun centred(a: Double, b: Double, dx: Double, dy: Double) =
            Similarity(a, b, dx + cx * (1 - a) + b * cy, dy + cy * (1 - a) - b * cx)
        // Each quadrant accumulates count, sums of X/Y, sums of their squares, and sum of XY.
        // Reuse the workspace across hypotheses; thumbnail pixel loops allocate no objects.
        val sums = DoubleArray(4 * 6)
        fun score(t: Similarity): Score {
            sums.fill(0.0)
            val marginX = max(2, width / 12)
            val marginY = max(2, height / 12)
            val stride = max(1, max(width, height) / 56)
            for (y in marginY until height - marginY step stride) {
                val row = y * width
                for (x in marginX + (y / stride % stride) until width - marginX step stride) {
                    val sx = t.x(x.toDouble(), y.toDouble())
                    val sy = t.y(x.toDouble(), y.toDouble())
                    if (sx < 0 || sy < 0 || sx >= width - 1 || sy >= height - 1) continue
                    val ix = sx.toInt()
                    val iy = sy.toInt()
                    val u = sx - ix
                    val v = sy - iy
                    val p = iy * width + ix
                    val sample = (1 - v) * ((1 - u) * image[p] + u * image[p + 1]) +
                        v * ((1 - u) * image[p + width] + u * image[p + width + 1])
                    val value = ref[row + x].toDouble()
                    if (!focus && (sample < .015 || sample > .985 || value < .015 || value > .985)) continue
                    val base = (if (y > cy) 12 else 0) + (if (x > cx) 6 else 0)
                    sums[base]++
                    sums[base + 1] += value
                    sums[base + 2] += sample
                    sums[base + 3] += value * value
                    sums[base + 4] += sample * sample
                    sums[base + 5] += sample * value
                }
            }
            var total = 0.0; var supported = 0
            for (base in 0 until sums.size step 6) {
                val n = sums[base]
                if (n < 16) { total += .65; continue }
                val sumRef = sums[base + 1]
                val sumCandidate = sums[base + 2]
                val varianceRef = sums[base + 3] - sumRef * sumRef / n
                val varianceCandidate = sums[base + 4] - sumCandidate * sumCandidate / n
                if (min(varianceRef, varianceCandidate) < n * .00005) { total += .65; continue }
                val covariance = sums[base + 5] - sumRef * sumCandidate / n
                val error = 1 - covariance / sqrt(varianceRef * varianceCandidate)
                if (error < .36) supported++
                total += error.coerceIn(0.0, .65)
            }
            // Missing texture cannot provide evidence for a different magnification or roll.
            return Score(total / 4, supported)
        }
        var best = translation
        val baseline = score(translation)
        if (baseline.supported == 4 && baseline.error < 1e-7) return translation
        var bestScore = if (baseline.supported >= 3) baseline.error else Double.POSITIVE_INFINITY
        fun consider(t: Similarity): Boolean {
            if (!t.plausible() || abs(t.x(cx,cy) - cx) > 16 || abs(t.y(cx,cy) - cy) > 16) return false
            val measured = score(t)
            if (measured.supported < 3 || measured.error + 1e-7 >= bestScore) return false
            best = t
            bestScore = measured.error
            return true
        }
        if (baseline.error > .06 || baseline.supported < 3) {
            // Clipping or a repeating fine texture can defeat the global census translation.
            for (dy in -12..12 step 4) for (dx in -12..12 step 4) {
                consider(Similarity(tx = dx.toDouble(), ty = dy.toDouble()))
            }
        }
        val seedX = best.tx; val seedY = best.ty
        for (scale in -2..2) for (roll in -2..2) {
            consider(centred(1 + scale * .04, roll * .038, seedX, seedY))
        }
        var step = 2.0
        val arm = max(cx,cy).coerceAtLeast(1.0)
        repeat(6) {
            for (iteration in 0 until 6) {
                val start = best
                val dx = start.x(cx,cy) - cx; val dy = start.y(cx,cy) - cy
                for (sign in -1..1 step 2) {
                    val d = sign * step
                    consider(centred(start.a + d/arm, start.b, dx, dy))
                    consider(centred(start.a, start.b + d/arm, dx, dy))
                    consider(start.copy(tx = start.tx + d))
                    consider(start.copy(ty = start.ty + d))
                }
                if (best == start) break
            }
            step *= .5
        }
        // Preserve exact pixel copies when an extra warp cannot materially improve the fit.
        if (bestScore < .30 && (initial.error >= .36f || bestScore + .0006 < baseline.error)) return best
        require(initial.error < .36f) { "Frames cannot be aligned reliably" }
        return translation
    }

    data class Match(val x: Double, val y: Double, val u: Double, val v: Double)

    /** Refine on spatially separated full-resolution patches. An independently moving subject
     * cannot determine the entire fit. Restrict the correction to the measured search radius. */
    fun refine(seed: Similarity, matches: List<Match>, width: Int, height: Int, radius: Int): Similarity {
        if (matches.size < 4) return seed
        fun fit(points: List<Match>): Similarity? {
            val x = points.sumOf { it.x } / points.size; val y = points.sumOf { it.y } / points.size
            val u = points.sumOf { it.u } / points.size; val v = points.sumOf { it.v } / points.size
            var energy = 0.0; var dot = 0.0; var cross = 0.0
            for (p in points) {
                val dx = p.x - x; val dy = p.y - y
                energy += dx*dx + dy*dy
                dot += dx*(p.u-u) + dy*(p.v-v)
                cross += dx*(p.v-v) - dy*(p.u-u)
            }
            if (energy < 1) return null
            val a = dot/energy; val b = cross/energy
            return Similarity(a,b,u-a*x+b*y,v-b*x-a*y)
        }
        var best = emptyList<Match>()
        var leastError = Double.POSITIVE_INFINITY
        for (i in matches.indices) for (j in i+1 until matches.size) {
            val t = fit(listOf(matches[i], matches[j])) ?: continue
            val inliers = matches.filter { hypot(t.x(it.x,it.y)-it.u, t.y(it.x,it.y)-it.v) <= 1.6 }
            if (inliers.size < 4 || inliers.size * 2 <= matches.size || inliers.size < best.size) continue
            if (inliers.maxOf { it.x } - inliers.minOf { it.x } < width * .25 ||
                inliers.maxOf { it.y } - inliers.minOf { it.y } < height * .25) continue
            val error = inliers.sumOf { hypot(t.x(it.x,it.y)-it.u, t.y(it.x,it.y)-it.v) }
            if (inliers.size > best.size || error < leastError) { best = inliers; leastError = error }
        }
        val result = if (best.isEmpty()) return seed else fit(best) ?: return seed
        if (!result.plausible()) return seed
        for (x in listOf(0.0, width - 1.0)) for (y in listOf(0.0, height - 1.0)) {
            if (hypot(result.x(x,y)-seed.x(x,y), result.y(x,y)-seed.y(x,y)) > 2.5*radius) return seed
        }
        return result
    }
}
