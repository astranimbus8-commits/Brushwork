package com.brushwork.paint.ui.vector

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.combinedClickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.text.BasicText
import androidx.compose.foundation.text.TextAutoSize
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
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.ColorModeOps
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
 * lines. All nine buttons fit a 392 dp phone (about 41 dp each, finger-sized); on a narrower
 * screen they keep 40 dp and the bar scrolls sideways.
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
    // The swatch shows the color as this document will paint it (grayscale / 1-bit documents).
    val color = Color(ColorModeOps.displayColor(controller.color, controller.doc.colorMode))
    fun run(action: () -> Unit) {
        controller.endCanvasGesture()
        action()
    }
    val summary = if (count == 1) "1 object selected" else "$count objects selected"
    // All nine buttons fit the user's 392 dp phone (about 41 dp each); narrower screens keep
    // 40 dp buttons and scroll sideways.
    BoxWithConstraints(modifier) {
        val itemWidth = if (maxWidth == Dp.Infinity) MAX_ITEM_WIDTH else ((maxWidth - ROW_PADDING * 2) / ITEMS).coerceIn(MIN_ITEM_WIDTH, MAX_ITEM_WIDTH)
        Surface(
            color = SheetBackground,
            contentColor = BrushworkColors.OnChrome,
            shape = RoundedCornerShape(14.dp),
            shadowElevation = 4.dp,
            modifier = Modifier.semantics { contentDescription = summary },
        ) {
            Row(
                Modifier.horizontalScroll(rememberScrollState()).padding(horizontal = ROW_PADDING, vertical = 2.dp),
                horizontalArrangement = Arrangement.spacedBy(0.dp),
                verticalAlignment = Alignment.CenterVertically,
            ) {
                ObjectBarItem(Icons.Outlined.Delete, "Delete", "Delete the selected objects", itemWidth) {
                    run { ObjectActions.delete(controller) }
                }
                ObjectBarItem(Icons.Outlined.ControlPointDuplicate, "Duplicate", "Duplicate the selected objects", itemWidth) {
                    run { ObjectActions.duplicate(controller) }
                }
                ObjectBarItem(Icons.Outlined.FlipToFront, "Forward", "Bring forward", itemWidth) {
                    run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FORWARD) }
                }
                ObjectBarItem(Icons.Outlined.FlipToBack, "Backward", "Send backward", itemWidth) {
                    run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACKWARD) }
                }
                ObjectBarItem(Icons.Outlined.VerticalAlignTop, "Front", "Bring to front", itemWidth) {
                    run { ObjectActions.arrange(controller, ObjectEdits.Arrange.FRONT) }
                }
                ObjectBarItem(Icons.Outlined.VerticalAlignBottom, "Back", "Send to back", itemWidth) {
                    run { ObjectActions.arrange(controller, ObjectEdits.Arrange.BACK) }
                }
                ObjectBarItem(
                    Icons.Outlined.FormatColorFill, "Recolor", "Recolor with the main color", itemWidth,
                    swatch = color,
                    longClickLabel = "Recolor the lines only",
                    onLongClick = { run { ObjectActions.recolor(controller, linesOnly = true) } },
                ) {
                    run { ObjectActions.recolor(controller, linesOnly = false) }
                }
                ObjectBarItem(EditorIcons.tool(ToolId.TRANSFORM), "Transform", "Transform the selected objects", itemWidth) {
                    run { ObjectActions.transform(controller) }
                }
                ObjectBarItem(Icons.Outlined.Deselect, "Deselect", "Deselect the objects", itemWidth) {
                    run { ObjectActions.deselect(controller) }
                }
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
    width: Dp,
    swatch: Color? = null,
    longClickLabel: String? = null,
    onLongClick: (() -> Unit)? = null,
    onClick: () -> Unit,
) {
    val tint = BrushworkColors.OnChrome
    Column(
        Modifier
            .width(width)
            .heightIn(min = MAX_ITEM_WIDTH)
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
        // The whole word always shows inside the button: a long one ("Transform" is about 49 dp
        // at 10 sp) is set a little smaller rather than clipped by the button's rounded shape.
        BasicText(
            label,
            style = MaterialTheme.typography.labelSmall.copy(
                color = tint,
                fontSize = LABEL_SP,
                lineHeight = 12.sp,
                letterSpacing = 0.sp,
                textAlign = TextAlign.Center,
            ),
            maxLines = 1,
            softWrap = false,
            autoSize = TextAutoSize.StepBased(minFontSize = MIN_LABEL_SP, maxFontSize = LABEL_SP, stepSize = 0.25.sp),
            modifier = Modifier.fillMaxWidth().padding(horizontal = 1.dp),
        )
    }
}

/** The label size of the selection bar; long words shrink down to [MIN_LABEL_SP] to fit their button. */
private val LABEL_SP = 10.sp
private val MIN_LABEL_SP = 7.5.sp

/** Buttons in the bar. */
private const val ITEMS = 9

/** The selection bar's button size, used when there is room. */
private val MAX_ITEM_WIDTH = 46.dp

/** The smallest button width (finger-sized); below that the bar scrolls sideways. */
private val MIN_ITEM_WIDTH = 40.dp

private val ROW_PADDING = 4.dp
