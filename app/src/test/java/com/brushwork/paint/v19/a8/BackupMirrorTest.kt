package com.brushwork.paint.v19.a8

import android.content.Context
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupMirror
import com.brushwork.paint.storage.backup.BackupRestore
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSettings
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.storage.backup.SafTree
import com.brushwork.paint.v19.a8.BackupKit.PICKED
import com.brushwork.paint.v19.a8.BackupKit.RED
import com.brushwork.paint.v19.a8.BackupKit.ROOT
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.expectedFiles
import com.brushwork.paint.v19.a8.BackupKit.idle
import com.brushwork.paint.v19.a8.BackupKit.index
import com.brushwork.paint.v19.a8.BackupKit.local
import com.brushwork.paint.v19.a8.BackupKit.names
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.paint
import com.brushwork.paint.v19.a8.BackupKit.remote
import com.brushwork.paint.v19.a8.BackupKit.repo
import com.brushwork.paint.v19.a8.BackupKit.rootDir
import com.brushwork.paint.v19.a8.BackupKit.source
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (R16 (b)): the mirror into the backup folder, against a storage provider that does not
 * truncate on "w", never replaces on rename, and (switchable) lacks `FLAG_SUPPORTS_RENAME`:
 * - the first backup copies every artwork, its thumbnail, the index (format 1) and `settings.json`;
 * - `project.json` is replaced in the R16 order (create `.new`, write "wt", delete, rename), or
 *   without rename support: write `.new`, rewrite `project.json` "wt", delete `.new`;
 * - a crash between the delete and the rename leaves `.new`, which a restore reads, and the next
 *   mirror replaces;
 * - each folder is listed with one children query per run; data files are copied once (a rename
 *   copies none, a new revision only its file, and the old revision goes);
 * - saves are conflated per artwork and mirrored off the main thread; a file that vanished
 *   mid-copy runs the artwork again.
 */
@RunWith(RobolectricTestRunner::class)
class BackupMirrorTest {
    private val log get() = FakeDocumentsProvider.log.toList()

    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    private fun assertMirrored(id: String) {
        val here = local(id)
        val there = remote(id)
        assertEquals("the files of $id", expectedFiles(here), names(there))
        for (name in names(there)) assertArrayEquals("$id/$name", File(here, name).readBytes(), File(there, name).readBytes())
    }

    @Test
    fun theFirstBackupCopiesEveryArtworkTheIndexAndTheSettings() {
        app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putInt("v19a8.test", 42).commit()
        val a = newArtwork("Sky")
        val b = newArtwork("Sea", RED)
        choose()
        assertMirrored(a)
        assertMirrored(b)
        val raw = File(rootDir(), BackupLayout.INDEX).readText()
        assertTrue("format 1 first: $raw", raw.startsWith("{\"format\":1"))
        val index = index()
        assertEquals(BackupLayout.APP, index.app)
        assertEquals(setOf(a to "Sky", b to "Sea"), index.artworks.map { it.id to it.name }.toSet())
        for (e in index.artworks) {
            val size = expectedFiles(local(e.id)).sumOf { File(local(e.id), it).length() }
            assertEquals("the size of ${e.name}", size, e.size)
            assertEquals(ProjectFormat.read(local(e.id)).revision, e.revision)
        }
        val settings = BackupSettings.decode(File(rootDir(), BackupLayout.SETTINGS).readBytes())!!
        assertEquals(1, settings.format)
        assertEquals(BackupSettings.PrefValue.IntV(42), settings.settings["v19a8.test"])
        val status = BackupService.status.value as BackupStatus.Ok
        assertEquals(PICKED, status.folderName)
        assertEquals(index.artworks.sumOf { it.size }, status.bytes)
        assertTrue(BackupKit.state().fullDone)

        // Unchanged settings are not written again; changed ones are.
        FakeDocumentsProvider.resetCounts()
        BackupService.backUpNow()
        idle()
        assertTrue("settings.json unchanged: $log", log.none { it.contains(BackupLayout.SETTINGS) })
        app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putInt("v19a8.test", 43).commit()
        BackupService.backUpNow()
        idle()
        assertTrue("settings.json rewritten: $log", log.any { it == "write $ROOT/${BackupLayout.SETTINGS} wt" })
    }

    @Test
    fun projectJsonIsReplacedInTheR16Order() {
        FakeDocumentsProvider.renameClash = FakeDocumentsProvider.RenameClash.FAIL
        val a = newArtwork("Before")
        choose()
        FakeDocumentsProvider.resetCounts()
        runBlocking { repo.rename(a, "After") }
        idle()
        val p = "$ROOT/$a/${ProjectFormat.PROJECT_FILE}"
        assertEquals(
            listOf("create $p.new", "write $p.new wt", "delete $p", "rename $p.new ${ProjectFormat.PROJECT_FILE}"),
            log.filter { it.contains(p) },
        )
        assertMirrored(a)
        assertEquals("After", ProjectFormat.read(remote(a)).name)
        assertTrue("every write truncates: $log", log.filter { it.startsWith("write") }.all { it.endsWith(" wt") })
    }

    @Test
    fun withoutRenameTheProjectFileIsRewrittenAfterTheNewOne() {
        FakeDocumentsProvider.supportsRename = false
        val a = newArtwork("A much longer name than the next one")
        choose()
        FakeDocumentsProvider.resetCounts()
        runBlocking { repo.rename(a, "Short") }
        idle()
        val p = "$ROOT/$a/${ProjectFormat.PROJECT_FILE}"
        assertEquals(
            listOf("create $p.new", "write $p.new wt", "write $p wt", "delete $p.new"),
            log.filter { it.contains(p) },
        )
        // "w" keeps old bytes on this provider: "wt" left no tail of the longer file.
        assertMirrored(a)
        assertEquals("Short", ProjectFormat.read(remote(a)).name)
    }

