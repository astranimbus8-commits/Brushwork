package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.inkNear
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import com.brushwork.paint.vector.draw.VectorEraserModes
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.hypot

/**
 * v1.8 final QA (verifier), item 1 (design §3.1, I15 "copies are data": the eraser and the Transform
 * tool on an object with live copies) on the user's phone (392 dp), every step by a finger, the
 * Rotation 4 ruler still on. A Curve ("Plain line") of three taps, ✓: one path with four copies.
 * - The eraser, "Partial", across copy 2's middle: ONE "Erase" step; the copies became plain paths
 *   (copy 2 in two pieces), none with copies; copy 2 is cut there, every other copy and copy 2's
 *   ends unchanged; the top row's Undo: the one path with its copies, the exact pixels.
 * - The eraser, "Object", across copy 3's middle: ONE step; the whole path goes, all its copies
 *   with it (no pixel left); Undo: the exact pixels.
 * - Transform, a finger dragging the box: ✓ ONE "transform objects" step; still ONE path with four
 *   copies, each copy moved with the source (the maps conjugated), none left where they were;
 *   Undo: the exact pixels.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsobjectopssandbox"])
class Qa18MirrorsObjectOpsUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun document(): Document = Smoke.document(W, H, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun steps() = c.undoManager.undoCount

    private fun settleRenders(what: String) {
        assertTrue(
            "$what: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering },
        )
        settle()
    }

    /** A unit normal of copy [m] of the source's line at [p] (the source runs level there). */
    private fun normal(m: FloatArray, p: Vec2): Vec2 {
        val a = map(m, p)!!
        val b = map(m, Vec2(p.x, p.y + 1f))!!
        val l = hypot(b.x - a.x, b.y - a.y)
        return Vec2((b.x - a.x) / l, (b.y - a.y) / l)
    }

    /** The eraser in [mode] (its chip tapped by a finger) across copy [k]'s line at its middle point. */
    private fun erase(mode: VectorEraseMode, maps: List<FloatArray>, k: Int, what: String) {
        if (VectorEraserModes.mode(c) != mode) {
            ui.reach(mode.label, 32f)
            Finger.tap(s, mode.label)
            settle()
        }
        assertEquals("$what: the \"${mode.label}\" chip", mode, VectorEraserModes.mode(c))
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        val q = map(maps[k], TAPS[1])!!
        val n = normal(maps[k], TAPS[1])
        val n0 = steps()
        ui.stroke(q.x - 15f * n.x to q.y - 15f * n.y, q.x to q.y, q.x + 15f * n.x to q.y + 15f * n.y)
        settleRenders(what)
        assertEquals("$what: ONE step", n0 + 1, steps())
        assertEquals("$what: the step's name", VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        println("Qa18MirrorsObjectOps $what: said \"${c.message}\"")
    }

    private fun undoExactly(what: String, layer: Layer, path: VPath, px: IntArray) {
        Finger.tap(s, "Undo")
        settleRenders("$what: Undo")
        assertEquals("$what: Undo: the one path with its copies", path, layer.vector!!.objects.single())
        assertArrayEquals("$what: Undo: the exact pixels", px, pixels(layer.bitmap))
    }

    private fun flow(h: ChromeHarness) {
        s = h.editor(document()) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.ROTATION, listOf(divisions("4")))
        val maps = ObjectCopies.mapsAt(c, TAPS[0].x, TAPS[0].y)
        assertEquals("Rotation 4: four maps", 4, maps.size)
        for (m in maps) for ((p, d) in TAPS.map { it to Vec2(0f, 0f) } + (TAPS[1] to MOVE)) {
            val q = map(m, p)!! + d
            assertTrue("every copy of $p (+ $d) on the canvas ($q)", q.x in 4f..(W - 4f) && q.y in 4f..(H - 4f))
        }

        // The curve.
        val layer = c.doc.layers[1]
        c.color = INK
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.PLAIN.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals(3, tool.pointCount)
        val n0 = steps()
        click("Apply curve edit", exact = true)
        settleRenders("the curve")
        assertEquals("the curve: ONE step", n0 + 1, steps())
        val path = layer.vector!!.objects.single() as VPath
        assertEquals("four copies", CopyMaps.of(maps), path.copies)
        val drawn = pixels(layer.bitmap)
        for ((k, m) in maps.withIndex()) for (p in TAPS) assertTrue("copy $k drawn at ${map(m, p)}", inkNear(layer.bitmap, map(m, p)!!, 3) >= 200)

        // The eraser, Partial, across copy 2: the copies expanded, copy 2 cut, in one step.
        ui.tool("Eraser")
        assertEquals(ToolId.ERASER, c.activeToolId)
        c.eraser = c.eraser.copy(size = 12f)
        erase(VectorEraseMode.PARTIAL, maps, 2, "Partial over copy 2")
        val pieces = layer.vector!!.objects
        assertEquals("Partial: the source, copies 1 and 3, copy 2 in two pieces", 5, pieces.size)
        assertTrue("Partial: none with copies", pieces.all { it is VPath && !ObjectCopies.hasCopies(it) })
        assertEquals("Partial: copy 2 cut at ${map(maps[2], TAPS[1])}", 0, inkNear(layer.bitmap, map(maps[2], TAPS[1])!!, 2))
        for ((k, m) in maps.withIndex()) for (p in TAPS) {
            if (k == 2 && p == TAPS[1]) continue
            assertTrue("Partial: copy $k still at ${map(m, p)}", inkNear(layer.bitmap, map(m, p)!!, 3) >= 200)
        }
        Qa18MirrorsKit.shoot(s, "eraser-partial")
        undoExactly("Partial", layer, path, drawn)

        // The eraser, Object, across copy 3: the whole path goes, every copy with it.
        erase(VectorEraseMode.OBJECT, maps, 3, "Object over copy 3")
        assertEquals("Object: nothing left", 0, layer.vector!!.objects.size)
        assertTrue("Object: no pixel left", pixels(layer.bitmap).all { it == 0 })
        undoExactly("Object", layer, path, drawn)

        // Transform: a finger drags the box; the copies move with the source.
        ui.tool("Transform")
        val t = c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil(10_000) { settle(1); t.lifted != null })
        settle()
        assertEquals(TransformTool.Lifted.VECTOR, t.lifted)
        ui.stroke(GRAB.x to GRAB.y, GRAB.x + MOVE.x / 2f to GRAB.y + MOVE.y / 2f, GRAB.x + MOVE.x to GRAB.y + MOVE.y)
        val n1 = steps()
        click("Apply transform edit", exact = true)
        settleRenders("the transform")
        assertEquals("Transform: ONE step", n1 + 1, steps())
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val moved = layer.vector!!.objects.single() as VPath
        assertEquals("the same path", path.id, moved.id)
        assertEquals("still four copies", 4, ObjectCopies.count(moved))
        val p0 = path.subpaths.single().anchors.map { Vec2(it.x, it.y) }
        val p1 = moved.subpaths.single().anchors.map { Vec2(it.x, it.y) }
        val d = p1[0] - p0[0]
        assertTrue("moved by the finger: $d", hypot(d.x - MOVE.x, d.y - MOVE.y) < 2f)
        for (i in p0.indices) assertTrue("point $i moved by $d: ${p0[i]} -> ${p1[i]}", hypot(p1[i].x - p0[i].x - d.x, p1[i].y - p0[i].y - d.y) < 0.05f)
        for ((k, m) in moved.copies.maps.withIndex()) {
            val now = map(m, p1[1])!!
            val want = map(maps[k], p0[1])!! + d
            assertTrue("copy $k of the moved path where copy $k was, moved too: $now vs $want", hypot(now.x - want.x, now.y - want.y) < 0.05f)
        }
        for ((k, m) in maps.withIndex()) {
            assertTrue("copy $k drawn moved at ${map(m, TAPS[1])!! + d}", inkNear(layer.bitmap, map(m, TAPS[1])!! + d, 3) >= 200)
            assertEquals("copy $k gone from ${map(m, TAPS[1])}", 0, inkNear(layer.bitmap, map(m, TAPS[1])!!, 2))
        }
        Qa18MirrorsKit.shoot(s, "transform-copies")
        undoExactly("Transform", layer, path, drawn)
        c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(c, "object ops")
    }

    @Test
    fun theEraserAndTransformTreatTheCopiesAsOneObject() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("eraser and transform on a mirrored path") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        val INK = 0xFF101010.toInt()

        /** The curve's three taps (its line runs level through the middle one). */
        val TAPS = listOf(Vec2(60f, 60f), Vec2(110f, 40f), Vec2(160f, 60f))

        /** Where the finger grabs the Transform box (inside it, away from its handles and pivot), and by how much it moves it. */
        val GRAB = Vec2(150f, 200f)
        val MOVE = Vec2(20f, 20f)
    }
}
