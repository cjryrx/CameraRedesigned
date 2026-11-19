package com.cjryrx.cameraredesigned.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.toggleable
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.runtime.saveable.Saver
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.cjryrx.cameraredesigned.domain.*
import java.util.Locale
import java.util.UUID
import kotlinx.serialization.json.Json
import kotlin.math.roundToInt

@Composable
internal fun CameraMenu(state: CameraUiState, onAction: (CameraAction) -> Unit, onBack: () -> Unit) {
    var tab by rememberSaveable { mutableIntStateOf(0) }
    val editorSaver = remember { Saver<FilterSpec?, String>(
        save = { value -> value?.let { Json.encodeToString(it) } },
        restore = { Json.decodeFromString<FilterSpec>(it) },
    ) }
    var editingFilter by rememberSaveable(stateSaver = editorSaver) { mutableStateOf<FilterSpec?>(null) }
    Column(Modifier.fillMaxSize().safeDrawingPadding()) {
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp, vertical = 8.dp), verticalAlignment = Alignment.CenterVertically) {
            TextButton(onClick = onBack) { Text(words("‹ Camera", "‹ 拍摄")) }
            Spacer(Modifier.weight(1f))
            Text("CAMERA REDESIGNED", style = MaterialTheme.typography.labelMedium)
        }
        val tabs = listOf(words("Shooting", "拍摄"), words("Focus", "对焦"), words("Filters", "滤镜"), words("Other", "其他"))
        Row(Modifier.fillMaxWidth().padding(horizontal = 12.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
            tabs.forEachIndexed { index, title ->
                FilterChip(selected = index == tab, onClick = { tab = index }, label = { Text(title) }, modifier = Modifier.weight(1f))
            }
        }
        Column(Modifier.weight(1f).fillMaxWidth().verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(16.dp)) {
            when (tab) {
                0 -> ShootingSettings(state, onAction)
                1 -> FocusSettings(state, onAction)
                2 -> FilterSettings(state, onAction) { editingFilter = it }
                else -> OtherSettings(state, onAction)
            }
        }
    }
    editingFilter?.let { filter ->
        FilterEditor(filter, state.samplePhotoPath, onAction,
            onSave = { onAction(CameraAction.SaveFilter(it)); editingFilter = null },
            onDismiss = { editingFilter = null })
    }
}

