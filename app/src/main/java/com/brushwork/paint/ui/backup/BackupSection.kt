package com.brushwork.paint.ui.backup

import android.text.format.DateUtils
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.alpha
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.unit.dp
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R16, design 2.4.14; owner A8): the Backup section of the editor's settings, after the
 * autosave interval and before [com.brushwork.paint.ui.diagnostics.DiagnosticsSection]:
 * - [BackupLabels19.FOLDER] with [BackupLabels19.NOT_SET], [BackupLabels19.USES] or
 *   [BackupLabels19.UNAVAILABLE] under it, the pick hint, and the button that opens the picker
 *   ([BackupLabels19.CHOOSE_FOLDER], [BackupLabels19.CHANGE_FOLDER], or
 *   [BackupLabels19.CHOOSE_AGAIN] after a refused pick or a lost grant);
 * - [BackupLabels19.NOW] with [BackupLabels19.BACKED_UP] or [BackupLabels19.PROGRESS];
 * - [BackupLabels19.RESTORE].
 */
@Composable
fun BackupSection() {
    val context = LocalContext.current
    BackupHost()
    val status by BackupService.status.collectAsState()
    SectionHeader(BackupLabels19.BACKUP)
    val folder = when (val s = status) {
        BackupStatus.NotSet -> BackupLabels19.NOT_SET
        BackupStatus.Unavailable -> BackupLabels19.UNAVAILABLE
        is BackupStatus.Ok -> String.format(BackupLabels19.USES, s.folderName)
        is BackupStatus.Running -> String.format(BackupLabels19.USES, BackupService.folderName)
    }
    BackupRow(BackupLabels19.FOLDER, folder)
    Text(BackupLabels19.PICK_HINT, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    TextButton(onClick = { BackupFlow.chooseFolder() }, modifier = Modifier.heightIn(min = 40.dp)) {
        Text(
            when {
                BackupFlow.pickFailed || status == BackupStatus.Unavailable -> BackupLabels19.CHOOSE_AGAIN
                status == BackupStatus.NotSet -> BackupLabels19.CHOOSE_FOLDER
                else -> BackupLabels19.CHANGE_FOLDER
            },
        )
    }
    val now = when (val s = status) {
        is BackupStatus.Running -> String.format(BackupLabels19.PROGRESS, s.done, s.total)
        is BackupStatus.Ok -> if (s.lastMillis > 0L) {
            val at = DateUtils.formatDateTime(
                context,
                s.lastMillis,
                DateUtils.FORMAT_SHOW_DATE or DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_MONTH,
            )
            String.format(BackupLabels19.BACKED_UP, at)
        } else {
            null
        }
        else -> null
    }
    BackupRow(BackupLabels19.NOW, now, enabled = status is BackupStatus.Ok) { BackupService.backUpNow() }
    BackupRow(BackupLabels19.RESTORE, null) { BackupFlow.restore(context) }
}

/** A 48 dp settings row: [title], [subtitle] under it; tappable when [onClick] is given. */
@Composable
private fun BackupRow(title: String, subtitle: String?, enabled: Boolean = true, onClick: (() -> Unit)? = null) {
    val base = Modifier.fillMaxWidth().heightIn(min = 48.dp)
    val tappable = if (onClick != null) base.clickable(enabled = enabled, role = Role.Button, onClick = onClick) else base
    Column(
        tappable.alpha(if (enabled) 1f else 0.5f).padding(vertical = 4.dp),
        horizontalAlignment = Alignment.Start,
    ) {
        Text(title, style = MaterialTheme.typography.bodyMedium)
        if (subtitle != null) Text(subtitle, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
    }
}
