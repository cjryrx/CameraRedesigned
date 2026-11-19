package com.cjryrx.cameraredesigned.data

import android.content.Context
import android.content.Intent
import androidx.core.content.ContextCompat
import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.domain.ShootingMode
import com.cjryrx.cameraredesigned.imaging.ImageProcessor
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.Channel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Semaphore
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.UUID
import java.util.concurrent.atomic.AtomicBoolean

@Serializable
data class ProcessingJob(
    val id: String,
    val jpegNames: List<String>,
    val rawNames: List<String>,
    val mode: ShootingMode,
    val quality: Int,
    val filter: FilterSpec?,
    val keepSources: Boolean,
    val publishJpeg: Boolean,
    val captureIntent: Boolean = false,
)

data class ProcessingState(val count: Int = 0, val lastSaved: String? = null, val error: String? = null, val failedCount: Int = 0)

/** Durable bounded queue. Acquisition must reserve a slot before allocating a full-resolution frame. */
class ProcessingQueue(private val context: Context, scope: CoroutineScope,
    private val publisher: PhotoSink = PhotoPublisher(context)) {
    val directory = File(context.filesDir, "capture-work").apply { mkdirs() }
    private val slots = Semaphore(4)
    private val channel = Channel<ProcessingJob>(4)
    private val recoveryComplete = CompletableDeferred<Unit>()
    private val mutableState = MutableStateFlow(ProcessingState())
    val state = mutableState.asStateFlow()
    private val processor = ImageProcessor()
    private val failed = mutableMapOf<String, ProcessingJob>()
    private val serviceRequested = AtomicBoolean(false)

    init {
        scope.launch(Dispatchers.IO) {
            for (job in channel) {
                try {
                    val warning = process(job)
                    // If the process stops during cleanup, this marker prevents another gallery publication.
                    val done = File(directory, "${job.id}.done")
                    Files.move(manifest(job).toPath(), done.toPath(), StandardCopyOption.ATOMIC_MOVE)
                    cleanCompleted(job, done)
                    mutableState.update { it.copy(lastSaved = job.id, error = warning ?: it.error) }
                } catch (e: CancellationException) { throw e }
                catch (e: Exception) {
                    synchronized(failed) {
                        failed[job.id] = job
                        mutableState.update { it.copy(failedCount = failed.size, error = "${e.message ?: e.javaClass.simpleName} — sources retained / 源片已保留") }
                    }
                } finally {
                    mutableState.update { it.copy(count = (it.count - 1).coerceAtLeast(0)) }
                    slots.release()
                }
            }
        }
        scope.launch(Dispatchers.IO) {
            try {
                // Producers await recoveryComplete before starting, so these uncommitted writes are inactive.
                directory.listFiles { f -> f.name.endsWith(".capture-part") || f.name.startsWith(".image-") }?.forEach { it.delete() }
                val journals = directory.listFiles().orEmpty().map { file ->
                    if (file.name.endsWith(".json.bak")) File(directory, file.name.removeSuffix(".bak")) else file
                }.filter { it.extension == "json" || it.extension == "done" }.distinct()
                journals.sortedBy { it.lastModified() }.forEach { file ->
                    try {
                        val job = appJson.decodeFromString<ProcessingJob>(android.util.AtomicFile(file).readFully().decodeToString())
                        require(job.id == file.nameWithoutExtension && job.id.matches(Regex("[a-zA-Z0-9_-]+")))
                        require((job.jpegNames + job.rawNames).all { it == File(it).name && it.startsWith("${job.id}-") })
                        if (file.extension == "done") cleanCompleted(job, file)
                        else {
                            slots.acquire()
                            finishReserved(job)
                        }
                    } catch (e: CancellationException) { throw e }
                    catch (e: Exception) { mutableState.update { it.copy(error = "Recovery: ${e.message}") } }
                }
            } finally { recoveryComplete.complete(Unit) }
        }
    }

    suspend fun reserve(onWait: () -> Unit = {}) {
        recoveryComplete.await()
        if (!slots.tryAcquire()) { onWait(); slots.acquire() }
    }
    fun releaseReservation() = slots.release()
    fun newId(): String = "CR_${System.currentTimeMillis()}_${UUID.randomUUID().toString().take(8)}"
    fun dismissError() { mutableState.update { it.copy(error = null) } }

    suspend fun retryFailed() {
        val jobs = synchronized(failed) {
            failed.values.toList().also {
                failed.clear()
                mutableState.update { state -> state.copy(failedCount = 0, error = null) }
            }
        }
        withContext(NonCancellable) {
            for (job in jobs) {
                reserve()
                finishReserved(job)
            }
        }
    }

    private fun manifest(job: ProcessingJob) = File(directory, "${job.id}.json")

    private fun cleanCompleted(job: ProcessingJob, marker: File) {
        val files = (job.jpegNames + job.rawNames + "${job.id}-output.jpg").map { File(directory, it) }
        if (files.all { !it.exists() || it.delete() }) marker.delete()
    }

    /** Persist expected output names before asking the sensor for the first exposure. */
    suspend fun recordIntent(job: ProcessingJob) = withContext(Dispatchers.IO) {
        require(job.captureIntent)
        val target = manifest(job)
        check(!target.exists())
        val part = File(directory, "${job.id}.json.capture-part")
        try {
            part.outputStream().use {
                it.write(appJson.encodeToString(job).encodeToByteArray())
                it.fd.sync()
            }
            Files.move(part.toPath(), target.toPath(), StandardCopyOption.ATOMIC_MOVE)
            Unit
        } finally { part.delete() }
    }

    /** Takes ownership of the reservation, including the zero-frame and failure paths. */
    suspend fun finishReserved(job: ProcessingJob): Boolean = withContext(NonCancellable + Dispatchers.IO) {
        var handedOff = false
        try {
            val ready = if (job.captureIntent) committedFrames(job) else job
            if (ready == null) {
                manifest(job).delete()
                return@withContext false
            }
            mutableState.update { it.copy(count = it.count + 1) }
            try {
                channel.send(ready)
                handedOff = true
            } finally {
                if (!handedOff) mutableState.update { it.copy(count = it.count - 1) }
            }
            true
        } finally { if (!handedOff) slots.release() }
    }

    private fun committedFrames(job: ProcessingJob): ProcessingJob? {
        fun committed(names: List<String>) = names.filter { File(directory, it).length() > 0 }
        val jpegs = committed(job.jpegNames)
        val raws = committed(job.rawNames)
        if (jpegs.isEmpty() && raws.isEmpty()) return null
        if (jpegs.size == job.jpegNames.size && raws.size == job.rawNames.size) return job
        mutableState.update { it.copy(error = "Capture interrupted. Recovering ${jpegs.size} JPEG, ${raws.size} DNG. / 拍摄中断，正在恢复已写入的照片。") }
        return job.copy(mode = ShootingMode.SINGLE, jpegNames = jpegs, rawNames = raws)
    }

    fun ensureService() {
        if (!serviceRequested.compareAndSet(false, true)) return
        try {
            ContextCompat.startForegroundService(context, Intent(context, ProcessingService::class.java))
        } catch (e: Exception) { serviceRequested.set(false); throw e }
    }

    internal fun serviceStopping() { serviceRequested.set(false) }

    private suspend fun process(job: ProcessingJob): String? {
        job.rawNames.forEach { publisher.publish(File(directory, it), it) }
        val files = job.jpegNames.map { File(directory, it) }
        val bracket = job.mode == ShootingMode.EXPOSURE_BRACKET || job.mode == ShootingMode.FOCUS_BRACKET
        if (bracket && files.size > 1) {
            val output = File(directory, "${job.id}-output.jpg")
            try {
                // ExposurePlan takes the neutral frame first, even for five- and seven-frame brackets.
                if (job.mode == ShootingMode.EXPOSURE_BRACKET) processor.mergeExposure(files, output, job.quality, job.filter, referenceIndex = 0)
                else processor.mergeFocus(files, output, job.quality, job.filter)
            } catch (e: IllegalArgumentException) {
                // An unalignable scene cannot be fixed by restarting the app. Deliver the actual exposures.
                if (job.publishJpeg) publishSources(job, files)
                return "Fusion failed. Source photos saved / 合成失败，已保存源片。 ${e.message}"
            }
            publisher.publish(output, "${job.id}-merged.jpg")
            if (job.keepSources && job.publishJpeg) publishSources(job, files)
        } else {
            // In an interrupted RAW-only bracket, rescue the JPEG only for frames missing their DNG.
            val deliver = if (job.publishJpeg) files else files.filter { it.nameWithoutExtension + ".dng" !in job.rawNames }
            publishSources(job, deliver)
        }
        return null
    }

    private suspend fun publishSources(job: ProcessingJob, files: List<File>) {
        for (file in files) {
            if (job.filter == null) publisher.publish(file, file.name)
            else {
                val output = File(directory, "${job.id}-output.jpg")
                processor.process(file, output, job.filter, job.quality)
                publisher.publish(output, file.name)
            }
        }
    }
}
