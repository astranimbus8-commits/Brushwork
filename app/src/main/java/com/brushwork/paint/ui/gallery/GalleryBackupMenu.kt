package com.brushwork.paint.ui.gallery

import androidx.compose.runtime.Composable

/**
 * v1.9 (R16, design 2.4.14; owner A8): whether the gallery's top bar shows its ⋮ menu, which
 * holds [GalleryBackupMenuItems] only. F1b: false, so the gallery looks as in v1.8.
 */
val galleryBackupMenuShown: Boolean = false

/**
 * v1.9 (R16, design 2.4.14; owner A8): the backup items of the gallery's ⋮ menu; [onDismissMenu]
 * closes the menu. F1b: empty.
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun GalleryBackupMenuItems(onDismissMenu: () -> Unit) {
}

/** v1.9 (R16, design 2.4.14; owner A8): the restore offer under the empty-gallery text. F1b: empty. */
@Composable
fun EmptyGalleryRestore() {
}
