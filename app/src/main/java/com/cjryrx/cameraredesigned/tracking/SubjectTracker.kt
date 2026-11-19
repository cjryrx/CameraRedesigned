package com.cjryrx.cameraredesigned.tracking

import com.cjryrx.cameraredesigned.domain.FocusArea
import com.cjryrx.cameraredesigned.domain.SubjectBox
import com.cjryrx.cameraredesigned.domain.TrackingMode
import kotlin.math.max
import kotlin.math.min

/** Lightweight association on the latest detection frame; no frame queue or image ownership. */
class SubjectTracker {
    private data class Track(var box: SubjectBox, var missed: Int = 0)
    private val tracks = mutableListOf<Track>()
    private var nextId = 1L
    private var selectedId: Long? = null
    private var advance = false

    @Synchronized fun reset() { tracks.clear(); selectedId = null; advance = false; nextId = 1L }
    /** Explicit on release or custom key. The coordinator controls the release transition. */
    @Synchronized fun next() { advance = true }

    @Synchronized fun update(boxes: List<SubjectBox>, area: FocusArea, mode: TrackingMode, pressed: Boolean): Pair<List<SubjectBox>, SubjectBox?> {
        val valid = boxes.asSequence().filter(::valid).sortedByDescending { it.confidence }.take(100).toList()
        tracks.forEach { it.missed++ }
        val available = tracks.toMutableSet()
        val observed = ArrayList<SubjectBox>()
        for (box in valid) {
            val match = available.asSequence().filter { it.box.label == box.label }
                .map { it to iou(it.box, box) }.filter { it.second >= 0.2f }.maxByOrNull { it.second }?.first
            val tracked = if (match == null) {
                box.copy(id = nextId++).also { tracks.add(Track(it)) }
            } else {
                available.remove(match)
                box.copy(id = match.box.id).also { match.box = it; match.missed = 0 }
            }
            observed.add(tracked)
        }
        tracks.removeAll { it.missed > 4 }
        val eligible = observed.filter { mode == TrackingMode.FULL_SCREEN || inArea(it, area) }
            .sortedByDescending(::selectionScore)
        val current = observed.firstOrNull { it.id == selectedId }
        val retained = current?.takeIf {
            when (mode) {
                TrackingMode.REGION -> inArea(it, area)
                TrackingMode.CONTINUOUS -> pressed || inArea(it, area)
                TrackingMode.FULL_SCREEN -> true
            }
        }
        val chosen = if (advance) {
            advance = false
            val index = eligible.indexOfFirst { it.id == selectedId }
            if (eligible.isEmpty()) null else eligible[(index + 1).mod(eligible.size)]
        } else retained ?: eligible.firstOrNull()
        selectedId = chosen?.id
        return observed to chosen
    }

    companion object {
        fun iou(a: SubjectBox, b: SubjectBox): Float {
            val overlap = max(0f, min(a.right, b.right) - max(a.left, b.left)) *
                max(0f, min(a.bottom, b.bottom) - max(a.top, b.top))
            val union = (a.right - a.left) * (a.bottom - a.top) + (b.right - b.left) * (b.bottom - b.top) - overlap
            return if (union > 0f) overlap / union else 0f
        }

        fun inArea(box: SubjectBox, area: FocusArea): Boolean {
            val half = when (area) { FocusArea.POINT -> 0.04f; FocusArea.CENTER -> 0.15f; FocusArea.WIDE -> 0.35f; FocusArea.FULL -> 0.5f }
            // A subject intersecting the zone is selectable even if its geometric center is outside it.
            return box.right >= 0.5f - half && box.left <= 0.5f + half && box.bottom >= 0.5f - half && box.top <= 0.5f + half
        }

        private fun valid(box: SubjectBox): Boolean = listOf(box.left, box.top, box.right, box.bottom, box.confidence).all { it.isFinite() } &&
            box.left >= 0f && box.top >= 0f && box.right <= 1f && box.bottom <= 1f && box.right > box.left && box.bottom > box.top && box.confidence in 0f..1f

        private fun selectionScore(box: SubjectBox): Float {
            val x = (box.left + box.right) / 2 - 0.5f; val y = (box.top + box.bottom) / 2 - 0.5f
            return box.confidence * 0.65f + (1f - x * x - y * y) * 0.35f
        }
    }
}
