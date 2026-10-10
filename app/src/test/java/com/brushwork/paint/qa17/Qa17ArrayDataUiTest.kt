package com.brushwork.paint.qa17

import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa16.item
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.BLUE
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshVector
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.ink
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.sin

/**
 * v1.7 final QA, item 3 (design §3.3) on the user's 392 dp phone: arrays of data layers that stay
 * editable with their own tools, by finger and label only.
 * - A path drawn with the Path tool on a vector layer, layer ⋮ "Array…", "Array along a curve":
 *   "Draw guide" and a finger stroke on the canvas (the hosted sheet minimizes, the stroke still
 *   reaches the tool), then "Use a path" (a tap on empty canvas says "Tap a vector path to use it
 *   as the guide"; a tap on a path of another layer copies it). The Path tool reopens the source
 *   path, a finger drags its middle point, "Apply path edit": the copies follow (one step).
 * - A text typed with the Text tool, layer ⋮ "Array…", "Array by transform" with Count, Move X,
 *   Turn and Scale per copy typed (a spiral, one step each); the Text tool edits the text
 *   ("Apply text edit", one step) and every copy shows the new text.
 * Every cache is checked against a fresh render of the layer's data (I1, I14).
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.arraydatasandbox"])
class Qa17ArrayDataUiTest {

    @Test
    fun dataArraysStayEditableAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("a vector path along a curve: Draw guide, Use a path, the source path edited") { vectorCurve(Qa17ArrayUi(h)) }
        h.section("a text spiral by transform, then the text edited") { textSpiral(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** Where copy k of [layer]'s array takes the source's centre (document px). */
    private fun copyCentres(layer: Layer): List<Vec2> {
        val d = layer.dataSnapshot()
        val src = requireNotNull(ArrayDraw.sourceBounds(d))
        return ArrayLayout.matrices(d.array!!.spec, src).map { m ->
            Vec2(m[0] * src.centerX() + m[1] * src.centerY() + m[2], m[3] * src.centerX() + m[4] * src.centerY() + m[5])
        }
    }

    private val NO_PATH = "Tap a vector path to use it as the guide"

    private fun assertNear(what: String, expected: Vec2, actual: Vec2, tol: Float = 1.5f) =
        assertTrue("$what: $actual, expected $expected", actual.distanceTo(expected) <= tol)

    // ------------------------------------------------------------------ vector path along a curve

    private fun vectorCurve(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        // What the flow starts from: a path on a vector layer of its own, to be picked as the guide.
        val guideLayer = requireNotNull(c.addVectorLayer("Guide"))
        val guideAnchors = listOf(VAnchor(40f, 120f, sharp = true), VAnchor(200f, 95f, sharp = true), VAnchor(360f, 120f, sharp = true))
        c.vectors.addObjects(guideLayer, listOf(VPath(0, subpaths = listOf(VSubpath(guideAnchors)), stroke = VStrokeStyle(color = BLUE, width = 3f))), "Add")
        val layer = requireNotNull(c.addVectorLayer("Source"))
        settle()
        assertSame(layer, c.activeLayer)

        // The source: an arch tapped out with the Path tool (points far enough apart that a tap
        // adds a point instead of picking the one before).
        u.tool("Path")
        for ((x, y) in listOf(20f to 40f, 60f to 10f, 100f to 40f)) {
            assertTrue("($x, $y) shows on the canvas", u.onCanvas(x, y))
            u.ui.tap(x, y)
        }
        u.applyEdit("Apply path edit")
        assertEquals("the arch is the layer's one object", 1, layer.vector!!.objects.size)
        assertEquals("three points", 3, (layer.vector!!.objects.single() as VPath).spline!!.points.size)

        // Layer ⋮ "Array…": the layer as a whole, in place (one step), the Array tool and its sheet.
        var n = u.steps()
        u.layerMenu(ArrayLabels.OPEN)
        u.settleRenders("array")
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.BUTTON, c.undoManager.undoLabel)
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val tool = c.currentTool as ArrayTool
        u.showArraySheet()
        n = u.steps()
        u.press(ArrayLabels.CURVE)
        u.settleRenders("curve")
        assertEquals(ArrayMode.CURVE, layer.array!!.spec.mode)
        assertEquals(n + 1, u.steps())

        // "Draw guide", then one finger stroke on the canvas above the sheet.
        u.press("Draw guide")
        assertEquals(ArrayTool.GuideInput.DRAW, tool.guideInput)
        val stroke = (0..32).map { i -> (40f + i * 10f) to (70f + 12f * sin(i / 4.0).toFloat()) }
        for ((x, y) in stroke) assertTrue("the stroke at ($x, $y) is on the canvas, not under the sheet", u.onCanvas(x, y))
        n = u.steps()
        u.ui.stroke(*stroke.toTypedArray())
        u.settleRenders("drawn guide")
        assertEquals("the stroke reached the tool: one step", n + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertEquals(ArrayTool.GuideInput.NONE, tool.guideInput)
        val drawn = layer.array!!.spec.guide!!.anchors
        assertNear("the guide starts where the finger went down", Vec2(40f, 70f), Vec2(drawn.first().x, drawn.first().y))
        assertNear("and ends where it lifted", Vec2(stroke.last().first, stroke.last().second), Vec2(drawn.last().x, drawn.last().y))
        var centres = copyCentres(layer)
        assertEquals(3, centres.size)
        assertNear("the last copy moved by the guide's end − start", centres[0] + Vec2(stroke.last().first - 40f, stroke.last().second - 70f), centres[2])
        assertEquals("I1: the cache is the expanded content's render", 0, differing(layer.bitmap, freshVector(c, layer.dataSnapshot())))
        for ((k, p) in centres.withIndex()) assertTrue("copy $k is drawn near $p", ink(layer.bitmap, RectF(p.x - 25f, p.y - 15f, p.x + 25f, p.y + 15f), 100) > 20)

        // "Use a path": a tap on empty canvas says what to do, a tap on the guide path copies it.
        u.showArraySheet()
        u.press("Use a path")
        assertEquals(ArrayTool.GuideInput.PICK, tool.guideInput)
        n = u.steps()
        assertTrue(u.onCanvas(300f, 50f))
        u.ui.tap(300f, 50f)
        assertTrue("\"${NO_PATH}\"; shown: ${SmokeUi.shown().take(60)}", has(NO_PATH, exact = true))
        assertEquals("nothing picked", n, u.steps())
        assertEquals("still waiting for a path", ArrayTool.GuideInput.PICK, tool.guideInput)
        assertTrue(u.onCanvas(200f, 95f))
        u.ui.tap(200f, 95f)
        u.settleRenders("picked")
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        val picked = layer.array!!.spec.guide!!.anchors
        assertEquals("the path's anchors, copied", guideAnchors.map { it.x to it.y }, picked.map { it.x to it.y })
        centres = copyCentres(layer)
        assertNear("copy 1 at the guide's middle", centres[0] + Vec2(160f, -25f), centres[1])
        assertNear("copy 2 at its end", centres[0] + Vec2(320f, 0f), centres[2])
        assertEquals("I1 after the pick", 0, differing(layer.bitmap, freshVector(c, layer.dataSnapshot())))

        // The Path tool reopens the source; a finger drags its middle point down; ✓.
        val spec = layer.array!!.spec
        val copy2Before = ink(layer.bitmap, RectF(centres[2].x - 30f, centres[2].y - 30f, centres[2].x + 30f, centres[2].y + 30f), 100)
        val before = pixels(layer.bitmap)
        u.tool("Path")
        val curve = c.currentTool as CurveTool
        u.ui.tap(60f, 25f)
        assertTrue("a tap on the source's line reopens it", curve.isReopened)
        val pts = curve.spline!!.points
        assertEquals("three points: ${pts.map { it.x to it.y }}", 3, pts.size)
        // The arch's top point (the Path tool adds points before a selected first point, so the order is the tool's).
        val top = pts.indices.minBy { pts[it].y }
        val mid = pts[top]
        assertEquals(Vec2(60f, 10f).distanceTo(Vec2(mid.x, mid.y)), 0f, 0.5f)
        QaCurves.drag(u.s, Vec2(mid.x, mid.y), Vec2(0f, 30f))
        assertEquals("the middle point followed the finger", 40f, curve.spline!!.points[top].y, 1.5f)
        n = u.steps()
        u.applyEdit("Apply path edit")
        assertEquals(n + 1, u.steps())
        assertEquals("Edit path", c.undoManager.undoLabel)
        assertEquals("the array is kept", spec, layer.array?.spec)
        assertEquals(40f, (layer.vector!!.objects.single() as VPath).spline!!.points[top].y, 1.5f)
        assertFalse("the copies changed", before.contentEquals(pixels(layer.bitmap)))
        assertEquals("I1: every copy shows the edited path", 0, differing(layer.bitmap, freshVector(c, layer.dataSnapshot())))
        val c2 = copyCentres(layer)[2]
        val copy2After = ink(layer.bitmap, RectF(c2.x - 30f, c2.y - 30f, c2.x + 30f, c2.y + 30f), 100)
        assertTrue("copy 2 is drawn ($copy2Before → $copy2After)", copy2After > 20)
        Smoke.assertQuiet(c, "vector curve")
    }

    // ------------------------------------------------------------------ text spiral

    private fun textSpiral(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        u.tool("Text")
        val text = c.currentTool as TextTool
        u.ui.tap(120f, 60f)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type("Ab")
        settle()
        click("OK", exact = true)
        u.applyEdit("Apply text edit")
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)

        var n = u.steps()
        u.layerMenu(ArrayLabels.OPEN)
        u.settleRenders("array")
        assertEquals(n + 1, u.steps())
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        u.showArraySheet()
        n = u.steps()
        u.press(ArrayLabels.TRANSFORM)
        u.settleRenders("transform")
        assertEquals(ArrayMode.TRANSFORM, layer.array!!.spec.mode)
        assertEquals(n + 1, u.steps())

        fun typed(label: String, value: String) {
            val k = u.steps()
            u.typeField(label, value)
            u.settleRenders(label)
            assertEquals("\"$label\" typed: one step", k + 1, u.steps())
            assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        }
        typed("Count", "9")
        typed("Move X", "40")
        typed("Turn", "40")
        typed(ArrayLabels.SCALE_PER_COPY, "0.85")
        val spiral = layer.array!!.spec
        assertEquals(9, spiral.count)
        assertEquals(40f, spiral.moveX, 0.01f)
        assertEquals(40f, spiral.turnDeg, 0.01f)
        assertEquals(0.85f, spiral.scale, 0.001f)
        assertEquals("I1: the spiral is the text's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        val onCanvas = copyCentres(layer).filter { it.x in 10f..390f && it.y in 10f..290f }
        assertTrue("most copies are on the canvas: $onCanvas", onCanvas.size >= 6)

        // The Text tool edits the source's text: a tap loads it, a second opens the editor.
        val src = requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot()))
        u.tool("Text")
        u.ui.tap(src.centerX(), src.centerY())
        if (!text.editorOpen) u.ui.tap(src.centerX(), src.centerY())
        assertTrue("the editor opens for the arrayed layer", text.editorOpen && text.editingLayer === layer)
        SmokeUi.field("Text").type("Wow")
        settle()
        click("OK", exact = true)
        n = u.steps()
        u.applyEdit("Apply text edit")
        assertEquals(n + 1, u.steps())
        assertEquals("Edit text", c.undoManager.undoLabel)
        assertEquals("Wow", layer.item().text)
        assertEquals("the spiral is kept", spiral, layer.array?.spec)
        assertEquals("I1: every copy shows the new text", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        u.shot("data-text-spiral")
        Smoke.assertQuiet(c, "text spiral")
    }
}
