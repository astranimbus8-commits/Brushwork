package com.brushwork.paint.qa19

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.gallery.GalleryScreen
import com.brushwork.paint.v19.a8.BackupKit
import com.brushwork.paint.v19.a8.FakeDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A8 (R16; I27): the cases of the backup UI tests ([Qa19BackupUiTest] at 392 dp,
 * [Qa19BackupNarrowUiTest] at 360 dp, each at font scale 1 and at [Qa19Ui.FONT_SCALES]). A phone
 * with an artwork and no backup folder, where an earlier install left a backup in
 * "Documents/Brushwork": the gallery's notice shows the hint above its 40 dp buttons (one line at
 * font scale 1), "Choose backup folder" opens the picker at Documents, the picked folder offers
 * the restore and writes nothing until "Restore them", the list restores the old artwork, and the
 * folder is set. "Later" hides the notice.
 */
internal object Qa19Backup {
    private const val OLD = "From the old phone"
    private const val TODAY = "Painted today"

    /** The tappable box around the text [label] (in window pixels). */
    private fun button(label: String): Rect {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(60)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return (n ?: e.node).boundsInWindow
    }

    private fun box(label: String): Rect =
        (Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(60)}")).bounds

    /** The old install's backup, then a new install with one artwork of its own. Returns the old artwork's id. */
    private fun phone(): String {
        BackupKit.freshInstall()
        val old = BackupKit.newArtwork(OLD, BackupKit.BLUE)
        BackupKit.choose()
        BackupKit.reinstall()
        BackupKit.newArtwork(TODAY)
        FakeDocumentsProvider.resetCounts()
        return old
    }

    /**
     * The gallery cards' texts, skipped by the overflow checks: the artworks' names and the
     * cards' size and date lines are one ellipsized line by design (GalleryScreen's card, not a
     * v1.9 control), and at font scale 2 on the 360 dp phone they are cut.
     */
    private val names: (String) -> Boolean = { it == OLD || it == TODAY || it.endsWith(" px") || it == "Just now" }

