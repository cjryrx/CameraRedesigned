package com.cjryrx.cameraredesigned.data

import com.cjryrx.cameraredesigned.domain.FilterAdjustments
import com.cjryrx.cameraredesigned.domain.FilterSpec
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.ByteArrayOutputStream
import java.io.File
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

class FilterArchiveTest {
    @get:Rule val temp = TemporaryFolder()

    @Test fun parametersAndNonAsciiNameRoundTripUnderANewLocalId() {
        val source = FilterSpec("old", "暖色 / Film", FilterAdjustments(
            exposure = .4f, temperature = .25f, tint = -.1f, saturation = .2f, vibrance = -.4f,
            contrast = .8f, highlights = -.7f, shadows = .2f, sharpness = .6f, blur = .1f,
        ), intensity = .65f)
        val bytes = ByteArrayOutputStream().also { FilterArchive.write(source, it) }.toByteArray()
        val dir = temp.newFolder()
        val actual = FilterArchive.read(bytes.inputStream(), dir)
        assertNotEquals(source.id, actual.id)
        assertEquals(source, actual.copy(id = source.id))
        assertEquals("暖色 _ Film.crfilter", FilterArchive.filename(source))
        assertTrue(dir.list().orEmpty().isEmpty())
    }

    @Test fun lutArchiveContainsAPortableReferenceAndOwnsAnIndependentCopyOnImport() {
        val original = temp.newFile("private-original.cube").apply { writeText(identityLut) }
        val filter = FilterSpec("cube", "Evening", lutPath = original.absolutePath, intensity = .42f)
        val bytes = ByteArrayOutputStream().also { FilterArchive.write(filter, it) }.toByteArray()
        ZipInputStream(bytes.inputStream()).use { zip ->
            assertEquals("filter.json", zip.nextEntry.name)
            val definition = appJson.decodeFromString<FilterSpec>(zip.readBytes().decodeToString())
            assertEquals("lut.cube", definition.lutPath)
            assertEquals("lut.cube", zip.nextEntry.name)
            assertEquals(identityLut, zip.readBytes().decodeToString())
            assertNull(zip.nextEntry)
        }
        assertTrue(original.delete())
        val dir = temp.newFolder()
        val restored = FilterArchive.read(bytes.inputStream(), dir)
        val asset = File(checkNotNull(restored.lutPath))
        assertEquals(dir, asset.parentFile)
        assertEquals(identityLut, asset.readText())
        assertEquals(filter.intensity, restored.intensity, 0f)
    }

    @Test fun rejectedArchivesLeaveNoLutFilesBehind() {
        val dir = temp.newFolder()
        val definitions = listOf(
            FilterSpec("bad", "", lutPath = "lut.cube"),
            FilterSpec("bad", "Unreferenced asset"),
            FilterSpec("bad", "External reference", lutPath = "../original.cube"),
        )
        val invalid = definitions.map {
            archive("lut.cube" to identityLut, "filter.json" to appJson.encodeToString(it))
        } + listOf(
            archive("filter.json" to appJson.encodeToString(FilterSpec("bad", "Missing", lutPath = "lut.cube"))),
            archive("lut.cube" to "LUT_3D_SIZE 2", "filter.json" to appJson.encodeToString(FilterSpec("bad", "Incomplete", lutPath = "lut.cube"))),
            archive("lut.cube" to identityLut, "filter.json" to " ".repeat(65537)),
            archive("lut.cube" to identityLut, "../unlisted" to "file"),
        )
        invalid.forEach { bytes ->
            assertThrows(IllegalArgumentException::class.java) { FilterArchive.read(bytes.inputStream(), dir) }
            assertTrue(dir.list().orEmpty().isEmpty())
        }
    }

    private fun archive(vararg entries: Pair<String, String>): ByteArray {
        val buffer = ByteArrayOutputStream()
        ZipOutputStream(buffer).use { zip ->
            for ((name, value) in entries) {
                zip.putNextEntry(ZipEntry(name))
                zip.write(value.encodeToByteArray())
                zip.closeEntry()
            }
        }
        return buffer.toByteArray()
    }

    private val identityLut = """
        TITLE "Identity"
        LUT_3D_SIZE 2
        0 0 0
        1 0 0
        0 1 0
        1 1 0
        0 0 1
        1 0 1
        0 1 1
        1 1 1
    """.trimIndent()
}
