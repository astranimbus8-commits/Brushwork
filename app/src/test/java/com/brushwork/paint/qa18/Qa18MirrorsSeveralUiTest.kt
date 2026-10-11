package com.brushwork.paint.qa18

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 final QA, item 1 (design §3.2, the user's "Put the select several as a small icon right
 * below or besides the X Y scale and transform") on the user's phone (392 dp), every step by a
 * finger. In Curve, Polyline, Path, Shape's Points mode and Free deform:
 * - "Select several" (tag [V18Tags.PILL_SEVERAL]) is a finger-sized cell at the END of the X / Y
 *   pill's first row (right of the fold cell, X, Y, "Increments" and the trash cell, beside the last
 *   of them), the scale row under that row;
 * - "Fold the X / Y strip": the cell stays, right after the fold (and trash) cell, and works: on,
 *   three taps pick three points, one drag moves them together (the others stay), the top row's
 *   Undo takes the drag back whole; off; "Unfold the X / Y strip" brings X and Y back.
 * Then a Curve on a vector layer under a Kaleidoscope 3 ruler: three points dragged together, ✓:
 * the path keeps the ruler's six maps and every copy shows the moved points.
 * Renders: `mirrors-several-<tool>-screen.png` (folded, three points picked), `mirrors-several-kaleidoscope.png`.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsseveralsandbox"])
