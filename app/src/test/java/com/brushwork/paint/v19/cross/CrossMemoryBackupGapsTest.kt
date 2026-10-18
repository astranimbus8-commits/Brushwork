package com.brushwork.paint.v19.cross

import android.content.ContentResolver
import android.content.Context
import android.content.ContextWrapper
import android.content.Intent
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSettings
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.editor.EditorPrefs
import com.brushwork.paint.v19.a8.BackupKit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.util.Collections
import java.util.concurrent.atomic.AtomicReference

/**
 * v1.9 Gate 2 item M4 (A8's known gaps):
 *
 * - (a, device row Q22) the app start's backup reads (the state file, the gallery's folder, the
 *   folder grants) run on the backup's worker, not on the main thread, and end where the start on
 *   the calling thread ends;
 * - (b) a settings restore reaches an open editor: its [EditorPrefs] follow the restored settings
 *   at once (no restart), the compositor's "Safe compositing" switch too;
 * - (c) the New canvas dialog's "Color…" background is a preference (`newCanvas.customColor`,
 *   pale cream the first time): kept in the settings, backed up and restored with them, never in
 *   an artwork's files. (The dialog's use of it: [com.brushwork.paint.qa19.Qa19NewCanvas.dialog].)
 *
 * The restore here brings back the settings alone (no artwork chosen), which the service does;
 * the restore list's button still needs an artwork (d, deferred).
 */
