package com.cjryrx.cameraredesigned.data

import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.domain.validated
import com.cjryrx.cameraredesigned.imaging.CubeLut
import kotlinx.serialization.encodeToString
import java.io.File
import java.io.InputStream
import java.io.OutputStream
import java.util.UUID
import java.util.zip.ZipEntry
import java.util.zip.ZipInputStream
import java.util.zip.ZipOutputStream

/** The two named entries are the entire .crfilter exchange format. Paths inside the document
 * are never interpreted as local file paths. Imported LUTs belong to this installation. */
internal object FilterArchive {
    const val MIME_TYPE = "application/vnd.cjryrx.crfilter"
    private const val DEFINITION_LIMIT = 64 * 1024
    private val invalidFilename = Regex("""[\p{Cntrl}<>:"/\\|?*]""")

    fun filename(filter: FilterSpec): String =
        filter.name.replace(invalidFilename, "_").take(64).trim(' ', '.').ifBlank { "Filter" } + ".crfilter"

    fun write(filter: FilterSpec, destination: OutputStream) {
        val spec = filter.validated()
        val packaged = spec.copy(lutPath = spec.lutPath?.let { "lut.cube" })
        ZipOutputStream(destination.buffered()).use { zip ->
            zip.putNextEntry(ZipEntry("filter.json"))
            zip.write(appJson.encodeToString(packaged).encodeToByteArray())
            zip.closeEntry()
            spec.lutPath?.let { path ->
                zip.putNextEntry(ZipEntry("lut.cube"))
                File(path).inputStream().use { it.copyTo(zip) }
                zip.closeEntry()
            }
        }
    }

    fun read(source: InputStream, filtersDir: File): FilterSpec {
        val id = UUID.randomUUID().toString()
        val lut = File(filtersDir, "$id.cube")
        var spec: FilterSpec? = null
        try {
            ZipInputStream(source.buffered()).use { zip ->
                while (true) {
                    val entry = zip.nextEntry ?: break
                    when (entry.name) {
                        "filter.json" -> {
                            require(spec == null) { "Duplicate filter definition" }
                            // readNBytes is unavailable on older supported Android versions.
                            val bytes = ByteArray(DEFINITION_LIMIT + 1)
                            var length = 0
                            while (length < bytes.size) {
                                val count = zip.read(bytes, length, bytes.size - length)
                                if (count < 0) break
                                length += count
                            }
                            require(length <= DEFINITION_LIMIT) { "Filter definition is too large" }
                            spec = appJson.decodeFromString<FilterSpec>(bytes.decodeToString(0, length))
                        }
                        "lut.cube" -> {
                            require(!lut.exists()) { "Duplicate LUT" }
                            zip.copyLimited(lut, CubeLut.MAX_FILE_BYTES)
                        }
                        else -> throw IllegalArgumentException("Unknown filter package entry")
                    }
                    zip.closeEntry()
                }
            }
            val parsed = requireNotNull(spec) { "Missing filter.json" }
            if (parsed.lutPath != null) {
                require(parsed.lutPath == "lut.cube" && lut.isFile) { "Invalid or missing LUT" }
                CubeLut.read(lut)
            } else require(!lut.exists()) { "Unreferenced LUT" }
            return parsed.copy(id = id, lutPath = if (parsed.lutPath != null) lut.absolutePath else null).validated()
        } catch (e: Exception) {
            lut.delete()
            throw e
        }
    }
}
