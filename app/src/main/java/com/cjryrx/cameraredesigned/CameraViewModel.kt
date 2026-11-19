package com.cjryrx.cameraredesigned

import android.app.Application
import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import com.cjryrx.cameraredesigned.camera.*
import com.cjryrx.cameraredesigned.data.*
import com.cjryrx.cameraredesigned.domain.*
import com.cjryrx.cameraredesigned.tracking.SubjectTracker
import com.cjryrx.cameraredesigned.tracking.YoloDetector
import kotlinx.coroutines.*
import kotlinx.coroutines.flow.*
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicLong

class CameraViewModel(application: Application) : AndroidViewModel(application) {
    private val app = application as CameraApplication
    private val engine = CameraEngine(application)
    private val repository = app.repository
    private val libraryLoaded = CompletableDeferred<Unit>()
    private val queue = app.processing
    private val mutableState = MutableStateFlow(CameraUiState())
    val state = mutableState.asStateFlow()
    private val external = MutableSharedFlow<CameraAction>(extraBufferCapacity = 8)
    val externalActions = external.asSharedFlow()
    private var texture: SurfaceTexture? = null
    private var surface: Surface? = null
    private var previewBuffer: android.util.Size? = null
    private var previewLens: String? = null
    private var viewWidth = 0
    private var viewHeight = 0
    private var requestedLensId: String? = null
    var displayRotation: Int = Surface.ROTATION_0
        private set
    @Volatile private var active = false
    @Volatile private var menuVisible = false
    private var loaded = false
    private val gate = ShutterGate()
    private var captureJob: Job? = null
    private var pointJob: Job? = null
    private val analysisGeneration = AtomicLong()
    private var saveSettingsJob: Job? = null
    private val analyzing = AtomicBoolean(false)
    private val modelOperation = AtomicBoolean(false)
    private val tracker = SubjectTracker()
    private val detector = YoloDetector(application)
    @Volatile private var detectorReady = false
    @Volatile private var captureHoldsFocus = false
    private var meteringPixels = IntArray(0)
    private var lastReadoutTime = 0L

    init {
        viewModelScope.launch {
            try {
                val saved = repository.load()
                mutableState.update { it.copy(settings = saved.settings, exposure = saved.exposure, filters = saved.filters) }
            } catch (e: Exception) { report(e) }
            libraryLoaded.complete(Unit)
            loaded = true
            engine.update(state.value.settings, state.value.exposure)
            startIfReady()
            if (state.value.settings.modelEnabled) loadModel()
        }
        viewModelScope.launch {
            engine.state.collect { e ->
                val current = state.value
                val applied = if (current.capabilities != e.capabilities) current.exposure.forLens(e.capabilities) else current.exposure
                val now = android.os.SystemClock.elapsedRealtime()
                val refresh = e.ready && (!current.cameraReady || applied.mode != ExposureMode.AUTO && now - lastReadoutTime >= 250)
                if (refresh) lastReadoutTime = now
                mutableState.update { old -> old.copy(capabilities = e.capabilities, lenses = e.lenses, exposure = applied,
                    measuredShutterNanos = if (refresh) e.exposureNanos else old.measuredShutterNanos,
                    measuredIso = if (refresh) e.iso else old.measuredIso,
                    measuredAperture = if (refresh) e.aperture else old.measuredAperture,
                    activeLensId = e.lensId, cameraReady = e.ready, focusing = e.focusing, focused = e.focused,
                    previewWidth = e.previewWidth, previewHeight = e.previewHeight, error = e.error ?: old.error) }
                if (applied != current.exposure) engine.update(current.settings, applied)
            }
        }
        viewModelScope.launch {
            queue.state.collect { q -> mutableState.update { it.copy(processingCount = q.count, failedSaveCount = q.failedCount,
                status = if (q.lastSaved != null) "Saved / 已保存" else it.status, error = q.error ?: it.error) } }
        }
    }

