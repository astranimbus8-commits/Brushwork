package com.brushwork.paint.qa18

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.gen.CurveGenerators
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.VPath
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.8 final QA (item 6, design §3.7): generated curves outlive the editor, on the phone (392 dp):
 * - a Curve spiral and a Path rose (moved by its centre handle), saved and opened again in a new
 *   editor, reopen by a tap on their lines with their settings, are changed in the sheet by finger
 *   and stored again generated; saved again they come back so;
 * - the SVG export draws them as paths and its payload brings them back generated (a tap on the
 *   imported spiral reopens it with its settings);
 * - on a raster layer the canvas follows the sheet live, ✓ paints the curve as ONE step that the
 *   top row's Undo and Redo take back and bring back.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvessavesandbox"])
class Qa18CurvesSaveUiTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun saveAndLoad(doc: Document): Document = runBlocking {
        val repo = ProjectRepository(app)
        repo.save(doc, null)
        repo.load(doc.id).also { assertEquals("no load warnings", emptyList<String>(), it.loadWarnings) }
    }

    private fun generated(layer: Layer): List<VPath> = layer.vector!!.objects.filterIsInstance<VPath>().filter { it.generator != null }

    private fun vectorLayer(d: Document): Layer = d.layers.single { it.isVectorLayer }

    private fun ofKind(layer: Layer, kind: CurveGenKind): VPath = generated(layer).single { it.generator!!.kind == kind }

    /** Of [p]'s line, the point farthest right (the rose moved right: clear of the spiral), or left (the spiral: clear of the rose). */
    private fun rightmost(q: Qa18CurvesUi, p: VPath): Vec2 = (0..40).map { q.onLine(p, it / 40f) }.maxBy { it.x }

    private fun leftmost(q: Qa18CurvesUi, p: VPath): Vec2 = (0..40).map { q.onLine(p, it / 40f) }.minBy { it.x }

    private fun chipShown(q: Qa18CurvesUi): Boolean =
        Finger.control(q.s, CurveGenLabels18.CHANGE) != null || StripTestKit.reach { SmokeUi.find(CurveGenLabels18.CHANGE, exact = true) } != null

    /** A Curve spiral at the centre, a Path rose moved right by its centre handle; both ✓. */
    private fun draw(q: Qa18CurvesUi) {
        q.tool("Curve")
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.LOGARITHMIC)
        q.applyEdit("Apply curve edit")
        q.tool("Path")
        val path = q.c.currentTool as CurveTool
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.ROSE)
        StripTestKit.closeMoreRow()
        val h = path.generatorHandles!!
        q.strokeDoc(h.centre, h.centre + Vec2(25f, 0f), h.centre + Vec2(50f, 0f), h.centre + Vec2(75f, 0f), h.centre + Vec2(100f, 0f))
        assertEquals("the centre handle moved the rose", h.centre.x + 100f, path.generator!!.cx, 1.5f)
        q.applyEdit("Apply path edit")
        assertEquals("two generated curves", 2, generated(q.c.activeLayer).size)
    }

    private fun saveReopenEdit(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        draw(q)
        val drawn = generated(q.c.activeLayer)
        File(app.filesDir, "projects").deleteRecursively()
        val loaded = saveAndLoad(q.c.doc)
        assertEquals("saved and loaded: the same curves, with their settings", drawn, generated(vectorLayer(loaded)))
        generated(vectorLayer(loaded)).forEach { assertTrue("I16 after loading", CurveGenerators.matches(it)) }

        // A new editor on the loaded artwork: each reopens by a tap and is changed in the sheet.
        q.editor(loaded)
        val layer = q.c.activeLayer
        val spiral = ofKind(layer, CurveGenKind.LOGARITHMIC)
        q.tool("Curve")
        val curve = q.c.currentTool as CurveTool
        q.tapDoc(leftmost(q, spiral))
        assertEquals("the spiral reopens with its settings", spiral.generator, curve.generator)
        assertTrue("its chip", chipShown(q))
        StripTestKit.closeMoreRow()
        q.tap(CurveGenLabels18.CHANGE)
        q.tap(CurveGenLabels18.CLOCKWISE)
        Finger.back()
        q.applyEdit("Apply curve edit")
        val spiral2 = ofKind(layer, CurveGenKind.LOGARITHMIC)
        assertEquals("the same object", spiral.id, spiral2.id)
        assertEquals("stored with the change", spiral.generator!!.copy(clockwise = !spiral.generator!!.clockwise), spiral2.generator)
        assertTrue(CurveGenerators.matches(spiral2))

        val rose = ofKind(layer, CurveGenKind.ROSE)
        q.tool("Path")
        val path = q.c.currentTool as CurveTool
        q.tapDoc(rightmost(q, rose))
        assertEquals("the rose reopens with its settings", rose.generator, path.generator)
        assertTrue("its chip", chipShown(q))
        q.tap(CurveGenLabels18.CHANGE)
        q.dragSlider(CurveGenLabels18.PETALS, 0.2f, downPast = 0.4f)
        val petals = path.generator!!.petalsN
        assertNotEquals("Petals changed", rose.generator!!.petalsN, petals)
        Finger.back()
        StripTestKit.closeMoreRow()
        q.applyEdit("Apply path edit")
        val rose2 = ofKind(layer, CurveGenKind.ROSE)
        assertEquals(rose.id, rose2.id)
        assertEquals("stored with the change", rose.generator!!.copy(petalsN = petals), rose2.generator)
        assertTrue("still a spline path", rose2.spline?.cyclic == true)
        assertTrue(CurveGenerators.matches(rose2))
        val again = saveAndLoad(q.c.doc)
        assertEquals("saved again: as edited", generated(layer), generated(vectorLayer(again)))
        Smoke.assertQuiet(q.c, "save, reopen, edit")

        // The SVG: paths any viewer draws, and a payload that brings them back generated.
        val scene = runBlocking { ExportSceneBuilder(q.c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val bytes = out.toByteArray()
        val text = bytes.toString(Charsets.UTF_8)
        assertTrue("the SVG draws paths", Regex("<path\\b").findAll(text).count() >= 2)
        val svg = SvgParser.parse(bytes)
        val payload = requireNotNull(svg.payload()) { "the SVG carries the artwork" }
        val edited = generated(layer)

        q.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = q.c
        val target = ImportTarget(400, 300, c.doc.dpi, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)
        PayloadImport.apply(c, PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target))
        q.settleRenders("the import")
        val imported = c.doc.layers.single { it.isVectorLayer && generated(it).isNotEmpty() }
        assertEquals("the SVG brings them back generated", edited.map { it.generator }, generated(imported).map { it.generator })
        generated(imported).forEach { assertTrue("I16 after the SVG", CurveGenerators.matches(it)) }
        c.doc.activeLayerIndex = c.doc.layers.indexOf(imported)
        q.settleRenders("the active layer")
        q.tool("Curve")
        val again2 = ofKind(imported, CurveGenKind.LOGARITHMIC)
        q.tapDoc(leftmost(q, again2))
        assertEquals("the imported spiral reopens with its settings", again2.generator, (c.currentTool as CurveTool).generator)
        assertTrue(chipShown(q))
        StripTestKit.closeMoreRow()
        Smoke.assertQuiet(c, "SVG")
    }

    private fun raster(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.rasterDoc())
        val c = q.c
        q.tool("Curve")
        val tool = c.currentTool as CurveTool
        val layer = c.activeLayer
        assertTrue("a raster layer", !layer.isVectorLayer)
        val steps = q.steps()
        q.tap(CurveGenLabels18.CURVES_DESC)
        q.tap(CurveGenLabels18.SINE)
        StripTestKit.closeMoreRow()
        val g = tool.generator!!
        q.tap(CurveGenLabels18.CHANGE)
        val screen = q.capture()
        q.dragSlider(CurveGenLabels18.WAVE_HEIGHT, 0.3f, downPast = 0.5f)
        assertNotEquals("Wave height changed", g.amplitude, tool.generator!!.amplitude)
        val r = g.size / 2f + 40f
        val shown = q.changedAboveSheet(screen, q.capture(), g.cx - r, g.cy - r, g.cx + r, g.cy + r)
        assertTrue("the canvas follows the sheet on a raster layer ($shown px)", shown > 50)
        Finger.back()
        assertTrue(!has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
        val line = tool.buildVPath()!!
        val at = listOf(0.2f, 0.5f, 0.8f).map { q.onLine(line, it) }
        assertTrue("not painted yet", at.none { q.inkAt(layer, it) })
        q.applyEdit("Apply curve edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        assertTrue("painted on the layer", at.all { q.inkAt(layer, it) })
        q.shot(layer, "curves-ui-raster-sine.png")
        q.undo()
        q.settleRenders("undo")
        assertTrue("Undo takes it off", at.none { q.inkAt(layer, it) })
        q.redo()
        q.settleRenders("redo")
        assertTrue("Redo paints it again", at.all { q.inkAt(layer, it) })
        assertNotNull(layer.bitmap)
        Smoke.assertQuiet(c, "raster")
    }

    @Test
    fun generatedCurvesAreSavedReopenedEditedExportedAndPainted() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("saved, reopened in a new editor, edited by finger, saved again, SVG") { saveReopenEdit(q) }
        h.section("on a raster layer") { raster(q) }
        dog.interrupt()
        h.finish()
    }
}
