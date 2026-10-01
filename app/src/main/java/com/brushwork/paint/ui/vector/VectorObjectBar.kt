package com.brushwork.paint.ui.vector

import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController

/**
 * The floating bar of selected vector objects (Delete, Duplicate, arrange, Recolor, Transform,
 * Deselect; v1.5 §4.9, owned by A2). The editor shows it instead of the selection bar while
 * `controller.vectors.selectedIds` is not empty. Foundation stub: nothing.
 */
@Composable
fun VectorObjectBar(controller: EditorController, modifier: Modifier) {}
