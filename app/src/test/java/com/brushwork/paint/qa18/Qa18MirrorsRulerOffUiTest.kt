package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.inkNear
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.8 final QA (verifier), item 1 (design §3.1 "Reopening keeps the STORED maps, whatever the
 * symmetry ruler is now" and "Remove mirror copies") on the user's phone (392 dp), every step by a
 * finger. Under a Rotation 4 ruler: a Curve ("Plain line") and a rectangle on two vector layers,
 * and a rectangle on the raster layer (its own shape layer). Then the ruler goes OFF (Symmetry ›
 * the ruler dropdown › "Off" › Done), and:
 * - a tap on a copy of the curve reopens it with its four stored copies; a point dragged, ✓: one
 *   step, every copy follows;
 * - reopened again, the strip's More row › "Remove mirror copies": one in-tool step (the top
 *   row's Undo brings the copies back, Redo drops them again), the item leaves the row; ✓: one
 *   step, the path stores no copies and only the source is drawn; Undo brings the live copies back
 *   with the exact pixels;
 * - the same "Remove mirror copies" on the rectangle of the vector layer, reopened from a copy;
 * - the shape layer reopened by a tap on a copy's edge keeps its copies; a corner dragged, ✓: one
 *   step, every copy follows; Undo gives the exact pixels back.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsrulleroffsandbox"])
class Qa18MirrorsRulerOffUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    private fun document(): Document = Smoke.document(W, H, layers = 2, whiteBottom = true).also { d ->
        for (name in listOf(CURVE_LAYER, SHAPE_LAYER)) {
            d.layers += Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        }
        d.activeLayerIndex = 1
    }

    private fun layer(name: String): Layer = c.doc.layers.single { it.name == name }

    private fun steps() = c.undoManager.undoCount

    private fun settleRenders(what: String) {
        assertTrue(
            "$what: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering },
        )
        settle()
    }

    /** The floating ✓: ONE step, everything landed. */
    private fun apply(label: String, what: String) {
        val n0 = steps()
        click(label, exact = true)
        settleRenders(what)
        assertEquals("$what: ONE step", n0 + 1, steps())
    }

    /** Ink at every copy of [now], none within 2 px of every copy of [was]. */
    private fun assertFollowed(what: String, layer: Layer, maps: List<FloatArray>, now: Vec2, was: Vec2) {
        for ((k, m) in maps.withIndex()) {
            assertTrue("$what: copy $k shows the point at ${map(m, now)}", inkNear(layer.bitmap, map(m, now)!!, 3) >= 200)
            assertEquals("$what: copy $k no longer at ${map(m, was)}", 0, inkNear(layer.bitmap, map(m, was)!!, 2))
        }
    }

    /** The source's ink at [p], none at any copy of [p]. */
    private fun assertOnlySource(what: String, layer: Layer, maps: List<FloatArray>, p: Vec2) {
        assertTrue("$what: the source still drawn at $p", inkNear(layer.bitmap, p, 3) >= 200)
        for (k in 1 until maps.size) assertEquals("$what: copy $k gone from ${map(maps[k], p)}", 0, inkNear(layer.bitmap, map(maps[k], p)!!, 3))
    }

    /** The strip's More row › "Remove mirror copies", by finger. */
    private fun removeCopiesByFinger(what: String) {
        if (!StripTestKit.moreRowShown()) Finger.tap(s, StripLabels18.MORE_TOGGLE)
        assertTrue("$what: the More row is open", StripTestKit.moreRowShown())
        Finger.tap(s, MirrorLabels18.REMOVE)
        settle()
        assertFalse("$what: the item leaves the row", SmokeUi.has(MirrorLabels18.REMOVE, exact = true))
        if (StripTestKit.moreRowShown()) Finger.tap(s, StripLabels18.MORE_TOGGLE)
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
        for (p in TAPS + listOf(BOX_A, BOX_B, RBOX_A, RBOX_B, RBOX_B + DRAG, TAPS[1] + Vec2(0f, MOVE))) for (m in maps) {
            val q = map(m, p)!!
            assertTrue("every copy of $p on the canvas ($q)", q.x in 4f..(W - 4f) && q.y in 4f..(H - 4f))
        }

        // Under the ruler: the curve, the vector rectangle, the shape-layer rectangle.
        val curves = layer(CURVE_LAYER)
        c.selectLayer(curves)
        c.color = INK
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.PLAIN.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals(3, tool.pointCount)
        apply("Apply curve edit", "the curve")
        val drawn = curves.vector!!.objects.single() as VPath
        assertEquals("the path keeps the ruler's maps", CopyMaps.of(maps), drawn.copies)

        val shapes = layer(SHAPE_LAYER)
        c.selectLayer(shapes)
        settle()
        ui.tool("Shape")
        val st = c.currentTool as ShapeTool
        ui.stroke(BOX_A.x to BOX_A.y, (BOX_A.x + BOX_B.x) / 2f to (BOX_A.y + BOX_B.y) / 2f, BOX_B.x to BOX_B.y)
        apply("Apply shape edit", "the vector rectangle")
        val box = shapes.vector!!.objects.single() as VShape
        assertEquals("the rectangle keeps the ruler's maps", CopyMaps.of(maps), box.shape.copies)

        val raster = c.doc.layers[1]
        c.selectLayer(raster)
        settle()
        val before = c.doc.layers.map { it.id }.toSet()
        ui.stroke(RBOX_A.x to RBOX_A.y, (RBOX_A.x + RBOX_B.x) / 2f to (RBOX_A.y + RBOX_B.y) / 2f, RBOX_B.x to RBOX_B.y)
        apply("Apply shape edit", "the shape-layer rectangle")
        val shapeLayer = c.doc.layers.single { it.id !in before }
        assertTrue("its own shape layer", shapeLayer.isShapeLayer)
        assertEquals("the shape layer keeps the ruler's maps", CopyMaps.of(maps), ShapeCodec.decode(shapeLayer.shapeData!!)!!.copies)

        // The ruler off.
        Qa18MirrorsKit.rulerOff(ui)
        assertTrue("no maps now", ObjectCopies.mapsAt(c, TAPS[0].x, TAPS[0].y).isEmpty())

        // The curve reopened from a copy keeps its stored copies; a point dragged; every copy follows.
        c.selectLayer(curves)
        settle()
        ui.tool("Curve")
        val onCopy = map(maps[2], TAPS[1])!!
        ui.tap(onCopy.x, onCopy.y)
        assertTrue("ruler off: a tap on copy 2 at $onCopy reopens the path", tool.isReopened)
        assertTrue("ruler off: the curve with its stored copies", tool.hasCopies)
        assertEquals("the four stored maps", CopyMaps.of(maps), CopyMaps.of(tool.copyMapsNow))
        QaCurves.drag(s, TAPS[1], Vec2(0f, MOVE))
        val moved = tool.pointAt(1)
        assertTrue("the point went down by $MOVE: $moved", abs(moved.x - TAPS[1].x) < 1.5f && abs(moved.y - (TAPS[1].y + MOVE)) < 1.5f)
        apply("Apply curve edit", "ruler off, the point moved")
        val bent = curves.vector!!.objects.single() as VPath
        assertEquals("the same path", drawn.id, bent.id)
        assertEquals("ruler off: the same copies", drawn.copies, bent.copies)
        assertFollowed("ruler off, the point moved", curves, maps, moved, TAPS[1])
        val bentPixels = pixels(curves.bitmap)

        // Reopened again; "Remove mirror copies": one in-tool step; ✓ stores none.
        val onCopy3 = map(maps[3], moved)!!
        ui.tap(onCopy3.x, onCopy3.y)
        assertTrue("a tap on copy 3 at $onCopy3 reopens the path", tool.isReopened && tool.hasCopies)
        removeCopiesByFinger("the curve")
        assertFalse("Remove mirror copies: none left", tool.hasCopies)
        assertTrue("still open", tool.isReopened)
        Finger.tap(s, "Undo")
        settle()
        assertTrue("the top row's Undo: the copies are back", tool.hasCopies)
        assertTrue("still open after the in-tool Undo", tool.isReopened)
        assertTrue("the item is back in the More row", StripTestKit.reach { SmokeUi.find(MirrorLabels18.REMOVE, exact = true) } != null)
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        Finger.tap(s, "Redo")
        settle()
        assertFalse("the top row's Redo: removed again", tool.hasCopies)
        apply("Apply curve edit", "the copies removed")
        val single = curves.vector!!.objects.single() as VPath
        assertEquals("the same path", drawn.id, single.id)
        assertEquals("no copies stored", CopyMaps.NONE, single.copies)
        assertFalse(ObjectCopies.hasCopies(single))
        assertOnlySource("the copies removed", curves, maps, moved)
        Finger.tap(s, "Undo")
        settleRenders("Undo the removal")
        assertEquals("Undo: the path with its copies", bent, curves.vector!!.objects.single())
        assertArrayEquals("Undo: the exact pixels", bentPixels, pixels(curves.bitmap))

        // The vector rectangle reopened from a copy; "Remove mirror copies"; ✓.
        c.selectLayer(shapes)
        settle()
        ui.tool("Shape")
        val top = Vec2(box.shape.cx, box.shape.cy - box.shape.h / 2f)
        val onBoxCopy = map(maps[2], top)!!
        ui.tap(onBoxCopy.x, onBoxCopy.y)
        assertNotNull("ruler off: a tap on copy 2's edge at $onBoxCopy reopens the rectangle", st.box)
        assertTrue("ruler off: the rectangle with its stored copies", st.hasCopies)
        removeCopiesByFinger("the rectangle")
        assertFalse("Remove mirror copies: none left", st.hasCopies)
        apply("Apply shape edit", "the rectangle's copies removed")
        val plainBox = shapes.vector!!.objects.single() as VShape
        assertEquals("the same rectangle", box.id, plainBox.id)
        assertEquals("no copies stored", CopyMaps.NONE, plainBox.shape.copies)
        assertOnlySource("the rectangle's copies removed", shapes, maps, top)

        // The shape layer reopened by a tap on a copy's edge keeps its copies; a corner dragged.
        c.selectLayer(shapeLayer)
        settle()
        val so = ShapeCodec.decode(shapeLayer.shapeData!!)!!
        val corner = Vec2(so.cx + so.w / 2f, so.cy + so.h / 2f)
        assertTrue("the corner where the finger let go: $corner", abs(corner.x - RBOX_B.x) < 2f && abs(corner.y - RBOX_B.y) < 2f)
        val layerPixels = pixels(shapeLayer.bitmap)
        val onLayerCopy = map(maps[1], Vec2(so.cx, so.cy - so.h / 2f))!!
        ui.tap(onLayerCopy.x, onLayerCopy.y)
        assertNotNull("ruler off: a tap on copy 1's edge at $onLayerCopy reopens the shape layer", st.box)
        assertTrue("ruler off: the shape layer with its stored copies", st.hasCopies)
        QaCurves.drag(s, corner, DRAG)
        apply("Apply shape edit", "the shape layer's corner dragged")
        val edited = ShapeCodec.decode(shapeLayer.shapeData!!)!!
        assertEquals("ruler off: the same copies", CopyMaps.of(maps), edited.copies)
        val corner2 = Vec2(edited.cx + edited.w / 2f, edited.cy + edited.h / 2f)
        assertTrue("the corner went by $DRAG: $corner2", abs(corner2.x - corner.x - DRAG.x) < 2.5f && abs(corner2.y - corner.y - DRAG.y) < 2.5f)
        assertFollowed("the shape layer's corner dragged", shapeLayer, maps, corner2, corner)
        Qa18MirrorsKit.shoot(s, "ruler-off")
        Finger.tap(s, "Undo")
        settleRenders("Undo the corner")
        assertArrayEquals("Undo: the shape layer's exact pixels", layerPixels, pixels(shapeLayer.bitmap))
        Smoke.assertQuiet(c, "ruler off")
    }

    @Test
    fun storedCopiesOutliveTheRulerAndRemoveMirrorCopiesDropsThem() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("ruler off: reopened, edited, copies removed") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        const val CURVE_LAYER = "Vector curve"
        const val SHAPE_LAYER = "Vector shape"
        val INK = 0xFF101010.toInt()

        /** The curve's three taps (50 px apart), its middle point dragged down by [MOVE]. */
        val TAPS = listOf(Vec2(60f, 60f), Vec2(110f, 40f), Vec2(160f, 60f))
        const val MOVE = 40f

        /** The vector rectangle's drag. */
        val BOX_A = Vec2(60f, 100f)
        val BOX_B = Vec2(110f, 140f)

        /** The shape-layer rectangle's drag, then its corner dragged by [DRAG]. */
        val RBOX_A = Vec2(240f, 40f)
        val RBOX_B = Vec2(280f, 80f)
        val DRAG = Vec2(20f, 20f)
    }
}
