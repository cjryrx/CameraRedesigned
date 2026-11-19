package com.cjryrx.cameraredesigned.camera

import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.supervisorScope
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Release stops future submissions, without cancelling the jobs that own exposed frames. */
internal class BurstControl {
    private val released = AtomicBoolean(false)
    val stopped = CompletableDeferred<Unit>()
    val isStopped: Boolean get() = released.get()
    fun stop() { released.set(true); stopped.complete(Unit) }
}

internal class BurstNotSubmitted : CancellationException("Burst stopped before submission")

/** Bounded producer with ordered delivery. A reservation remains owned until completed returns.
 * The submit job must finish saving an already accepted frame even under parent cancellation. */
internal suspend fun <T> runBurstPipeline(
    control: BurstControl,
    fps: Int,
    maximumInFlight: Int,
    prepare: suspend (Int) -> File?,
    submit: (CoroutineScope, File) -> Deferred<Result<T>>,
    completed: suspend (File, Result<T>) -> Unit,
) = supervisorScope {
    require(fps >= 0 && maximumInFlight in 1..3)
    val flights = ArrayDeque<Pair<File, Deferred<Result<T>>>>()
    var reserved: File? = null
    var failure: Throwable? = null
    var index = 0
    var nextSubmission = 0L
    val interval = if (fps == 0) 0L else 1_000_000_000L / fps

    suspend fun deliver(file: File, result: Result<T>) {
        if (result.isFailure && result.exceptionOrNull() !is BurstNotSubmitted) {
            failure = failure ?: result.exceptionOrNull(); control.stop()
        }
        try { completed(file, result) }
        catch (e: Exception) { failure = failure ?: e; control.stop() }
    }

    suspend fun retire() {
        val (file, result) = flights.removeFirst()
        withContext(NonCancellable) {
            val value = try { result.await() } catch (e: Exception) { Result.failure(e) }
            deliver(file, value)
        }
    }

    try {
        while (index == 0 || !control.isStopped) {
            // Flush completed frames promptly; slow disk/publication prevents further allocation.
            while (flights.firstOrNull()?.second?.isCompleted == true) retire()
            if (flights.size == maximumInFlight) retire()
            currentCoroutineContext().ensureActive()
            if (index > 0 && control.isStopped) break
            val remaining = nextSubmission - System.nanoTime()
            if (remaining > 0) withTimeoutOrNull((remaining + 999_999L) / 1_000_000L) { control.stopped.await() }
            if (index > 0 && control.isStopped) break

            reserved = prepare(index) ?: break
            currentCoroutineContext().ensureActive()
            if (index > 0 && control.isStopped) break
            val file = requireNotNull(reserved)
            val result = submit(this, file)
            flights.addLast(file to result)
            reserved = null
            index++
            nextSubmission = System.nanoTime() + interval
        }
    } catch (e: Throwable) {
        control.stop()
        throw e
    } finally {
        // Cancellation/release must not strand write-ahead journals or processing-queue slots.
        withContext(NonCancellable) {
            while (flights.isNotEmpty()) retire()
            reserved?.let { file ->
                try { completed(file, Result.failure(BurstNotSubmitted())) }
                catch (e: Exception) { failure = failure ?: e }
            }
        }
        control.stop()
    }
    failure?.let { throw it }
}
