package com.cjryrx.cameraredesigned.data

import android.content.ContextWrapper
import android.net.Uri
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.domain.ShootingMode
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.first
import kotlinx.serialization.encodeToString
import org.junit.Assert.*
import org.junit.Test
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/** Exercises real durable files with a fake publication boundary; never writes test images to the gallery. */
class ProcessingQueueDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(CameraProbeActivity::class.java)

    @Test fun retryAfterFreeingStorageUsesTheSameJournalWithoutRestarting() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "queue-retry-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val attempts = AtomicInteger()
        try {
            val queue = ProcessingQueue(context, scope, PhotoSink { file, _ ->
                if (attempts.incrementAndGet() == 1) throw java.io.IOException("Out of space")
                assertArrayEquals(byteArrayOf(1, 2, 3), file.readBytes())
                Uri.EMPTY
            })
            val job = ProcessingJob("retry", listOf("retry-0.jpg"), emptyList(), ShootingMode.SINGLE, 95, null, true, true, captureIntent = true)
            queue.reserve()
            queue.recordIntent(job)
            File(queue.directory, job.jpegNames.single()).writeBytes(byteArrayOf(1, 2, 3))
            queue.finishReserved(job)
            withTimeout(5000) { queue.state.first { it.count == 0 && it.failedCount == 1 } }
            queue.retryFailed()
            withTimeout(5000) { queue.state.first { it.count == 0 && it.lastSaved == job.id } }
            assertEquals(0, queue.state.value.failedCount)
            assertEquals(2, attempts.get())
            assertTrue(queue.directory.listFiles()!!.isEmpty())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun interruptedRawBracketRecoversOnlyCommittedFramesAndKeepsUnpairedJpeg() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "queue-bracket-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val work = File(root, "capture-work").apply { mkdirs() }
        val id = "interrupted"
        val jpegs = (0..2).map { "$id-$it.jpg" }
        val raws = (0..2).map { "$id-$it.dng" }
        val job = ProcessingJob(id, jpegs, raws, ShootingMode.FOCUS_BRACKET, 95, null, false, false, captureIntent = true)
        atomicWrite(File(work, "$id.json"), appJson.encodeToString(job).encodeToByteArray())
        val completed = listOf(jpegs[0], raws[0], jpegs[1])
        completed.forEach { File(work, it).writeText(it) }
        File(work, ".${raws[1]}-123.capture-part").writeText("incomplete")
        val published = mutableListOf<String>()
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val queue = ProcessingQueue(context, scope, PhotoSink { file, name ->
                assertEquals(name, file.readText())
                published += name
                Uri.EMPTY
            })
            withTimeout(5000) { queue.state.first { it.lastSaved == id && it.count == 0 } }
            assertEquals(listOf(raws[0], jpegs[1]), published)
            assertNotNull(queue.state.value.error)
            assertTrue(work.listFiles()!!.isEmpty())
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }

    @Test fun failedPublicationRetainsSourceAndRecoversExactlyOnce() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "queue-recovery-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val work = File(root, "capture-work").apply { mkdirs() }
        val source = File(work, "test-job-0.jpg").apply { writeBytes(byteArrayOf(1, 3, 5, 7)) }
        val job = ProcessingJob("test-job", listOf(source.name), emptyList(), ShootingMode.SINGLE, 95, null, true, true)
        val manifest = File(work, "test-job.json")
        atomicWrite(manifest, appJson.encodeToString(job).encodeToByteArray())
        val firstScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        val secondScope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val first = ProcessingQueue(context, firstScope, PhotoSink { _, _ -> error("simulated storage failure") })
            withTimeout(5000) { first.state.first { it.error != null && it.count == 0 } }
            assertTrue(manifest.isFile); assertArrayEquals(byteArrayOf(1, 3, 5, 7), source.readBytes())
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            val publishes = AtomicInteger()
            val second = ProcessingQueue(context, secondScope, PhotoSink { file, name ->
                assertEquals(source.name, name)
                assertArrayEquals(byteArrayOf(1, 3, 5, 7), file.readBytes())
                publishes.incrementAndGet(); Uri.EMPTY
            })
            withTimeout(5000) { second.state.first { it.lastSaved == job.id && it.count == 0 } }
            assertEquals(1, publishes.get())
            assertFalse(manifest.exists()); assertFalse(source.exists())
        } finally {
            firstScope.coroutineContext[Job]!!.cancelAndJoin()
            secondScope.coroutineContext[Job]!!.cancelAndJoin()
            root.deleteRecursively()
        }
    }

    @Test fun acquisitionBackpressureBlocksUntilAReservedSlotIsReleased() = runBlocking<Unit> {
        val base = InstrumentationRegistry.getInstrumentation().targetContext
        val root = File(base.cacheDir, "queue-pressure-${System.nanoTime()}").apply { mkdirs() }
        val context = object : ContextWrapper(base) { override fun getFilesDir() = root }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
        try {
            val queue = ProcessingQueue(context, scope, PhotoSink { _, _ -> error("No image should be published") })
            repeat(4) { queue.reserve() }
            val blocked = async { queue.reserve() }
            delay(100)
            assertFalse("The fifth acquisition must wait rather than allocate another frame", blocked.isCompleted)
            queue.releaseReservation()
            withTimeout(3000) { blocked.await() }
            repeat(4) { queue.releaseReservation() }
        } finally { scope.coroutineContext[Job]!!.cancelAndJoin(); root.deleteRecursively() }
    }
}
