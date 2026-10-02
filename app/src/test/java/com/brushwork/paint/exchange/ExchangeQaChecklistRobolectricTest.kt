package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Rect
import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.QaExchange.drag
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs

/**
 * Final QA (v1.5 §7, integration checklist item 1) with the real tools on a project made by the
 * gallery: New canvas → Vector → brush → partial erase → Lasso → Transform scale (a pinch with one
 * finger on the objects) → undo ×5 / redo ×5 → save, reopen → Export SVG → Import SVG. Object count
 * and bounds must be equal: exactly (the file's Brushwork data, "Editable layers") and as a foreign
 * SVG (no Brushwork data: one outline per stroke, a stroke cut by the eraser as two).
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeQaChecklistRobolectricTest {

    private val controllers = ArrayList<EditorController>()

    @After
    fun tearDown() = controllers.forEach { it.dispose() }

    private fun track(c: EditorController) = c.also {
        controllers += it
        it.snapping.enabled = false
    }

    private val w = 600
    private val h = 400

    private fun idle(c: EditorController) = assertTrue(Smoke.pumpUntil { c.busyMessage == null && !c.vectors.isRendering })

    /** The pixel bounds of what [b] shows (alpha > 0), or null. */
    private fun ink(b: Bitmap): Rect? {
        val px = pixels(b)
        var l = Int.MAX_VALUE
        var t = Int.MAX_VALUE
        var r = -1
        var btm = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            if (px[y * b.width + x] ushr 24 == 0) continue
            if (x < l) l = x
            if (x > r) r = x
            if (y < t) t = y
            if (y > btm) btm = y
        }
        return if (r < 0) null else Rect(l, t, r + 1, btm + 1)
    }

    /** [o] rendered alone, as a vector layer renders it. */
    private fun alone(o: VObject): Bitmap = ExchangeFixtures.render(VectorContent(objects = listOf(o), nextId = o.id + 1), w, h)

    private fun export(c: EditorController, payload: Boolean): File {
        val options = ExportOptions(VectorFormat.SVG, includePayload = payload)
        val scene = runBlocking { ExportSceneBuilder(c, options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        QaExchange.parseXml(out.toByteArray())
        return File(app.cacheDir, "checklist-${if (payload) "bw" else "plain"}.svg").apply { writeBytes(out.toByteArray()) }
    }

    /** A new canvas like the gallery makes, opened in an editor. */
    private fun newCanvas(repo: ProjectRepository, name: String): EditorController {
        val id = runBlocking { repo.create(NewCanvasSpec(name, w, h, 300f)) }
        return track(Smoke.controller(app, runBlocking { repo.load(id) }))
    }

    private fun importFile(c: EditorController, file: File, editable: Boolean? = null) {
        val state = ExchangeUiState(c)
        state.context = app
        val before = c.undoManager.undoCount
        state.importUri(Uri.fromFile(file))
        if (editable != null) {
            assertTrue("asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
            if (editable) state.answerEditable() else state.answerPicture()
        }
        assertTrue("imported", Smoke.pumpUntil { c.busyMessage == null && c.undoManager.undoCount == before + 1 })
        idle(c)
    }

    /**
     * An SVG of the canvas' own size (a Brushwork export without its data, or a file made to
     * measure in another app) comes in 1:1: its size in mm, read back at the document's DPI, is
     * the canvas size give or take rounding, not "too large, scaled to 90 %".
     */
    @Test
    fun aFileOfTheCanvasSizeIsPlacedOneToOne() {
        val sizes = listOf(
            Triple(600, 400, 300f), Triple(480, 360, 300f), Triple(1080, 2408, 350f), Triple(2480, 3508, 300f),
            Triple(1000, 1000, 72f), Triple(1920, 1080, 96f), Triple(3000, 2000, 350f), Triple(1240, 1754, 150f),
        )
        val scaled = ArrayList<String>()
        for ((w, h, dpi) in sizes) {
            // The root element as SvgWriter writes it.
            val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="${SvgWriter.num(w * 25.4 / dpi)}mm" height="${SvgWriter.num(h * 25.4 / dpi)}mm" viewBox="0 0 $w $h"/>"""
            val doc = com.brushwork.paint.exchange.svg.SvgParser.parse(svg.toByteArray())
            val place = VectorImport.placement(com.brushwork.paint.exchange.svg.SvgToVector.viewportSize(doc, dpi), w, h, fill = false)
            if (place != com.brushwork.paint.exchange.svg.Affine.IDENTITY) scaled += "$w x $h at $dpi dpi: $place"
        }
        assertTrue("placed 1:1: $scaled", scaled.isEmpty())
        // An A4 page from Illustrator on the A4 canvas at 300 dpi (2480 x 3508; 210 mm is 2480.3 px).
        val a4 = com.brushwork.paint.exchange.svg.SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="210mm" height="297mm" viewBox="0 0 595.28 841.89"/>""".toByteArray())
        assertEquals(com.brushwork.paint.exchange.svg.Affine.IDENTITY, VectorImport.placement(com.brushwork.paint.exchange.svg.SvgToVector.viewportSize(a4, 300f), 2480, 3508, fill = false))
        // Larger than the canvas by more than rounding: 90 % and centred, as before.
        assertNotEquals(com.brushwork.paint.exchange.svg.Affine.IDENTITY, VectorImport.placement(2482f to 3508f, 2480, 3508, fill = false))
        // Through the real writer, into an artwork of the same size.
        val repo = ProjectRepository(app)
        val c = newCanvas(repo, "Same size")
        c.toggleVectorMode()
        QaExchange.brush(c, 0xFF2040A0.toInt(), 40f to 360f, 560f to 40f, size = 6f)
        idle(c)
        val target = newCanvas(repo, "Target")
        importFile(target, export(c, payload = false))
        target.currentTool.discard()
        idle(target)
        val stroke = c.activeLayer.vector!!.objects.single()
        val path = target.doc.layers.single { it.name == "Imported SVG" }.vector!!.objects.single()
        assertEquals("the import is where it was in its file", ink(alone(stroke)), ink(alone(path)))
    }

    /** Pixels of [a] and [b] that differ by more than [tol] in any channel. */
    private fun differing(a: Bitmap, b: Bitmap, tol: Int): Int {
        val pa = pixels(a)
        val pb = pixels(b)
        var n = 0
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            if (x == y) continue
            for (s in intArrayOf(24, 16, 8, 0)) {
                if (abs(((x ushr s) and 0xFF) - ((y ushr s) and 0xFF)) > tol) { n++; break }
            }
        }
        return n
    }

    /**
     * Gradients and texts through an SVG without Brushwork data (as another app would hand it
     * back): a linear and an elliptical radial gradient, a closed shape with an outline, and a
     * horizontal text come back where they were and looking the same.
     */
    @Test
    fun gradientsShapesAndTextComeBackFromAPlainSvg() {
        val repo = ProjectRepository(app)
        val c = newCanvas(repo, "Plain")
        c.toggleVectorMode()
        val layer = c.activeLayer
        fun rect(l: Float, t: Float, r: Float, b: Float, fill: com.brushwork.paint.vector.VPaint) = VPath(
            0,
            subpaths = listOf(com.brushwork.paint.vector.VSubpath(listOf(com.brushwork.paint.vector.VAnchor(l, t, true), com.brushwork.paint.vector.VAnchor(r, t, true), com.brushwork.paint.vector.VAnchor(r, b, true), com.brushwork.paint.vector.VAnchor(l, b, true)), closed = true)),
            fill = fill,
        )
        val stops = listOf(com.brushwork.paint.vector.VStop(0f, 0xFFFF2000.toInt()), com.brushwork.paint.vector.VStop(0.5f, 0x8000C040.toInt()), com.brushwork.paint.vector.VStop(1f, 0xFF2040FF.toInt()))
        val objects = listOf(
            rect(20f, 20f, 280f, 180f, com.brushwork.paint.vector.VPaint.Linear(40f, 30f, 260f, 170f, stops)),
            // An ellipse-shaped radial gradient (gradientTransform: scale x 2, then moved).
            rect(300f, 20f, 580f, 180f, com.brushwork.paint.vector.VPaint.Radial(0f, 0f, 60f, stops, listOf(2f, 0f, 0f, 1f, 440f, 100f))),
            ExchangeFixtures.box(60f, 220f, 260f, 360f),
        )
        c.vectors.addObjects(layer, objects, "Add")
        idle(c)
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as com.brushwork.paint.tools.text.TextTool
        text.startTextAt(420f, 290f)
        text.setText("Plain SVG")
        text.updateSpec { it.copy(sizePx = 40f, color = 0xFF202080.toInt()) }
        text.confirmEditor()
        assertTrue(text.commitItem())
        val textLayer = c.activeLayer
        assertTrue(textLayer.isTextLayer)
        c.selectTool(ToolId.BRUSH)
        idle(c)

        val target = newCanvas(repo, "Back")
        importFile(target, export(c, payload = false))
        target.currentTool.discard()
        idle(target)
        val back = target.doc.layers.single { it.name == "Imported SVG" }.vector!!.objects
        assertEquals(3, back.size)
        for ((o, p) in layer.vector!!.objects.zip(back)) {
            val a = alone(o)
            val b = alone(p)
            val inked = pixels(a).count { it ushr 24 != 0 }
            val off = differing(a, b, 6)
            assertTrue("object ${o.id} (${(o as VPath).fill}) looks the same: $off of $inked pixels differ", off <= inked / 100)
        }
        // The text: a text layer again, the same words where they were.
        val texts = target.doc.layers.filter { it.isTextLayer }
        assertEquals(1, texts.size)
        assertEquals("Plain SVG", com.brushwork.paint.tools.text.TextCodec.decode(texts[0].textData)!!.text)
        val was = ink(textLayer.bitmap)!!
        val now = ink(texts[0].bitmap)!!
        for ((x, y) in listOf(was.left to now.left, was.top to now.top, was.right to now.right, was.bottom to now.bottom)) {
            assertTrue("text where it was: $was vs $now", abs(x - y) <= 2)
        }
        val item0 = com.brushwork.paint.tools.text.TextCodec.decode(textLayer.textData)!!
        val item1 = com.brushwork.paint.tools.text.TextCodec.decode(texts[0].textData)!!
        // The same style, the same place (rounding aside: 3 decimals in the file, font metrics).
        assertEquals(item0.spec, item1.spec)
        assertEquals(item0.cx, item1.cx, 0.5f)
        assertEquals(item0.cy, item1.cy, 0.5f)
    }

    @Test
    fun vectorWorkSurvivesUndoSaveExportAndImport() {
        val repo = ProjectRepository(app)
        val c = newCanvas(repo, "Checklist")
        // Vector.
        c.toggleVectorMode()
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        // Three brush strokes.
        QaExchange.brush(c, 0xFF203060.toInt(), 60f to 80f, 300f to 100f, 540f to 80f, size = 14f)
        QaExchange.brush(c, 0xFFC03020.toInt(), 80f to 190f, 520f to 210f, size = 10f)
        QaExchange.brush(c, 0xFF208040.toInt(), 100f to 320f, 300f to 300f, size = 18f)
        idle(c)
        assertEquals(3, layer.vector!!.objects.size)
        // A partial erase across the middle of the first stroke cuts it in two.
        c.selectTool(ToolId.ERASER)
        // "Partial" in the vector eraser's strip (Object, the default, takes whole objects).
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        c.presetFor(ToolId.ERASER)?.let { c.updatePreset(ToolId.ERASER, it.copy(size = 24f)) }
        drag(c, 300f to 40f, 300f to 140f)
        idle(c)
        assertTrue(layer.isVectorLayer)
        val erased = layer.vector!!.objects
        assertEquals("the first stroke is two pieces now: $erased", 4, erased.size)
        assertTrue(erased.all { it is VStroke })
        // Lasso around the first stroke's pieces and the second stroke.
        c.selectTool(ToolId.LASSO)
        drag(c, 30f to 30f, 570f to 30f, 570f to 250f, 30f to 250f, 30f to 30f)
        assertTrue("selected", Smoke.pumpUntil { c.vectors.selectedIds.size == 3 })
        val picked = c.vectors.selectedIds.toSet()
        // Transform: a pinch with one finger on the objects, the other beside them: 80 %.
        c.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c.currentTool.hasPendingWork })
        val tt = c.currentTool as TransformTool
        val box = tt.transformState!!.bounds()
        val t = c.viewTransform
        val a = t.docToScreen(300f, 150f)
        val b = t.docToScreen(300f, 380f)
        assertTrue("one finger inside the box: $box", 150f > box.top && 150f < box.bottom && 380f > box.bottom)
        val focus = Vec2((a.x + b.x) / 2f, (a.y + b.y) / 2f)
        assertTrue("the pinch scales the objects", c.twoFingerStart(focus, Vec2(a.x, a.y), Vec2(b.x, b.y)))
        c.twoFingerGesture(Vec2(0f, 0f), 0.8f, 0f)
        c.twoFingerEnd(cancelled = false)
        val steps = c.undoManager.undoCount
        tt.commit()
        idle(c)
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        assertTrue("still a vector layer", layer.isVectorLayer)
        val done = layer.vector!!
        assertEquals(4, done.objects.size)
        for (o in done.objects.filter { it.id in picked }) {
            val before = VectorOps.bounds(erased.single { it.id == o.id })
            val after = VectorOps.bounds(o)
            assertEquals("scaled to 80 %: ${o.id}", before.width() * 0.8f, after.width(), before.width() * 0.05f)
        }
        assertEquals("the third stroke stayed", erased.single { it.id !in picked && VectorOps.bounds(it).top > 250f }, done.objects.single { it.id !in picked })
        val donePixels = pixels(layer.bitmap)
        // Undo ×5, redo ×5: the same objects and the same pixels.
        val history = c.undoManager.undoCount
        repeat(5) { c.undo(); idle(c) }
        assertEquals(history - 5, c.undoManager.undoCount)
        assertNotEquals(done, layer.vector)
        repeat(5) { c.redo(); idle(c) }
        assertEquals(done, layer.vector)
        assertArrayEquals(donePixels, pixels(layer.bitmap))
        // Save, reopen.
        runBlocking { repo.save(c.doc, null) }
        val reopened = runBlocking { repo.load(c.doc.id) }
        assertTrue("no load warnings: ${reopened.loadWarnings}", reopened.loadWarnings.isEmpty())
        val c2 = track(Smoke.controller(app, reopened))
        val layer2 = c2.doc.layers.single { it.isVectorLayer }
        assertEquals(done, layer2.vector)
        assertArrayEquals(donePixels, pixels(layer2.bitmap))

        // Export SVG with the Brushwork data → Import SVG, "Editable layers": exactly the objects.
        val target = newCanvas(repo, "Target")
        importFile(target, export(c2, payload = true), editable = true)
        val back = target.doc.layers.single { it.isVectorLayer }
        assertEquals(done, back.vector)
        assertArrayEquals(donePixels, pixels(back.bitmap))

        // Export SVG without it → Import SVG: one outline per stroke (the cut stroke as two), each
        // where the stroke was and looking like it.
        val foreign = newCanvas(repo, "Foreign")
        importFile(foreign, export(c2, payload = false))
        val imported = foreign.doc.layers.single { it.name == "Imported SVG" }
        assertTrue(imported.isVectorLayer)
        assertNotNull("Transform opened on the import", (foreign.currentTool as? TransformTool)?.transformState)
        foreign.currentTool.discard()
        idle(foreign)
        val paths = imported.vector!!.objects
        assertEquals("object count", done.objects.size, paths.size)
        assertTrue(paths.all { it is VPath })
        for ((o, p) in done.objects.zip(paths)) {
            val ib = ink(alone(o))!!
            val pb = ink(alone(p))!!
            for ((x, y) in listOf(ib.left to pb.left, ib.top to pb.top, ib.right to pb.right, ib.bottom to pb.bottom)) {
                assertTrue("bounds of ${o.id}: $ib vs $pb", abs(x - y) <= 1)
            }
            // Coverage: the outline paints where the stroke paints (edges aside).
            val sp = pixels(alone(o))
            val pp = pixels(alone(p))
            var inked = 0
            var differ = 0
            for (i in sp.indices) {
                val sa = sp[i] ushr 24
                val pa = pp[i] ushr 24
                if (sa > 0 || pa > 0) inked++
                if (abs(sa - pa) > 128) differ++
            }
            assertTrue("${o.id} looks the same: $differ of $inked pixels differ", differ <= inked / 50)
        }
        Smoke.assertQuiet(foreign, "foreign import")
    }
}
