package com.cjryrx.cameraredesigned.data

import com.cjryrx.cameraredesigned.domain.FilterSpec
import com.cjryrx.cameraredesigned.domain.ShootingMode
import org.junit.Assert.*
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import java.util.UUID

class FilterAssetCleanupTest {
    @get:Rule val temp = TemporaryFolder()

    private fun asset(dir: File) = File(dir, UUID.randomUUID().toString() + ".cube").apply { writeText("asset") }
    private fun spec(file: File) = FilterSpec("library", "Imported", lutPath = file.absolutePath)

    @Test fun collectsOnlyOwnedLutsUnreferencedByLibraryAndAllJournalVariants() {
        val filters = temp.newFolder("filters")
        val work = temp.newFolder("capture-work")
        val active = asset(filters)
        val obsolete = asset(filters)
        val foreign = File(filters, "external.cube").apply { createNewFile() }
        val retained = listOf("one.json", "two.json.bak", "three.json.new").map { name ->
            val pending = asset(filters)
            val job = ProcessingJob("job", listOf("pending.jpg"), emptyList(), ShootingMode.SINGLE, 95, spec(pending), false, true)
            File(work, name).writeText(appJson.encodeToString(job))
            pending
        }
        pruneUnusedLuts(filters, work, listOf(spec(active)))
        assertTrue(active.isFile && foreign.isFile)
        assertTrue(retained.all(File::isFile))
        assertFalse(obsolete.exists())
    }

    @Test fun unparseableJournalOrUnavailableDirectoryPreservesEveryAsset() {
        val filters = temp.newFolder("filters")
        val asset = asset(filters)
        val work = temp.newFolder("capture-work")
        File(work, "interrupted.json.new").writeText("{")
        pruneUnusedLuts(filters, work, emptyList())
        assertTrue(asset.isFile)
        val unavailable = temp.newFile("not-a-directory")
        pruneUnusedLuts(filters, unavailable, emptyList())
        assertTrue(asset.isFile)
    }
}
