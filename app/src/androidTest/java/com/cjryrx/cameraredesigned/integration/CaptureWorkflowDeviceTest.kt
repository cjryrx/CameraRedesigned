package com.cjryrx.cameraredesigned.integration

import android.app.Instrumentation
import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.ImageFormat
import android.graphics.Rect
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.os.SystemClock
import android.util.Log
import android.util.Size
import android.view.KeyEvent
import android.view.MotionEvent
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.exifinterface.media.ExifInterface
import androidx.lifecycle.Lifecycle
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.camera.DngStructure
import com.cjryrx.cameraredesigned.domain.BurstRate
import com.cjryrx.cameraredesigned.domain.BracketFocusMethod
import com.cjryrx.cameraredesigned.domain.CameraAction
import com.cjryrx.cameraredesigned.domain.CameraSettings
import com.cjryrx.cameraredesigned.domain.PhotoFormat
import com.cjryrx.cameraredesigned.domain.ShootingMode
import com.cjryrx.cameraredesigned.domain.ShutterPriority
import kotlinx.coroutines.CoroutineStart
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.flow.dropWhile
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.drop
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.time.LocalDateTime
import java.time.ZoneOffset
import java.time.format.DateTimeFormatter
import java.util.Collections
import java.util.Locale
import kotlin.math.abs

/** Real touchscreen -> MainActivity -> full-resolution sensor capture -> public MediaStore bytes. */
@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30) // OWNER_PACKAGE_NAME keeps gallery cleanup scoped to the test install.
class CaptureWorkflowDeviceTest : CameraWorkflowFixture() {
    @Test fun singleShutterPublishesOnceAndPreviewResumesAfterMenuAndRotation() = withCamera(ShootingMode.SINGLE) {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        livePreview()
        val portrait = captureAndHold()
        verifyJpeg(portrait)

        tap(bounds("Menu", "菜单"))
        waitFor("portrait settings menu") {
            findBounds("CAMERA REDESIGNED") != null && !model.state.value.cameraReady &&
                activityOrientation() == Configuration.ORIENTATION_PORTRAIT
        }
        tap(bounds("‹ Camera", "‹ 拍摄"))
        waitFor("return from menu") { model.state.value.cameraReady }
        orient(ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE, Configuration.ORIENTATION_LANDSCAPE)
        livePreview()
        val landscape = captureAndHold()
        assertNotEquals("Both gestures must have a distinct published photo", portrait, landscape)
        verifyJpeg(landscape)
    }

    @Test fun heldBurstPublishesFullSizeJpegsAndStopsOnRelease() = withCamera(ShootingMode.BURST) {
        livePreview()
        val start = SystemClock.elapsedRealtime()
        holdShutter { drag ->
            drag(1f)
            waitFor("three completed burst captures", 30_000) { model.state.value.shotsTaken >= 3 }
        }
        awaitIdle()
        val captures = model.state.value.shotsTaken
        val saved = newPhotos()
        assertTrue(captures >= 3)
        assertEquals("Each burst frame must have exactly one published JPEG", captures, saved.size)
        Log.i("CaptureWorkflowTest", "Burst: ${saved.size} JPEGs captured and published in ${SystemClock.elapsedRealtime() - start} ms")
        saved.forEach(::verifyJpeg)
        // At 2 fps this covers two further deadlines, including scheduling jitter.
        SystemClock.sleep(1200)
        assertEquals("Finger release must stop acquisition", captures, model.state.value.shotsTaken)
        assertEquals("Finger release must stop publication", saved, newPhotos())
    }

