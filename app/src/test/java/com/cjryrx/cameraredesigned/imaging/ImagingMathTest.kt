package com.cjryrx.cameraredesigned.imaging

import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import org.junit.Assert.*
import org.junit.Test
import java.io.StringReader
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.random.Random

class ImagingMathTest {
    @Test fun defocusedFramesDoNotDiluteVisibleDetailEvenWhenAlignmentIsPerfect() {
        val width = 128; val height = 96
        fun frame(detail: Double) = IntArray(width * height) { i ->
            val x = (i % width).toDouble(); val y = (i / width).toDouble()
            val base = .48 + .13 * sin(x * .039 + y * .022) + .12 * cos(y * .036 - x * .025)
            val texture = .115 * sin(x * .48 + y * .16) + .05 * cos(x * .31 - y * .47)
            val value = (base + texture * detail).toFloat()
            ColorKernel.pack(value, value, value)
        }
        val sharp = frame(1.0)
        val defocused = frame(.06)
        val sharpWeight = FusionMath.focusWeights(sharp, width, height)
        val defocusedWeight = FusionMath.focusWeights(defocused, width, height)
        for (blurredFrameCount in listOf(1, 8)) {
            var error = 0.0; var count = 0
            for (y in 12 until height - 12) for (x in 12 until width - 12) {
                val i = y * width + x
                val total = sharpWeight[i] + defocusedWeight[i] * blurredFrameCount
                val expected = sharp[i] and 255
                val result = if (total < 1e-12f) expected.toFloat() else 255f * ColorKernel.srgb(
                    (ColorKernel.linear(expected / 255f) * sharpWeight[i] +
                        ColorKernel.linear((defocused[i] and 255) / 255f) * defocusedWeight[i] * blurredFrameCount) / total)
                error += abs(result - expected)
                count++
            }
            assertTrue("$blurredFrameCount defocused frame(s) diluted sharp detail by ${error / count} levels", error / count < 1.5)
        }
    }

    private fun identityCube(extra: String = ""): CubeLut = CubeLut.parse(StringReader(buildString {
        append("# identity\nTITLE \"Identity\"\nLUT_3D_SIZE 2\n$extra")
        for (b in 0..1) for (g in 0..1) for (r in 0..1) append("$r $g $b\n")
    }))

    @Test fun lutRedCoordinateChangesFastestAndInterpolatesAllAxes() {
        val lut = identityCube()
        val result = FloatArray(3)
        lut.sample(0.17f, 0.43f, 0.81f, result)
        assertArrayEquals(floatArrayOf(0.17f, 0.43f, 0.81f), result, 0.00001f)
        lut.sample(1f, 0f, 0f, result)
        assertArrayEquals(floatArrayOf(1f, 0f, 0f), result, 0f)
    }

    @Test fun lutHonorsPerChannelDomainAndClampsBeyondIt() {
        val lut = identityCube("DOMAIN_MIN -1 0 1\nDOMAIN_MAX 1 2 3\n")
        val result = FloatArray(3)
        lut.sample(0f, 1f, 2f, result)
        assertArrayEquals(floatArrayOf(0.5f, 0.5f, 0.5f), result, 0.00001f)
        lut.sample(-5f, 20f, 1f, result)
        assertArrayEquals(floatArrayOf(0f, 1f, 0f), result, 0f)
    }

    @Test fun lutRejectsTruncationNonFiniteAndUnboundedSize() {
        val invalid = listOf("LUT_3D_SIZE 2\n0 0 0", "LUT_3D_SIZE 99999", "LUT_3D_SIZE 2\nNaN 0 0", "LUT_1D_SIZE 2\n0 0 0")
        invalid.forEach { text ->
            assertThrows(IllegalArgumentException::class.java) { CubeLut.parse(StringReader(text)) }
        }
    }

    @Test fun defaultAndZeroStrengthPreserveAllChannelsExactly() {
        val sample = intArrayOf(0xff000000.toInt(), 0xffffffff.toInt(), 0x8013579b.toInt(), 0xfff01080.toInt())
        val result = sample.copyOf()
        ColorKernel().apply(result)
        assertArrayEquals(sample, result)
        ColorKernel(FilterAdjustments(exposure = 3f, saturation = -1f), intensity = 0f).apply(result)
        assertArrayEquals(sample, result)
    }

    @Test fun oneStopExposureDoublesLinearLightAndPreservesAlpha() {
        val result = ColorKernel(FilterAdjustments(exposure = 1f)).apply(0x7f404040)
        assertEquals(127, result ushr 24)
        val original = ColorKernel.linear(64f / 255)
        val transformed = ColorKernel.linear((result and 255) / 255f)
        assertEquals(original * 2, transformed, 0.003f)
    }

    @Test fun zeroSaturationProducesNeutralGray() {
        val result = ColorKernel(FilterAdjustments(saturation = -1f)).apply(0xffed2762.toInt())
        assertEquals(result and 255, result ushr 8 and 255)
        assertEquals(result and 255, result ushr 16 and 255)
    }

