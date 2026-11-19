package com.cjryrx.cameraredesigned.camera

/** Saturating nanosecond-to-millisecond budget for queued sensor frames plus stage overhead. */
internal fun cameraStageTimeout(exposureNanos: Long, frames: Int, marginMillis: Long): Long {
    require(frames > 0 && marginMillis >= 0)
    val positive = exposureNanos.coerceAtLeast(0)
    val millis = positive / 1_000_000L + if (positive % 1_000_000L != 0L) 1L else 0L
    return if (millis > (Long.MAX_VALUE - marginMillis) / frames) Long.MAX_VALUE
    else marginMillis + millis * frames
}
