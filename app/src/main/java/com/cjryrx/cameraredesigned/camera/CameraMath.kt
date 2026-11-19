package com.cjryrx.cameraredesigned.camera

import kotlin.math.pow
import kotlin.math.roundToInt

/** Coordinates are in the displayed, fully visible upright preview, including front-camera mirroring. */
internal fun viewToSensor(x: Float, y: Float, rotation: Int, mirrored: Boolean): Pair<Float, Float> {
    val px = (if (mirrored) 1f - x else x).coerceIn(0f, 1f)
    val py = y.coerceIn(0f, 1f)
    return when ((rotation % 360 + 360) % 360) {
        90 -> py to 1f - px
        180 -> 1f - px to 1f - py
        270 -> 1f - py to px
        else -> px to py
    }
}

/** Conservative feedback: limit each correction to 1/2 stop to avoid visible oscillation. */
internal fun meteringCorrection(meanLuma: Float, ev: Float): Double {
    val target = (0.45 * 2.0.pow(ev / 2.2)).coerceIn(0.04, 0.92)
    return (target / meanLuma.coerceIn(0.01f, 1f)).pow(2.2).coerceIn(0.7071, 1.4143)
}

internal fun compensationSteps(ev: Float, step: Float, minimum: Int, maximum: Int): Int =
    if (step <= 0f) 0 else (ev / step).roundToInt().coerceIn(minimum, maximum)

/** Approximate Planckian-locus sRGB illuminant; normalized inverse gains for camera WB. */
internal fun whiteBalanceGains(kelvin: Int): FloatArray {
    val t = kelvin.coerceIn(2000, 10000) / 100.0
    val r = if (t <= 66) 255.0 else 329.698727446 * (t - 60).pow(-0.1332047592)
    val g = if (t <= 66) 99.4708025861 * kotlin.math.ln(t) - 161.1195681661
        else 288.1221695283 * (t - 60).pow(-0.0755148492)
    val b = if (t >= 66) 255.0 else if (t <= 19) 1.0
        else 138.5177312231 * kotlin.math.ln(t - 10) - 305.0447927307
    val rg = (g.coerceIn(1.0, 255.0) / r.coerceIn(1.0, 255.0)).toFloat()
    val bg = (g.coerceIn(1.0, 255.0) / b.coerceIn(1.0, 255.0)).toFloat()
    val minimum = minOf(rg, 1f, bg)
    return floatArrayOf((rg / minimum).coerceIn(1f, 8f), (1f / minimum).coerceIn(1f, 8f), (bg / minimum).coerceIn(1f, 8f))
}
