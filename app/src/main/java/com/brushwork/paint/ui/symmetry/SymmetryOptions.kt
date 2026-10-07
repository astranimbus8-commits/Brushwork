package com.brushwork.paint.ui.symmetry

import androidx.compose.foundation.layout.Row
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import com.brushwork.paint.tools.symmetry.SymmetryTool

/**
 * The Symmetry tool's options strip (v1.7 item 18, §3.18; area H): the type chips
 * (`SymmetryType.label`), "Divisions" and "Reset symmetry". Emits one Row, like every tool's
 * options.
 *
 * Foundation stub: an empty row (the options panel hides for the tool until area H fills it).
 */
@Composable
fun SymmetryOptions(tool: SymmetryTool) {
    Row(verticalAlignment = Alignment.CenterVertically) {}
}
