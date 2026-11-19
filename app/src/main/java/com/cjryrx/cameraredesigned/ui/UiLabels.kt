package com.cjryrx.cameraredesigned.ui

import androidx.compose.runtime.Composable
import androidx.compose.ui.platform.LocalConfiguration
import com.cjryrx.cameraredesigned.domain.*
import java.util.Locale

@Composable
internal fun words(english: String, chinese: String): String =
    if (LocalConfiguration.current.locales[0].language == "zh") chinese else english

@Composable
internal fun label(value: Enum<*>): String = when (value) {
    ExposureMode.AUTO -> words("AUTO", "全自动")
    PhotoFormat.RAW_JPEG -> "RAW + JPEG"
    PhotoFormat.RAW -> "RAW · DNG"
    ShootingMode.SINGLE -> words("Single", "单张")
    ShootingMode.BURST -> words("Burst", "连拍")
    ShootingMode.EXPOSURE_BRACKET -> words("Exposure bracket", "包围曝光")
    ShootingMode.FOCUS_BRACKET -> words("Focus stack", "包围对焦")
    BurstRate.LOW -> words("Low · 2 fps", "低 · 2 张/秒")
    BurstRate.MEDIUM -> words("Medium · 5 fps", "中 · 5 张/秒")
    BurstRate.HIGH -> words("High · 10 fps", "高 · 10 张/秒")
    BurstRate.MAX -> words("Maximum", "最高")
    FocusArea.POINT -> words("Center point", "中央单点")
    FocusArea.CENTER -> words("Center area", "中央区域")
    FocusArea.WIDE -> words("Wide center", "中心广域")
    FocusArea.FULL -> words("Full frame", "全屏")
    TrackingMode.REGION -> words("Within region", "区域内追踪")
    TrackingMode.CONTINUOUS -> words("Follow subject", "持续追踪")
    TrackingMode.FULL_SCREEN -> words("Full-frame tracking", "全屏追踪")
    ShutterPriority.RELEASE -> words("Release priority", "快门优先")
    ShutterPriority.FOCUS -> words("Focus priority", "合焦优先")
    FlashMode.OFF -> words("Off", "关闭")
    FlashMode.AUTO -> words("Auto", "自动")
    FlashMode.ON -> words("On", "开启")
    FlashMode.TORCH -> words("Torch", "常亮")
    CustomFunction.NEXT_SUBJECT -> words("Next subject", "切换对焦主体")
    CustomFunction.SHOOTING_MODE -> words("Shooting mode", "切换拍摄模式")
    CustomFunction.FOCUS_AREA -> words("Focus area", "切换对焦区域")
    CustomFunction.FOCUS_MAGNIFIER -> words("Focus magnifier", "放大对焦点")
    CustomFunction.EXPOSURE_WARNING -> words("Exposure warning", "过曝/欠曝区域")
    CustomFunction.FOCUS_PEAKING -> words("Focus peaking", "合焦与边缘")
    BracketFocusMethod.RANGE -> words("Distance range", "对焦距离范围")
    BracketFocusMethod.POINTS -> words("Selected points", "指定对焦点")
    else -> value.name
}

internal fun shutterLabel(nanos: Long): String {
    val seconds = nanos / 1_000_000_000.0
    return if (seconds >= 1.0) String.format(Locale.ROOT, "%.1f″", seconds)
    else "1/${(1.0 / seconds).toInt().coerceAtLeast(1)}"
}

internal fun shutterStops(range: LongRange): List<Long> {
    val candidates = listOf(8000, 6400, 5000, 4000, 3200, 2500, 2000, 1600, 1250,
        1000, 800, 640, 500, 400, 320, 250, 200, 160, 125, 100, 80, 60, 50, 40,
        30, 25, 20, 15, 13, 10, 8, 6, 5, 4, 3, 2).map { 1_000_000_000L / it } +
        listOf(1L, 2, 4, 8, 15, 30, 60, 120).map { it * 1_000_000_000L }
    return (listOf(range.first) + candidates.filter { it in range } + range.last).distinct().sorted()
}

internal fun isoStops(range: IntRange): List<Int> =
    (listOf(range.first) + listOf(25, 32, 40, 50, 64, 80, 100, 125, 160, 200, 250, 320,
        400, 500, 640, 800, 1000, 1250, 1600, 2000, 2500, 3200, 4000, 5000, 6400,
        8000, 10000, 12800, 16000, 20000, 25600, 32000, 51200, 102400).filter { it in range } + range.last)
        .distinct().sorted()
