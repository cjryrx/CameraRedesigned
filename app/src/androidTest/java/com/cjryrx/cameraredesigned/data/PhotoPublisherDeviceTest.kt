package com.cjryrx.cameraredesigned.data

import android.content.ContentValues
import android.graphics.Bitmap
import android.net.Uri
import android.provider.MediaStore
import android.provider.MediaStore.MediaColumns
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.Rule
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import java.io.File

class PhotoPublisherDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(CameraProbeActivity::class.java)

    @Test fun interruptedGalleryWriteResumesSameUriAndPublishesExactBytes() = runBlocking<Unit> {
        val context = InstrumentationRegistry.getInstrumentation().targetContext
        assumeTrue(context.packageName.endsWith(".acceptance"))
        val resolver = context.contentResolver
        val sink = PhotoPublisher(context)
        val source = File.createTempFile("CR_publish_", ".jpg", context.cacheDir)
        val cleanup = mutableSetOf<Uri>()
        try {
            val bitmap = Bitmap.createBitmap(12, 16, Bitmap.Config.ARGB_8888)
            try {
                bitmap.eraseColor(0xff24ab67.toInt())
                source.outputStream().use { assertTrue(bitmap.compress(Bitmap.CompressFormat.JPEG, 91, it)) }
            } finally { bitmap.recycle() }
            val collection = MediaStore.Images.Media.getContentUri(MediaStore.VOLUME_EXTERNAL_PRIMARY)
            val uri = requireNotNull(resolver.insert(collection, ContentValues().apply {
                put(MediaColumns.DISPLAY_NAME, source.name)
                put(MediaColumns.RELATIVE_PATH, "DCIM/CameraRedesigned/")
                put(MediaColumns.MIME_TYPE, "image/jpeg")
                put(MediaColumns.IS_PENDING, 1)
            }))
            cleanup += uri
            resolver.openOutputStream(uri)!!.use { it.write(ByteArray(4096)) }
            val result = sink.publish(source, source.name)
            cleanup += result
            assertEquals("Reuse the interrupted media row", uri, result)
            assertArrayEquals(source.readBytes(), resolver.openInputStream(result)!!.use { it.readBytes() })
            resolver.query(result, arrayOf(MediaColumns.IS_PENDING), null, null, null)!!.use {
                assertTrue(it.moveToFirst())
                assertEquals(0, it.getInt(0))
            }
            val retried = sink.publish(source, source.name)
            cleanup += retried
            assertEquals(result, retried)
        } finally {
            cleanup.forEach { resolver.delete(it, null, null) }
            source.delete()
        }
    }
}