    fun setActive(value: Boolean) {
        analysisGeneration.incrementAndGet()
        active = value
        if (value) { startIfReady(); if (queue.state.value.count > 0) runCatching { queue.ensureService() }.onFailure(::report) }
        else {
            if (state.value.countdown > 0) captureJob?.cancel()
            previewBuffer = null; gate.release(); pointJob?.cancel(); engine.stop()
        }
    }
    fun setDisplayRotation(rotation: Int) {
        if (rotation == displayRotation) return
        displayRotation = rotation
        analysisGeneration.incrementAndGet()
        mutableState.update { it.copy(displayRotation = rotation) }
        engine.updateDisplayRotation(rotation)
    }
    fun previewAvailable(value: SurfaceTexture, width: Int, height: Int) {
        analysisGeneration.incrementAndGet()
        texture = value; viewWidth = width; viewHeight = height; startIfReady()
    }
    fun previewDestroyed(releaseInput: () -> Unit) {
        analysisGeneration.incrementAndGet()
        gate.release(); texture = null; previewBuffer = null
        val oldSurface = surface; surface = null
        app.scope.launch {
            try { engine.stopAndAwait() }
            catch (e: TimeoutCancellationException) { report(IllegalStateException("Camera close timed out", e)) }
            finally { oldSurface?.release(); releaseInput() }
        }
    }
    private fun startIfReady() {
        if (!active || !loaded || menuVisible || viewWidth == 0) return
        val value = texture ?: return
        try {
            val targetLens = requestedLensId
            val size = engine.previewSize(targetLens, viewWidth, viewHeight)
            if (surface?.isValid == true && previewBuffer == size && previewLens == targetLens && engine.state.value.error == null) return
            value.setDefaultBufferSize(size.width, size.height)
            val output = surface ?: Surface(value).also { surface = it }
            previewBuffer = size; previewLens = targetLens
            mutableState.update { it.copy(cameraReady = false) }
            engine.start(output, viewWidth, viewHeight, targetLens, displayRotation)
        } catch (e: Exception) { report(e) }
    }

