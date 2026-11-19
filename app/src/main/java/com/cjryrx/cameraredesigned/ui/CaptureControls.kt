package com.cjryrx.cameraredesigned.ui

import androidx.compose.animation.core.animateFloatAsState
import androidx.compose.animation.core.spring
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.gestures.awaitEachGesture
import androidx.compose.foundation.gestures.awaitFirstDown
import androidx.compose.foundation.gestures.snapping.rememberSnapFlingBehavior
import androidx.compose.foundation.interaction.DragInteraction
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.lazy.rememberLazyListState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.hapticfeedback.HapticFeedbackType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.platform.LocalHapticFeedback
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.onClick
import androidx.compose.ui.semantics.role
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.IntOffset
import androidx.compose.ui.unit.dp
import com.cjryrx.cameraredesigned.domain.*
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlin.math.abs
import kotlin.math.roundToInt

@Composable
internal fun ShutterSlider(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    val diameter = state.settings.sliderSizeDp.coerceIn(48f, 88f).dp
    val travel = state.settings.sliderTravelDp.coerceIn(24f, 96f).dp
    val distancePx = with(LocalDensity.current) { travel.toPx() }
    val thumbBottomPx = with(LocalDensity.current) { (diameter + 8.dp).toPx() }
    val latestAction by rememberUpdatedState(onAction)
    val burst = state.settings.shootingMode == ShootingMode.BURST
    val haptics = LocalHapticFeedback.current
    var drag by remember { mutableFloatStateOf(0f) }
    var pressed by remember { mutableStateOf(false) }
    val animated by animateFloatAsState(drag, spring(dampingRatio = 0.7f), label = "shutterReturn")
    val description = words("Shutter: hold to focus, drag down to capture", "快门：按住对焦，下拉拍摄")
    // Observe gestures on the stationary track, not on the moving thumb. Otherwise
    // each visual offset changes local pointer coordinates and introduces jitter.
    val gestureModifier = Modifier.semantics {
                contentDescription = description
                role = Role.Button
                onClick {
                    if (!state.cameraReady) false else {
                        latestAction(CameraAction.PressShutter)
                        latestAction(CameraAction.DragShutter(true))
                        latestAction(CameraAction.ReleaseShutter)
                        true
                    }
                }
            }
            .pointerInput(state.cameraReady, burst, distancePx, thumbBottomPx, state.settings.haptics) {
                if (!state.cameraReady) return@pointerInput
                awaitEachGesture {
                    val down = awaitFirstDown()
                    if (down.position.y > thumbBottomPx) return@awaitEachGesture
                    down.consume()
                    pressed = true
                    val gesture = ShutterGesture(burst)
                    latestAction(CameraAction.PressShutter)
                    try {
                        do {
                            val event = awaitPointerEvent()
                            val pointer = event.changes.firstOrNull { it.id == down.id } ?: break
                            if (!pointer.pressed) break
                            val fraction = ((pointer.position.y - down.position.y) / distancePx).coerceIn(0f, 1f)
                            // A hysteresis gap prevents small finger tremors from toggling burst.
                            gesture.move(fraction)?.let { armed ->
                                latestAction(CameraAction.DragShutter(armed))
                                if (armed) {
                                    if (state.settings.haptics) haptics.performHapticFeedback(HapticFeedbackType.LongPress)
                                }
                            }
                            drag = gesture.thumbFraction(fraction) * distancePx
                            pointer.consume()
                        } while (true)
                    } finally {
                        pressed = false
                        drag = 0f
                        latestAction(CameraAction.ReleaseShutter)
                    }
                }
            }
    Box(Modifier.width(diameter + 16.dp).height(diameter + travel + 16.dp).then(gestureModifier), contentAlignment = Alignment.TopCenter) {
        Box(Modifier.padding(top = 8.dp).width(diameter).height(diameter + travel)
            .clip(RoundedCornerShape(50)).background(MaterialTheme.colorScheme.surfaceContainerHigh))
        Box(Modifier.padding(top = 8.dp).offset { IntOffset(0, animated.roundToInt()) }.size(diameter)
            .border(2.dp, if (state.focused) Color(0xFF9ADC9C) else MaterialTheme.colorScheme.outline, CircleShape)
            .padding(5.dp).clip(CircleShape)
            .background(if (pressed || state.shooting) MaterialTheme.colorScheme.primary else Color(0xFFE5E6E2))
            .alpha(if (state.cameraReady) 1f else 0.4f), contentAlignment = Alignment.Center) {
            if (state.countdown > 0) Text(state.countdown.toString(), color = Color.Black, style = MaterialTheme.typography.headlineMedium)
            else if (state.focusing) CircularProgressIndicator(Modifier.size(24.dp), strokeWidth = 2.dp, color = Color.Black)
            else Text(if (burst) "•••" else "↓", color = Color.Black, style = MaterialTheme.typography.headlineSmall)
        }
    }
}

