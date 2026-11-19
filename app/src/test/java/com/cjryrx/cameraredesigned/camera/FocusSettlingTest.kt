package com.cjryrx.cameraredesigned.camera

import org.junit.Assert.*
import org.junit.Test

class FocusSettlingTest {
    @Test fun approximateInfinityAcceptsStableReportedOffsetAfterThreeFrames() {
        val settling = FocusSettling(0f, calibrated = false)
        assertFalse(settling.observe(1, 0f, .11998f, true, true))
        assertFalse(settling.observe(2, 0f, .11998f, true, true))
        assertTrue(settling.observe(3, 0f, .11998f, true, true))
    }

    @Test fun calibratedLensMustMeetTheRequestedMetricDistance() {
        val settling = FocusSettling(0f, calibrated = true)
        (1L..5L).forEach { assertFalse(settling.observe(it, 0f, .11998f, true, true)) }
    }

    @Test fun missingOrMovingLensReportsCannotPretendToBeSettled() {
        val settling = FocusSettling(4f, calibrated = false)
        assertFalse(settling.observe(1, 4f, null, true, true))
        assertFalse(settling.observe(2, 4f, 4f, false, true))
        assertFalse(settling.observe(3, 4f, 4f, true, false))
        assertFalse(settling.observe(4, 0f, 4f, true, true))
        assertFalse(settling.observe(5, 4f, 4f, true, true))
        assertFalse(settling.observe(5, 4f, 4f, true, true)) // A repeated callback is not another frame.
        assertFalse(settling.observe(6, 4f, 4f, true, true))
        assertTrue(settling.observe(7, 4f, 4f, true, true))
    }

    @Test fun differentBracketCommandsMustProduceDifferentLensReports() {
        assertFalse(focusMoved(0f, .12f, 4f, .12f))
        assertTrue(focusMoved(0f, .12f, 4f, 3.9f))
        assertTrue(focusMoved(4f, 3.9f, 4f, 3.9f))
    }
}
