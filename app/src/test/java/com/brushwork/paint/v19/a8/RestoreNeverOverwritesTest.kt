package com.brushwork.paint.v19.a8

import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.backup.BackupRestore
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.v19.a8.BackupKit.BLUE
import com.brushwork.paint.v19.a8.BackupKit.RED
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.local
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.paint
import com.brushwork.paint.v19.a8.BackupKit.pixel
import com.brushwork.paint.v19.a8.BackupKit.projects
import com.brushwork.paint.v19.a8.BackupKit.remote
import com.brushwork.paint.v19.a8.BackupKit.repo
import com.brushwork.paint.v19.a8.BackupKit.source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (R16 (b), I23): a restore never overwrites an artwork of the gallery. An id the gallery
 * has gets a new id and the name suffix [BackupLabels19.RESTORED_SUFFIX], and the gallery's own
 * artwork stays byte for byte; a free id comes back as it was in the backup. Each artwork is
 * staged and checked first: one the backup lost a file of is left out, and no staging folder stays.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreNeverOverwritesTest {
    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    private fun bytesOf(dir: File): Map<String, List<Byte>> =
        dir.listFiles().orEmpty().associate { it.name to it.readBytes().toList() }

    @Test
    fun aTakenIdGetsANewIdAndTheRestoredSuffix() {
        val a = newArtwork("Sky", BLUE)
        choose()
        // The folder's grant goes: the gallery's newer version is not backed up.
        BackupKit.dropGrants()
        BackupService.startWith(app)
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
        paint(a, RED)
        val before = bytesOf(local(a))

        val src = source()
        val item = runBlocking { BackupService.listRestore(app, src) }.single()
        assertEquals(a, item.id)
        assertFalse("another revision: not already in the gallery", item.already)
        assertTrue(item.checkedByDefault)
        val restored = runBlocking { BackupService.restore(app, src, listOf(a), alsoSettings = false) }
        assertEquals(1, restored)

        assertEquals("the gallery's own artwork is untouched", before, bytesOf(local(a)))
        val ids = projects.list().orEmpty().filter { ProjectFormat.isValidId(it) }.toSet()
        val copy = (ids - a).single()
        assertNotEquals(a, copy)
        val dto = ProjectFormat.read(local(copy))
        assertEquals(copy, dto.id)
        assertEquals("Sky" + BackupLabels19.RESTORED_SUFFIX, dto.name)
        assertEquals("the backup's pixels", BLUE, pixel(copy))
        assertEquals("the gallery's pixels", RED, pixel(a))
        assertEquals(setOf("Sky", "Sky" + BackupLabels19.RESTORED_SUFFIX), runBlocking { repo.list() }.map { it.name }.toSet())
    }

    @Test
    fun aFreeIdComesBackAsItWas() {
        val a = newArtwork("Sky", BLUE)
        choose()
        val backedUp = File(remote(a), ProjectFormat.PROJECT_FILE).readBytes()
        BackupKit.reinstall()
        val src = source()
        val n = runBlocking { BackupService.restore(app, src, listOf(a), alsoSettings = false) }
        assertEquals(1, n)
        assertArrayEquals("the same project file", backedUp, File(local(a), ProjectFormat.PROJECT_FILE).readBytes())
        assertEquals(BLUE, pixel(a))
        assertEquals(listOf("Sky"), runBlocking { repo.list() }.map { it.name })
    }

    @Test
    fun anArtworkMissingAFileIsLeftOutAndNoStagingStays() {
        val a = newArtwork("Broken")
        val b = newArtwork("Fine")
        choose()
        val lost = ProjectFormat.referencedFiles(ProjectFormat.read(remote(a))).first()
        File(remote(a), lost).delete()
        BackupKit.reinstall()
        val src = source()
        val restored = BackupRestore.restore(src.tree, src.root!!, listOf(a, b), projects)
        assertEquals(listOf(b), restored)
        assertFalse(local(a).exists())
        assertEquals("no staging left", emptyList<String>(), projects.list().orEmpty().filter { it.startsWith(BackupRestore.STAGING_PREFIX) })
    }

    @Test
    fun aStagingFolderLeftByAKilledRestoreIsSwept() {
        newArtwork("A")
        val stale = File(projects, BackupRestore.STAGING_PREFIX + "0f0e0d0c-0b0a-4908-8706-050403020100").apply { mkdirs() }
        File(stale, "x.png").writeBytes(ByteArray(3))
        stale.setLastModified(System.currentTimeMillis() - 2 * 60 * 60 * 1000L)
        assertEquals(1, runBlocking { repo.list() }.size)
        assertFalse(stale.exists())
    }

    @Test
    fun restoredNamesKeepTheGalleryLimit() {
        val long = "n".repeat(100)
        val named = BackupRestore.restoredName(long)
        assertEquals(100, named.length)
        assertTrue(named.endsWith(BackupLabels19.RESTORED_SUFFIX))
    }
}
