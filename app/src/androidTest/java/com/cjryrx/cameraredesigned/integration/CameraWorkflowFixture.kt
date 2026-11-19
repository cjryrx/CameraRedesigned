package com.cjryrx.cameraredesigned.integration

import android.Manifest
import android.app.KeyguardManager
import android.content.ContentUris
import android.content.res.Configuration
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.PowerManager
import android.os.SystemClock
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import android.view.InputDevice
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.ViewModelProvider
import androidx.test.core.app.ActivityScenario
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.CameraApplication
import com.cjryrx.cameraredesigned.CameraViewModel
import com.cjryrx.cameraredesigned.MainActivity
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import com.cjryrx.cameraredesigned.data.Preferences
import com.cjryrx.cameraredesigned.domain.BurstRate
import com.cjryrx.cameraredesigned.domain.CameraSettings
import com.cjryrx.cameraredesigned.domain.ShootingMode
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.TimeoutCancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import kotlin.math.abs

/** Runs the real activity and removes only media published by this test installation. */
abstract class CameraWorkflowFixture {
    protected val instrumentation = InstrumentationRegistry.getInstrumentation()
    protected val app get() = instrumentation.targetContext.applicationContext as CameraApplication
    private val images = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
    protected lateinit var scenario: ActivityScenario<MainActivity>
    protected lateinit var model: CameraViewModel
    private var baseline = emptySet<Uri>()

    protected fun withCamera(mode: ShootingMode, block: () -> Unit) {
        assumeTrue("Use -PisolatedDeviceTests=true for gallery-writing tests",
            app.packageName == "com.cjryrx.cameraredesigned.acceptance")
        instrumentation.uiAutomation.grantRuntimePermission(app.packageName, Manifest.permission.CAMERA)
        // A large journal from a previous interrupted run must recover while the process is visible.
        // The debug probe keeps the screen on and never opens a camera until explicitly requested.
        val original = ActivityScenario.launch(CameraProbeActivity::class.java).use { guard ->
            guard.onActivity { check(it.foreground.value) { "Recovery requires a foreground activity" } }
            stage("waiting for previous capture recovery") {
                app.processing.reserve()
                app.processing.releaseReservation()
                app.processing.state.first { it.count == 0 }
            }
            baseline = ownedPhotos()
            val saved = stage("reading original preferences") { app.repository.load() }
            stage("setting up capture mode") { app.repository.save(Preferences(CameraSettings(shootingMode = mode, burstRate = BurstRate.LOW))) }
            saved
        }
        var restored = false
        val restore = AutoCloseable {
            if (!restored) {
                stage("restoring original preferences") { app.repository.save(original) }
                restored = true
            }
        }
        restore.use {
            ActivityScenario.launch(MainActivity::class.java).use {
                scenario = it
                scenario.onActivity { activity -> model = ViewModelProvider(activity)[CameraViewModel::class.java] }
                // Keep MainActivity resumed until every asserted capture is finished and accounted for.
                // use() suppresses cleanup failures onto the real test failure instead of hiding it.
                val cleanup = AutoCloseable {
                    stage("draining capture and photo processing") {
                        model.state.first { !it.shooting }
                        app.processing.state.first { it.count == 0 }
                    }
                    newPhotos().forEach { uri -> assertEquals("Remove test photo $uri", 1, app.contentResolver.delete(uri, null, null)) }
                }
                restore.use {
                    cleanup.use {
                        waitFor("camera open") { model.state.value.cameraReady }
                        block()
                    }
                }
            }
        }
    }

    private fun <T> stage(name: String, operation: suspend () -> T): T = runBlocking {
        try { withTimeout(30_000) { operation() } }
        catch (timeout: TimeoutCancellationException) {
            throw AssertionError("Timed out $name; ${queueDiagnostic()}", timeout)
        }
    }

    private fun queueDiagnostic(): String {
        val files = app.processing.directory.listFiles().orEmpty().sortedBy { it.name }
            .take(16).joinToString { "${it.name}:${it.length()}" }
        return "queue=${app.processing.state.value}, work=[$files], " +
            "interactive=${app.getSystemService(PowerManager::class.java).isInteractive}, " +
            "locked=${app.getSystemService(KeyguardManager::class.java).isKeyguardLocked}"
    }

