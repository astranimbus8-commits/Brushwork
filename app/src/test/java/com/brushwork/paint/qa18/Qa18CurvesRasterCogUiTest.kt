package com.brushwork.paint.qa18

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.vector.ShapeStripItems
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 final QA (item 7, design §3.8) on a RASTER layer, where the user paints, on the phone
 * (392 dp), by finger:
 * - a cog with a "Hole size", stroked with "Current brush" ("Stroke with"), goes into a layer of
 *   its own ("Editable" on, the default) as ONE step; the brush draws BOTH outlines (the teeth and
 *   the hole) and the hole shows the page;
 * - a tap on it opens it again as a cog (its hole kept, no stray points from the v1.7 fallback
 *   outline the layer stores), its "Number of teeth" is changed in "Shape settings", ✓ is ONE
 *   step, Undo gives back the earlier cog and its pixels exactly, Redo the new one;
 * - saved and loaded, the layer is still that cog, and a tap in a new editor opens it with its
 *   teeth and hole ("Points" is refused for the hole);
 * - with "Editable" off the cog is painted into the layer, both outlines, as ONE step.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvesrastercogsandbox"])
class Qa18CurvesRasterCogUiTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pixels(l: Layer): IntArray {
        val b = l.bitmap
        return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
    }

    private fun shapeOf(l: Layer): ShapeObject = ShapeCodec.decode(l.shapeData) ?: throw AssertionError("\"${l.name}\" holds no shape")

    /** The hole's radius of [o] (document px; its box is square). */
    private fun holeRadius(o: ShapeObject): Float = o.w / 2f * (1f - o.cogDepth) * o.cogHole

    /** Points of the circle of radius [r] about [c] showing ink on [layer]. */
    private fun inkedRound(q: Qa18CurvesUi, layer: Layer, c: Vec2, r: Float, n: Int = 32): Int =
        (0 until n).count { i -> val a = 2.0 * Math.PI * i / n; q.inkAt(layer, Vec2(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 1) }

    /** Shape type Cog, 12 teeth, a hole (dragged), Stroke with the current brush. */
    private fun setUp(q: Qa18CurvesUi): ShapeTool {
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        q.tap("Shape type")
        q.tap(ShapeType.COG.label)
        assertEquals(ShapeType.COG, tool.settings.type)
        q.tap("Shape settings")
        SmokeUi.typeAndDone(CogLabels18.TEETH, "12")
        q.dragSlider(CogLabels18.HOLE_SIZE, 0.6f)
        assertTrue("a hole: ${tool.settings.cogHole}", tool.settings.cogHole > 0.3f)
        Finger.back()
        q.tap("Shape style")
        q.tap(ShapeStyle.STROKE.label)
        q.tap(ShapeStripItems.STROKE_WITH_LABEL)
        q.tap(ShapeStroke.BRUSH.label)
        StripTestKit.closeMoreRow()
        assertEquals(ShapeStroke.BRUSH, tool.settings.strokeWith)
        return tool
    }

    /** Both outlines of the holed cog [o] inked on [layer], the hole empty. */
    private fun bothOutlines(q: Qa18CurvesUi, layer: Layer, o: ShapeObject, what: String) {
        val c = Vec2(o.cx, o.cy)
        val hole = holeRadius(o)
        assertTrue("$what: the brush drew the hole's outline", inkedRound(q, layer, c, hole) >= 24)
        assertTrue("$what: and the teeth", inkedRound(q, layer, c, o.w / 2f * (1f - o.cogDepth / 2f)) >= 8)
        assertFalse("$what: the hole shows the page", q.inkAt(layer, c, 2))
    }

    private fun ownLayer(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.rasterDoc())
        val c = q.c
        val tool = setUp(q)
        assertTrue("Editable (own layer) is on", tool.settings.editable)
        val layersBefore = c.doc.layers.toList()
        val steps = q.steps()
        q.strokeDoc(Vec2(60f, 10f), Vec2(130f, 80f), Vec2(200f, 150f), Vec2(270f, 220f), Vec2(340f, 290f))
        assertNotNull("an open cog", tool.box)
        q.applyEdit("Apply shape edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        val layer = c.doc.layers.singleOrNull { it !in layersBefore } ?: throw AssertionError("no new layer: ${c.doc.layers.map { it.name }}")
        val first = shapeOf(layer)
        assertEquals(ShapeType.COG, first.type)
        assertEquals(12, first.cogTeeth)
        assertTrue("its hole kept", first.cogHole > 0.3f)
        assertNull("read back as a cog, not as its v1.7 fallback points", first.points)
        assertTrue("stroked with the brush", first.paintsWithBrush)
        assertTrue("the raster layer below untouched", layersBefore.drop(1).all { q.ink(it) == 0 })
        bothOutlines(q, layer, first, "placed")
        q.shot(layer, "curves-ui-cog-raster-brush-hole.png")
        val data1 = layer.shapeData
        val pixels1 = pixels(layer)

        // A tap on its hole's outline opens it again, as a cog.
        val hole = holeRadius(first)
        q.tapDoc(Vec2(first.cx + hole, first.cy))
        assertTrue("the tap opened the cog's layer", tool.editingLayer === layer)
        assertEquals(ShapeType.COG, tool.settings.type)
        assertEquals(12, tool.settings.cogTeeth)
        assertEquals("the hole as it was", first.cogHole, tool.settings.cogHole)
        assertNull("no stray points", tool.points)
        q.tap("Shape settings")
        SmokeUi.typeAndDone(CogLabels18.TEETH, "20")
        Finger.back()
        assertEquals(20, tool.settings.cogTeeth)
        val edits = q.steps()
        q.applyEdit("Apply shape edit")
        assertEquals("✓ of the opened cog is ONE step", edits + 1, q.steps())
        assertEquals("no new layer", layersBefore.size + 1, c.doc.layers.size)
        val second = shapeOf(layer)
        assertEquals(20, second.cogTeeth)
        assertEquals(first.cogHole, second.cogHole)
        assertEquals("the same place", first.cx to first.cy, second.cx to second.cy)
        bothOutlines(q, layer, second, "20 teeth")
        val pixels2 = pixels(layer)
        assertFalse("the pixels follow the teeth", pixels2.contentEquals(pixels1))

        q.undo()
        q.settleRenders("undo")
        assertEquals("Undo: the 12-tooth cog's data", data1, layer.shapeData)
        assertTrue("Undo: its pixels exactly", pixels(layer).contentEquals(pixels1))
        q.redo()
        q.settleRenders("redo")
        assertEquals(20, shapeOf(layer).cogTeeth)
        assertTrue("Redo: the 20-tooth pixels exactly", pixels(layer).contentEquals(pixels2))

        // Saved and loaded; a new editor opens it by a tap, as a cog with its hole.
        File(app.filesDir, "projects").deleteRecursively()
        val loaded = runBlocking {
            val repo = ProjectRepository(app)
            repo.save(c.doc, null)
            repo.load(c.doc.id)
        }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val back = loaded.layers.single { it.id == layer.id }
        assertEquals("saved and loaded: the same cog", second, shapeOf(back))
        q.editor(loaded)
        q.tool("Shape")
        val again = q.c.currentTool as ShapeTool
        q.tapDoc(Vec2(second.cx + hole, second.cy))
        assertEquals("a tap opens the loaded cog", back.id, again.editingLayer?.id)
        assertEquals(ShapeType.COG, again.settings.type)
        assertEquals(20, again.settings.cogTeeth)
        assertEquals(second.cogHole, again.settings.cogHole)
        assertNull(again.points)
        q.tap(ShapeStripItems.POINTS_LABEL)
        assertTrue("Points refused for the hole", has(CogLabels18.HOLE_REFUSAL, exact = true))
        assertFalse(again.pointsMode)
        StripTestKit.closeMoreRow()
        Smoke.assertQuiet(q.c, "a cog in its own layer")
    }

    private fun painted(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.rasterDoc())
        val c = q.c
        val tool = setUp(q)
        q.tap(ShapeStripItems.EDITABLE_LABEL)
        StripTestKit.closeMoreRow()
        assertFalse("Editable off", tool.settings.editable)
        val layer = c.activeLayer
        val layers = c.doc.layers.size
        val steps = q.steps()
        q.strokeDoc(Vec2(60f, 10f), Vec2(130f, 80f), Vec2(200f, 150f), Vec2(270f, 220f), Vec2(340f, 290f))
        val o = tool.box?.let { b -> ShapeObject(type = ShapeType.COG, cx = b.cx, cy = b.cy, w = b.w, h = b.h, cogDepth = tool.settings.cogDepth, cogHole = tool.settings.cogHole) }
            ?: throw AssertionError("no open cog")
        q.applyEdit("Apply shape edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        assertEquals("no layer of its own", layers, c.doc.layers.size)
        bothOutlines(q, layer, o, "painted")
        q.shot(layer, "curves-ui-cog-raster-painted.png")
        q.undo()
        q.settleRenders("undo")
        assertEquals("Undo takes it off", 0, q.ink(layer))
        Smoke.assertQuiet(c, "a cog painted into the layer")
    }

    @Test
    fun aHoledCogOnARasterLayerStrokedWithTheBrush() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("its own layer, opened again, 20 teeth, Undo, saved and opened again") { ownLayer(q) }
        h.section("Editable off: painted into the layer") { painted(q) }
        dog.interrupt()
        h.finish()
    }
}
