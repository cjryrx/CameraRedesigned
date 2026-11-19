package com.cjryrx.cameraredesigned.camera.probe

import android.app.Activity
import android.graphics.SurfaceTexture
import android.os.Bundle
import android.view.Surface
import android.view.TextureView
import android.view.WindowManager
import com.cjryrx.cameraredesigned.camera.CameraEngine
import com.cjryrx.cameraredesigned.domain.CameraSettings
import com.cjryrx.cameraredesigned.domain.ExposureSettings
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.asStateFlow

/** Foreground, debug-only camera harness. It never publishes photographs or modifies user settings. */
class CameraProbeActivity : Activity() {
    lateinit var engine: CameraEngine
        private set
    lateinit var viewfinder: TextureView
        private set
    val surfaceReady = CompletableDeferred<Unit>()
    private val cleanupScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var producer: SurfaceTexture? = null
    private var output: Surface? = null
    private val mutableForeground = MutableStateFlow(false)
    val foreground = mutableForeground.asStateFlow()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        // Wake a sleeping screen for this explicit test; never dismiss or draw over secure keyguard.
        setTurnScreenOn(true)
        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        engine = CameraEngine(applicationContext)
        viewfinder = TextureView(this).apply {
            surfaceTextureListener = object : TextureView.SurfaceTextureListener {
                override fun onSurfaceTextureAvailable(texture: SurfaceTexture, width: Int, height: Int) {
                    producer = texture
                    surfaceReady.complete(Unit)
                }
                override fun onSurfaceTextureSizeChanged(texture: SurfaceTexture, width: Int, height: Int) = Unit
                override fun onSurfaceTextureUpdated(texture: SurfaceTexture) = Unit
                override fun onSurfaceTextureDestroyed(texture: SurfaceTexture): Boolean {
                    // We own the producer until CameraDevice.onClosed acknowledges the shutdown.
                    val oldOutput = output
                    output = null
                    producer = null
                    cleanupScope.launch {
                        try { engine.stopAndAwait() }
                        finally { oldOutput?.release(); texture.release() }
                    }
                    return false
                }
            }
        }
        setContentView(viewfinder)
    }

    override fun onResume() { super.onResume(); mutableForeground.value = true }
    override fun onPause() { mutableForeground.value = false; engine.stop(); super.onPause() }

    /** Main-thread operation after surfaceReady and previous stopAndAwait have completed. */
    fun start(lensId: String? = null, settings: CameraSettings = CameraSettings(), rotation: Int = Surface.ROTATION_0) {
        check(foreground.value) { "Unlock the test device and keep the camera probe in the foreground" }
        check(output == null) { "Call stopCamera() before starting another probe session" }
        val texture = requireNotNull(producer) { "Probe preview is not available" }
        val size = engine.previewSize(lensId, viewfinder.width, viewfinder.height)
        texture.setDefaultBufferSize(size.width, size.height)
        output = Surface(texture)
        engine.update(settings, ExposureSettings())
        engine.start(requireNotNull(output), viewfinder.width, viewfinder.height, lensId, rotation)
    }

    /** Releases the output only after device closure; leaves TextureView producer available for restart. */
    suspend fun stopCamera() {
        engine.stopAndAwait()
        withContext(Dispatchers.Main.immediate) { output?.release(); output = null }
    }

    override fun onDestroy() {
        cleanupScope.launch {
            try { engine.stopAndAwait() }
            finally { engine.close() }
        }
        super.onDestroy()
    }
}
