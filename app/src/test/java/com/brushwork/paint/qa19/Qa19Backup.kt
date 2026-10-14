package com.brushwork.paint.qa19

import android.app.Activity
import android.content.Intent
import android.net.Uri
import android.provider.DocumentsContract
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.AppSettings
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.storage.backup.BackupLayout
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.EditorPrefs
import com.brushwork.paint.ui.editor.EditorSettingsDialog
import com.brushwork.paint.ui.gallery.GalleryScreen
import com.brushwork.paint.v19.a8.BackupKit
import com.brushwork.paint.v19.a8.FakeDocumentsProvider
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowActivity

/**
 * v1.9 A8 (R16; I27): the cases of the backup UI tests ([Qa19BackupUiTest] at 392 dp,
 * [Qa19BackupNarrowUiTest] at 360 dp, each at font scale 1 and at [Qa19Ui.FONT_SCALES]). A phone
 * with an artwork and no backup folder, where an earlier install left a backup in
 * "Documents/Brushwork": the gallery's notice shows the hint above its 40 dp buttons (one line at
 * font scale 1), "Choose backup folder" opens the picker at Documents, the picked folder offers
 * the restore and writes nothing until "Restore them", the list restores the old artwork, and the
 * folder is set. "Later" hides the notice.
 *
 * v1.9 A8 review: the other three ways in. The empty gallery's "Restore artworks…" (a reinstalled
 * app: the picker, then the list at once), the editor Settings' Backup section (the folder, "Back
 * up now", "Restore artworks…") and the gallery's ⋮ menu.
 */
internal object Qa19Backup {
    private const val OLD = "From the old phone"
    private const val TODAY = "Painted today"

    /** The content description of GalleryScreen's ⋮ button (frozen; it has no label constant). */
    private const val MORE = "More options"

    /** The tappable box around the text [label] (in window pixels). */
    private fun button(label: String): Rect {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(60)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return (n ?: e.node).boundsInWindow
    }

