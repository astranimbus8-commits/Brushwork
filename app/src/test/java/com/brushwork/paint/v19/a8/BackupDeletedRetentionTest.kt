package com.brushwork.paint.v19.a8

import android.net.Uri
import android.provider.DocumentsContract
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupRestore
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.RestoreItem
import com.brushwork.paint.v19.a8.BackupKit.DAY
import com.brushwork.paint.v19.a8.BackupKit.MB
import com.brushwork.paint.v19.a8.BackupKit.ROOT
import com.brushwork.paint.v19.a8.BackupKit.T0
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.idle
import com.brushwork.paint.v19.a8.BackupKit.index
import com.brushwork.paint.v19.a8.BackupKit.local
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.remote
import com.brushwork.paint.v19.a8.BackupKit.repo
import com.brushwork.paint.v19.a8.BackupKit.rootDir
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (R16 (b), D25): a deleted artwork's backup folder moves to `deleted/<id>/` (the index
 * lists it with when it went), is purged after 30 days (at app start and after each backup), and
 * oldest first while the backup's volume has less than 500 MB free.
 */
@RunWith(RobolectricTestRunner::class)
class BackupDeletedRetentionTest {
    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    private fun deletedDir(id: String) = File(rootDir(), "${BackupLayout.DELETED}/$id")

    private fun delete(id: String, at: Long) {
        BackupKit.now = at
        runBlocking { repo.delete(id) }
        idle()
    }

    @Test
    fun aDeletedArtworkMovesToDeleted() {
        val a = newArtwork("Gone")
        val keep = newArtwork("Kept")
        choose()
        delete(a, T0 + 1000)
        assertFalse(remote(a).exists())
        assertTrue("moved with its files", File(deletedDir(a), ProjectFormat.PROJECT_FILE).isFile)
        val index = index()
        assertEquals(listOf(keep), index.artworks.map { it.id })
        assertEquals(listOf(a to "Gone"), index.deleted.map { it.id to it.name })
        assertEquals(T0 + 1000, index.deleted.single().deletedAt)
        assertTrue("moved, not copied: ${FakeDocumentsProvider.log}", FakeDocumentsProvider.log.any { it == "move $ROOT/$a $ROOT/${BackupLayout.DELETED}/$a" })
    }

    @Test
    fun withoutMoveTheFolderIsCopiedThenDeleted() {
        FakeDocumentsProvider.supportsMove = false
        val a = newArtwork("Gone")
        choose()
        val files = BackupKit.names(remote(a))
        delete(a, T0 + 1000)
        assertFalse(remote(a).exists())
        assertEquals(files, BackupKit.names(deletedDir(a)))
    }

    @Test
    fun anArtworkDeletedWhileTheAppWasAwayMovesAtTheNextStart() {
        val a = newArtwork("A")
        newArtwork("B")
        choose()
        local(a).deleteRecursively()
        BackupService.startWith(app)
        idle()
        assertTrue(File(deletedDir(a), ProjectFormat.PROJECT_FILE).isFile)
        assertEquals(listOf(a), index().deleted.map { it.id })
    }

    @Test
    fun purgedAfterThirtyDaysAtStartAndAfterABackup() {
        val a = newArtwork("A")
        val b = newArtwork("B")
        newArtwork("C")
        choose()
        delete(a, T0)
        delete(b, T0 + DAY)

        // App start 29 days later: both kept.
        BackupKit.now = T0 + 29 * DAY
        BackupService.startWith(app)
        idle()
        assertTrue(deletedDir(a).isDirectory)
        assertTrue(deletedDir(b).isDirectory)

        // App start at 30 days: the first goes.
        BackupKit.now = T0 + 30 * DAY
        BackupService.startWith(app)
        idle()
        assertFalse(deletedDir(a).exists())
        assertTrue(deletedDir(b).isDirectory)
        assertEquals(listOf(b), index().deleted.map { it.id })

        // After a backup a day later: the second goes too.
        BackupKit.now = T0 + 31 * DAY
        BackupService.backUpNow()
        idle()
        assertFalse(deletedDir(b).exists())
        assertEquals(emptyList<String>(), index().deleted)
    }

    /** Restores [id] from the backup folder, as "Restore artworks…" does, and waits for its backup. */
    private fun restore(id: String) {
        val n = runBlocking { BackupService.restore(app, BackupKit.source(), listOf(id), alsoSettings = false) }
        assertEquals("restored", 1, n)
        idle()
    }

