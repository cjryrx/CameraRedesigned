package com.cjryrx.cameraredesigned.data

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
import android.util.AtomicFile
import com.cjryrx.cameraredesigned.domain.*
import com.cjryrx.cameraredesigned.imaging.CubeLut
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import kotlinx.serialization.Serializable
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import java.io.File
import java.io.InputStream
import java.util.UUID

@Serializable
data class Preferences(val settings: CameraSettings = CameraSettings(), val exposure: ExposureSettings = ExposureSettings(), val filters: List<FilterSpec> = emptyList())

internal val appJson = Json { ignoreUnknownKeys = true; encodeDefaults = true; prettyPrint = true }

internal fun atomicWrite(file: File, bytes: ByteArray) {
    file.parentFile?.mkdirs()
    val atomic = AtomicFile(file)
    val stream = atomic.startWrite()
    try { stream.write(bytes); atomic.finishWrite(stream) }
    catch (e: Exception) { atomic.failWrite(stream); throw e }
}

internal fun InputStream.copyLimited(file: File, limit: Long) {
    file.outputStream().use { out ->
        val buffer = ByteArray(64 * 1024)
        var total = 0L
        while (true) {
            val n = read(buffer)
            if (n == -1) break
            total += n
            require(total <= limit) { "Imported file exceeds size limit" }
            out.write(buffer, 0, n)
        }
    }
}

class AppRepository(private val context: Context) {
    private val mutex = Mutex()
    private var canCleanAtStartup = true
    private val config = File(context.filesDir, "preferences.json")
    val filtersDir = File(context.filesDir, "filters").apply { mkdirs() }

    suspend fun load(): Preferences = withContext(Dispatchers.IO) {
        mutex.withLock {
            val clean = canCleanAtStartup
            canCleanAtStartup = false
            if (!config.exists() && !File(config.path + ".bak").exists()) return@withLock Preferences()
            val saved = appJson.decodeFromString<Preferences>(AtomicFile(config).readFully().decodeToString())
            val validated = saved.copy(settings = saved.settings.validated(), filters = saved.filters.map(FilterSpec::validated))
            if (clean) pruneUnusedLuts(filtersDir, File(context.filesDir, "capture-work"), validated.filters)
            validated
        }
    }
    suspend fun save(value: Preferences) = withContext(Dispatchers.IO) {
        mutex.withLock {
            canCleanAtStartup = false
            atomicWrite(config, appJson.encodeToString(value).encodeToByteArray())
        }
    }

    suspend fun importLut(uri: Uri): FilterSpec = withContext(Dispatchers.IO) {
        mutex.withLock { canCleanAtStartup = false }
        val id = UUID.randomUUID().toString()
        val destination = File(filtersDir, "$id.cube")
        try {
            openInput(uri).use { it.copyLimited(destination, CubeLut.MAX_FILE_BYTES) }
            val title = CubeLut.read(destination).title?.trim()?.takeIf(String::isNotBlank)
            val name = title ?: documentName(uri)?.substringBeforeLast('.')?.trim()?.takeIf(String::isNotBlank)
            FilterSpec(id, name?.take(100) ?: "LUT ${id.take(6)}", lutPath = destination.absolutePath)
        } catch (e: Exception) {
            destination.delete()
            throw e
        }
    }

    private fun openInput(uri: Uri): InputStream =
        checkNotNull(context.contentResolver.openInputStream(uri)) { "Document provider could not open the file" }

    private fun documentName(uri: Uri): String? {
        val column = OpenableColumns.DISPLAY_NAME
        return try {
            context.contentResolver.query(uri, arrayOf(column), null, null, null)?.use { cursor ->
                if (cursor.moveToFirst()) cursor.getString(0) else null
            }
        } catch (_: RuntimeException) { null } // Some providers supply only a stream.
    }

    suspend fun importFilter(uri: Uri): FilterSpec = withContext(Dispatchers.IO) {
        mutex.withLock { canCleanAtStartup = false }
        openInput(uri).use { FilterArchive.read(it, filtersDir) }
    }

    suspend fun exportFilter(filter: FilterSpec, document: Uri) = withContext(Dispatchers.IO) {
        // Finish compression before opening the user-selected document for writing.
        val snapshot = prepareFilterShare(filter)
        try {
            val stream = checkNotNull(context.contentResolver.openOutputStream(document, "wt")) {
                "Document provider could not open the destination"
            }
            stream.use { out -> snapshot.inputStream().use { it.copyTo(out) } }
        } finally {
            snapshot.delete()
        }
        Unit
    }

    suspend fun prepareFilterShare(filter: FilterSpec): File = withContext(Dispatchers.IO) {
        val exportDir = File(context.cacheDir, "exports").apply { mkdirs() }
        // Receivers may open a shared URI long after the chooser returns.
        val expiry = System.currentTimeMillis() - 7L * 24 * 60 * 60 * 1000
        exportDir.listFiles()?.filter { it.extension == "crfilter" && it.lastModified() < expiry }?.forEach { it.delete() }
        val snapshot = File.createTempFile("filter-", ".crfilter", exportDir)
        try {
            snapshot.outputStream().use { FilterArchive.write(filter, it) }
            snapshot
        } catch (e: Exception) {
            snapshot.delete()
            throw e
        }
    }
}
