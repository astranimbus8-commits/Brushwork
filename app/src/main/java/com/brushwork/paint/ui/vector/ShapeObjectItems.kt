package com.brushwork.paint.ui.vector

import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.unit.dp
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripKeyV19
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.vector.select.ObjectEdits

/**
 * v1.9 (R14, design 3.2 A9 step 5): the Shape More row's object items, after v1.8's More items
 * (StripKeysV19 ranks 50 to 52): [LayerLabels19.DUPLICATE], [ObjectEdits.Arrange.FORWARD] and
 * [ObjectEdits.Arrange.BACKWARD], with the object bar's icons and actions
 * ([ShapeTool.duplicateOpen], [ShapeTool.arrangeOpen]). The strip adds them while
 * [ShapeTool.objectActionsApply] (an open shape object outside Points mode) and the selection bar
 * and the object bar are hidden (`StripContext.selectionBarShown` and [ShapeStripItems.selectionOrObjectBarUp]):
 * the bars and the strip never show the same labels (I10). Under the paste bar alone they show.
 */
internal fun shapeObjectItems(tool: ShapeTool): List<StripItem> {
    val forward = ObjectEdits.Arrange.FORWARD
    val backward = ObjectEdits.Arrange.BACKWARD
    return listOf(
        objectItem(StripKeysV19.DUPLICATE, LayerLabels19.DUPLICATE, Icons.Outlined.ControlPointDuplicate) { tool.duplicateOpen() },
        objectItem(StripKeysV19.FORWARD, forward.label, Icons.Outlined.FlipToFront) { tool.arrangeOpen(forward) },
        objectItem(StripKeysV19.BACKWARD, backward.label, Icons.Outlined.FlipToBack) { tool.arrangeOpen(backward) },
    )
}

private fun objectItem(key: StripKeyV19, label: String, icon: ImageVector, onClick: () -> Unit): StripItem =
    StripItem(key.key, label, key.widthDp.dp, row = StripRow.MORE) {
        ShapeIconAction(icon, label) { onClick() }
    }
