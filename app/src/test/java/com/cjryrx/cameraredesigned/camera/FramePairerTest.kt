package com.cjryrx.cameraredesigned.camera

import org.junit.Assert.*
import org.junit.Test
import java.io.IOException

class FramePairerTest {
    private val disposed = mutableListOf<String>()
    private fun pairer(jpeg: Boolean = true, raw: Boolean = true) =
        FramePairer<String, String, String>(jpeg, raw, { disposed += it })

    @Test fun outOfOrderBuffersWaitForMatchingResultAndBothOutputs() {
        val pairer = pairer()
        pairer.jpeg(20, "jpeg")
        pairer.raw(20, "raw")
        assertNull(pairer.take())
        pairer.result(20, "metadata")
        assertEquals(FramePairer.Matched("jpeg", "raw", "metadata"), pairer.take())
        assertNull(pairer.take())
        pairer.close()
        assertTrue(disposed.isEmpty()) // Matched RAW ownership moved to the capture transaction.
    }

    @Test fun differentSensorTimestampsCannotProduceAFalsePair() {
        val pairer = pairer()
        pairer.jpeg(20, "jpeg")
        pairer.raw(19, "stale")
        pairer.result(20, "metadata")
        assertNull(pairer.take())
        assertEquals(listOf("stale"), disposed)
        pairer.raw(20, "current")
        assertEquals("current", pairer.take()?.raw)
    }

    @Test fun lateOldReadFailureDoesNotAbortTheNewShot() {
        val pairer = pairer(raw = false)
        pairer.failure(19, IOException("old image copy failed"))
        pairer.expect(20)
        pairer.failure(19, IOException("another old image copy failed"))
        pairer.jpeg(20, "jpeg")
        pairer.result(20, "metadata")
        assertEquals("jpeg", pairer.take()?.jpeg)
    }

    @Test fun matchingReadFailureIsReportedWithoutPartialSuccess() {
        val pairer = pairer()
        pairer.jpeg(20, "jpeg")
        pairer.failure(20, IOException("RAW write failed"))
        pairer.result(20, "metadata")
        try { pairer.take(); fail("Expected matching read error") }
        catch (e: IOException) { assertEquals("RAW write failed", e.message) }
        pairer.close()
    }

    @Test fun closeDisposesBufferedAndLateRawExactlyOnce() {
        val pairer = pairer()
        pairer.raw(20, "queued")
        pairer.close()
        pairer.close()
        pairer.raw(20, "late")
        assertEquals(listOf("queued", "late"), disposed)
    }

    @Test fun unmatchedBufferLimitEvictsOldRawInsteadOfGrowingMemory() {
        val pairer = pairer()
        (1L..5L).forEach { pairer.raw(it, "raw-$it") }
        assertEquals(listOf("raw-1", "raw-2"), disposed)
        pairer.close()
        assertEquals(5, disposed.distinct().size)
    }

    @Test fun replacementDisposesPriorRawAndUnexpectedRawIsNotRetained() {
        val pairer = pairer()
        pairer.raw(20, "first")
        pairer.raw(20, "replacement")
        assertEquals(listOf("first"), disposed)
        pairer.close()
        val jpegOnly = this.pairer(raw = false)
        jpegOnly.raw(30, "unexpected")
        assertEquals(listOf("first", "replacement", "unexpected"), disposed)
    }

    @Test fun resultMustAgreeWithExposureStartTimestamp() {
        val pairer = pairer()
        pairer.expect(20)
        try { pairer.result(21, "wrong result"); fail("Expected timestamp mismatch") }
        catch (_: IllegalStateException) { }
    }

    @Test fun burstRequestsShareImmutableJpegBuffersButMatchOnlyTheirOwnTimestamp() {
        val requests = List(3) { pairer(raw = false) }
        // Images can beat callbacks on their separate handler and arrive in a different order.
        listOf(102L, 100L, 101L).forEach { timestamp -> requests.forEach { it.jpeg(timestamp, "jpeg-$timestamp") } }
        requests[1].result(101, "request-1")
        requests[2].expect(102)
        requests[0].result(100, "request-0")
        requests[2].result(102, "request-2")
        requests.forEachIndexed { index, request ->
            assertEquals(FramePairer.Matched("jpeg-${100 + index}", null, "request-$index"), request.take())
            assertNull(request.take())
        }
    }
}