    @Test fun blurMatchesNaiveBoxAtCornersAndInterior() {
        val width = 9; val height = 7; val radius = 2
        val random = Random(4)
        val source = IntArray(width * height) { 0xff000000.toInt() or random.nextInt(0x1000000) }
        val actual = IntArray(source.size)
        ColorKernel.blur(source, width, height, radius, actual, IntArray(source.size))
        for (y in 0 until height) for (x in 0 until width) for (shift in intArrayOf(16, 8, 0)) {
            var sum = 0
            for (dy in -radius..radius) for (dx in -radius..radius) {
                sum += source[(y + dy).coerceIn(0, height - 1) * width + (x + dx).coerceIn(0, width - 1)] ushr shift and 255
            }
            val expected = sum / 25
            assertTrue(abs(expected - (actual[y * width + x] ushr shift and 255)) <= 1)
        }
    }

    @Test fun censusFindsTranslationDespiteExposureChange() {
        val width = 80; val height = 64
        val random = Random(123)
        val reference = FloatArray(width * height) { random.nextFloat() * 0.6f + 0.2f }
        val candidate = FloatArray(reference.size) { 0.5f }
        val dx = 4; val dy = -3
        for (y in 0 until height) for (x in 0 until width) {
            if (x + dx in 0 until width && y + dy in 0 until height)
                candidate[(y + dy) * width + x + dx] = reference[y * width + x] * 0.7f + 0.1f
        }
        val result = TranslationAligner.align(reference, candidate, width, height, 8)
        assertEquals(dx, result.x)
        assertEquals(dy, result.y)
        assertEquals(0f, result.error, 0.001f)
    }

    @Test fun flatSceneHasDeterministicZeroTranslation() {
        val result = TranslationAligner.align(FloatArray(1024) { 0.2f }, FloatArray(1024) { 0.6f }, 32, 32, 4)
        assertEquals(0, result.x); assertEquals(0, result.y)
        assertEquals(0, result.samples)
    }

    @Test fun focusAlignmentFindsMotionWhenOppositeDepthRegionsChangeSharpness() {
        val width = 96; val height = 72
        val scene = focusScene(width, height)
        val blur = FusionMath.smooth(scene, width, height, 2)
        val reference = FloatArray(scene.size) { i -> if (i % width < width / 2) scene[i] else blur[i] }
        val otherFocus = FloatArray(scene.size) { i -> if (i % width < width / 2) blur[i] else scene[i] }
        val moved = FloatArray(scene.size) { 0.3f }
        val dx = 3; val dy = -2
        for (y in 0 until height) for (x in 0 until width) if (x + dx in 0 until width && y + dy in 0 until height)
            moved[(y + dy) * width + x + dx] = otherFocus[y * width + x]
        val result = TranslationAligner.alignFocus(reference, moved, width, height, 8)
        assertEquals(dx, result.x)
        assertEquals(dy, result.y)
        assertTrue("Registration must retain the existing acceptance threshold: $result", result.error < 0.36f)
        assertTrue(result.samples >= 32)
    }

    @Test fun focusAlignmentDoesNotInventMotionFromDefocusAlone() {
        val width = 96; val height = 72
        val scene = focusScene(width, height)
        val blur = FusionMath.smooth(scene, width, height, 3)
        val result = TranslationAligner.alignFocus(scene, blur, width, height, 8)
        assertEquals(0, result.x)
        assertEquals(0, result.y)
        assertTrue(result.error < 0.36f)
    }

    @Test fun focusAlignmentRejectsAmbiguousHighFrequencyTexture() {
        val width = 64; val height = 64
        // Defocus destroys a one-pixel checker. No common scene structure survives to register.
        val checker = FloatArray(width * height) { i -> if ((i / width + i % width) % 2 == 0) 0.2f else 0.8f }
        val uniform = FloatArray(checker.size) { 0.5f }
        val result = TranslationAligner.alignFocus(checker, uniform, width, height, 6)
        assertTrue(result.error >= 0.36f)
    }

    private fun focusScene(width: Int, height: Int): FloatArray = FloatArray(width * height) { i ->
        val x = i % width; val y = i / width
        val foreground = if (x in 12..35 && y in 14..58) 65 else 0
        val background = if (x in 58..83 && y in 22..48) 85 else 0
        val detail = if ((x / 3 + y / 3) % 2 == 0) 14 else -14
        (45 + x / 3 + y / 4 + foreground + background + detail) / 255f
    }

    @Test fun clippedExposureCannotContributeToFusion() {
        assertEquals(0f, FusionMath.exposureWeight(0xffffffff.toInt()), 0f)
        assertEquals(0f, FusionMath.exposureWeight(0xff000000.toInt()), 0f)
        assertEquals(0f, FusionMath.exposureWeight(0xffff8000.toInt()), 0f)
        assertTrue(FusionMath.exposureWeight(0xff808080.toInt()) > FusionMath.exposureWeight(0xff202020.toInt()))
    }

    @Test fun sharpRegionReceivesHigherFocusWeightThanBlurredRegion() {
        val width = 32; val height = 32
        val sharp = IntArray(width * height) { i -> if ((i / width / 2 + i % width / 2) % 2 == 0) 0xff404040.toInt() else 0xffc0c0c0.toInt() }
        val blur = IntArray(sharp.size)
        ColorKernel.blur(sharp, width, height, 3, blur, IntArray(sharp.size))
        val a = FusionMath.focusWeights(sharp, width, height)
        val b = FusionMath.focusWeights(blur, width, height)
        assertTrue(a[16 * width + 16] > b[16 * width + 16] * 10)
    }
}
