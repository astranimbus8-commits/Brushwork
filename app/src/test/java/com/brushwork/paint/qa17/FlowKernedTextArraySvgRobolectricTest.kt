package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextOutlinePart
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.vector.VPaint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.ByteArrayOutputStream

/**
 * v1.7 integration flow (design §6.2, areas D, E and A): a KERNED text (a pair kern, with "Font
 * kerning" on) placed with the Text tool, arrayed in Transform mode (a spiral) and exported to
 * SVG. Each action is one step; the layer stays a text with a live array whose pixels are its
 * data drawn (I1, I14); in the SVG every copy is the text's OUTLINE (no `<text>`), each exactly
 * the kerned source outline mapped by its `ArrayLayout` matrix, so the kerning shows in every
 * copy (the letters a fifth of an em closer than unkerned); undo walks back step by step.
 */
@RunWith(RobolectricTestRunner::class)
class FlowKernedTextArraySvgRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 400
    private val h = 300

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun setUp(): Pair<EditorController, TextTool> {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("flow-kern", "flow-kern", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.TEXT)
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The letters' outline of [item] as the export writes it. */
    private fun letters(item: TextItem): VectorPath {
        val parts = TextExport.outlineParts(item)!!
        assertEquals("no box, no outline stroke: the letters only", listOf(TextOutlinePart.Kind.TEXT), parts.map { it.kind })
        return ExportSceneBuilder.vectorPathOf(parts.single().path)
    }

    private fun map(m: FloatArray, p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    private fun pointsOf(p: VectorPath): List<Vec2> = p.ops.mapNotNull {
        when (it) {
            is PathOp.MoveTo -> it.p
            is PathOp.LineTo -> it.p
            is PathOp.CubicTo -> it.p
            PathOp.Close -> null
        }
    }

    private fun assertSamePath(what: String, expected: VectorPath, actual: VectorPath) {
        assertEquals("$what: ops", expected.ops.map { it::class }, actual.ops.map { it::class })
        for ((e, a) in pointsOf(expected).zip(pointsOf(actual))) {
            assertEquals("$what: x", e.x, a.x, 1e-3f)
            assertEquals("$what: y", e.y, a.y, 1e-3f)
        }
    }

    @Test
    fun aKernedTextArrayedInTransformModeExportsEveryCopyAsKernedOutlines() {
        val (c, tool) = setUp()
        val steps0 = c.undoManager.undoCount

        // 1. The Text tool: "AVATAR" kerned at "A|V" (−200 / 1000 em), Font kerning on.
        val before = c.doc.layers.toSet()
        tool.startTextAt(200f, 150f)
        tool.setText("AVATAR")
        tool.setSizePx(40f)
        tool.setKerns(0..0, -200)
        tool.confirmEditor()
        tool.commit()
        idle()
        val layer = c.doc.layers.single { it !in before && it.textData != null }
        assertEquals(steps0 + 1, c.undoManager.undoCount)
        assertEquals("Add text", c.undoManager.undoLabel)
        val item = TextCodec.decode(layer.textData)!!
        assertEquals(listOf(TextKern(0, -200)), item.kerns)
        assertTrue(item.spec.fontKerning)
        val data = layer.textData
        val single = pixels(layer.bitmap)

        // 2. Array (one step), then a spiral in Transform mode (one step "Edit array").
        assertTrue(ArrayOps.fromLayer(c, layer))
        idle()
        assertEquals(steps0 + 2, c.undoManager.undoCount)
        assertEquals(ArrayLabels.BUTTON, c.undoManager.undoLabel)
        val plain = layer.array!!.spec
        val spiral = plain.copy(mode = ArrayMode.TRANSFORM, count = COUNT, moveX = 18f, moveY = 12f, turnDeg = 50f, scale = 0.8f)
        assertTrue(ArrayOps.edit(c, layer, spiral))
        idle()
        assertEquals(steps0 + 3, c.undoManager.undoCount)
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        val spec = layer.array!!.spec
        assertEquals(ArrayMode.TRANSFORM, spec.mode)
        assertEquals(COUNT, spec.count)
        assertEquals("the text stays text, kerns and all", data, layer.textData)

        // I1 / I14: the pixels are the array of the stored text, drawn from the data.
        val source = ArrayDraw.sourceBounds(layer.dataSnapshot())!!
        val fresh = BitmapUtils.createLayerBitmap(w, h)
        ArrayDraw.drawWithArray(Canvas(fresh), layer.array, source) { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }
        assertArrayEquals("the cache is the data drawn", pixels(fresh), pixels(layer.bitmap))

        // 3. SVG: every copy is the kerned outline mapped by its matrix, copy N − 1 at the bottom.
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val items = scene.layers.single { it.name == layer.name }.items
        assertEquals("one outline per copy", COUNT, items.size)
        assertTrue("outlines, not text or a picture: $items", items.all { it is SceneItem.Shape })
        val shapes = items.map { it as SceneItem.Shape }
        val own = letters(item)
        val ms = ArrayLayout.matrices(spec, source)
        assertEquals(COUNT, ms.size)
        for (k in 0 until COUNT) {
            val s = shapes[COUNT - 1 - k]
            assertEquals("copy $k: the text color", VPaint.Solid(item.spec.color), s.fill)
            assertSamePath("copy $k", own.transformed { map(ms[k], it) }, s.path)
        }
        // The spiral: each copy turned 50° and scaled 0.8 from the one before.
        val b0 = shapes.last().path.bounds()!!
        val b1 = shapes[COUNT - 2].path.bounds()!!
        assertTrue("copy 1 is placed elsewhere", b0 != b1)
        // The kerning shows in the outline positions: the kerned letters are a fifth of an em
        // (8 px) closer than the same text unkerned, and every copy is that outline mapped.
        val unkerned = letters(item.copy(kerns = emptyList())).bounds()!!
        assertEquals(8f, unkerned.width - b0.width, 1.5f)

        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = out.toString("UTF-8")
        assertFalse("no <text>", svg.contains("<text"))
        assertTrue("a <path> per copy", Regex("<path\\b").findAll(svg).count() >= COUNT)

        // Undo, one step per action.
        c.undo()
        idle()
        assertEquals("undo Edit array", plain, layer.array!!.spec)
        c.undo()
        idle()
        assertNull("undo Array", layer.array)
        assertEquals(data, layer.textData)
        assertArrayEquals("the single text again", single, pixels(layer.bitmap))
        c.undo()
        idle()
        assertEquals("undo Add text", -1, c.doc.indexOf(layer))
        assertEquals(steps0, c.undoManager.undoCount)
    }

    private companion object {
        const val COUNT = 6
    }
}
