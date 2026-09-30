package com.brushwork.paint.ui.placement

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.selectableGroup
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.transform.TransformAnchor
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * 3 x 3 reference point picker (like Illustrator's): nine squares on the outline and center
 * lines of a box; the chosen one is filled. Each square is a [cell]-sized touch target.
 */
@Composable
fun TransformAnchorPicker(selected: TransformAnchor, onSelect: (TransformAnchor) -> Unit, modifier: Modifier = Modifier, cell: Dp = 40.dp) {
    val lineColor = BrushworkColors.OnChromeDim.copy(alpha = 0.55f)
    Column(
        modifier
            .size(cell * 3)
            .clip(RoundedCornerShape(8.dp))
            .background(BrushworkColors.ChromeHigh.copy(alpha = 0.6f))
            .drawBehind {
                // The box through the outer points and its center lines.
                val c = cell.toPx()
                val half = c / 2f
                val stroke = 1.dp.toPx()
                drawRect(lineColor, topLeft = Offset(half, half), size = Size(c * 2f, c * 2f), style = Stroke(stroke))
                drawLine(lineColor, Offset(half, half + c), Offset(half + 2f * c, half + c), stroke)
                drawLine(lineColor, Offset(half + c, half), Offset(half + c, half + 2f * c), stroke)
            }
            .selectableGroup(),
    ) {
        for (row in 0 until 3) {
            Row {
                for (col in 0 until 3) {
                    val a = TransformAnchor.GRID[row * 3 + col]
                    val on = a == selected
                    Box(
                        Modifier
                            .size(cell)
                            .selectable(selected = on, role = Role.RadioButton, onClick = { onSelect(a) })
                            .semantics { contentDescription = "Reference point: ${a.label.lowercase()}" },
                        contentAlignment = Alignment.Center,
                    ) {
                        val shape = RoundedCornerShape(2.dp)
                        Box(
                            Modifier
                                .size(if (on) 14.dp else 10.dp)
                                .background(if (on) BrushworkColors.Accent else BrushworkColors.Chrome, shape)
                                .border(1.5.dp, if (on) Color.White else BrushworkColors.OnChromeDim, shape)
                        )
                    }
                }
            }
        }
    }
}