    @Test
    fun theFakeKeepsATailOnW() {
        // The provider quirk the "wt" writes guard against is real in the fake.
        BackupKit.newArtwork("A")
        choose()
        val tree = SafTree(app.contentResolver, FakeDocumentsProvider.tree(PICKED))
        val root = tree.dir(tree.picked, BackupLayout.ROOT_NAME, create = false)!!
        val doc = tree.child(root, BackupLayout.INDEX)!!
        val before = File(rootDir(), BackupLayout.INDEX).length()
        app.contentResolver.openOutputStream(doc.uri, "w")!!.use { it.write("{}".toByteArray()) }
        assertEquals("w keeps the tail", before, File(rootDir(), BackupLayout.INDEX).length())
    }

    @Test
    fun aCrashBetweenDeleteAndRenameRestoresFromNew() {
        val a = newArtwork("Before")
        choose()
        FakeDocumentsProvider.failNextRename = true
        runBlocking { repo.rename(a, "After") }
        idle()
        val folder = remote(a)
        assertFalse("deleted before the failed rename", File(folder, ProjectFormat.PROJECT_FILE).exists())
        assertTrue(File(folder, ProjectFormat.PROJECT_FILE + BackupLayout.NEW_SUFFIX).isFile)

        val src = source()
        val listed = runBlocking { BackupService.listRestore(app, src) }
        assertTrue("listed", listed.any { it.id == a })
        val elsewhere = File(app.cacheDir, "other-projects").apply { deleteRecursively() }
        assertEquals(listOf(a), BackupRestore.restore(src.tree, src.root!!, listOf(a), elsewhere))
        assertEquals("After", ProjectFormat.read(File(elsewhere, a)).name)

        // The next mirror writes project.json and deletes the leftover.
        BackupService.backUpNow()
        idle()
        assertMirrored(a)
    }

    @Test
    fun oneChildrenQueryPerFolderPerRun() {
        val ids = (1..3).map { newArtwork("A$it") }
        val pick = BackupKit.inspect()
        FakeDocumentsProvider.resetCounts()
        runBlocking { BackupService.commit(app, pick) }
        idle()
        val first = FakeDocumentsProvider.childQueries.mapValues { it.value.get() }
        for ((path, n) in first) assertTrue("the first backup listed $path $n times: $first", n <= 1)

        FakeDocumentsProvider.resetCounts()
        BackupService.backUpNow()
        idle()
        val counts = FakeDocumentsProvider.childQueries.mapValues { it.value.get() }
        for ((path, n) in counts) assertTrue("$path listed $n times: $counts", n <= 1)
        for (id in ids) assertEquals("$id listed once", 1, FakeDocumentsProvider.queriesOf("$ROOT/$id"))
        assertEquals(1, FakeDocumentsProvider.queriesOf(ROOT))
    }

    @Test
    fun dataFilesAreCopiedOnce() {
        val a = newArtwork("A")
        choose()
        val dataFiles = { log.filter { it.startsWith("write $ROOT/$a/") && ProjectFormat.isDataFile(it.substringAfterLast('/').substringBefore(' ')) } }

        FakeDocumentsProvider.resetCounts()
        runBlocking { repo.rename(a, "B") }
        idle()
        assertEquals("a rename copies no data file", emptyList<String>(), dataFiles())

        val old = ProjectFormat.referencedFiles(ProjectFormat.read(local(a)))
        FakeDocumentsProvider.resetCounts()
        paint(a, RED)
        idle()
        val now = ProjectFormat.referencedFiles(ProjectFormat.read(local(a)))
        val fresh = now - old
        assertTrue("the save made a new revision file", fresh.isNotEmpty())
        assertEquals("only the new revision is copied", fresh.map { "write $ROOT/$a/$it wt" }.toSet(), dataFiles().toSet())
        for (gone in old - now) assertTrue("$gone deleted: $log", "delete $ROOT/$a/$gone" in log)
        assertMirrored(a)
    }

    @Test
    fun savesAreConflatedAndMirroredOffTheMainThread() {
        val a = newArtwork("A")
        choose()
        FakeDocumentsProvider.resetCounts()
        repeat(20) { BackupService.onSaved(a) }
        idle()
        val runs = log.count { it == "rename $ROOT/$a/${ProjectFormat.PROJECT_FILE}${BackupLayout.NEW_SUFFIX} ${ProjectFormat.PROJECT_FILE}" }
        assertTrue("20 saves conflated into $runs mirrors", runs in 1..5)
        assertEquals(setOf("brushwork-backup"), FakeDocumentsProvider.writers.toSet())
    }

    @Test
    fun aFileThatVanishesMidCopyRunsTheArtworkAgain() {
        val a = newArtwork("A")
        choose()
        val tree = SafTree(app.contentResolver, FakeDocumentsProvider.tree(PICKED))
        val root = tree.dir(tree.picked, BackupLayout.ROOT_NAME, create = false)!!
        val mirror = BackupMirror(tree, root, BackupKit.projects)
        val name = ProjectFormat.referencedFiles(ProjectFormat.read(local(a))).first()
        File(local(a), name).delete()
        assertEquals(BackupMirror.Result.Again, mirror.mirror(a))
        local(a).deleteRecursively()
        assertEquals(BackupMirror.Result.Gone, mirror.mirror(a))
    }
}
