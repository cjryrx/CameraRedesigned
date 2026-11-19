package com.cjryrx.cameraredesigned

import android.Manifest
import android.content.ClipData
import android.content.Intent
import android.content.pm.ActivityInfo
import android.content.pm.PackageManager
import android.hardware.display.DisplayManager
import androidx.core.net.toUri
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.Settings
import android.view.KeyEvent
import android.view.WindowManager
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.compose.foundation.layout.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.core.content.ContextCompat
import androidx.core.content.FileProvider
import androidx.core.view.WindowCompat
import androidx.lifecycle.Lifecycle
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import androidx.lifecycle.lifecycleScope
import androidx.lifecycle.repeatOnLifecycle
import com.cjryrx.cameraredesigned.data.copyLimited
import com.cjryrx.cameraredesigned.data.appJson
import com.cjryrx.cameraredesigned.data.FilterArchive
import com.cjryrx.cameraredesigned.domain.CameraAction
import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.ui.CameraApp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.io.File

class MainActivity : ComponentActivity() {
    private val model: CameraViewModel by viewModels()
    private val app get() = application as CameraApplication
    private var cameraPermission by mutableStateOf(false)
    private var volumeKey: Int? = null
    private val displayListener = object : DisplayManager.DisplayListener {
        override fun onDisplayAdded(displayId: Int) = Unit
        override fun onDisplayRemoved(displayId: Int) = Unit
        override fun onDisplayChanged(displayId: Int) {
            val current = window.decorView.display ?: return
            if (current.displayId == displayId) model.setDisplayRotation(current.rotation)
        }
    }
    private val permission = registerForActivityResult(ActivityResultContracts.RequestPermission()) { granted ->
        cameraPermission = granted
        model.setActive(granted && lifecycle.currentState.isAtLeast(Lifecycle.State.STARTED))
    }
    private val lutPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importFilter(it, lut = true) }
    }
    private val filterPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { model.importFilter(it, lut = false) }
    }
    private var pendingExport: FilterSpec? = null
    private val exportDocument = registerForActivityResult(ActivityResultContracts.CreateDocument(FilterArchive.MIME_TYPE)) { uri ->
        val filter = pendingExport
        pendingExport = null
        if (uri != null && filter != null) model.exportFilter(filter, uri)
    }
    private val modelPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { lifecycleScope.launch { model.installModel(it) } }
    }
    private val photoPicker = registerForActivityResult(ActivityResultContracts.OpenDocument()) { uri ->
        uri?.let { lifecycleScope.launch {
            try {
                val sample = withContext(Dispatchers.IO) {
                    val file = File.createTempFile("filter-sample-", ".image", cacheDir)
                    try {
                        checkNotNull(contentResolver.openInputStream(it)) { "Sample photo is unavailable" }
                            .use { input -> input.copyLimited(file, 64L * 1024 * 1024) }
                        file
                    } catch (e: Exception) {
                        file.delete()
                        throw e
                    }
                }
                model.setSample(sample.absolutePath)
            } catch (e: Exception) { model.report(e) }
        } }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        pendingExport = savedInstanceState?.getString("pendingFilterExport")?.let {
            appJson.decodeFromString<FilterSpec>(it)
        }
        enableEdgeToEdge(statusBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT),
            navigationBarStyle = SystemBarStyle.dark(android.graphics.Color.TRANSPARENT))
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        lifecycleScope.launch {
            repeatOnLifecycle(Lifecycle.State.STARTED) {
                model.externalActions.collect { action ->
                    when (action) {
                        CameraAction.ImportLut -> lutPicker.launch(arrayOf("*/*"))
                        CameraAction.ImportFilter -> filterPicker.launch(arrayOf("*/*"))
                        CameraAction.ImportModel -> modelPicker.launch(arrayOf("*/*"))
                        CameraAction.SelectSamplePhoto -> photoPicker.launch(arrayOf("image/*"))
                        CameraAction.DownloadModel -> lifecycleScope.launch { model.downloadModel() }
                        is CameraAction.ExportFilter -> {
                            val filter = model.state.value.filters.firstOrNull { it.id == action.id } ?: return@collect
                            pendingExport = filter
                            try { exportDocument.launch(FilterArchive.filename(filter)) }
                            catch (e: Exception) { pendingExport = null; model.report(e) }
                        }
                        is CameraAction.ShareFilter -> {
                            val filter = model.state.value.filters.firstOrNull { it.id == action.id } ?: return@collect
                            try {
                                val file = app.repository.prepareFilterShare(filter)
                                val uri = FileProvider.getUriForFile(this@MainActivity, "$packageName.files", file, FilterArchive.filename(filter))
                                startActivity(Intent.createChooser(Intent(Intent.ACTION_SEND).apply {
                                    type = "application/zip"
                                    putExtra(Intent.EXTRA_STREAM, uri)
                                    clipData = ClipData.newRawUri(filter.name, uri)
                                    addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION)
                                }, filter.name))
                            } catch (e: Exception) { model.report(e) }
                        }
                        is CameraAction.MenuVisible -> {
                            requestedOrientation = if (action.visible) ActivityInfo.SCREEN_ORIENTATION_PORTRAIT else ActivityInfo.SCREEN_ORIENTATION_FULL_SENSOR
                            val lightMenu = action.visible && resources.configuration.uiMode and android.content.res.Configuration.UI_MODE_NIGHT_MASK != android.content.res.Configuration.UI_MODE_NIGHT_YES
                            WindowCompat.getInsetsController(window, window.decorView).apply {
                                isAppearanceLightStatusBars = lightMenu
                                isAppearanceLightNavigationBars = lightMenu
                            }
                        }
                        else -> Unit
                    }
                }
            }
        }
        setContent {
            val state by model.state.collectAsStateWithLifecycle()
            if (cameraPermission) CameraApp(state, model::action,
                onPreviewAvailable = { texture, w, h ->
                    model.setDisplayRotation(window.decorView.display?.rotation ?: 0)
                    model.previewAvailable(texture, w, h)
                }, onPreviewDestroyed = model::previewDestroyed,
                onAnalysisFrame = model::analyze, onFirstPreviewFrame = ::reportFullyDrawn)
            else MaterialTheme(colorScheme = darkColorScheme()) {
                Surface(Modifier.fillMaxSize()) {
                    Column(Modifier.fillMaxSize().safeDrawingPadding().padding(32.dp), verticalArrangement = Arrangement.Center, horizontalAlignment = Alignment.CenterHorizontally) {
                        Text("Camera Redesigned", style = MaterialTheme.typography.headlineMedium)
                        Spacer(Modifier.height(24.dp))
                        val chinese = resources.configuration.locales[0].language == "zh"
                        Text(if (chinese) "允许使用相机以开始拍摄。照片保存在设备本地。" else "Allow camera access to start shooting. Photos stay on your device.")
                        Spacer(Modifier.height(16.dp))
                        Button(onClick = { permission.launch(Manifest.permission.CAMERA) }) { Text(if (chinese) "允许使用相机" else "Allow camera") }
                        TextButton(onClick = { startActivity(Intent(Settings.ACTION_APPLICATION_DETAILS_SETTINGS, "package:$packageName".toUri())) }) {
                            Text(if (chinese) "系统权限设置" else "App permission settings")
                        }
                    }
                }
            }
        }
        if (!cameraPermission && savedInstanceState == null) permission.launch(Manifest.permission.CAMERA)
    }
    override fun onSaveInstanceState(outState: Bundle) {
        pendingExport?.let { outState.putString("pendingFilterExport", appJson.encodeToString(it)) }
        super.onSaveInstanceState(outState)
    }
    override fun onResume() {
        super.onResume()
        cameraPermission = ContextCompat.checkSelfPermission(this, Manifest.permission.CAMERA) == PackageManager.PERMISSION_GRANTED
        getSystemService(DisplayManager::class.java).registerDisplayListener(displayListener, Handler(Looper.getMainLooper()))
        model.setDisplayRotation(window.decorView.display?.rotation ?: 0)
        model.setActive(cameraPermission)
    }
    override fun onPause() {
        volumeKey = null
        getSystemService(DisplayManager::class.java).unregisterDisplayListener(displayListener)
        model.setActive(false); super.onPause()
    }
    override fun onKeyDown(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey == keyCode) return true
        val volume = keyCode == KeyEvent.KEYCODE_VOLUME_UP || keyCode == KeyEvent.KEYCODE_VOLUME_DOWN
        val state = model.state.value
        if (volume && volumeKey == null && event.repeatCount == 0 && state.cameraReady && !state.shooting && state.settings.volumeShutter && cameraPermission) {
            volumeKey = keyCode
            model.action(CameraAction.PressShutter)
            model.action(CameraAction.DragShutter(true))
            return true
        }
        return super.onKeyDown(keyCode, event)
    }
    override fun onKeyUp(keyCode: Int, event: KeyEvent): Boolean {
        if (volumeKey == keyCode) {
            volumeKey = null
            model.action(CameraAction.ReleaseShutter); return true
        }
        return super.onKeyUp(keyCode, event)
    }
}
