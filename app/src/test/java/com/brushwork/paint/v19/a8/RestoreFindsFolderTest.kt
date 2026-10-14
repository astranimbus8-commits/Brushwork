package com.brushwork.paint.v19.a8

import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.backup.BackupIndex
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupRestore
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.v19.a8.BackupKit.PICKED
import com.brushwork.paint.v19.a8.BackupKit.ROOT
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.index
import com.brushwork.paint.v19.a8.BackupKit.inspect
import com.brushwork.paint.v19.a8.BackupKit.local
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.projects
import com.brushwork.paint.v19.a8.BackupKit.remote
import com.brushwork.paint.v19.a8.BackupKit.rootDir
import com.brushwork.paint.v19.a8.BackupKit.storage
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (R16 (b)): a restore finds the backup whether the user picks the folder they chose
 * ("Documents/Brushwork") or "Brushwork backup" itself (also renamed, by its index), lists the
 * artworks from the index or, without one, by scanning the folders (and those the index leaves
 * out), and reads `project.json.new` when `project.json` is missing or damaged.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreFindsFolderTest {
    private lateinit var a: String
    private lateinit var b: String

    @Before
    fun setUp() {
        BackupKit.freshInstall()
        a = newArtwork("Alpha")
        b = newArtwork("Beta")
        choose()
        BackupKit.reinstall()
    }

    private fun listed(tree: android.net.Uri = FakeDocumentsProvider.tree(PICKED)) =
        runBlocking { BackupService.listRestore(app, inspect(tree).source) }

    @Test
    fun theFolderTheUserChoseHoldsIt() {
        val src = inspect().source
        assertFalse(src.pickedIsRoot)
        assertEquals(BackupLayout.ROOT_NAME, src.root!!.name)
        assertEquals(2, src.count)
        assertEquals(setOf("Alpha", "Beta"), listed().map { it.name }.toSet())
    }

    @Test
    fun theBackupFolderItselfHoldsIt() {
        val src = inspect(FakeDocumentsProvider.tree(ROOT)).source
        assertTrue(src.pickedIsRoot)
        assertEquals(2, src.count)
        assertEquals(setOf(a, b), listed(FakeDocumentsProvider.tree(ROOT)).map { it.id }.toSet())
    }

    @Test
    fun aRenamedBackupFolderIsFoundByItsIndex() {
        assertTrue(rootDir().renameTo(File(storage, "$PICKED/Old phone")))
        val src = inspect(FakeDocumentsProvider.tree("$PICKED/Old phone")).source
        assertTrue(src.pickedIsRoot)
        assertEquals(2, src.count)
    }

    @Test
    fun aFolderWithoutABackupHasNone() {
        File(storage, "Documents/Empty").mkdirs()
        val src = inspect(FakeDocumentsProvider.tree("Documents/Empty")).source
        assertNull(src.root)
        assertEquals(0, src.count)
        assertEquals("nothing made while looking", emptyList<String>(), File(storage, "Documents/Empty").list()!!.toList())
    }

    @Test
    fun withoutTheIndexTheFoldersAreScanned() {
        File(rootDir(), BackupLayout.INDEX).delete()
        assertEquals(2, inspect().source.count)
        assertEquals(setOf("Alpha", "Beta"), listed().map { it.name }.toSet())
        val damaged = File(rootDir(), BackupLayout.INDEX).apply { writeText("{\"format\":1,") }
        assertEquals(2, inspect().source.count)
        assertTrue(damaged.isFile)
    }

    @Test
    fun foldersTheIndexLeavesOutAreListedToo() {
        val only = index().copy(artworks = index().artworks.filter { it.id == a })
        File(rootDir(), BackupLayout.INDEX).writeBytes(BackupIndex.encode(only))
        assertEquals(setOf(a, b), listed().map { it.id }.toSet())
    }

    @Test
    fun aFolderWithOnlyTheNewFileUsesIt() {
        val p = File(remote(a), ProjectFormat.PROJECT_FILE)
        assertTrue(p.renameTo(File(remote(a), ProjectFormat.PROJECT_FILE + BackupLayout.NEW_SUFFIX)))
        File(rootDir(), BackupLayout.INDEX).delete() // the scan reads the folder's own file
        assertEquals("Alpha", listed().single { it.id == a }.name)
        val src = inspect().source
        assertEquals(listOf(a), BackupRestore.restore(src.tree, src.root!!, listOf(a), projects))
        assertEquals("Alpha", ProjectFormat.read(local(a)).name)
    }

    @Test
    fun aDamagedProjectFileFallsBackToTheNewFile() {
        val p = File(remote(a), ProjectFormat.PROJECT_FILE)
        p.copyTo(File(remote(a), ProjectFormat.PROJECT_FILE + BackupLayout.NEW_SUFFIX))
        p.writeText("{\"id\":")
        val src = inspect().source
        assertEquals(listOf(a), BackupRestore.restore(src.tree, src.root!!, listOf(a), projects))
        assertEquals("Alpha", ProjectFormat.read(local(a)).name)
    }

    @Test
    fun bothReadableTheProjectFileWins() {
        val p = File(remote(a), ProjectFormat.PROJECT_FILE)
        val dto = ProjectFormat.read(remote(a))
        val other = File(storage, "other").apply { mkdirs() }
        ProjectFormat.write(other, dto.copy(name = "Older"))
        File(other, ProjectFormat.PROJECT_FILE).copyTo(File(remote(a), ProjectFormat.PROJECT_FILE + BackupLayout.NEW_SUFFIX))
        assertTrue(p.isFile)
        val src = inspect().source
        BackupRestore.restore(src.tree, src.root!!, listOf(a), projects)
        assertEquals("Alpha", ProjectFormat.read(local(a)).name)
    }
}
