package com.brushwork.paint.ui.placement

import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.tools.remove.CafSource
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.mask.MaskStripItems
import com.brushwork.paint.ui.mask.modeLabel
import com.brushwork.paint.ui.remove.RemoveStripItems
import com.brushwork.paint.ui.textframes.LINK_HINT
import com.brushwork.paint.ui.textframes.TextFrameStripItems
import com.brushwork.paint.ui.tools.strip.StripHandlesV17
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 G (design §3.9, §4.8, rule L): the converted strips' labels.
 * 1. Each text of G's strips is v1.7's, unchanged (the item objects' constants and functions
 *    against the v1.7 strings).
 * 2. Every v1.7 handle [StripHandlesV17] recorded for G's tools (raster and vector layer) is one of
 *    G's labels, but the duplicates §3.9 removes and the name it lets a tool keep for two.
 * 3. On each of G's strips, the v1.8 texts (the More toggle, Free deform's mesh button) are not
 *    substrings of a v1.7 text there, nor the other way round (SmokeUi.matches is a
 *    case-sensitive `contains`). Pairs of two v1.7 texts were there before and are not counted.
 * JVM: pure constants.
 */
class StripsLabelsTest {
    private val T = TransformStripItems
    private val X = TextStripItems
    private val F = FrameStripItems
    private val TF = TextFrameStripItems
    private val M = MaskStripItems
    private val R = RemoveStripItems

    @Test
    fun theConvertedStripsKeepV17sTexts() {
        val v17 = listOf(
            // Transform
            TransformTool.Mode.FREE.label to "Free", T.keepAspectLabel(true) to "Keep aspect ratio: on",
            T.keepAspectLabel(false) to "Keep aspect ratio: off", T.FROM_CENTER to "From center",
            T.FLIP_HORIZONTALLY to "Flip horizontally", T.FLIP_VERTICALLY to "Flip vertically", T.DELETE_LABEL to "Delete",
            T.DELETE_PICTURE to "Delete picture", T.ROTATE_CCW_LABEL to "Rotate 90° counter-clockwise",
            T.ROTATE_CW_LABEL to "Rotate 90° clockwise", T.FIT_LABEL to "Fit to canvas", T.RESET_LABEL to "Reset transform",
            T.INTERPOLATION_LABEL to "Choose interpolation", T.NUMBERS_LABEL to "Numbers", T.meshText(3, 3) to "3 × 3",
            SNAP_LABEL to "Snap to objects",
            // Text
            X.EDIT_LABEL to "Edit text", X.verticalLabel(false) to "Horizontal text (tap for vertical)",
            X.verticalLabel(true) to "Vertical text (tap for horizontal)", X.LETTERS_LABEL to "Letter scaling",
            X.LETTERS_TEXT to "Letters", X.WRAP_TEXT to "Wrap", X.NUMBERS_LABEL to "Numbers",
            // Frame divider
            F.NEW_LAYER_LABEL to "New frame layer", F.GRID_LABEL to "Rows × columns", F.REMOVE_LABEL to "Remove panels mode",
            F.SETTINGS_LABEL to "Frame settings / new frame layer", F.REDRAW_LABEL to "Redraw", F.NEW_FRAME_LABEL to "New frame",
            F.THEN_DIVIDE to "then drag across a panel to divide it", F.DIVIDE_HINT to "Drag across panels to divide",
            F.REMOVE_HINT to "Tap a panel to remove it", F.EDITED to "Frame layer was edited",
            // Text frames
            TF.EDIT_LABEL to "Edit story", TF.LINK_LABEL to "Link…", TF.UNLINK_LABEL to "Unlink here",
            TF.DELETE_LABEL to "Delete frame", TF.THREADS_LABEL to "Threads", TF.CANCEL_LINK_LABEL to "Cancel link",
            TF.DRAW_HINT to "Drag on the canvas to draw a text frame, tap a frame to select it",
            TF.FRAME_HINT to "Drag the frame to move it, its dots to resize it, tap it again to type",
            TF.wrapLabel(false) to "Wrap around picture", TF.wrapLabel(true) to "Wrap around picture: on",
            TF.overset(1) to "+ 1 character", TF.overset(132) to "+ 132 characters",
            TF.oversetDescription(132) to "+ 132 characters that don't fit: link another frame",
            LINK_HINT to "Draw the next frame, or tap a text box",
            // Masks
            M.add(M.LINEAR) to "+ Linear", M.add(M.RADIAL) to "+ Radial", M.add(M.BRUSH) to "+ Brush",
            M.addState(M.LINEAR, true) to "Add Linear: armed, drag on the canvas", M.addState(M.LINEAR, false) to "Add Linear",
            M.INVERT_LABEL to "Invert", M.ADJUST_LABEL to "Adjust…", M.DONE_LABEL to "Done", M.TARGET_LABEL to "Choose what the mask is for",
            M.BRUSH to "Brush", M.ERASE_LABEL to "Erase", M.overlayLabel(false) to "Mask overlay: after edits",
            M.overlayLabel(true) to "Mask overlay: always on", M.components(0) to "Components (0)",
            modeLabel(MaskMode.ADD) to "Add", modeLabel(MaskMode.SUBTRACT) to "Subtract", modeLabel(MaskMode.INTERSECT) to "Intersect",
            // Remove
            R.SIZE_LABEL to "Remove brush size", R.ADAPT_LABEL to "Color adaptation", R.BUSY_LABEL to "Removing",
            R.sourceLabel(CafSource.LAYER) to "Sample: Current layer", R.sourceLabel(CafSource.ALL_LAYERS) to "Sample: All layers",
        )
        for ((mine, old) in v17) assertEquals("v1.7's text", old, mine)
    }