class Qa18MirrorsSeveralUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun attach(screen: ChromeScreen) {
        s = screen
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
    }

    private fun points(t: PointEditor): List<Vec2> = (0 until t.pointCount).map { t.pointAt(it) }

    private fun assertAll(what: String, expected: List<Vec2>, t: PointEditor, tol: Float) {
        val now = points(t)
        assertEquals("$what: point count", expected.size, now.size)
        for (i in expected.indices) assertTrue("$what: point $i ${expected[i]} vs ${now[i]}", abs(expected[i].x - now[i].x) <= tol && abs(expected[i].y - now[i].y) <= tol)
    }

    private fun cell(what: String): Rect = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "$what: no \"Select several\" cell; shown: ${SmokeUi.shown().take(80)}" }

    /**
     * The cell ends the pill's first row: on screen, finger-sized, on the fold cell's row, right of
     * every other row-1 cell (fold; X, Y and "Increments" unless [folded]; the trash cell), next to
     * the last of them; the scale row (when there is one) under the row, none while [folded].
     */
    private fun assertAtRowEnd(what: String, folded: Boolean) = Qa18MirrorsKit.severalAtRowEnd(s, what, folded)

    private fun tapCell(t: PointEditor, on: Boolean, what: String) {
        val r = cell(what)
        Finger.tapAt(s, r.center.x, r.center.y)
        assertEquals("$what: Select several", on, t.selectSeveral)
        assertEquals("$what: the cell's state", if (on) "On" else "Off", PillTestKit.severalState())
    }

    /** The cell on; [picked] tapped; dragged from [grab] by [BY]: they move together, the others stay; Undo takes it back; off. */
    private fun groupDrag(t: PointEditor, what: String, picked: List<Int>, grab: Int, tol: Float, shot: String? = null): List<Vec2> {
        tapCell(t, on = true, what = "$what, on")
        val start = points(t)
        // A point picked already (Shape, Free deform: the plain tap that gave the pill a position)
        // stays picked when the cell goes on; a tap on it would unpick it.
        for (i in picked) if (i !in t.pointSelection.indices) QaCurves.tap(s, start[i])
        assertEquals("$what: the taps picked", picked, t.pointSelection.indices.sorted())
        assertAll("$what: picking moved nothing", start, t, tol)
        if (shot != null) Qa18MirrorsKit.shoot(s, shot)
        QaCurves.drag(s, start[grab], BY)
        val d = t.pointAt(grab) - start[grab]
        assertTrue("$what: the grabbed point moved: $d", d.length > 5f)
        val moved = start.mapIndexed { i, p -> if (i in picked) p + d else p }
        assertAll("$what: dragged together", moved, t, tol)
        Finger.tap(s, "Undo")
        settle()
        assertAll("$what: ONE Undo takes the drag back", start, t, tol)
        Finger.tap(s, "Redo")
        settle()
        assertAll("$what: Redo", moved, t, tol)
        tapCell(t, on = false, what = "$what, off")
        return moved
    }

    /** The cell's place unfolded, then folded (where the group drag runs), then unfolded again. */
    private fun foldedRun(t: PointEditor, what: String, picked: List<Int>, grab: Int, tol: Float) {
        assertAtRowEnd("$what, unfolded", folded = false)
        Finger.tap(s, "Fold the X / Y strip")
        settle()
        assertAtRowEnd("$what, folded", folded = true)
        groupDrag(t, "$what, folded", picked, grab, tol, shot = "several-${what.lowercase().replace(' ', '-')}")
        Finger.tap(s, "Unfold the X / Y strip")
        settle()
        assertAtRowEnd("$what, unfolded again", folded = false)
    }

    // ------------------------------------------------------------------ sections

    private fun curveTool(h: ChromeHarness, label: String) {
        attach(h.editor())
        QaCurves.tool(s, label)
        val tool = c.currentTool as CurveTool
        QaCurves.snapOff(c)
        for (p in FIVE) QaCurves.tap(s, p)
        assertEquals("$label: five points", 5, tool.pointCount)
        foldedRun(tool, label, picked = listOf(1, 2, 3), grab = 2, tol = 0.05f)
        tool.discard()
        settle()
        Smoke.assertQuiet(c, label)
    }

    private fun shapePoints(h: ChromeHarness) {
        attach(h.editor())
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        QaCurves.drag(s, Vec2(60f, 70f), Vec2(170f, 150f))
        assertNotNull("a pending rectangle", tool.box)
        ui.reach("Points", 32f)
        settle()
        Finger.tap(s, "Points")
        assertTrue(tool.pointsMode)
        assertEquals(4, tool.pointCount)
        // A corner picked by a plain tap (nothing moves): the pill has a position, so its full first row.
        QaCurves.tap(s, tool.pointAt(0))
        foldedRun(tool, "Shape points", picked = listOf(0, 1, 2), grab = 0, tol = 0.05f)
        tool.discard()
        settle()
        Smoke.assertQuiet(c, "Shape")
    }

    private fun freeDeform(h: ChromeHarness) {
        attach(h.editor())
        c.selectLayer(0)
        settle()
        QaCurves.tool(s, "Transform")
        val tool = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); tool.transformState != null })
        settle()
        ui.reach(TransformTool.Mode.FREE.label, 32f)
        Finger.tap(s, TransformTool.Mode.FREE.label)
        settle()
        ui.reach(TransformLabels17.FREE_DEFORM, 32f)
        SmokeUi.tap(TransformLabels17.FREE_DEFORM, exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); tool.isMeshShown })
        settle()
        val stride = tool.meshColumns + 1
        QaCurves.tap(s, tool.pointAt(stride + 1))
        foldedRun(tool, "Free deform", picked = listOf(stride + 1, stride + 2, 2 * stride + 1), grab = stride + 1, tol = 0.5f)
        tool.discard()
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "Free deform")
    }

    private fun kaleidoscopeCurve(h: ChromeHarness) {
        val doc: Document = Smoke.document(W, H, layers = 1, whiteBottom = true).also { d ->
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 1
        }
        attach(h.editor(doc) { it.snapping.enabled = false })
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.KALEIDOSCOPE, listOf(divisions("3")))
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        for (p in WEDGE) ui.tap(p.x, p.y)
        assertEquals("four points (no tap picked a copy's point)", WEDGE.size, tool.pointCount)
        assertTrue("the copies follow while it is drawn", tool.hasCopies)
        val maps = ObjectCopies.mapsAt(c, tool.pointAt(0).x, tool.pointAt(0).y)
        assertEquals("Kaleidoscope 3: six maps", 6, maps.size)
        val moved = groupDrag(tool, "Kaleidoscope curve", picked = listOf(0, 1, 2), grab = 1, tol = 0.05f)
        val layer = c.doc.activeLayer
        val n0 = c.undoManager.undoCount
        click("Apply curve edit", exact = true)
        assertTrue("landed", Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && !c.vectors.isRendering })
        settle()
        assertEquals("✓: one step", n0 + 1, c.undoManager.undoCount)
        val path = layer.vector!!.objects.single() as VPath
        assertEquals("the path keeps the ruler's maps", CopyMaps.of(maps), path.copies)
        var seen = 0
        for ((k, m) in maps.withIndex()) for (p in moved) {
            val q = map(m, p) ?: continue
            if (q.x < 6f || q.y < 6f || q.x > W - 6f || q.y > H - 6f) continue
            seen++
            var best = 0
            for (y in q.y.toInt() - 3..q.y.toInt() + 3) for (x in q.x.toInt() - 3..q.x.toInt() + 3) {
                if (x in 0 until W && y in 0 until H) best = maxOf(best, layer.bitmap.getPixel(x, y) ushr 24)
            }
            assertTrue("copy $k shows the dragged point at $q", best >= 200)
        }
        assertTrue("copy spots looked at: $seen", seen >= 18)
        Qa18MirrorsKit.png(Qa18MirrorsKit.flat(c), "several-kaleidoscope")
        Smoke.assertQuiet(c, "Kaleidoscope")
    }

    @Test
    fun selectSeveralEndsThePillRowFoldedTooAndMovesMirroredPoints() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        for (label in listOf("Curve", "Polyline", "Path")) h.section(label) { curveTool(h, label) }
        h.section("Shape points") { shapePoints(h) }
        h.section("Free deform") { freeDeform(h) }
        h.section("Kaleidoscope curve") { kaleidoscopeCurve(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        val FIVE = listOf(Vec2(80f, 200f), Vec2(140f, 110f), Vec2(200f, 190f), Vec2(260f, 110f), Vec2(320f, 200f))
        val BY = Vec2(10f, 20f)

        /** Four taps 50 px apart in one wedge of the Kaleidoscope 3 (their mirror images 60 px or more away). */
        val WEDGE = listOf(Vec2(40f, 150f), Vec2(90f, 125f), Vec2(140f, 150f), Vec2(90f, 175f))
    }
}
