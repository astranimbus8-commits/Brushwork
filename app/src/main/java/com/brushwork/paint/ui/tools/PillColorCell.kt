package com.brushwork.paint.ui.tools

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.ui.theme.IbisDims

/**
 * v1.10 (R2, J20; signature frozen by F0, body A2's): the X / Y pill's Object colors cell
 * (`PillCell.COLOR`), the first cell of row 2 under the move icon. The editor screen passes it to
 * [CoordinatePill] only while the tool's `ColoredObjectTool.objectColors` is not null, so on `main`
 * at `gate-v110-0` it never shows (no tool has an editor yet).
 *
 * F0 stub: an empty cell of the pill's icon size (40 × 40 dp touch). A2 draws the swatch (fill disc,
 * stroke ring, the two frame rings), the long-press name `ObjectColorLabels110.CELL`, the tag
 * `V110Tags.PILL_COLOR`, and opens the Object colors sheet on [editor].
 */
@Suppress("UNUSED_PARAMETER") // A2 reads it; the F0 stub does not.
@Composable
fun PillColorCell(editor: ObjectColorEditor) {
    Box(Modifier.width(IbisDims.PillIconTouch).requiredHeight(IbisDims.PillCellTouch))
}
