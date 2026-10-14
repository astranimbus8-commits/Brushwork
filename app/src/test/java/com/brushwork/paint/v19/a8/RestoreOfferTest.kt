package com.brushwork.paint.v19.a8

import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.v19.a8.BackupKit.PICKED
import com.brushwork.paint.v19.a8.BackupKit.ROOT
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.awaitMain
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.idle
import com.brushwork.paint.v19.a8.BackupKit.index
import com.brushwork.paint.v19.a8.BackupKit.local
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.remote
import com.brushwork.paint.v19.a8.BackupKit.storage
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
 * v1.9 A8 (R16 (a)): a picked folder that already holds a backup offers the restore and changes
 * nothing (no grant kept, no folder set, nothing written or deleted) until the user chooses:
 * [com.brushwork.paint.ui.common.BackupLabels19.RESTORE_THEM] opens the list and sets the folder
 * when it closes; [com.brushwork.paint.ui.common.BackupLabels19.BACK_UP_HERE] sets it at once and
 * keeps the other install's artworks in the backup. A folder without a backup is set at once; a
 * storage root, Download or no answer is refused.
 */
@RunWith(RobolectricTestRunner::class)
class RestoreOfferTest {
    private lateinit var a: String
    private lateinit var b: String

    @Before
    fun setUp() {
        BackupKit.freshInstall()
        a = newArtwork("Alpha")
        b = newArtwork("Beta")
        choose()
        BackupKit.reinstall()
        FakeDocumentsProvider.resetCounts()
    }

    private fun pick(path: String?, purpose: BackupFlow.Purpose = BackupFlow.Purpose.FOLDER) {
        BackupFlow.launched(purpose)
        BackupFlow.onPicked(app, path?.let { FakeDocumentsProvider.tree(it) })
    }

    private fun offer(): BackupFlow.Dialog.Offer {
        awaitMain("the offer") { BackupFlow.dialog != null }
        return BackupFlow.dialog as? BackupFlow.Dialog.Offer ?: throw AssertionError("not the offer: ${BackupFlow.dialog}")
    }

    private fun assertNothingChanged(where: String) {
        idle()
        assertEquals("nothing written or deleted, $where", emptyList<String>(), FakeDocumentsProvider.log.toList())
        assertEquals(where, BackupStatus.NotSet, BackupService.status.value)
        assertFalse(where, BackupService.folderSet)
        assertTrue("no grant kept, $where", app.contentResolver.persistedUriPermissions.isEmpty())
        assertNull("no folder stored, $where", BackupKit.state().tree)
    }

    @Test
    fun aPickedFolderWithAnIndexOffersTheRestoreAndWritesNothing() {
        val c = newArtwork("Gamma")
        pick(PICKED)
        val offer = offer()
        assertEquals(2, offer.pick.source.count)
        assertNothingChanged("while the offer shows")

        // Dismissed: still nothing.
        BackupFlow.dialog = null
        assertNothingChanged("after a dismissal")

        // "Back up here without restoring": the folder is set, this install's artwork is backed
        // up, the other install's stay in the backup (and in its index), none comes back here.
        pick(PICKED)
        BackupFlow.backUpHere(app, offer())
        awaitMain("the folder set") { BackupService.folderSet }
        idle()
        assertTrue(BackupService.status.value is BackupStatus.Ok)
        assertEquals(setOf(a, b, c), index().artworks.map { it.id }.toSet())
        assertTrue(remote(a).isDirectory)
        assertTrue(remote(c).isDirectory)
        assertFalse(local(a).exists())
        assertFalse(local(b).exists())
        assertEquals(1, app.contentResolver.persistedUriPermissions.size)
    }

    @Test
    fun restoreThemOpensTheListAndSetsTheFolderWhenItCloses() {
        pick(PICKED)
        BackupFlow.restoreOffered(offer())
        val list = BackupFlow.dialog as BackupFlow.Dialog.Restore
        assertTrue(list.commitAfter != null)
        assertNothingChanged("while the list shows")
        BackupFlow.closeRestore(app, list, listOf(a, b), alsoSettings = false)
        awaitMain("restored and set") { BackupService.folderSet && local(a).exists() && local(b).exists() }
        idle()
        assertNull(BackupFlow.dialog)
        assertTrue(BackupService.status.value is BackupStatus.Ok)
        assertEquals(setOf(a, b), index().artworks.map { it.id }.toSet())
    }

    @Test
    fun aFolderWithoutABackupIsSetAtOnce() {
        File(storage, "Documents/New").mkdirs()
        newArtwork("Gamma")
        pick("Documents/New")
        awaitMain("the folder set") { BackupService.folderSet }
        idle()
        assertNull("no offer", BackupFlow.dialog)
        assertTrue(File(storage, "Documents/New/Brushwork backup/brushwork-backup.json").isFile)
    }

    @Test
    fun aRootDownloadOrNoAnswerIsRefused() {
        File(storage, "Download").mkdirs()
        for (path in listOf("", "Download", null)) {
            BackupFlow.reset()
            pick(path)
            awaitMain("refused $path") { BackupFlow.pickFailed }
            assertNull(BackupFlow.dialog)
            assertNothingChanged("picked $path")
        }
        // A refused pick asks again; the next good one clears it.
        pick(PICKED)
        offer()
        assertFalse(BackupFlow.pickFailed)
    }

    @Test
    fun pickingTheSameFolderAgainAfterTheGrantWentOffersNothing() {
        BackupKit.freshInstall()
        newArtwork("Alpha")
        choose()
        BackupKit.dropGrants()
        BackupService.startWith(app)
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
        pick(PICKED)
        awaitMain("set again") { BackupService.status.value !is BackupStatus.Unavailable }
        idle()
        assertNull("the same backup: no offer", BackupFlow.dialog)
        assertTrue(BackupService.status.value is BackupStatus.Ok)
        assertTrue(File(storage, "$ROOT/brushwork-backup.json").isFile)
    }
}
