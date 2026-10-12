package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa18.Qa18MirrorsKit.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.PerspectiveBakeKit
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish (mirrors follow-ups, item 3) on the user's phone (392 dp), every step by a finger:
 * the Perspective array (its default cell) bakes a vector path or shape into plain paths, and the
 * copies wholly off the canvas (most of up to 256 maps) are no longer baked.
 * - The Curve (three taps, ✓) and the Shape (a drag, ✓) each on their own vector layer: ONE step;
 *   the source first; fewer objects than maps; no baked copy wholly off the canvas.
 * - Every visible copy is kept: the layer's pixels equal the uncut bake (the source plus one path
 *   per map, `ObjectCopies.baked` without the canvas) rendered on the canvas, and the data and the
 *   pixels agree.
 * - The top row's Undo takes the whole bake back.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishmirrorsbakesandbox"])
class Qa18PolishMirrorsBakeUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    /** A white page, a clear raster layer and an empty vector layer for each tool. */
    private fun document(): Document = Smoke.document(W, H, layers = 2, whiteBottom = true).also { d ->
        for (t in listOf("Curve", "Shape")) d.layers += Layer(d.newLayerId(), "Vector $t", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
    }

    private fun steps() = c.undoManager.undoCount

    private fun settleRenders(what: String) {
        assertTrue(
            "$what: done",
            Smoke.pumpUntil(10_000) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending },
        )
        settle()
    }

    /** What the vector renderer draws for [objects] (renumbered as a commit does) on the canvas. */
    private fun render(objects: List<VObject>): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        VectorLayerRenderer.render(Canvas(b), VectorContent.EMPTY.plus(objects).first, Rect(0, 0, W, H), tips = TipCache(), document = Rect(0, 0, W, H))
        return pixels(b)
    }

    /** The bake [stored] on [layer] against [maps]: the cut and what it keeps. */
    private fun checkBake(what: String, layer: Layer, stored: List<VObject>, maps: List<FloatArray>) {
        assertTrue("$what: perspective maps", ObjectCopies.isPerspective(maps))
        val uncut = PerspectiveBakeKit.uncut(stored.first(), maps)
        val off = uncut.count { PerspectiveBakeKit.whollyOff(it, W, H) }
        println("Qa18PolishMirrorsBake $what: ${maps.size} maps, ${uncut.size} paths before the cut, $off wholly off the canvas, ${stored.size} baked")
        assertEquals("$what: before the cut, the source and one path per copy", maps.size, uncut.size)
        assertTrue("$what: copies lie off the canvas ($off)", off > 0)
        assertEquals("$what: the source and one path per copy on the canvas", maps.size - off, stored.size)
        assertEquals("$what: no baked copy wholly off the canvas", emptyList<String>(), stored.filter { PerspectiveBakeKit.whollyOff(it, W, H) }.map { "${VectorOps.bounds(it)}" })
        assertTrue("$what: the copies are plain paths", stored.drop(1).all { it is VPath && !ObjectCopies.hasCopies(it) })
        val all = render(uncut)
        assertTrue("$what: the bake paints", all.any { it != 0 })
        assertArrayEquals("$what: every visible copy kept (the canvas as with every copy baked)", all, render(stored))
        assertArrayEquals("$what: the layer shows it", all, pixels(layer.bitmap))
    }

    private fun flow(h: ChromeHarness) {
        s = h.editor(document()) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.PERSPECTIVE_ARRAY)

        // The Curve, a plain line of three taps, ✓.
        val curves = c.doc.layers.single { it.name == "Vector Curve" }
        c.selectLayer(curves)
        c.color = 0xFF101010.toInt()
        ui.tool("Curve")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.PLAIN.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals("three points", 3, tool.pointCount)
        val first = tool.pointAt(0)
        val curveMaps = ObjectCopies.mapsAt(c, first.x, first.y)
        val n0 = steps()
        click("Apply curve edit", exact = true)
        settleRenders("the curve")
        assertEquals("the curve: ONE step", n0 + 1, steps())
        val curveObjects = curves.vector!!.objects
        assertTrue("the source path first", curveObjects.first() is VPath)
        checkBake("Curve", curves, curveObjects, curveMaps)

        // The Shape, a drag, ✓.
        val shapes = c.doc.layers.single { it.name == "Vector Shape" }
        c.selectLayer(shapes)
        c.color = 0xFF1E5AD2.toInt()
        val shapeMaps = ObjectCopies.mapsAt(c, BOX.first.x, BOX.first.y)
        ui.tool("Shape")
        val shapeTool = c.currentTool as ShapeTool
        ui.stroke(BOX.first.x to BOX.first.y, (BOX.first.x + BOX.second.x) / 2f to (BOX.first.y + BOX.second.y) / 2f, BOX.second.x to BOX.second.y)
        assertNotNull("a pending shape", shapeTool.box)
        val n1 = steps()
        click("Apply shape edit", exact = true)
        settleRenders("the shape")
        assertEquals("the shape: ONE step", n1 + 1, steps())
        val shapeObjects = shapes.vector!!.objects
        assertTrue("the source shape first", shapeObjects.first() is VShape)
        checkBake("Shape", shapes, shapeObjects, shapeMaps)
        val shots = Qa18PolishMirrorsKit.shoot(s, "bake")
        println("Qa18PolishMirrorsBake: ${shots.joinToString()}")

        // The top row's Undo: the whole bake goes, in one step.
        Finger.tap(s, "Undo")
        settleRenders("Undo")
        assertEquals("Undo takes one step", n1, steps())
        assertTrue("one Undo takes every baked path", shapes.vector!!.objects.isEmpty())
        assertTrue("no pixel left", pixels(shapes.bitmap).all { it == 0 })
        Smoke.assertQuiet(c, "bake")
    }

    @Test
    fun aPerspectiveBakeKeepsOnlyTheCopiesOnTheCanvas() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("Perspective bake on vector layers") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300

        /** The curve's taps, 50 px apart (a tap closer to a point picks that point). */
        val TAPS = listOf(Vec2(55f, 215f), Vec2(100f, 175f), Vec2(145f, 215f))

        /** The shape's drag (its two corners). */
        val BOX = Vec2(160f, 60f) to Vec2(185f, 90f)
    }
}
