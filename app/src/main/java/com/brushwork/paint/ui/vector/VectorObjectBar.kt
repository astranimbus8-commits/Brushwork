package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.wrapContentWidth
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.outlined.ControlPointDuplicate
import androidx.compose.material.icons.outlined.Delete
import androidx.compose.material.icons.outlined.Deselect
import androidx.compose.material.icons.outlined.FlipToBack
import androidx.compose.material.icons.outlined.FlipToFront
import androidx.compose.material.icons.outlined.FormatColorFill
import androidx.compose.material.icons.outlined.VerticalAlignBottom
import androidx.compose.material.icons.outlined.VerticalAlignTop
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.SheetBackground
import com.brushwork.paint.ui.editor.EditorIcons
import com.brushwork.paint.ui.editor.endCanvasGesture
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits

/**
 * The floating bar of selected vector objects (Delete, Duplicate, arrange, Recolor, Transform,
 * Deselect; v1.5 §4.9, owned by A2). The editor shows it instead of the selection bar while
 * `controller.vectors.selectedIds` is not empty. Every edit is one undo step
 * ([ObjectActions]); Recolor paints lines and fills with the main color, a long press only the
 * lines. Like the selection bar it scrolls sideways when the screen is too narrow for all nine
 * buttons (they keep a ≥ 46 dp touch target).
 *
 * Selecting another layer drops the object selection (the objects of a layer that isn't being
 * worked on are not acted on).
 */
@Composable
fun VectorObjectBar(controller: EditorController, modifier: Modifier) {
    val v = controller.vectors
    val count by remember(controller) { derivedStateOf { v.selectedIds.size } }
    val selectedLayer by remember(controller) { derivedStateOf { v.selectedLayer } }
    val activeLayer by remember(controller) { derivedStateOf { controller.layersVersion; controller.activeLayer } }
    LaunchedEffect(selectedLayer, activeLayer) {
        if (selectedLayer != null && selectedLayer !== activeLayer) v.setSelection(null, emptySet())
    }
    val color = Color(controller.color)
    fun run(action: () -> Unit) {
        controller.endCanvasGesture()
        action()
    }
    val summary = if (count == 1) "1 object selected" else "$count objects selected"
    Surface(
        color = SheetBackground,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(14.dp),
        shadowElevation = 4.dp,
        modifier = modifier.semantics { contentDescription = summary },
    ) {
        Row(
            Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = 4.dp, vertical = 2.dp),
            horizontalArrangement = Arrangement.spacedBy(0.dp),
            verticalAlignment = Alignment.CenterVertically,
        ) {
            ObjectBarItem(Icons.Outlined.Delete, "Delete", "Delete the selected objects") {
                run { ObjectActions.delete(controller) }
            }
            ObjectBarItem(Icons.Outlined.ControlPointDuplicate, "Duplicate", "Duplicate the selected objects") {
                run { ObjectActions.duplicate(controller) }
            }
            ObjectBarItem(Icons.Outlined.FlipToFront, "Forward", "Bring forward") {
                run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FORWARD) }
            }
            ObjectBarItem(Icons.Outlined.FlipToBack, "Backward", "Send backward") {
                run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACKWARD) }
            }
            ObjectBarItem(Icons.Outlined.VerticalAlignTop, "Front", "Bring to front") {
                run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FRONT) }
            }
            ObjectBarItem(Icons.Outlined.VerticalAlignBottom, "Back", "Send to back") {
                run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACK) }
            }
            ObjectBarItem(
                Icons.Outlined.FormatColorFill, "Recolor", "Recolor with the main color",
                swatch = color,
                longClickLabel = "Recolor the lines only",
                onLongClick = { run { ObjectActions.recolor(controller, linesOnly = true) } },
            ) {
                run { ObjectActions.recolor(controller, linesOnly = false) }
            }
            ObjectBarItem(EditorIcons.tool(ToolId.TRANSFORM), "Transform", "Transform the selected objects") {
                run { ObjectActions.transform(controller) }
            }
            ObjectBarItem(Icons.Outlined.Deselect, "Deselect", "Deselect the objects") {
                run { ObjectActions.deselect(controller) }
            }
        }
    }
}

/**
 * Icon over a short label (the selection bar's look); [clickLabel] tells a screen reader what it
 * does. [swatch] shows a color dot on the icon; [onLongClick] adds a long-press action.
 */
@Composable
private fun ObjectBarItem(
    icon: ImageVector,
    label: String,
    clickLabel: String,
    swatch: Color? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = BrushworkColors.OnChrome
    Column(
        Modifier
            .width(ITEM_WIDTH)
            .heightIn(min = ITEM_WIDTH)
            .clip(RoundedCornerShape(10.dp))
            .combinedClickable(
                onClickLabel = clickLabel,
                role = Role.Button,
                onLongClickLabel = longClickLabel,
                onLongClick = onLongClick,
                onClick = onClick,
            ),
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Box {
            Icon(icon, contentDescription = null, tint = tint, modifier = Modifier.size(20.dp))
            if (swatch != null) {
                Box(
                    Modifier
                        .align(Alignment.BottomEnd)
                        .offset(x = 3.dp, y = 3.dp)
                        .size(9.dp)
                        .clip(CircleShape)
                        .background(swatch)
                        .border(1.dp, BrushworkColors.Chrome, CircleShape),
                )
            }
        }
        Spacer(Modifier.height(2.dp))
        // Lets a long word overhang the button a little rather than wrap or clip.
        Text(
            label,
            style = MaterialTheme.typography.labelSmall,
            fontSize = 10.sp,
            lineHeight = 12.sp,
            color = tint,
            maxLines = 1,
            softWrap = false,
            textAlign = TextAlign.Center,
            modifier = Modifier.wrapContentWidth(Alignment.CenterHorizontally, unbounded = true),
        )
    }
}

private val ITEM_WIDTH = 46.dp
