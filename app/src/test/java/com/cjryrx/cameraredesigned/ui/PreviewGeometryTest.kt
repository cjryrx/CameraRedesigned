package com.cjryrx.cameraredesigned.ui

import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import kotlin.math.hypot

class PreviewGeometryTest {
    @Test fun camera2DisplayCorrectionDoesNotRepeatProducerSensorRotation() {
        assertEquals(0, camera2DisplayCorrection(0))
        assertEquals(270, camera2DisplayCorrection(90))
        assertEquals(180, camera2DisplayCorrection(180))
        assertEquals(90, camera2DisplayCorrection(270))
        assertEquals(0, camera2DisplayCorrection(360))
    }
    @Test fun portraitSensorRotationPreservesCornerOrientation() {
        assertArrayEquals(floatArrayOf(300f, 0f, 300f, 400f, 0f, 400f, 0f, 0f),
            previewCorners(300, 400, 400, 300, 90, false), .001f)
    }

    @Test fun frontPreviewMirrorsOnlyHorizontalViewAxis() {
        val back = previewCorners(300, 400, 400, 300, 90, false)
        val front = previewCorners(300, 400, 400, 300, 90, true)
        for (i in back.indices step 2) {
            assertEquals(300f - back[i], front[i], .001f)
            assertEquals(back[i + 1], front[i + 1], .001f)
        }
    }

    @Test fun mismatchedViewFitsEntireBufferWithoutStretchOrCrop() {
        for (rotation in listOf(0, 90, 180, 270)) {
            val corners = previewCorners(700, 500, 400, 300, rotation, false)
            for (i in corners.indices step 2) {
                assertTrue(corners[i] in 0f..700f)
                assertTrue(corners[i + 1] in 0f..500f)
            }
            val a = hypot(corners[2] - corners[0], corners[3] - corners[1])
            val b = hypot(corners[4] - corners[2], corners[5] - corners[3])
            assertEquals(4f / 3f, a / b, .001f)
        }
    }

    @Test fun exposureStopsStayInsideHardwareRangeIncludingBothEndpoints() {
        val shutterRange = 73_000L..217_000_000L
        val times = shutterStops(shutterRange)
        assertEquals(shutterRange.first, times.first())
        assertEquals(shutterRange.last, times.last())
        assertTrue(times.all { it in shutterRange })
        assertEquals(times.sorted().distinct(), times)
        val isoRange = 73..2333
        val iso = isoStops(isoRange)
        assertEquals(isoRange.first, iso.first())
        assertEquals(isoRange.last, iso.last())
        assertTrue(iso.all { it in isoRange })
    }
}
