package com.cjryrx.cameraredesigned.ui

import android.graphics.Bitmap
import android.graphics.SurfaceTexture
import androidx.activity.compose.BackHandler
import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp
import com.cjryrx.cameraredesigned.domain.*

private val CameraColors = darkColorScheme(primary = Color(0xFFB9D4AB), onPrimary = Color(0xFF20341C),
    primaryContainer = Color(0xFF354A30), onPrimaryContainer = Color(0xFFD5F0C7),
    background = Color(0xFF10120F), surface = Color(0xFF10120F), surfaceContainerHigh = Color(0xFF282B25))

@Composable
fun CameraApp(
    state: CameraUiState,
    onAction: (CameraAction) -> Unit,
    onPreviewAvailable: (SurfaceTexture, Int, Int) -> Unit,
    onPreviewDestroyed: (releaseInput: () -> Unit) -> Unit,
    onAnalysisFrame: (Bitmap) -> Unit = {},
    onFirstPreviewFrame: () -> Unit = {},
) {
    var menuOpen by rememberSaveable { mutableStateOf(false) }
    var filterPicker by remember { mutableStateOf(false) }
    val currentAction by rememberUpdatedState(onAction)
    LaunchedEffect(menuOpen) { currentAction(CameraAction.MenuVisible(menuOpen)) }
    BackHandler(menuOpen) { menuOpen = false }
    val colors = if (!menuOpen || isSystemInDarkTheme()) CameraColors else lightColorScheme()
    MaterialTheme(colorScheme = colors) {
        Surface(Modifier.fillMaxSize()) {
            if (menuOpen) CameraMenu(state, onAction) { menuOpen = false }
            else BoxWithConstraints(Modifier.fillMaxSize().safeDrawingPadding().padding(8.dp)) {
                val landscape = maxWidth > maxHeight
                val portraitControlsMaxHeight = maxHeight * .60f
                val status: @Composable () -> Unit = {
                    Column(Modifier.fillMaxWidth().padding(horizontal = 6.dp, vertical = 4.dp)) {
                        val text = when {
                            state.waitingForSave -> words("Waiting for photo storage", "正在等待保存队列")
                            state.processingCount > 0 -> words("Saving ${state.processingCount}", "正在保存 ${state.processingCount} 张")
                            else -> state.status
                        }
                        if (text.isNotBlank()) Text(text, style = MaterialTheme.typography.labelSmall, maxLines = 2)
                        if (state.failedSaveCount > 0) TextButton(onClick = { onAction(CameraAction.RetrySaving) }) {
                            Text(words("Retry saving (${state.failedSaveCount})", "重新保存（${state.failedSaveCount}）"))
                        }
                        if (state.shooting) Text(words("Captured ${state.shotsTaken}", "已拍摄 ${state.shotsTaken} 张"), color = MaterialTheme.colorScheme.primary, style = MaterialTheme.typography.labelSmall)
                    }
                }
                val actions: @Composable () -> Unit = {
                    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
                        FilledTonalButton(onClick = { filterPicker = true }, modifier = Modifier.weight(1f)) {
                            Text(state.filters.firstOrNull { it.id == state.settings.activeFilterId }?.name ?: words("Filters", "滤镜"), maxLines = 1)
                        }
                        OutlinedButton(onClick = { onAction(CameraAction.CustomKey) }, modifier = Modifier.widthIn(min = 64.dp)) { Text("Fn") }
                    }
                    TextButton(onClick = { menuOpen = true }, modifier = Modifier.fillMaxWidth()) { Text(words("Menu", "菜单")) }
                }
                if (landscape) Row(Modifier.fillMaxSize(), horizontalArrangement = Arrangement.spacedBy(12.dp)) {
                    CameraPreview(state, onAction, onPreviewAvailable, onPreviewDestroyed, onAnalysisFrame, onFirstPreviewFrame, Modifier.weight(1f).fillMaxHeight())
                    Column(Modifier.width(290.dp).fillMaxHeight()) {
                        Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween, verticalAlignment = Alignment.Top) {
                            Column(Modifier.weight(1f)) { LensBar(state, onAction); QuickControls(state, onAction) }
                            ShutterSlider(state, onAction)
                        }
                        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState())) {
                            ExposureControls(state, onAction)
                            actions()
                            status()
                        }
                    }
                } else Column(Modifier.fillMaxSize()) {
                    Row(Modifier.weight(1f).fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                        CameraPreview(state, onAction, onPreviewAvailable, onPreviewDestroyed, onAnalysisFrame, onFirstPreviewFrame, Modifier.weight(1f).fillMaxHeight())
                        Column(horizontalAlignment = Alignment.CenterHorizontally) {
                            ShutterSlider(state, onAction)
                            Text(label(state.settings.shootingMode), style = MaterialTheme.typography.labelSmall)
                        }
                    }
                    Column(Modifier.fillMaxWidth().heightIn(max = portraitControlsMaxHeight).verticalScroll(rememberScrollState())) {
                        LensBar(state, onAction)
                        QuickControls(state, onAction)
                        ExposureControls(state, onAction)
                        actions()
                        status()
                    }
                }
            }
            if (state.error != null) AlertDialog(onDismissRequest = { onAction(CameraAction.DismissError) },
                title = { Text(words("Camera notice", "相机提示")) }, text = { Text(state.error) },
                confirmButton = { TextButton(onClick = { onAction(CameraAction.DismissError) }) { Text(words("OK", "确定")) } })
            if (filterPicker) AlertDialog(onDismissRequest = { filterPicker = false },
                title = { Text(words("Select filter", "选择滤镜")) },
                text = {
                    Column(Modifier.heightIn(max = 400.dp).verticalScroll(rememberScrollState())) {
                        TextButton(onClick = { onAction(CameraAction.Settings(state.settings.copy(activeFilterId = null))); filterPicker = false }) { Text(words("Original", "原片")) }
                        state.filters.forEach { filter ->
                            TextButton(onClick = { onAction(CameraAction.Settings(state.settings.copy(activeFilterId = filter.id))); filterPicker = false }) { Text(filter.name) }
                        }
                        if (state.filters.isEmpty()) Text(words("Create a filter or import a LUT in the menu.", "在菜单中创建滤镜或导入 LUT。"))
                    }
                }, confirmButton = { TextButton(onClick = { filterPicker = false }) { Text(words("Close", "关闭")) } })
        }
    }
}