    @Test fun rawExposureBracketPublishesEveryDngAndOneMergedJpeg() = withCamera(ShootingMode.EXPOSURE_BRACKET) {
        assumeTrue("Requires RAW on the active lens", model.state.value.capabilities.supportsRaw)
        runBlocking {
            val reopened = async(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                withTimeout(20_000) {
                    model.state.dropWhile { it.cameraReady && it.error == null }.first { it.cameraReady || it.error != null }
                }
            }
            scenario.onActivity { model.action(CameraAction.Settings(model.state.value.settings.copy(format = PhotoFormat.RAW))) }
            assertNull(reopened.await().error)
        }
        livePreview()
        holdShutter { drag ->
            drag(1f)
            waitFor("collecting RAW bracket") { model.state.value.shooting || model.state.value.shotsTaken > 0 }
        }
        waitFor("complete RAW bracket and merged JPEG", 180_000) {
            !model.state.value.shooting && app.processing.state.value.count == 0 && newPhotos().size >= 4
        }
        assertEquals(3, model.state.value.shotsTaken)
        val saved = newPhotos()
        assertEquals(4, saved.size)
        val merged = saved.single { app.contentResolver.getType(it) == "image/jpeg" }
        verifyJpeg(merged)
        val description = requireNotNull(app.contentResolver.openInputStream(merged)).use {
            ExifInterface(it).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)
        }
        assertEquals("Exposure fusion (3)", description)
        val scratch = File.createTempFile("bracket-check-", ".dng", app.cacheDir)
        try {
            for (uri in saved - merged) {
                requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
                    scratch.outputStream().use { output -> input.copyTo(output) }
                }
                val raw = DngStructure.read(scratch)
                assertEquals("Published source must have a TIFF DNGVersion", 1L, raw.version.first())
                assertTrue("Every DNG must contain the complete sensor raster", raw.rasterBytes >= raw.width * raw.height)
            }
        } finally { scratch.delete() }
    }

    @Test fun quickFocusPriorityVolumeShutterAndInterruptedTimer() = withCamera(ShootingMode.SINGLE) {
        livePreview()
        settings { it.copy(volumeShutter = true, priority = ShutterPriority.FOCUS) }
        volumeClick()
        waitFor("quick-release focus-priority photo") { newPhotos().size == 1 && !model.state.value.shooting }
        verifyJpeg(newPhotos().single())
        settings { it.copy(timerSeconds = 3) }
        volumeClick()
        waitFor("visible countdown") { model.state.value.countdown > 0 }
        scenario.moveToState(Lifecycle.State.CREATED)
        scenario.moveToState(Lifecycle.State.RESUMED)
        livePreview()
        SystemClock.sleep(3200)
        awaitIdle()
        assertEquals("A countdown from a previous foreground session must not take a photo", 1, newPhotos().size)
        assertEquals(0, model.state.value.countdown)

        // A fresh timed shot must still run to completion once after the interrupted one.
        volumeClick()
        waitFor("fresh countdown") { model.state.value.countdown > 0 }
        waitFor("new timed photo") { newPhotos().size == 2 && !model.state.value.shooting }
        assertEquals(1, model.state.value.shotsTaken)
    }

    /** A prepared near/far scene may additionally be required with -e requireDistinctFocusPoints true. */
    @Test fun recordsRealViewfinderPointsAndPublishesFullResolutionFocusStack() = withCamera(ShootingMode.FOCUS_BRACKET) {
        val maxFocus = model.state.value.capabilities.minFocusDiopters
        assumeTrue("Requires a focusable lens", maxFocus > 0f)
        livePreview()
        settings { it.copy(focusMethod = BracketFocusMethod.POINTS, keepBracketSources = true) }
        val preview = Rect()
        scenario.onActivity { activity ->
            val texture = (activity.window.decorView as ViewGroup).descendants.filterIsInstance<TextureView>().single()
            val origin = IntArray(2).also(texture::getLocationOnScreen)
            preview.set(origin[0], origin[1], origin[0] + texture.width, origin[1] + texture.height)
        }
        val args = InstrumentationRegistry.getArguments()
        val coordinates = listOf("near" to .3f, "far" to .7f)
        for ((index, target) in coordinates.withIndex()) {
            val x = args.getString("${target.first}X")?.toFloat() ?: target.second
            val y = args.getString("${target.first}Y")?.toFloat() ?: .5f
            require(x in 0.02f..0.98f && y in 0.02f..0.98f)
            val down = SystemClock.uptimeMillis()
            val screenX = preview.left + x * preview.width()
            val screenY = preview.top + y * preview.height()
            send(MotionEvent.ACTION_DOWN, down, screenX, screenY)
            send(MotionEvent.ACTION_UP, down, screenX, screenY)
            waitFor("AF distance for selected point ${index + 1}") { model.state.value.focusPoints.size == index + 1 }
            val point = model.state.value.focusPoints[index]
            assertEquals(x, point.x, .015f)
            assertEquals(y, point.y, .015f)
            assertTrue("Recorded distance outside lens travel: $point", point.diopters in 0f..maxFocus)
        }
        val distances = model.state.value.focusPoints.map { it.diopters }
        // A flat real scene may legitimately return the same focal plane for both taps.
        val multiplePlanes = abs(distances[0] - distances[1]) >= .02f
        if (args.getString("requireDistinctFocusPoints") == "true")
            assertTrue("Set near/far tap coordinates on distinct focal planes. Observed distances: $distances", multiplePlanes)
        if (!multiplePlanes) settings { it.copy(focusMethod = BracketFocusMethod.RANGE, focusFar = 0f,
            focusNear = minOf(2f, maxFocus), focusStackCount = 3) }
        val count = if (multiplePlanes) 2 else 3
        holdShutter { drag ->
            drag(1f)
            waitFor("collecting focus bracket") { model.state.value.shooting || model.state.value.shotsTaken > 0 }
        }
        waitFor("full-resolution focus fusion and all source photos", 180_000) {
            !model.state.value.shooting && app.processing.state.value.count == 0 && newPhotos().size >= count + 1
        }
        assertEquals(count, model.state.value.shotsTaken)
        val saved = newPhotos()
        assertEquals(count + 1, saved.size)
        val merged = saved.single { uri ->
            requireNotNull(app.contentResolver.openInputStream(uri)).use {
                ExifInterface(it).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION) == "Focus stack ($count)"
            }
        }
        saved.forEach(::verifyJpeg)
        report("Focus stack: pointsDiopters=$distances method=${model.state.value.settings.focusMethod} sources=$count merged=$merged")
    }

    @Test fun measuresEightSecondBurstAtEveryRate() {
        assumeTrue("Opt in with -e measureBurst true", InstrumentationRegistry.getArguments().getString("measureBurst") == "true")
        withCamera(ShootingMode.BURST) {
            livePreview()
            for (rate in BurstRate.entries) measureRate(rate)
        }
    }

    private fun measureRate(rate: BurstRate) {
        settings { it.copy(burstRate = rate) }
        val before = newPhotos()
        val thermal = app.getSystemService(PowerManager::class.java)
        val initialThermal = thermal.currentThermalStatus
        var peakThermal = initialThermal
        val timestamps = Collections.synchronizedList(mutableListOf<Long>())
        var heldMillis = 0L
        val started = SystemClock.elapsedRealtime()
        runBlocking {
            // This observes the actual completed Camera2 sensor timestamps without writing any per-frame test logs.
            val observer = launch(Dispatchers.Unconfined, start = CoroutineStart.UNDISPATCHED) {
                model.state.map { it.lastCaptureTimestampNanos }.distinctUntilChanged().drop(1).collect { if (it > 0) timestamps.add(it) }
            }
            try {
                holdShutter { drag ->
                    val armedAt = SystemClock.elapsedRealtime()
                    drag(1f)
                    while (SystemClock.elapsedRealtime() - armedAt < 8_000) {
                        peakThermal = maxOf(peakThermal, thermal.currentThermalStatus)
                        SystemClock.sleep(50)
                    }
                    heldMillis = SystemClock.elapsedRealtime() - armedAt
                }
                awaitIdle()
            } finally { observer.cancel() }
        }
        val drainMillis = SystemClock.elapsedRealtime() - started
        val finalThermal = thermal.currentThermalStatus
        val photos = newPhotos() - before
        assertTrue("A sustained burst should produce multiple frames", photos.size > 1)
        assertEquals("Lost/extra published frame at $rate", model.state.value.shotsTaken, photos.size)
        assertEquals("Lost/extra sensor timestamp at $rate", photos.size, timestamps.size)
        assertTrue("HAL timestamps must increase", timestamps.zipWithNext().all { (a, b) -> b > a })
        val sensorSpan = (timestamps.last() - timestamps.first()) / 1e6
        val exifTimes = photos.mapNotNull(::exifMillis)
        val exifSpan = if (exifTimes.size == photos.size) (exifTimes.max() - exifTimes.min()).toString() else "unavailable"
        val cap = model.state.value.capabilities
        val size = Size(cap.photoWidth, cap.photoHeight)
        val camera = app.getSystemService(CameraManager::class.java).getCameraCharacteristics(cap.lensId)
        val streams = requireNotNull(camera[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP])
        val stall = streams.getOutputStallDuration(ImageFormat.JPEG, size) / 1e6
        val minimum = streams.getOutputMinFrameDuration(ImageFormat.JPEG, size) / 1e6
        val fps = String.format(Locale.ROOT, "%.2f", (photos.size - 1) * 1000.0 / sensorSpan)
        photos.forEach(::verifyJpeg)
        SystemClock.sleep(1200)
        assertEquals("Release failed at $rate", photos, newPhotos() - before)
        assertEquals(photos.size, model.state.value.shotsTaken)
        report("Burst rate=$rate frames=${photos.size} size=${cap.photoWidth}x${cap.photoHeight} heldMs=$heldMillis " +
            "sensorSpanMs=${sensorSpan.toLong()} sensorFps=$fps exifSpanMs=$exifSpan gestureAndDrainMs=$drainMillis " +
            "reportedJpegStallMs=$stall reportedMinFrameMs=$minimum thermalStart=$initialThermal thermalPeak=$peakThermal thermalEnd=$finalThermal")
        photos.forEach { assertEquals(1, app.contentResolver.delete(it, null, null)) }
    }

    /** EXIF time may be quantized to whole seconds or omitted by the camera HAL. */
    private fun exifMillis(uri: Uri): Long? = requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
        val exif = ExifInterface(input)
        val date = exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL) ?: return@use null
        val local = runCatching { LocalDateTime.parse(date, DateTimeFormatter.ofPattern("yyyy:MM:dd HH:mm:ss", Locale.ROOT)) }
            .getOrNull() ?: return@use null
        val millis = exif.getAttribute(ExifInterface.TAG_SUBSEC_TIME_ORIGINAL).orEmpty().takeWhile(Char::isDigit)
            .take(3).padEnd(3, '0').toLong()
        local.toEpochSecond(ZoneOffset.UTC) * 1000 + millis
    }

    private fun settings(change: (CameraSettings) -> CameraSettings) = scenario.onActivity {
        model.action(CameraAction.Settings(change(model.state.value.settings)))
    }

    private fun volumeClick() = instrumentation.sendKeyDownUpSync(KeyEvent.KEYCODE_VOLUME_DOWN)

    private fun report(message: String) {
        Log.i("CaptureWorkflowTest", message)
        instrumentation.sendStatus(2, Bundle().apply { putString(Instrumentation.REPORT_KEY_STREAMRESULT, "\n$message\n") })
    }
}
