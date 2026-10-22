package com.brushwork.paint.ui.gallery

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.engine.EngineNotice
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.ui.backup.BackupHost
import com.brushwork.paint.ui.diagnostics.CrashCard
import com.brushwork.paint.ui.engine.EngineNoticeCard

/**
 * v1.9 (R9, R16, design 2.4.14, C9; declarations frozen, body A8): the one notice the gallery
 * shows above its grid, full width. Priority: a crash report not yet seen, then the backup folder
 * unavailable, then no backup folder set, then the backup's progress.
 *
 * v1.10 (F1, J32, design 2.11): an engine notice ([Engine], A10's [EngineNoticeCard]) comes
 * after a crash report and before the backup's notices.
 */
sealed interface GalleryNotice {
    data class Crash(val report: CrashReport) : GalleryNotice
    data class Engine(val notice: EngineNotice) : GalleryNotice
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
 * that moment shows it again (the risk is losing artworks, not a notice). v1.10 (F1): [engine]
 * ([EngineNotices.current], never set before A10) comes after the crash report.
 */
fun pickNotice(
    crash: CrashReport?,
    backup: BackupStatus,
    notSetDismissedAtMillis: Long,
    nowMillis: Long,
    engine: EngineNotice? = null,
): GalleryNotice? {
    if (crash != null) return GalleryNotice.Crash(crash)
    if (engine != null) return GalleryNotice.Engine(engine)
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
 * The gallery's notice slot (GalleryScreen, under its top bar). v1.9 A8: it also hosts the backup's
 * folder picker and dialogs ([BackupHost]), for the notice, the ⋮ menu and the empty gallery.
 * The no-folder notice waits for the first artwork (nothing to lose before), and
 * [com.brushwork.paint.ui.common.BackupLabels19.LATER] is kept in the backup's state file, so it
 * holds for 7 days across app starts.
 */
@Composable
fun GalleryNotices(modifier: Modifier = Modifier) {
    val context = LocalContext.current
    BackupHost()
    val status by BackupService.status.collectAsState()
    val hasArtworks by BackupService.hasArtworks.collectAsState()
    val laterAt by BackupService.notSetLaterAt.collectAsState()
    var crash by remember { mutableStateOf(CrashLog.unseen(context)) }
    // v1.10 (F1, J32): the engine's notice (A10 raises it; OK hides it).
    var engine by remember { mutableStateOf(EngineNotices.current) }
    val backup = if (status == BackupStatus.NotSet && !hasArtworks) BackupStatus.Ok(0L, "", 0L) else status
    val now = remember(backup, laterAt) { BackupService.clock() }
    val notice = pickNotice(crash, backup, laterAt, now, engine) ?: return
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
            is GalleryNotice.Engine -> EngineNoticeCard(
                notice = notice.notice,
                onOk = {
                    EngineNotices.current = null
                    engine = null
                },
            )
            else -> BackupBanner(notice = notice, onChoose = { BackupFlow.chooseFolder() }, onLater = { BackupService.putOffNotice() })
        }
    }
}
