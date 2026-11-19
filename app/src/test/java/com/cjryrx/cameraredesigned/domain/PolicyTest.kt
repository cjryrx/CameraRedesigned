package com.cjryrx.cameraredesigned.domain

import org.junit.Assert.*
import org.junit.Test

class PolicyTest {
    @Test fun switchingLensClampsOnlyToAdvertisedExposureControls() {
        val fixed = CameraCapabilities(lensId = "back", apertures = listOf(1.88f), shutterRange = 100L..1_000L,
            isoRange = 100..1600, supportsManualSensor = true, compensationRange = -3f..3f, compensationStep = 1f / 6,
            minFocusDiopters = 8f)
        val exposure = ExposureSettings(ExposureMode.A, aperture = 2.8f, shutterNanos = 2_000L, iso = 6400, compensationEv = 4.5f, focusDiopters = 10f)
        val adjusted = exposure.forLens(fixed)
        assertEquals(ExposureMode.P, adjusted.mode)
        assertNull(adjusted.aperture)
        assertEquals(1600, adjusted.iso)
        assertEquals(1000L, adjusted.shutterNanos)
        assertEquals(3f, adjusted.compensationEv, .0001f)
        assertEquals(8f, requireNotNull(adjusted.focusDiopters), .0001f)
        assertNull(exposure.forLens(fixed.copy(supportsManualSensor = false)).iso)
    }

    @Test fun `single shutter latches until a new press`() {
        val gate = ShutterGate()
        assertFalse(gate.drag(true, false))
        gate.press()
        assertTrue(gate.drag(true, false))
        assertFalse(gate.drag(false, false))
        assertFalse(gate.drag(true, false))
        gate.release(); gate.press()
        assertTrue(gate.drag(true, false))
    }
    @Test fun `burst re-arms only after leaving lower zone`() {
        val gate = ShutterGate(); gate.press()
        assertTrue(gate.drag(true, true)); assertTrue(gate.continueBurst())
        assertFalse(gate.drag(true, true))
        gate.drag(false, true); assertFalse(gate.continueBurst())
        assertTrue(gate.drag(true, true))
        gate.release(); assertFalse(gate.continueBurst())
    }
    @Test fun `bracket preserves central recovery reference and symmetric stops`() {
        assertEquals(listOf(0f, -1f, 1f, -2f, 2f), ExposurePlan.bracketOffsets(5, 1f))
        assertEquals(40_000_000L, ExposurePlan.bracketShutter(10_000_000, 2f, 1L..100_000_000L))
        assertEquals(100_000_000L, ExposurePlan.bracketShutter(90_000_000, 2f, 1L..100_000_000L))
    }
    @Test fun `focus range uses diopters and includes both endpoints`() {
        assertEquals(listOf(0f, 1f, 2f), ExposurePlan.focusRange(2f, 0f, 3, 5f))
    }
    @Test(expected = IllegalArgumentException::class)
    fun `non finite imported filter is rejected`() {
        FilterSpec("valid", "Filter", FilterAdjustments(exposure = Float.NaN)).validated()
    }
}
