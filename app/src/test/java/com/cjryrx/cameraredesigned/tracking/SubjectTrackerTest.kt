package com.cjryrx.cameraredesigned.tracking

import com.cjryrx.cameraredesigned.domain.FocusArea
import com.cjryrx.cameraredesigned.domain.SubjectBox
import com.cjryrx.cameraredesigned.domain.TrackingMode
import org.junit.Assert.*
import org.junit.Test
import java.nio.FloatBuffer

class SubjectTrackerTest {
    private fun box(x: Float, label: String = "person", confidence: Float = 0.8f) = SubjectBox(0, label, confidence, x, 0.35f, x + 0.2f, 0.65f)

    @Test fun identifiersSurviveDetectionReorderingAndSmallMotion() {
        val tracker = SubjectTracker()
        val first = tracker.update(listOf(box(0.1f), box(0.6f)), FocusArea.FULL, TrackingMode.FULL_SCREEN, true).first
        val second = tracker.update(listOf(box(0.61f), box(0.11f)), FocusArea.FULL, TrackingMode.FULL_SCREEN, true).first
        assertEquals(first[0].id, second[1].id)
        assertEquals(first[1].id, second[0].id)
    }

    @Test fun regionDoesNotChooseSubjectsOutsideZone() {
        val tracker = SubjectTracker()
        assertNull(tracker.update(listOf(box(0.01f)), FocusArea.POINT, TrackingMode.REGION, true).second)
        assertNotNull(tracker.update(listOf(box(0.4f)), FocusArea.POINT, TrackingMode.REGION, true).second)
    }

    @Test fun continuousTargetCanLeaveAcquisitionAreaWhilePressed() {
        val tracker = SubjectTracker()
        val first = tracker.update(listOf(box(0.4f)), FocusArea.POINT, TrackingMode.CONTINUOUS, true).second!!
        tracker.update(listOf(box(0.48f)), FocusArea.POINT, TrackingMode.CONTINUOUS, true)
        val moved = tracker.update(listOf(box(0.56f)), FocusArea.POINT, TrackingMode.CONTINUOUS, true).second!!
        assertEquals(first.id, moved.id)
        assertNull(tracker.update(listOf(box(0.56f)), FocusArea.POINT, TrackingMode.CONTINUOUS, false).second)
    }

    @Test fun releaseAdvanceSelectsNextEligibleSubject() {
        val tracker = SubjectTracker()
        val boxes = listOf(box(0.1f), box(0.6f))
        val first = tracker.update(boxes, FocusArea.FULL, TrackingMode.FULL_SCREEN, true).second!!
        tracker.next()
        val next = tracker.update(boxes, FocusArea.FULL, TrackingMode.FULL_SCREEN, false).second!!
        assertNotEquals(first.id, next.id)
    }

    @Test fun missingSubjectDoesNotProduceStaleFocusBox() {
        val tracker = SubjectTracker()
        tracker.update(listOf(box(0.4f)), FocusArea.CENTER, TrackingMode.REGION, true)
        assertNull(tracker.update(emptyList(), FocusArea.CENTER, TrackingMode.REGION, true).second)
    }

    @Test fun letterboxCoordinatesAndClassAwareNmsAreCorrect() {
        val anchors = YoloPostprocessor.ANCHORS
        val data = FloatArray(6 * anchors)
        fun prediction(i: Int, category: Int, confidence: Float) {
            data[i] = 320f; data[anchors + i] = 320f
            data[2 * anchors + i] = 320f; data[3 * anchors + i] = 160f
            data[(4 + category) * anchors + i] = confidence
        }
        prediction(0, 0, 0.9f); prediction(1, 0, 0.8f); prediction(2, 1, 0.7f)
        val result = YoloPostprocessor.decode(FloatBuffer.wrap(data), 2, listOf("person", "cat"), YoloPostprocessor.letterbox(640, 320))
        assertEquals(2, result.size)
        assertEquals(0.25f, result[0].left, 0.00001f)
        assertEquals(0.25f, result[0].top, 0.00001f)
        assertEquals(0.75f, result[0].right, 0.00001f)
        assertEquals(0.75f, result[0].bottom, 0.00001f)
        assertEquals(listOf("person", "cat"), result.map { it.label })
    }

    @Test fun invalidCoordinatesAndLogitsAreDiscarded() {
        val anchors = YoloPostprocessor.ANCHORS
        val data = FloatArray(5 * anchors)
        data[0] = Float.NaN; data[anchors] = 320f; data[2 * anchors] = 20f; data[3 * anchors] = 20f; data[4 * anchors] = 0.9f
        data[1] = 320f; data[anchors + 1] = 320f; data[2 * anchors + 1] = 20f; data[3 * anchors + 1] = 20f; data[4 * anchors + 1] = 7f
        assertTrue(YoloPostprocessor.decode(FloatBuffer.wrap(data), 1, listOf("subject"), YoloPostprocessor.letterbox(640, 640)).isEmpty())
    }
}