@Composable
private fun LensBar(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    Row(Modifier.fillMaxWidth().horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(6.dp)) {
        state.lenses.forEach { lens -> FilterChip(selected = lens.id == state.activeLensId, onClick = { onAction(CameraAction.SelectLens(lens.id)) },
            label = { Text(lens.label) }, enabled = !state.shooting) }
    }
}

@Composable
private fun QuickControls(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    Row(Modifier.horizontalScroll(rememberScrollState()), horizontalArrangement = Arrangement.spacedBy(8.dp)) {
        if (state.capabilities.hasFlash) AssistChip(onClick = {
            val modes = FlashMode.entries
            onAction(CameraAction.Settings(state.settings.copy(flash = modes[(modes.indexOf(state.settings.flash) + 1) % modes.size])))
        }, label = { Text(words("Flash", "闪光") + " · " + label(state.settings.flash)) })
        AssistChip(onClick = {
            val delays = listOf(0, 3, 10)
            onAction(CameraAction.Settings(state.settings.copy(timerSeconds = delays[(delays.indexOf(state.settings.timerSeconds) + 1).mod(delays.size)])))
        }, label = { Text(words("Timer", "倒计时") + " · ${state.settings.timerSeconds}s") })
        if (state.focusPoints.isNotEmpty()) InputChip(selected = true, onClick = { onAction(CameraAction.ClearFocusPoints) },
            label = { Text(words("Clear ${state.focusPoints.size} points", "清除 ${state.focusPoints.size} 个点")) })
    }
}
