package com.cjryrx.cameraredesigned.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import androidx.exifinterface.media.ExifInterface
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.domain.FilterSpec
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import java.io.File
import java.security.MessageDigest
import kotlin.math.min
import kotlin.math.abs
import kotlin.math.sin
import kotlin.math.cos
import kotlin.math.roundToInt

class ImageProcessorDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(CameraProbeActivity::class.java)

    private lateinit var directory: File
    private val processor = ImageProcessor()

    @Before fun createDirectory() {
        directory = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "imaging-test-${System.nanoTime()}")
        check(directory.mkdir())
    }

    @After fun cleanup() { directory.deleteRecursively() }

    @Test fun identityPreviewRetainsEveryPixelAndDoesNotMutateInput() = runBlocking {
        val source = pattern()
        val before = pixels(source)
        val result = processor.preview(source, FilterSpec("identity", "Identity"))
        try {
            assertArrayEquals(before, pixels(result))
            assertArrayEquals(before, pixels(source))
            assertNotSame(source, result)
        } finally { source.recycle(); result.recycle() }
    }

    @Test fun compatibleCubeProducesIdentityPreview() = runBlocking {
        val lut = File(directory, "identity.cube").apply {
            writeText(buildString {
                append("LUT_3D_SIZE 2\n")
                for (b in 0..1) for (g in 0..1) for (r in 0..1) append("$r $g $b\n")
            })
        }
        val source = pattern()
        val result = processor.preview(source, FilterSpec("cube", "Identity LUT", lutPath = lut.path))
        try { assertArrayEquals(pixels(source), pixels(result)) }
        finally { source.recycle(); result.recycle() }
    }

    @Test fun fullResolutionProcessingPreservesExifAndSourceBytes() = runBlocking {
        val input = sourceFile("capture.jpg")
        val before = hash(input)
        ExifInterface(input).apply {
            setAttribute(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_ROTATE_90.toString())
            setAttribute(ExifInterface.TAG_DATETIME_ORIGINAL, "2026:11:18 20:00:00")
            setAttribute(ExifInterface.TAG_EXPOSURE_TIME, "1/100")
            setAttribute(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, "200")
            saveAttributes()
        }
        val withMetadata = hash(input)
        assertFalse(before.contentEquals(withMetadata))
        val output = File(directory, "processed.jpg")
        processor.process(input, output, null, 95)
        assertArrayEquals(withMetadata, hash(input))
        assertImage(output)
        val exif = ExifInterface(output)
        assertEquals(ExifInterface.ORIENTATION_ROTATE_90, exif.getAttributeInt(ExifInterface.TAG_ORIENTATION, 1))
        assertEquals("2026:11:18 20:00:00", exif.getAttribute(ExifInterface.TAG_DATETIME_ORIGINAL))
        assertEquals(200, exif.getAttributeInt(ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY, 0))
        assertEquals(0.01, exif.getAttributeDouble(ExifInterface.TAG_EXPOSURE_TIME, 0.0), 0.00001)
    }

    @Test fun exposureFusionProducesFullResolutionImageAndKeepsSources() = runBlocking {
        val inputs = listOf(sourceFile("dark.jpg", 0.65f), sourceFile("middle.jpg"), sourceFile("bright.jpg", 1.35f))
        val hashes = inputs.map(::hash)
        val output = File(directory, "exposure.jpg")
        processor.mergeExposure(inputs, output, 95)
        assertImage(output)
        inputs.forEachIndexed { index, file -> assertArrayEquals(hashes[index], hash(file)) }
        assertTrue(ExifInterface(output).getAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION)!!.contains("Exposure fusion"))
    }

    @Test fun focusFusionProducesFullResolutionImageAndKeepsSources() = runBlocking {
        val first = sourceFile("focus-near.jpg", defocusLeft = false)
        val second = sourceFile("focus-far.jpg", defocusLeft = true)
        val inputs = listOf(first, second)
        val hashes = inputs.map(::hash)
        val output = File(directory, "focus.jpg")
        processor.mergeFocus(inputs, output, 95)
        assertImage(output)
        inputs.forEachIndexed { index, file -> assertArrayEquals(hashes[index], hash(file)) }
        assertTrue("Left depth region must gain detail from the sharp source",
            detailEnergy(output, 16, 31, 20, 50) > detailEnergy(second, 16, 31, 20, 50) * 1.3)
        assertTrue("Right depth region must gain detail from the other sharp source",
            detailEnergy(output, 62, 79, 26, 43) > detailEnergy(first, 62, 79, 26, 43) * 1.3)
    }

    @Test fun fullyClippedBracketMemberDoesNotPreventPublishingUsefulFrames() = runBlocking {
        val reference = sourceFile("neutral.jpg")
        val white = File(directory, "clipped.jpg")
        val blank = Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888)
        try {
            blank.eraseColor(android.graphics.Color.WHITE)
            white.outputStream().use { check(blank.compress(Bitmap.CompressFormat.JPEG, 100, it)) }
        } finally { blank.recycle() }
        val output = File(directory, "recovered.jpg")
        processor.mergeExposure(listOf(reference,white),output,100,referenceIndex=0)
        val src = requireNotNull(BitmapFactory.decodeFile(reference.path))
        val dst = requireNotNull(BitmapFactory.decodeFile(output.path))
        try {
            assertEquals(src.width,dst.width)
            assertEquals(src.height,dst.height)
            val a = pixels(src); val b = pixels(dst)
            val error = a.indices.sumOf { abs((a[it] and 255) - (b[it] and 255)) }.toDouble() / a.size
            assertTrue("Clipped white frame changed the neutral exposure (mean absolute error $error)", error < 2)
        } finally { src.recycle(); dst.recycle() }
    }

    @Test fun invalidFilterPreservesSourceAndPreviouslyCompletedOutput() = runBlocking {
        val input = sourceFile("input.jpg")
        val output = sourceFile("existing-output.jpg", 0.7f)
        val sourceHash = hash(input)
        val outputHash = hash(output)
        val invalidLut = File(directory, "truncated.cube").apply { writeText("LUT_3D_SIZE 2\n0 0 0\n") }
        var rejected = false
        try {
            processor.process(input, output, FilterSpec("broken", "Broken LUT", lutPath = invalidLut.path), 95)
        } catch (_: IllegalArgumentException) { rejected = true }
        assertTrue(rejected)
        assertArrayEquals(sourceHash, hash(input))
        assertArrayEquals(outputHash, hash(output))
        assertTrue(directory.listFiles()!!.none { it.name.startsWith(".image-") })
    }

    @Test fun breathingAndHandRollRecoverDetailAtBothDepthsInTheReferenceFraming() = runBlocking {
        val width = 769; val height = 545
        val a = 1.047 * cos(.037); val b = 1.047 * sin(.037)
        val cx = (width - 1) * .5; val cy = (height - 1) * .5
        fun intensity(x: Double, y: Double, detail: Double): Int {
            val base = .48 + .13*sin(x*.039+y*.022) + .12*cos(y*.036-x*.025) +
                .08*sin(.051*x + 4*cos(.022*y)) + .04*cos(y*.063)
            val texture = .115*sin(x*.48+y*.16) + .05*cos(x*.31-y*.47)
            return ((base + detail*texture)*255).roundToInt().coerceIn(0,255)
        }
        fun image(name: String, near: Boolean): File {
            val data = IntArray(width * height) { i ->
                val u = i % width - cx - 5.3; val v = i / width - cy + 3.7
                val x = if (near) cx + (a*u+b*v)/(a*a+b*b) else (i%width).toDouble()
                val y = if (near) cy + (-b*u+a*v)/(a*a+b*b) else (i/width).toDouble()
                val luma = intensity(x,y, if ((x<cx) == near) 1.0 else .06)
                (255 shl 24) or (luma*65793)
            }
            val bitmap = Bitmap.createBitmap(data,width,height,Bitmap.Config.ARGB_8888)
            try {
                return File(directory,name).also { file ->
                    file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 98, it)) }
                }
            } finally { bitmap.recycle() }
        }
        val candidate = image("near-moving.jpg",true)
        val reference = image("far-reference.jpg",false)
        val output = File(directory, "registered-focus.jpg")
        processor.mergeFocus(listOf(candidate,reference),output,98)
        fun errors(file: File): Pair<Double,Double> {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
            try {
                assertEquals(width,bitmap.width); assertEquals(height,bitmap.height)
                val data = pixels(bitmap)
                var left = 0.0; var right = 0.0; var count = 0
                for (y in 36 until height-36 step 2) for (x in 40 until width/2-24 step 2) {
                    val other = width-1-x
                    left += abs((data[y*width+x] and 255) - intensity(x.toDouble(),y.toDouble(),1.0))
                    right += abs((data[y*width+other] and 255) - intensity(other.toDouble(),y.toDouble(),1.0))
                    count++
                }
                return left/count to right/count
            } finally { bitmap.recycle() }
        }
        val merged = errors(output)
        val original = errors(reference)
        assertTrue("Registered subject error $merged, original $original", merged.first < original.first*.55)
        assertTrue("Reference depth lost detail: $merged", merged.second < 4)
    }

    private fun sourceFile(name: String, gain: Float = 1f, defocusLeft: Boolean? = null): File {
        val bitmap = pattern(gain)
        try {
            if (defocusLeft != null) {
                val sharp = pixels(bitmap)
                val blurred = IntArray(sharp.size)
                ColorKernel.blur(sharp, WIDTH, HEIGHT, 2, blurred, IntArray(sharp.size))
                for (y in 0 until HEIGHT) for (x in 0 until WIDTH) if ((x < WIDTH / 2) == defocusLeft) sharp[y * WIDTH + x] = blurred[y * WIDTH + x]
                bitmap.setPixels(sharp, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
            }
            return File(directory, name).also { file -> file.outputStream().use { check(bitmap.compress(Bitmap.CompressFormat.JPEG, 100, it)) } }
        } finally { bitmap.recycle() }
    }

    private fun pattern(gain: Float = 1f): Bitmap {
        val data = IntArray(WIDTH * HEIGHT) { index ->
            val x = index % WIDTH; val y = index / WIDTH
            // Scene-scale boundaries plus fine surface detail. Pure near-Nyquist noise is not a
            // realistic registration target once one half is defocused and the other is sharp.
            val foreground = if (x in 12..35 && y in 14..58) 65 else 0
            val background = if (x in 58..83 && y in 22..48) 85 else 0
            val detail = if ((x / 3 + y / 3) % 2 == 0) 14 else -14
            val base = 45 + x / 3 + y / 4 + foreground + background + detail
            val value = min(250, (base * gain).toInt())
            (0xff shl 24) or (value shl 16) or (value shl 8) or value
        }
        return Bitmap.createBitmap(WIDTH, HEIGHT, Bitmap.Config.ARGB_8888).apply {
            setPixels(data, 0, WIDTH, 0, 0, WIDTH, HEIGHT)
        }
    }

    private fun pixels(bitmap: Bitmap): IntArray = IntArray(bitmap.width * bitmap.height).also {
        bitmap.getPixels(it, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    }

    private fun assertImage(file: File) {
        assertTrue(file.length() > 100)
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            assertEquals(WIDTH, bitmap.width)
            assertEquals(HEIGHT, bitmap.height)
            val values = pixels(bitmap)
            assertTrue(values.any { it and 0xffffff != 0 })
            assertTrue(values.any { it and 0xffffff != 0xffffff })
        } finally { bitmap.recycle() }
    }

    private fun hash(file: File): ByteArray = MessageDigest.getInstance("SHA-256").digest(file.readBytes())
    private fun detailEnergy(file: File, left: Int, right: Int, top: Int, bottom: Int): Double {
        val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path))
        try {
            val data = pixels(bitmap)
            var energy = 0.0
            for (y in top..bottom) for (x in left..right) {
                val i = y * bitmap.width + x
                val laplace = 4 * ColorKernel.gray(data[i]) - ColorKernel.gray(data[i - 1]) - ColorKernel.gray(data[i + 1]) -
                    ColorKernel.gray(data[i - bitmap.width]) - ColorKernel.gray(data[i + bitmap.width])
                energy += laplace * laplace
            }
            return energy / ((right - left + 1) * (bottom - top + 1))
        } finally { bitmap.recycle() }
    }
    companion object { private const val WIDTH = 96; private const val HEIGHT = 72 }
}
