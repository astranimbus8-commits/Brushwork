package com.brushwork.paint.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableLongStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.diagnostics.CrashCard

/**
 * v1.9 (R9, R16, design 2.4.14, C9; declarations frozen, body A8): the one notice the gallery
 * shows above its grid, full width. Priority: a crash report not yet seen, then the backup folder
 * unavailable, then no backup folder set, then the backup's progress.
 */
sealed interface GalleryNotice {
    data class Crash(val report: CrashReport) : GalleryNotice
    data object BackupUnavailable : GalleryNotice
    data object BackupNotSet : GalleryNotice
    data class BackupProgress(val done: Int, val total: Int) : GalleryNotice
}

/** How long [com.brushwork.paint.ui.common.BackupLabels19.LATER] hides the no-folder notice: 7 days (R16). */
const val NOT_SET_LATER_MILLIS: Long = 7L * 24 * 60 * 60 * 1000

/**
 * The notice to show, or null. [notSetDismissedAtMillis] is when
 * [com.brushwork.paint.ui.common.BackupLabels19.LATER] was last tapped on the no-folder notice
 * (0 = never): it stays hidden for [NOT_SET_LATER_MILLIS] after that. A clock set back before
 * that moment shows it again (the risk is losing artworks, not a notice).
 */
fun pickNotice(crash: CrashReport?, backup: BackupStatus, notSetDismissedAtMillis: Long, nowMillis: Long): GalleryNotice? {
    if (crash != null) return GalleryNotice.Crash(crash)
    return when (backup) {
        BackupStatus.Unavailable -> GalleryNotice.BackupUnavailable
        BackupStatus.NotSet -> {
            val later = notSetDismissedAtMillis > 0L && nowMillis >= notSetDismissedAtMillis &&
                nowMillis - notSetDismissedAtMillis < NOT_SET_LATER_MILLIS
            if (later) null else GalleryNotice.BackupNotSet
        }
        is BackupStatus.Running -> GalleryNotice.BackupProgress(backup.done, backup.total)
        is BackupStatus.Ok -> null
    }
}

/**
 * The gallery's notice slot (GalleryScreen, under its top bar). F1b: no crash report is kept and
 * the backup reads Ok, so nothing shows; a tap on [com.brushwork.paint.ui.common.BackupLabels19.LATER] is
 * remembered for the screen only (A8 keeps it).
 */
@Composable
fun GalleryNotices(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    val backup by BackupService.status.collectAsState()
    var crash by remember { mutableStateOf(CrashLog.unseen(context)) }
    var laterAt by remember { mutableLongStateOf(0L) }
    val now = remember(backup, laterAt) { System.currentTimeMillis() }
    val notice = pickNotice(crash, backup, laterAt, now) ?: return
    Box(modifier) {
        when (notice) {
            is GalleryNotice.Crash -> CrashCard(
                report = notice.report,
                onCopy = {},
                onShare = {},
                onDismiss = {
                    CrashLog.markSeen(context, notice.report.id)
                    crash = null
                },
            )
            else -> BackupBanner(notice = notice, onChoose = {}, onLater = { laterAt = System.currentTimeMillis() })
        }
    }
}
