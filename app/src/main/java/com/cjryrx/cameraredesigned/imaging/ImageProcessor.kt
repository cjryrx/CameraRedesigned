package com.cjryrx.cameraredesigned.imaging

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.BitmapRegionDecoder
import android.graphics.ColorSpace
import android.graphics.Matrix
import android.graphics.Rect
import androidx.core.graphics.createBitmap
import androidx.exifinterface.media.ExifInterface
import com.cjryrx.cameraredesigned.domain.FilterSpec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileOutputStream
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * Full-resolution sRGB JPEG processing. At most one output bitmap and bounded stripes are resident;
 * a group never decodes all photographs at once. Calls on an instance are serialized to bound memory.
 * Inputs remain owned by the caller. Output appears only after encoding and metadata succeed.
 */
class ImageProcessor {
    private val processing = Mutex()
    private data class CachedLut(val path: String, val modified: Long, val bytes: Long, val lut: CubeLut)
    @Volatile private var cachedLut: CachedLut? = null

    suspend fun process(input: File, output: File, filter: FilterSpec?, jpegQuality: Int) = processing.withLock {
        withContext(Dispatchers.Default) {
            validateOutput(listOf(input), output, jpegQuality)
            val image = decodeFull(input)
            try {
                applyFilter(image, filter)
                writeAtomic(image, input, output, jpegQuality)
            } finally { image.recycle() }
        }
    }

    suspend fun mergeExposure(inputs: List<File>, output: File, jpegQuality: Int, filter: FilterSpec? = null,
        referenceIndex: Int = inputs.size / 2) =
        processing.withLock { withContext(Dispatchers.Default) { merge(inputs, output, jpegQuality, filter, false, referenceIndex) } }

    suspend fun mergeFocus(inputs: List<File>, output: File, jpegQuality: Int, filter: FilterSpec? = null) =
        processing.withLock { withContext(Dispatchers.Default) { merge(inputs, output, jpegQuality, filter, true, inputs.size / 2) } }

    /** Caller owns both the input and returned bitmap. No mutation of the input. */
    suspend fun preview(input: Bitmap, filter: FilterSpec?): Bitmap = withContext(Dispatchers.Default) {
        val result = requireNotNull(input.copy(Bitmap.Config.ARGB_8888, true)) { "Cannot allocate filter preview" }
        try { applyFilter(result, filter); result } catch (error: Throwable) { result.recycle(); throw error }
    }

