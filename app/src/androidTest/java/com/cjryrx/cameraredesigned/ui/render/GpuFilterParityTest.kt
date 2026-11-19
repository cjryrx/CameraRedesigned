package com.cjryrx.cameraredesigned.ui.render

import android.graphics.Color
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.imaging.ColorKernel
import org.junit.Assert.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

@RunWith(AndroidJUnit4::class)
class GpuFilterParityTest {
    // Some devices freeze even an instrumented background process between camera and GPU tests.
    @get:Rule val foreground = ActivityScenarioRule(
        CameraProbeActivity::class.java)

    @Test fun gradingAndSpatialPassesMatchJpegKernelOnUniformColor() {
        val filter = FilterSpec("parity", "Parity", FilterAdjustments(exposure = .7f, temperature = .2f,
            tint = -.1f, saturation = .15f, vibrance = .2f, contrast = .1f, highlights = -.15f,
            shadows = .1f, sharpness = .5f, blur = .5f))
        val source = Color.rgb(110, 80, 50)
        verifyRenderedColor(filter, source, ColorKernel(filter.adjustments).apply(source))
    }

    @Test fun importedFloatLutIsActuallyApplied() {
        val file = File(InstrumentationRegistry.getInstrumentation().targetContext.cacheDir, "gpu-parity.cube")
        file.writeText("LUT_3D_SIZE 2\n" + List(8) { "0.0 0.5 1.0" }.joinToString("\n"))
        try { verifyRenderedColor(FilterSpec("lut-parity", "LUT parity", lutPath = file.absolutePath), Color.RED, Color.rgb(0, 128, 255)) }
        finally { file.delete() }
    }

    private fun verifyRenderedColor(filter: FilterSpec, inputColor: Int, expected: Int) {
        val inputReady = CountDownLatch(1)
        val matched = CountDownLatch(1)
        val source = AtomicReference<Surface?>(null)
        val failure = AtomicReference<String?>(null)
        val lastColor = AtomicReference<Int?>(null)
        val reader = ImageReader.newInstance(64, 64, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ images -> images.acquireLatestImage()?.use { image ->
            val plane = image.planes[0]
            val buffer = plane.buffer
            val offset = 32 * plane.rowStride + 32 * plane.pixelStride
            val actual = Color.rgb(buffer.get(offset).toInt() and 255, buffer.get(offset + 1).toInt() and 255, buffer.get(offset + 2).toInt() and 255)
            lastColor.set(actual)
            if (abs(Color.red(actual) - Color.red(expected)) <= 3 && abs(Color.green(actual) - Color.green(expected)) <= 3 && abs(Color.blue(actual) - Color.blue(expected)) <= 3) matched.countDown()
        } }, Handler(Looper.getMainLooper()))
        val renderer = GpuPreviewRenderer(
            onInputReady = { input, _, _ -> input.setDefaultBufferSize(64, 64); source.set(Surface(input)); inputReady.countDown() },
            onAnalysis = { it.recycle() },
            onError = { failure.set(it); inputReady.countDown(); matched.countDown() },
        )
        renderer.configure(PreviewStyle(rotation = 0, filter = filter))
        try {
            renderer.start(reader.surface, 64, 64)
            assertTrue(inputReady.await(10, TimeUnit.SECONDS)); assertNull(failure.get())
            val producer = requireNotNull(source.get())
            for (frame in 0 until 20) {
                val canvas = producer.lockCanvas(null); canvas.drawColor(inputColor); producer.unlockCanvasAndPost(canvas)
                if (matched.await(100, TimeUnit.MILLISECONDS)) break
            }
            assertNull("GPU render error: ${failure.get()}", failure.get())
            assertTrue("Expected color $expected, last GPU color ${lastColor.get()}", matched.await(2, TimeUnit.SECONDS))
        } finally { source.get()?.release(); renderer.pause(); renderer.close(); reader.close() }
    }
}
