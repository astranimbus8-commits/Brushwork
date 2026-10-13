package com.brushwork.paint.ui.gallery

import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.storage.backup.BackupStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/** v1.9 (design 2.4.14, C9): one gallery notice at a time, crash > unavailable > not set > progress. */
class GalleryNoticePickTest {
    private val report = CrashReport("r1", 1_000L, CrashReport.Kind.CRASH, "IllegalStateException", "text")
    private val ok = BackupStatus.Ok(5_000L, "Brushwork", 1024L)
    private val running = BackupStatus.Running(3, 12)
    private val now = 100L * NOT_SET_LATER_MILLIS

    @Test
    fun aCrashComesFirst() {
        for (b in listOf(ok, running, BackupStatus.NotSet, BackupStatus.Unavailable)) {
            assertEquals(GalleryNotice.Crash(report), pickNotice(report, b, 0L, now))
        }
    }

    @Test
    fun theBackupStatusPicksTheRest() {
        assertEquals(GalleryNotice.BackupUnavailable, pickNotice(null, BackupStatus.Unavailable, 0L, now))
        assertEquals(GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, 0L, now))
        assertEquals(GalleryNotice.BackupProgress(3, 12), pickNotice(null, running, 0L, now))
        assertNull(pickNotice(null, ok, 0L, now))
    }

    @Test
    fun laterHidesTheNoFolderNoticeForSevenDays() {
        val later = now - 1_000L
        assertNull(pickNotice(null, BackupStatus.NotSet, later, now))
        assertNull(pickNotice(null, BackupStatus.NotSet, now, now))
        assertNull(pickNotice(null, BackupStatus.NotSet, now - NOT_SET_LATER_MILLIS + 1, now))
        assertEquals(GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, now - NOT_SET_LATER_MILLIS, now))
        // A clock set back before the tap shows it again.
        assertEquals(GalleryNotice.BackupNotSet, pickNotice(null, BackupStatus.NotSet, now + 1, now))
        // "Later" hides only the no-folder notice.
        assertEquals(GalleryNotice.BackupUnavailable, pickNotice(null, BackupStatus.Unavailable, later, now))
        assertEquals(GalleryNotice.BackupProgress(3, 12), pickNotice(null, running, later, now))
        assertEquals(GalleryNotice.Crash(report), pickNotice(report, BackupStatus.NotSet, later, now))
    }
}
