package com.brushwork.paint.ui.gallery

import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.width
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.Backup
import androidx.compose.material.icons.outlined.Folder
import androidx.compose.material.icons.outlined.Restore
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Icon
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.backup.BackupFlow
import com.brushwork.paint.ui.common.BackupLabels19

/**
 * v1.9 (R16, design 2.4.14; owner A8): whether the gallery's top bar shows its ⋮ menu, which
 * holds [GalleryBackupMenuItems] only. A8: shown.
 */
val galleryBackupMenuShown: Boolean = true

/**
 * v1.9 (R16, design 2.4.14; owner A8): the backup items of the gallery's ⋮ menu; [onDismissMenu]
 * closes the menu. [BackupLabels19.FOLDER] opens the folder picker, [BackupLabels19.NOW] backs
 * every artwork up again (with a folder set and readable), [BackupLabels19.RESTORE] opens the
 * restore list. The picker and dialogs live in [GalleryNotices]' host, which outlives the menu.
 */
@Composable
fun GalleryBackupMenuItems(onDismissMenu: () -> Unit) {
    val context = LocalContext.current
    val status by BackupService.status.collectAsState()
    DropdownMenuItem(
        text = { Text(BackupLabels19.FOLDER) },
        leadingIcon = { Icon(Icons.Outlined.Folder, contentDescription = null) },
        onClick = {
            onDismissMenu()
            BackupFlow.chooseFolder()
        },
    )
    DropdownMenuItem(
        text = { Text(BackupLabels19.NOW) },
        leadingIcon = { Icon(Icons.Outlined.Backup, contentDescription = null) },
        enabled = status is BackupStatus.Ok,
        onClick = {
            onDismissMenu()
            BackupService.backUpNow()
        },
    )
    DropdownMenuItem(
        text = { Text(BackupLabels19.RESTORE) },
        leadingIcon = { Icon(Icons.Outlined.Restore, contentDescription = null) },
        onClick = {
            onDismissMenu()
            BackupFlow.restore(context)
        },
    )
}

/**
 * v1.9 (R16, design 2.4.14; owner A8): under the empty-gallery text, [BackupLabels19.RESTORE], so
 * a reinstalled app reaches its works in one tap (the folder picker, then the restore list).
 */
@Composable
fun EmptyGalleryRestore() {
    val context = LocalContext.current
    Spacer(Modifier.height(16.dp))
    OutlinedButton(onClick = { BackupFlow.restore(context) }) {
        Icon(Icons.Outlined.Restore, contentDescription = null)
        Spacer(Modifier.width(8.dp))
        Text(BackupLabels19.RESTORE)
    }
}
