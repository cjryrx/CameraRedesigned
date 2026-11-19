package com.cjryrx.cameraredesigned.ui

import android.graphics.Bitmap
import androidx.compose.foundation.Image
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.runtime.saveable.Saver
import androidx.compose.runtime.saveable.rememberSaveable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.layout.ContentScale
import androidx.compose.ui.unit.dp
import androidx.compose.ui.window.Dialog
import androidx.compose.ui.window.DialogProperties
import com.cjryrx.cameraredesigned.domain.*
import com.cjryrx.cameraredesigned.imaging.ImageProcessor
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.delay
import java.io.File
import java.util.Locale
import kotlinx.serialization.json.Json

internal val filterDraftSaver = Saver<FilterSpec, String>(
    save = { Json.encodeToString(it) },
    restore = { Json.decodeFromString<FilterSpec>(it) },
)

@Composable
internal fun FilterEditor(
    original: FilterSpec,
    samplePath: String?,
    onAction: (CameraAction) -> Unit,
    onSave: (FilterSpec) -> Unit,
    onDismiss: () -> Unit,
) {
    var draft by rememberSaveable(original.id, stateSaver = filterDraftSaver) { mutableStateOf(original) }
    var source by remember { mutableStateOf<Bitmap?>(null) }
    var rendered by remember { mutableStateOf<Bitmap?>(null) }
    var loading by remember { mutableStateOf(false) }
    var previewError by remember { mutableStateOf<String?>(null) }
    val processor = remember { ImageProcessor() }
    LaunchedEffect(samplePath) {
        previewError = null
        source = null
        rendered = null
        if (samplePath != null) try {
            loading = true
            source = processor.decodePreview(File(samplePath), 1024)
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            previewError = error.message
        } finally {
            loading = false
        }
    }
    LaunchedEffect(source, draft.adjustments, draft.lutPath, draft.intensity) {
        val bitmap = source ?: return@LaunchedEffect
        delay(100) // Coalesce edits; never queue a render for every slider pointer event.
        try {
            loading = true
            rendered = processor.preview(bitmap, draft)
            previewError = null
        } catch (cancelled: CancellationException) {
            throw cancelled
        } catch (error: Exception) {
            previewError = error.message
        } finally {
            loading = false
        }
    }
    Dialog(onDismissRequest = onDismiss, properties = DialogProperties(usePlatformDefaultWidth = false)) {
        Surface(Modifier.fillMaxSize().safeDrawingPadding()) {
            Column(Modifier.fillMaxSize()) {
                Row(Modifier.fillMaxWidth().padding(8.dp), verticalAlignment = Alignment.CenterVertically) {
                    TextButton(onClick = onDismiss) { Text(words("Cancel", "取消")) }
                    Text(words("Filter editor", "滤镜编辑"), Modifier.weight(1f), style = MaterialTheme.typography.titleMedium)
                    TextButton(onClick = { onSave(draft.copy(name = draft.name.trim())) }, enabled = draft.name.isNotBlank()) { Text(words("Save", "保存")) }
                }
                Box(Modifier.fillMaxWidth().height(220.dp).background(Color.Black), contentAlignment = Alignment.Center) {
                    (rendered ?: source)?.let { Image(it.asImageBitmap(), words("Filter preview", "滤镜预览"), Modifier.fillMaxSize(), contentScale = ContentScale.Fit) }
                    if (source == null) TextButton(onClick = { onAction(CameraAction.SelectSamplePhoto) }) { Text(words("Choose example photo", "选择实例照片")) }
                    if (loading) CircularProgressIndicator(Modifier.size(24.dp).align(Alignment.TopEnd).padding(4.dp))
                }
                Column(Modifier.weight(1f).verticalScroll(rememberScrollState()).padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    OutlinedTextField(draft.name, onValueChange = { draft = draft.copy(name = it.take(80)) },
                        label = { Text(words("Filter name", "滤镜名称")) }, modifier = Modifier.fillMaxWidth(), singleLine = true)
                    TextButton(onClick = { onAction(CameraAction.SelectSamplePhoto) }) { Text(words("Change example photo", "更换实例照片")) }
                    previewError?.let { Text(words("Preview could not be rendered: ", "无法生成预览：") + it, color = MaterialTheme.colorScheme.error) }
                    if (draft.lutPath != null) {
                        EditSlider(words("LUT intensity", "LUT 强度"), draft.intensity, 0f..1f) { draft = draft.copy(intensity = it) }
                    } else {
                        val a = draft.adjustments
                        fun update(value: FilterAdjustments) { draft = draft.copy(adjustments = value) }
                        EditSlider(words("Exposure", "曝光"), a.exposure, -5f..5f) { update(a.copy(exposure = it)) }
                        EditSlider(words("Temperature", "色温"), a.temperature) { update(a.copy(temperature = it)) }
                        EditSlider(words("Tint", "色调"), a.tint) { update(a.copy(tint = it)) }
                        EditSlider(words("Saturation", "饱和度"), a.saturation) { update(a.copy(saturation = it)) }
                        EditSlider(words("Vibrance", "自然饱和度"), a.vibrance) { update(a.copy(vibrance = it)) }
                        EditSlider(words("Contrast", "对比度"), a.contrast) { update(a.copy(contrast = it)) }
                        EditSlider(words("Highlights", "高光"), a.highlights) { update(a.copy(highlights = it)) }
                        EditSlider(words("Shadows", "阴影"), a.shadows) { update(a.copy(shadows = it)) }
                        EditSlider(words("Sharpness", "锐化"), a.sharpness, 0f..1f) { update(a.copy(sharpness = it)) }
                        EditSlider(words("Blur", "模糊"), a.blur, 0f..1f) { update(a.copy(blur = it)) }
                        OutlinedButton(onClick = { draft = draft.copy(adjustments = FilterAdjustments()) }) { Text(words("Reset adjustments", "重置调整")) }
                    }
                }
            }
        }
    }
}

@Composable
private fun EditSlider(title: String, value: Float, range: ClosedFloatingPointRange<Float> = -1f..1f, onChange: (Float) -> Unit) {
    Column {
        Row { Text(title, Modifier.weight(1f)); Text(String.format(Locale.ROOT, "%+.2f", value), style = MaterialTheme.typography.labelMedium) }
        Slider(value.coerceIn(range), onValueChange = onChange, valueRange = range)
    }
}