    /** The laid-out height (pixels, not clipped by a scroll viewport) of the tappable box around the text [label]. */
    private fun tappableHeight(label: String): Int {
        val e = Qa19Ui.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${Qa19Ui.shown().take(60)}")
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return (n ?: e.node).size.height
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

    /** The folder picker [a] started (it opens at Documents). */
    private fun picker(a: Activity, where: String): ShadowActivity.IntentForResult {
        Smoke.pumpUntil(5_000) { shadowOf(a).peekNextStartedActivityForResult() != null }
        val started = shadowOf(a).nextStartedActivityForResult ?: throw AssertionError("no folder picker, $where")
        assertEquals(where, Intent.ACTION_OPEN_DOCUMENT_TREE, started.intent.action)
        @Suppress("DEPRECATION")
        assertEquals(where, BackupFlow.INITIAL_URI, started.intent.getParcelableExtra<Uri>(DocumentsContract.EXTRA_INITIAL_URI))
        return started
    }

    /** The picker returns [BackupKit.PICKED]. */
    private fun pickDocumentsBrushwork(a: Activity, started: ShadowActivity.IntentForResult) =
        shadowOf(a).receiveResult(started.intent, Activity.RESULT_OK, Intent().setData(FakeDocumentsProvider.tree(BackupKit.PICKED)))

    /** Whether the backup's index was written since the log was reset. */
    private fun indexWritten(): Boolean =
        FakeDocumentsProvider.log.toList().any { it.startsWith("write ${BackupKit.ROOT}/${BackupLayout.INDEX} ") }

    /**
     * A reinstalled app with an empty gallery: "Restore artworks…" under the empty-gallery text
     * (inside the screen, no notice: there is nothing to lose yet) opens the picker; the picked
     * folder's list shows at once (no offer) and writes nothing; restoring brings the old artwork
     * back and the folder becomes the backup folder.
     */
    fun emptyGalleryRestore(h: Qa19Harness, fontScale: Float, widthDp: Float) {
        val where = "the empty gallery, $widthDp dp at font scale $fontScale"
        BackupKit.freshInstall()
        val old = BackupKit.newArtwork(OLD, BackupKit.BLUE)
        BackupKit.choose()
        BackupKit.reinstall()
        FakeDocumentsProvider.resetCounts()
        val a = h.show(fontScale) { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        Smoke.pumpUntil(10_000) { Qa19Ui.has(BackupLabels19.RESTORE, exact = true) }
        assertTrue("the restore button, $where; shown: ${Qa19Ui.shown().take(40)}", Qa19Ui.has(BackupLabels19.RESTORE, exact = true))
        assertNull("no notice without artworks, $where", Qa19Ui.tagged(V19Tags.BACKUP_BANNER))
        val r = button(BackupLabels19.RESTORE)
        assertTrue("the restore button inside the screen, $where: $r", r.left >= 0f && r.right <= a.window.decorView.width)
        // The button's text only: the empty-gallery texts above it are GalleryScreen's (frozen).
        Qa19Ui.assertNoTextOverflow(where, except = { it != BackupLabels19.RESTORE })

        Qa19Ui.click(BackupLabels19.RESTORE, exact = true)
        pickDocumentsBrushwork(a, picker(a, where))

        val confirm = String.format(BackupLabels19.RESTORE_CONFIRM, 1)
        Smoke.pumpUntil(10_000) { Qa19Ui.has(confirm, exact = true) }
        assertTrue("the list at once, $where; shown: ${Qa19Ui.shown().take(40)}", Qa19Ui.has(BackupLabels19.RESTORE_TITLE, exact = true))
        assertNull("no offer before the list, $where", Qa19Ui.tagged(V19Tags.RESTORE_OFFER))
        assertTrue(where, Qa19Ui.has(OLD, exact = true))
        assertTrue("$confirm is on, $where", SmokeUi.isEnabled(confirm))
        BackupKit.idle()
        assertEquals("nothing written while the list shows, $where", emptyList<String>(), FakeDocumentsProvider.log.toList())
        assertFalse("no folder set yet, $where", BackupService.folderSet)
        val list = setOf(BackupLabels19.RESTORE_TITLE, OLD, BackupLabels19.ALSO_SETTINGS, confirm)
        Qa19Ui.assertNoTextOverflow("the restore list, $where", except = { it !in list })

        Qa19Ui.click(confirm, exact = true)
        Smoke.pumpUntil(20_000) { BackupService.folderSet && BackupKit.local(old).isDirectory }
        BackupKit.idle()
        Smoke.pumpUntil(10_000) { Qa19Ui.has(OLD, exact = true) }
        assertTrue("restored into the gallery, $where", Qa19Ui.has(OLD, exact = true))
        assertTrue("backing up, $where: ${BackupService.status.value}", BackupService.status.value is BackupStatus.Ok)
        assertEquals(where, listOf(old), BackupKit.index().artworks.map { it.id })
    }

    /** The texts of the Settings' Backup section (the overflow checks look at these: the other sections are other areas'). */
    private fun backupText(t: String): Boolean =
        t == BackupLabels19.BACKUP.uppercase() || t.startsWith(BackupLabels19.BACKED_UP.substringBefore("%s")) || t in listOf(
            BackupLabels19.FOLDER, BackupLabels19.NOT_SET, String.format(BackupLabels19.USES, BackupKit.PICKED), BackupLabels19.PICK_HINT,
            BackupLabels19.CHOOSE_FOLDER, BackupLabels19.CHANGE_FOLDER, BackupLabels19.NOW, BackupLabels19.RESTORE,
        )

    /**
     * The editor Settings' Backup section (the real dialog): no folder, then an empty folder
     * picked (no offer: it is set at once), "Back up now" and "Restore artworks…" (the list, the
     * gallery's artwork marked "Already in the gallery").
     */
    fun settings(h: Qa19Harness, fontScale: Float, widthDp: Float) {
        val where = "Settings, $widthDp dp at font scale $fontScale"
        BackupKit.freshInstall()
        BackupKit.newArtwork(TODAY)
        FakeDocumentsProvider.resetCounts()
        val a = h.show(fontScale) { EditorSettingsDialog(EditorPrefs(AppSettings(BackupKit.app)), onDismiss = {}) }
        val density = a.resources.displayMetrics.density
        Smoke.pumpUntil(10_000) { Qa19Ui.has(BackupLabels19.FOLDER, exact = true) }
        val before = listOf(
            BackupLabels19.BACKUP.uppercase(), BackupLabels19.FOLDER, BackupLabels19.NOT_SET, BackupLabels19.PICK_HINT,
            BackupLabels19.CHOOSE_FOLDER, BackupLabels19.NOW, BackupLabels19.RESTORE,
        )
        for (label in before) assertTrue("\"$label\", $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(label, exact = true))
        assertFalse("${BackupLabels19.NOW} is off without a folder, $where", SmokeUi.isEnabled(BackupLabels19.NOW))
        // Laid-out heights: the section sits below the dialog's fold, where window bounds are clipped.
        val choose = tappableHeight(BackupLabels19.CHOOSE_FOLDER) / density
        assertTrue("${BackupLabels19.CHOOSE_FOLDER} is at least 40 dp tall, $where: $choose dp", choose >= 39.5f)
        for (label in listOf(BackupLabels19.NOW, BackupLabels19.RESTORE)) {
            val row = tappableHeight(label) / density
            assertTrue("$label is a 48 dp row, $where: $row dp", row >= 47.5f)
        }
        Qa19Ui.assertNoTextOverflow(where, except = { !backupText(it) })

        // An empty folder: no offer, it is the backup folder at once.
        Qa19Ui.click(BackupLabels19.CHOOSE_FOLDER, exact = true)
        pickDocumentsBrushwork(a, picker(a, where))
        Smoke.pumpUntil(20_000) { BackupService.folderSet && BackupService.status.value is BackupStatus.Ok }
        BackupKit.idle()
        val uses = String.format(BackupLabels19.USES, BackupKit.PICKED)
        val backedUp = BackupLabels19.BACKED_UP.substringBefore("%s")
        Smoke.pumpUntil(10_000) { Qa19Ui.has(uses, exact = true) && Qa19Ui.shown().any { it.startsWith(backedUp) } }
        assertNull("no offer for an empty folder, $where", Qa19Ui.tagged(V19Tags.RESTORE_OFFER))
        assertTrue("\"$uses\", $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(uses, exact = true))
        assertTrue(where, Qa19Ui.has(BackupLabels19.CHANGE_FOLDER, exact = true))
        assertFalse(where, Qa19Ui.has(BackupLabels19.CHOOSE_FOLDER, exact = true))
        assertTrue("when it last backed up, $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.shown().any { it.startsWith(backedUp) })
        assertTrue("${BackupLabels19.NOW} is on with a folder, $where", SmokeUi.isEnabled(BackupLabels19.NOW))
        Qa19Ui.assertNoTextOverflow(where, except = { !backupText(it) })

        // "Back up now": a whole run again (the index is written).
        FakeDocumentsProvider.resetCounts()
        Qa19Ui.click(BackupLabels19.NOW, exact = true)
        BackupKit.idle()
        assertTrue("backed up again, $where: ${FakeDocumentsProvider.log.toList()}", indexWritten())

        // "Restore artworks…": the folder's list; the gallery's own artwork is already there.
        Qa19Ui.click(BackupLabels19.RESTORE, exact = true)
        val none = String.format(BackupLabels19.RESTORE_CONFIRM, 0)
        Smoke.pumpUntil(10_000) { Qa19Ui.has(none, exact = true) && Qa19Ui.has(BackupLabels19.ALREADY, exact = true) }
        assertTrue("the list, $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(BackupLabels19.RESTORE_TITLE, exact = true))
        assertTrue(where, Qa19Ui.has(TODAY, exact = true))
        assertTrue(where, Qa19Ui.has(BackupLabels19.ALREADY, exact = true))
        assertFalse("nothing to restore, $where", SmokeUi.isEnabled(none))
        Qa19Ui.assertNoTextOverflow("the restore list, $where")
    }

    /**
     * The gallery's ⋮ menu with a folder set: its three items; "Back up now" closes the menu and
     * backs up; "Backup folder…" opens the picker (the same folder again: no offer, still set);
     * "Restore artworks…" opens the list.
     */
    fun menu(h: Qa19Harness, widthDp: Float) {
        val where = "the gallery's menu, $widthDp dp"
        BackupKit.freshInstall()
        BackupKit.newArtwork(TODAY)
        BackupKit.choose()
        FakeDocumentsProvider.resetCounts()
        val a = h.show { GalleryScreen(BackupKit.repo, onOpenProject = {}) }
        Smoke.pumpUntil(10_000) { Qa19Ui.has(TODAY, exact = true) }
        assertNull("no notice with a folder set, $where", Qa19Ui.tagged(V19Tags.BACKUP_BANNER))
        fun open() {
            Qa19Ui.click(MORE, exact = true)
            Smoke.pumpUntil(5_000) { Qa19Ui.has(BackupLabels19.FOLDER, exact = true) }
        }
        open()
        for (label in listOf(BackupLabels19.FOLDER, BackupLabels19.NOW, BackupLabels19.RESTORE)) {
            assertTrue("\"$label\", $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(label, exact = true))
        }
        assertTrue("${BackupLabels19.NOW} is on with a folder, $where", SmokeUi.isEnabled(BackupLabels19.NOW))
        Qa19Ui.assertNoTextOverflow(where, except = names)

        Qa19Ui.click(BackupLabels19.NOW, exact = true)
        Smoke.pumpUntil(5_000) { !Qa19Ui.has(BackupLabels19.FOLDER, exact = true) }
        assertFalse("the menu closed, $where", Qa19Ui.has(BackupLabels19.FOLDER, exact = true))
        BackupKit.idle()
        assertTrue("backed up again, $where: ${FakeDocumentsProvider.log.toList()}", indexWritten())

        open()
        Qa19Ui.click(BackupLabels19.FOLDER, exact = true)
        pickDocumentsBrushwork(a, picker(a, where))
        Qa19Ui.settle()
        BackupKit.idle()
        Qa19Ui.settle()
        assertNull("no offer for the backup's own folder, $where", Qa19Ui.tagged(V19Tags.RESTORE_OFFER))
        assertNull(where, BackupFlow.dialog)
        assertTrue("still set, $where", BackupService.folderSet)
        assertTrue("backing up, $where: ${BackupService.status.value}", BackupService.status.value is BackupStatus.Ok)

        open()
        Qa19Ui.click(BackupLabels19.RESTORE, exact = true)
        Smoke.pumpUntil(10_000) { Qa19Ui.has(BackupLabels19.RESTORE_TITLE, exact = true) && Qa19Ui.has(BackupLabels19.ALREADY, exact = true) }
        assertTrue("the list, $where; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(BackupLabels19.RESTORE_TITLE, exact = true))
        assertTrue(where, Qa19Ui.has(TODAY, exact = true))
    }
}