    /** Decode a bounded, EXIF-oriented sample for the editor. */
    suspend fun decodePreview(input: File, maxEdge: Int = 1280): Bitmap = withContext(Dispatchers.IO) {
        require(maxEdge in 64..4096)
        val size = imageBounds(input)
        var sample = 1
        while (max(size.first, size.second) / sample > maxEdge * 2) sample *= 2
        val decoded = requireNotNull(BitmapFactory.decodeFile(input.path, options().apply { inSampleSize = sample })) { "Cannot decode sample photo" }
        val scale = min(1f, maxEdge.toFloat() / max(decoded.width, decoded.height))
        val orientation = ExifInterface(input).getAttributeInt(ExifInterface.TAG_ORIENTATION, ExifInterface.ORIENTATION_NORMAL)
        val transform = Matrix().apply {
            when (orientation) {
                ExifInterface.ORIENTATION_FLIP_HORIZONTAL -> setScale(-1f, 1f)
                ExifInterface.ORIENTATION_ROTATE_180 -> setRotate(180f)
                ExifInterface.ORIENTATION_FLIP_VERTICAL -> setScale(1f, -1f)
                ExifInterface.ORIENTATION_TRANSPOSE -> { setRotate(90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_90 -> setRotate(90f)
                ExifInterface.ORIENTATION_TRANSVERSE -> { setRotate(-90f); postScale(-1f, 1f) }
                ExifInterface.ORIENTATION_ROTATE_270 -> setRotate(-90f)
            }
            postScale(scale, scale)
        }
        try {
            Bitmap.createBitmap(decoded, 0, 0, decoded.width, decoded.height, transform, true).also {
                if (it !== decoded) decoded.recycle()
            }
        } catch (error: Throwable) { decoded.recycle(); throw error }
    }

    private suspend fun merge(inputs: List<File>, output: File, quality: Int, filter: FilterSpec?, focus: Boolean, referenceIndex: Int) {
        require(inputs.size in 2..32) { "A stack requires 2..32 source images" }
        require(referenceIndex in inputs.indices)
        validateOutput(inputs, output, quality)
        val dimensions = imageBounds(inputs.first())
        val width = dimensions.first; val height = dimensions.second
        val orientation = ExifInterface(inputs[referenceIndex]).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1)
        inputs.forEach {
            require(imageBounds(it) == dimensions) { "Stack image dimensions differ" }
            require(ExifInterface(it).getAttributeInt(ExifInterface.TAG_ORIENTATION, 1) == orientation) { "Stack orientation changed during capture" }
        }
        ensureMemory(width, height)
        val registrations = register(inputs, referenceIndex, width, height, focus)
        val decoders = ArrayList<BitmapRegionDecoder>()
        var destination: Bitmap? = null
        try {
            inputs.forEach { decoders.add(decoder(it)) }
            val result = createBitmap(width, height)
            destination = result
            val stripeRows = stripeHeight(width)
            for (top in 0 until height step stripeRows) {
                currentCoroutineContext().ensureActive()
                val end = min(height, top + stripeRows)
                val halo = 9
                val stripeTop = max(0, top - halo)
                val stripeEnd = min(height, end + halo)
                val rows = stripeEnd - stripeTop
                val count = width * rows
                val region = Rect(0, stripeTop, width, stripeEnd)
                val reference = readWarpedRegion(decoders[referenceIndex], region, Similarity(), options())
                val red = FloatArray(count)
                val green = FloatArray(count)
                val blue = FloatArray(count)
                val weights = FloatArray(count)
                for (frame in inputs.indices) {
                    currentCoroutineContext().ensureActive()
                    val registration = registrations[frame] ?: continue
                    val pixels = if (frame == referenceIndex) reference else readWarpedRegion(decoders[frame], region, registration.transform, options())
                    val frameWeights = if (focus) FusionMath.focusWeights(pixels, width, rows)
                        else FusionMath.smooth(FloatArray(count) { FusionMath.exposureWeight(pixels[it]) }, width, rows, 7)
                    for (y in 0 until rows) for (x in 0 until width) {
                        val i = y * width + x
                        if (pixels[i] ushr 24 != 255) continue
                        var weight = frameWeights[i]
                        val pixel = pixels[i]
                        if (!focus) {
                            // Smoothing must never reintroduce a clipped sample.
                            if (FusionMath.isClipped(pixel)) continue
                            if (frame != referenceIndex && isMoving(reference[i], pixel, registration.exposureRatio)) weight *= 0.001f
                        }
                        red[i] += linearChannels[pixel ushr 16 and 255] * weight
                        green[i] += linearChannels[pixel ushr 8 and 255] * weight
                        blue[i] += linearChannels[pixel and 255] * weight
                        weights[i] += weight
                    }
                }
                val outputPixels = IntArray(width * (end - top))
                for (y in top until end) for (x in 0 until width) {
                    val i = (y - stripeTop) * width + x
                    outputPixels[(y - top) * width + x] = if (weights[i] < 1e-12f) reference[i] else
                        ColorKernel.pack(ColorKernel.srgb(red[i] / weights[i]), ColorKernel.srgb(green[i] / weights[i]), ColorKernel.srgb(blue[i] / weights[i]))
                }
                result.setPixels(outputPixels, 0, width, 0, top, width, end - top)
            }
            applyFilter(result, filter)
            writeAtomic(result, inputs[referenceIndex], output, quality, if (focus) "Focus stack (${inputs.size})" else "Exposure fusion (${inputs.size})")
        } finally {
            destination?.recycle()
            decoders.forEach { it.recycle() }
        }
    }

    private data class Registration(val transform: Similarity, val exposureRatio: Float)
    private data class Thumbnail(val width: Int, val height: Int, val luma: FloatArray, val clipped: Boolean)

    private suspend fun register(inputs: List<File>, referenceIndex: Int, width: Int, height: Int, focus: Boolean): List<Registration?> {
        var sample = 1
        while (max(width, height) / sample > 384) sample *= 2
        fun thumbnail(file: File): Thumbnail {
            val bitmap = requireNotNull(BitmapFactory.decodeFile(file.path, options().apply { inSampleSize = sample })) { "Cannot read alignment preview" }
            try {
                val pixels = IntArray(bitmap.width * bitmap.height)
                bitmap.getPixels(pixels, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
                return Thumbnail(bitmap.width, bitmap.height, FloatArray(pixels.size) { ColorKernel.gray(pixels[it]) },
                    !focus && pixels.all(FusionMath::isClipped))
            } finally { bitmap.recycle() }
        }
        val (w, h, reference) = thumbnail(inputs[referenceIndex])
        require(w >= 8 && h >= 8) { "Stack images are too small" }
        val referenceDecoder = decoder(inputs[referenceIndex])
        try {
            return inputs.mapIndexed { index, file ->
                currentCoroutineContext().ensureActive()
                if (index == referenceIndex) Registration(Similarity(), 1f) else {
                    val (cw, ch, candidate, clipped) = thumbnail(file)
                    require(w == cw && h == ch)
                    if (clipped) return@mapIndexed null
                    val coarse = SimilarityAlignment.estimate(reference, candidate, w, h, focus)
                    val ratio = if (focus) 1f else FusionMath.exposureRatio(reference, candidate, w, h, coarse)
                    val seed = coarse.fullResolution(sample)
                    val corrected = if (sample == 1) seed else {
                        val other = decoder(file)
                        try { refine(referenceDecoder, other, seed, sample, width, height, focus) }
                        finally { other.recycle() }
                    }
                    Registration(corrected, ratio)
                }
            }
        } finally { referenceDecoder.recycle() }
    }

    private suspend fun refine(ref: BitmapRegionDecoder, other: BitmapRegionDecoder, seed: Similarity, sample: Int,
        width: Int, height: Int, focus: Boolean): Similarity {
        val extent = min(112, min(width, height) / 4)
        if (extent < 24) return seed
        val radius = min(16, sample + 2)
        val matches = ArrayList<SimilarityAlignment.Match>(9)
        val fractions = doubleArrayOf(.18, .5, .82)
        for (fy in fractions) for (fx in fractions) {
            currentCoroutineContext().ensureActive()
            val left = ((width - extent)*fx).roundToInt()
            val top = ((height - extent)*fy).roundToInt()
            val patch = Rect(left, top, left + extent, top + extent)
            val a = readWarpedRegion(ref, patch, Similarity(), options())
            val b = readWarpedRegion(other, patch, seed, options())
            if (b.any { it ushr 24 != 255 }) continue
            val lumaA = FloatArray(a.size) { ColorKernel.gray(a[it]) }
            val lumaB = FloatArray(b.size) { ColorKernel.gray(b[it]) }
            val residual = if (focus) TranslationAligner.alignFocus(lumaA, lumaB, extent, extent, radius)
                else TranslationAligner.align(lumaA, lumaB, extent, extent, radius)
            if (residual.samples < 64 || residual.error >= .25f || abs(residual.x) >= radius || abs(residual.y) >= radius) continue
            val x = left + (extent - 1)*.5; val y = top + (extent - 1)*.5
            matches += SimilarityAlignment.Match(x, y,
                seed.x(x + residual.x, y + residual.y), seed.y(x + residual.x, y + residual.y))
        }
        return SimilarityAlignment.refine(seed, matches, width, height, radius)
    }
    private fun isMoving(reference: Int, candidate: Int, ratio: Float): Boolean {
        val a = ColorKernel.gray(reference); val b = ColorKernel.gray(candidate)
        // No trustworthy motion estimate is possible when the reference or candidate is clipped.
        if (a !in 0.08f..0.92f || b !in 0.04f..0.96f) return false
        val refLinear = ColorKernel.linear(a)
        val corrected = ColorKernel.linear(b) * ratio
        return abs(refLinear - corrected) > 0.035f + 0.35f * max(refLinear, corrected)
    }

    private suspend fun applyFilter(bitmap: Bitmap, filter: FilterSpec?) {
        if (filter == null) return
        val lut = filter.lutPath?.let(::loadLut)
        val kernel = ColorKernel(filter.adjustments, lut, filter.intensity)
        val width = bitmap.width; val height = bitmap.height
        val rows = stripeHeight(width)
        val pixels = IntArray(width * min(rows, height))
        for (top in 0 until height step rows) {
            currentCoroutineContext().ensureActive()
            val count = min(rows, height - top)
            bitmap.getPixels(pixels, 0, width, 0, top, width, count)
            kernel.apply(pixels, width * count)
            bitmap.setPixels(pixels, 0, width, 0, top, width, count)
        }
        if (lut != null) return
        val amount = filter.adjustments.sharpness.coerceIn(0f, 1f) * filter.intensity.coerceIn(0f, 1f) * 2f
        if (amount > 0f) spatialPass(bitmap, 2, amount)
        val blur = ceil(filter.adjustments.blur.coerceIn(0f, 1f) * filter.intensity.coerceIn(0f, 1f) * 12).toInt()
        if (blur > 0) spatialPass(bitmap, blur, 0f)
    }

    private suspend fun spatialPass(bitmap: Bitmap, radius: Int, sharpen: Float) {
        val width = bitmap.width; val height = bitmap.height
        val rows = stripeHeight(width)
        var previousTail = IntArray(0)
        for (top in 0 until height step rows) {
            currentCoroutineContext().ensureActive()
            val end = min(height, top + rows)
            val haloTop = max(0, top - radius)
            val haloEnd = min(height, end + radius)
            val haloRows = haloEnd - haloTop
            val source = IntArray(width * haloRows)
            bitmap.getPixels(source, 0, width, 0, haloTop, width, haloRows)
            if (previousTail.isNotEmpty()) previousTail.copyInto(source, 0)
            val nextTailStart = max(haloTop, end - radius) - haloTop
            previousTail = source.copyOfRange(nextTailStart * width, (end - haloTop) * width)
            val softened = IntArray(source.size)
            ColorKernel.blur(source, width, haloRows, radius, softened, IntArray(source.size))
            if (sharpen > 0f) for (i in source.indices) softened[i] = ColorKernel.sharpen(source[i], softened[i], sharpen)
            bitmap.setPixels(softened, (top - haloTop) * width, width, 0, top, width, end - top)
        }
    }

    private suspend fun writeAtomic(bitmap: Bitmap, reference: File, output: File, quality: Int, description: String? = null) {
        currentCoroutineContext().ensureActive()
        val directory = requireNotNull(output.absoluteFile.parentFile)
        require(directory.isDirectory || directory.mkdirs()) { "Cannot create output directory" }
        val temporary = File.createTempFile(".image-", ".jpg", directory)
        try {
            withContext(Dispatchers.IO) {
                FileOutputStream(temporary).use { stream ->
                    check(bitmap.compress(Bitmap.CompressFormat.JPEG, quality, stream)) { "JPEG encoding failed" }
                    stream.fd.sync()
                }
                copyExif(reference, temporary, bitmap.width, bitmap.height, description)
                FileOutputStream(temporary, true).use { it.fd.sync() }
            }
            currentCoroutineContext().ensureActive()
            withContext(Dispatchers.IO) {
                Files.move(temporary.toPath(), output.toPath(), StandardCopyOption.ATOMIC_MOVE, StandardCopyOption.REPLACE_EXISTING)
            }
        } finally { temporary.delete() }
    }

    private fun copyExif(input: File, output: File, width: Int, height: Int, description: String?) {
        val source = ExifInterface(input)
        val target = ExifInterface(output)
        val tags = arrayOf(
            ExifInterface.TAG_MAKE, ExifInterface.TAG_MODEL, ExifInterface.TAG_ORIENTATION,
            ExifInterface.TAG_DATETIME, ExifInterface.TAG_DATETIME_ORIGINAL, ExifInterface.TAG_DATETIME_DIGITIZED,
            ExifInterface.TAG_SUBSEC_TIME, ExifInterface.TAG_SUBSEC_TIME_ORIGINAL, ExifInterface.TAG_SUBSEC_TIME_DIGITIZED,
            ExifInterface.TAG_OFFSET_TIME, ExifInterface.TAG_OFFSET_TIME_ORIGINAL, ExifInterface.TAG_OFFSET_TIME_DIGITIZED,
            ExifInterface.TAG_EXPOSURE_TIME, ExifInterface.TAG_F_NUMBER, ExifInterface.TAG_PHOTOGRAPHIC_SENSITIVITY,
            ExifInterface.TAG_FOCAL_LENGTH, ExifInterface.TAG_FOCAL_LENGTH_IN_35MM_FILM, ExifInterface.TAG_LENS_MAKE,
            ExifInterface.TAG_LENS_MODEL, ExifInterface.TAG_LENS_SPECIFICATION, ExifInterface.TAG_EXPOSURE_BIAS_VALUE,
            ExifInterface.TAG_EXPOSURE_MODE, ExifInterface.TAG_EXPOSURE_PROGRAM, ExifInterface.TAG_METERING_MODE,
            ExifInterface.TAG_FLASH, ExifInterface.TAG_WHITE_BALANCE, ExifInterface.TAG_ARTIST, ExifInterface.TAG_COPYRIGHT,
        )
        tags.forEach { tag -> source.getAttribute(tag)?.let { target.setAttribute(tag, it) } }
        source.latLong?.let { coordinates -> target.setLatLong(coordinates[0], coordinates[1]) }
        val altitude = source.getAltitude(Double.NaN)
        if (altitude.isFinite()) target.setAltitude(altitude)
        target.setAttribute(ExifInterface.TAG_IMAGE_WIDTH, width.toString())
        target.setAttribute(ExifInterface.TAG_IMAGE_LENGTH, height.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_X_DIMENSION, width.toString())
        target.setAttribute(ExifInterface.TAG_PIXEL_Y_DIMENSION, height.toString())
        target.setAttribute(ExifInterface.TAG_COLOR_SPACE, "1")
        target.setAttribute(ExifInterface.TAG_SOFTWARE, "Camera Redesigned")
        description?.let { target.setAttribute(ExifInterface.TAG_IMAGE_DESCRIPTION, it) }
        target.saveAttributes()
    }

    private fun decodeFull(file: File): Bitmap {
        val (width, height) = imageBounds(file)
        ensureMemory(width, height)
        return requireNotNull(BitmapFactory.decodeFile(file.path, options().apply { inMutable = true })) { "Cannot decode source JPEG" }
    }

    private fun imageBounds(file: File): Pair<Int, Int> {
        require(file.isFile && file.length() > 0) { "Missing source image" }
        val options = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        BitmapFactory.decodeFile(file.path, options)
        require(options.outWidth > 0 && options.outHeight > 0) { "Invalid image file" }
        return options.outWidth to options.outHeight
    }

    private fun ensureMemory(width: Int, height: Int) {
        val runtime = Runtime.getRuntime()
        val available = runtime.maxMemory() - (runtime.totalMemory() - runtime.freeMemory())
        val required = width.toLong() * height * 4 + width.toLong() * (stripeHeight(width) + 24) * 48 + 24L * 1024 * 1024
        require(required < available * 0.85) { "Insufficient memory for full-resolution processing; source photos are retained" }
    }

    private fun validateOutput(inputs: List<File>, output: File, quality: Int) {
        require(quality in 1..100) { "JPEG quality must be 1..100" }
        require(inputs.none { it.canonicalFile == output.canonicalFile }) { "Output must not overwrite a source image" }
    }

    private fun stripeHeight(width: Int): Int = (131_072 / width).coerceIn(16, 128)
    private fun loadLut(path: String): CubeLut {
        val file = File(path)
        val cached = cachedLut
        if (cached != null && cached.path == file.absolutePath && cached.modified == file.lastModified() && cached.bytes == file.length()) return cached.lut
        return CubeLut.read(file).also { cachedLut = CachedLut(file.absolutePath, file.lastModified(), file.length(), it) }
    }
    private fun options() = BitmapFactory.Options().apply {
        inPreferredConfig = Bitmap.Config.ARGB_8888
        inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB)
        inScaled = false
    }

    private companion object {
        val linearChannels = FloatArray(256) { ColorKernel.linear(it / 255f) }
    }

    @Suppress("DEPRECATION")
    private fun decoder(file: File): BitmapRegionDecoder =
        requireNotNull(BitmapRegionDecoder.newInstance(file.path, false)) { "Cannot open image decoder" }
}
