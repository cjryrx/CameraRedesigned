package com.cjryrx.cameraredesigned.camera

import android.Manifest
import android.graphics.BitmapFactory
import android.graphics.ImageFormat
import android.hardware.camera2.CameraCharacteristics
import android.hardware.camera2.CameraManager
import android.hardware.camera2.CaptureRequest
import android.hardware.camera2.CaptureResult
import android.hardware.camera2.params.StreamConfigurationMap
import android.os.Build
import android.view.Surface
import androidx.exifinterface.media.ExifInterface
import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import androidx.test.rule.GrantPermissionRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import com.cjryrx.cameraredesigned.domain.CameraSettings
import com.cjryrx.cameraredesigned.domain.PhotoFormat
import com.cjryrx.cameraredesigned.domain.ExposureMode
import com.cjryrx.cameraredesigned.domain.ExposureSettings
import com.cjryrx.cameraredesigned.domain.FlashMode
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.withTimeout
import kotlinx.coroutines.withTimeoutOrNull
import kotlinx.coroutines.withContext
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.ByteArrayInputStream
import java.io.File
import java.util.UUID
import kotlin.math.abs
import kotlin.math.max

/** Uses the real sensor. Every captured byte stays inside this app; test DNG files are deleted. */
@RunWith(AndroidJUnit4::class)
class CameraDeviceTest {
    @get:Rule val cameraPermission: GrantPermissionRule = GrantPermissionRule.grant(Manifest.permission.CAMERA)

    @Test fun maximumResolutionCapabilitiesAreReported() = withProbe { _, _ ->
        // withProbe records public capability metadata; no capture or camera session is opened.
        report("PASS maximum-resolution metadata inventory")
    }

