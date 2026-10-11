package com.brushwork.paint.ui.tools

import androidx.compose.runtime.Composable
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerTool
import com.brushwork.paint.brush.BrushTool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.frame.FrameDividerTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.remove.RemoveTool
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.FillTool
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.select.MarqueeTool
import com.brushwork.paint.tools.select.ObjectSelectTool
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.symmetry.SymmetryTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.array.arrayStrip
import com.brushwork.paint.ui.assist.rulerStrip
import com.brushwork.paint.ui.brush.brushStrip
import com.brushwork.paint.ui.clone.cloneStrip
import com.brushwork.paint.ui.mask.maskStrip
import com.brushwork.paint.ui.pathfinder.pathfinderStrip
import com.brushwork.paint.ui.placement.frameDividerStrip
import com.brushwork.paint.ui.placement.textStrip
import com.brushwork.paint.ui.placement.transformStrip
import com.brushwork.paint.ui.remove.removeStrip
import com.brushwork.paint.ui.selection.colorSelectStrip
import com.brushwork.paint.ui.selection.eyedropperStrip
import com.brushwork.paint.ui.selection.fillStrip
import com.brushwork.paint.ui.selection.lassoStrip
import com.brushwork.paint.ui.selection.magicWandStrip
import com.brushwork.paint.ui.selection.magneticLassoStrip
import com.brushwork.paint.ui.selection.marqueeStrip
import com.brushwork.paint.ui.selection.objectSelectStrip
import com.brushwork.paint.ui.symmetry.symmetryStrip
import com.brushwork.paint.ui.textframes.textFrameStrip
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.curveStrip
import com.brushwork.paint.ui.vector.shapeStrip

/**
 * The strip dispatcher (v1.8, design §4.4 and §4.6; frozen, the lead's): the active tool's
 * [StripSpec], from the builder in its owner's folder. The `when` is exhaustive over [ToolId],
 * so a new tool does not compile without a builder (`StripRegistryTest` checks each one runs).
 * A tool of an unexpected type (a test's fake) shows [StripSpec.EMPTY], as v1.7 showed nothing.
 */
object ToolStrips {
    @Composable
    fun of(c: EditorController): StripSpec {
        val id = c.activeToolId
        val tool = c.tools[id] ?: return StripSpec.EMPTY
        val spec: StripSpec? = when (id) {
            ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR -> (tool as? BrushTool)?.let { brushStrip(it, c) }
            ToolId.FILL -> (tool as? FillTool)?.let { fillStrip(it, c) }
            ToolId.EYEDROPPER -> (tool as? EyedropperTool)?.let { eyedropperStrip(it, c) }
            ToolId.MAGIC_WAND -> (tool as? MagicWandTool)?.let { magicWandStrip(it, c) }
            ToolId.LASSO -> (tool as? LassoTool)?.let { lassoStrip(it, c) }
            ToolId.MARQUEE -> (tool as? MarqueeTool)?.let { marqueeStrip(it, c) }
            ToolId.TRANSFORM -> (tool as? TransformTool)?.let { transformStrip(it, c) }
            ToolId.TEXT -> (tool as? TextTool)?.let { textStrip(it, c) }
            ToolId.SHAPE -> (tool as? ShapeTool)?.let { shapeStrip(it, c) }
            ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH -> (tool as? CurveTool)?.let { curveStrip(it, c) }
            ToolId.FRAME_DIVIDER -> (tool as? FrameDividerTool)?.let { frameDividerStrip(it, c) }
            ToolId.RULER -> (tool as? RulerTool)?.let { rulerStrip(it, c) }
            ToolId.OBJECT_SELECT -> (tool as? ObjectSelectTool)?.let { objectSelectStrip(it, c) }
            ToolId.REMOVE -> (tool as? RemoveTool)?.let { removeStrip(it, c) }
            ToolId.CLONE -> (tool as? CloneTool)?.let { cloneStrip(it, c) }
            ToolId.MASK -> (tool as? MaskTool)?.let { maskStrip(it, c) }
            ToolId.TEXT_FRAMES -> (tool as? TextFrameTool)?.let { textFrameStrip(it, c) }
            ToolId.ARRAY -> (tool as? ArrayTool)?.let { arrayStrip(it, c) }
            ToolId.SYMMETRY -> (tool as? SymmetryTool)?.let { symmetryStrip(it, c) }
            ToolId.PATHFINDER -> (tool as? PathfinderTool)?.let { pathfinderStrip(it, c) }
            ToolId.MAGNETIC_LASSO -> (tool as? MagneticLassoTool)?.let { magneticLassoStrip(it, c) }
            ToolId.COLOR_SELECT -> (tool as? ColorSelectTool)?.let { colorSelectStrip(it, c) }
        }
        return spec ?: StripSpec.EMPTY
    }
}
