package com.brushwork.paint.ui.tools

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController

/**
 * The X / Y coordinate strip under the tool options (v1.5 §4.6, owned by A4): two rows with a
 * value and a slider per axis for whatever the current tool is placing. It lives in the top
 * chrome, but the editor leaves its height out of the canvas fit inset, so showing it never
 * moves the canvas (V11). Foundation stub: nothing (zero height).
 */
@Composable
fun CoordinateStrip(controller: EditorController) {}
