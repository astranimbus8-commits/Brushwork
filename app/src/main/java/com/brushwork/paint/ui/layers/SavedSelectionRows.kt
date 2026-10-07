package com.brushwork.paint.ui.layers

import androidx.compose.foundation.layout.Column
import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController

/**
 * The saved selections' rows (v1.7 item 14, §3.14; area G): listed under the "Selection Layer"
 * row of the layer window, newest first, 48 dp each with a mask thumbnail and the name, and each
 * with its ⋮ menu (Load, Add to, Subtract from, Intersect with, Update from, Rename, Delete).
 *
 * The layer list shows them as ONE `LazyColumn` item (its header count stays `HEADER_ITEMS = 2`
 * in `LayersPanel.kt`), so this composable emits a [Column] of rows.
 *
 * Foundation stub: emits no rows until area G implements it.
 */
@Composable
fun SavedSelectionRows(controller: EditorController) {
    Column {}
}
