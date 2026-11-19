package com.cjryrx.cameraredesigned.camera

import kotlinx.coroutines.*
import org.junit.Assert.*
import org.junit.Test
import java.io.File
import java.io.IOException

class BurstPipelineTest {
    @Test fun boundedFramesDeliverInSubmissionOrderEvenIfDiskFinishesOutOfOrder() = runBlocking {
        val signals = List(4) { CompletableDeferred<Result<Int>>() }
        val submitted = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val job = launch {
            runBurstPipeline(BurstControl(), 0, 3,
                prepare = { if (it < 4) File("$it.jpg") else null },
                submit = { _, file -> file.nameWithoutExtension.toInt().let { submitted += it; signals[it] } },
                completed = { _, result -> delivered += result.getOrThrow() })
        }
        awaitCount(submitted, 3)
        assertEquals(3, submitted.size)
        signals[2].complete(Result.success(2))
        signals[1].complete(Result.success(1))
        yield()
        assertTrue("Later disk completions cannot overtake first frame", delivered.isEmpty())
        signals[0].complete(Result.success(0))
        awaitCount(submitted, 4)
        signals[3].complete(Result.success(3))
        withTimeout(2_000) { job.join() }
        assertEquals(listOf(0, 1, 2, 3), delivered)
    }

    @Test fun releaseBeforeFirstFrameStillCapturesTheThresholdCrossing() = runBlocking {
        val control = BurstControl().apply { stop() }
        var submitted = 0
        var delivered = 0
        runBurstPipeline(control, 0, 3, prepare = { File("$it.jpg") },
            submit = { _, _ -> submitted++; CompletableDeferred(Result.success(1)) },
            completed = { _, result -> delivered += result.getOrThrow() })
        assertEquals(1, submitted)
        assertEquals(1, delivered)
    }

    @Test fun releaseWhilePreparingClosesThatReservationWithoutAnotherExposure() = runBlocking {
        val control = BurstControl()
        var submitted = 0
        val outcomes = mutableListOf<Result<Int>>()
        runBurstPipeline(control, 0, 3, prepare = { index ->
            if (index == 1) control.stop()
            File("$index.jpg")
        }, submit = { _, _ -> submitted++; CompletableDeferred(Result.success(1)) },
            completed = { _, result -> outcomes += result })
        assertEquals(1, submitted)
        assertEquals(2, outcomes.size)
        assertTrue(outcomes.last().exceptionOrNull() is BurstNotSubmitted)
    }

    @Test fun cancellationWaitsForExposedFrameAndClosesEveryReservation() = runBlocking {
        val exposed = CompletableDeferred<Unit>()
        val disk = CompletableDeferred<Unit>()
        var saved = false
        var completed = 0
        val job = launch {
            runBurstPipeline(BurstControl(), 0, 1,
                prepare = { File("$it.jpg") },
                submit = { scope, _ -> scope.async(start = CoroutineStart.UNDISPATCHED) {
                    withContext(NonCancellable) {
                        exposed.complete(Unit)
                        disk.await()
                        saved = true
                        Result.success(1)
                    }
                } }, completed = { _, _ -> assertTrue(saved); completed++ })
        }
        exposed.await()
        job.cancel()
        yield()
        assertFalse(job.isCompleted)
        assertEquals(0, completed)
        disk.complete(Unit)
        withTimeout(2_000) { job.join() }
        assertTrue(saved)
        assertEquals(1, completed)
    }

    @Test fun captureFailureDrainsOtherAcceptedFramesBeforeThrowing() = runBlocking {
        val signals = List(3) { CompletableDeferred<Result<Int>>() }
        val submitted = mutableListOf<Int>()
        val delivered = mutableListOf<Int>()
        val expected = IOException("Camera lost buffer")
        val outcome = async {
            runCatching {
                runBurstPipeline(BurstControl(), 0, 3,
                    prepare = { File("$it.jpg") },
                    submit = { _, file -> file.nameWithoutExtension.toInt().let { submitted += it; signals[it] } },
                    completed = { file, _ -> delivered += file.nameWithoutExtension.toInt() })
            }
        }
        awaitCount(submitted, 3)
        signals[0].complete(Result.failure(expected))
        yield()
        assertFalse(outcome.isCompleted)
        signals[1].complete(Result.success(1))
        signals[2].complete(Result.success(2))
        val actual = requireNotNull(withTimeout(2_000) { outcome.await() }.exceptionOrNull())
        // Coroutine debug mode copies exceptions across suspension boundaries to recover stacks.
        // The failure contract is the original type/message/cause, not the wrapper's identity.
        assertEquals(expected.javaClass, actual.javaClass)
        assertEquals(expected.message, actual.message)
        assertTrue("The first failure must remain the original cause",
            generateSequence(actual) { it.cause }.any { it === expected })
        assertEquals(listOf(0, 1, 2), delivered)
        assertEquals(3, submitted.size)
    }

    @Test fun singleInFlightFallbackRetainsBackpressure() = runBlocking {
        val first = CompletableDeferred<Result<Int>>()
        val submitted = mutableListOf<Int>()
        val job = launch {
            runBurstPipeline(BurstControl(), 0, 1, prepare = { if (it < 2) File("$it.jpg") else null },
                submit = { _, file -> file.nameWithoutExtension.toInt().let { index ->
                    submitted += index
                    if (index == 0) first else CompletableDeferred(Result.success(index))
                } }, completed = { _, result -> result.getOrThrow() })
        }
        awaitCount(submitted, 1)
        yield()
        assertEquals(listOf(0), submitted)
        first.complete(Result.success(0))
        withTimeout(2_000) { job.join() }
        assertEquals(listOf(0, 1), submitted)
    }

    private suspend fun awaitCount(values: List<Int>, size: Int) {
        withTimeout(2_000) { while (values.size < size) yield() }
    }
}
