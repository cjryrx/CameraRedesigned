package com.cjryrx.cameraredesigned.ui

/** One instance per pointer gesture; capture can latch only once outside burst mode. */
internal class ShutterGesture(private val burst: Boolean) {
    private var armed = false
    private var fired = false

    fun move(fraction: Float): Boolean? {
        val next = if (armed) fraction >= .60f else fraction >= .85f
        if (next == armed || fired && !burst) return null
        armed = next
        if (armed) fired = true
        return armed
    }

    fun thumbFraction(fraction: Float): Float = if (fired && !burst) 0f else fraction.coerceIn(0f, 1f)
}
