package com.cjryrx.cameraredesigned.ui

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.SurfaceTexture
import android.view.Surface
import android.view.TextureView
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.detectTapGestures
import androidx.compose.foundation.layout.*
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import androidx.compose.ui.viewinterop.AndroidView
import com.cjryrx.cameraredesigned.domain.*
import com.cjryrx.cameraredesigned.ui.render.GpuPreviewRenderer
import com.cjryrx.cameraredesigned.ui.render.PreviewStyle
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

@Composable
internal fun CameraPreview(
    state: CameraUiState,
    onAction: (CameraAction) -> Unit,
    onPreviewAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onPreviewDestroyed: (releaseInput: () -> Unit) -> Unit,
    onAnalysisFrame: (Bitmap) -> Unit,
    onFirstPreviewFrame: () -> Unit,
    modifier: Modifier = Modifier,
) {
    val latestAvailable by rememberUpdatedState(onPreviewAvailable)
    val latestDestroyed by rememberUpdatedState(onPreviewDestroyed)
    val latestFrame by rememberUpdatedState(onAnalysisFrame)
    val latestFirstFrame by rememberUpdatedState(onFirstPreviewFrame)
    val cameraReady by rememberUpdatedState(state.cameraReady)
    var texture by remember { mutableStateOf<TextureView?>(null) }
    var renderer by remember { mutableStateOf<GpuPreviewRenderer?>(null) }
    var gpuFailure by remember { mutableStateOf<String?>(null) }
    val front = state.lenses.firstOrNull { it.id == state.activeLensId }?.frontFacing == true
    val bufferWidth = state.previewWidth.coerceAtLeast(1)
    val bufferHeight = state.previewHeight.coerceAtLeast(1)
    val displayDegrees = when (state.displayRotation) {
        Surface.ROTATION_90 -> 90
        Surface.ROTATION_180 -> 180
        Surface.ROTATION_270 -> 270
        else -> 0
    }
    val relativeRotation = ((state.capabilities.sensorOrientation + if (front) displayDegrees else -displayDegrees) + 360) % 360
    // Camera2's producer puts sensor rotation and front-camera mirroring into the
    // native buffer transform. Both TextureView and OES getTransformMatrix apply it.
    // Only the display rotation remains; repeating the sensor transform rotates twice.
    val displayCorrection = camera2DisplayCorrection(displayDegrees)
    val naturalWidth = if (state.capabilities.sensorOrientation % 180 == 0) bufferWidth else bufferHeight
    val naturalHeight = if (state.capabilities.sensorOrientation % 180 == 0) bufferHeight else bufferWidth
    val latestGeometry by rememberUpdatedState(PreviewTransform(naturalWidth, naturalHeight, displayCorrection, false))
    val ratio = if (relativeRotation % 180 == 0) bufferWidth.toFloat() / bufferHeight else bufferHeight.toFloat() / bufferWidth
    val analysisNeeded = state.cameraReady && (state.settings.modelEnabled || state.exposure.needsMeteringSample(state.capabilities.apertures.size > 1))
    val selected = state.subjects.firstOrNull { it.id == state.selectedSubjectId }
    val focusX = selected?.let { (it.left + it.right) / 2 } ?: state.focusX
    val focusY = selected?.let { (it.top + it.bottom) / 2 } ?: state.focusY
    val gpuStyle = PreviewStyle(displayCorrection, false,
        state.filters.firstOrNull { it.id == state.settings.activeFilterId }.takeIf { state.settings.realtimeFilter },
        state.exposureWarning, state.focusPeaking, state.magnifier, focusX, focusY, analysisNeeded)
    val latestStyle by rememberUpdatedState(gpuStyle)
    SideEffect { renderer?.configure(gpuStyle) }
    LaunchedEffect(texture, analysisNeeded, gpuFailure) {
        val view = texture ?: return@LaunchedEffect
        if (!analysisNeeded || gpuFailure == null) return@LaunchedEffect
        while (isActive) {
            if (view.isAvailable && view.width > 0 && view.height > 0) {
                val scale = 640f / max(view.width, view.height)
                val width = (view.width * min(scale, 1f)).roundToInt().coerceAtLeast(1)
                val height = (view.height * min(scale, 1f)).roundToInt().coerceAtLeast(1)
                // Callback owns this bounded bitmap and must recycle it after processing.
                view.getBitmap(width, height)?.let(latestFrame)
            }
            delay(200)
        }
    }
    BoxWithConstraints(modifier.background(Color.Black)) {
        val previewWidth = minOf(maxWidth, maxHeight * ratio)
        val previewHeight = previewWidth / ratio
        Box(Modifier.width(previewWidth).height(previewHeight).align(Alignment.TopStart).clipToBounds()) {
            key(gpuFailure != null) { AndroidView(
                factory = { context ->
                    TextureView(context).apply {
                        texture = this
                        var ownedRenderer: GpuPreviewRenderer? = null
                        var reportedPreview = false
                        surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                            override fun onSurfaceTextureAvailable(surface: SurfaceTexture, width: Int, height: Int) {
                                if (gpuFailure != null) latestAvailable(surface, width, height)
                                else {
                                    val gpu = GpuPreviewRenderer(
                                        onInputReady = { source, w, h -> latestAvailable(source, w, h) },
                                        onAnalysis = { frame -> latestFrame(frame) },
                                        onError = { error -> gpuFailure = error },
                                    )
                                    renderer = gpu
                                    ownedRenderer = gpu
                                    gpu.configure(latestStyle)
                                    gpu.start(Surface(surface), width, height)
                                }
                            }
                            override fun onSurfaceTextureSizeChanged(surface: SurfaceTexture, width: Int, height: Int) {
                                val geometry = latestGeometry
                                if (gpuFailure != null) {
                                    configureTransform(this@apply, geometry.width, geometry.height, geometry.rotation, geometry.mirror)
                                    latestAvailable(surface, width, height)
                                } else renderer?.resize(width, height)
                            }
                            override fun onSurfaceTextureDestroyed(surface: SurfaceTexture): Boolean {
                                val gpu = ownedRenderer
                                ownedRenderer = null
                                if (renderer === gpu) renderer = null
                                if (gpu != null) {
                                    gpu.pause()
                                    latestDestroyed { gpu.close() }
                                    return true // This is only the output window, not the camera input.
                                }
                                latestDestroyed { surface.release() }
                                return false // Root releases the native input after CameraDevice.onClosed.
                            }
                            override fun onSurfaceTextureUpdated(surface: SurfaceTexture) {
                                if (!reportedPreview && cameraReady && surface.timestamp > 0L) {
                                    reportedPreview = true
                                    latestFirstFrame()
                                }
                            }
                        }
                    }
                },
                update = { if (gpuFailure != null) configureTransform(it, naturalWidth, naturalHeight, displayCorrection, false) },
                modifier = Modifier.fillMaxSize(),
            ) }
            Box(Modifier.fillMaxSize().pointerInput(state.settings.focusMethod, state.settings.shootingMode, state.magnifier, focusX, focusY) {
                detectTapGestures { point ->
                    val zoom = if (state.magnifier && gpuFailure == null) 2f else 1f
                    val cx = if (zoom > 1f) focusX.coerceIn(.25f, .75f) else .5f
                    val cy = if (zoom > 1f) focusY.coerceIn(.25f, .75f) else .5f
                    val x = (cx + (point.x / size.width - .5f) / zoom).coerceIn(0f, 1f)
                    val y = (cy + (point.y / size.height - .5f) / zoom).coerceIn(0f, 1f)
                    if (state.settings.shootingMode == ShootingMode.FOCUS_BRACKET && state.settings.focusMethod == BracketFocusMethod.POINTS)
                        onAction(CameraAction.AddFocusPoint(x, y))
                    else onAction(CameraAction.FocusAt(x, y))
                }
            })
            if (state.settings.showFocusBoxes) Canvas(Modifier.fillMaxSize()) {
                val zoom = if (state.magnifier && gpuFailure == null) 2f else 1f
                val cx = if (zoom > 1f) focusX.coerceIn(.25f, .75f) else .5f
                val cy = if (zoom > 1f) focusY.coerceIn(.25f, .75f) else .5f
                fun screen(x: Float, y: Float) = Offset(((x - cx) * zoom + .5f) * size.width, ((y - cy) * zoom + .5f) * size.height)
                val fraction = when (state.settings.focusArea) {
                    FocusArea.POINT -> .08f
                    FocusArea.CENTER -> .28f
                    FocusArea.WIDE -> .65f
                    FocusArea.FULL -> .96f
                }
                val focusColor = if (state.focused) Color(0xFFB2EFAB) else Color.White.copy(alpha = .6f)
                val area = Size(size.width * fraction * zoom, size.height * fraction * zoom)
                drawRect(focusColor, screen((focusX - fraction / 2).coerceIn(0f, 1f - fraction),
                    (focusY - fraction / 2).coerceIn(0f, 1f - fraction)), area, style = Stroke(1.dp.toPx()))
                state.subjects.forEach { subject ->
                    drawRect(if (subject.id == state.selectedSubjectId) Color(0xFFB2EFAB) else Color.White,
                        screen(subject.left, subject.top),
                        Size((subject.right - subject.left) * size.width * zoom, (subject.bottom - subject.top) * size.height * zoom),
                        style = Stroke(if (subject.id == state.selectedSubjectId) 2.dp.toPx() else 1.dp.toPx()))
                }
                state.focusPoints.forEach { point ->
                    drawCircle(Color(0xFFFFDDB0), 9.dp.toPx(), screen(point.x, point.y), style = Stroke(2.dp.toPx()))
                }
            }
            if (gpuFailure != null) Text(words("GPU preview unavailable; live filters and aids are disabled. Saved photos are unaffected.", "GPU 预览不可用，实时滤镜与辅助显示已停用；照片保存不受影响。"),
                Modifier.align(Alignment.TopStart).background(Color.Black.copy(alpha = .75f)).padding(8.dp), color = Color.White,
                style = MaterialTheme.typography.labelSmall)
            if (state.settings.shootingMode == ShootingMode.FOCUS_BRACKET && state.settings.focusMethod == BracketFocusMethod.POINTS) {
                Text(words("Tap subjects to add focus points", "点击主体添加对焦点"), Modifier.align(Alignment.BottomStart)
                    .background(Color.Black.copy(alpha = .6f)).padding(8.dp), color = Color.White, style = MaterialTheme.typography.labelMedium)
            }
        }
    }
}

