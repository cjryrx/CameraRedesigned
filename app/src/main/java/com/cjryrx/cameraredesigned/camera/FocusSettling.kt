package com.cjryrx.cameraredesigned.camera

import kotlin.math.abs
import kotlin.math.max

/** Uncalibrated/approximate lens values are motor reports, not exact metric focus distances. */
internal class FocusSettling(private val target: Float, private val calibrated: Boolean) {
    private var previous: Float? = null
    private var previousFrame = Long.MIN_VALUE
    private var stableFrames = 0

    fun observe(frame: Long, requested: Float?, reported: Float?, stationary: Boolean, manualAf: Boolean): Boolean {
        if (frame == previousFrame) return false
        previousFrame = frame
        val valid = requested != null && abs(requested - target) <= .0001f && reported != null && reported.isFinite() &&
            stationary && manualAf && (!calibrated || abs(reported - target) <= max(.02f, abs(target) * .02f))
        if (!valid) { previous = null; stableFrames = 0; return false }
        val actual = requireNotNull(reported)
        stableFrames = if (previous?.let { abs(it - actual) <= max(.01f, abs(actual) * .01f) } == true) stableFrames + 1 else 1
        previous = actual
        return stableFrames >= 3
    }
}

internal fun focusMoved(previousTarget: Float, previousActual: Float, target: Float, actual: Float): Boolean =
    abs(previousTarget - target) <= .0001f || abs(previousActual - actual) > .001f
