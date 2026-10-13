package com.brushwork.paint.ui.gallery

import androidx.compose.runtime.Composable

/**
 * v1.9 (R16, design 2.4.14; owner A8): the backup notices of [GalleryNotices] (the folder
 * unavailable, no folder set, the progress), tagged [com.brushwork.paint.ui.common.V19Tags.BACKUP_BANNER].
 * F1b: empty (the backup status is Ok, so it is never called).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun BackupBanner(notice: GalleryNotice, onChoose: () -> Unit, onLater: () -> Unit) {
}
