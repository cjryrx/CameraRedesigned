package com.cjryrx.cameraredesigned.data

import com.cjryrx.cameraredesigned.domain.FilterSpec
import java.io.File
import java.io.IOException
import java.nio.file.Files

/** Run once, before the first library change. A queued capture owns its LUT until its journal
 * disappears, even after the user removes the preset from their library. */
internal fun pruneUnusedLuts(filtersDir: File, workDir: File, library: List<FilterSpec>) {
    val ownedName = Regex("[0-9a-f]{8}(?:-[0-9a-f]{4}){3}-[0-9a-f]{12}\\.cube")
    try {
        val candidates = filtersDir.listFiles { file -> file.isFile && ownedName.matches(file.name) } ?: return
        if (candidates.isEmpty()) return
        val retained = library.mapNotNullTo(mutableSetOf()) { it.lutPath?.let { path -> File(path).canonicalPath } }
        if (!Files.notExists(workDir.toPath())) {
            val work = workDir.listFiles() ?: return
            for (file in work) {
                if (file.name.endsWith(".json") || file.name.endsWith(".json.bak") || file.name.endsWith(".json.new")) {
                    val job = appJson.decodeFromString<ProcessingJob>(file.readText())
                    job.filter?.lutPath?.let { retained.add(File(it).canonicalPath) }
                }
            }
        }
        val obsolete = candidates.filter { it.canonicalPath !in retained }
        for (file in obsolete) file.delete()
    } catch (_: IOException) {
        // The worker may have finished and removed a journal while we were reading it.
    } catch (_: IllegalArgumentException) {
        // A partially written, damaged or incompatible journal may still reference a filter.
    } catch (_: SecurityException) {
        // Without access to the entire working set we cannot establish that a LUT is unused.
    }
}
