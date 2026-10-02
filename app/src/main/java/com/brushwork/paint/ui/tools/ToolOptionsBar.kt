package com.brushwork.paint.ui.tools

import androidx.compose.foundation.background
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.layout.layout
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.assist.RulerToolOptions
import com.brushwork.paint.ui.brush.BrushToolOptions
import com.brushwork.paint.ui.clone.CloneToolOptions
import com.brushwork.paint.ui.mask.MaskToolOptions
import com.brushwork.paint.ui.placement.FrameDividerOptions
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.placement.TransformToolOptions
import com.brushwork.paint.ui.selection.EyedropperOptions
import com.brushwork.paint.ui.selection.FillOptions
import com.brushwork.paint.ui.selection.LassoOptions
import com.brushwork.paint.ui.selection.MagicWandOptions
import com.brushwork.paint.ui.selection.MarqueeOptions
import com.brushwork.paint.ui.textframes.TextFrameToolOptions
import com.brushwork.paint.ui.theme.BrushworkColors
import com.brushwork.paint.ui.vector.CurveToolOptions
import com.brushwork.paint.ui.vector.ShapeToolOptions
import com.brushwork.paint.ui.vector.VectorEraserOptions

/**
 * The per-tool options strip (v1.6: inside the floating options panel under the top row). Each
 * module provides `XxxOptions(tool)`; those composables emit one Row (they may also show their
 * own dialogs/popups). [onContentWidth] receives the width (px) of what the tool shows, at
 * layout time (0 = a tool without options): the panel hides itself then.
 */
@Composable
fun ToolOptionsBar(controller: EditorController, modifier: Modifier = Modifier, onContentWidth: ((Int) -> Unit)? = null) {
    val tool = controller.tools[controller.activeToolId] ?: return
    // Derived: isVectorMode reads layersVersion, which changes with every committed edit.
    val vectorMode by remember(controller) { derivedStateOf { controller.isVectorMode } }
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp)
            .then(
                if (onContentWidth == null) {
                    Modifier
                } else {
                    Modifier.layout { measurable, constraints ->
                        // The content's own width (no minimum), reported before it is padded out.
                        val p = measurable.measure(constraints.copy(minWidth = 0))
                        onContentWidth(p.width)
                        layout(maxOf(p.width, constraints.minWidth), p.height) { p.place(0, 0) }
                    }
                },
            ),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        // Vector mode (v1.5): what is drawn stays editable.
        if (vectorMode) VectorModeChip()
        when (tool) {
            is BrushTool -> {
                // The vector eraser's modes come first: they decide what a stroke removes, and
                // the start of the strip is what a phone shows without scrolling.
                if (tool.id == ToolId.ERASER && vectorMode) VectorEraserOptions(controller)
                BrushToolOptions(tool)
            }
            is CloneTool -> CloneToolOptions(tool)
            is MaskTool -> MaskToolOptions(tool)
            is FillTool -> FillOptions(tool)
            is EyedropperTool -> EyedropperOptions(tool)
            is MagicWandTool -> MagicWandOptions(tool)
            is LassoTool -> LassoOptions(tool)
            is MarqueeTool -> MarqueeOptions(tool)
            is TransformTool -> TransformToolOptions(tool)
            is TextTool -> TextToolOptions(tool)
            is ShapeTool -> ShapeToolOptions(tool)
            is CurveTool -> CurveToolOptions(tool)
            is FrameDividerTool -> FrameDividerOptions(tool)
            is RulerTool -> RulerToolOptions(tool)
            is com.brushwork.paint.tools.select.ObjectSelectTool -> com.brushwork.paint.ui.selection.ObjectSelectOptions(tool)
            is com.brushwork.paint.tools.remove.RemoveTool -> com.brushwork.paint.ui.remove.RemoveToolOptions(tool)
            // v1.6 (pre-registered by the foundation; area D fills it). The Path tool is a
            // CurveTool: its options branch on CurveTool.kind inside CurveToolOptions (area B).
            is TextFrameTool -> TextFrameToolOptions(tool)
        }
    }
}

/** Accent "VECTOR" chip at the start of the options strip while the active layer is a vector layer. */
@Composable
private fun VectorModeChip() {
    Text(
        "VECTOR",
        fontSize = 11.sp,
        fontWeight = FontWeight.Bold,
        color = Color.White,
        maxLines = 1,
        modifier = Modifier
            .padding(end = 6.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(BrushworkColors.AccentDim)
            .padding(horizontal = 6.dp, vertical = 3.dp)
            .semantics { contentDescription = "Vector mode is on" },
    )
}