    /** G's labels at every state (each state function at its values). */
    private fun gLabels(): Set<String> = buildSet {
        addAll(TransformTool.Mode.entries.map { it.label })
        addAll(listOf(T.keepAspectLabel(true), T.keepAspectLabel(false), T.FROM_CENTER, T.FLIP_HORIZONTALLY, T.FLIP_VERTICALLY))
        addAll(listOf(T.DELETE_LABEL, T.DELETE_PICTURE, T.ROTATE_CCW_LABEL, T.ROTATE_CW_LABEL, T.FIT_LABEL, T.RESET_LABEL))
        addAll(listOf(T.INTERPOLATION_LABEL, T.NUMBERS_LABEL, SNAP_LABEL, TransformLabels18.MESH_SIZE) + T.MESH_POPUP)
        addAll(listOf(TransformLabels17.FREE_DEFORM, TransformLabels17.RESET, TransformLabels17.SMOOTH, PointLabels.SELECT_ALL, PointLabels.DESELECT_ALL))
        addAll(listOf(X.EDIT_LABEL, X.verticalLabel(false), X.verticalLabel(true), X.LETTERS_LABEL, X.NUMBERS_LABEL, TF.wrapLabel(false), TF.wrapLabel(true)))
        addAll(listOf(F.NEW_LAYER_LABEL, F.GRID_LABEL, F.REMOVE_LABEL, F.SETTINGS_LABEL, F.REDRAW_LABEL, F.NEW_FRAME_LABEL))
        addAll(listOf(TF.EDIT_LABEL, TF.LINK_LABEL, TF.UNLINK_LABEL, TF.DELETE_LABEL, TF.THREADS_LABEL, TF.CANCEL_LINK_LABEL))
        addAll(listOf(M.add(M.LINEAR), M.add(M.RADIAL), M.add(M.BRUSH), M.INVERT_LABEL, M.ADJUST_LABEL, M.DONE_LABEL, M.TARGET_LABEL))
        addAll(listOf(M.BRUSH, M.ERASE_LABEL, M.overlayLabel(false), M.overlayLabel(true), M.components(0)) + MaskMode.entries.map(::modeLabel))
        addAll(listOf(R.SIZE_LABEL, R.ADAPT_LABEL) + CafSource.entries.map(R::sourceLabel))
        addAll(PathfinderOp.entries.map { it.description } + PathfinderLabels.SELECT_ALL)
    }

    @Test
    fun everyV17HandleOfGsToolsIsOneOfItsLabels() {
        val mine = gLabels()
        val tools = listOf("TRANSFORM", "TEXT", "FRAME_DIVIDER", "TEXT_FRAMES", "MASK", "REMOVE", "PATHFINDER")
        for (tool in tools) for (key in listOf(tool, "$tool@vector")) {
            val handles = StripHandlesV17.HANDLES[key]
            assertTrue("$key: recorded", handles != null)
            for (h in handles!! - StripHandlesV17.REMOVED) {
                val name = StripHandlesV17.SAME[h] ?: h
                assertTrue("$key: \"$h\" is one of G's labels", name in mine)
            }
        }
    }

    // ------------------------------------------------------------------ 3. the screens

