package com.cjryrx.cameraredesigned.camera

/** Single-request, bounded timestamp join. Caller serializes calls; returned buffers transfer ownership. */
internal class FramePairer<J : Any, R : Any, M : Any>(
    private val needJpeg: Boolean,
    private val needRaw: Boolean,
    private val disposeRaw: (R) -> Unit,
    private val maximumUnmatched: Int = 3,
) {
    data class Matched<J, R, M>(val jpeg: J?, val raw: R?, val metadata: M)
    private class Parts<J, R>(var jpeg: J? = null, var raw: R? = null, var error: Throwable? = null)
    private val frames = LinkedHashMap<Long, Parts<J, R>>()
    private var expected: Long? = null
    private var metadata: M? = null
    private var terminal = false

    init { require(maximumUnmatched > 0 && (needJpeg || needRaw)) }

    fun expect(timestamp: Long) {
        if (terminal) return
        check(expected == null || expected == timestamp) { "Capture result timestamp does not match exposure start" }
        expected = timestamp
        val stale = frames.keys.filter { it != timestamp }
        stale.forEach { remove(it) }
    }

    fun result(timestamp: Long, value: M) { expect(timestamp); if (!terminal) metadata = value }

    fun jpeg(timestamp: Long, value: J) {
        if (!needJpeg) return
        parts(timestamp)?.jpeg = value
    }

    fun raw(timestamp: Long, value: R) {
        val parts = if (needRaw) parts(timestamp) else null
        if (parts == null) disposeRaw(value)
        else { parts.raw?.let(disposeRaw); parts.raw = value }
    }

    fun failure(timestamp: Long, failure: Throwable) { parts(timestamp)?.error = failure }

    fun take(): Matched<J, R, M>? {
        if (terminal) return null
        val result = metadata ?: return null
        val parts = frames[expected] ?: return null
        parts.error?.let { throw it }
        if (needJpeg && parts.jpeg == null || needRaw && parts.raw == null) return null
        val matched = Matched(parts.jpeg, parts.raw, result)
        frames.remove(expected)
        close()
        return matched
    }

    fun close() {
        terminal = true
        frames.values.forEach { it.raw?.let(disposeRaw) }
        frames.clear()
        metadata = null
    }

    private fun parts(timestamp: Long): Parts<J, R>? {
        if (terminal || expected?.let { it != timestamp } == true) return null
        return frames.getOrPut(timestamp) {
            if (frames.size == maximumUnmatched) remove(frames.keys.first())
            Parts()
        }
    }

    private fun remove(timestamp: Long) { frames.remove(timestamp)?.raw?.let(disposeRaw) }
}