    @Test fun rawOnlyCaptureDoesNotConfigureAJpegSurface() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        if (!initial.capabilities.supportsRaw) {
            report("SKIP RAW-only: this lens does not advertise RAW")
            return@withProbe
        }
        activity.stopCamera()
        scenario.onActivity { it.start(initial.lensId, CameraSettings(format = PhotoFormat.RAW)) }
        val rawOnly = ready(activity.engine)
        assertTrue(rawOnly.rawOutputConfigured)
        assertFalse("RAW-only single capture must not allocate a JPEG surface", rawOnly.jpegOutputConfigured)
        val directory = InstrumentationRegistry.getInstrumentation().targetContext.cacheDir
        val destination = File(directory, "camera-destination-probe-${UUID.randomUUID()}.dng")
        try {
            val frame = activity.engine.capture(CaptureSpec(PhotoFormat.RAW), rawDestination = destination)
            assertNull(frame.jpeg)
            assertEquals(destination.canonicalFile, frame.rawFile?.canonicalFile)
            val structure = DngStructure.read(destination)
            assertEquals(1L, structure.version.first())
            assertTrue(structure.rasterBytes >= structure.width * structure.height)
            report("PASS RAW-only atomic destination: ${structure.width}x${structure.height}, bytes=${destination.length()}")
        } finally { destination.delete() }
    }

    @Test fun actualJpegCaptureRotationAndCameraReopen() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        report("DEVICE ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}\n$initial")
        assertTrue("A real lens must be listed", initial.lenses.isNotEmpty())
        val first = activity.engine.capture(CaptureSpec(PhotoFormat.JPEG, 95, 0))
        verifyJpeg(first, initial, 0)
        assertNull(first.rawFile)
        val second = activity.engine.capture(CaptureSpec(PhotoFormat.JPEG, 95, 0))
        verifyJpeg(second, initial, 0)
        assertTrue("Sequential captures must not reuse a stale image", second.metadata.timestamp > first.metadata.timestamp)
        activity.stopCamera()
        assertFalse(activity.engine.state.value.ready)

        // Rotation changes request metadata, not the selected full-resolution sensor output.
        scenario.onActivity { it.start(initial.lensId, rotation = Surface.ROTATION_90) }
        val reopened = ready(activity.engine)
        val rotated = activity.engine.capture(CaptureSpec(PhotoFormat.JPEG, 95, 90))
        verifyJpeg(rotated, reopened, 90)
        // UNKNOWN timestamp sources need not share an origin across separate camera opens.
        assertTrue("Each capture must have a valid sensor timestamp", rotated.metadata.timestamp > 0)
        assertEquals(initial.capabilities.photoWidth, reopened.capabilities.photoWidth)
        assertEquals(initial.capabilities.photoHeight, reopened.capabilities.photoHeight)
        report("PASS JPEG capture, orientation and stop/reopen")
    }

    @Test fun jpegBurstPreservesFullResolutionAndPairsEveryFrame() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        val baseline = activity.engine.capture(CaptureSpec(PhotoFormat.JPEG, 95, 90)).metadata
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "burst-probe-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val files = mutableListOf<File>()
        val results = mutableListOf<Pair<File, CaptureMetadata>>()
        var peakInFlight = 0
        var capacity = 0
        try {
            activity.engine.captureJpegBurst(CaptureSpec(PhotoFormat.JPEG, 95, 90), 0,
                prepare = { index ->
                    peakInFlight = maxOf(peakInFlight, activity.engine.state.value.burstInFlight)
                    capacity = maxOf(capacity, activity.engine.state.value.burstCapacity)
                    if (index < 5) File(directory, "$index.jpg").also { files += it } else null
                },
                completed = { file, result -> results += file to result.getOrThrow() })
            assertEquals(5, results.size)
            assertEquals(files, results.map { it.first })
            assertTrue(results.zipWithNext().all { (a, b) -> a.second.timestamp < b.second.timestamp })
            results.forEach { (file, metadata) ->
                verifyJpeg(CapturedFrame(file.readBytes(), null, metadata), initial, 90)
                assertTrue(file.length() > 4096)
            }
            val fps = (results.size - 1) * 1e9 / (results.last().second.timestamp - results.first().second.timestamp)
            report("PASS bounded JPEG burst: ${results.size} frames, ${initial.capabilities.photoWidth}x${initial.capabilities.photoHeight}, " +
                "sensorFps=$fps, capacity=$capacity, peakInFlight=${activity.engine.state.value.burstPeakInFlight}, " +
                "observedInFlight=$peakInFlight, resultPipelineDepth=${results.map { it.second.pipelineDepth }}, " +
                "processingModes=${results.map { it.second.processingModes }.distinct()}, " +
                "singlePipelineDepth=${baseline.pipelineDepth}, singleProcessingModes=${baseline.processingModes}")
        } finally { files.forEach { it.delete() }; directory.delete() }
    }

    @Test fun jpegBurstReleaseDrainsAcceptedFramesAndQuickReleaseKeepsFirstShot() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        ready(activity.engine)
        val directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "burst-release-probe-${UUID.randomUUID()}")
        assertTrue(directory.mkdir())
        val files = mutableListOf<File>()
        val completed = mutableListOf<File>()
        var saved = 0
        try {
            activity.engine.captureJpegBurst(CaptureSpec(PhotoFormat.JPEG), 0,
                prepare = { index -> File(directory, "$index.jpg").also { files += it } },
                completed = { file, result ->
                    completed += file
                    if (result.isSuccess) { assertTrue(file.length() > 4096); saved++ }
                    else assertTrue(result.exceptionOrNull() is kotlinx.coroutines.CancellationException)
                    activity.engine.stopBurst()
                })
            assertEquals("Every prepared file has exactly one completion", files, completed)
            assertTrue("Release has at most three accepted requests to drain", saved in 1..3)
            assertTrue(files.size in 1..3)
            val priorFiles = files.size
            val priorSaved = saved
            activity.engine.captureJpegBurst(CaptureSpec(PhotoFormat.JPEG), 0,
                prepare = { index ->
                    assertEquals("Quick release cannot prepare a second shot", 0, index)
                    activity.engine.stopBurst()
                    File(directory, "quick.jpg").also { files += it }
                }, completed = { file, result ->
                    result.getOrThrow()
                    completed += file
                    assertTrue(file.length() > 4096)
                    saved++
                })
            assertEquals(priorFiles + 1, files.size)
            assertEquals(priorSaved + 1, saved)
            assertEquals(files, completed)
            report("PASS JPEG burst release: ${priorSaved} accepted frames drained; quick release saved exactly one")
        } finally { files.forEach { it.delete() }; directory.delete() }
    }

    @Test fun supportedRawAndJpegArePairedAndDngIsReadable() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val first = ready(activity.engine)
        if (!first.capabilities.supportsRaw) {
            report("SKIP RAW: ${first.lensId} does not advertise RAW capability")
            return@withProbe
        }
        activity.stopCamera()
        scenario.onActivity { it.start(first.lensId, CameraSettings(format = PhotoFormat.RAW_JPEG)) }
        val rawState = ready(activity.engine)
        val frame = activity.engine.capture(CaptureSpec(PhotoFormat.RAW_JPEG, 95, 90))
        var preserveEvidence = true
        try {
            verifyJpeg(frame, rawState, 90)
            val raw = requireNotNull(frame.rawFile) { "RAW+JPEG must return its DNG" }
            assertTrue("DNG cannot be an empty header", raw.length() > 4096)
            val exif = ExifInterface(raw)
            val structure = DngStructure.read(raw)
            report("DNG TIFF inspection=$structure; ExifInterface.DNGVersion=${exif.getAttribute(ExifInterface.TAG_DNG_VERSION)}; ExifInterface.orientation=${exif.getAttribute(ExifInterface.TAG_ORIENTATION)}")
            assertEquals("TIFF DNGVersion (50706) must have four bytes", 4, structure.version.size)
            assertEquals("DNG version must be version 1", 1L, structure.version.first())
            assertEquals(ExifInterface.ORIENTATION_ROTATE_90.toLong(), structure.orientation)
            assertTrue("DNG must carry sensor dimensions", structure.width > 0 && structure.height > 0)
            assertTrue("DNG sensor raster must be complete", structure.rasterBytes >= structure.width * structure.height)
            assertTrue("RAW_SENSOR DNG must describe its Bayer CFA", structure.cfaPattern.isNotEmpty())
            assertTrue("DNG must carry capture sensitivity", frame.metadata.iso > 0)
            assertTrue("DNG must carry exposure time", frame.metadata.exposureNanos > 0)
            report("PASS RAW+JPEG timestamp=${frame.metadata.timestamp}, DNG bytes=${raw.length()}, width=${exif.getAttribute(ExifInterface.TAG_IMAGE_WIDTH)}")
            // Preserve the parser disagreement as evidence even when TIFF structure is valid.
            preserveEvidence = exif.getAttribute(ExifInterface.TAG_DNG_VERSION) == null
        } finally {
            frame.rawFile?.let { raw ->
                if (preserveEvidence) {
                    val diagnostic = File(InstrumentationRegistry.getInstrumentation().targetContext.filesDir, "camera-probe-diagnostic.dng")
                    val copied = runCatching { raw.copyTo(diagnostic, overwrite = true) }.isSuccess
                    report("Preserved internal DNG diagnostic: ${if (copied) diagnostic.name else raw.absolutePath}")
                    if (copied) raw.delete()
                } else raw.delete()
            }
        }
    }

    @Test fun cameraSwitchUsesTheSelectedLensCapabilities() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        val alternative = initial.lenses.firstOrNull { it.frontFacing != initial.frontFacing }
            ?: initial.lenses.firstOrNull { it.id != initial.lensId }
        if (alternative == null) {
            report("SKIP lens switch: one public lens")
            return@withProbe
        }
        activity.stopCamera()
        scenario.onActivity { it.start(alternative.id) }
        val switched = ready(activity.engine)
        assertEquals(alternative.id, switched.lensId)
        assertEquals(alternative.id, switched.capabilities.lensId)
        assertEquals(alternative.frontFacing, switched.frontFacing)
        val frame = activity.engine.capture(CaptureSpec(PhotoFormat.JPEG, 90, 0))
        verifyJpeg(frame, switched, 0)
        report("PASS lens switch ${initial.lensId} -> ${alternative.id}: $switched")
    }

    @Test fun exposureModesAndBracketRequestsReachTheSensor() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        val cap = initial.capabilities
        val shutterRange = cap.shutterRange
        val isoRange = cap.isoRange
        val base = CaptureSpec(PhotoFormat.JPEG, 90)
        val supportedEv = minOf(1f, cap.compensationRange.endInclusive)
        if (cap.compensationStep > 0 && supportedEv > 0) {
            val evFrame = activity.engine.capture(base.copy(compensationEv = supportedEv))
            assertEquals("Hardware must acknowledge the requested EV", supportedEv, evFrame.metadata.compensationEv, cap.compensationStep / 2 + .001f)
            assertTrue("Auto exposure must remain enabled", evFrame.metadata.aeMode != CaptureRequest.CONTROL_AE_MODE_OFF)
            report("PASS AE compensation requested=$supportedEv actual=${evFrame.metadata.compensationEv}")
        }
        if (!cap.supportsManualSensor || shutterRange == null || isoRange == null) {
            report("SKIP manual exposure: lens does not advertise manual sensor control")
            return@withProbe
        }
        val requestedShutter = 10_000_000L.coerceIn(shutterRange)
        val requestedIso = (isoRange.first * 2).coerceIn(isoRange)
        activity.engine.update(CameraSettings(), ExposureSettings(mode = ExposureMode.M, shutterNanos = requestedShutter, iso = requestedIso))
        val manual = activity.engine.capture(base)
        assertManual(manual, requestedShutter, requestedIso)
        report("PASS M requested=$requestedShutter/$requestedIso actual=${manual.metadata}")

        // Neutral metering is supplied explicitly: this isolates accepted sensor controls from scene lighting.
        activity.engine.update(CameraSettings(), ExposureSettings(mode = ExposureMode.P, iso = requestedIso))
        activity.engine.submitMeteringLuma(.45f)
        val program = activity.engine.capture(base)
        assertEquals("P must retain selected ISO", requestedIso, program.metadata.iso)
        report("PASS P fixed ISO actual=${program.metadata}")

        activity.engine.update(CameraSettings(), ExposureSettings(mode = ExposureMode.S, shutterNanos = requestedShutter))
        activity.engine.submitMeteringLuma(.45f)
        val shutterPriority = activity.engine.capture(base)
        assertExposureClose(requestedShutter, shutterPriority.metadata.exposureNanos)
        report("PASS S fixed shutter actual=${shutterPriority.metadata}")

        if (cap.apertures.size > 1) {
            val aperture = cap.apertures.first()
            activity.engine.update(CameraSettings(), ExposureSettings(mode = ExposureMode.A, aperture = aperture, iso = requestedIso))
            activity.engine.submitMeteringLuma(.45f)
            val aperturePriority = activity.engine.capture(base)
            assertEquals("A must retain chosen aperture", aperture, aperturePriority.metadata.aperture, .01f)
            assertEquals(requestedIso, aperturePriority.metadata.iso)
            report("PASS A aperture actual=${aperturePriority.metadata}")
        } else report("SKIP A: fixed aperture ${cap.apertures}")

        activity.engine.update(CameraSettings(), ExposureSettings())
        val bracket = listOf(.5, 1.0, 2.0).map { (requestedShutter * it).toLong().coerceIn(shutterRange) }.distinct()
        val actual = bracket.map { shutter ->
            val frame = activity.engine.capture(base.copy(shutterNanos = shutter, iso = requestedIso))
            assertManual(frame, shutter, requestedIso)
            frame.metadata.exposureNanos
        }
        assertTrue("Bracket sensor exposures must be distinct and ordered", actual.zipWithNext().all { it.first < it.second })
        report("PASS exposure bracket requested=$bracket actual=$actual")
    }

    @Test fun flashOffTorchAndForcedCaptureReachTheHalAndReset() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        var initial = ready(activity.engine)
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        if (!initial.capabilities.hasFlash) {
            val flashLens = initial.lenses.firstOrNull {
                manager.getCameraCharacteristics(it.id)[CameraCharacteristics.FLASH_INFO_AVAILABLE] == true
            }
            if (flashLens != null) {
                activity.stopCamera()
                scenario.onActivity { it.start(flashLens.id) }
                initial = ready(activity.engine)
            }
        }
        val cap = initial.capabilities
        val c = manager.getCameraCharacteristics(requireNotNull(initial.lensId))
        val availableAe = c[CameraCharacteristics.CONTROL_AE_AVAILABLE_MODES] ?: intArrayOf()
        val off = CameraSettings(flash = FlashMode.OFF)
        val automatic = ExposureSettings()
        val spec = CaptureSpec(PhotoFormat.JPEG, 90)
        val timestamps = mutableListOf<Long>()

        suspend fun shot(label: String): CaptureMetadata {
            val frame = activity.engine.capture(spec)
            verifyJpeg(frame, initial, 0)
            timestamps += frame.metadata.timestamp
            report("FLASH $label lens=${initial.lensId} actual=${frame.metadata}")
            return frame.metadata
        }

        fun assertOff(label: String, metadata: CaptureMetadata) {
            assertEquals("$label must disable flash control", CaptureRequest.FLASH_MODE_OFF, metadata.flashMode)
            assertEquals("$label must leave automatic exposure without flash", CaptureRequest.CONTROL_AE_MODE_ON, metadata.aeMode)
            if (cap.hasFlash) {
                if (CaptureResult.FLASH_STATE in c.availableCaptureResultKeys) {
                    assertNotNull("$label must supply its advertised flash-state observation", metadata.flashState)
                }
                assertTrue("$label must not illuminate any part of the exposure: $metadata",
                    metadata.flashState != CaptureResult.FLASH_STATE_FIRED && metadata.flashState != CaptureResult.FLASH_STATE_PARTIAL)
            } else assertEquals(CaptureResult.FLASH_STATE_UNAVAILABLE, metadata.flashState)
        }

        try {
            activity.engine.update(off, automatic)
            assertOff("initial OFF", shot("OFF"))
            if (!cap.hasFlash) {
                report("SKIP TORCH/ON: no public flash-equipped lens; OFF and UNAVAILABLE verified")
                return@withProbe
            }

            activity.engine.update(off.copy(flash = FlashMode.TORCH), automatic)
            awaitFlashPreview(activity.engine, "TORCH", CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.FLASH_MODE_TORCH, fired = true)
            val torch = shot("TORCH")
            assertEquals(CaptureRequest.CONTROL_AE_MODE_ON, torch.aeMode)
            assertEquals(CaptureRequest.FLASH_MODE_TORCH, torch.flashMode)
            assertEquals("Torch must actually illuminate the captured exposure", CaptureResult.FLASH_STATE_FIRED, torch.flashState)

            activity.engine.update(off, automatic)
            awaitFlashPreview(activity.engine, "OFF after TORCH", CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.FLASH_MODE_OFF, fired = false)
            assertOff("OFF after TORCH", shot("OFF after TORCH"))

            if (CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH in availableAe) {
                activity.engine.update(off.copy(flash = FlashMode.ON), automatic)
                awaitFlashPreview(activity.engine, "ON", CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH, flashMode = null)
                val forced = shot("ON automatic exposure")
                assertEquals(CaptureRequest.CONTROL_AE_MODE_ON_ALWAYS_FLASH, forced.aeMode)
                // AE's ALWAYS_FLASH overrides FLASH_MODE; FIRED is the evidence of physical use.
                assertEquals("Forced flash must actually fire during the still exposure", CaptureResult.FLASH_STATE_FIRED, forced.flashState)
            } else report("SKIP AE forced flash: ALWAYS_FLASH is absent from ${availableAe.contentToString()}")

            val shutterRange = cap.shutterRange
            val isoRange = cap.isoRange
            if (cap.supportsManualSensor && shutterRange != null && isoRange != null) {
                val shutter = 10_000_000L.coerceIn(shutterRange)
                val iso = isoRange.first
                activity.engine.update(off.copy(flash = FlashMode.ON), ExposureSettings(mode = ExposureMode.M, shutterNanos = shutter, iso = iso))
                val manual = shot("ON manual exposure")
                assertEquals(CaptureRequest.CONTROL_AE_MODE_OFF, manual.aeMode)
                assertEquals(CaptureRequest.FLASH_MODE_SINGLE, manual.flashMode)
                assertEquals("Manual exposure must still fire the physical flash", CaptureResult.FLASH_STATE_FIRED, manual.flashState)
                assertExposureClose(shutter, manual.exposureNanos)
                assertEquals(iso, manual.iso)
            } else report("SKIP manual single flash: manual sensor control unavailable")

            activity.engine.update(off, automatic)
            awaitFlashPreview(activity.engine, "final OFF", CaptureRequest.CONTROL_AE_MODE_ON, CaptureRequest.FLASH_MODE_OFF, fired = false)
            assertOff("final OFF", shot("final OFF"))
            assertTrue("Flash changes must capture fresh sensor frames", timestamps.zipWithNext().all { it.first < it.second })
            report("PASS physical flash OFF/TORCH/supported ON paths and final OFF reset; ${timestamps.size} full-resolution internal JPEGs")
        } finally {
            activity.engine.update(off, automatic)
            // Failure must not leave the torch powered while the report is being collected. Probe
            // shutdown also closes the device, including when the HAL cannot acknowledge OFF.
            withContext(NonCancellable) {
                withTimeoutOrNull(3_000) {
                    activity.engine.state.first { !it.ready || it.flashMode == CaptureRequest.FLASH_MODE_OFF &&
                        it.aeMode == CaptureRequest.CONTROL_AE_MODE_ON && it.flashState != CaptureResult.FLASH_STATE_FIRED }
                }
            }
        }
    }

    private suspend fun awaitFlashPreview(engine: CameraEngine, label: String, aeMode: Int, flashMode: Int?, fired: Boolean? = null) {
        val observed = withTimeoutOrNull(8_000) {
            engine.state.first { it.ready && it.aeMode == aeMode && (flashMode == null || it.flashMode == flashMode) && when (fired) {
                true -> it.flashState == CaptureResult.FLASH_STATE_FIRED
                false -> it.flashState != CaptureResult.FLASH_STATE_FIRED && it.flashState != CaptureResult.FLASH_STATE_PARTIAL
                null -> true
            } }
        }
        assertNotNull("Preview $label was not physically acknowledged; last=${engine.state.value}", observed)
        report("FLASH preview $label actual=$observed")
    }

    @Test fun focusBracketAndManualWhiteBalanceReachTheSensor() = withProbe { scenario, activity ->
        scenario.onActivity { it.start() }
        val initial = ready(activity.engine)
        val cap = initial.capabilities
        val base = CaptureSpec(PhotoFormat.JPEG, 90)
        if (cap.minFocusDiopters > 0) {
            val distances = listOf(0f, cap.minFocusDiopters * .4f)
            val actualDistances = distances.map { target ->
                val frame = activity.engine.capture(base.copy(focusDiopters = target))
                if (cap.calibratedFocus) assertEquals("Calibrated focus must match requested distance", target, frame.metadata.focusDiopters, max(.02f, target * .02f))
                report("PASS focus bracket target=$target actual=${frame.metadata.focusDiopters}, calibrated=${cap.calibratedFocus}")
                frame.metadata.focusDiopters
            }
            assertTrue("Different bracket targets must produce different physical lens reports", abs(actualDistances[0] - actualDistances[1]) > .001f)
        } else report("SKIP focus bracket: fixed-focus camera")
        if (cap.supportsManualWhiteBalance) {
            activity.engine.update(CameraSettings(manualWhiteBalanceEnabled = true), ExposureSettings(whiteBalanceKelvin = 4200))
            // Allow the vendor's initial auto CCM to be observed before overriding white balance.
            delay(300)
            val frame = activity.engine.capture(base)
            assertEquals("Manual WB must disable AWB", CaptureRequest.CONTROL_AWB_MODE_OFF, frame.metadata.awbMode)
            val gains = requireNotNull(frame.metadata.whiteBalanceGains)
            assertEquals(4, gains.size)
            assertTrue("Sensor must report finite positive WB gains", gains.all { it.isFinite() && it > 0 })
            assertTrue("4200K control must produce non-neutral channel gains", gains.max() - gains.min() > .05f)
            report("PASS manual WB 4200K actual gains=$gains")
        } else report("SKIP manual WB: unavailable")
    }

    private fun assertManual(frame: CapturedFrame, shutter: Long, iso: Int) {
        assertNotNull(frame.jpeg)
        assertEquals("Manual exposure must disable AE", CaptureRequest.CONTROL_AE_MODE_OFF, frame.metadata.aeMode)
        assertEquals("Selected ISO must be accepted", iso, frame.metadata.iso)
        assertExposureClose(shutter, frame.metadata.exposureNanos)
    }

    private fun assertExposureClose(expected: Long, actual: Long) {
        // Exposure is quantized to sensor line time. Permit at most 1% or 100us.
        val tolerance = max(100_000L, expected / 100)
        assertTrue("Requested shutter=$expected ns, actual=$actual ns, tolerance=$tolerance", abs(expected - actual) <= tolerance)
    }

    private fun verifyJpeg(frame: CapturedFrame, state: EngineState, requestedOrientation: Int) {
        val bytes = requireNotNull(frame.jpeg) { "JPEG capture returned no image" }
        assertTrue("JPEG must contain a complete image", bytes.size > 512)
        assertEquals(0xff, bytes[0].toInt() and 255)
        assertEquals(0xd8, bytes[1].toInt() and 255)
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeByteArray(bytes, 0, bytes.size, options)
        val decoded = BitmapFactory.decodeByteArray(bytes, 0, bytes.size,
            BitmapFactory.Options().apply { inSampleSize = 32 })
        assertNotNull("Camera JPEG must decode to pixels", decoded)
        decoded?.recycle()
        val expected = state.capabilities
        val normal = options.outWidth == expected.photoWidth && options.outHeight == expected.photoHeight
        val rotated = options.outWidth == expected.photoHeight && options.outHeight == expected.photoWidth
        assertTrue("Full resolution must be preserved: ${options.outWidth}x${options.outHeight}, expected ${expected.photoWidth}x${expected.photoHeight}", normal || rotated)
        val exif = ExifInterface(ByteArrayInputStream(bytes))
        val actualRotation = exif.rotationDegrees
        // Camera2 permits rotating pixels or encoding EXIF orientation. Validate either representation.
        if (requestedOrientation % 180 != 0 && expected.photoWidth != expected.photoHeight) {
            assertTrue("JPEG must encode requested quarter-turn", (normal && actualRotation == requestedOrientation) || (rotated && actualRotation == 0))
        } else assertEquals(requestedOrientation, actualRotation)
        assertTrue(frame.metadata.timestamp > 0)
        assertEquals(requestedOrientation, frame.metadata.orientation)
    }

    private suspend fun ready(engine: CameraEngine): EngineState = withTimeout(20_000) {
        engine.state.first { it.ready || it.error != null }.also {
            assertNull("Camera reported ${it.error}", it.error)
            assertTrue(it.ready)
        }
    }

    private fun withProbe(test: suspend (ActivityScenario<CameraProbeActivity>, CameraProbeActivity) -> Unit) {
        ActivityScenario.launch(CameraProbeActivity::class.java).use { scenario ->
            lateinit var activity: CameraProbeActivity
            scenario.onActivity { activity = it }
            runBlocking {
                check(withTimeoutOrNull(20_000) { activity.foreground.first { it } } == true) {
                    "Camera test requires an unlocked device with the probe in the foreground; secure keyguard was not bypassed"
                }
                withTimeout(10_000) { activity.surfaceReady.await() }
                reportCapabilities()
                try { test(scenario, activity) }
                finally { activity.stopCamera() }
            }
        }
    }

    private fun report(text: String) {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        File(context.filesDir, "camera-probe-report.txt").appendText("${System.currentTimeMillis()} $text\n\n")
    }

    private fun reportCapabilities() {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        val manager = context.getSystemService(CameraManager::class.java)
        val lines = manager.cameraIdList.map { id ->
            val c = manager.getCameraCharacteristics(id)
            val normal = c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP]
            val maximum = if (Build.VERSION.SDK_INT >= 31) c[CameraCharacteristics.SCALER_STREAM_CONFIGURATION_MAP_MAXIMUM_RESOLUTION] else null
            val maximumArray = if (Build.VERSION.SDK_INT >= 31) c[CameraCharacteristics.SENSOR_INFO_PIXEL_ARRAY_SIZE_MAXIMUM_RESOLUTION] else null
            "lens=$id facing=${c[CameraCharacteristics.LENS_FACING]} level=${c[CameraCharacteristics.INFO_SUPPORTED_HARDWARE_LEVEL]} " +
                "capabilities=${c[CameraCharacteristics.REQUEST_AVAILABLE_CAPABILITIES]?.joinToString()} " +
                "shutter=${c[CameraCharacteristics.SENSOR_INFO_EXPOSURE_TIME_RANGE]} iso=${c[CameraCharacteristics.SENSOR_INFO_SENSITIVITY_RANGE]} " +
                "apertures=${c[CameraCharacteristics.LENS_INFO_AVAILABLE_APERTURES]?.joinToString()} " +
                "ev=${c[CameraCharacteristics.CONTROL_AE_COMPENSATION_RANGE]} step=${c[CameraCharacteristics.CONTROL_AE_COMPENSATION_STEP]} " +
                "focus=${c[CameraCharacteristics.LENS_INFO_MINIMUM_FOCUS_DISTANCE]} calibration=${c[CameraCharacteristics.LENS_INFO_FOCUS_DISTANCE_CALIBRATION]} " +
                "physicalIds=${c.physicalCameraIds} maxSensorArray=$maximumArray\n" +
                "DEFAULT_MAP ${streamSummary(normal)}\nMAXIMUM_MAP ${streamSummary(maximum)}"
        }
        report("PUBLIC CAPABILITIES ${Build.MANUFACTURER} ${Build.MODEL} API ${Build.VERSION.SDK_INT}\n${lines.joinToString("\n")}")
    }

    private fun streamSummary(map: StreamConfigurationMap?): String {
        if (map == null) return "unavailable"
        return listOf(ImageFormat.JPEG to "JPEG", ImageFormat.RAW_SENSOR to "RAW").joinToString("; ") { (format, label) ->
            val regular = map.getOutputSizes(format).orEmpty().toList()
            val high = map.getHighResolutionOutputSizes(format).orEmpty().toList()
            val largest = (regular + high).maxByOrNull { it.width.toLong() * it.height }
            "$label regular=$regular highResolution=$high largest=$largest " +
                "minFrameNs=${largest?.let { map.getOutputMinFrameDuration(format, it) }} " +
                "stallNs=${largest?.let { map.getOutputStallDuration(format, it) }}"
        }
    }
}
