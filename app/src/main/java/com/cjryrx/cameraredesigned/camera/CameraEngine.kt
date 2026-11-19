package com.cjryrx.cameraredesigned.camera

import android.Manifest
import android.annotation.SuppressLint
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCaptureSession
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraDevice
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureFailure
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.DngCreator
import android.hardware.camera2.TotalCaptureResult
import android.hardware.camera2.params.ColorSpaceTransform
import android.hardware.camera2.params.MeteringRectangle
import android.hardware.camera2.params.OutputConfiguration
import android.hardware.camera2.params.RggbChannelVector
import android.hardware.camera2.params.SessionConfiguration
import android.media.Image
import android.media.ImageReader
import android.os.Handler
import android.os.Build
import android.os.HandlerThread
import android.os.SystemClock
import android.util.Size
import android.view.Surface
import com.cjryrx.cameraredesigned.domain.*
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.async
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.Executor
import java.util.concurrent.atomic.AtomicLong
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicReference
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.math.sqrt

/**
 * One Camera2 owner. All device/session/request state belongs to cameraThread; imageThread only copies
 * buffers before closing Images. JPEG and RAW are paired strictly by sensor timestamp. No borrowed
 * Image can escape capture. Public mutation methods are safe from the main thread.
 */
class CameraEngine(context: Context) : AutoCloseable {
    private val app = context.applicationContext
    private val manager = app.getSystemService(CameraManager::class.java)
    private val cameraThread = HandlerThread("CameraControl").apply { start() }
    private val imageThread = HandlerThread("CameraImages").apply { start() }
    private val handler = Handler(cameraThread.looper)
    private val imageHandler = Handler(imageThread.looper)
    private val executor = Executor { handler.post(it) }
    private val captureMutex = Mutex()
    private val epoch = AtomicLong(0)
    private val _state = MutableStateFlow(EngineState())
    val state: StateFlow<EngineState> = _state.asStateFlow()

    fun clearError() { _state.update { it.copy(error = null) } }

    /** Display rotation (including 180-degree changes) does not require reopening the sensor. */
    fun updateDisplayRotation(rotation: Int) {
        require(rotation in Surface.ROTATION_0..Surface.ROTATION_270)
        handler.post {
            if (closed || displayRotation == rotation) return@post
            displayRotation = rotation
            repeating() // AF/AE regions use the inverse of the current display transform.
        }
    }
    private val resultState = MutableStateFlow<ResultSnapshot?>(null)
    private var device: CameraDevice? = null
    private var session: CameraCaptureSession? = null
    private var characteristics: CameraCharacteristics? = null
    private var jpegReader: ImageReader? = null
    private var rawReader: ImageReader? = null
    private var preview: Surface? = null
    // A normal/RAW capture owns one entry. JPEG burst owns at most three; only the camera
    // handler reads or mutates entries, while each coroutine owns its completed image bytes.
    private val pending = LinkedHashMap<Long, PendingCapture>()
    private val activeBurst = AtomicReference<BurstControl?>(null)
    private var settings = CameraSettings()
    private var exposure = ExposureSettings()
    private var closed = false
    private var displayRotation = 0
    private var viewWidth = 0
    private var viewHeight = 0
    private var holdFocus = false
    private var focusX = .5f
    private var focusY = .5f
    private var targetedFocus = false
    private var manualExposure = 10_000_000L
    private var manualIso = 100
    private var automaticAperture: Float? = null
    private var lastMeteringUpdate = 0L
    private var lastMeteringSample = 0L
    private var temporarySpec: CaptureSpec? = null
    private var sequenceNumber = 0L
    private var requestNumber = 0L
    private var calibratedColorTransform: ColorSpaceTransform? = null
    private val deviceLifetimes = mutableSetOf<CompletableDeferred<Unit>>()
    @Volatile private var lensAliases: Map<String, String> = emptyMap()
    // Serialized by captureMutex, including cancellation; epoch prevents cross-lens comparison.
    private var previousFocusCapture: Triple<Long, Float, Float>? = null

    init { handler.post { refreshLenses() } }

    /** Call before creating the Surface; set this size on SurfaceTexture.setDefaultBufferSize. */
    fun previewSize(lensId: String? = null, width: Int, height: Int): Size {
        val id = chooseLens(lensId)
        val c = manager.getCameraCharacteristics(id)
        val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val photo = map.getOutputSizes(ImageFormat.JPEG)?.maxByOrNull { it.width.toLong() * it.height }
            ?: throw CameraFailure("This lens has no JPEG output")
        val ratio = photo.width.toDouble() / photo.height
        val candidates = map.getOutputSizes(android.graphics.SurfaceTexture::class.java).orEmpty()
        val sameAspect = candidates.filter { abs(it.width.toDouble() / it.height - ratio) < .015 }
        val sizes = sameAspect.ifEmpty { candidates.toList() }
        val bounded = sizes.filter { it.width <= 1920 && it.height <= 1440 }
        val target = max(width, height).coerceAtMost(1920)
        return (bounded.ifEmpty { sizes }).minByOrNull {
            abs(it.width - target).toLong() + if (it.width < target) 1000 else 0
        } ?: throw CameraFailure("This lens has no preview output")
    }

    /** displayRotation accepts Surface.ROTATION_* values, not degrees. Caller owns surface. */
    fun start(surface: Surface, width: Int, height: Int, lensId: String? = null, displayRotation: Int = 0) {
        val generation = epoch.incrementAndGet()
        handler.post {
            if (closed) return@post
            closeSession(CameraFailure("Camera configuration changed"))
            preview = surface
            viewWidth = width
            viewHeight = height
            this.displayRotation = displayRotation
            if (!surface.isValid) { fail("Preview surface is unavailable"); return@post }
            open(generation, lensId)
        }
    }

    fun stop() {
        epoch.incrementAndGet()
        handler.post {
            closeSession(CameraFailure("Camera stopped"))
            preview = null
        }
    }

    /** Acknowledges CameraDevice.onClosed before a caller disposes its producer SurfaceTexture. */
    suspend fun stopAndAwait(timeoutMillis: Long = 10_000) {
        epoch.incrementAndGet()
        val closing = CompletableDeferred<List<CompletableDeferred<Unit>>>()
        if (!handler.post {
            closeSession(CameraFailure("Camera stopped"))
            preview = null
            closing.complete(deviceLifetimes.toList())
        }) return
        withTimeout(timeoutMillis) { closing.await().forEach { it.await() } }
    }

    override fun close() {
        epoch.incrementAndGet()
        handler.post {
            if (closed) return@post
            closed = true
            closeSession(CameraFailure("Camera closed"))
            preview = null
            imageThread.quitSafely()
            cameraThread.quitSafely()
        }
    }

    fun update(settings: CameraSettings, exposure: ExposureSettings) {
        handler.post {
            if (closed) return@post
            val changedOutputs = outputProfile(this.settings) != outputProfile(settings)
            val changedArea = this.settings.focusArea != settings.focusArea
            this.settings = settings
            this.exposure = exposure
            if (changedArea) { focusX = .5f; focusY = .5f; targetedFocus = false }
            if (changedOutputs && device != null) {
                val surface = preview ?: return@post
                start(surface, viewWidth, viewHeight, state.value.lensId, displayRotation)
            } else repeating()
        }
    }