@Composable
private fun ShootingSettings(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    val s = state.settings
    fun update(value: CameraSettings) = onAction(CameraAction.Settings(value))
    Section(words("File & quality", "文件与画质")) {
        val formats = PhotoFormat.entries.filter { it == PhotoFormat.JPEG || state.capabilities.supportsRaw }
        Choice(words("Format", "格式"), formats, s.format) { update(s.copy(format = it)) }
        if (!state.capabilities.supportsRaw) Hint(words("This lens does not expose RAW capture.", "当前镜头未开放 RAW 拍摄。"))
        NumberSetting(words("JPEG quality", "JPEG 精度"), s.jpegQuality.toFloat(), 50f..100f, "${s.jpegQuality}", 49) { update(s.copy(jpegQuality = it.roundToInt())) }
        Hint(words("Resolution is preserved for every burst frame.", "所有连拍帧均保持所选分辨率。"))
    }
    Section(words("Capture", "拍摄方式")) {
        val modes = ShootingMode.entries.filter { it != ShootingMode.FOCUS_BRACKET || state.capabilities.minFocusDiopters > 0f }
        Choice(words("Shooting mode", "拍摄模式"), modes, s.shootingMode) { update(s.copy(shootingMode = it)) }
        Choice(words("Shutter priority", "快门响应"), ShutterPriority.entries, s.priority) { update(s.copy(priority = it)) }
        if (s.priority == ShutterPriority.FOCUS) Hint(words("Wait for focus with a bounded timeout; failure is reported.", "等待合焦，超时后明确提示失败。"))
        if (s.shootingMode == ShootingMode.BURST) {
            Choice(words("Burst speed", "连拍速度"), BurstRate.entries, s.burstRate) { update(s.copy(burstRate = it)) }
            Hint(words("Actual speed depends on exposure, format and storage. Resolution never changes automatically.", "实际速度取决于曝光、格式和存储性能；不会自动降低分辨率。"))
        }
        ChoiceValue(words("Timer", "倒计时"), listOf(0, 3, 10), s.timerSeconds, { if (it == 0) words("Off", "关闭") else "${it}s" }) { update(s.copy(timerSeconds = it)) }
    }
    if (s.shootingMode == ShootingMode.EXPOSURE_BRACKET) Section(words("Exposure bracketing", "包围曝光")) {
        ChoiceValue(words("Frames", "张数"), listOf(3, 5, 7), s.bracketCount, { it.toString() }) { update(s.copy(bracketCount = it)) }
        ChoiceValue(words("EV interval", "EV 间隔"), listOf(.3f, .7f, 1f, 2f), s.bracketStepEv, { "$it EV" }) { update(s.copy(bracketStepEv = it)) }
        Toggle(words("Keep source images", "保留源片"), s.keepBracketSources || s.format != PhotoFormat.JPEG, enabled = s.format == PhotoFormat.JPEG) { update(s.copy(keepBracketSources = it)) }
        Hint(words("RAW brackets retain DNG sources and also produce a merged JPEG.", "RAW 包围拍摄保留 DNG 源片，并输出合成 JPEG。"))
    }
    if (s.shootingMode == ShootingMode.FOCUS_BRACKET) Section(words("Focus stacking", "包围对焦")) {
        Choice(words("Focus selection", "对焦设置"), BracketFocusMethod.entries, s.focusMethod) { update(s.copy(focusMethod = it)) }
        if (s.focusMethod == BracketFocusMethod.RANGE) {
            val max = state.capabilities.minFocusDiopters.coerceAtLeast(.1f)
            NumberSetting(words("Near endpoint", "近端"), s.focusNear.coerceIn(0f, max), 0f..max, focusDistance(s.focusNear, state.capabilities.calibratedFocus)) {
                update(s.copy(focusNear = it.coerceAtLeast(s.focusFar.coerceAtMost(max))))
            }
            NumberSetting(words("Far endpoint", "远端"), s.focusFar.coerceIn(0f, max), 0f..max, focusDistance(s.focusFar, state.capabilities.calibratedFocus)) {
                update(s.copy(focusFar = it.coerceAtMost(s.focusNear.coerceAtMost(max))))
            }
            ChoiceValue(words("Frames", "张数"), listOf(3, 5, 7, 9, 15), s.focusStackCount, { it.toString() }) { update(s.copy(focusStackCount = it)) }
            if (!state.capabilities.calibratedFocus) Hint(words("This lens has no calibrated distance scale. Values indicate relative focus position.", "此镜头无可靠距离标定，数值仅表示相对对焦位置。"))
        } else {
            Hint(words("Return to the camera and tap each subject to record its focus distance.", "返回拍摄界面，依次点击主体以记录对焦位置。"))
            Text(words("Recorded points: ${state.focusPoints.size}", "已记录 ${state.focusPoints.size} 个对焦点"))
            OutlinedButton(onClick = { onAction(CameraAction.ClearFocusPoints) }, enabled = state.focusPoints.isNotEmpty()) { Text(words("Clear points", "清除对焦点")) }
        }
        Toggle(words("Keep source images", "保留源片"), s.keepBracketSources || s.format != PhotoFormat.JPEG, enabled = s.format == PhotoFormat.JPEG) { update(s.copy(keepBracketSources = it)) }
        Hint(words("For best results, keep the camera and subject still throughout the sequence.", "拍摄期间请保持相机与主体稳定，以获得最佳融合效果。"))
    }
}

@Composable
private fun FocusSettings(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    val s = state.settings
    fun update(value: CameraSettings) = onAction(CameraAction.Settings(value))
    Section(words("Autofocus", "自动对焦")) {
        Choice(words("Focus area", "对焦区域"), FocusArea.entries, s.focusArea) { update(s.copy(focusArea = it)) }
        Choice(words("When holding the shutter", "按住滑块时"), TrackingMode.entries, s.trackingMode) { update(s.copy(trackingMode = it)) }
        Toggle(words("Show focus boxes", "显示对焦框"), s.showFocusBoxes) { update(s.copy(showFocusBoxes = it)) }
        Hint(words("Releasing the shutter searches for the next subject. If no subject is found, the selected area is focused.", "松开滑块后自动寻找下一个主体；未识别到主体时对所选区域对焦。"))
    }
    Section(words("Subject recognition", "主体识别")) {
        Text(state.modelStatus, style = MaterialTheme.typography.bodyMedium)
        Toggle(words("Use recognition model", "使用识别模型"), s.modelEnabled) { update(s.copy(modelEnabled = it)) }
        Hint(words("System autofocus works without any model download. Recognition runs on this device after you enable an imported or downloaded model.", "无需下载即可使用系统自动对焦。导入或下载模型并启用后，在本机进行主体识别。"))
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            FilledTonalButton(onClick = { onAction(CameraAction.DownloadModel) }) { Text(words("Download model", "下载模型")) }
            OutlinedButton(onClick = { onAction(CameraAction.ImportModel) }) { Text(words("Import model", "导入模型")) }
        }
        Hint(words("ONNX detection · RGB float32 [1,3,640,640] → [1,4+C,8400]. Models must follow the documented import contract.", "ONNX 检测模型 · RGB float32 [1,3,640,640] → [1,4+C,8400]。导入模型须符合协议。"))
    }
    Section(words("Manual controls", "手动控制")) {
        Toggle(words("Manual focus", "手动对焦"), s.manualFocusEnabled, state.capabilities.minFocusDiopters > 0f) {
            update(s.copy(manualFocusEnabled = it))
            if (!it) onAction(CameraAction.Exposure(state.exposure.copy(focusDiopters = null)))
        }
        Toggle(words("Manual white balance", "手动白平衡"), s.manualWhiteBalanceEnabled, state.capabilities.supportsManualWhiteBalance) {
            update(s.copy(manualWhiteBalanceEnabled = it))
            if (!it) onAction(CameraAction.Exposure(state.exposure.copy(whiteBalanceKelvin = null)))
        }
        Hint(words("Enabled controls appear below the exposure dials.", "开启后，控制项显示在拍摄界面的曝光拨轮下方。"))
    }
}