@Composable
internal fun ExposureControls(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    val exposure = state.exposure
    val cap = state.capabilities
    fun update(value: ExposureSettings) = onAction(CameraAction.Exposure(value))
    val modes = ExposureMode.entries.filter { (it != ExposureMode.A || cap.apertures.size > 1) &&
        (it == ExposureMode.AUTO || it == ExposureMode.P || cap.supportsManualSensor) }
    Wheel(words("Mode", "模式"), modes.map { label(it) }, modes.indexOf(exposure.mode)) { update(exposure.copy(mode = modes[it])) }
    if (exposure.mode != ExposureMode.AUTO) {
        if (cap.apertures.size > 1) {
            val manual = exposure.mode == ExposureMode.A || exposure.mode == ExposureMode.M
            if (manual) Wheel(words("Aperture", "光圈"), cap.apertures.map { "ƒ/$it" }, cap.apertures.indexOf(exposure.aperture)) {
                update(exposure.copy(aperture = cap.apertures[it]))
            } else Wheel(words("Aperture", "光圈"), listOf(state.measuredAperture?.let { "ƒ/$it" } ?: "AUTO"), 0, false) {}
        }
        cap.shutterRange?.let { range ->
            val stops = remember(range) { shutterStops(range) }
            if (exposure.mode == ExposureMode.S || exposure.mode == ExposureMode.M) {
                Wheel(words("Shutter", "快门"), stops.map(::shutterLabel), nearest(stops, exposure.shutterNanos)) { update(exposure.copy(shutterNanos = stops[it])) }
            } else Wheel(words("Shutter", "快门"), listOf(state.measuredShutterNanos?.let(::shutterLabel) ?: "AUTO"), 0, false) {}
        }
        cap.isoRange?.let { range ->
            val stops = remember(range) { listOf<Int?>(null) + isoStops(range) }
            Wheel("ISO", stops.map { it?.toString() ?: state.measuredIso?.let { value -> "A · $value" } ?: "AUTO" },
                stops.indexOf(exposure.iso), cap.supportsManualSensor) { update(exposure.copy(iso = stops[it])) }
        }
        if (exposure.mode == ExposureMode.S && cap.apertures.size <= 1 && exposure.iso != null) {
            Text(words("Fixed aperture + manual ISO: no automatic exposure variable remains.", "固定光圈与手动 ISO 下，已无可自动调整的曝光参数。"),
                style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
    }
    if (cap.compensationStep > 0 && cap.compensationRange.endInclusive > cap.compensationRange.start) {
        val step = cap.compensationStep
        val stops = remember(cap.compensationRange, step) {
            val min = (cap.compensationRange.start / step).roundToInt()
            val max = (cap.compensationRange.endInclusive / step).roundToInt()
            (min..max).map { it * step }
        }
        val selected = stops.indices.minByOrNull { abs(stops[it] - exposure.compensationEv) } ?: 0
        Wheel("EV", stops.map { java.lang.String.format(java.util.Locale.ROOT, "%+.1f", it) }, selected,
            exposure.mode != ExposureMode.M || exposure.iso == null) { update(exposure.copy(compensationEv = stops[it])) }
    }
    if (state.settings.manualFocusEnabled && cap.minFocusDiopters > 0f) {
        Text(words("Manual focus · near → far", "手动对焦 · 近 → 远"), style = MaterialTheme.typography.labelSmall)
        Slider(value = (exposure.focusDiopters ?: 0f).coerceIn(0f, cap.minFocusDiopters),
            onValueChange = { update(exposure.copy(focusDiopters = it)) }, valueRange = 0f..cap.minFocusDiopters)
    }
    if (state.settings.manualWhiteBalanceEnabled && cap.supportsManualWhiteBalance) {
        Text("WB · ${exposure.whiteBalanceKelvin ?: 5500} K", style = MaterialTheme.typography.labelSmall)
        Slider(value = (exposure.whiteBalanceKelvin ?: 5500).toFloat().coerceIn(2000f, 10000f),
            onValueChange = { update(exposure.copy(whiteBalanceKelvin = it.roundToInt())) }, valueRange = 2000f..10000f)
    }
}

private fun nearest(stops: List<Long>, value: Long): Int = stops.indices.minByOrNull { abs(stops[it] - value) } ?: 0

@Composable
internal fun Wheel(title: String, values: List<String>, selected: Int, enabled: Boolean = true, onSelect: (Int) -> Unit) {
    if (values.isEmpty()) return
    val index = selected.coerceIn(values.indices)
    val listState = rememberLazyListState(index)
    val currentCallback by rememberUpdatedState(onSelect)
    val currentIndex by rememberUpdatedState(index)
    var userScrolled by remember { mutableStateOf(false) }
    LaunchedEffect(listState) {
        listState.interactionSource.interactions.collect { if (it is DragInteraction.Start) userScrolled = true }
    }
    LaunchedEffect(index, values.size) { if (!userScrolled) listState.scrollToItem(index) }
    LaunchedEffect(listState, values.size, enabled) {
        snapshotFlow { listState.isScrollInProgress }.distinctUntilChanged().collect { scrolling ->
            if (!scrolling && userScrolled) {
                val layout = listState.layoutInfo
                val center = (layout.viewportStartOffset + layout.viewportEndOffset) / 2
                val closest = layout.visibleItemsInfo.minByOrNull { abs(it.offset + it.size / 2 - center) }?.index
                userScrolled = false
                if (enabled && closest != null && closest != currentIndex) currentCallback(closest)
            }
        }
    }
    Row(Modifier.fillMaxWidth().height(46.dp).alpha(if (enabled) 1f else 0.4f), verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.width(54.dp), style = MaterialTheme.typography.labelSmall, color = MaterialTheme.colorScheme.onSurfaceVariant)
        BoxWithConstraints(Modifier.weight(1f)) {
            val cellWidth = 76.dp
            Box(Modifier.align(Alignment.Center).width(cellWidth).height(36.dp).background(MaterialTheme.colorScheme.primaryContainer, RoundedCornerShape(10.dp)))
            LazyRow(state = listState, flingBehavior = rememberSnapFlingBehavior(listState), userScrollEnabled = enabled,
                contentPadding = PaddingValues(horizontal = ((maxWidth - cellWidth) / 2).coerceAtLeast(0.dp))) {
                itemsIndexed(values) { itemIndex, text ->
                    Box(Modifier.width(cellWidth).height(44.dp).clickable(enabled) { onSelect(itemIndex) }, contentAlignment = Alignment.Center) {
                        Text(text, style = MaterialTheme.typography.labelLarge,
                            fontWeight = if (itemIndex == index) FontWeight.Bold else FontWeight.Normal,
                            color = if (itemIndex == index) MaterialTheme.colorScheme.onPrimaryContainer else MaterialTheme.colorScheme.onSurfaceVariant)
                    }
                }
            }
        }
    }
}
