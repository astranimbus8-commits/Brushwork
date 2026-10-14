package com.brushwork.paint.v19.a8

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.storage.backup.BackupIndex
import com.brushwork.paint.storage.backup.BackupIndexData
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSource
import com.brushwork.paint.storage.backup.BackupStateStore
import com.brushwork.paint.ui.backup.BackupFlow
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import java.io.File

/**
 * v1.9 A8 (R16): the backup tests' kit. A fake storage ([FakeDocumentsProvider]) over a folder of
 * the app's data, "Documents/Brushwork" as the folder the picker returns, a clock the tests move,
 * and the steps of a user's life with the app: make and paint artworks, choose the backup folder,
 * uninstall and install again ([reinstall]).
 */
internal object BackupKit {
    /** The folder the tests pick, as the storage provider names it. */
    const val PICKED = "Documents/Brushwork"

    /** The backup's root under it. */
    const val ROOT = "$PICKED/${BackupLayout.ROOT_NAME}"

    const val DAY = 24L * 60 * 60 * 1000
    const val MB = 1024L * 1024
    const val T0 = 1_760_000_000_000L
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLUE = 0xFF2050C0.toInt()
    const val RED = 0xFFC02020.toInt()

    val app: Context get() = ApplicationProvider.getApplicationContext()

    /** The time [BackupService.clock] reads. */
    @Volatile var now: Long = T0

    /** The fake storage's folder ("the phone's shared storage"). */
    val storage: File get() = File(app.filesDir.parentFile, "fake-storage")

    val projects: File get() = ProjectRepository.projectsDir(app)

    val repo: ProjectRepository get() = ProjectRepository(app)

    /**
     * A fresh install over an empty shared storage: no artworks, no backup state, no folder
     * grants, the clock at [T0], plenty of free space. Returns the tree of [PICKED].
     */
    fun freshInstall(): Uri {
        BackupService.idle()
        now = T0
        BackupService.clock = { now }
        BackupService.freeBytes = { _, _ -> 64L shl 30 }
        storage.deleteRecursively()
        val tree = FakeDocumentsProvider.install(storage, PICKED)
        wipeApp()
        return tree
    }

    /**
     * Uninstall and install again: the app's artworks, backup state, settings and folder grants
     * are gone; the shared storage stays.
     */
    fun reinstall(clearPrefs: Boolean = true) {
        assertTrue("the backup worker went idle", BackupService.idle(60_000))
        wipeApp(clearPrefs)
    }

    private fun wipeApp(clearPrefs: Boolean = true) {
        projects.deleteRecursively()
        app.noBackupFilesDir.deleteRecursively()
        File(app.filesDir, "fonts").deleteRecursively()
        if (clearPrefs) {
            for (name in listOf("brushwork_settings", "brushwork_brushes", "brushwork_palettes")) {
                app.getSharedPreferences(name, Context.MODE_PRIVATE).edit().clear().commit()
            }
        }
        dropGrants()
        BackupFlow.reset()
        BackupService.startWith(app)
    }

    /** The folder grants go (an uninstall, or the user revoking them). */
    fun dropGrants() {
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        for (p in app.contentResolver.persistedUriPermissions.toList()) app.contentResolver.releasePersistableUriPermission(p.uri, flags)
    }

    /** Waits for the backup worker. */
    fun idle() = assertTrue("the backup worker went idle", BackupService.idle(60_000))

    /** A new 64 x 48 artwork named [name], filled with [color]. */
    fun newArtwork(name: String, color: Int = WHITE): String =
        runBlocking { repo.create(NewCanvasSpec(name, 64, 48, 300f, color, CanvasStart.FILL)) }

    /** Paints the whole first layer of [id] with [color] and saves it (a new revision). */
    fun paint(id: String, color: Int) = runBlocking {
        val r = repo
        val doc = r.load(id)
        doc.layers[0].bitmap.eraseColor(color)
        doc.layers[0].markChanged()
        r.save(doc, null)
        for (l in doc.layers) l.recycleBitmaps()
    }

    /** The top-left pixel of the gallery's artwork [id]'s first layer. */
    fun pixel(id: String): Int = runBlocking {
        val doc = repo.load(id)
        try { doc.layers[0].bitmap.getPixel(0, 0) } finally { for (l in doc.layers) l.recycleBitmaps() }
    }

    /** What the picked [tree] holds (nothing is changed). */
    fun inspect(tree: Uri = FakeDocumentsProvider.tree(PICKED)): BackupService.Pick.Usable = runBlocking {
        BackupService.inspect(app, tree) as? BackupService.Pick.Usable ?: throw AssertionError("$tree was refused")
    }

    /** Chooses [tree] as the backup folder ("Back up here") and waits for the first backup. */
    fun choose(tree: Uri = FakeDocumentsProvider.tree(PICKED)): BackupService.Pick.Usable {
        val pick = inspect(tree)
        runBlocking { BackupService.commit(app, pick) }
        idle()
        return pick
    }

    /** The backup found in the picked [tree]. */
    fun source(tree: Uri = FakeDocumentsProvider.tree(PICKED)): BackupSource = inspect(tree).source

    fun rootDir(): File = File(storage, ROOT)

    fun remote(id: String): File = File(rootDir(), id)

    fun local(id: String): File = File(projects, id)

    /** The backup's index as written. */
    fun index(): BackupIndexData {
        val f = File(rootDir(), BackupLayout.INDEX)
        assertTrue("the index is written", f.isFile)
        return BackupIndex.decode(f.readBytes()) ?: throw AssertionError("the index does not decode: ${f.readText()}")
    }

    /** The files of an artwork folder, by name. */
    fun names(dir: File): Set<String> = dir.list().orEmpty().toSet()

    /** The names an artwork's `project.json` references, plus its project file and thumbnail. */
    fun expectedFiles(dir: File): Set<String> {
        val dto = ProjectFormat.read(dir)
        return ProjectFormat.referencedFiles(dto) + ProjectFormat.PROJECT_FILE + ProjectFormat.THUMB_FILE
    }

    /** Runs the main looper until [cond] holds (the backup flow's coroutines resume there). */
    fun awaitMain(what: String, timeoutMs: Long = 30_000, cond: () -> Boolean) {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (!cond()) {
            if (System.currentTimeMillis() > deadline) throw AssertionError("timed out waiting for $what")
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
    }

    /** The backup state as stored. */
    fun state() = BackupStateStore.read(app).also { assertNotNull(it) }
}
