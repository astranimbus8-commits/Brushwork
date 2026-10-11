package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.points.PointGizmo
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.ui.vector.POINT_WEIGHT_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs
import kotlin.math.sqrt

/**
 * v1.7 QA (item 1, design §3.1d) on the full editor at the user's phone width (ZTE Axon 50 Lite,
 * 392 dp): the points bar of Curve, Polyline and Path shows "Select all points" (44 dp) without
 * sliding the strip (and, v1.8 F4, the X / Y pill its 40 dp "Select several" cell), every group
 * control and the Stroke / Fill / Both segments are reached by sliding it at their full size, the
 * hint stays on screen, and the group gizmo's corner, edge and rotate handles are hit by a finger 20 dp off their centre (the
 * 44 dp touch), each gesture one in-tool step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class Qa17PointsNarrowUiTest {

    /**
     * No point at the corner or the edge middle the gizmo section drags (SE, E): a point there
     * would win the touch (a point at least as close as a handle wins, as in the Shape tool).
     */
    private val five = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 130f), Vec2(200f, 210f), Vec2(160f, 190f))

    private fun open(s: ChromeScreen, label: String): CurveTool {
        QaCurves.tool(s, label)
        val tool = s.c.currentTool as CurveTool
        for (p in five) assertTrue(tool.addAnchor(p))
        tool.deselect()
        settle()
        return tool
    }

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    private fun points(t: CurveTool): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    /** Where the control labelled [label] is (its clickable node), for a failure message. */
    private fun where(label: String): String {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && !n.config.contains(androidx.compose.ui.semantics.SemanticsActions.OnClick)) n = n.parent
        return n?.let { "${it.boundsInWindow} pos ${it.positionInWindow} size ${it.size}" } ?: "none"
    }

    @Test
    fun pointsAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        for (label in listOf("Curve", "Polyline", "Path")) {
            h.section("$label: the points bar at 392 dp") { bar(h.editor(), label) }
        }
        h.section("Path: the gizmo's handles at 44 dp") { gizmo(h.editor()) }
        dog.interrupt()
        h.finish()
    }

    private fun bar(s: ChromeScreen, label: String) {
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        val tool = open(s, label)
        val ui = Qa16Ui(s)
        val all = PointLabels.SELECT_ALL
        assertTrue("$label: \"$all\" on screen without sliding, $SELECTION_DP dp: ${where(all)}", ui.wholly(all, SELECTION_DP))
        // v1.8 (F4): "Select several" is the X / Y pill's 40 dp cell, not a strip chip.
        val several = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "$label: the pill's \"${PointLabels.SELECT_SEVERAL}\" cell" }
        assertTrue("$label: the cell is on screen: $several", several.left >= -0.5f && several.right <= s.widthDp + 0.5f && several.top >= -0.5f)
        assertTrue("$label: the cell is 40 dp: $several", several.width >= PILL_CELL_DP - 0.5f && several.height >= PILL_CELL_DP - 0.5f)
        PillTestKit.toggleSeveral()
        assertTrue("$label: on", tool.selectSeveral)
        val hint = s.dp(requireNotNull(SmokeUi.find(PointLabels.SEVERAL_HINT, exact = true)).node.boundsInWindow)
        assertTrue("$label: the hint is on screen: $hint", hint.left >= -0.5f && hint.right <= s.widthDp + 0.5f)
        PillTestKit.toggleSeveral()

        click(PointLabels.SELECT_ALL, exact = true)
        assertEquals(5, tool.pointSelection.count)
        // Each control and its size (dp): the chips are the strip's own (as the single point's).
        val group = buildList {
            add(PointLabels.DESELECT_ALL to SELECTION_DP)
            if (tool.isPath) add(POINT_WEIGHT_LABEL to FIELD_DP)
            add(POINT_THICKNESS_LABEL to FIELD_DP)
            if (!tool.polyline) add("Sharp corner" to CHIP_DP)
            add("Delete point" to CHIP_DP)
        }
        for ((l, size) in group) {
            ui.reach(l, size)
            assertTrue("$label: \"$l\" reached and at least $size dp: ${where(l)}", ui.wholly(l, size))
        }
        // v1.8: Stroke / Fill / Both are entries of the Stroke kind dropdown.
        click(CurveLabels17.STROKE_KIND, exact = true)
        for (l in listOf(CurveLabels17.STROKE_ONLY, CurveLabels17.FILL_ONLY, CurveLabels17.BOTH)) {
            assertTrue("$label: \"$l\" in the dropdown and at least $FIELD_DP dp: ${where(l)}", ui.wholly(l, FIELD_DP))
        }
        // The group controls work from where the finger reached them.
        ui.reach(CurveLabels17.FILL_ONLY)
        click(CurveLabels17.FILL_ONLY, exact = true)
        assertTrue(tool.settings.fill)
        tool.discard()
    }

    private fun gizmo(s: ChromeScreen) {
        val c = s.c
        val tool = open(s, "Path")
        click(PointLabels.SELECT_ALL, exact = true)
        // (v1.8: a More-row item; the row closes before the canvas drags.)
        StripTestKit.closeMoreRow()
        val t = c.viewTransform
        val gizmo = PointGizmo()
        fun layout() = requireNotNull(gizmo.layout(points(tool), t))
        val off = t.dp(20f)
        val n = steps(tool)

        // The SE corner, 20 dp out along its diagonal: a proportional scale about the box centre.
        val before = points(tool)
        val l0 = layout()
        val se = l0.cornersScreen[2]
        val diag = (se - t.docToScreen(l0.pivotDoc)).let { it / sqrt(it.x * it.x + it.y * it.y) }
        QaCurves.drag(s, t.screenToDoc(se + diag * off), Vec2(40f, 30f))
        val scaled = points(tool)
        val w0 = before.maxOf { it.x } - before.minOf { it.x }
        val w1 = scaled.maxOf { it.x } - scaled.minOf { it.x }
        assertTrue("the corner scaled the group: $w0 -> $w1", w1 > w0 * 1.1f)
        assertEquals("one in-tool step", n + 1, steps(tool))

        // The E edge middle, 20 dp to its right: one axis.
        val l1 = layout()
        val e = (l1.cornersScreen[1] + l1.cornersScreen[2]) / 2f
        val ys = points(tool).map { it.y }
        QaCurves.drag(s, t.screenToDoc(e + Vec2(off, 0f)), Vec2(30f, 0f))
        val widened = points(tool)
        for (i in ys.indices) assertEquals("the edge keeps y of point $i", ys[i], widened[i].y, 0.05f)
        assertTrue(widened.maxOf { it.x } - widened.minOf { it.x } > w1 + 10f)
        assertEquals(n + 2, steps(tool))

        // The rotate knob, 20 dp to its right: the group turns about the box centre.
        val l2 = layout()
        val pivot = l2.pivotDoc
        val r0 = points(tool).map { (it - pivot).let { d -> sqrt(d.x * d.x + d.y * d.y) } }
        QaCurves.drag(s, t.screenToDoc(l2.rotateHandleScreen + Vec2(off, 0f)), Vec2(80f, 40f))
        val turned = points(tool)
        for (i in r0.indices) {
            val r = (turned[i] - pivot).let { d -> sqrt(d.x * d.x + d.y * d.y) }
            assertEquals("point $i keeps its distance to the centre", r0[i], r, 0.05f + r0[i] * 1e-4f)
        }
        assertTrue("turned", turned.zip(widened).any { (a, b) -> abs(a.x - b.x) > 1f })
        assertEquals(n + 3, steps(tool))
        tool.discard()
    }

    private companion object {
        /** "Select all points" / "Deselect all points": v1.8 More-row icons, the 40 dp touch minimum (§3.9). */
        const val SELECTION_DP = 40f
        /** v1.8 (F4): the X / Y pill's "Select several" cell (`IbisDims.PillIconTouch`). */
        const val PILL_CELL_DP = 40f
        /** The thickness and weight fields and the Stroke / Fill / Both segments (§3.7a). */
        const val FIELD_DP = 40f
        /** The strip's chips ("Sharp corner", "Delete point"), as for a single point since v1.6. */
        const val CHIP_DP = 32f
    }
}