    protected fun orient(request: Int, orientation: Int) {
        scenario.onActivity { it.requestedOrientation = request }
        waitFor("requested capture orientation") {
            activityOrientation() == orientation && model.state.value.cameraReady
        }
    }

    protected fun activityOrientation(): Int {
        var result = Configuration.ORIENTATION_UNDEFINED
        scenario.onActivity { result = it.resources.configuration.orientation }
        return result
    }

    /** Checks successive producer frames and the actual uncropped TextureView viewport. */
    protected fun livePreview() {
        var first = 0L
        waitFor("live preview frames at native aspect") {
            var live = false
            scenario.onActivity { activity ->
                val texture = (activity.window.decorView as ViewGroup).descendants.filterIsInstance<TextureView>()
                    .firstOrNull { it.isAvailable && it.width > 0 && it.height > 0 } ?: return@onActivity
                val state = model.state.value
                val rotation = activity.window.decorView.display.rotation
                if (!state.cameraReady || state.displayRotation != rotation) return@onActivity
                val turn = (state.capabilities.sensorOrientation - rotation * 90 + 360) % 180 != 0
                val w = if (turn) state.previewHeight else state.previewWidth
                val h = if (turn) state.previewWidth else state.previewHeight
                val stamp = texture.surfaceTexture?.timestamp ?: 0L
                val rightAspect = abs(texture.width - texture.height * (w.toFloat() / h)) <= 3f
                live = rightAspect && first > 0 && stamp > first
                if (rightAspect && stamp > 0) first = stamp
            }
            live
        }
    }

    protected fun captureAndHold(): Uri {
        val before = newPhotos()
        holdShutter { drag ->
            drag(.45f)
            SystemClock.sleep(350)
            assertFalse("Half press must not start shooting", model.state.value.shooting)
            assertEquals("Below-threshold travel must not publish a photo", before, newPhotos())
            drag(1f)
            waitFor("first captured and published JPEG") {
                newPhotos().size > before.size && !model.state.value.shooting
            }
            // Cross the threshold again within the same physical touch.
            drag(.3f)
            drag(1f)
        }
        awaitIdle()
        return (newPhotos() - before).single()
    }

