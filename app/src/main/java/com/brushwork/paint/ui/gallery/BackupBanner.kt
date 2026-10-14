package com.brushwork.paint.ui.gallery

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.LinearProgressIndicator
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.ui.common.BackupLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R16, design 2.4.14; owner A8): the backup notices of [GalleryNotices], tagged
 * [V19Tags.BACKUP_BANNER], full width under the gallery's top bar:
 * - no folder: [BackupLabels19.BANNER_NOT_SET], the pick hint, and the 40 dp buttons
 *   [BackupLabels19.CHOOSE_FOLDER] ([BackupLabels19.CHOOSE_AGAIN] after a refused pick) and
 *   [BackupLabels19.LATER], on one line at 360 dp (they wrap at large font sizes);
 * - the grant lost: [BackupLabels19.UNAVAILABLE], the hint and [BackupLabels19.CHOOSE_AGAIN];
 * - the first full backup: [BackupLabels19.PROGRESS] with a bar.
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun BackupBanner(notice: GalleryNotice, onChoose: () -> Unit, onLater: () -> Unit) {
    if (notice is GalleryNotice.Crash) return
    Surface(color = BrushworkColors.ChromeHigh, modifier = Modifier.fillMaxWidth().testTag(V19Tags.BACKUP_BANNER)) {
        Column(Modifier.padding(horizontal = 16.dp, vertical = 10.dp)) {
            when (notice) {
                is GalleryNotice.BackupProgress -> {
                    Text(String.format(BackupLabels19.PROGRESS, notice.done, notice.total), style = MaterialTheme.typography.bodyMedium)
                    Spacer(Modifier.height(8.dp))
                    val fraction = if (notice.total > 0) notice.done.toFloat() / notice.total else 0f
                    LinearProgressIndicator(progress = { fraction }, modifier = Modifier.fillMaxWidth(), color = BrushworkColors.Accent)
                }
                GalleryNotice.BackupNotSet, GalleryNotice.BackupUnavailable -> {
                    val unavailable = notice == GalleryNotice.BackupUnavailable
                    Text(if (unavailable) BackupLabels19.UNAVAILABLE else BackupLabels19.BANNER_NOT_SET, style = MaterialTheme.typography.bodyMedium)
                    Text(BackupLabels19.PICK_HINT, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
                    Spacer(Modifier.height(6.dp))
                    FlowRow(horizontalArrangement = Arrangement.spacedBy(8.dp), verticalArrangement = Arrangement.spacedBy(4.dp)) {
                        Button(
                            onClick = onChoose,
                            modifier = Modifier.heightIn(min = 40.dp),
                            contentPadding = PaddingValues(horizontal = 16.dp, vertical = 6.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrushworkColors.Accent),
                        ) {
                            Text(if (unavailable || BackupFlow.pickFailed) BackupLabels19.CHOOSE_AGAIN else BackupLabels19.CHOOSE_FOLDER)
                        }
                        if (!unavailable) {
                            TextButton(
                                onClick = onLater,
                                modifier = Modifier.heightIn(min = 40.dp),
                                contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
                            ) { Text(BackupLabels19.LATER) }
                        }
                    }
                }
                is GalleryNotice.Crash -> Unit
            }
        }
    }
}
