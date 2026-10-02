package com.brushwork.paint.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController

/**
 * The X / Y pill (v1.6 §3.7.8; area G): "small, the number is the slider, X and Y in beveled
 * squares", with the ✥ fold cell and the "#" increments toggle. Shows whenever
 * `coordinateSourceOf(tool)` is non-null; the editor screen places it ([modifier]) under the
 * options strip.
 *
 * Foundation stub: the v1.5 X / Y strip ([CoordinateStrip]), so the current UI keeps working.
 */
@Composable
fun CoordinatePill(controller: EditorController, modifier: Modifier = Modifier) {
    Box(modifier) { CoordinateStrip(controller) }
}
