package com.brushwork.paint.ui.tools

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.assist.RulerToolOptions
import com.brushwork.paint.ui.brush.BrushToolOptions
import com.brushwork.paint.ui.placement.FrameDividerOptions
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.placement.TransformToolOptions
import com.brushwork.paint.ui.selection.EyedropperOptions
import com.brushwork.paint.ui.selection.FillOptions
import com.brushwork.paint.ui.selection.LassoOptions
import com.brushwork.paint.ui.selection.MagicWandOptions
import com.brushwork.paint.ui.selection.MarqueeOptions
import com.brushwork.paint.ui.vector.CurveToolOptions
import com.brushwork.paint.ui.vector.ShapeToolOptions

/**
 * The per-tool options strip under the top bar. Each module provides `XxxOptions(tool)`;
 * those composables emit one Row (they may also show their own dialogs/popups).
 */
@Composable
fun ToolOptionsBar(controller: EditorController, modifier: Modifier = Modifier) {
    val tool = controller.tools[controller.activeToolId] ?: return
    Row(
        modifier = modifier
            .heightIn(min = 44.dp)
            .horizontalScroll(rememberScrollState())
            .padding(horizontal = 8.dp),
        verticalAlignment = Alignment.CenterVertically,
    ) {
        when (tool) {
            is BrushTool -> BrushToolOptions(tool)
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
        }
    }
}