    fun action(action: CameraAction) {
        if (state.value.shooting && (action is CameraAction.Settings || action is CameraAction.Exposure ||
                action is CameraAction.SelectLens || action is CameraAction.FocusAt || action is CameraAction.AddFocusPoint)) return
        when (action) {
            is CameraAction.Settings -> {
                val previous = state.value.settings
                val next = action.value.validated()
                if (previous.modelEnabled != next.modelEnabled) analysisGeneration.incrementAndGet()
                mutableState.update { it.copy(settings = next, exposure = it.exposure.copy(
                    focusDiopters = if (next.manualFocusEnabled && !previous.manualFocusEnabled) engine.state.value.focusDiopters else it.exposure.focusDiopters,
                    whiteBalanceKelvin = if (next.manualWhiteBalanceEnabled && !previous.manualWhiteBalanceEnabled) 5500 else it.exposure.whiteBalanceKelvin,
                )) }
                engine.update(next, state.value.exposure); persist()
                if (next.modelEnabled && !previous.modelEnabled) viewModelScope.launch { loadModel() }
                if (!next.modelEnabled) { tracker.reset(); mutableState.update { it.copy(subjects = emptyList(), selectedSubjectId = null, modelStatus = "System AF") } }
            }
            is CameraAction.Exposure -> {
                val cap = state.value.capabilities
                val e = action.value.forLens(cap)
                mutableState.update { it.copy(exposure = e) }; engine.update(state.value.settings, e); persist()
            }
            is CameraAction.SelectLens -> if (captureJob?.isActive != true) {
                analysisGeneration.incrementAndGet(); pointJob?.cancel()
                requestedLensId = action.id
                tracker.reset(); mutableState.update { it.copy(activeLensId = action.id, focusPoints = emptyList(), subjects = emptyList()) }; startIfReady()
            }
            is CameraAction.FocusAt -> {
                mutableState.update { it.copy(focusX = action.x, focusY = action.y) }
                engine.focusAt(action.x, action.y)
            }
            is CameraAction.AddFocusPoint -> {
                pointJob?.cancel()
                pointJob = viewModelScope.launch {
                try {
                    require(state.value.capabilities.minFocusDiopters > 0f) { "This lens has fixed focus" }
                    mutableState.update { it.copy(focusX = action.x, focusY = action.y) }
                    engine.focusAt(action.x, action.y)
                    check(engine.awaitFocus(2500)) { "Focus not acquired; point was not recorded" }
                    mutableState.update { it.copy(focusPoints = (it.focusPoints + FocusPoint(action.x, action.y, engine.state.value.focusDiopters)).takeLast(30)) }
                    engine.releaseFocus()
                } catch (e: Exception) { report(e) }
                }
            }
            CameraAction.ClearFocusPoints -> mutableState.update { it.copy(focusPoints = emptyList()) }
            CameraAction.PressShutter -> {
                if (!active || menuVisible || !state.value.cameraReady || state.value.shooting) return
                pointJob?.cancel()
                gate.press()
                val selected = state.value.subjects.firstOrNull { it.id == state.value.selectedSubjectId }
                engine.focusAt(selected?.let { (it.left + it.right) / 2 } ?: state.value.focusX,
                    selected?.let { (it.top + it.bottom) / 2 } ?: state.value.focusY,
                    targeted = selected != null || state.value.focusX != .5f || state.value.focusY != .5f)
            }
            is CameraAction.DragShutter -> {
                if (!action.armed) engine.stopBurst()
                if (gate.drag(action.armed, state.value.settings.shootingMode == ShootingMode.BURST)) beginCapture()
            }
            CameraAction.ReleaseShutter -> {
                if (!gate.pressed) return
                gate.release()
                engine.stopBurst()
                if (!captureHoldsFocus) releaseShutterFocus()
            }
            CameraAction.CustomKey -> customKey()
            is CameraAction.SaveFilter -> runCatching {
                val filter = action.value.validated()
                mutableState.update { current ->
                    val index = current.filters.indexOfFirst { it.id == filter.id }
                    val filters = if (index < 0) current.filters + filter
                        else current.filters.toMutableList().apply { this[index] = filter }
                    current.copy(filters = filters)
                }
                persist(debounce = false)
            }.onFailure(::report)
            is CameraAction.DeleteFilter -> {
                mutableState.update { it.copy(filters = it.filters.filterNot { f -> f.id == action.id },
                    settings = if (it.settings.activeFilterId == action.id) it.settings.copy(activeFilterId = null) else it.settings) }
                persist(debounce = false)
            }
            CameraAction.DismissError -> {
                engine.clearError(); queue.dismissError(); mutableState.update { it.copy(error = null) }
                if (!state.value.cameraReady && active && !menuVisible && state.value.capabilities.let { it.supportsRaw || state.value.settings.format == PhotoFormat.JPEG }) {
                    previewBuffer = null; startIfReady()
                }
            }
            CameraAction.RetrySaving -> {
                mutableState.update { it.copy(error = null) }
                app.scope.launch {
                    runCatching { queue.ensureService() }.onFailure(::report)
                    try { queue.retryFailed() }
                    catch (e: Exception) { report(e) }
                }
            }
            is CameraAction.MenuVisible -> {
                menuVisible = action.visible
                if (menuVisible) {
                    if (state.value.countdown > 0) captureJob?.cancel()
                    previewBuffer = null; gate.release(); engine.stop()
                } else startIfReady()
                external.tryEmit(action)
            }
            else -> external.tryEmit(action)
        }
    }

