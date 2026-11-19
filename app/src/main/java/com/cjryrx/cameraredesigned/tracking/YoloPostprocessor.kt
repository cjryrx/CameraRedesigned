package com.cjryrx.cameraredesigned.tracking

import com.cjryrx.cameraredesigned.domain.SubjectBox
import java.nio.FloatBuffer
import kotlin.math.min
import kotlin.math.roundToInt

/** Fixed v1 input/output protocol. All returned coordinates are normalized in the original image. */
object YoloPostprocessor {
    const val SIZE = 640
    const val ANCHORS = 8400
    data class Letterbox(val width: Int, val height: Int, val resizedWidth: Int, val resizedHeight: Int, val left: Int, val top: Int) {
        val scaleX: Float get() = resizedWidth.toFloat() / width
        val scaleY: Float get() = resizedHeight.toFloat() / height
    }

    fun letterbox(width: Int, height: Int): Letterbox {
        require(width > 0 && height > 0)
        val scale = min(SIZE.toFloat() / width, SIZE.toFloat() / height)
        val resizedWidth = (width * scale).roundToInt().coerceIn(1, SIZE)
        val resizedHeight = (height * scale).roundToInt().coerceIn(1, SIZE)
        return Letterbox(width, height, resizedWidth, resizedHeight, (SIZE - resizedWidth) / 2, (SIZE - resizedHeight) / 2)
    }

    fun decode(output: FloatBuffer, classes: Int, labels: List<String>, transform: Letterbox, confidence: Float = 0.35f, overlap: Float = 0.45f): List<SubjectBox> {
        require(classes in 1..256 && output.remaining() == (4 + classes) * ANCHORS)
        require(labels.size == classes && confidence in 0f..1f && overlap in 0f..1f)
        val candidates = ArrayList<SubjectBox>()
        for (anchor in 0 until ANCHORS) {
            var best = -1; var score = confidence
            for (category in 0 until classes) {
                val probability = output[(category + 4) * ANCHORS + anchor]
                if (probability.isFinite() && probability in score..1f) { score = probability; best = category }
            }
            if (best < 0) continue
            val cx = output[anchor]; val cy = output[ANCHORS + anchor]
            val width = output[2 * ANCHORS + anchor]; val height = output[3 * ANCHORS + anchor]
            if (!cx.isFinite() || !cy.isFinite() || !width.isFinite() || !height.isFinite() || width <= 0 || height <= 0) continue
            val left = ((cx - width / 2 - transform.left) / transform.scaleX / transform.width).coerceIn(0f, 1f)
            val right = ((cx + width / 2 - transform.left) / transform.scaleX / transform.width).coerceIn(0f, 1f)
            val top = ((cy - height / 2 - transform.top) / transform.scaleY / transform.height).coerceIn(0f, 1f)
            val bottom = ((cy + height / 2 - transform.top) / transform.scaleY / transform.height).coerceIn(0f, 1f)
            if (right > left && bottom > top) candidates.add(SubjectBox(best.toLong(), labels[best], score, left, top, right, bottom))
        }
        val ordered = candidates.sortedByDescending { it.confidence }.take(300)
        val selected = ArrayList<SubjectBox>(100)
        for (candidate in ordered) {
            if (selected.none { it.id == candidate.id && SubjectTracker.iou(it, candidate) > overlap }) selected.add(candidate)
            if (selected.size == 100) break
        }
        return selected
    }
}
