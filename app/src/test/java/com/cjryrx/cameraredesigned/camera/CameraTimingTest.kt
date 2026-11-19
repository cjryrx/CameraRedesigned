package com.cjryrx.cameraredesigned.camera

import org.junit.Assert.*
import org.junit.Test

class CameraTimingTest {
    @Test fun longExposureBudgetIncludesQueuedPreviewFrames() {
        assertEquals(100_000L, cameraStageTimeout(30_000_000_000L, 3, 10_000))
    }

    @Test fun nanosecondsRoundUpAndZeroDoesNotRemoveStageGrace() {
        assertEquals(10_001L, cameraStageTimeout(1L, 1, 10_000))
        assertEquals(3_000L, cameraStageTimeout(0, 3, 3_000))
    }

    @Test fun maliciousLargeDurationsDoNotOverflowIntoImmediateTimeout() {
        assertEquals(Long.MAX_VALUE, cameraStageTimeout(Long.MAX_VALUE, Int.MAX_VALUE, 10_000))
    }
}
