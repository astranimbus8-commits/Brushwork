package com.brushwork.paint.qa

import android.net.Uri
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportJob
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext

/**
 * QA, §7 checklist 1's end: the vector drawing made with the tools is exported (SVG, PDF) through
 * the export sheet's Save as… and imported back ("Made with Brushwork" → Editable layers): the
 * same objects, bounds and pixels, one undo step, still editable. Also: what the export writes is
 * what the user sees, also when the last edit (a Transform committed by the export itself) still
 * renders in the background.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorExchangeQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    /** Jobs start after 3 s (real time) unless a flush waits for them (then at once after that). */
    private class LateWorker : CoroutineDispatcher() {
        private val ex = Executors.newSingleThreadExecutor { Thread(it, "late-vector-worker").apply { isDaemon = true } }
        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ex.execute { Thread.sleep(3000); block.run() }
        }
    }

    /** Strokes (finger, stylus), a shape, a curve with a thick point, a polyline. */
    private fun drawing(): Layer {
        r = VectorQaRig(480, 320)
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        val vec = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 80f, 440f to 60f); r.checkpoint("s1")
        r.stylusStroke(40f to 120f, 240f to 140f, 440f to 120f); r.checkpoint("s2")
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40B060.toInt()) }
        r.stroke(300f to 180f, 350f to 220f, 400f to 260f)
        shape.commit(); r.checkpoint("ellipse")
        r.tool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        r.tap(40f, 200f); r.tap(140f, 280f); r.tap(260f, 200f)
        curve.select(1); curve.setWidth(1, 2.5f); curve.endNumericEdit()
        curve.commit(); r.checkpoint("curve")
        r.tool(ToolId.BRUSH)
        return vec
    }

    private fun file(name: String) = File(r.activity.cacheDir, name).also { it.delete() }

    private fun exportTo(f: File, format: VectorFormat) {
        ExportJob(c, ExportOptions(format)).saveTo(r.activity, Uri.fromFile(f))
        assertTrue("exported", Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering && f.length() > 0 })
        Smoke.pump(40)
    }

    private fun payloadVector(f: File, format: VectorFormat, name: String): VectorContent? {
        val payload = if (format == VectorFormat.SVG) SvgParser.parse(f.readBytes()).payload() else OwnPdfReader(ArrayPdfBytes(f.readBytes())).payload()
        return assertNotNull(payload).let { payload!!.layers.first { it.props.name == name }.vector }
    }

    private fun roundTrip(format: VectorFormat) {
        val vec = drawing()
        val content = vec.vector!!
        val f = file("qa-roundtrip.${format.extension}")
        exportTo(f, format)
        assertEquals("the file holds the objects", content, payloadVector(f, format, vec.name))
        // Import it back into the artwork: "Made with Brushwork" -> Editable layers.
        val state = ExchangeUiState(c)
        state.context = r.activity
        val steps = c.undoManager.undoCount
        val layers = c.doc.layers.size
        state.importUri(Uri.fromFile(f))
        assertTrue("asked", Smoke.pumpUntil(20_000) { state.dialog is ExchangeDialog.MadeWithBrushwork && c.busyMessage == null })
        state.answerEditable()
        assertTrue("imported", Smoke.pumpUntil(30_000) { c.doc.layers.size > layers && c.busyMessage == null && !c.vectors.isRendering })
        Smoke.pump(40)
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
        val back = c.doc.layers.drop(layers).single { it.isVectorLayer }
        val v = back.vector!!
        assertEquals("object count", content.objects.size, v.objects.size)
        for ((a, b) in content.objects.zip(v.objects)) {
            assertEquals("bounds of ${a.id}", VectorOps.bounds(a), VectorOps.bounds(b))
        }
        assertEquals("the same objects", content.objects.map { it.withId(0) }, v.objects.map { it.withId(0) })
        r.assertCacheFresh("imported layer", back)
        assertEquals("the same pixels", r.pixels(vec.bitmap).toList(), r.pixels(back.bitmap).toList())
        // Still editable: the imported layer is a vector layer the tools work on.
        c.selectLayer(back)
        assertTrue(c.isVectorMode)
        r.resync()
        r.stroke(60f to 300f, 400f to 300f)
        r.checkpoint("a stroke on the imported layer")
        assertEquals(content.objects.size + 1, back.vector!!.objects.size)
        // One undo takes the stroke back, another the whole import.
        r.undoAndCheck("undo the stroke")
        c.undo()
        Smoke.pump(40)
        assertEquals(layers, c.doc.layers.size)
    }

    @Test
    fun svgExportAndImportBringBackTheSameEditableObjects() = roundTrip(VectorFormat.SVG)

    @Test
    fun pdfExportAndImportBringBackTheSameEditableObjects() = roundTrip(VectorFormat.PDF)

    @Test
    fun exportingWhileTheTransformItCommitsStillRendersWritesTheTransformedObjects() {
        val vec = drawing()
        c.vectors.workerDispatcher = LateWorker()
        // Transform: everything moved and turned, not confirmed; then Export SVG > Save as…
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        t.moveBy(20.5f, 10.5f)
        t.setRotation(12.0); t.endNumericEdit()
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val f = file("qa-pending.svg")
        // The export commits the transform (its render goes to the background) and writes.
        exportTo(f, VectorFormat.SVG)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val shown = vec.vector!!
        assertEquals("the file holds what is on the canvas (the transformed objects)", shown, payloadVector(f, VectorFormat.SVG, vec.name))
        r.assertCacheFresh("after the export", vec, tolerance = 2, maxOffPermille = 5)
    }

    @Test
    fun sharingAPngWhileTheTransformItCommitsStillRendersSharesWhatIsOnTheCanvas() {
        drawing()
        c.vectors.workerDispatcher = LateWorker()
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        t.moveBy(40.5f, 20.5f)
        t.setRotation(15.0); t.endNumericEdit()
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val png = File(File(r.activity.cacheDir, "exports"), "${c.doc.name}.png").also { it.delete() }
        // Overflow menu > Share: commits the transform and flattens the artwork.
        com.brushwork.paint.ui.editor.EditorActions(c, r.activity).share()
        assertTrue("shared", Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering && png.length() > 0 })
        Smoke.pump(40)
        val shared = android.graphics.BitmapFactory.decodeFile(png.path)!!
        val shown = c.compositor.renderFlattened()
        var off = 0
        for (y in 0 until shown.height) for (x in 0 until shown.width) {
            val a = shared.getPixel(x, y)
            val b = shown.getPixel(x, y)
            var d = 0
            for (s in intArrayOf(24, 16, 8, 0)) d = maxOf(d, kotlin.math.abs(((a ushr s) and 0xFF) - ((b ushr s) and 0xFF)))
            if (d > 2) off++
        }
        assertTrue("the shared PNG is what is on the canvas: $off pixels differ", off < 20)
    }
}
