package com.brushwork.paint.tools.select.magnetic

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.8 (C, design §3.4): the magnetic lasso on a black disc (radius 100) on white. Four anchors on
 * its rim make the disc, not the diamond of their straight lines (the segments follow the rim);
 * the selection mode combines it with a selection made before (New, Add, Subtract, Intersect),
 * as ONE "Magnetic lasso" step. Refer: the active layer's pixels or the canvas's; snapping moves a
 * tap onto the rim, and stays put with it off. Until the edge map is ready, segments are straight
 * (and dashed).
 */
@RunWith(RobolectricTestRunner::class)
class MagneticModesTest {

    private val cx = 200f
    private val cy = 150f
    private val r = 100f

    /** The disc on the bottom layer; the active layer is the empty one above it unless [onDisc]. */
    private fun setUp(onDisc: Boolean = true, source: SampleSource = SampleSource.LAYER, ready: Boolean = true): Pair<EditorController, MagneticLassoTool> {
        ShadowLog.stream = null
        val doc = Smoke.document(400, 300, whiteBottom = true)
        Canvas(doc.layers[0].bitmap).drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF000000.toInt() })
        if (onDisc) doc.activeLayerIndex = 0
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        val tool = c.tools.getValue(ToolId.MAGNETIC_LASSO) as MagneticLassoTool
        tool.updateSettings(tool.settings.copy(source = source))
        c.selectTool(ToolId.MAGNETIC_LASSO)
        if (ready) assertTrue("the edge map is built", Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        return c to tool
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    /** Taps the disc's four rim points and closes on the first. */
    private fun outline(c: EditorController, t: MagneticLassoTool) {
        c.tap(cx - r, cy); c.tap(cx, cy - r); c.tap(cx + r, cy); c.tap(cx, cy + r)
        assertTrue(Smoke.pumpUntil { t.idle })
        c.tap(cx - r, cy)
        assertTrue(Smoke.pumpUntil { t.idle })
    }

    private fun area(s: Selection): Double {
        var sum = 0.0
        for (y in 0 until s.height) for (x in 0 until s.width) sum += s.alphaAt(x, y) / 255.0
        return sum
    }

    @Test
    fun fourRimAnchorsSelectTheDisc() {
        val (c, t) = setUp()
        outline(c, t)
        val s = c.selection!!
        val disc = PI * r * r
        val a = area(s)
        assertTrue("selected $a px, the disc is $disc (the anchors' diamond ${2 * r * r})", abs(a - disc) < 0.04 * disc)
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        Smoke.assertQuiet(c, "magnetic disc")
    }

    @Test
    fun theFourModesCombineWithTheSelectionBefore() {
        val (c, t) = setUp()
        val left = Selection.fromPath(Path().apply { addRect(0f, 0f, 200f, 300f, Path.Direction.CW) }, 400, 300, false)
        // (x, y) -> alpha after each mode: inside both, the disc only, the left half only, neither.
        val probes = listOf(150 to 150, 250 to 150, 50 to 150, 380 to 20)
        val expected = mapOf(
            SelectionMode.REPLACE to listOf(255, 255, 0, 0),
            SelectionMode.ADD to listOf(255, 255, 255, 0),
            SelectionMode.SUBTRACT to listOf(0, 0, 255, 0),
            SelectionMode.INTERSECT to listOf(255, 0, 0, 0),
        )
        for ((mode, alphas) in expected) {
            c.setSelection(left.copy(), label = "Base")
            val steps = c.undoManager.undoCount
            t.mode = mode
            outline(c, t)
            val s = c.selection!!
            assertEquals("$mode", alphas, probes.map { (x, y) -> s.alphaAt(x, y) })
            assertEquals("$mode: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        }
    }

    @Test
    fun referLayerOrCanvas() {
        // The active layer is empty: with Refer "Layer" there are no edges to follow.
        val (c, t) = setUp(onDisc = false, source = SampleSource.LAYER)
        val map = t.edgeMap!!
        assertTrue("no edges", (0 until map.height).all { y -> (0 until map.width).all { x -> map.at(x, y) == 0 } })
        outline(c, t)
        val diamond = 2 * r * r
        val a = area(c.selection!!)
        assertTrue("straight segments: $a px, the diamond is $diamond", abs(a - diamond) < 0.04 * diamond)
        // Refer "Canvas": the disc's rim again (the map is built anew).
        c.deselect()
        t.updateSettings(t.settings.copy(source = SampleSource.CANVAS))
        assertFalse(t.edgeMapReady)
        assertTrue(Smoke.pumpUntil { t.edgeMapReady && t.idle })
        outline(c, t)
        assertTrue(abs(area(c.selection!!) - PI * r * r) < 0.04 * PI * r * r)
    }

    @Test
    fun aTapSnapsToTheRimWithinReach() {
        val (c, t) = setUp()
        // 15 px inside the rim: the anchor lands on it.
        c.tap(cx - r + 15f, cy)
        val d = hypot(t.points.x(0) - cx, t.points.y(0) - cy)
        assertTrue("the anchor is ${abs(d - r)} px off the rim", abs(d - r) <= 1.5f)
        t.discard()
        // Out of reach (40 px inside, reach 24 px): it stays.
        c.tap(cx - r + 40f, cy)
        assertEquals(cx - r + 40f, t.points.x(0))
        t.discard()
        // Snapping off: it stays.
        t.updateSettings(t.settings.copy(snapToEdge = false))
        c.tap(cx - r + 15f, cy)
        assertEquals(cx - r + 15f, t.points.x(0))
        assertEquals(cy, t.points.y(0))
    }

    @Test
    fun untilTheMapIsReadySegmentsAreStraight() {
        val (c, t) = setUp(ready = false)
        assertFalse(t.edgeMapReady)
        c.tap(cx - r, cy); c.tap(cx, cy - r)
        assertFalse("not traced yet", t.isTraced(0))
        assertTrue(Smoke.pumpUntil { t.edgeMapReady && t.idle })
        assertTrue("traced once the map is ready", t.isTraced(0))
        // The traced segment runs along the rim, not the chord.
        val s = t.points.segment(0)!!
        for (k in 0 until s.size / 2) {
            val d = hypot(s[2 * k] - cx, s[2 * k + 1] - cy)
            assertTrue("point $k is ${abs(d - r)} px off the rim", abs(d - r) <= 2f)
        }
        // Undo / redo bring the traced segment back without tracing again.
        c.undo()
        c.redo()
        assertTrue(t.isTraced(0))
    }
}