    protected fun holdShutter(block: ((Float) -> Unit) -> Unit) {
        val track = bounds("Shutter: hold to focus, drag down to capture", "快门：按住对焦，下拉拍摄")
        val density = app.resources.displayMetrics.density
        val settings = model.state.value.settings
        val startY = track.top + (8 + settings.sliderSizeDp / 2) * density
        val x = track.exactCenterX()
        val downTime = SystemClock.uptimeMillis()
        var y = startY
        try {
            // Some lenses report AF scan only briefly; subscribe before injecting DOWN.
            runBlocking {
                val scan = if (model.state.value.capabilities.minFocusDiopters > 0) {
                    async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                        withTimeout(5_000) { model.state.first { it.focusing } }
                    }
                } else null
                send(MotionEvent.ACTION_DOWN, downTime, x, y)
                scan?.await()
            }
            block { fraction ->
                y = startY + settings.sliderTravelDp * density * fraction
                send(MotionEvent.ACTION_MOVE, downTime, x, y)
            }
        } finally {
            send(MotionEvent.ACTION_UP, downTime, x, y)
        }
    }

    protected fun awaitIdle() = waitFor("capture and publication to drain") {
        !model.state.value.shooting && app.processing.state.value.count == 0
    }

    protected fun bounds(vararg labels: String): Rect {
        var found: Rect? = null
        waitFor(labels.joinToString(" / ")) { found = findBounds(*labels); found != null }
        return requireNotNull(found)
    }

    protected fun findBounds(vararg labels: String, diagnostic: MutableList<String>? = null): Rect? {
        val root = instrumentation.uiAutomation.rootInActiveWindow ?: return null
        diagnostic?.add("root=${root.packageName}/${root.className}")
        val nodes = ArrayDeque(listOf(root))
        while (nodes.isNotEmpty()) {
            val node = nodes.removeFirst()
            if (node.packageName?.toString() == app.packageName) {
                val description = node.contentDescription?.toString()
                val text = node.text?.toString()
                if (diagnostic != null && (description != null || text != null) && diagnostic.size < 60) {
                    diagnostic.add("${node.className}[$text,$description], visible=${node.isVisibleToUser}")
                }
                if (node.isVisibleToUser && labels.any { it == description || it == text }) {
                    val rect = Rect().also(node::getBoundsInScreen)
                    if (!rect.isEmpty) return rect
                }
            }
            for (index in 0 until node.childCount) node.getChild(index)?.let(nodes::addLast)
        }
        return null
    }

    protected fun tap(rect: Rect) {
        val down = SystemClock.uptimeMillis()
        send(MotionEvent.ACTION_DOWN, down, rect.exactCenterX(), rect.exactCenterY())
        send(MotionEvent.ACTION_UP, down, rect.exactCenterX(), rect.exactCenterY())
    }

    protected fun send(action: Int, down: Long, x: Float, y: Float) {
        val event = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, x, y, 0)
        try {
            event.source = InputDevice.SOURCE_TOUCHSCREEN
            instrumentation.sendPointerSync(event)
        } finally { event.recycle() }
    }

    protected fun waitFor(message: String, timeout: Long = 20_000, predicate: () -> Boolean) {
        val end = SystemClock.uptimeMillis() + timeout
        do {
            assertNull("Unexpected camera error while waiting for $message", model.state.value.error)
            if (predicate()) return
            SystemClock.sleep(40)
        } while (SystemClock.uptimeMillis() < end)
        val nodes = mutableListOf<String>()
        findBounds(diagnostic = nodes)
        fail("Timed out waiting for $message; ${queueDiagnostic()}; app accessibility nodes=$nodes; ${model.state.value}")
    }

    protected fun newPhotos() = ownedPhotos() - baseline

    private fun ownedPhotos(): Set<Uri> {
        val selection = "${MediaColumns.OWNER_PACKAGE_NAME}=? AND ${MediaColumns.RELATIVE_PATH}=? AND ${MediaColumns.IS_PENDING}=0"
        return requireNotNull(app.contentResolver.query(images, arrayOf(MediaColumns._ID), selection,
            arrayOf(app.packageName, "DCIM/CameraRedesigned/"), null)).use { rows ->
            buildSet { while (rows.moveToNext()) add(ContentUris.withAppendedId(images, rows.getLong(0))) }
        }
    }

    protected fun verifyJpeg(uri: Uri) {
        assertEquals("image/jpeg", app.contentResolver.getType(uri))
        val state = model.state.value
        val manager = app.getSystemService(CameraManager::class.java)
        val c = manager.getCameraCharacteristics(requireNotNull(state.activeLensId))
        val full = requireNotNull(c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
            .getOutputSizes(ImageFormat.JPEG).maxBy { it.width.toLong() * it.height }
        val front = c[CameraCharacteristics.LENS_FACING] == CameraCharacteristics.LENS_FACING_FRONT
        val angle = (requireNotNull(c[CameraCharacteristics.SENSOR_ORIENTATION]) +
            (if (front) 1 else -1) * state.displayRotation * 90 + 360) % 360
        val exifRotation = requireNotNull(app.contentResolver.openInputStream(uri)).use { ExifInterface(it).rotationDegrees }
        val options = BitmapFactory.Options().apply { inSampleSize = 16 }
        val bitmap = requireNotNull(app.contentResolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, options) }
        assertNotNull("Published JPEG must decode to pixels", bitmap)
        bitmap?.recycle()
        val bounds = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        requireNotNull(app.contentResolver.openInputStream(uri)).use { BitmapFactory.decodeStream(it, null, bounds) }
        val sensorRaster = bounds.outWidth == full.width && bounds.outHeight == full.height
        val rotatedRaster = bounds.outWidth == full.height && bounds.outHeight == full.width
        assertTrue("Full-resolution JPEG expected $full, got ${bounds.outWidth}x${bounds.outHeight}", sensorRaster || rotatedRaster)
        if (angle % 180 == 90 && rotatedRaster && !sensorRaster) assertEquals(0, exifRotation)
        else assertEquals("JPEG must carry the capture-time orientation", angle, exifRotation)
    }
}
