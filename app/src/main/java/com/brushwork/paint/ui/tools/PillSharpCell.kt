package com.brushwork.paint.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.ui.theme.IbisDims

/**
 * v1.10 (R12, J28; signature frozen by F0, body A5's): the X / Y pill's Sharp / Smooth cell
 * (`PillCell.SHARP`), at the end of row 2 under "Select several" (never under the trash). The
 * editor screen passes it to [CoordinatePill] only while the tool's `CornerTool.pointCorners` is
 * not null and its `corner` is not null, so on `main` at `gate-v110-0` it never shows (no tool has
 * an editor yet).
 *
 * F0 stub: an empty cell of the pill's icon size (40 × 40 dp touch). A5 draws the face of the
 * [editor]'s `corner` state (R12 (a)), the long-press name, the tag `V110Tags.PILL_SHARP`, and a
 * tap calls `setSelectedSharp`.
 */
@Suppress("UNUSED_PARAMETER") // A5 reads it; the F0 stub does not.
@Composable
fun PillSharpCell(editor: PointCornerEditor) {
    Box(Modifier.width(IbisDims.PillIconTouch).requiredHeight(IbisDims.PillCellTouch))
}