@RunWith(RobolectricTestRunner::class)
class CrossMemoryBackupGapsTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private var safeCompositing = false

    @Before
    fun setUp() {
        safeCompositing = AdjustmentStage.safeCompositing
        BackupKit.freshInstall()
    }

    @After
    fun tearDown() {
        BackupKit.idle()
        AdjustmentStage.safeCompositing = safeCompositing
    }

    /** A context whose files, no-backup files and content resolver record the threads that ask for them. */
    private class Recording(base: Context) : ContextWrapper(base) {
        val threads: MutableList<String> = Collections.synchronizedList(ArrayList())
        override fun getApplicationContext(): Context = this
        override fun getNoBackupFilesDir(): File = super.getNoBackupFilesDir().also { threads += Thread.currentThread().name }
        override fun getFilesDir(): File = super.getFilesDir().also { threads += Thread.currentThread().name }
        override fun getContentResolver(): ContentResolver = super.getContentResolver().also { threads += Thread.currentThread().name }
    }

    /** The app start (`BrushworkApp.onCreate`, the main thread) over what the install holds now. */
    private fun startAndRecord(where: String): Recording {
        assertSame("the test runs on the main thread", Looper.getMainLooper(), Looper.myLooper())
        val main = Thread.currentThread().name
        val r = Recording(app)
        BackupService.startInBackground(r)
        assertFalse("$where: the start read nothing on the main thread", r.threads.contains(main))
        BackupKit.idle()
        assertTrue("$where: the start read the state", r.threads.isNotEmpty())
        assertEquals("$where: on the backup's worker", setOf("brushwork-backup"), r.threads.toSet())
        return r
    }

    @Test
    fun theAppStartReadsTheBackupStateOnTheWorker() {
        // No folder yet, one artwork: "not set", and the gallery has an artwork (the notice may show).
        BackupKit.newArtwork("Kept")
        startAndRecord("no folder")
        assertEquals(BackupStatus.NotSet, BackupService.status.value)
        assertTrue(BackupService.hasArtworks.value)
        BackupService.startWith(app)
        assertEquals("as the start on this thread", BackupStatus.NotSet, BackupService.status.value)
        assertTrue(BackupService.hasArtworks.value)

        // A folder whose grant is gone: unavailable, as the start on this thread says.
        val pick = BackupKit.choose()
        BackupKit.dropGrants()
        startAndRecord("no grant")
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
        BackupService.startWith(app)
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
        val id = BackupKit.newArtwork("While away")
        BackupKit.idle()
        assertFalse("not backed up while unavailable", BackupKit.remote(id).exists())

        // The grant is back: the start finds the folder usable, and its catch-up mirrors what was saved meanwhile.
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        app.contentResolver.takePersistableUriPermission(pick.tree, flags)
        startAndRecord("a folder")
        val s = BackupService.status.value
        assertTrue("usable: $s", s is BackupStatus.Ok)
        assertEquals(pick.folderName, (s as BackupStatus.Ok).folderName)
        assertEquals("the catch-up mirrored it", BackupKit.expectedFiles(BackupKit.local(id)), BackupKit.names(BackupKit.remote(id)))
        BackupService.startWith(app)
        assertTrue("as the start on this thread", BackupService.status.value is BackupStatus.Ok)
    }

    @Test
    fun aSettingsRestoreReachesAnOpenEditorAndKeepsTheCanvasColor() {
        val first = AppSettings(app)
        assertEquals("(c) the first time: the dialog's pale cream", 0xFFF3E7D3.toInt(), first.newCanvasCustomColor)
        // The settings the backup keeps (none of them the defaults).
        val cyan = 0xFF2090A0.toInt()
        first.twoFingerUndo = false
        first.threeFingerRedo = false
        first.stylusOnlyDrawing = true
        first.leftHanded = true
        first.longPressEyedropper = false
        first.autosaveSeconds = 120
        first.safeCompositing = true
        first.newCanvasCustomColor = cyan
        assertEquals("(c) remembered", cyan, AppSettings(app).newCanvasCustomColor)
        val id = BackupKit.newArtwork("Settings")
        BackupKit.choose()
        val kept = File(BackupKit.rootDir(), BackupLayout.SETTINGS)
        assertTrue("the backup holds the settings", kept.isFile)
        assertTrue("(c) with the color", kept.readText().contains("newCanvas.customColor"))
        val artworkFiles = BackupKit.local(id).listFiles().orEmpty().associate { it.name to it.readBytes().toList() }
        for (f in BackupKit.local(id).listFiles().orEmpty()) {
            assertFalse("(c) not project data: ${f.name}", f.readBytes().toString(Charsets.ISO_8859_1).contains("customColor"))
        }

        // An open editor; the settings change in it (no save: the backup keeps the ones above).
        val editor = EditorPrefs(AppSettings(app))
        assertTrue(editor.leftHanded)
        editor.twoFingerUndo = true
        editor.threeFingerRedo = true
        editor.stylusOnlyDrawing = false
        editor.leftHanded = false
        editor.longPressEyedropper = true
        editor.autosaveSeconds = 30
        editor.safeCompositing = false
        AppSettings(app).newCanvasCustomColor = 0xFFC03020.toInt()
        assertFalse(AdjustmentStage.safeCompositing)

        // (b) The settings alone come back (no artwork chosen), off the main thread as from the
        // Backup section (its coroutine; the main thread runs meanwhile).
        val source = BackupKit.source()
        val restored = AtomicReference<Result<Int>>()
        Thread { restored.set(runCatching { runBlocking { BackupService.restore(app, source, emptyList(), alsoSettings = true) } }) }.start()
        BackupKit.awaitMain("the restore") { restored.get() != null }
        shadowOf(Looper.getMainLooper()).idle()
        assertEquals("no artwork", 0, restored.get().getOrThrow())
        assertFalse(editor.twoFingerUndo)
        assertFalse(editor.threeFingerRedo)
        assertTrue(editor.stylusOnlyDrawing)
        assertTrue(editor.leftHanded)
        assertFalse(editor.longPressEyedropper)
        assertEquals(120, editor.autosaveSeconds)
        assertTrue("the compositor's switch", editor.safeCompositing && AdjustmentStage.safeCompositing)
        assertEquals("(c) restored with the settings", cyan, AppSettings(app).newCanvasCustomColor)
        assertTrue(app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).contains("newCanvas.customColor"))
        assertEquals("the artwork untouched", artworkFiles, BackupKit.local(id).listFiles().orEmpty().associate { it.name to it.readBytes().toList() })

        // The editor's own changes still write through, and follow.
        editor.leftHanded = false
        shadowOf(Looper.getMainLooper()).idle()
        assertFalse(editor.leftHanded)
        assertFalse(AppSettings(app).leftHanded)
    }
}
