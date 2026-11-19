package com.cjryrx.cameraredesigned.camera

import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.io.IOException

class CaptureFilesTest {
    @get:Rule val temporary = TemporaryFolder()

    @Test fun destinationAppearsOnlyAfterCompleteWrite() {
        val directory = temporary.newFolder()
        val destination = File(directory, "photo.dng")
        commitCaptureFile(destination) {
            it.write(byteArrayOf(1, 2))
            assertFalse(destination.exists())
            it.write(byteArrayOf(3, 4))
        }
        assertArrayEquals(byteArrayOf(1, 2, 3, 4), destination.readBytes())
        assertEquals(listOf("photo.dng"), directory.list()?.toList())
    }

    @Test fun failedWriterLeavesNeitherDestinationNorPart() {
        val directory = temporary.newFolder()
        val destination = File(directory, "photo.dng")
        try {
            commitCaptureFile(destination) { it.write(byteArrayOf(1)); throw IOException("disk failure") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertFalse(destination.exists())
        assertTrue(directory.list().isNullOrEmpty())
    }

    @Test fun failedRawWritePreservesAlreadyCommittedJpeg() {
        val directory = temporary.newFolder()
        val jpeg = File(directory, "photo.jpg")
        commitCaptureFile(jpeg) { it.write(byteArrayOf(1, 2, 3)) }
        try {
            commitCaptureFile(File(directory, "photo.dng")) { throw IOException("encoder failure") }
            fail("Expected failure")
        } catch (_: IOException) { }
        assertArrayEquals(byteArrayOf(1, 2, 3), jpeg.readBytes())
        assertEquals(listOf("photo.jpg"), directory.list()?.toList())
    }

    @Test fun retryCannotClobberACompletedFrame() {
        val directory = temporary.newFolder()
        val destination = File(directory, "photo.jpg").apply { writeBytes(byteArrayOf(7)) }
        try {
            commitCaptureFile(destination) { it.write(byteArrayOf(8)) }
            fail("Expected existing destination rejection")
        } catch (_: IllegalArgumentException) { }
        assertArrayEquals(byteArrayOf(7), destination.readBytes())
    }

    @Test fun emptyWriterDoesNotPublishAnEmptyImage() {
        val directory = temporary.newFolder()
        try {
            commitCaptureFile(File(directory, "photo.jpg")) { }
            fail("Expected empty image rejection")
        } catch (_: IllegalStateException) { }
        assertTrue(directory.list().isNullOrEmpty())
    }
}
