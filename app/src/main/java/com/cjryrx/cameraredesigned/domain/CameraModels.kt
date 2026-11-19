package com.cjryrx.cameraredesigned.domain

import kotlinx.serialization.Serializable

enum class ExposureMode { AUTO, P, A, S, M }
enum class PhotoFormat { JPEG, RAW, RAW_JPEG }
enum class ShootingMode { SINGLE, BURST, EXPOSURE_BRACKET, FOCUS_BRACKET }
enum class BurstRate(val fps: Int) { LOW(2), MEDIUM(5), HIGH(10), MAX(0) }
enum class FocusArea { POINT, CENTER, WIDE, FULL }
enum class TrackingMode { REGION, CONTINUOUS, FULL_SCREEN }
enum class ShutterPriority { RELEASE, FOCUS }
enum class FlashMode { OFF, AUTO, ON, TORCH }
enum class CustomFunction { NEXT_SUBJECT, SHOOTING_MODE, FOCUS_AREA, FOCUS_MAGNIFIER, EXPOSURE_WARNING, FOCUS_PEAKING }
enum class BracketFocusMethod { RANGE, POINTS }

@Serializable
data class ExposureSettings(
    val mode: ExposureMode = ExposureMode.AUTO,
    val aperture: Float? = null,
    val shutterNanos: Long = 10_000_000L,
    val iso: Int? = null,
    val compensationEv: Float = 0f,
    val focusDiopters: Float? = null,
    val whiteBalanceKelvin: Int? = null,
)

@Serializable
data class CameraSettings(
    val format: PhotoFormat = PhotoFormat.JPEG,
    val jpegQuality: Int = 95,
    val shootingMode: ShootingMode = ShootingMode.SINGLE,
    val burstRate: BurstRate = BurstRate.MEDIUM,
    val priority: ShutterPriority = ShutterPriority.RELEASE,
    val focusArea: FocusArea = FocusArea.CENTER,
    val trackingMode: TrackingMode = TrackingMode.REGION,
    val showFocusBoxes: Boolean = true,
    val flash: FlashMode = FlashMode.OFF,
    val timerSeconds: Int = 0,
    val volumeShutter: Boolean = false,
    val manualFocusEnabled: Boolean = false,
    val manualWhiteBalanceEnabled: Boolean = false,
    val sliderSizeDp: Float = 64f,
    val sliderTravelDp: Float = 48f,
    val haptics: Boolean = true,
    val bracketCount: Int = 3,
    val bracketStepEv: Float = 1f,
    val keepBracketSources: Boolean = true,
    val focusMethod: BracketFocusMethod = BracketFocusMethod.RANGE,
    val focusNear: Float = 5f,
    val focusFar: Float = 0f,
    val focusStackCount: Int = 5,
    val realtimeFilter: Boolean = false,
    val activeFilterId: String? = null,
    val customFunction: CustomFunction = CustomFunction.FOCUS_AREA,
    val modelEnabled: Boolean = false,
)

data class LensInfo(val id: String, val label: String, val frontFacing: Boolean, val focalLengthMm: Float)

data class CameraCapabilities(
    val lensId: String = "",
    val apertures: List<Float> = emptyList(),
    val shutterRange: LongRange? = null,
    val isoRange: IntRange? = null,
    val compensationRange: ClosedFloatingPointRange<Float> = 0f..0f,
    val compensationStep: Float = 0f,
    val minFocusDiopters: Float = 0f,
    val calibratedFocus: Boolean = false,
    val supportsRaw: Boolean = false,
    val supportsManualSensor: Boolean = false,
    val supportsManualWhiteBalance: Boolean = false,
    val hasFlash: Boolean = false,
    val photoWidth: Int = 4,
    val photoHeight: Int = 3,
    val sensorOrientation: Int = 90,
)

data class SubjectBox(val id: Long, val label: String, val confidence: Float, val left: Float, val top: Float, val right: Float, val bottom: Float)
data class FocusPoint(val x: Float, val y: Float, val diopters: Float)

@Serializable
data class FilterAdjustments(
    val exposure: Float = 0f,
    val temperature: Float = 0f,
    val tint: Float = 0f,
    val saturation: Float = 0f,
    val vibrance: Float = 0f,
    val contrast: Float = 0f,
    val highlights: Float = 0f,
    val shadows: Float = 0f,
    val sharpness: Float = 0f,
    val blur: Float = 0f,
)
@Serializable
data class FilterSpec(
    val id: String,
    val name: String,
    val adjustments: FilterAdjustments = FilterAdjustments(),
    val lutPath: String? = null,
    val intensity: Float = 1f,
)

data class CameraUiState(
    val settings: CameraSettings = CameraSettings(),
    val exposure: ExposureSettings = ExposureSettings(),
    val measuredShutterNanos: Long? = null,
    val measuredIso: Int? = null,
    val measuredAperture: Float? = null,
    val capabilities: CameraCapabilities = CameraCapabilities(),
    val lenses: List<LensInfo> = emptyList(),
    val activeLensId: String? = null,
    val cameraReady: Boolean = false,
    val previewWidth: Int = 4,
    val previewHeight: Int = 3,
    val displayRotation: Int = 0,
    val focusing: Boolean = false,
    val focused: Boolean = false,
    val shooting: Boolean = false,
    val shotsTaken: Int = 0,
    val lastCaptureTimestampNanos: Long = 0,
    val processingCount: Int = 0,
    val failedSaveCount: Int = 0,
    val waitingForSave: Boolean = false,
    val countdown: Int = 0,
    val status: String = "",
    val error: String? = null,
    val filters: List<FilterSpec> = emptyList(),
    val subjects: List<SubjectBox> = emptyList(),
    val selectedSubjectId: Long? = null,
    val focusPoints: List<FocusPoint> = emptyList(),
    val focusX: Float = .5f,
    val focusY: Float = .5f,
    val modelStatus: String = "System AF",
    val magnifier: Boolean = false,
    val exposureWarning: Boolean = false,
    val focusPeaking: Boolean = false,
    val samplePhotoPath: String? = null,
)

sealed interface CameraAction {
    data class Settings(val value: CameraSettings) : CameraAction
    data class Exposure(val value: ExposureSettings) : CameraAction
    data class SelectLens(val id: String) : CameraAction
    data class FocusAt(val x: Float, val y: Float) : CameraAction
    data class AddFocusPoint(val x: Float, val y: Float) : CameraAction
    data object ClearFocusPoints : CameraAction
    data object PressShutter : CameraAction
    data class DragShutter(val armed: Boolean) : CameraAction
    data object ReleaseShutter : CameraAction
    data object CustomKey : CameraAction
    data object ImportLut : CameraAction
    data object ImportFilter : CameraAction
    data class ExportFilter(val id: String) : CameraAction
    data class ShareFilter(val id: String) : CameraAction
    data class SaveFilter(val value: FilterSpec) : CameraAction
    data class DeleteFilter(val id: String) : CameraAction
    data object ImportModel : CameraAction
    data object DownloadModel : CameraAction
    data object SelectSamplePhoto : CameraAction
    data object DismissError : CameraAction
    data object RetrySaving : CameraAction
    data class MenuVisible(val visible: Boolean) : CameraAction
}
