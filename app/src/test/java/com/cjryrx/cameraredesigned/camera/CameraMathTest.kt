package com.cjryrx.cameraredesigned.camera

import org.junit.Assert.*
import org.junit.Test

class CameraMathTest {
    @Test fun portraitBackCameraMapsVisibleTopLeftToSensorBottomLeft() {
        assertEquals(0f to 1f, viewToSensor(0f, 0f, 90, false))
        assertEquals(1f to 0f, viewToSensor(1f, 1f, 90, false))
    }

    @Test fun frontCameraMirroringIsRemovedBeforeRotation() {
        assertEquals(0f to 0f, viewToSensor(0f, 0f, 90, true))
        assertEquals(1f to 1f, viewToSensor(1f, 1f, 90, true))
    }

    @Test fun rotationKeepsCenterFixedAndClampsInvalidTouchCoordinates() {
        for (rotation in listOf(0, 90, 180, 270)) {
            assertEquals(.5f to .5f, viewToSensor(.5f, .5f, rotation, false))
        }
        assertEquals(0f to 1f, viewToSensor(-2f, 3f, 0, false))
    }

    @Test fun exposureCompensationUsesDeviceStepsAndLimits() {
        assertEquals(2, compensationSteps(0.7f, 1f / 3, -6, 6))
        assertEquals(6, compensationSteps(5f, 1f / 3, -6, 6))
        assertEquals(-6, compensationSteps(-5f, 1f / 3, -6, 6))
        assertEquals(0, compensationSteps(3f, 0f, 0, 0))
    }

    @Test fun softwareMeteringConvergesWithoutUnboundedJumps() {
        assertEquals(1.0, meteringCorrection(.45f, 0f), .0001)
        assertTrue(meteringCorrection(.1f, 0f) > 1.0)
        assertTrue(meteringCorrection(.9f, 0f) < 1.0)
        assertTrue(meteringCorrection(.01f, 0f) <= 1.415)
        assertTrue(meteringCorrection(1f, 0f) >= .707)
        assertTrue(meteringCorrection(.45f, 1f) > 1.0)
    }

    @Test fun whiteBalanceProducesFinitePositiveGainsAtBothEndpoints() {
        for (temperature in listOf(2000, 6500, 10000)) {
            val gains = whiteBalanceGains(temperature)
            assertEquals(3, gains.size)
            assertTrue(gains.all { it.isFinite() && it in 1f..8f })
        }
    }
}
