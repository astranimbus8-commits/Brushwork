package com.brushwork.paint.v110.cross

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa18.Fingers18
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.CornerTool
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import kotlin.math.roundToInt

/**
 * v1.10 Gate 2 cross test (the X / Y pill, R2 x R12 x v1.7's cells): every cell of the pill on
 * the real editor at 392 and 360 dp and font scale 1.0, each tapped by a finger at the middle of
 * the control found by its tag or name, with two points selected and "Select several" on, in
 * Curve, Path and Shape (a hexagon in Points mode):
 * - each cell shows whole inside the pill's 8 dp margins: row 1 the move icon, X, Y, "#", the
 *   trash and "Select several"; row 2 Object colors, the Scale row (keep, Scale X, Scale Y, "Scale
 *   increments") and Sharp / Smooth; folded, `[move][colors][trash][several][sharp]` in one row;
 *   no text overflows its box (unfolded, folded, with a dialog or the sheet open);
 * - each tap acts once. "Select several", "#", "Scale increments", keep and the move icon switch
 *   their state once and record nothing; Object colors opens the Object colors sheet (Back
 *   closes it, nothing recorded); X, Y, Scale X and Scale Y open their dialog, and the typed value
 *   is ONE in-tool step; Sharp / Smooth is ONE in-tool step that flips the selected points (smooth
 *   to sharp, sharp to smooth, a mix to all sharp), folded too; the trash deletes the selected
 *   points as ONE in-tool step. Each step is taken back by one Undo and brought again by Redo,
 *   and none is a document step;
 * - Polyline: the Sharp / Smooth cell is greyed, its long press names it
 *   [PointLabels110.POLYLINE_ALWAYS_SHARP], and a tap changes nothing.
 *
 * One test method, its cases as sections (Compose's frame clock serves a sandbox's first test;
 * [Qa19Ui.uiTest] restarts the UI dispatcher for each case). The 360 dp phone comes from
 * `RuntimeEnvironment.setQualifiers` (as `Qa110SharpCellUiTest`). CLASSIC and FULL.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.cross.pillallcellssandbox"])
class CrossPillAllCellsTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    @Test
    fun everyPillCellIsReachableAndActsOnceAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (id in listOf(ToolId.CURVE, ToolId.PATH, ToolId.SHAPE)) h.section("$level, $width dp: $id") { cells(h, id, width) }
                h.section("$level, $width dp: Polyline") { polyline(h, width) }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** The points and their corners: what an in-tool step changes. */
    private data class Points(val at: List<Vec2>, val sharp: List<Boolean>)

    private fun points(tool: Tool): Points {
        val e = tool as PointEditor
        val sharp = when (tool) {
            is ShapeTool -> tool.docAnchors()!!.map { !it.smooth }
            is CurveTool -> if (tool.isPath) tool.spline!!.points.map { it.sharp } else tool.anchors.map { it.sharp }
            else -> throw AssertionError("not a point tool: $tool")
        }
        return Points((0 until e.pointCount).map { e.pointAt(it) }, sharp)
    }

    /** The editor at [width] dp (unfolded pill, increments off, snapping off) with [id]. */
    private fun open(h: Qa19Harness, id: ToolId, width: Int): ChromeScreen {
        val s = h.editor { c ->
            c.settings.coordinateStripFolded = false
            c.increments.update { it.copy(enabled = false) }
        }
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        s.c.snapping.enabled = false
        s.c.selectTool(id)
        Qa19Ui.settle()
        return s
    }

    /** [r] whole on screen inside the pill's 8 dp margins (a finger can hit it); [r]. */
    private fun reachable(s: ChromeScreen, what: String, r: Rect?): Rect {
        requireNotNull(r) { "$what: not on screen; shown: ${Qa19Ui.shown().take(80)}" }
        val m = IbisDims.PillStart.value
        assertTrue(
            "$what whole on screen: $r in ${s.widthDp} x ${s.heightDp} dp",
            r.width > 0f && r.height > 0f && r.left >= m - EPS && r.right <= s.widthDp - m + EPS && r.top >= -EPS && r.bottom <= s.heightDp + EPS,
        )
        return r
    }

    private fun tagged(s: ChromeScreen, tag: String, what: String): Rect = reachable(s, what, s.tagged(tag))

    private fun named(s: ChromeScreen, label: String): Rect = reachable(s, "\"$label\"", Finger.control(s, label))

    private fun tap(s: ChromeScreen, r: Rect) = Finger.tapAt(s, r.center.x, r.center.y)

    /** The switch state of the control described as [label]. */
    private fun switchOn(s: ChromeScreen, label: String): Boolean? =
        s.placed().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true }
            ?.node?.config?.getOrNull(SemanticsProperties.ToggleableState)?.let { it == ToggleableState.On }

    /**
     * [act] is ONE in-tool step: it changes the points, one Undo gives them back exactly and Redo
     * brings the change again, and the document records nothing. The points after it.
     */
    private fun oneStep(s: ChromeScreen, tool: Tool, what: String, act: () -> Unit): Points {
        val before = points(tool)
        val steps = s.c.undoManager.undoCount
        act()
        val after = points(tool)
        assertNotEquals("$what: it acted", before, after)
        s.c.undo()
        Qa19Ui.settle()
        assertEquals("$what: one Undo gives the points back", before, points(tool))
        s.c.redo()
        Qa19Ui.settle()
        assertEquals("$what: Redo brings it again", after, points(tool))
        assertEquals("$what: no document step", steps, s.c.undoManager.undoCount)
        return after
    }

    /** [act] records nothing: the tool's history mark, the points and the document's steps as they were. */
    private fun noStep(s: ChromeScreen, tool: Tool, what: String, act: () -> Unit) {
        val mark = tool.historyMark()
        val before = points(tool)
        val steps = s.c.undoManager.undoCount
        act()
        assertEquals("$what: no in-tool step", mark, tool.historyMark())
        assertEquals("$what: the points as they were", before, points(tool))
        assertEquals("$what: no document step", steps, s.c.undoManager.undoCount)
    }

    /** The selection [i] (of the tool's points). */
    private fun select(tool: Tool, vararg i: Int) {
        val e = tool as PointEditor
        e.selectPoints(PointSelection.of(e.pointCount, *i))
        Qa19Ui.settle()
    }

    /**
     * The two selected points: the inner two of [FOUR] (a Path's ends stay sharp), or two of the
     * hexagon's that share no side (two on one side can share an x: no width to scale).
     */
    private fun selected(tool: Tool): IntArray = if (tool is ShapeTool) intArrayOf(0, 2) else intArrayOf(1, 2)

    /** Draws the tool's object and selects [selected]; the tool. */
    private fun draw(s: ChromeScreen, id: ToolId): Tool {
        val tool = s.c.currentTool
        if (tool is ShapeTool) {
            tool.update { it.copy(type = ShapeType.POLYGON, sides = 6, style = ShapeStyle.STROKE, corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false) }
            QaCurves.drag(s, Vec2(110f, 70f), Vec2(170f, 150f))
            tool.setPointEditing(true)
            Qa19Ui.settle()
            assertEquals("$id: a hexagon's points", 6, tool.pointCount)
        } else {
            tool as CurveTool
            for (p in FOUR) assertTrue("$id: a point at $p", tool.addAnchor(p))
            tool.deselect()
        }
        val sel = selected(tool)
        select(tool, *sel)
        val box = points(tool).at.filterIndexed { i, _ -> i in sel }
        assertTrue("$id: the selection's box has both sizes: $box", box[0].x != box[1].x && box[0].y != box[1].y)
        return tool
    }

    private fun cells(h: Qa19Harness, id: ToolId, width: Int) {
        val s = open(h, id, width)
        val c = s.c
        val tool = draw(s, id)
        val editor = tool as PointEditor
        val at = "$id at $width dp"

        // Row 1 and row 2 whole on screen; no text cut.
        for (label in listOf(FOLD, X_CELL, Y_CELL, INCREMENTS_LABEL, PillLabels.KEEP_PROPORTIONS, PillLabels.SCALE_X, PillLabels.SCALE_Y, PillLabels.SCALE_INCREMENTS)) named(s, label)
        val trash = tagged(s, V17Tags.PILL_TRASH, "$at: the trash")
        assertEquals("$at: the trash deletes the selected points", trash, Finger.control(s, PillLabels.DELETE_POINTS))
        tagged(s, V17Tags.PILL_SCALE_ROW, "$at: the Scale row")
        tagged(s, V110Tags.PILL_COLOR, "$at: Object colors")
        tagged(s, V110Tags.PILL_SHARP, "$at: Sharp / Smooth")
        Qa19Ui.assertNoTextOverflow("$at, unfolded")

        // "Select several": one tap, on.
        assertEquals("Off", PillTestKit.severalState())
        tap(s, tagged(s, V18Tags.PILL_SEVERAL, "$at: Select several"))
        assertEquals("$at: Select several on", "On", PillTestKit.severalState())
        assertTrue(editor.selectSeveral)

        // Sharp / Smooth: each tap one step; smooth and sharp swap, a mix becomes all sharp.
        val sel = selected(tool)
        val s0 = points(tool).sharp[sel[0]]
        assertEquals("$at: both selected points alike", s0, points(tool).sharp[sel[1]])
        fun sharpTap(what: String, wantName: String, want: Boolean) {
            assertEquals("$at, $what: the face", wantName, PillTestKit.sharpName())
            val after = oneStep(s, tool, "$at, $what") { tap(s, tagged(s, V110Tags.PILL_SHARP, "$at: Sharp / Smooth")) }
            for (i in editor.pointSelection.indices) assertEquals("$at, $what: point $i", want, after.sharp[i])
        }
        fun face(sharp: Boolean) = if (sharp) ShapeStripItems.SMOOTH_LABEL else ShapeStripItems.SHARP_LABEL
        sharpTap("tap 1", face(s0), !s0)
        sharpTap("tap 2", face(!s0), s0)
        select(tool, sel[0])
        sharpTap("one point", face(s0), !s0)
        select(tool, *sel)
        assertEquals("$at: a mix", CornerState.MIXED, (tool as CornerTool).pointCorners?.corner)
        sharpTap("a mix", PointLabels110.MIXED_MAKES_SHARP, true)

        // Object colors: one tap opens the sheet; Back closes it; nothing recorded.
        noStep(s, tool, "$at, Object colors") {
            tap(s, tagged(s, V110Tags.PILL_COLOR, "$at: Object colors"))
            assertEquals("$at: the sheet opened", listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
            Qa19Ui.assertNoTextOverflow("$at, Object colors open")
            Finger.back()
            assertTrue(
                "$at: Back closed the sheet: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}",
                ObjectColorLabels110.CELL !in SmokeUi.sheetTitles() && ObjectColorLabels110.CELL !in SmokeUi.pillTitles(),
            )
        }

        // X and Y: a tap opens the dialog; the typed value moves the selected points (one step).
        val target = (tool as PillPositionTool).pillPosition
        for ((axis, cell) in listOf("X" to X_CELL, "Y" to Y_CELL)) {
            val from = requireNotNull(target.position) { "$at: the selection's position" }
            val v = ((if (axis == "X") from.x else from.y) + 20f).roundToInt()
            oneStep(s, tool, "$at, $axis typed") {
                tap(s, named(s, cell))
                assertTrue("$at: the $axis dialog: ${SmokeUi.shown().take(40)}", SmokeUi.has("$axis$POSITION", exact = true))
                Qa19Ui.assertNoTextOverflow("$at, the $axis dialog")
                SmokeUi.typeAndDone(axis, "$v")
                assertFalse("$at: the $axis dialog closed", SmokeUi.has("$axis$POSITION", exact = true))
            }
            val now = requireNotNull(target.position)
            assertEquals("$at: $axis typed", v.toFloat(), if (axis == "X") now.x else now.y, 0.05f)
            assertEquals("$at: the other axis kept", if (axis == "X") from.y else from.x, if (axis == "X") now.y else now.x, 0.05f)
        }

        // "#", keep and "Scale increments": one tap switches, a second switches back; nothing recorded.
        for (label in listOf(INCREMENTS_LABEL, PillLabels.SCALE_INCREMENTS)) {
            noStep(s, tool, "$at, \"$label\"") {
                tap(s, named(s, label))
                assertTrue("$at: \"$label\" switched increments on", c.increments.state.enabled)
                tap(s, named(s, label))
                assertFalse("$at: \"$label\" switched them off", c.increments.state.enabled)
            }
        }
        noStep(s, tool, "$at, keep") {
            assertEquals(true, switchOn(s, PillLabels.KEEP_PROPORTIONS))
            tap(s, named(s, PillLabels.KEEP_PROPORTIONS))
            assertEquals("$at: keep off", false, switchOn(s, PillLabels.KEEP_PROPORTIONS))
            tap(s, named(s, PillLabels.KEEP_PROPORTIONS))
            assertEquals("$at: keep on", true, switchOn(s, PillLabels.KEEP_PROPORTIONS))
        }

        // Scale X and Scale Y: a tap opens the dialog; the typed value is one step (keep on: both axes).
        val scale = requireNotNull((tool as ScaledTool).objectScale) { "$at: a scale" }
        for ((label, v) in listOf(PillLabels.SCALE_X to 150, PillLabels.SCALE_Y to 120)) {
            oneStep(s, tool, "$at, $label typed") {
                tap(s, named(s, label))
                assertTrue("$at: the $label dialog: ${SmokeUi.shown().take(40)}", SmokeUi.windows().size > 1)
                Qa19Ui.assertNoTextOverflow("$at, the $label dialog")
                SmokeUi.typeAndDone(label, "$v")
                assertEquals("$at: the $label dialog closed", 1, SmokeUi.windows().size)
                // (Read before the Undo: points changed otherwise give the row a new 100 % box.)
                val p = requireNotNull(scale.scalePercent)
                assertEquals("$at: $label typed, X", v.toFloat(), p.x, 0.05f)
                assertEquals("$at: $label typed, Y (keep)", v.toFloat(), p.y, 0.05f)
            }
        }

        // The move icon: folded, the cells in one row, Sharp / Smooth acting there too; unfolded again.
        noStep(s, tool, "$at, fold") { tap(s, named(s, FOLD)) }
        assertTrue(c.settings.coordinateStripFolded)
        assertNull("$at, folded: no X", Finger.control(s, X_CELL))
        assertNull("$at, folded: no Scale row", s.tagged(V17Tags.PILL_SCALE_ROW))
        val row = listOf(
            named(s, UNFOLD),
            tagged(s, V110Tags.PILL_COLOR, "$at, folded: Object colors"),
            tagged(s, V17Tags.PILL_TRASH, "$at, folded: the trash"),
            tagged(s, V18Tags.PILL_SEVERAL, "$at, folded: Select several"),
            tagged(s, V110Tags.PILL_SHARP, "$at, folded: Sharp / Smooth"),
        )
        for (i in 1 until row.size) {
            assertEquals("$at, folded: one row", row[0].center.y, row[i].center.y, EPS)
            assertTrue("$at, folded: in order", row[i - 1].right <= row[i].left + EPS)
        }
        Qa19Ui.assertNoTextOverflow("$at, folded")
        sharpTap("folded", ShapeStripItems.SMOOTH_LABEL, false)
        noStep(s, tool, "$at, unfold") { tap(s, named(s, UNFOLD)) }
        assertFalse(c.settings.coordinateStripFolded)
        named(s, X_CELL)

        // The trash: one tap deletes the selected points (one step).
        val kept = points(tool).at.filterIndexed { i, _ -> i !in sel }
        oneStep(s, tool, "$at, the trash") { tap(s, tagged(s, V17Tags.PILL_TRASH, "$at: the trash")) }
        assertEquals("$at: the other points stay", kept, points(tool).at)

        // "Select several": one tap, off.
        tap(s, tagged(s, V18Tags.PILL_SEVERAL, "$at: Select several"))
        assertEquals("$at: Select several off", "Off", PillTestKit.severalState())
        assertFalse(editor.selectSeveral)

        if (tool is ShapeTool) tool.discard() else (tool as CurveTool).discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(c, at)
    }

    private fun polyline(h: Qa19Harness, width: Int) {
        val s = open(h, ToolId.POLYLINE, width)
        val tool = s.c.currentTool as CurveTool
        for (p in FOUR.take(3)) assertTrue("Polyline: a point at $p", tool.addAnchor(p))
        tool.deselect()
        select(tool, 0, 1)
        tap(s, tagged(s, V18Tags.PILL_SEVERAL, "Polyline: Select several"))
        assertEquals("Polyline: Select several on", "On", PillTestKit.severalState())
        assertEquals(CornerState.ALWAYS_SHARP, tool.pointCorners?.corner)
        val r = tagged(s, V110Tags.PILL_SHARP, "Polyline: Sharp / Smooth")
        assertEquals("Polyline: greyed", true, PillTestKit.sharpGreyed())
        assertEquals(PointLabels110.POLYLINE_ALWAYS_SHARP, PillTestKit.sharpName())
        noStep(s, tool, "Polyline: the long press") {
            Fingers18.longPressAt(s, r)
            assertEquals("Polyline: the long press names it", PointLabels110.POLYLINE_ALWAYS_SHARP, Fingers18.bubble())
        }
        Qa19Ui.assertNoTextOverflow("Polyline at $width dp")
        val anchors = tool.anchors
        noStep(s, tool, "Polyline: a tap") { tap(s, r) }
        assertTrue("Polyline: a tap changes nothing", tool.anchors === anchors)
        assertEquals(PointLabels110.POLYLINE_ALWAYS_SHARP, PillTestKit.sharpName())
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "Polyline at $width dp")
    }

    companion object {
        private const val EPS = 0.5f

        /** Curve's, Path's and Polyline's points (document px). */
        private val FOUR = listOf(Vec2(90f, 190f), Vec2(150f, 100f), Vec2(230f, 110f), Vec2(300f, 190f))

        /** CoordinatePill's literals (no Labels constant holds them): the move icon, the X and Y cells, their dialogs' title. */
        private const val FOLD = "Fold the X / Y strip"
        private const val UNFOLD = "Unfold the X / Y strip"
        private const val X_CELL = "X slider"
        private const val Y_CELL = "Y slider"
        private const val POSITION = " position"

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
