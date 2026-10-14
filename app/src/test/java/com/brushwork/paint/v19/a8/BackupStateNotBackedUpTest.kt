package com.brushwork.paint.v19.a8

import android.content.Context
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSettings
import com.brushwork.paint.storage.backup.BackupStateData
import com.brushwork.paint.storage.backup.BackupStateStore
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.v19.a8.BackupKit.PICKED
import com.brushwork.paint.v19.a8.BackupKit.app
import com.brushwork.paint.v19.a8.BackupKit.choose
import com.brushwork.paint.v19.a8.BackupKit.idle
import com.brushwork.paint.v19.a8.BackupKit.newArtwork
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A8 (R16 (b)): the backup folder's state (`backup-state.json`: the folder, its grant, what
 * was mirrored) is never part of Android's Auto Backup, because a folder grant does not move to a
 * new phone. After Auto Backup brings the settings back on a new install, the backup starts with
 * no folder (the notice asks for one), and a state that names a folder without its grant is
 * "unavailable", never backing up.
 */
@RunWith(RobolectricTestRunner::class)
class BackupStateNotBackedUpTest {
    @Before
    fun setUp() {
        BackupKit.freshInstall()
    }

    @Test
    fun theStateFileLiesOutsideWhatAutoBackupKeeps() {
        newArtwork("A")
        choose()
        val f = BackupStateStore.file(app)
        assertTrue("written", f.isFile)
        assertEquals(app.noBackupFilesDir.absoluteFile, f.parentFile!!.absoluteFile)
        assertFalse("not under filesDir", f.absolutePath.startsWith(app.filesDir.absolutePath))
        assertTrue("it names the folder", BackupStateStore.read(app).tree!!.contains("Brushwork"))
    }

    @Test
    fun afterAnAutoBackupRestoreNoFolderIsSet() {
        app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).edit().putInt("v19a8.kept", 7).commit()
        newArtwork("A")
        choose()
        // A new phone: Auto Backup restores the preference files only.
        BackupKit.reinstall(clearPrefs = false)
        assertEquals(7, app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).getInt("v19a8.kept", 0))
        assertEquals(BackupStatus.NotSet, BackupService.status.value)
        assertFalse(BackupService.folderSet)
        newArtwork("B")
        assertEquals("the notice will ask for a folder", BackupStatus.NotSet, BackupService.status.value)
    }

    @Test
    fun aStateWithoutItsGrantNeverBacksUp() {
        newArtwork("A")
        val stale = BackupStateData(tree = FakeDocumentsProvider.tree(PICKED).toString(), folderName = PICKED, fullDone = true)
        BackupStateStore.write(app, stale)
        FakeDocumentsProvider.resetCounts()
        BackupService.startWith(app)
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
        newArtwork("B")
        BackupService.backUpNow()
        idle()
        assertEquals("nothing written", emptyList<String>(), FakeDocumentsProvider.log.toList())
        assertEquals(BackupStatus.Unavailable, BackupService.status.value)
    }
}
