package com.cjryrx.cameraredesigned.ui.render

import android.graphics.Bitmap
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PixelFormat
import android.media.ImageReader
import android.os.Handler
import android.os.Looper
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.*
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference
import kotlin.math.abs

/** Synthetic buffer producer verifies actual OES transforms and asynchronous PBO readback on device. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewReadbackTest {
    // Some devices freeze even an instrumented background process between camera and GPU tests.
    @get:Rule val foreground = ActivityScenarioRule(
        CameraProbeActivity::class.java)

    @Test fun uprightAnalysisMatchesAllSensorRotationsAndMirroring() {
        for (rotation in listOf(0, 90, 180, 270)) for (mirror in listOf(false, true)) {
            verifyCorners(rotation, mirror)
        }
    }

    private fun verifyCorners(rotation: Int, mirror: Boolean) {
        val inputReady = CountDownLatch(1)
        val analyzed = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val source = AtomicReference<Surface?>(null)
        val result = AtomicReference<Bitmap?>(null)
        val swapped = rotation % 180 != 0
        val width = if (swapped) 60 else 80
        val height = if (swapped) 80 else 60
        val reader = ImageReader.newInstance(width, height, PixelFormat.RGBA_8888, 3)
        reader.setOnImageAvailableListener({ it.acquireLatestImage()?.close() }, Handler(Looper.getMainLooper()))
        val renderer = GpuPreviewRenderer(
            onInputReady = { input, _, _ -> input.setDefaultBufferSize(80, 60); source.set(Surface(input)); inputReady.countDown() },
            onAnalysis = { bitmap -> if (result.compareAndSet(null, bitmap)) analyzed.countDown() else bitmap.recycle() },
            onError = { failure.set(it); inputReady.countDown(); analyzed.countDown() },
        )
        renderer.configure(PreviewStyle(rotation = rotation, mirror = mirror, analysis = true))
        try {
            renderer.start(reader.surface, width, height)
            assertTrue(inputReady.await(10, TimeUnit.SECONDS))
            assertNull(failure.get())
            val producer = requireNotNull(source.get())
            val paint = Paint()
            repeat(6) {
                val canvas = producer.lockCanvas(null)
                paint.color = Color.RED; canvas.drawRect(0f, 0f, 40f, 30f, paint)
                paint.color = Color.GREEN; canvas.drawRect(40f, 0f, 80f, 30f, paint)
                paint.color = Color.BLUE; canvas.drawRect(0f, 30f, 40f, 60f, paint)
                paint.color = Color.WHITE; canvas.drawRect(40f, 30f, 80f, 60f, paint)
                producer.unlockCanvasAndPost(canvas)
                if (analyzed.await(80, TimeUnit.MILLISECONDS)) return@repeat
            }
            assertTrue("No analysis frame", analyzed.await(5, TimeUnit.SECONDS))
            assertNull(failure.get())
            val bitmap = requireNotNull(result.get())
            val originals = listOf(Triple(.25f, .25f, Color.RED), Triple(.75f, .25f, Color.GREEN),
                Triple(.25f, .75f, Color.BLUE), Triple(.75f, .75f, Color.WHITE))
            for ((x, y, expected) in originals) {
                val (u, v) = when (rotation) { 90 -> 1f - y to x; 180 -> 1f - x to 1f - y; 270 -> y to 1f - x; else -> x to y }
                val px = ((if (mirror) 1f - u else u) * bitmap.width).toInt()
                val py = (v * bitmap.height).toInt()
                val actual = bitmap.getPixel(px, py)
                assertTrue("rotation=$rotation mirror=$mirror ($px,$py): expected $expected actual $actual",
                    abs(Color.red(expected) - Color.red(actual)) <= 3 && abs(Color.green(expected) - Color.green(actual)) <= 3 && abs(Color.blue(expected) - Color.blue(actual)) <= 3)
            }
        } finally {
            source.get()?.release(); renderer.pause(); renderer.close(); result.get()?.recycle(); reader.close()
        }
    }
}