@Composable
private fun FilterSettings(state: CameraUiState, onAction: (CameraAction) -> Unit, onEdit: (FilterSpec) -> Unit) {
    val s = state.settings
    Section(words("Filter library", "滤镜库")) {
        Toggle(words("Apply to live preview", "应用于实时取景"), s.realtimeFilter) { onAction(CameraAction.Settings(s.copy(realtimeFilter = it))) }
        Hint(words("One filter at a time. JPEG files receive the selected filter; DNG data remains unchanged.", "一次启用一个滤镜。所选滤镜应用于 JPEG，DNG 保持原始数据。"))
        FilledTonalButton(onClick = { onEdit(FilterSpec(UUID.randomUUID().toString(), "")) }, modifier = Modifier.fillMaxWidth()) { Text(words("Create custom filter", "创建自定义滤镜")) }
        Row(horizontalArrangement = Arrangement.spacedBy(8.dp)) {
            OutlinedButton(onClick = { onAction(CameraAction.ImportFilter) }) { Text(words("Import filter", "导入滤镜")) }
            OutlinedButton(onClick = { onAction(CameraAction.ImportLut) }) { Text(words("Import LUT", "导入 LUT")) }
        }
    }
    if (state.filters.isEmpty()) Hint(words("Your saved filters and imported .cube LUTs appear here.", "保存的滤镜与导入的 .cube LUT 将显示在这里。"))
    state.filters.forEach { filter ->
        Section(filter.name) {
            Row(Modifier.fillMaxWidth(), verticalAlignment = Alignment.CenterVertically) {
                RadioButton(selected = s.activeFilterId == filter.id, onClick = {
                    onAction(CameraAction.Settings(s.copy(activeFilterId = if (s.activeFilterId == filter.id) null else filter.id)))
                })
                Text(if (filter.lutPath != null) "3D LUT" else words("Custom adjustments", "自定义调色"), Modifier.weight(1f))
                TextButton(onClick = { onEdit(filter) }) { Text(words("Edit", "编辑")) }
            }
            if (filter.lutPath != null) NumberSetting(words("Intensity", "强度"), filter.intensity, 0f..1f, "${(filter.intensity * 100).roundToInt()}%") {
                onAction(CameraAction.SaveFilter(filter.copy(intensity = it)))
            }
            Row {
                TextButton(onClick = { onAction(CameraAction.ExportFilter(filter.id)) }) { Text(words("Export", "导出")) }
                TextButton(onClick = { onAction(CameraAction.ShareFilter(filter.id)) }) { Text(words("Share", "分享")) }
                Spacer(Modifier.weight(1f))
                var confirmDelete by remember(filter.id) { mutableStateOf(false) }
                TextButton(onClick = { confirmDelete = true }) { Text(words("Delete", "删除"), color = MaterialTheme.colorScheme.error) }
                if (confirmDelete) AlertDialog(onDismissRequest = { confirmDelete = false }, title = { Text(words("Delete filter?", "删除滤镜？")) }, text = { Text(filter.name) },
                    confirmButton = { TextButton(onClick = { onAction(CameraAction.DeleteFilter(filter.id)); confirmDelete = false }) { Text(words("Delete", "删除")) } },
                    dismissButton = { TextButton(onClick = { confirmDelete = false }) { Text(words("Cancel", "取消")) } })
            }
        }
    }
}

