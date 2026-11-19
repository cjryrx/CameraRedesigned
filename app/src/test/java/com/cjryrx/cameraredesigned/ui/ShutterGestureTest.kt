package com.cjryrx.cameraredesigned.ui

import org.junit.Assert.*
import org.junit.Test

class ShutterGestureTest {
    @Test fun singleShotCannotRetriggerUntilNewPointerGesture() {
        val gesture = ShutterGesture(burst = false)
        assertNull(gesture.move(.4f))
        assertEquals(true, gesture.move(.9f))
        assertEquals(0f, gesture.thumbFraction(.9f), 0f)
        assertNull(gesture.move(0f))
        assertNull(gesture.move(1f))
        assertEquals(true, ShutterGesture(burst = false).move(.9f))
    }

    @Test fun burstHasHysteresisAndStopsWhenDraggedBack() {
        val gesture = ShutterGesture(burst = true)
        assertNull(gesture.move(.84f))
        assertEquals(true, gesture.move(.85f))
        assertNull(gesture.move(.7f))
        assertEquals(false, gesture.move(.59f))
        assertNull(gesture.move(.7f))
        assertEquals(true, gesture.move(1f))
        assertEquals(1f, gesture.thumbFraction(1f), 0f)
    }
}