    /** The notice, the picker, the offer and the restore list at the device's [fontScale] on a [widthDp] phone. */
    fun chooseAndRestore(h: Qa19Harness, fontScale: Float, widthDp: Float) {
        val where = "$widthDp dp at font scale $fontScale"
        val old = phone()
        val a = h.show(fontScale) { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        val density = a.resources.displayMetrics.density
        assertEquals("the phone's width", widthDp, a.window.decorView.width / density, 1f)
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(V19Tags.BACKUP_BANNER) != null && Qa19Ui.has(TODAY) }
        assertTrue("the no-folder notice, $where", Qa19Ui.has(BackupLabels19.BANNER_NOT_SET, exact = true))
        val choose = button(BackupLabels19.CHOOSE_FOLDER)
        val later = button(BackupLabels19.LATER)
        for ((label, r) in listOf(BackupLabels19.CHOOSE_FOLDER to choose, BackupLabels19.LATER to later)) {
            assertTrue("$label is at least 40 dp tall, $where: ${r.height / density} dp", r.height / density >= 39.5f)
            assertTrue("$label inside the screen, $where: $r", r.left >= 0f && r.right <= a.window.decorView.width)
        }
        if (fontScale == 1f) assertEquals("the buttons on one line at font scale 1, $where", choose.top, later.top, 1f)
        assertTrue("the hint above the buttons, $where", box(BackupLabels19.PICK_HINT).bottom <= choose.top + 1f)
        Qa19Ui.assertNoTextOverflow("the notice, $where", except = names)

        // The picker opens at Documents.
        Qa19Ui.click(BackupLabels19.CHOOSE_FOLDER, exact = true)
        Smoke.pumpUntil(5_000) { shadowOf(a).peekNextStartedActivityForResult() != null }
        val started = shadowOf(a).nextStartedActivityForResult ?: throw AssertionError("no folder picker, $where")
        assertEquals(Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
        @Suppress("DEPRECATION")
        assertEquals(BackupFlow.INITIAL_URI, started.intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        shadowOf(a).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(FakeDocumentsProvider.tree(BackupKit.PICKED)))

        // The offer; nothing is written until the choice.
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(V19Tags.RESTORE_OFFER) != null }
        assertTrue("the offer, $where; shown: ${Qa19Ui.shown().take(40)}", Qa19Ui.has(String.format(BackupLabels19.FOUND, 1), exact = true))
        assertTrue(where, Qa19Ui.has(BackupLabels19.RESTORE_THEM, exact = true))
        assertTrue(where, Qa19Ui.has(BackupLabels19.BACK_UP_HERE, exact = true))
        BackupKit.idle()
        assertEquals("nothing written while the offer shows, $where", emptyList<String>(), FakeDocumentsProvider.log.toList())
        assertEquals(where, BackupStatus.NotSet, BackupService.status.value)
        Qa19Ui.assertNoTextOverflow("the offer, $where", except = names)

        // "Restore them": the list, the old artwork checked, the settings box on.
        Qa19Ui.click(BackupLabels19.RESTORE_THEM, exact = true)
        val confirm = String.format(BackupLabels19.RESTORE_CONFIRM, 1)
        Smoke.pumpUntil(10_000) { Qa19Ui.has(confirm, exact = true) }
        assertTrue(where, Qa19Ui.has(BackupLabels19.RESTORE_TITLE, exact = true))
        assertTrue(where, Qa19Ui.has(OLD, exact = true))
        assertTrue(where, Qa19Ui.has(BackupLabels19.ALSO_SETTINGS, exact = true))
        assertEquals("still nothing written, $where", emptyList<String>(), FakeDocumentsProvider.log.toList())
        Qa19Ui.assertNoTextOverflow("the restore list, $where", except = names)

        Qa19Ui.click(confirm, exact = true)
        Smoke.pumpUntil(20_000) { BackupService.folderSet && BackupKit.local(old).isDirectory }
        BackupKit.idle()
        Smoke.pumpUntil(10_000) { Qa19Ui.has(OLD) && Qa19Ui.tagged(V19Tags.BACKUP_BANNER) == null }
        assertTrue("restored into the gallery, $where", Qa19Ui.has(OLD))
        assertNull("no notice once the folder is set, $where", Qa19Ui.tagged(V19Tags.BACKUP_BANNER))
        assertTrue("backing up, $where: ${BackupService.status.value}", BackupService.status.value is BackupStatus.Ok)
        assertEquals(where, setOf(old, BackupKit.repo.let { r -> kotlinx.coroutines.runBlocking { r.list() } }.single { it.name == TODAY }.id),
            BackupKit.index().artworks.map { it.id }.toSet())
    }

    /** "Later" hides the notice, and it stays hidden at the next start. */
    fun later(h: Qa19Harness, widthDp: Float) {
        val where = "$widthDp dp"
        phone()
        h.show { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(V19Tags.BACKUP_BANNER) != null }
        Qa19Ui.click(BackupLabels19.LATER, exact = true)
        Smoke.pumpUntil(5_000) { Qa19Ui.tagged(V19Tags.BACKUP_BANNER) == null }
        assertNull("hidden, $where", Qa19Ui.tagged(V19Tags.BACKUP_BANNER))
        BackupKit.idle()
        BackupService.startWith(BackupKit.app)
        h.show { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        Smoke.pumpUntil(2_000) { false }
        assertNull("still hidden after a restart, $where", Qa19Ui.tagged(V19Tags.BACKUP_BANNER))
        BackupKit.now += 7 * BackupKit.DAY
        h.show { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(V19Tags.BACKUP_BANNER) != null }
        assertTrue("back after 7 days, $where", Qa19Ui.has(BackupLabels19.BANNER_NOT_SET, exact = true))
    }
}
