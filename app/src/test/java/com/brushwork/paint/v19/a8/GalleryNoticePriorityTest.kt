package com.brushwork.paint.v19.a8

import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.gallery.GalleryNotice
import com.brushwork.paint.ui.gallery.NOT_SET_LATER_MILLIS
import com.brushwork.paint.ui.gallery.pickNotice
import com.brushwork.paint.v19.a8.BackupKit.DAY
import com.brushwork.paint.v19.a8.BackupKit.T0
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.idle
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import com.brushwork.paint.v19.a8.BackupKit.repo
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A8 (R9, R16, C9): the gallery's one notice. An unseen crash report comes first, then the
 * backup folder unavailable, then no folder set, then the first backup's progress. "Later" hides
 * the no-folder notice for 7 days, also across app starts (it is kept in the backup's state); the
 * no-folder notice waits for the first artwork; the backup's status follows the folder's grant.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryNoticePriorityTest {
    private val crash = CrashReport("r1", T0, CrashReport.Kind.CRASH, "boom", "trace")

    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    @Test
    fun theOrderOfTheNotices() {
        val running = BackupStatus.Running(2, 5)
        val ok = BackupStatus.Ok(T0, "Documents/Brushwork", 10L)
        for (s in listOf(BackupStatus.Unavailable, BackupStatus.NotSet, running, ok)) {
            assertEquals("a crash first, over $s", GalleryNotice.Crash(crash), pickNotice(crash, s, 0L, T0))
        }
        assertEquals(GalleryNotice.BackupUnavailable, pickNotice(null, BackupStatus.Unavailable, T0, T0))
        assertEquals(GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, 0L, T0))
        assertEquals(GalleryNotice.BackupProgress(2, 5), pickNotice(null, running, 0L, T0))
        assertNull(pickNotice(null, ok, 0L, T0))
    }

    @Test
    fun laterHidesTheNoFolderNoticeForSevenDays() {
        val at = T0
        assertNull(pickNotice(null, BackupStatus.NotSet, at, at))
        assertNull(pickNotice(null, BackupStatus.NotSet, at, at + 6 * DAY))
        assertNull(pickNotice(null, BackupStatus.NotSet, at, at + NOT_SET_LATER_MILLIS - 1))
        assertEquals(GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, at, at + NOT_SET_LATER_MILLIS))
        assertEquals("a clock set back shows it", GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, at, at - 1))
        assertEquals("Later never hides the unavailable folder", GalleryNotice.BackupUnavailable, pickNotice(null, BackupStatus.Unavailable, at, at))
    }

    @Test
    fun laterIsKeptAcrossAppStarts() {
        assertEquals(BackupStatus.NotSet, BackupService.status.value)
        assertEquals(0L, BackupService.notSetLaterAt.value)
        BackupKit.now = T0 + 5
        BackupService.putOffNotice()
        idle()
        assertEquals(T0 + 5, BackupService.notSetLaterAt.value)
        assertEquals(T0 + 5, BackupKit.state().notSetLaterAt)
        BackupService.startWith(app)
        assertEquals("read back at the next start", T0 + 5, BackupService.notSetLaterAt.value)
    }

    @Test
    fun theNoFolderNoticeWaitsForTheFirstArtwork() {
        assertFalse(BackupService.hasArtworks.value)
        val a = newArtwork("A")
        assertTrue(BackupService.hasArtworks.value)
        runBlocking { repo.delete(a) }
        idle()
        assertFalse(BackupService.hasArtworks.value)
        newArtwork("B")
        BackupService.startWith(app)
        assertTrue("found at start", BackupService.hasArtworks.value)
    }

    @Test
    fun theStatusFollowsTheGrant() {
        assertEquals(BackupStatus.NotSet, BackupService.status.value)
        newArtwork("A")
        choose()
        assertTrue(BackupService.status.value is BackupStatus.Ok)
        BackupService.startWith(app)
        assertTrue("granted at the next start", BackupService.status.value is BackupStatus.Ok)
        idle()
        BackupKit.dropGrants()
        BackupService.startWith(app)
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)

        // The folder itself gone while the grant stays: unavailable after the next backup tries.
        BackupKit.freshInstall()
        newArtwork("B")
        choose()
        BackupKit.storage.resolve(BackupKit.PICKED).deleteRecursively()
        BackupService.backUpNow()
        idle()
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
    }
}
