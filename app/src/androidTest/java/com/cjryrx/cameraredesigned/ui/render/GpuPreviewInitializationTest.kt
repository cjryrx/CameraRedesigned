package com.cjryrx.cameraredesigned.ui.render

import android.graphics.SurfaceTexture
import android.view.Surface
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicReference

/** Verifies the device driver compiles every real preview shader and creates the OES input. */
@RunWith(AndroidJUnit4::class)
class GpuPreviewInitializationTest {
    // Some devices freeze even an instrumented background process between camera and GPU tests.
    @get:Rule val foreground = ActivityScenarioRule(
        CameraProbeActivity::class.java)

    @Test fun initializesAllProgramsAndCameraInput() {
        val ready = CountDownLatch(1)
        val failure = AtomicReference<String?>(null)
        val output = SurfaceTexture(false).apply { setDefaultBufferSize(320, 240) }
        val renderer = GpuPreviewRenderer(
            onInputReady = { input, _, _ -> input.setDefaultBufferSize(640, 480); ready.countDown() },
            onAnalysis = { it.recycle() },
            onError = { failure.set(it); ready.countDown() },
        )
        try {
            renderer.start(Surface(output), 320, 240)
            assertTrue("Preview initialization timed out", ready.await(10, TimeUnit.SECONDS))
            assertNull("GPU shaders failed: ${failure.get()}", failure.get())
        } finally {
            renderer.pause(); renderer.close(); output.release()
        }
    }
}
