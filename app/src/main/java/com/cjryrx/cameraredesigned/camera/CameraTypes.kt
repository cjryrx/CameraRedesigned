package com.cjryrx.cameraredesigned.camera

import com.cjryrx.cameraredesigned.domain.CameraCapabilities
import com.cjryrx.cameraredesigned.domain.LensInfo
import com.cjryrx.cameraredesigned.domain.PhotoFormat
import java.io.File

data class EngineState(
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val lenses: List<LensInfo> = emptyList(),
    val lensId: String? = null,
    val ready: Boolean = false,
    val focusing: Boolean = false,
    val focused: Boolean = false,
    val exposureNanos: Long = 10_000_000L,
    val iso: Int = 100,
    val aperture: Float? = null,
    /** Latest preview HAL reports; these are not echoes of requested settings. */
    val aeMode: Int? = null,
    val flashMode: Int? = null,
    val flashState: Int? = null,
    val focusDiopters: Float = 0f,
    val previewWidth: Int = 4,
    val previewHeight: Int = 3,
    val sensorOrientation: Int = 90,
    val frontFacing: Boolean = false,
    val rotateAndCrop: Int? = null,
    val jpegOutputConfigured: Boolean = false,
    val rawOutputConfigured: Boolean = false,
    /** Burst ownership includes sensor requests, image copy and atomic file commit. */
    val burstInFlight: Int = 0,
    val burstCapacity: Int = 0,
    val burstPeakInFlight: Int = 0,
    val error: String? = null,
)

/** orientation is the sensor-to-JPEG clockwise rotation in degrees (0/90/180/270). */
data class CaptureSpec(
    val format: PhotoFormat,
    val jpegQuality: Int = 95,
    val orientation: Int = 0,
    val shutterNanos: Long? = null,
    val iso: Int? = null,
    val focusDiopters: Float? = null,
    val compensationEv: Float? = null,
    val requireJpeg: Boolean = false,
)

data class CaptureMetadata(
    val timestamp: Long,
    val orientation: Int,
    val exposureNanos: Long,
    val iso: Int,
    val focusDiopters: Float,
    val aperture: Float,
    val rotateAndCrop: Int? = null,
    val compensationEv: Float = 0f,
    val aeMode: Int? = null,
    val awbMode: Int? = null,
    val whiteBalanceGains: List<Float>? = null,
    val pipelineDepth: Int? = null,
    /** Actual HAL-returned ISP modes, for device diagnostics (not inferred request settings). */
    val processingModes: Map<String, Int> = emptyMap(),
    val flashMode: Int? = null,
    val flashState: Int? = null,
    val aeState: Int? = null,
)

/** Caller owns rawFile and must publish or delete it. JPEG is exactly one complete camera JPEG. */
data class CapturedFrame(val jpeg: ByteArray?, val rawFile: File?, val metadata: CaptureMetadata)

class CameraFailure(message: String, cause: Throwable? = null) : Exception(message, cause)