    /** The restore list's rows for [id]. */
    private fun rows(id: String): List<RestoreItem> {
        val s = BackupKit.source()
        return BackupRestore.list(s.tree, s.root!!, BackupKit.projects).filter { it.id == id }
    }

    /**
     * v1.9 A8 review: an artwork restored with its id from "Deleted in the last 30 days" leaves
     * `deleted/`. Its folder moves back (no data file is copied again), the index lists it once,
     * and the restore list shows it once, already in the gallery. Before, the folder stayed in
     * `deleted/` for good: the purge never saw it again, and the list showed the artwork twice.
     */
    @Test
    fun aDeletedArtworkRestoredWithItsIdLeavesDeleted() {
        val a = newArtwork("Back again")
        val keep = newArtwork("Kept")
        choose()
        delete(a, T0 + 1000)
        assertEquals(listOf(true), rows(a).map { it.deleted })

        FakeDocumentsProvider.resetCounts()
        restore(a)
        assertTrue("in the gallery", local(a).isDirectory)
        assertFalse("gone from deleted/", deletedDir(a).exists())
        assertEquals(BackupKit.expectedFiles(local(a)), BackupKit.names(remote(a)))
        val log = FakeDocumentsProvider.log.toList()
        assertTrue("moved back: $log", log.any { it == "move $ROOT/${BackupLayout.DELETED}/$a $ROOT/$a" })
        for (name in ProjectFormat.referencedFiles(ProjectFormat.read(local(a)))) {
            assertFalse("$name is not copied again: $log", log.any { it.startsWith("write $ROOT/$a/$name ") })
        }
        val index = index()
        assertEquals(setOf(a, keep), index.artworks.map { it.id }.toSet())
        assertEquals(emptyList<String>(), index.deleted.map { it.id })
        assertEquals("listed once, already in the gallery", listOf(false to true), rows(a).map { it.deleted to it.already })

        // A second backup and the 30 days change nothing.
        BackupKit.now = T0 + 31 * DAY
        BackupService.backUpNow()
        idle()
        assertTrue(File(remote(a), ProjectFormat.PROJECT_FILE).isFile)
        assertFalse(deletedDir(a).exists())
    }

    /** A deleted copy beside a folder of the same id in the root (a move cut short) is dropped once the artwork is back. */
    @Test
    fun aDeletedCopyBesideTheRootsFolderIsDropped() {
        val a = newArtwork("Back again")
        choose()
        delete(a, T0 + 1000)
        deletedDir(a).copyRecursively(remote(a))
        restore(a)
        assertFalse("gone from deleted/", deletedDir(a).exists())
        assertEquals(BackupKit.expectedFiles(local(a)), BackupKit.names(remote(a)))
        assertEquals(emptyList<String>(), index().deleted.map { it.id })
        assertEquals(listOf(false), rows(a).map { it.deleted })
    }

    @Test
    fun purgedOldestFirstWhileLessThan500MbFree() {
        val ids = (1..3).map { newArtwork("A$it") }
        newArtwork("Kept")
        choose()
        for ((i, id) in ids.withIndex()) delete(id, T0 + i * 60_000L)
        assertEquals(ids, index().deleted.map { it.id })

        // Each purge frees 70 MB: 380 and 450 MB purge the two oldest; 520 MB keeps the newest.
        val probes = ArrayList<Uri>()
        BackupService.freeBytes = { _, probe ->
            probes += probe
            380 * MB + (3 - File(rootDir(), BackupLayout.DELETED).list().orEmpty().size) * 70 * MB
        }
        BackupService.backUpNow()
        idle()
        assertFalse(deletedDir(ids[0]).exists())
        assertFalse(deletedDir(ids[1]).exists())
        assertTrue(deletedDir(ids[2]).isDirectory)
        assertEquals(listOf(ids[2]), index().deleted.map { it.id })
        assertEquals("asked after each purge", 3, probes.size)
        assertEquals("the volume of the backup's own index", "root:$ROOT/${BackupLayout.INDEX}", DocumentsContract.getDocumentId(probes.first()))

        // Unknown free space: nothing more is purged.
        BackupService.freeBytes = { _, _ -> null }
        BackupService.backUpNow()
        idle()
        assertTrue(deletedDir(ids[2]).isDirectory)
    }
}
