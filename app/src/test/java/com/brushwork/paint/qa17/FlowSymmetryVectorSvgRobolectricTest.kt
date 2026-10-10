package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.StrokeEnvelopeExport
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.sin

/**
 * v1.7 integration flow (design §6.2, areas H, F and A; §6.4 row 12): a Rotation × 6 stroke of a
 * solid brush (the Pen) in VECTOR mode on a vector layer, saved, reopened and exported to SVG
 * ("Outlines", the default). It is ONE step and ONE
 * `VStroke` carrying the six maps; reopened it is still that one stroke and redraws as drawn
 * (±1); the SVG has it as ONE path made of six envelopes, one per copy, each where its map puts
 * the stroke.
 */
@RunWith(RobolectricTestRunner::class)
class FlowSymmetryVectorSvgRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    private fun controller(doc: Document): EditorController {
        val settings = AppSettings(app)
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun maxChannelDiff(a: Int, b: Int): Int {
        var m = 0
        for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
        return m
    }

    @Test
    fun aRotationSixVectorStrokeSavedReopenedAndExportedIsOneStrokeOfSixEnvelopes() = runBlocking {
        AppSettings(app).prefs.edit().clear().commit()
        val doc = Document("flow-sym", "Flow sym", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = controller(doc)
        c.color = 0xFF2060C0.toInt()
        c.tools
        c.selectTool(ToolId.BRUSH)
        // The Pen: a solid brush, so "Outlines" (the SVG default) writes envelopes, not a picture.
        c.brush = BrushLibrary.defaultBrush
        assertTrue(StrokeEnvelopeExport.isSolid(c.brush))
        assertTrue("vector mode: the active layer is a vector layer", c.isVectorMode)
        val sym = SymmetrySettings(SymmetryType.ROTATION, divisions = 6)
        c.updateSymmetry(sym)

        // The stroke: one step, one VStroke with the six maps.
        val pts = List(24) { k ->
            val t = k / 23f
            ToolPoint(170f + 50f * t, 60f + 12f * sin(t * 6f), 1f, k.toLong())
        }
        val steps = c.undoManager.undoCount
        c.pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) c.pointerMove(p)
        c.pointerUp(pts.last())
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val drawn = c.doc.layers[1].vector!!.objects.single() as VStroke
        val maps = SymmetryMaps.transforms(sym, w, h, pts[0].x, pts[0].y)
        assertEquals(6, maps.size)
        assertEquals(6, drawn.copies.size)
        val live = pixels(c.doc.layers[1].bitmap)

        // Saved and reopened: still ONE stroke with its six maps, and the same pixels.
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(sym, loaded.symmetry)
        val layer = loaded.layers[1]
        val stroke = layer.vector!!.objects.single() as VStroke
        assertEquals(drawn.id, stroke.id)
        assertEquals("the six maps are saved with the stroke", 6, stroke.copies.size)
        for ((a, b) in maps.zip(stroke.copies)) assertArrayEquals(a, b, 1e-4f)
        assertArrayEquals("the saved pixels come back", live, pixels(layer.bitmap))
        // It redraws from its data as it was drawn (±1).
        val redraw = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(redraw), layer.vector!!, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        val again = pixels(redraw)
        var bad = 0
        for (i in live.indices) if (maxChannelDiff(live[i], again[i]) > 1) bad++
        assertEquals("the reopened redraw equals the live stroke (±1)", 0, bad)

        // Exported to SVG from the reopened artwork: ONE path, the six envelopes, non-zero.
        val reopened = controller(loaded)
        val scene = ExportSceneBuilder(reopened, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        val shape = scene.layers.single { it.name == "Layer 1" }.items.single() as SceneItem.Shape
        val envelopes = StrokeEnvelopeExport.ofCopies(stroke)
        assertEquals("one envelope per copy", 6, envelopes.size)
        assertEquals(envelopes.flatMap { it.ops }, shape.path.ops)
        assertFalse(shape.evenOdd)
        assertEquals(StrokeEnvelopeExport.fillColor(stroke.color, stroke.preset, stroke.opacity), (shape.fill as VPaint.Solid).color)
        // Each envelope is where its map puts the stroke: the map applied to the stroke's first
        // point lies inside that envelope's bounds, and the six are apart.
        val boxes = envelopes.map { it.bounds()!! }
        for ((k, m) in maps.withIndex()) {
            val x = m[0] * pts[0].x + m[1] * pts[0].y + m[2]
            val y = m[3] * pts[0].x + m[4] * pts[0].y + m[5]
            val b = boxes[k]
            assertTrue("copy $k's start ($x, $y) in its envelope $b", x in b.left - 2f..b.right + 2f && y in b.top - 2f..b.bottom + 2f)
        }
        val centres = boxes.map { (it.left + it.right) / 2f to (it.top + it.bottom) / 2f }
        for (i in centres.indices) for (j in i + 1 until centres.size) {
            val d = abs(centres[i].first - centres[j].first) + abs(centres[i].second - centres[j].second)
            assertTrue("envelopes $i and $j apart", d > 20f)
        }

        val out = ByteArrayOutputStream()
        SvgWriter(scene).write(out)
        val svg = out.toString("UTF-8")
        val paths = Regex("<path\\b[^>]*\\sd=\"([^\"]*)\"").findAll(svg).map { it.groupValues[1] }.toList()
        assertEquals("one <path> in the SVG: $paths", 1, paths.size)
        assertEquals("its subpaths: the envelopes'", shape.path.ops.count { it is PathOp.MoveTo }, paths.single().count { it == 'M' })
    }
}