    private fun persist(debounce: Boolean = true) {
        saveSettingsJob?.cancel()
        val snapshot = state.value
        saveSettingsJob = app.scope.launch {
            if (debounce) delay(250)
            try { repository.save(Preferences(snapshot.settings, snapshot.exposure, snapshot.filters)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
        }
    }

    fun importFilter(uri: android.net.Uri, lut: Boolean) {
        viewModelScope.launch {
            try {
                // A document-picker result may be restored before persisted presets are loaded.
                libraryLoaded.await()
                val filter = if (lut) repository.importLut(uri) else repository.importFilter(uri)
                action(CameraAction.SaveFilter(filter))
            } catch (e: Exception) { report(e) }
        }
    }
    fun exportFilter(filter: FilterSpec, destination: android.net.Uri) {
        viewModelScope.launch {
            try {
                repository.exportFilter(filter, destination)
                mutableState.update { it.copy(status = "Filter exported / 滤镜已导出") }
            } catch (e: Exception) { report(e) }
        }
    }
    fun setSample(path: String) {
        val previous = state.value.samplePhotoPath
        mutableState.update { it.copy(samplePhotoPath = path) }
        if (previous != null && previous != path) app.scope.launch(Dispatchers.IO) { File(previous).delete() }
    }
    private fun releaseShutterFocus() {
        engine.releaseFocus()
        tracker.next()
        mutableState.update { it.copy(selectedSubjectId = null, focusX = .5f, focusY = .5f) }
    }
    fun report(error: Throwable) { if (error !is CancellationException) mutableState.update { it.copy(error = error.message ?: error.javaClass.simpleName) } }
    suspend fun loadModel() {
        try {
            detector.load()
            detectorReady = true
            mutableState.update { it.copy(modelStatus = "ONNX · offline / 离线") }
        } catch (e: Exception) {
            mutableState.update { it.copy(settings = it.settings.copy(modelEnabled = false), modelStatus = "System AF") }
            report(e); persist()
        }
    }

    suspend fun installModel(uri: android.net.Uri) {
        if (!modelOperation.compareAndSet(false, true)) return
        try {
            mutableState.update { it.copy(modelStatus = "Validating model / 正在验证模型") }
            detector.install(uri)
            action(CameraAction.Settings(state.value.settings.copy(modelEnabled = true)))
            mutableState.update { it.copy(modelStatus = "ONNX · offline / 离线") }
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { restoreModelStatus(); report(e) }
        finally { modelOperation.set(false) }
    }

    suspend fun downloadModel() {
        if (!modelOperation.compareAndSet(false, true)) return
        try {
            mutableState.update { it.copy(modelStatus = "Downloading / 下载中") }
            detector.downloadDefault { percent -> mutableState.update { it.copy(modelStatus = "Download / 下载 $percent%") } }
            action(CameraAction.Settings(state.value.settings.copy(modelEnabled = true)))
            mutableState.update { it.copy(modelStatus = "ONNX · offline / 离线") }
        } catch (e: TimeoutCancellationException) {
            restoreModelStatus()
            report(IllegalStateException("Model download timed out / 模型下载超时", e))
        } catch (e: CancellationException) { throw e }
        catch (e: Exception) { restoreModelStatus(); report(e) }
        finally { modelOperation.set(false) }
    }

    private fun restoreModelStatus() {
        mutableState.update { it.copy(modelStatus = if (detectorReady && it.settings.modelEnabled) "ONNX · offline / 离线" else "System AF") }
    }

    private fun beginCapture() {
        if (captureJob?.isActive == true || !state.value.cameraReady) return
        captureJob = viewModelScope.launch {
            val snapshot = state.value
            captureHoldsFocus = snapshot.settings.priority == ShutterPriority.FOCUS
            mutableState.update { it.copy(shooting = true, shotsTaken = 0, lastCaptureTimestampNanos = 0) }
            try {
                for (second in snapshot.settings.timerSeconds downTo 1) {
                    mutableState.update { it.copy(countdown = second) }; delay(1000)
                    check(active && !menuVisible) { "Countdown cancelled" }
                }
                mutableState.update { it.copy(countdown = 0) }
                if (snapshot.settings.priority == ShutterPriority.FOCUS) check(engine.awaitFocus(2500)) { "Focus timeout; no photo taken" }
                if (snapshot.settings.shootingMode == ShootingMode.BURST) {
                    // One initial shot remains valid even if the finger releases after crossing the threshold.
                    do {
                        val settings = snapshot.settings
                        if (settings.format == PhotoFormat.JPEG && settings.flash in setOf(FlashMode.OFF, FlashMode.TORCH)) {
                            captureJpegBurst(snapshot)
                        } else {
                            val started = android.os.SystemClock.elapsedRealtime()
                            captureSequence(snapshot, ShootingMode.SINGLE)
                            val fps = settings.burstRate.fps
                            if (fps > 0) delay((1000 / fps - (android.os.SystemClock.elapsedRealtime() - started)).coerceAtLeast(0))
                        }
                    } while (gate.continueBurst() && active && !menuVisible)
                } else captureSequence(snapshot, snapshot.settings.shootingMode)
            } catch (e: TimeoutCancellationException) { report(IllegalStateException("Capture timed out / 拍摄超时", e)) }
            catch (e: CancellationException) { throw e }
            catch (e: Exception) { report(e) }
            finally {
                if (captureHoldsFocus && !gate.pressed) releaseShutterFocus()
                captureHoldsFocus = false
                mutableState.update { it.copy(shooting = false, countdown = 0, waitingForSave = false) }
                engine.update(state.value.settings, state.value.exposure)
            }
        }
    }

    private fun captureSpec(snapshot: CameraUiState, bracket: Boolean): CaptureSpec {
        val orientation = (snapshot.capabilities.sensorOrientation + (if (engine.state.value.frontFacing) 1 else -1) * rotationDegrees() + 360) % 360
        return CaptureSpec(snapshot.settings.format, snapshot.settings.jpegQuality, orientation, requireJpeg = bracket)
    }

    private suspend fun captureJpegBurst(snapshot: CameraUiState) {
        val jobs = mutableMapOf<File, ProcessingJob>()
        engine.captureJpegBurst(captureSpec(snapshot, false), snapshot.settings.burstRate.fps,
            prepare = { index ->
                val first = index == 0 && state.value.shotsTaken == 0
                val job = reserveJob(snapshot, ShootingMode.SINGLE, 1, first)
                job?.let { File(queue.directory, it.jpegNames.single()).also { file -> jobs[file] = job } }
            },
            completed = { file, result ->
                val job = jobs.remove(file) ?: error("Missing capture journal for ${file.name}")
                try { result.getOrNull()?.let { recordFrame(it.timestamp) } }
                finally { finishJob(job) }
            })
    }

    private fun recordFrame(timestamp: Long) {
        mutableState.update { it.copy(shotsTaken = it.shotsTaken + 1, lastCaptureTimestampNanos = timestamp) }
        if (captureHoldsFocus) {
            captureHoldsFocus = false
            if (!gate.pressed) releaseShutterFocus()
        }
    }

    private suspend fun captureSequence(snapshot: CameraUiState, mode: ShootingMode) {
        val settings = snapshot.settings
        val cap = snapshot.capabilities
        val current = engine.state.value
        val bracket = mode == ShootingMode.EXPOSURE_BRACKET || mode == ShootingMode.FOCUS_BRACKET
        require(!bracket || settings.flash == FlashMode.OFF || settings.flash == FlashMode.TORCH) {
            "Bracketing requires flash Off or continuous Torch / 包围拍摄请关闭闪光灯或使用常亮灯"
        }
        val base = captureSpec(snapshot, bracket)
        val specs = when (mode) {
            ShootingMode.EXPOSURE_BRACKET -> {
                val offsets = ExposurePlan.bracketOffsets(settings.bracketCount, settings.bracketStepEv)
                if (cap.supportsManualSensor && cap.shutterRange != null) offsets.map {
                    val requested = current.exposureNanos.toDouble() * Math.pow(2.0, it.toDouble())
                    require(requested >= cap.shutterRange.first && requested <= cap.shutterRange.last) {
                        "Bracket exceeds shutter limits; reduce EV step or change base exposure"
                    }
                    base.copy(shutterNanos = ExposurePlan.bracketShutter(current.exposureNanos, it, cap.shutterRange), iso = current.iso)
                } else {
                    require(offsets.all { it + snapshot.exposure.compensationEv in cap.compensationRange }) { "Exposure bracket exceeds this lens's EV range" }
                    offsets.map { base.copy(compensationEv = snapshot.exposure.compensationEv + it) }
                }
            }
            ShootingMode.FOCUS_BRACKET -> {
                require(cap.minFocusDiopters > 0f) { "Focus bracketing is unavailable on this fixed-focus lens" }
                val points = if (settings.focusMethod == BracketFocusMethod.POINTS) snapshot.focusPoints.map { it.diopters }.also {
                    require(it.size >= 2) { "Record at least two focus points in the viewfinder" }
                } else ExposurePlan.focusRange(settings.focusNear, settings.focusFar, settings.focusStackCount, cap.minFocusDiopters)
                require(points.distinct().size >= 2) { "Select different near and far focus distances" }
                points.distinct().map { base.copy(focusDiopters = it, shutterNanos = current.exposureNanos.takeIf { cap.supportsManualSensor }, iso = current.iso.takeIf { cap.supportsManualSensor }) }
            }
            else -> listOf(base)
        }
        val job = reserveJob(snapshot, mode, specs.size, first = state.value.shotsTaken == 0) ?: return
        try {
            specs.forEachIndexed { index, spec ->
                check(active && !menuVisible) { "Camera capture interrupted" }
                val frame = engine.capture(spec, rawDestination = job.rawNames.getOrNull(index)?.let { File(queue.directory, it) },
                    jpegDestination = job.jpegNames.getOrNull(index)?.let { File(queue.directory, it) })
                recordFrame(frame.metadata.timestamp)
            }
        } finally { finishJob(job) }
    }

    private suspend fun reserveJob(snapshot: CameraUiState, mode: ShootingMode, count: Int, first: Boolean): ProcessingJob? {
        val settings = snapshot.settings
        val bracket = mode == ShootingMode.EXPOSURE_BRACKET || mode == ShootingMode.FOCUS_BRACKET
        fun eligible() = active && !menuVisible && (first || settings.shootingMode != ShootingMode.BURST || gate.continueBurst())
        if (!eligible()) return null
        val id = queue.newId()
        val jpegs = if (settings.format != PhotoFormat.RAW || bracket) (0 until count).map { "$id-$it.jpg" } else emptyList()
        val raws = if (settings.format != PhotoFormat.JPEG) (0 until count).map { "$id-$it.dng" } else emptyList()
        val job = ProcessingJob(id, jpegs, raws, mode, settings.jpegQuality,
            snapshot.filters.firstOrNull { it.id == settings.activeFilterId }, settings.keepBracketSources,
            settings.format != PhotoFormat.RAW, captureIntent = true)
        queue.reserve { mutableState.update { it.copy(waitingForSave = true) } }
        mutableState.update { it.copy(waitingForSave = false) }
        if (!eligible()) {
            queue.releaseReservation()
            return null
        }
        try {
            queue.recordIntent(job)
        } catch (e: Exception) {
            queue.releaseReservation()
            throw e
        }
        return job
    }

    private suspend fun finishJob(job: ProcessingJob) = withContext(NonCancellable) {
        if (queue.finishReserved(job)) runCatching { queue.ensureService() }.onFailure(::report)
        Unit
    }

    private fun rotationDegrees() = when (displayRotation) { Surface.ROTATION_90 -> 90; Surface.ROTATION_180 -> 180; Surface.ROTATION_270 -> 270; else -> 0 }
    private fun customKey() {
        val s = state.value.settings
        when (s.customFunction) {
            CustomFunction.NEXT_SUBJECT -> tracker.next()
            CustomFunction.SHOOTING_MODE -> action(CameraAction.Settings(s.copy(shootingMode = ShootingMode.entries[(s.shootingMode.ordinal + 1) % ShootingMode.entries.size])))
            CustomFunction.FOCUS_AREA -> action(CameraAction.Settings(s.copy(focusArea = FocusArea.entries[(s.focusArea.ordinal + 1) % FocusArea.entries.size])))
            CustomFunction.FOCUS_MAGNIFIER -> mutableState.update { it.copy(magnifier = !it.magnifier) }
            CustomFunction.EXPOSURE_WARNING -> mutableState.update { it.copy(exposureWarning = !it.exposureWarning) }
            CustomFunction.FOCUS_PEAKING -> mutableState.update { it.copy(focusPeaking = !it.focusPeaking) }
        }
    }

    /** Called with an owned upright preview bitmap. Drop frames under load; never delay capture. */
    fun analyze(bitmap: Bitmap) {
        if (!analyzing.compareAndSet(false, true)) { bitmap.recycle(); return }
        val generation = analysisGeneration.get()
        viewModelScope.launch(Dispatchers.Default) {
            try {
                if (generation != analysisGeneration.get() || !active || menuVisible) return@launch
                val snapshot = state.value
                if (snapshot.exposure.needsMeteringSample(snapshot.capabilities.apertures.size > 1)) {
                    val size = bitmap.width * bitmap.height
                    if (meteringPixels.size < size) meteringPixels = IntArray(size)
                    bitmap.getPixels(meteringPixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                    var luma = 0.0
                    var samples = 0
                    for (y in 0 until bitmap.height step 4) for (x in 0 until bitmap.width step 4) {
                        val p = meteringPixels[y * bitmap.width + x]
                        luma += .2126 * ((p shr 16) and 255) + .7152 * ((p shr 8) and 255) + .0722 * (p and 255)
                        samples++
                    }
                    engine.submitMeteringLuma((luma / (samples * 255.0)).toFloat())
                }
                if (snapshot.settings.modelEnabled && detectorReady) {
                    val boxes = detector.detect(bitmap)
                    if (generation != analysisGeneration.get()) return@launch
                    val selection = tracker.update(boxes, snapshot.settings.focusArea, snapshot.settings.trackingMode, gate.pressed)
                    mutableState.update { it.copy(subjects = selection.first, selectedSubjectId = selection.second?.id) }
                    val selected = selection.second
                    if (!captureHoldsFocus) engine.updateFocusRegion(selected?.let { (it.left + it.right) / 2 } ?: .5f,
                        selected?.let { (it.top + it.bottom) / 2 } ?: .5f, targeted = selected != null)
                }
            } catch (e: CancellationException) { throw e }
            catch (e: Exception) {
                if (state.value.settings.modelEnabled) {
                    mutableState.update { it.copy(settings = it.settings.copy(modelEnabled = false), subjects = emptyList(), modelStatus = "System AF") }
                    engine.releaseFocus()
                }
                report(e)
            } finally { bitmap.recycle(); analyzing.set(false) }
        }
    }

    override fun onCleared() {
        analysisGeneration.incrementAndGet()
        val oldSurface = surface; surface = null
        app.scope.launch {
            try { engine.stopAndAwait() }
            catch (e: TimeoutCancellationException) { android.util.Log.w("CameraRedesigned", "Camera close timed out", e) }
            finally { engine.close(); oldSurface?.release() }
        }
        app.scope.launch(Dispatchers.IO) { detector.close() }
    }
}