private data class PreviewTransform(val width: Int, val height: Int, val rotation: Int, val mirror: Boolean)

/** Camera2 producer metadata already accounts for sensor mounting and front-facing reflection. */
internal fun camera2DisplayCorrection(displayDegrees: Int): Int = (360 - displayDegrees.mod(360)).mod(360)

/** TextureView first stretches the buffer to its bounds; map those bounds to rotated buffer corners. */
private fun configureTransform(view: TextureView, width: Int, height: Int, rotation: Int, mirror: Boolean) {
    if (view.width == 0 || view.height == 0) return
    val source = floatArrayOf(0f, 0f, view.width.toFloat(), 0f, view.width.toFloat(), view.height.toFloat(), 0f, view.height.toFloat())
    val target = previewCorners(view.width, view.height, width, height, rotation, mirror)
    view.setTransform(Matrix().apply { setPolyToPoly(source, 0, target, 0, 4) })
}

internal fun previewCorners(viewWidth: Int, viewHeight: Int, bufferWidth: Int, bufferHeight: Int, rotation: Int, mirror: Boolean): FloatArray {
    val rotatedWidth = if (rotation % 180 == 0) bufferWidth else bufferHeight
    val rotatedHeight = if (rotation % 180 == 0) bufferHeight else bufferWidth
    val scale = min(viewWidth.toFloat() / rotatedWidth.coerceAtLeast(1), viewHeight.toFloat() / rotatedHeight.coerceAtLeast(1))
    val width = rotatedWidth * scale
    val height = rotatedHeight * scale
    val left = (viewWidth - width) / 2
    val top = (viewHeight - height) / 2
    val normalized = listOf(0f to 0f, 1f to 0f, 1f to 1f, 0f to 1f)
    return normalized.flatMap { (x, y) ->
        val (u, v) = when (rotation) { 90 -> 1f - y to x; 180 -> 1f - x to 1f - y; 270 -> y to 1f - x; else -> x to y }
        listOf(left + (if (mirror) 1f - u else u) * width, top + v * height)
    }.toFloatArray()
}
