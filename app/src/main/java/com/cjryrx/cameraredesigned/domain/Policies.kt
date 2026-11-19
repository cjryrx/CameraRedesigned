package com.cjryrx.cameraredesigned.domain

import kotlin.math.pow
import kotlin.math.roundToLong
import kotlin.math.abs
import kotlin.math.roundToInt

fun ExposureSettings.forLens(cap: CameraCapabilities): ExposureSettings {
    if (cap.lensId.isEmpty()) return this
    val appliedMode = when {
        mode == ExposureMode.AUTO -> mode
        !cap.supportsManualSensor || mode == ExposureMode.A && cap.apertures.size <= 1 -> ExposureMode.P
        else -> mode
    }
    val ev = if (compensationEv.isFinite() && cap.compensationStep > 0f)
        (compensationEv.coerceIn(cap.compensationRange) / cap.compensationStep).roundToInt() * cap.compensationStep else 0f
    return copy(mode = appliedMode, aperture = if (cap.apertures.size <= 1) null else
        cap.apertures.minByOrNull { abs(it - (aperture ?: cap.apertures.first())) },
        shutterNanos = cap.shutterRange?.let { shutterNanos.coerceIn(it) } ?: shutterNanos,
        iso = if (cap.supportsManualSensor) cap.isoRange?.let { iso?.coerceIn(it) } else null,
        compensationEv = ev.coerceIn(cap.compensationRange),
        focusDiopters = focusDiopters?.takeIf { it.isFinite() }?.coerceIn(0f, cap.minFocusDiopters),
        whiteBalanceKelvin = whiteBalanceKelvin?.coerceIn(2000, 10000))
}

fun ExposureSettings.needsMeteringSample(variableAperture: Boolean): Boolean = when (mode) {
    ExposureMode.AUTO -> false
    ExposureMode.P -> iso != null
    ExposureMode.A -> true
    ExposureMode.S -> iso == null || variableAperture
    ExposureMode.M -> iso == null
}

/** Gesture state is independent of pointer sampling frequency and recomposition. */
class ShutterGate {
    @Volatile var pressed = false; private set
    var armed = false; private set
    private var fired = false
    fun press() { pressed = true; armed = false; fired = false }
    fun drag(lower: Boolean, burst: Boolean): Boolean {
        if (!pressed) return false
        armed = lower
        if (!lower) { if (burst) fired = false; return false }
        if (fired && !burst) return false
        if (fired) return false
        fired = true
        return true
    }
    fun release() { pressed = false; armed = false; fired = false }
    fun continueBurst() = pressed && armed
}

object ExposurePlan {
    /** Center frame first: useful recoverable reference if the sequence is interrupted. */
    fun bracketOffsets(count: Int, step: Float): List<Float> {
        require(count in listOf(3, 5, 7) && step.isFinite() && step > 0f)
        return listOf(0f) + (1..count / 2).flatMap { listOf(-it * step, it * step) }
    }
    fun bracketShutter(base: Long, ev: Float, range: LongRange): Long =
        (base.toDouble() * 2.0.pow(ev.toDouble())).roundToLong().coerceIn(range)
    fun focusRange(near: Float, far: Float, count: Int, max: Float): List<Float> {
        require(count in 2..30 && near.isFinite() && far.isFinite() && max > 0)
        return List(count) { i -> (far + (near - far) * i / (count - 1)).coerceIn(0f, max) }
    }
}

fun CameraSettings.validated() = copy(
    jpegQuality = jpegQuality.coerceIn(1, 100), timerSeconds = timerSeconds.coerceIn(0, 10),
    sliderSizeDp = sliderSizeDp.takeIf { it.isFinite() }?.coerceIn(48f, 88f) ?: 64f,
    sliderTravelDp = sliderTravelDp.takeIf { it.isFinite() }?.coerceIn(24f, 96f) ?: 48f,
    bracketCount = bracketCount.takeIf { it in listOf(3, 5, 7) } ?: 3,
    bracketStepEv = bracketStepEv.takeIf { it.isFinite() }?.coerceIn(.3f, 2f) ?: 1f,
    focusNear = focusNear.takeIf { it.isFinite() }?.coerceIn(0f, 100f) ?: 5f,
    focusFar = focusFar.takeIf { it.isFinite() }?.coerceIn(0f, 100f) ?: 0f,
    focusStackCount = focusStackCount.coerceIn(2, 30),
)

fun FilterSpec.validated(): FilterSpec {
    require(id.matches(Regex("[a-zA-Z0-9_-]{1,80}"))) { "Invalid filter identifier" }
    require(name.isNotBlank() && name.length <= 100) { "Invalid filter name" }
    fun Float.bound(low: Float = -1f, high: Float = 1f): Float {
        require(isFinite()) { "Filter contains non-finite values" }
        return coerceIn(low, high)
    }
    val a = adjustments
    return copy(intensity = intensity.bound(0f, 1f), adjustments = a.copy(
        exposure = a.exposure.bound(-5f, 5f), temperature = a.temperature.bound(), tint = a.tint.bound(),
        saturation = a.saturation.bound(), vibrance = a.vibrance.bound(), contrast = a.contrast.bound(),
        highlights = a.highlights.bound(), shadows = a.shadows.bound(),
        sharpness = a.sharpness.bound(0f, 1f), blur = a.blur.bound(0f, 1f),
    ))
}