@Composable
private fun OtherSettings(state: CameraUiState, onAction: (CameraAction) -> Unit) {
    val s = state.settings
    fun update(value: CameraSettings) = onAction(CameraAction.Settings(value))
    Section(words("Controls", "操作")) {
        Choice(words("Custom key", "自定义功能键"), CustomFunction.entries, s.customFunction) { update(s.copy(customFunction = it)) }
        Toggle(words("Volume button shutter", "音量键快门"), s.volumeShutter) { update(s.copy(volumeShutter = it)) }
        Toggle(words("Haptic feedback", "触觉反馈"), s.haptics) { update(s.copy(haptics = it)) }
        NumberSetting(words("Shutter diameter", "滑块直径"), s.sliderSizeDp, 48f..88f, "${s.sliderSizeDp.roundToInt()} dp") { update(s.copy(sliderSizeDp = it)) }
        NumberSetting(words("Shutter travel", "下拉行程"), s.sliderTravelDp, 24f..96f, "${s.sliderTravelDp.roundToInt()} dp") { update(s.copy(sliderTravelDp = it)) }
    }
    Section(words("About", "关于")) {
        Text("Camera Redesigned", style = MaterialTheme.typography.titleLarge)
        Text(words("Authors & contributors", "作者与贡献者"), style = MaterialTheme.typography.labelLarge)
        Text("cjryrx（梦得斯鸫）\nChatGPT", style = MaterialTheme.typography.bodyLarge)
        Text("AGPL-3.0", style = MaterialTheme.typography.labelMedium)
    }
}

@Composable
internal fun Section(title: String, content: @Composable ColumnScope.() -> Unit) {
    Column(Modifier.fillMaxWidth().background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(20.dp)).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
        Text(title, style = MaterialTheme.typography.titleMedium, color = MaterialTheme.colorScheme.primary)
        content()
    }
}

@Composable
private fun Hint(value: String) = Text(value, style = MaterialTheme.typography.bodySmall, color = MaterialTheme.colorScheme.onSurfaceVariant)

@Composable
private fun <T : Enum<T>> Choice(title: String, options: List<T>, selected: T, onSelect: (T) -> Unit) =
    ChoiceValue(title, options, selected, { label(it) }, onSelect)

@Composable
private fun <T> ChoiceValue(title: String, options: List<T>, selected: T, name: @Composable (T) -> String, onSelect: (T) -> Unit) {
    var expanded by remember { mutableStateOf(false) }
    Box {
        Row(Modifier.fillMaxWidth().clickable { expanded = true }.heightIn(min = 48.dp), verticalAlignment = Alignment.CenterVertically) {
            Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
            Text(name(selected), style = MaterialTheme.typography.labelLarge, color = MaterialTheme.colorScheme.primary)
            Text(" ▾", color = MaterialTheme.colorScheme.onSurfaceVariant)
        }
        DropdownMenu(expanded = expanded, onDismissRequest = { expanded = false }) {
            options.forEach { value -> DropdownMenuItem(text = { Text(name(value)) }, onClick = { onSelect(value); expanded = false }) }
        }
    }
}

@Composable
private fun Toggle(title: String, checked: Boolean, enabled: Boolean = true, onChange: (Boolean) -> Unit) {
    Row(Modifier.fillMaxWidth().heightIn(min = 48.dp)
        .toggleable(value = checked, enabled = enabled, role = Role.Switch, onValueChange = onChange),
        verticalAlignment = Alignment.CenterVertically) {
        Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium)
        Switch(checked, onCheckedChange = null, enabled = enabled)
    }
}

@Composable
internal fun NumberSetting(title: String, value: Float, range: ClosedFloatingPointRange<Float>, formatted: String, steps: Int = 0, onChange: (Float) -> Unit) {
    var current by remember(value) { mutableFloatStateOf(value.coerceIn(range)) }
    Column {
        Row { Text(title, Modifier.weight(1f), style = MaterialTheme.typography.bodyMedium); Text(formatted, style = MaterialTheme.typography.labelMedium) }
        Slider(current, onValueChange = { current = it }, valueRange = range, steps = steps, onValueChangeFinished = { onChange(current) })
    }
}

@Composable
private fun focusDistance(diopters: Float, calibrated: Boolean): String = when {
    diopters == 0f -> "∞"
    calibrated -> String.format(Locale.ROOT, "%.2f m", 1f / diopters)
    else -> String.format(Locale.ROOT, "%.2f", diopters)
}
