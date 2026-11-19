package com.cjryrx.cameraredesigned.tracking

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.SystemClock
import android.util.Log
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.domain.FocusArea
import com.cjryrx.cameraredesigned.domain.TrackingMode
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import org.junit.Rule
import org.junit.Test
import java.io.File
import java.security.MessageDigest

/** Run with -e testStagedModel true only after root stages the checksum-verified official model. */
class DefaultModelDeviceTest {
    // Some devices freeze even an instrumented background process between camera and GPU tests.
    @get:Rule val foreground = ActivityScenarioRule(
        CameraProbeActivity::class.java)

    @Test fun officialModelRecognizesAndTracksSubjectsAcrossShiftedFrames() = runBlocking<Unit> {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testTrackingFixture") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.cacheDir, "tracking-fixture.jpg")
        assertEquals("c02019c4979c191eb739ddd944445ef408dad5679acab6fd520ef9d434bfbc63", digest(file))
        val scene = requireNotNull(BitmapFactory.decodeFile(file.path))
        val tracker = SubjectTracker()
        val detector = YoloDetector(context)
        val durations = mutableListOf<Long>()
        try {
            detector.load()
            var selectedId: Long? = null
            for (x in listOf(0, 12, 24, 36, 24, 12, 0)) {
                val frame = Bitmap.createBitmap(scene, x, 0, scene.width - 48, scene.height)
                val start = SystemClock.elapsedRealtime()
                val detections = try { detector.detect(frame) } finally { frame.recycle() }
                durations += SystemClock.elapsedRealtime() - start
                assertTrue("Bus must be detected", detections.any { it.label == "bus" })
                assertTrue("At least two people must be detected", detections.count { it.label == "person" } >= 2)
                val (_, selection) = tracker.update(detections, FocusArea.FULL, TrackingMode.CONTINUOUS, true)
                val selected = requireNotNull(selection)
                if (selectedId != null) assertEquals("Retain the same moving subject", selectedId, selected.id)
                selectedId = selected.id
            }
            tracker.next()
            val (_, next) = tracker.update(detector.detect(scene), FocusArea.FULL, TrackingMode.CONTINUOUS, false)
            assertNotEquals("Release can acquire another actual subject", selectedId, requireNotNull(next).id)
            Log.i("CameraTrackingTest", "YOLO11n, seven shifted frames: inference and decode ms = $durations")
        } finally { detector.close(); scene.recycle() }
    }

    @Test fun officialModelLoadsAndRunsAndRejectsInvalidReplacement(): Unit = runBlocking {
        assumeTrue(InstrumentationRegistry.getArguments().getString("testStagedModel") == "true")
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val file = File(context.filesDir, "models/active.onnx")
        assumeTrue(file.isFile)
        val hash = digest(file)
        assertEquals(YoloDetector.DEFAULT_MODEL_SHA256, hash)
        val detector = YoloDetector(context)
        val sample = Bitmap.createBitmap(320, 240, Bitmap.Config.ARGB_8888).apply { eraseColor(0xff727272.toInt()) }
        val invalid = File(context.cacheDir, "invalid-test-model-${System.nanoTime()}.onnx").apply { writeText("invalid onnx test input") }
        try {
            detector.load()
            val detections = detector.detect(sample)
            assertTrue(detections.size <= 100)
            assertTrue(detections.all { it.left in 0f..1f && it.top in 0f..1f && it.right in 0f..1f && it.bottom in 0f..1f })
            var rejected = false
            try { detector.install(invalid) } catch (_: Exception) { rejected = true }
            assertTrue(rejected)
            assertEquals(hash, digest(file))
            detector.detect(sample)
            Unit
        } finally { detector.close(); sample.recycle(); invalid.delete() }
    }

    private fun digest(file: File): String {
        val digest = MessageDigest.getInstance("SHA-256")
        file.inputStream().use { input ->
            val buffer = ByteArray(64 * 1024)
            while (true) { val count = input.read(buffer); if (count < 0) break; digest.update(buffer, 0, count) }
        }
        return digest.digest().joinToString("") { "%02x".format(it.toInt() and 255) }
    }
}