    /** Normalized upright, mirrored-as-displayed coordinates; preview must be uncropped. */
    fun focusAt(x: Float, y: Float, targeted: Boolean = true) {
        handler.post {
            if (temporarySpec?.focusDiopters != null) return@post
            focusX = x.coerceIn(0f, 1f); focusY = y.coerceIn(0f, 1f)
            targetedFocus = targeted
            holdFocus = true
            requestNumber++
            _state.update { it.copy(focusing = true, focused = false) }
            val d = device ?: return@post
            val s = session ?: return@post
            runCatching {
                val cancel = previewRequest(d)
                cancel.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_CANCEL)
                s.capture(cancel.build(), resultCallback, handler)
                repeating()
                val trigger = previewRequest(d)
                trigger.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_START)
                s.capture(trigger.build(), resultCallback, handler)
            }.onFailure { fail("Unable to focus", it) }
        }
    }

    /** Updates model-selected metering target while leaving continuous AF running. */
    fun updateFocusRegion(x: Float, y: Float, targeted: Boolean = true) {
        handler.post {
            if (temporarySpec?.focusDiopters != null) return@post
            val nx = x.coerceIn(0f, 1f); val ny = y.coerceIn(0f, 1f)
            if (!holdFocus && targeted == targetedFocus && abs(nx - focusX) < .015f && abs(ny - focusY) < .015f) return@post
            focusX = nx; focusY = ny
            targetedFocus = targeted
            holdFocus = false
            requestNumber++
            repeating()
        }
    }

    fun releaseFocus() {
        handler.post {
            holdFocus = false
            focusX = .5f; focusY = .5f
            targetedFocus = false
            if (temporarySpec?.focusDiopters != null) return@post
            requestNumber++
            repeating()
        }
    }

    suspend fun awaitFocus(timeoutMillis: Long = 2_000): Boolean {
        val barrier = CompletableDeferred<Unit>()
        if (!handler.post { barrier.complete(Unit) }) return false
        withTimeoutOrNull(timeoutMillis) { barrier.await() } ?: return false
        if (state.value.capabilities.minFocusDiopters <= 0f) return state.value.ready
        return withTimeoutOrNull(timeoutMillis) { state.first { it.focused || !it.ready }.focused } ?: false
    }

    /** Feed unfiltered preview luma at <=5Hz. Hardware AE priority is preferred when available. */
    fun submitMeteringLuma(mean: Float) {
        if (!mean.isFinite()) return
        handler.post {
            lastMeteringSample = SystemClock.elapsedRealtime()
            if (!usesSoftwareAutoExposure() || pending.isNotEmpty() && activeBurst.get() == null) return@post
            val now = SystemClock.elapsedRealtime()
            if (now - lastMeteringUpdate < 180) return@post
            lastMeteringUpdate = now
            val correction = meteringCorrection(mean, exposure.compensationEv)
            val cap = state.value.capabilities
            val isoRange = cap.isoRange ?: return@post
            val shutterRange = cap.shutterRange ?: return@post
            if (exposure.mode == ExposureMode.S && exposure.iso != null && cap.apertures.size > 1) {
                val current = automaticAperture ?: cap.apertures.first()
                val target = (.45 * 2.0.pow(exposure.compensationEv / 2.2)).coerceIn(.04, .92)
                val desired = current / (target / mean.coerceIn(.01f, 1f)).pow(1.1)
                automaticAperture = cap.apertures.minByOrNull { abs(it - desired) }
            } else if (exposure.mode == ExposureMode.S || exposure.mode == ExposureMode.M) {
                manualIso = (manualIso * correction).roundToInt().coerceIn(isoRange)
            } else {
                manualExposure = (manualExposure * correction).toLong().coerceIn(shutterRange)
                if (exposure.iso == null && (manualExposure == shutterRange.last || manualExposure == shutterRange.first)) {
                    manualIso = (manualIso * correction).roundToInt().coerceIn(isoRange)
                }
            }
            repeating()
        }
    }

    suspend fun capture(spec: CaptureSpec, rawDestination: File? = null, jpegDestination: File? = null): CapturedFrame = captureMutex.withLock {
        require(rawDestination == null || spec.format != PhotoFormat.JPEG) { "RAW destination requires RAW capture" }
        require(jpegDestination == null || spec.format != PhotoFormat.RAW || spec.requireJpeg) { "JPEG destination requires a JPEG output" }
        require(rawDestination == null || jpegDestination == null || rawDestination.canonicalFile != jpegDestination.canonicalFile) { "JPEG and RAW destinations must differ" }
        require(rawDestination?.exists() != true && jpegDestination?.exists() != true) { "Capture destination already exists" }
        val requestEpoch = epoch.get()
        val activeCapture = AtomicBoolean(true)
        val preparation = CompletableDeferred<PreparedCapture>()
        if (!handler.post {
            if (!activeCapture.get()) return@post
            runCatching {
                checkCapture(spec, requestEpoch)
                val changesPreview = spec.shutterNanos != null || spec.iso != null ||
                    spec.focusDiopters != null || spec.compensationEv != null
                if (changesPreview) {
                    temporarySpec = spec
                    requestNumber++
                    repeating()
                }
                val previewExposure = previewExposureBudget(spec)
                preparation.complete(PreparedCapture(requestNumber,
                    cameraStageTimeout(previewExposure, pipelineDepth() + 3, 3_000),
                    cameraStageTimeout(previewExposure, pipelineDepth() + 2, 2_000),
                    settings.flash == FlashMode.ON || settings.flash == FlashMode.AUTO))
            }.onFailure { preparation.completeExceptionally(it) }
        }) preparation.completeExceptionally(CameraFailure("Camera thread is closed"))
        var rawFile: File? = null
        var transaction: PendingCapture? = null
        try {
            val prepared = withTimeout(5_000) { preparation.await() }
            val preparedVersion = prepared.version
            if (spec.focusDiopters != null) {
                val requested = spec.focusDiopters.coerceIn(0f, state.value.capabilities.minFocusDiopters)
                val settling = FocusSettling(requested, state.value.capabilities.calibratedFocus)
                val settled = withTimeoutOrNull(prepared.focusTimeoutMillis) {
                    resultState.first {
                        it != null && it.epoch == requestEpoch && it.requestVersion == preparedVersion &&
                            settling.observe(it.frameNumber, it.requestedFocus, it.reportedFocus, it.lensStationary,
                                it.afMode == CaptureRequest.CONTROL_AF_MODE_OFF)
                    }
                }
                if (settled == null) throw CameraFailure("Lens did not settle: target=$requested, expectedVersion=$preparedVersion, " +
                    "epoch=$requestEpoch, calibrated=${state.value.capabilities.calibratedFocus}, " +
                    "maxDistance=${state.value.capabilities.minFocusDiopters}, last=${resultState.value}")
                previousFocusCapture?.takeIf { it.first == requestEpoch }?.let { previous ->
                    if (!focusMoved(previous.second, previous.third, requested, settled.focus)) {
                        throw CameraFailure("Lens did not move between bracket targets: requested ${previous.second} -> $requested, " +
                            "reported ${previous.third} -> ${settled.focus}")
                    }
                }
            }
            // Let AE process an EV bracket before releasing the still shutter.
            if (spec.compensationEv != null && spec.shutterNanos == null) {
                val converged = withTimeoutOrNull(prepared.aeTimeoutMillis) {
                    resultState.first { it != null && it.epoch == requestEpoch && it.requestVersion == preparedVersion && it.aeConverged }
                }
                if (converged == null) throw CameraFailure("Exposure did not converge for this bracket")
            }
            if (prepared.prepareFlash) prepareFlash(requestEpoch, prepared.aeTimeoutMillis, activeCapture)
            val completed = CompletableDeferred<Acquired>()
            val accepted = CompletableDeferred<Long>()
            val operation = PendingCapture(0, requestEpoch, spec, completed)
            transaction = operation
            if (!handler.post {
                if (!activeCapture.get()) { accepted.cancel(); completed.cancel(); return@post }
                try {
                    checkCapture(spec, requestEpoch)
                    val d = requireNotNull(device)
                    val s = requireNotNull(session)
                    val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                    applyControls(b, spec, still = true)
                    val needJpeg = spec.format != PhotoFormat.RAW || spec.requireJpeg
                    val needRaw = spec.format != PhotoFormat.JPEG
                    if (needJpeg) b.addTarget(requireNotNull(jpegReader).surface)
                    if (needRaw) b.addTarget(requireNotNull(rawReader).surface)
                    b.set(CaptureRequest.JPEG_QUALITY, spec.jpegQuality.coerceIn(1, 100).toByte())
                    b.set(CaptureRequest.JPEG_ORIENTATION, spec.orientation)
                    val tag = ++sequenceNumber
                    b.setTag(tag)
                    operation.tag = tag
                    pending[tag] = operation
                    operation.sequenceId = s.capture(b.build(), captureCallback, handler)
                    val exposureTime = b.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: state.value.exposureNanos
                    // A still request can sit behind already-exposing preview frames. Account for
                    // those frames and the device-declared JPEG/RAW encoder stall, not just shutter.
                    val stall = outputStallNanos(spec)
                    accepted.complete(cameraStageTimeout(max(exposureTime, state.value.exposureNanos), pipelineDepth() + 1,
                        cameraStageTimeout(stall, 1, 10_000)))
                } catch (e: Exception) {
                    accepted.completeExceptionally(e)
                    completed.completeExceptionally(e)
                }
            }) accepted.completeExceptionally(CameraFailure("Camera thread is closed"))
            val timeout = withTimeout(5_000) { accepted.await() }
            val acquired = withTimeout(timeout) { completed.await() }
            previousFocusCapture = spec.focusDiopters?.let { target ->
                acquired.result[CaptureResult.LENS_FOCUS_DISTANCE]?.let { actual -> Triple(requestEpoch, target, actual) }
            }
            if (jpegDestination != null || acquired.raw != null) {
                // A complete sensor frame is already owned. Commit JPEG before slower RAW encoding,
                // and finish these local writes even if a screen/lifecycle cancellation arrives.
                withContext(Dispatchers.IO + NonCancellable) {
                    if (jpegDestination != null) {
                        commitCaptureFile(jpegDestination) { it.write(requireNotNull(acquired.jpeg)) }
                    }
                    if (acquired.raw != null) {
                        val file = rawDestination ?: File(File(app.filesDir, "capture-spool"), "raw-${UUID.randomUUID()}.dng")
                        // Assign before dispatcher return so default temporary-file cleanup also works
                        // under prompt coroutine cancellation. Explicit destinations are never deleted.
                        rawFile = file
                        DngCreator(acquired.characteristics, acquired.result).use { creator ->
                            creator.setOrientation(when (spec.orientation) { 90 -> 6; 180 -> 3; 270 -> 8; else -> 1 })
                            acquired.raw.file.inputStream().buffered().use { pixels ->
                                commitCaptureFile(file) { output ->
                                    val buffered = output.buffered()
                                    creator.writeInputStream(buffered, acquired.raw.size, pixels, 0)
                                    buffered.flush()
                                }
                            }
                        }
                    }
                }
                currentCoroutineContext().ensureActive()
            }
            CapturedFrame(acquired.jpeg, rawFile, metadata(acquired, spec))
        } catch (e: Exception) {
            if (rawDestination == null) rawFile?.delete()
            throw e
        } finally {
            activeCapture.set(false)
            withContext(NonCancellable) {
                val cleanup = CompletableDeferred<Unit>()
                if (!handler.post {
                    transaction?.rawSpool?.delete()
                    if (requestEpoch == epoch.get()) {
                        transaction?.let { pending.remove(it.tag)?.pairer?.close(); it.completion.cancel() }
                        if (temporarySpec != null) {
                            temporarySpec = null
                            repeating()
                        }
                    }
                    cleanup.complete(Unit)
                }) { transaction?.rawSpool?.delete(); cleanup.complete(Unit) }
                cleanup.await()
            }
        }
    }

    /** Stop future JPEG burst submissions. Already submitted requests are drained and saved. */
    fun stopBurst() { activeBurst.get()?.stop() }

    /**
     * Full-resolution JPEG pipeline. fps=0 means as fast as the sensor and durable storage permit.
     * prepare must reserve storage/backpressure and commit a write-ahead intent before returning.
     * completed is called exactly once per returned file, in submission order, on the caller's
     * coroutine context; a stopped, unsubmitted frame reports CancellationException. These callbacks
     * never overlap. First submission survives a quick finger release, but not lifecycle cancellation.
     * Up to three requests can already be queued at release; abortCaptures would destroy their images.
     * Errors are thrown only after all owned files and callbacks have drained. RAW/preflash/brackets
     * use capture(); burst-capability-less cameras use this same full-resolution path one at a time.
     */
    suspend fun captureJpegBurst(
        spec: CaptureSpec,
        fps: Int,
        prepare: suspend (index: Int) -> File?,
        completed: suspend (file: File, result: Result<CaptureMetadata>) -> Unit,
    ) = captureMutex.withLock {
        require(spec.format == PhotoFormat.JPEG && spec.shutterNanos == null && spec.iso == null &&
            spec.focusDiopters == null && spec.compensationEv == null) { "JPEG burst requires the current non-bracket exposure" }
        require(fps >= 0)
        val generation = epoch.get()
        val control = BurstControl()
        check(activeBurst.compareAndSet(null, control))
        try {
            val capacity = CompletableDeferred<Int>()
            if (!handler.post {
                try {
                    checkCapture(spec, generation)
                    check(settings.flash == FlashMode.OFF || settings.flash == FlashMode.TORCH) { "JPEG burst requires flash Off or Torch" }
                    val capabilities = characteristics?.get(CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES) ?: intArrayOf()
                    capacity.complete(if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_BURST_CAPTURE in capabilities) 3 else 1)
                } catch (e: Exception) { capacity.completeExceptionally(e) }
            }) capacity.completeExceptionally(CameraFailure("Camera thread is closed"))
            val maximumInFlight = withTimeout(5_000) { capacity.await() }
            _state.update { it.copy(burstCapacity = maximumInFlight, burstPeakInFlight = 0) }
            var first = true
            runBurstPipeline(control, fps, maximumInFlight, prepare, submit = { scope, file ->
                enqueueBurstJpeg(scope, spec, file, generation, control, first, fastProcessing = fps == 0 || fps > 2).also { first = false }
            }, completed = completed)
        } finally {
            control.stop()
            activeBurst.compareAndSet(control, null)
            _state.update { it.copy(burstCapacity = 0, burstInFlight = 0) }
        }
    }

    private fun enqueueBurstJpeg(scope: CoroutineScope, spec: CaptureSpec, destination: File,
        generation: Long, control: BurstControl, first: Boolean, fastProcessing: Boolean): Deferred<Result<CaptureMetadata>> {
        val operation = PendingCapture(0, generation, spec, CompletableDeferred())
        val accepted = CompletableDeferred<Long>()
        val active = AtomicBoolean(true)
        if (!handler.post {
            if (!active.get()) return@post
            try {
                if (!first && control.isStopped) throw BurstNotSubmitted()
                checkCapture(spec, generation)
                require(!destination.exists()) { "Capture destination already exists: ${destination.name}" }
                val d = requireNotNull(device)
                val s = requireNotNull(session)
                val b = d.createCaptureRequest(CameraDevice.TEMPLATE_STILL_CAPTURE)
                applyControls(b, spec, still = true)
                if (fastProcessing) applyFastBurstProcessing(b)
                b.addTarget(requireNotNull(jpegReader).surface)
                // Give live view a frame during every exposure even if queued still requests leave
                // no gap for repeating preview. This is the existing configured preview surface.
                preview?.takeIf { it.isValid }?.let(b::addTarget)
                b.set(CaptureRequest.JPEG_QUALITY, spec.jpegQuality.coerceIn(1, 100).toByte())
                b.set(CaptureRequest.JPEG_ORIENTATION, spec.orientation)
                operation.tag = ++sequenceNumber
                b.setTag(operation.tag)
                val exposureTime = b.get(CaptureRequest.SENSOR_EXPOSURE_TIME) ?: state.value.exposureNanos
                val queued = pending.size + 1
                val timeout = cameraStageTimeout(max(exposureTime, state.value.exposureNanos), pipelineDepth() + queued,
                    cameraStageTimeout(outputStallNanos(spec), queued, 10_000))
                pending[operation.tag] = operation
                operation.sequenceId = s.capture(b.build(), captureCallback, handler)
                _state.update { it.copy(burstInFlight = pending.size, burstPeakInFlight = maxOf(it.burstPeakInFlight, pending.size)) }
                accepted.complete(timeout)
            } catch (e: Exception) {
                pending.remove(operation.tag)?.pairer?.close()
                accepted.completeExceptionally(e)
                operation.completion.completeExceptionally(e)
            }
        }) accepted.completeExceptionally(CameraFailure("Camera thread is closed"))

        // Start immediately, so cancellation between returning a file and the next suspension cannot
        // discard the owner of an exposure. Saving is bounded by the number of submitted requests.
        return scope.async(start = CoroutineStart.UNDISPATCHED) {
            withContext(NonCancellable) {
                try {
                    val timeout = withTimeout(5_000) { accepted.await() }
                    val acquired = withTimeout(timeout) { operation.completion.await() }
                    withContext(Dispatchers.IO) {
                        commitCaptureFile(destination) { it.write(requireNotNull(acquired.jpeg)) }
                    }
                    Result.success(metadata(acquired, spec))
                } catch (e: Exception) {
                    if (e !is BurstNotSubmitted) control.stop()
                    Result.failure(e)
                } finally {
                    active.set(false)
                    val cleaned = CompletableDeferred<Unit>()
                    if (!handler.post {
                        pending.remove(operation.tag)?.pairer?.close()
                        operation.completion.cancel()
                        _state.update { it.copy(burstInFlight = pending.size) }
                        cleaned.complete(Unit)
                    }) cleaned.complete(Unit)
                    cleaned.await()
                }
            }
        }
    }

    private fun metadata(acquired: Acquired, spec: CaptureSpec): CaptureMetadata {
        val r = acquired.result
        return CaptureMetadata(
            timestamp = requireNotNull(r[CaptureResult.SENSOR_TIMESTAMP]), orientation = spec.orientation,
            exposureNanos = r[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 0,
            iso = r[CaptureResult.SENSOR_SENSITIVITY] ?: 0,
            focusDiopters = r[CaptureResult.LENS_FOCUS_DISTANCE] ?: 0f,
            aperture = r[CaptureResult.LENS_APERTURE] ?: 0f,
            rotateAndCrop = if (Build.VERSION.SDK_INT >= 31) r[CaptureResult.SCALER_ROTATE_AND_CROP] else null,
            compensationEv = (r[CaptureResult.CONTROL_AE_EXPOSURE_COMPENSATION] ?: 0) *
                (acquired.characteristics[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f),
            aeMode = r[CaptureResult.CONTROL_AE_MODE], awbMode = r[CaptureResult.CONTROL_AWB_MODE],
            whiteBalanceGains = r[CaptureResult.COLOR_CORRECTION_GAINS]?.let { listOf(it.red, it.greenEven, it.greenOdd, it.blue) },
            pipelineDepth = r[CaptureResult.REQUEST_PIPELINE_DEPTH]?.toInt(),
            flashMode = r[CaptureResult.FLASH_MODE], flashState = r[CaptureResult.FLASH_STATE],
            aeState = r[CaptureResult.CONTROL_AE_STATE],
            processingModes = buildMap {
                r[CaptureResult.NOISE_REDUCTION_MODE]?.let { put("noiseReduction", it) }
                r[CaptureResult.EDGE_MODE]?.let { put("edge", it) }
                r[CaptureResult.SHADING_MODE]?.let { put("shading", it) }
                r[CaptureResult.TONEMAP_MODE]?.let { put("tonemap", it) }
                r[CaptureResult.HOT_PIXEL_MODE]?.let { put("hotPixel", it) }
                r[CaptureResult.COLOR_CORRECTION_ABERRATION_MODE]?.let { put("aberration", it) }
                r[CaptureResult.DISTORTION_CORRECTION_MODE]?.let { put("distortion", it) }
            },
        )
    }

    /** Camera2 FAST processing must not stall the sensor. Only medium/high/maximum JPEG burst
     * selects it; low-rate, single and fused-source captures keep the still template's defaults.
     * Resolution, JPEG compression quality, white balance and the user's filter remain identical. */
    private fun applyFastBurstProcessing(b: CaptureRequest.Builder) {
        val c = requireNotNull(characteristics)
        fun select(key: CaptureRequest.Key<Int>, modes: CameraCharacteristics.Key<IntArray>, value: Int) {
            if (key in c.availableCaptureRequestKeys && value in (c[modes] ?: intArrayOf())) b.set(key, value)
        }
        select(CaptureRequest.NOISE_REDUCTION_MODE, CameraCharacteristics.NOISE_REDUCTION_AVAILABLE_NOISE_REDUCTION_MODES, CaptureRequest.NOISE_REDUCTION_MODE_FAST)
        select(CaptureRequest.EDGE_MODE, CameraCharacteristics.EDGE_AVAILABLE_EDGE_MODES, CaptureRequest.EDGE_MODE_FAST)
        select(CaptureRequest.SHADING_MODE, CameraCharacteristics.SHADING_AVAILABLE_MODES, CaptureRequest.SHADING_MODE_FAST)
        select(CaptureRequest.TONEMAP_MODE, CameraCharacteristics.TONEMAP_AVAILABLE_TONE_MAP_MODES, CaptureRequest.TONEMAP_MODE_FAST)
        select(CaptureRequest.HOT_PIXEL_MODE, CameraCharacteristics.HOT_PIXEL_AVAILABLE_HOT_PIXEL_MODES, CaptureRequest.HOT_PIXEL_MODE_FAST)
        select(CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE, CameraCharacteristics.COLOR_CORRECTION_AVAILABLE_ABERRATION_MODES, CaptureRequest.COLOR_CORRECTION_ABERRATION_MODE_FAST)
        select(CaptureRequest.DISTORTION_CORRECTION_MODE, CameraCharacteristics.DISTORTION_CORRECTION_AVAILABLE_MODES, CaptureRequest.DISTORTION_CORRECTION_MODE_FAST)
    }

    @SuppressLint("MissingPermission")
    private fun open(generation: Long, requestedId: String?) {
        val lifetime = CompletableDeferred<Unit>()
        try {
            if (app.checkSelfPermission(Manifest.permission.CAMERA) != PackageManager.PERMISSION_GRANTED) {
                throw CameraFailure("Camera permission is required")
            }
            refreshLenses()
            val id = chooseLens(requestedId)
            val c = manager.getCameraCharacteristics(id)
            characteristics = c
            val cap = capabilities(id, c)
            automaticAperture = cap.apertures.firstOrNull()
            manualIso = (cap.isoRange?.first ?: 100).coerceAtLeast(100)
            manualExposure = 10_000_000L
            lastMeteringSample = 0
            calibratedColorTransform = null
            val size = previewSize(id, viewWidth, viewHeight)
            _state.update { it.copy(capabilities = cap, lensId = id, ready = false, error = null,
                previewWidth = size.width, previewHeight = size.height, sensorOrientation = cap.sensorOrientation,
                frontFacing = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT) }
            deviceLifetimes += lifetime
            manager.openCamera(id, executor, object : CameraDevice.StateCallback() {
                override fun onClosed(camera: CameraDevice) {
                    deviceLifetimes.remove(lifetime)
                    lifetime.complete(Unit)
                }
                override fun onOpened(camera: CameraDevice) {
                    if (generation != epoch.get() || closed) { camera.close(); return }
                    device = camera
                    configure(generation)
                }
                override fun onDisconnected(camera: CameraDevice) {
                    camera.close()
                    if (generation == epoch.get()) { closeSession(CameraFailure("Camera disconnected")); fail("Camera disconnected") }
                }
                override fun onError(camera: CameraDevice, error: Int) {
                    camera.close()
                    if (generation == epoch.get()) { closeSession(CameraFailure("Camera error $error")); fail("Camera error $error") }
                }
            })
        } catch (e: Exception) {
            deviceLifetimes.remove(lifetime)
            lifetime.complete(Unit)
            fail("Cannot open camera", e)
        }
    }

    private fun configure(generation: Long) {
        try {
            val d = requireNotNull(device)
            val c = requireNotNull(characteristics)
            val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            val cap = state.value.capabilities
            val outputs = outputProfile(settings)
            if (outputs.jpeg) {
                jpegReader = ImageReader.newInstance(cap.photoWidth, cap.photoHeight, ImageFormat.JPEG, 3).also {
                    it.setOnImageAvailableListener({ reader -> copyAvailable(reader, generation) }, imageHandler)
                }
            }
            if (outputs.raw) {
                if (!cap.supportsRaw) throw CameraFailure("RAW is not supported by this lens")
                val size = map.getOutputSizes(ImageFormat.RAW_SENSOR).maxBy { it.width.toLong() * it.height }
                rawReader = ImageReader.newInstance(size.width, size.height, ImageFormat.RAW_SENSOR, 2).also {
                    it.setOnImageAvailableListener({ reader -> copyAvailable(reader, generation) }, imageHandler)
                }
            }
            val surfaces = listOfNotNull(preview, jpegReader?.surface, rawReader?.surface)
            val config = SessionConfiguration(SessionConfiguration.SESSION_REGULAR,
                surfaces.map(::OutputConfiguration), executor, object : CameraCaptureSession.StateCallback() {
                    override fun onConfigured(configured: CameraCaptureSession) {
                        if (generation != epoch.get() || closed) { configured.close(); return }
                        session = configured
                        _state.update { it.copy(ready = false, error = null,
                            jpegOutputConfigured = outputs.jpeg, rawOutputConfigured = outputs.raw) }
                        repeating()
                    }
                    override fun onConfigureFailed(failed: CameraCaptureSession) {
                        failed.close()
                        if (generation == epoch.get()) {
                            closeSession(CameraFailure("This lens cannot combine full-resolution outputs"))
                            fail("This lens cannot combine preview with the selected full-resolution outputs (JPEG=${outputs.jpeg}, RAW=${outputs.raw})")
                        }
                    }
                })
            d.createCaptureSession(config)
        } catch (e: Exception) { closeSession(e); fail("Camera session could not be configured", e) }
    }

    private fun copyAvailable(reader: ImageReader, generation: Long) {
        var image: Image? = null
        var rawSpool: File? = null
        var imageTimestamp: Long? = null
        try {
            image = reader.acquireNextImage() ?: return
            if (generation != epoch.get()) return
            val timestamp = image.timestamp
            imageTimestamp = timestamp
            val jpeg: ByteArray?
            val raw: RawCopy?
            if (image.format == ImageFormat.JPEG) {
                val buffer = image.planes[0].buffer
                jpeg = ByteArray(buffer.remaining()).also(buffer::get)
                raw = null
            } else {
                jpeg = null
                val plane = image.planes[0]
                check(plane.pixelStride == 2) { "Unsupported RAW pixel stride" }
                val source = plane.buffer.duplicate()
                val rowBytes = image.width * 2
                val rowBuffer = ByteArray(rowBytes)
                val directory = File(app.cacheDir, "sensor-spool").apply { check(mkdirs() || isDirectory) }
                val file = File.createTempFile("sensor-", ".raw16", directory)
                rawSpool = file
                file.outputStream().buffered(256 * 1024).use { output ->
                    for (row in 0 until image.height) {
                        source.position(row * plane.rowStride)
                        source.get(rowBuffer)
                        output.write(rowBuffer)
                    }
                }
                raw = RawCopy(Size(image.width, image.height), file)
            }
            val posted = handler.post {
                if (generation != epoch.get() || pending.isEmpty()) { raw?.file?.delete(); return@post }
                // JPEG bytes are immutable and shared until onCaptureStarted/result identifies the
                // owner. Each bounded pairer drops other timestamps; no full-size copies are made.
                if (jpeg != null) pending.values.forEach { it.pairer.jpeg(timestamp, jpeg) }
                // RAW is deliberately serial, so one pairer alone owns/deletes the spool file.
                if (raw != null) {
                    val owner = pending.values.singleOrNull { it.spec.format != PhotoFormat.JPEG }
                    if (owner == null) raw.file.delete() else owner.pairer.raw(timestamp, raw)
                }
                completeIfPaired()
            }
            if (posted) rawSpool = null
        } catch (e: Exception) {
            handler.post {
                if (generation == epoch.get()) {
                    // A previous timed-out frame may still finish copying. Its failure must not
                    // cancel the next shot; attach errors to the sensor timestamp like image data.
                    imageTimestamp?.let { timestamp -> pending.values.forEach {
                        it.pairer.failure(timestamp, CameraFailure("Unable to read camera image at $timestamp", e))
                    } }
                    completeIfPaired()
                }
            }
        } finally { image?.close(); rawSpool?.delete() }
    }

    private fun completeIfPaired() {
        pending.values.forEach { p ->
            try {
                val pair = p.pairer.take() ?: return@forEach
                p.rawSpool = pair.raw?.file
                p.completion.complete(Acquired(pair.jpeg, pair.raw, pair.metadata, requireNotNull(characteristics)))
            } catch (e: Exception) { p.completion.completeExceptionally(e) }
        }
    }

    private val captureCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureStarted(session: CameraCaptureSession, request: CaptureRequest, timestamp: Long, frameNumber: Long) {
            val p = pending[request.tag] ?: return
            if (p.epoch != epoch.get() || session !== this@CameraEngine.session) return
            try { p.pairer.expect(timestamp) }
            catch (e: Exception) { p.completion.completeExceptionally(e) }
        }
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val p = pending[request.tag] ?: return
            if (p.epoch != epoch.get() || session !== this@CameraEngine.session) return
            try { p.pairer.result(requireNotNull(result[CaptureResult.SENSOR_TIMESTAMP]), result) }
            catch (e: Exception) { p.completion.completeExceptionally(e); return }
            completeIfPaired()
        }
        override fun onCaptureFailed(session: CameraCaptureSession, request: CaptureRequest, failure: CaptureFailure) {
            val p = pending[request.tag] ?: return
            if (p.epoch == epoch.get() && session === this@CameraEngine.session) {
                p.completion.completeExceptionally(CameraFailure("Capture failed (tag=${p.tag}, reason=${failure.reason}, frame=${failure.frameNumber})"))
            }
        }
        override fun onCaptureBufferLost(session: CameraCaptureSession, request: CaptureRequest, target: Surface, frameNumber: Long) {
            val p = pending[request.tag] ?: return
            if (p.epoch == epoch.get() && session === this@CameraEngine.session) {
                p.completion.completeExceptionally(CameraFailure("Camera lost an image buffer (tag=${p.tag}, frame=$frameNumber)"))
            }
        }
        override fun onCaptureSequenceAborted(session: CameraCaptureSession, sequenceId: Int) {
            if (session !== this@CameraEngine.session) return
            pending.values.filter { it.sequenceId == sequenceId }.forEach {
                it.completion.completeExceptionally(CameraFailure("Capture sequence $sequenceId was aborted"))
            }
        }
    }

    private fun previewRequest(d: CameraDevice): CaptureRequest.Builder = d.createCaptureRequest(CameraDevice.TEMPLATE_PREVIEW).apply {
        addTarget(requireNotNull(preview))
        applyControls(this, temporarySpec)
        setTag(PreviewTag(epoch.get(), requestNumber))
    }

    private fun repeating() {
        val d = device ?: return
        val s = session ?: return
        if (preview?.isValid != true) return
        runCatching { s.setRepeatingRequest(previewRequest(d).build(), resultCallback, handler) }
            .onFailure { fail("Camera settings could not be applied", it) }
    }

    private fun applyControls(b: CaptureRequest.Builder, override: CaptureSpec?, still: Boolean = false) {
        val c = characteristics ?: return
        val cap = state.value.capabilities
        val mode = exposure.mode
        val priority = aePriority()
        val mustManual = manualExposureRequired(override)
        // UI owns sensor-to-display rotation and front-camera mirroring. AUTO can additionally
        // rotate/crop PRIVATE and JPEG streams (and remap AF regions) in compatibility mode.
        // Explicit NONE preserves native sensor geometry; JPEG_ORIENTATION remains independent.
        if (Build.VERSION.SDK_INT >= 31 &&
            CaptureRequest.SCALER_ROTATE_AND_CROP_NONE in (c[CameraCharacteristics.SCALER_AVAILABLE_ROTATE_AND_CROP_MODES] ?: intArrayOf()) &&
            CaptureRequest.SCALER_ROTATE_AND_CROP in c.availableCaptureRequestKeys) {
            b.set(CaptureRequest.SCALER_ROTATE_AND_CROP, CaptureRequest.SCALER_ROTATE_AND_CROP_NONE)
        }
        b.set(CaptureRequest.CONTROL_MODE, CaptureRequest.CONTROL_MODE_AUTO)
        if (mustManual) {
            val shutters = cap.shutterRange ?: throw CameraFailure("Manual exposure is unavailable")
            val sensitivities = cap.isoRange ?: throw CameraFailure("Manual ISO is unavailable")
            val shutter = (override?.shutterNanos ?: if (mode == ExposureMode.S || mode == ExposureMode.M) exposure.shutterNanos else manualExposure).coerceIn(shutters)
            val iso = (override?.iso ?: exposure.iso ?: manualIso).coerceIn(sensitivities)
            b.set(CaptureRequest.CONTROL_AE_MODE, CaptureRequest.CONTROL_AE_MODE_OFF)
            b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, shutter)
            b.set(CaptureRequest.SENSOR_SENSITIVITY, iso)
            val maximumDuration = c[CameraCharacteristics.SENSOR_INFO_MAX_FRAME_DURATION] ?: Long.MAX_VALUE
            b.set(CaptureRequest.SENSOR_FRAME_DURATION, max(shutter, 33_333_333L).coerceAtMost(maximumDuration))
            b.set(CaptureRequest.FLASH_MODE, when (if (cap.hasFlash) settings.flash else FlashMode.OFF) {
                FlashMode.TORCH -> CaptureRequest.FLASH_MODE_TORCH
                FlashMode.ON -> if (still && cap.hasFlash) CaptureRequest.FLASH_MODE_SINGLE else CaptureRequest.FLASH_MODE_OFF
                else -> CaptureRequest.FLASH_MODE_OFF
            })
        } else {
            val availableAe = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES] ?: intArrayOf()
            val desired = when (settings.flash) {
                FlashMode.AUTO -> CaptureRequest.CONTROL_AE_MODE_ON_AUTO_FLASH
                FlashMode.ON -> CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH
                else -> CaptureRequest.CONTROL_AE_MODE_ON
            }
            b.set(CaptureRequest.CONTROL_AE_MODE, if (desired in availableAe) desired else CaptureRequest.CONTROL_AE_MODE_ON)
            b.set(CaptureRequest.FLASH_MODE, if (settings.flash == FlashMode.TORCH && cap.hasFlash) CaptureRequest.FLASH_MODE_TORCH else CaptureRequest.FLASH_MODE_OFF)
            if (priority != 0) {
                b.set(AE_PRIORITY_REQUEST, priority)
                if (priority == 1) b.set(CaptureRequest.SENSOR_SENSITIVITY, exposure.iso)
                if (priority == 2) b.set(CaptureRequest.SENSOR_EXPOSURE_TIME, exposure.shutterNanos.coerceIn(requireNotNull(cap.shutterRange)))
            }
        }
        if (cap.apertures.size > 1 && (mode == ExposureMode.A || mode == ExposureMode.M)) {
            exposure.aperture?.let { chosen -> cap.apertures.minByOrNull { abs(it - chosen) }?.let { b.set(CaptureRequest.LENS_APERTURE, it) } }
        }
        if (cap.apertures.size > 1 && mode == ExposureMode.S && exposure.iso != null) {
            automaticAperture?.let { b.set(CaptureRequest.LENS_APERTURE, it) }
        }
        val ev = if (mode == ExposureMode.M && exposure.iso != null) 0f else override?.compensationEv ?: exposure.compensationEv
        c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]?.let { range ->
            b.set(CaptureRequest.CONTROL_AE_EXPOSURE_COMPENSATION, compensationSteps(ev, cap.compensationStep, range.lower, range.upper))
        }
        val focusDistance = override?.focusDiopters ?: if (settings.manualFocusEnabled) exposure.focusDiopters else null
        val availableAf = c[CameraCharacteristics.CONTROL_AF_AVAILABLE_MODES] ?: intArrayOf()
        val afMode = when {
            focusDistance != null || cap.minFocusDiopters <= 0f -> CaptureRequest.CONTROL_AF_MODE_OFF
            holdFocus && CaptureRequest.CONTROL_AF_MODE_AUTO in availableAf -> CaptureRequest.CONTROL_AF_MODE_AUTO
            CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE in availableAf -> CaptureRequest.CONTROL_AF_MODE_CONTINUOUS_PICTURE
            CaptureRequest.CONTROL_AF_MODE_AUTO in availableAf -> CaptureRequest.CONTROL_AF_MODE_AUTO
            else -> CaptureRequest.CONTROL_AF_MODE_OFF
        }
        b.set(CaptureRequest.CONTROL_AF_MODE, afMode)
        b.set(CaptureRequest.CONTROL_AF_TRIGGER, CaptureRequest.CONTROL_AF_TRIGGER_IDLE)
        if (focusDistance != null) b.set(CaptureRequest.LENS_FOCUS_DISTANCE, focusDistance.coerceIn(0f, cap.minFocusDiopters))
        val regions = meteringRegion(c)
        if ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AF] ?: 0) > 0) b.set(CaptureRequest.CONTROL_AF_REGIONS, regions)
        if ((c[CameraCharacteristics.CONTROL_MAX_REGIONS_AE] ?: 0) > 0) b.set(CaptureRequest.CONTROL_AE_REGIONS, regions)
        if (settings.manualWhiteBalanceEnabled && exposure.whiteBalanceKelvin != null && cap.supportsManualWhiteBalance && calibratedColorTransform != null) {
            val gains = whiteBalanceGains(requireNotNull(exposure.whiteBalanceKelvin))
            b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_OFF)
            b.set(CaptureRequest.COLOR_CORRECTION_MODE, CaptureRequest.COLOR_CORRECTION_MODE_TRANSFORM_MATRIX)
            b.set(CaptureRequest.COLOR_CORRECTION_GAINS, RggbChannelVector(gains[0], gains[1], gains[1], gains[2]))
            // Retain the device's last calibrated CCM rather than substituting an identity transform.
            b.set(CaptureRequest.COLOR_CORRECTION_TRANSFORM, requireNotNull(calibratedColorTransform))
        } else b.set(CaptureRequest.CONTROL_AWB_MODE, CaptureRequest.CONTROL_AWB_MODE_AUTO)
        if (CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON in (c[CameraCharacteristics.LENS_INFO_AVAILABLE_OPTICAL_STABILIZATION] ?: intArrayOf())) {
            b.set(CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE, CaptureRequest.LENS_OPTICAL_STABILIZATION_MODE_ON)
        }
    }

    private fun aePriority(): Int {
        val c = characteristics ?: return 0
        if (c.availableCaptureRequestKeys.none { it.name == AE_PRIORITY_REQUEST.name }) return 0
        val modes = runCatching { c[AE_PRIORITIES] }.getOrNull() ?: intArrayOf()
        return when {
            exposure.mode == ExposureMode.P && exposure.iso != null && 1 in modes -> 1
            exposure.mode == ExposureMode.S && exposure.iso == null && 2 in modes -> 2
            else -> 0
        }
    }

    private fun manualExposureRequired(override: CaptureSpec? = null): Boolean {
        val mode = exposure.mode
        val priority = aePriority()
        return override?.shutterNanos != null || override?.iso != null ||
            (state.value.capabilities.supportsManualSensor && (mode == ExposureMode.M || mode == ExposureMode.A ||
                mode == ExposureMode.S && priority != 2 || mode == ExposureMode.P && exposure.iso != null && priority != 1))
    }

    private fun usesSoftwareAutoExposure(): Boolean = state.value.capabilities.supportsManualSensor && when (exposure.mode) {
        ExposureMode.AUTO -> false
        ExposureMode.P -> exposure.iso != null && aePriority() == 0
        ExposureMode.A -> true
        ExposureMode.S -> if (exposure.iso == null) aePriority() == 0 else state.value.capabilities.apertures.size > 1
        ExposureMode.M -> exposure.iso == null
    }

    private fun meteringRegion(c: CameraCharacteristics): Array<MeteringRectangle> {
        val sensor = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] ?: return emptyArray()
        // Camera2 streams center-crop the active sensor to their configured aspect ratio.
        val aspect = state.value.previewWidth.toFloat() / state.value.previewHeight
        val cropWidth = minOf(sensor.width(), (sensor.height() * aspect).roundToInt())
        val cropHeight = minOf(sensor.height(), (sensor.width() / aspect).roundToInt())
        val active = Rect(sensor.left + (sensor.width() - cropWidth) / 2, sensor.top + (sensor.height() - cropHeight) / 2,
            sensor.left + (sensor.width() + cropWidth) / 2, sensor.top + (sensor.height() + cropHeight) / 2)
        val rotated = relativeRotation()
        val (sx, sy) = viewToSensor(focusX, focusY, rotated, state.value.frontFacing)
        val fraction = if (targetedFocus) .12f else when (settings.focusArea) {
            FocusArea.POINT -> .08f; FocusArea.CENTER -> .25f; FocusArea.WIDE -> .6f; FocusArea.FULL -> 1f
        }
        val width = max(1, (active.width() * fraction).roundToInt())
        val height = max(1, (active.height() * fraction).roundToInt())
        val left = (active.left + sx * active.width() - width / 2f).roundToInt().coerceIn(active.left, active.right - width)
        val top = (active.top + sy * active.height() - height / 2f).roundToInt().coerceIn(active.top, active.bottom - height)
        return arrayOf(MeteringRectangle(Rect(left, top, left + width, top + height), MeteringRectangle.METERING_WEIGHT_MAX))
    }

    private fun relativeRotation(): Int {
        val degrees = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
        return (state.value.sensorOrientation - degrees * (if (state.value.frontFacing) -1 else 1) + 360) % 360
    }

    private val resultCallback = object : CameraCaptureSession.CaptureCallback() {
        override fun onCaptureCompleted(session: CameraCaptureSession, request: CaptureRequest, result: TotalCaptureResult) {
            val tag = request.tag as? PreviewTag ?: return
            if (tag.epoch != epoch.get() || session !== this@CameraEngine.session) return
            val af = result[CaptureResult.CONTROL_AF_STATE]
            val ae = result[CaptureResult.CONTROL_AE_STATE]
            val focus = result[CaptureResult.LENS_FOCUS_DISTANCE] ?: 0f
            val iso = result[CaptureResult.SENSOR_SENSITIVITY] ?: 100
            val shutter = result[CaptureResult.SENSOR_EXPOSURE_TIME] ?: 10_000_000L
            val fixed = state.value.capabilities.minFocusDiopters <= 0f
            val manualFocus = request[CaptureRequest.CONTROL_AF_MODE] == CaptureRequest.CONTROL_AF_MODE_OFF
            val stationary = result[CaptureResult.LENS_STATE] != CaptureResult.LENS_STATE_MOVING
            val focused = fixed || manualFocus && stationary || af == CaptureResult.CONTROL_AF_STATE_FOCUSED_LOCKED || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_FOCUSED
            _state.update { it.copy(ready = true, exposureNanos = shutter, iso = iso, focusDiopters = focus,
                aperture = result[CaptureResult.LENS_APERTURE],
                aeMode = result[CaptureResult.CONTROL_AE_MODE], flashMode = result[CaptureResult.FLASH_MODE],
                flashState = result[CaptureResult.FLASH_STATE],
                rotateAndCrop = if (Build.VERSION.SDK_INT >= 31) result[CaptureResult.SCALER_ROTATE_AND_CROP] else null,
                focusing = if (tag.version == requestNumber) af == CaptureResult.CONTROL_AF_STATE_ACTIVE_SCAN || af == CaptureResult.CONTROL_AF_STATE_PASSIVE_SCAN else it.focusing,
                focused = if (tag.version == requestNumber) focused else it.focused) }
            resultState.value = ResultSnapshot(tag.epoch, tag.version, stationary, focus,
                ae == null || ae == CaptureResult.CONTROL_AE_STATE_CONVERGED || ae == CaptureResult.CONTROL_AE_STATE_LOCKED || ae == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED,
                ae, result.frameNumber, request[CaptureRequest.LENS_FOCUS_DISTANCE], result[CaptureResult.LENS_FOCUS_DISTANCE],
                result[CaptureResult.LENS_STATE], result[CaptureResult.CONTROL_AF_MODE], af,
                result[CaptureResult.CONTROL_AE_MODE], result[CaptureResult.CONTROL_AWB_MODE], shutter, iso)
            val firstColorCalibration = calibratedColorTransform == null && result[CaptureResult.COLOR_CORRECTION_TRANSFORM] != null
            if (request[CaptureRequest.CONTROL_AWB_MODE] != CaptureRequest.CONTROL_AWB_MODE_OFF) {
                calibratedColorTransform = result[CaptureResult.COLOR_CORRECTION_TRANSFORM] ?: calibratedColorTransform
            }
            if (request[CaptureRequest.CONTROL_AE_MODE] != CaptureRequest.CONTROL_AE_MODE_OFF) {
                manualExposure = shutter
                manualIso = iso
            }
            if (firstColorCalibration && settings.manualWhiteBalanceEnabled) repeating()
        }
    }

    private suspend fun prepareFlash(generation: Long, convergenceTimeoutMillis: Long, activeCapture: AtomicBoolean) {
        val flash = CompletableDeferred<Long?>()
        if (!handler.post {
            if (!activeCapture.get()) { flash.cancel(); return@post }
            try {
                val d = requireNotNull(device)
                val s = requireNotNull(session)
                val b = previewRequest(d)
                val ae = b.get(CaptureRequest.CONTROL_AE_MODE)
                val needed = settings.flash == FlashMode.ON || settings.flash == FlashMode.AUTO &&
                    resultState.value?.aeState == CaptureResult.CONTROL_AE_STATE_FLASH_REQUIRED
                if (!needed || ae == CaptureRequest.CONTROL_AE_MODE_OFF) { flash.complete(null); return@post }
                val version = ++requestNumber
                b.setTag(PreviewTag(generation, version))
                b.set(CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER, CaptureRequest.CONTROL_AE_PRECAPTURE_TRIGGER_START)
                resultState.value = null
                s.capture(b.build(), resultCallback, handler)
                repeating()
                flash.complete(version)
            } catch (e: Exception) { flash.completeExceptionally(e) }
        }) flash.completeExceptionally(CameraFailure("Camera thread is closed"))
        val flashVersion = withTimeout(3_000) { flash.await() }
        if (flashVersion != null) {
            val done = withTimeoutOrNull(convergenceTimeoutMillis) {
                resultState.first { it != null && it.epoch == generation && it.requestVersion == flashVersion && it.aeConverged }
            }
            if (done == null) throw CameraFailure("Flash metering timed out")
        }
    }

    private fun checkCapture(spec: CaptureSpec, generation: Long) {
        if (generation != epoch.get() || !state.value.ready || session == null) throw CameraFailure("Camera is not ready")
        if (spec.format != PhotoFormat.JPEG && rawReader == null) throw CameraFailure("RAW session is not ready for this lens")
        if ((spec.format != PhotoFormat.RAW || spec.requireJpeg) && jpegReader == null) {
            throw CameraFailure("JPEG output is not configured; select JPEG/RAW+JPEG or a bracket mode before capture")
        }
        if (exposure.mode != ExposureMode.AUTO && !state.value.capabilities.supportsManualSensor &&
            (exposure.mode != ExposureMode.P || exposure.iso != null)) throw CameraFailure("This lens does not support manual exposure")
        if (usesSoftwareAutoExposure() && SystemClock.elapsedRealtime() - lastMeteringSample > 2_000) {
            throw CameraFailure("Waiting for a live exposure-metering sample")
        }
        if (settings.flash == FlashMode.AUTO && state.value.capabilities.hasFlash && manualExposureRequired(spec)) {
            throw CameraFailure("Auto flash is unavailable with manual exposure; choose On or Off")
        }
        if (spec.focusDiopters != null && state.value.capabilities.minFocusDiopters <= 0f) throw CameraFailure("This lens has fixed focus")
        if (settings.manualWhiteBalanceEnabled && exposure.whiteBalanceKelvin != null &&
            state.value.capabilities.supportsManualWhiteBalance && calibratedColorTransform == null) {
            throw CameraFailure("Waiting for camera white-balance calibration")
        }
    }

    private fun pipelineDepth(): Int = (characteristics?.get(CameraCharacteristics.REQUEST_PIPELINE_MAX_DEPTH)?.toInt() ?: 4).coerceIn(1, 8)

    private fun outputProfile(settings: CameraSettings) = OutputProfile(
        jpeg = settings.format != PhotoFormat.RAW || settings.shootingMode == ShootingMode.EXPOSURE_BRACKET || settings.shootingMode == ShootingMode.FOCUS_BRACKET,
        raw = settings.format != PhotoFormat.JPEG,
    )

    private fun previewExposureBudget(spec: CaptureSpec): Long {
        val configured = if (exposure.mode == ExposureMode.M || exposure.mode == ExposureMode.S) exposure.shutterNanos else manualExposure
        return max(state.value.exposureNanos, spec.shutterNanos ?: configured)
            .coerceAtMost(state.value.capabilities.shutterRange?.last ?: Long.MAX_VALUE)
    }

    private fun outputStallNanos(spec: CaptureSpec): Long {
        val map = characteristics?.get(CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP) ?: return 0L
        val jpeg = if (spec.format != PhotoFormat.RAW || spec.requireJpeg) jpegReader?.let {
            map.getOutputStallDuration(ImageFormat.JPEG, Size(it.width, it.height))
        } ?: 0L else 0L
        val raw = if (spec.format != PhotoFormat.JPEG) rawReader?.let {
            map.getOutputStallDuration(ImageFormat.RAW_SENSOR, Size(it.width, it.height))
        } ?: 0L else 0L
        return max(jpeg, raw)
    }

    private fun closeSession(cause: Throwable) {
        activeBurst.get()?.stop()
        pending.values.forEach { it.completion.completeExceptionally(cause); it.pairer.close() }
        pending.clear()
        temporarySpec = null
        runCatching { session?.stopRepeating() }
        session?.close(); session = null
        device?.close(); device = null
        // Readers are closed on their own handler, after any in-progress buffer copy has completed.
        val jpeg = jpegReader; jpegReader = null
        val raw = rawReader; rawReader = null
        imageHandler.post { jpeg?.close(); raw?.close() }
        resultState.value = null
        holdFocus = false
        targetedFocus = false
        _state.update { it.copy(ready = false, focusing = false, focused = false,
            jpegOutputConfigured = false, rawOutputConfigured = false, burstInFlight = 0,
            aeMode = null, flashMode = null, flashState = null) }
    }

    private fun refreshLenses() {
        try {
            val cameras = manager.cameraIdList.associateWith(manager::getCameraCharacteristics).filterValues {
                !it[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]?.getOutputSizes(ImageFormat.JPEG).isNullOrEmpty()
            }
            // Some OEMs expose both a logical camera and its identical default physical child as
            // independently openable IDs. Hide a child only with explicit topology AND matching
            // optical/sensor metadata; focal length alone is not enough to establish duplication.
            val aliases = cameras.flatMap { (logicalId, logical) ->
                val capabilities = logical[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
                if (CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA !in capabilities) emptyList()
                else logical.physicalCameraIds.filter { physicalId ->
                    if (physicalId == logicalId) return@filter false
                    val child = cameras[physicalId] ?: return@filter false
                    sameDefaultLens(logical, child)
                }.map { it to logicalId }
            }.toMap()
            lensAliases = aliases
            val visible = cameras.filterKeys { it !in aliases }
            val defaultBack = visible.values.firstOrNull {
                it[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK &&
                    CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_LOGICAL_MULTI_CAMERA in (it[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf())
            } ?: visible.values.firstOrNull { it[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            val baseEquivalent = defaultBack?.let(::equivalentFocalLength)
            val chinese = app.resources.configuration.locales[0].language == "zh"
            val lenses = visible.map { (id, c) ->
                val front = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
                val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: 0f
                val equivalent = equivalentFocalLength(c)
                val role = when {
                    front -> if (chinese) "前置" else "Front"
                    c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_EXTERNAL -> if (chinese) "外接" else "External"
                    equivalent != null && baseEquivalent != null && equivalent < baseEquivalent * .8f -> if (chinese) "超广角" else "Ultra-wide"
                    equivalent != null && baseEquivalent != null && equivalent > baseEquivalent * 1.25f -> if (chinese) "长焦" else "Telephoto"
                    else -> if (chinese) "主摄" else "Main"
                }
                val focalLabel = if (equivalent != null) {
                    if (chinese) "等效 ${equivalent.roundToInt()}mm" else "${equivalent.roundToInt()}mm eq"
                } else "${"%.1f".format(java.util.Locale.ROOT, focal)}mm"
                LensInfo(id, "$role · $focalLabel", front, focal)
            }.sortedWith(compareBy<LensInfo> { it.frontFacing }.thenBy { it.focalLengthMm })
            _state.update { it.copy(lenses = lenses) }
        } catch (e: Exception) { fail("Unable to list camera lenses", e) }
    }

    private fun sameDefaultLens(logical: CameraCharacteristics, physical: CameraCharacteristics): Boolean {
        val logicalFocal = logical[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return false
        val physicalFocal = physical[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return false
        val logicalSize = logical[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return false
        val physicalSize = physical[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return false
        val logicalPixels = logical[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE] ?: return false
        val physicalPixels = physical[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE] ?: return false
        return abs(logicalFocal - physicalFocal) < .01f && logicalSize == physicalSize && logicalPixels == physicalPixels &&
            logical[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] == physical[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE] &&
            logical[CameraCharacteristics.LENS_FACING] == physical[CameraCharacteristics.LENS_FACING] &&
            logical[CameraCharacteristics.SENSOR_ORIENTATION] == physical[CameraCharacteristics.SENSOR_ORIENTATION]
    }

    /** Diagonal-based 35mm equivalent, restricted to the active imaging area when metadata exists. */
    private fun equivalentFocalLength(c: CameraCharacteristics): Float? {
        val focal = c[CameraCharacteristics.LENS_INFO_AVAILABLE_FOCAL_LENGTHS]?.firstOrNull() ?: return null
        val physical = c[CameraCharacteristics.SENSOR_INFO_PHYSICAL_SIZE] ?: return null
        val pixels = c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE]
        val active = c[CameraCharacteristics.SENSOR_INFO_ACTIVE_ARRAY_SIZE]
        val width = physical.width * if (pixels != null && active != null && pixels.width > 0) active.width().toFloat() / pixels.width else 1f
        val height = physical.height * if (pixels != null && active != null && pixels.height > 0) active.height().toFloat() / pixels.height else 1f
        val diagonal = sqrt(width * width + height * height)
        return if (diagonal > 0 && focal > 0) focal * 43.266615f / diagonal else null
    }

    private fun chooseLens(requested: String?): String {
        val ids = manager.cameraIdList
        val canonical = requested?.let { lensAliases[it] ?: it }
        if (canonical != null && canonical in ids) return canonical
        return ids.firstOrNull { manager.getCameraCharacteristics(it)[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_BACK }
            ?: ids.firstOrNull() ?: throw CameraFailure("No camera is available")
    }

    private fun capabilities(id: String, c: CameraCharacteristics): CameraCapabilities {
        val available = c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES] ?: intArrayOf()
        val map = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val jpeg = map.getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val step = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]?.toFloat() ?: 0f
        val compensation = c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]
        return CameraCapabilities(
            lensId = id,
            apertures = c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.toList().orEmpty(),
            shutterRange = c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]?.let { it.lower..it.upper },
            isoRange = c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]?.let { it.lower..it.upper },
            compensationRange = ((compensation?.lower ?: 0) * step)..((compensation?.upper ?: 0) * step),
            compensationStep = step,
            minFocusDiopters = c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE] ?: 0f,
            calibratedFocus = c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION] == CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION_CALIBRATED,
            supportsRaw = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_RAW in available && !map.getOutputSizes(ImageFormat.RAW_SENSOR).isNullOrEmpty(),
            supportsManualSensor = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_SENSOR in available,
            supportsManualWhiteBalance = CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES_MANUAL_POST_PROCESSING in available &&
                CaptureRequest.CONTROL_AWB_MODE_OFF in (c[CameraCharacteristics.CONTROL_AWB_AVAILABLE_MODES] ?: intArrayOf()),
            hasFlash = c[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true,
            photoWidth = jpeg.width, photoHeight = jpeg.height,
            sensorOrientation = c[CameraCharacteristics.SENSOR_ORIENTATION] ?: 0,
        )
    }

    private fun fail(message: String, cause: Throwable? = null) {
        _state.update { it.copy(error = if (cause == null) message else "$message: ${cause.message ?: cause.javaClass.simpleName}") }
    }


    private data class PreviewTag(val epoch: Long, val version: Long)
    private data class OutputProfile(val jpeg: Boolean, val raw: Boolean)
    private data class PreparedCapture(val version: Long, val focusTimeoutMillis: Long, val aeTimeoutMillis: Long, val prepareFlash: Boolean)
    private data class ResultSnapshot(val epoch: Long, val requestVersion: Long, val lensStationary: Boolean,
        val focus: Float, val aeConverged: Boolean, val aeState: Int?, val frameNumber: Long,
        val requestedFocus: Float?, val reportedFocus: Float?, val lensState: Int?, val afMode: Int?, val afState: Int?,
        val aeMode: Int?, val awbMode: Int?, val exposureNanos: Long, val iso: Int)
    private data class RawCopy(val size: Size, val file: File)
    private data class Acquired(val jpeg: ByteArray?, val raw: RawCopy?, val result: TotalCaptureResult, val characteristics: CameraCharacteristics)
    private data class PendingCapture(var tag: Long, val epoch: Long, val spec: CaptureSpec,
        val completion: CompletableDeferred<Acquired>, var rawSpool: File? = null, var sequenceId: Int? = null) {
        val pairer = FramePairer<ByteArray, RawCopy, TotalCaptureResult>(
            spec.format != PhotoFormat.RAW || spec.requireJpeg, spec.format != PhotoFormat.JPEG, { it.file.delete() })
    }

    companion object {
        // Public Camera2 keys, gated by availableCaptureRequestKeys; safe on API 29+ without linkage to newer constants.
        private val AE_PRIORITY_REQUEST = CaptureRequest.Key("android.control.aePriorityMode", Int::class.javaObjectType)
        private val AE_PRIORITIES = CameraCharacteristics.Key("android.control.aeAvailablePriorityModes", IntArray::class.java)
    }
}
