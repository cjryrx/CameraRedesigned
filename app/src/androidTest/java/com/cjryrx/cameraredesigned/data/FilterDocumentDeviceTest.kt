package com.cjryrx.cameraredesigned.data

import android.content.ContextWrapper
import androidx.core.content.FileProvider
import androidx.test.platform.app.InstrumentationRegistry
import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import com.cjryrx.cameraredesigned.domain.FilterSpec
import kotlinx.coroutines.runBlocking
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import androidx.test.ext.junit.rules.ActivityScenarioRule
import com.cjryrx.cameraredesigned.camera.probe.CameraProbeActivity
import org.junit.rules.TemporaryFolder
import java.io.File

class FilterDocumentDeviceTest {
    @get:Rule val foreground = ActivityScenarioRule(CameraProbeActivity::class.java)

    private val base = InstrumentationRegistry.getInstrumentation().targetContext
    // Use the app's FileProvider as a document destination and independent share recipient.
    @get:Rule val temp = TemporaryFolder(File(base.cacheDir, "exports").apply { mkdirs() })

    @Test fun documentExportAndDelayedShareKeepTheirOwnVersionOfTheFilter() = runBlocking<Unit> {
        val files = temp.newFolder("files")
        val cache = temp.newFolder("cache")
        val context = object : ContextWrapper(base) {
            override fun getFilesDir() = files
            override fun getCacheDir() = cache
        }
        val repository = AppRepository(context)
        val authority = base.packageName + ".files"
        val first = FilterSpec("first", "Warm", FilterAdjustments(temperature = .26f))
        val revised = first.copy(adjustments = FilterAdjustments(temperature = -.12f))
        val shared = repository.prepareFilterShare(first)
        val sharedUri = FileProvider.getUriForFile(base, authority, shared, FilterArchive.filename(first))
        val document = temp.newFile("On device.crfilter").apply { writeBytes(ByteArray(65536) { 1 }) }
        val documentUri = FileProvider.getUriForFile(base, authority, document)
        repository.exportFilter(revised, documentUri)
        assertTrue("Existing document was truncated", document.length() in 1L..65535L)
        val fromShared = repository.importFilter(sharedUri)
        val fromDocument = repository.importFilter(documentUri)
        assertEquals(first, fromShared.copy(id = first.id))
        assertEquals(revised, fromDocument.copy(id = revised.id))
        assertEquals(1, File(cache, "exports").listFiles().orEmpty().size)
    }

    @Test fun freshLutImportSurvivesConcurrentLibraryStartupAndSubsequentReload() = runBlocking<Unit> {
        val files = temp.newFolder("files")
        val context = object : ContextWrapper(base) { override fun getFilesDir() = files }
        AppRepository(context).save(Preferences())
        val library = AppRepository(context)
        val cube = temp.newFile("Original.cube").apply {
            writeText("TITLE \"Midnight\"\nLUT_3D_SIZE 2\n0 0 0\n1 0 0\n0 1 0\n1 1 0\n0 0 1\n1 0 1\n0 1 1\n1 1 1\n")
        }
        val source = FileProvider.getUriForFile(base, base.packageName + ".files", cube)
        // A restored document-picker result can arrive before the settings load finishes.
        val filter = library.importLut(source)
        library.load()
        assertEquals("Midnight", filter.name)
        val imported = File(requireNotNull(filter.lutPath))
        assertEquals(cube.readText(), imported.readText())
        library.save(Preferences(filters = listOf(filter)))
        val next = AppRepository(context)
        assertEquals(filter, next.load().filters.single())
        assertTrue(imported.isFile)
        next.save(Preferences())
        assertTrue(imported.isFile)
        AppRepository(context).load()
        assertFalse(imported.exists())
    }
}
