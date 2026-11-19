package com.cjryrx.cameraredesigned.integration

import android.content.pm.ActivityInfo
import android.content.res.Configuration
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.view.TextureView
import android.view.ViewGroup
import androidx.core.view.descendants
import androidx.exifinterface.media.ExifInterface
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.SdkSuppress
import com.cjryrx.cameraredesigned.domain.CameraAction
import com.cjryrx.cameraredesigned.domain.CustomFunction
import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.domain.FocusArea
import com.cjryrx.cameraredesigned.domain.ShootingMode
import org.junit.Assert.*
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@SdkSuppress(minSdkVersion = 30)
class FilterCaptureDeviceTest : CameraWorkflowFixture() {
    @Test fun selectedPresetProcessesBothJpegsWhileOnlyEnabledPreviewIsGraded() = withCamera(ShootingMode.SINGLE) {
        orient(ActivityInfo.SCREEN_ORIENTATION_PORTRAIT, Configuration.ORIENTATION_PORTRAIT)
        livePreview()

        // Exercise a rebind, including the real Fn button dispatch.
        scenario.onActivity { model.action(CameraAction.Settings(model.state.value.settings.copy(customFunction = CustomFunction.EXPOSURE_WARNING))) }
        tap(bounds("Fn"))
        waitFor("Fn exposure warning on") { model.state.value.exposureWarning }
        tap(bounds("Fn"))
        waitFor("Fn exposure warning off") { !model.state.value.exposureWarning }
        scenario.onActivity { model.action(CameraAction.Settings(model.state.value.settings.copy(customFunction = CustomFunction.FOCUS_AREA))) }
        tap(bounds("Fn"))
        waitFor("Fn changes center focus region to wide") { model.state.value.settings.focusArea == FocusArea.WIDE }

        val original = capturePhoto()
        verifyJpeg(original)
        val originalColors = jpegColors(original)
        val originalPreview = previewColors()
        // Pick the less common tone in BOTH unfiltered samples. This works with monochrome scenes too.
        val bright = originalColors.bright + originalPreview.bright < originalColors.dim + originalPreview.dim
        val preset = FilterSpec("acceptance-gray", "Acceptance Gray", FilterAdjustments(
            exposure = -5f, shadows = if (bright) 1f else 0f, contrast = -1f, saturation = -1f))
        assertTrue("Unfiltered JPEG already matches the chosen signature: $originalColors", originalColors.match(bright) < .8)
        scenario.onActivity { model.action(CameraAction.SaveFilter(preset)) }
        tap(bounds("Filters", "滤镜"))
        tap(bounds(preset.name))
        waitFor("filter selection") { model.state.value.settings.activeFilterId == preset.id }
        assertFalse(model.state.value.settings.realtimeFilter)
        assertTrue("Preview was graded before enabling real-time filters", previewColors().match(bright) < .8)
        assertGradedJpeg(capturePhoto(), bright)

        tap(bounds("Menu", "菜单"))
        tap(bounds("Filters", "滤镜"))
        tap(bounds("Apply to live preview", "应用于实时取景"))
        waitFor("live preview setting") { model.state.value.settings.realtimeFilter }
        tap(bounds("‹ Camera", "‹ 拍摄"))
        livePreview()
        waitFor("GPU preview shows selected tone") { previewColors().match(bright) > .99 }
        assertGradedJpeg(capturePhoto(), bright)
        assertEquals("One unfiltered and two filtered JPEGs", 3, newPhotos().size)
    }

    private fun capturePhoto(): Uri {
        val before = newPhotos()
        holdShutter { drag -> drag(1f) }
        waitFor("full-resolution JPEG publication", 120_000) {
            !model.state.value.shooting && app.processing.state.value.count == 0 && (newPhotos() - before).size == 1
        }
        return (newPhotos() - before).single()
    }

    private fun assertGradedJpeg(uri: Uri, bright: Boolean) {
        verifyJpeg(uri)
        val colors = jpegColors(uri)
        assertTrue("JPEG pixels do not match the selected neutral tone: $colors", colors.match(bright) > .99)
        requireNotNull(app.contentResolver.openInputStream(uri)).use { input ->
            assertEquals("Camera Redesigned", ExifInterface(input).getAttribute(ExifInterface.TAG_SOFTWARE))
        }
    }

    private fun previewColors(): GrayFractions {
        lateinit var sample: Bitmap
        scenario.onActivity { activity ->
            val texture = (activity.window.decorView as ViewGroup).descendants.filterIsInstance<TextureView>().single()
            sample = requireNotNull(texture.getBitmap(96, 72))
        }
        return grayFractions(sample)
    }

    private fun jpegColors(uri: Uri): GrayFractions = requireNotNull(app.contentResolver.openInputStream(uri)).use { stream ->
        grayFractions(requireNotNull(BitmapFactory.decodeStream(stream, null, BitmapFactory.Options().apply { inSampleSize = 16 })))
    }

    private data class GrayFractions(val dim: Double, val bright: Double) {
        fun match(useBright: Boolean) = if (useBright) bright else dim
    }

    // -5 EV constrains input linear RGB to [0,1/32]. At half contrast and zero saturation this
    // yields 64..89 gray; +1 shadows moves it near 140. Allow rounding and JPEG quantization.
    private fun grayFractions(sample: Bitmap): GrayFractions {
        try {
            val pixels = IntArray(sample.width * sample.height)
            sample.getPixels(pixels, 0, sample.width, 0, 0, sample.width, sample.height)
            var dim = 0
            var bright = 0
            for (pixel in pixels) {
                val r = pixel ushr 16 and 255
                val g = pixel ushr 8 and 255
                val b = pixel and 255
                if (maxOf(r, g, b) - minOf(r, g, b) > 3) continue
                if (g in 61..92) dim++
                if (g in 132..148) bright++
            }
            return GrayFractions(dim.toDouble() / pixels.size, bright.toDouble() / pixels.size)
        } finally { sample.recycle() }
    }
}