    /** Pairs (a, b) of [texts] where a is a substring of b (equal texts at two places count). */
    private fun substringPairs(texts: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in texts.indices) for (j in texts.indices) {
            if (i != j && texts[j].contains(texts[i])) out += texts[i] to texts[j]
        }
        return out
    }

    private fun assertNoSubstrings(screen: String, v18: List<String>, v17: List<String>) {
        val mine = v18.distinct()
        val pairs = substringPairs(mine + v17.distinct()).filter { (a, b) -> a in mine || b in mine }
        assertTrue("$screen: a v1.8 text inside another (SmokeUi would match the wrong one): $pairs", pairs.isEmpty())
    }

    private val toggle = listOf(StripLabels18.MORE_TOGGLE)

    @Test
    fun noV18TextIsInsideAnotherOnGsStrips() {
        val interpolation = TransformTool.Interpolation.entries.flatMap { listOf(it.label, it.description) }
        assertNoSubstrings(
            "Transform, box modes",
            v18 = toggle + TransformLabels18.INTERPOLATION_CAPTION,
            v17 = TransformTool.Mode.entries.map { it.label } + interpolation + listOf(
                T.keepAspectLabel(true), T.keepAspectLabel(false), T.FROM_CENTER, T.FLIP_HORIZONTALLY, T.FLIP_VERTICALLY, T.DELETE_LABEL,
                T.DELETE_PICTURE, T.ROTATE_CCW_LABEL, T.ROTATE_CW_LABEL, T.FIT_LABEL, T.RESET_LABEL, SNAP_LABEL, T.INTERPOLATION_LABEL,
                T.NUMBERS_LABEL, "Numbers", "Position & size",
            ),
        )
        assertNoSubstrings(
            "Transform, Free deform",
            v18 = toggle + TransformLabels18.MESH_SIZE + TransformLabels18.INTERPOLATION_CAPTION,
            v17 = TransformTool.Mode.entries.map { it.label } + interpolation + T.MESH_POPUP + listOf(
                TransformLabels17.RESET, TransformLabels17.SMOOTH, PointLabels.SELECT_ALL, PointLabels.DESELECT_ALL, PointLabels.SEVERAL_HINT,
                T.DELETE_LABEL, T.INTERPOLATION_LABEL, T.meshText(3, 3), T.meshText(12, 12),
            ),
        )
        assertNoSubstrings(
            "Text",
            v18 = toggle,
            v17 = listOf(
                X.EDIT_LABEL, X.verticalLabel(false), X.verticalLabel(true), X.LETTERS_LABEL, X.LETTERS_TEXT, X.WRAP_TEXT,
                TF.wrapLabel(false), TF.wrapLabel(true), "Wrap around picture (works with horizontal text)", X.NUMBERS_LABEL, SNAP_LABEL,
            ),
        )
        assertNoSubstrings(
            "Frame divider",
            v18 = toggle,
            v17 = listOf(
                F.NEW_LAYER_LABEL, F.GRID_LABEL, F.REMOVE_LABEL, F.SETTINGS_LABEL, F.REDRAW_LABEL, F.NEW_FRAME_LABEL, F.THEN_DIVIDE,
                F.DIVIDE_HINT, F.REMOVE_HINT, F.EDITED, SNAP_LABEL, "Frame layer", "Split into rows × columns",
            ),
        )
        assertNoSubstrings(
            "Text frames",
            v18 = toggle,
            v17 = listOf(
                TF.EDIT_LABEL, TF.LINK_LABEL, TF.UNLINK_LABEL, TF.DELETE_LABEL, TF.THREADS_LABEL, TF.CANCEL_LINK_LABEL, TF.DRAW_HINT,
                TF.FRAME_HINT, TF.wrapLabel(false), TF.wrapLabel(true), TF.overset(132), TF.oversetDescription(132), LINK_HINT,
                SNAP_LABEL, "No wrap",
            ),
        )
        assertNoSubstrings(
            "Masks",
            v18 = toggle,
            v17 = MaskMode.entries.map(::modeLabel) + listOf(
                M.add(M.LINEAR), M.add(M.RADIAL), M.add(M.BRUSH), M.INVERT_LABEL, M.ADJUST_LABEL, M.DONE_LABEL, M.TARGET_LABEL,
                M.BRUSH, M.ERASE_LABEL, M.overlayLabel(false), M.overlayLabel(true), M.components(0), "New Tone", "This layer's mask",
            ),
        )
        assertNoSubstrings(
            "Remove",
            v18 = toggle,
            v17 = listOf(R.SIZE_LABEL, R.ADAPT_LABEL, R.BUSY_LABEL, "Size", "600 px") + CafSource.entries.flatMap { listOf(R.sourceLabel(it), it.label) },
        )
        assertNoSubstrings(
            "Pathfinder",
            v18 = toggle,
            v17 = PathfinderOp.entries.flatMap { listOf(it.description, it.label) } + listOf(
                PathfinderLabels.SELECT_ALL, PathfinderLabels.HINT, PathfinderLabels.WORKING, PathfinderLabels.picked(2),
            ),
        )
    }
}
