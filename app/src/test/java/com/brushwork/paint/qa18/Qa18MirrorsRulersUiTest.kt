package com.brushwork.paint.qa18

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.divisions
import com.brushwork.paint.qa18.Qa18MirrorsKit.localScale
import com.brushwork.paint.qa18.Qa18MirrorsKit.map
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.qa18.Qa18MirrorsKit.spacingX
import com.brushwork.paint.qa18.Qa18MirrorsKit.spacingY
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.PerspectiveBakeKit
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
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
 * v1.8 final QA, item 1 (design §3.1): Curve, Polyline, Path and Shape under each symmetry ruler
 * (Mirror; Kaleidoscope 3; Rotation 4; Array 150 × 150; Perspective array with its default cell)
 * on the user's phone (392 dp), on a raster layer and on vector layers, every step by a finger
 * (the ruler through the Symmetry tool, the taps and drags on the canvas, the floating ✓):
 * - each commit is ONE step, drawn where every copy's map takes the path's ends (or the shape's
 *   corners) on the canvas;
 * - raster layer: the curves are pixels; the shape ("Editable (own layer)" on, as it comes) goes
 *   to a shape layer keeping its copies, except under the Perspective array, where it is painted
 *   into the layer with "Perspective copies are drawn as pixels";
 * - vector layer: one object keeping the ruler's maps as its copies; under the Perspective array
 *   plain paths of their own ("Perspective copies become separate paths", once); the top row's
 *   Undo takes a commit back whole, its Redo brings it back.
 * Each tool draws on a vector layer of its own (a tap on an object's copy reopens that object,
 * and every ruler spreads copies over the canvas) and in a color of its own on the raster layer.
 * Renders: `mirrors-<ruler>-curves.png` (the document) and `-screen.png` (the editor).
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.mirrorsrulerssandbox"])
class Qa18MirrorsRulersUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    /** A white page, a clear raster layer (active) and an empty vector layer per tool on top. */
    private fun document(): Document = Smoke.document(W, H, layers = 2, whiteBottom = true).also { d ->
        for (t in TOOLS) d.layers += Layer(d.newLayerId(), "Vector $t", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun steps() = c.undoManager.undoCount

    /** The floating ✓ ("Apply <tool> edit"), then whatever it starts has landed. */
    private fun apply(label: String) {
        click(label, exact = true)
        settleRenders(label)
    }

    private fun settleRenders(what: String) {
        assertTrue(
            "$what: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending },
        )
        settle()
    }

    private fun said(text: String): Boolean = Smoke.pumpUntil(10_000) { settle(1); SmokeUi.has(text, exact = true) }

    /**
     * Where [maps] take [probes] on the canvas (6 px in, where the copy is at least half size):
     * the ones where nothing changed between [before] and [after] within 4 px; [seen] counts the
     * places looked at.
     */
    private fun missing(before: IntArray, after: IntArray, maps: List<FloatArray>, probes: List<Vec2>, seen: IntArray): List<String> {
        val out = mutableListOf<String>()
        for ((k, m) in maps.withIndex()) for (p in probes) {
            val q = map(m, p) ?: continue
            if (q.x < 6f || q.y < 6f || q.x > W - 6f || q.y > H - 6f || localScale(m, p) < 0.5f) continue
            seen[0]++
            val x0 = q.x.toInt()
            val y0 = q.y.toInt()
            var hit = false
            for (y in y0 - 4..y0 + 4) for (x in x0 - 4..x0 + 4) {
                if (x in 0 until W && y in 0 until H && before[y * W + x] != after[y * W + x]) hit = true
            }
            if (!hit) out += "copy $k of $p at $q"
        }
        return out
    }

    private fun assertDrawnEverywhere(what: String, before: IntArray, after: IntArray, maps: List<FloatArray>, probes: List<Vec2>) {
        val seen = IntArray(1)
        val miss = missing(before, after, maps, probes, seen)
        println("Qa18MirrorsRulers $what: ${seen[0]} copy spots looked at, ${miss.size} empty")
        assertTrue("$what: copies looked at (${seen[0]})", seen[0] >= minOf(2, maps.size))
        assertEquals("$what: every copy drawn", emptyList<String>(), miss)
    }

    /** [taps] by a finger in the tool [label]; the ruler's maps at the path's first point as placed (they are taken there). */
    private fun curveTaps(label: String, taps: List<Vec2>): List<FloatArray> {
        ui.tool(label)
        val tool = c.currentTool as CurveTool
        for (p in taps) ui.tap(p.x, p.y)
        assertEquals("$label: a new path of ${taps.size} points", taps.size, tool.pointCount)
        assertFalse("$label: not a reopened object", tool.isReopened)
        assertTrue("$label: the copies follow while it is drawn", tool.hasCopies)
        val first = tool.pointAt(0)
        assertTrue("$label: the first point under the finger ($first)", abs(first.x - taps[0].x) < 2f && abs(first.y - taps[0].y) < 2f)
        return ObjectCopies.mapsAt(c, first.x, first.y)
    }

    private fun shapeDrag(from: Vec2, to: Vec2) {
        ui.tool("Shape")
        val tool = c.currentTool as ShapeTool
        ui.stroke(from.x to from.y, (from.x + to.x) / 2f to (from.y + to.y) / 2f, to.x to to.y)
        assertNotNull("a pending shape", tool.box)
        assertTrue("the copies show while it is drawn", tool.hasCopies)
    }

    private fun select(layer: Layer) {
        c.selectLayer(layer)
        settle()
    }

    /** The four tools on the raster layer: pixels of every copy, one step each. */
    private fun onRaster(name: String, perspective: Boolean) {
        val layer = c.doc.layers[1]
        select(layer)
        for ((label, taps) in CURVES) {
            c.color = COLORS.getValue(label)
            // The copies are taken at the path's first point (where the finger put it).
            val maps = curveTaps(label, taps)
            val before = pixels(layer.bitmap)
            val n0 = steps()
            apply("Apply ${label.lowercase()} edit")
            assertEquals("$name raster $label: one step", n0 + 1, steps())
            assertFalse("$name raster $label: still a raster layer", layer.isVectorLayer)
            assertDrawnEverywhere("$name raster $label", before, pixels(layer.bitmap), maps, listOf(taps.first(), taps.last()))
        }
        c.color = COLORS.getValue("Shape")
        val maps = ObjectCopies.mapsAt(c, SHAPE.first.x, SHAPE.first.y)
        val layersBefore = c.doc.layers.map { it.id }.toSet()
        shapeDrag(SHAPE.first, SHAPE.second)
        val before = pixels(layer.bitmap)
        val n0 = steps()
        apply("Apply shape edit")
        assertEquals("$name raster Shape: one step", n0 + 1, steps())
        val corners = listOf(SHAPE.first, SHAPE.second)
        val added = c.doc.layers.filter { it.id !in layersBefore }
        if (perspective) {
            assertEquals("$name raster Shape: painted into the layer", emptyList<Layer>(), added)
            assertTrue("$name raster Shape: the message says so", said(MirrorLabels18.PERSPECTIVE_PIXELS))
            assertDrawnEverywhere("$name raster Shape", before, pixels(layer.bitmap), maps, corners)
        } else {
            val shapeLayer = added.single()
            assertTrue("$name raster Shape: a shape layer", shapeLayer.isShapeLayer)
            val o = ShapeCodec.decode(shapeLayer.shapeData!!) ?: throw AssertionError("$name: no shape stored")
            assertEquals("$name raster Shape: the layer keeps the ruler's copies", CopyMaps.of(maps), o.copies)
            assertDrawnEverywhere("$name raster Shape", IntArray(W * H), pixels(shapeLayer.bitmap), maps, corners)
        }
    }

    /** What a vector commit added: one object with the ruler's copies, or (perspective) the bake. */
    private fun checkStored(what: String, added: List<com.brushwork.paint.vector.VObject>, maps: List<FloatArray>, perspective: Boolean, shape: Boolean) {
        if (perspective) {
            // The source stays what it is (a shape stays a shape: ARCHITECTURE "one extra plain path
            // per copy on the canvas beside the shape"), without copies; every copy is a plain path.
            val source = added.first()
            assertTrue("$what: the source first, without copies ($source)", (if (shape) source is VShape else source is VPath) && !ObjectCopies.hasCopies(source))
            assertTrue("$what: the copies are plain paths", added.drop(1).all { it is VPath && !ObjectCopies.hasCopies(it) })
            val off = added.count { o -> VectorOps.bounds(o).let { b -> b.right < 0f || b.bottom < 0f || b.left > W || b.top > H } }
            println("Qa18MirrorsRulers $what: ${maps.size} maps, ${added.size} paths baked, $off of them wholly off the canvas")
            // v1.8 polish (a requested change, not a weaker check): the copies wholly off the canvas
            // are no longer baked (they painted nothing): the path and one path per copy ON the canvas.
            val kept = PerspectiveBakeKit.onCanvas(PerspectiveBakeKit.uncut(source, maps), W, H)
            assertEquals("$what: none baked wholly off the canvas", 0, off)
            assertTrue("$what: some copies lie off the canvas (${kept.size} of ${maps.size} kept)", kept.size < maps.size)
            assertEquals("$what: the path and one path per copy on the canvas", kept.size, added.size)
        } else {
            assertEquals("$what: one object", 1, added.size)
            val copies = if (shape) (added.single() as VShape).shape.copies else (added.single() as VPath).copies
            assertEquals("$what: it keeps the ruler's maps", CopyMaps.of(maps), copies)
        }
    }

    /** The four tools on their vector layers: one object with the ruler's copies (or the perspective bake), one step each. */
    private fun onVector(name: String, perspective: Boolean) {
        var bakedSaid = false
        for ((label, taps) in CURVES) {
            val layer = c.doc.layers.single { it.name == "Vector $label" }
            select(layer)
            c.color = COLORS.getValue(label)
            val maps = curveTaps(label, taps)
            val before = pixels(layer.bitmap)
            val n0 = steps()
            apply("Apply ${label.lowercase()} edit")
            assertEquals("$name vector $label: one step", n0 + 1, steps())
            val added = layer.vector!!.objects
            checkStored("$name vector $label", added, maps, perspective, shape = false)
            if (perspective && !bakedSaid) assertTrue("$name vector $label: the message says so", said(MirrorLabels18.PERSPECTIVE_BAKED))
            bakedSaid = true
            val drawn = pixels(layer.bitmap)
            assertDrawnEverywhere("$name vector $label", before, drawn, maps, listOf(taps.first(), taps.last()))
            if (label == "Curve") {
                // The top row's Undo takes the whole commit (every copy, every baked path) back; Redo brings it back.
                Finger.tap(s, "Undo")
                settleRenders("$name: Undo")
                assertEquals("$name vector $label: Undo takes one step", n0, steps())
                assertTrue("$name vector $label: one Undo takes it all back", layer.vector!!.objects.isEmpty())
                assertTrue("$name vector $label: no pixel left", pixels(layer.bitmap).all { it == 0 })
                Finger.tap(s, "Redo")
                settleRenders("$name: Redo")
                assertEquals("$name vector $label: Redo brings it back", added.size, layer.vector!!.objects.size)
                assertTrue("$name vector $label: drawn again", drawn.contentEquals(pixels(layer.bitmap)))
            }
        }
        val layer = c.doc.layers.single { it.name == "Vector Shape" }
        select(layer)
        c.color = COLORS.getValue("Shape")
        val maps = ObjectCopies.mapsAt(c, SHAPE.first.x, SHAPE.first.y)
        shapeDrag(SHAPE.first, SHAPE.second)
        val before = pixels(layer.bitmap)
        val n0 = steps()
        apply("Apply shape edit")
        assertEquals("$name vector Shape: one step", n0 + 1, steps())
        checkStored("$name vector Shape", layer.vector!!.objects, maps, perspective, shape = true)
        assertDrawnEverywhere("$name vector Shape", before, pixels(layer.bitmap), maps, listOf(SHAPE.first, SHAPE.second))
    }

    private fun ruler(h: ChromeHarness, name: String, type: SymmetryType, values: List<Qa18MirrorsKit.Value> = emptyList()) {
        s = h.editor(document()) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        Qa18MirrorsKit.rulerOn(ui, type, values)
        val perspective = type == SymmetryType.PERSPECTIVE_ARRAY
        onRaster(name, perspective)
        onVector(name, perspective)
        // The editor with the Curve tool open again: its faint guides over the result.
        ui.tool("Curve")
        Qa18MirrorsKit.shoot(s, "$name-curves")
        Smoke.assertQuiet(c, name)
    }

    @Test
    fun curvesAndShapesUnderEachRulerOnRasterAndVectorLayers() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        h.section("Mirror") { ruler(h, "mirror", SymmetryType.MIRROR) }
        h.section("Kaleidoscope 3") { ruler(h, "kaleidoscope", SymmetryType.KALEIDOSCOPE, listOf(divisions("3"))) }
        h.section("Rotation 4") { ruler(h, "rotation", SymmetryType.ROTATION, listOf(divisions("4"))) }
        h.section("Array 150") { ruler(h, "array", SymmetryType.ARRAY, listOf(spacingX("150"), spacingY("150"))) }
        h.section("Perspective array") { ruler(h, "perspective", SymmetryType.PERSPECTIVE_ARRAY) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300

        val TOOLS = listOf("Curve", "Polyline", "Path", "Shape")

        /** Each tool's color on the raster layer (a copy drawn over another tool's ink still changes it). */
        val COLORS = mapOf(
            "Curve" to 0xFF000000.toInt(), "Polyline" to 0xFFD02020.toInt(), "Path" to 0xFF1E8A3C.toInt(), "Shape" to 0xFF1E5AD2.toInt(),
        )

        /**
         * Three taps per curve tool, 50 px apart (a tap closer to a point picks that point), first
         * points off the Array 150 grid's cell edges (x = 50 is one: the start cell, and so the
         * copies, would hang on a fraction of a pixel).
         */
        val CURVES = listOf(
            "Curve" to listOf(Vec2(55f, 215f), Vec2(100f, 175f), Vec2(145f, 215f)),
            "Polyline" to listOf(Vec2(55f, 85f), Vec2(100f, 45f), Vec2(145f, 85f)),
            "Path" to listOf(Vec2(30f, 140f), Vec2(80f, 125f), Vec2(130f, 140f)),
        )

        /** The shape's drag (its two corners). */
        val SHAPE = Vec2(160f, 60f) to Vec2(185f, 90f)
    }
}
