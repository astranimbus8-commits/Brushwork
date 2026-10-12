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
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.PerspectiveBakeKit
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 polish, the verifier's flow around item 3 (mirrors follow-ups) on the user's phone
 * (392 dp), every step by a finger: the Perspective array's bake of a BRUSH line (the Path tool,
 * "Current brush", a 40 px brush: the widest reach, where a copy just off the canvas still paints
 * inside it) on a vector layer over a dark page.
 * - ONE step; the source first; no baked copy wholly off the canvas; fewer objects than maps.
 * - Every visible copy kept: the layer's pixels equal the uncut bake rendered on the canvas, also
 *   along the canvas's edges, where the brush of a copy centred outside reaches in.
 * - The top row's Undo takes the whole bake back; Redo brings back the same objects (bounds, in
 *   order) and the same pixels.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.polishmirrorsverifybakesandbox"])
class Qa18PolishMirrorsVerifyBakeUiTest {

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c get() = s.c

    /** A dark page, a clear raster layer and an empty vector layer (active). */
    private fun document(): Document = Smoke.document(W, H, layers = 2).also { d ->
        d.layers[0].bitmap.eraseColor(DARK)
        d.layers += Layer(d.newLayerId(), "Vector", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 2
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

    /** Pixels of [p] within 3 px of the canvas's edges that are not clear. */
    private fun edgeInk(p: IntArray): Int {
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            if ((x < 3 || y < 3 || x >= W - 3 || y >= H - 3) && (p[y * W + x] ushr 24) != 0) n++
        }
        return n
    }

    private fun flow(h: ChromeHarness) {
        s = h.editor(document()) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the user's phone is 392 dp wide", 392f, s.widthDp, 1f)
        val layer = c.doc.layers.single { it.name == "Vector" }
        assertTrue(layer === c.doc.activeLayer)
        c.updatePreset(ToolId.BRUSH, c.presetFor(ToolId.BRUSH)!!.copy(size = 40f))
        Qa18MirrorsKit.rulerOn(ui, SymmetryType.PERSPECTIVE_ARRAY)

        // The Path, "Current brush", three taps, ✓.
        c.color = LIGHT
        ui.tool("Path")
        val tool = c.currentTool as CurveTool
        click(CurveLabels17.STROKE_KIND, exact = true)
        click(CurveStroke.BRUSH.label, exact = true)
        settle()
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        for (p in TAPS) ui.tap(p.x, p.y)
        assertEquals("three points", 3, tool.pointCount)
        val first = tool.pointAt(0)
        val maps = ObjectCopies.mapsAt(c, first.x, first.y)
        assertTrue("perspective maps", ObjectCopies.isPerspective(maps))
        val n0 = steps()
        click("Apply path edit", exact = true)
        settleRenders("the path")
        assertEquals("the path: ONE step", n0 + 1, steps())
        val stored = layer.vector!!.objects
        val source = stored.first() as VPath
        assertEquals("a brush line", VStrokeKind.BRUSH, source.stroke?.kind)

        // The cut: none wholly off, every visible copy kept (the pixels of the uncut bake).
        val uncut = PerspectiveBakeKit.uncut(source, maps)
        val off = uncut.count { PerspectiveBakeKit.whollyOff(it, W, H) }
        println("Qa18PolishMirrorsVerifyBake: ${maps.size} maps, $off wholly off the canvas, ${stored.size} baked")
        assertEquals("before the cut, the source and one path per copy", maps.size, uncut.size)
        assertTrue("copies lie off the canvas ($off)", off > 0)
        assertEquals("the source and one path per copy on the canvas", maps.size - off, stored.size)
        assertEquals("no baked copy wholly off", emptyList<String>(), stored.filter { PerspectiveBakeKit.whollyOff(it, W, H) }.map { "${VectorOps.bounds(it)}" })
        val all = render(uncut)
        assertTrue("the brush reaches the canvas's edges (${edgeInk(all)} px)", edgeInk(all) > 0)
        assertArrayEquals("every visible copy kept (the canvas as with every copy baked)", all, render(stored))
        val baked = pixels(layer.bitmap)
        assertArrayEquals("the layer shows it", all, baked)
        val boundsAfter = stored.map { VectorOps.bounds(it) }
        Qa18PolishMirrorsKit.shoot(s, "verify-bake-dark-brush")

        // Undo takes all of it; Redo brings back the same objects and pixels.
        Finger.tap(s, "Undo")
        settleRenders("Undo")
        assertEquals("Undo takes one step", n0, steps())
        assertTrue("one Undo takes every baked path", layer.vector!!.objects.isEmpty())
        assertTrue("no pixel left", pixels(layer.bitmap).all { it == 0 })
        Finger.tap(s, "Redo")
        settleRenders("Redo")
        assertEquals("Redo: the one step again", n0 + 1, steps())
        assertEquals("Redo: the same objects, in order", stored, layer.vector!!.objects)
        assertEquals("Redo: the same bounds", boundsAfter, layer.vector!!.objects.map { VectorOps.bounds(it) })
        assertArrayEquals("Redo: the same pixels", baked, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "verify bake")
    }

    @Test
    fun aBrushLineBakeOnADarkPageKeepsEveryVisibleCopyThroughUndoAndRedo() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("Perspective bake of a brush Path on a dark page") { flow(h) }
        dog.interrupt()
        h.finish()
    }

    private companion object {
        const val W = 400
        const val H = 300
        val DARK = 0xFF15171C.toInt()
        val LIGHT = 0xFFF2E6C8.toInt()

        /** The Path's taps, 50 px apart (a tap closer to a point picks that point). */
        val TAPS = listOf(Vec2(60f, 210f), Vec2(110f, 170f), Vec2(160f, 210f))
    }
}
