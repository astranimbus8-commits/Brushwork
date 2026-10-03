package com.brushwork.paint.qa16

import android.graphics.Canvas
import android.graphics.Matrix
import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.LetterScaleAlign
import com.brushwork.paint.tools.text.LetterScaleCurve
import com.brushwork.paint.tools.text.LetterScaleDirection
import com.brushwork.paint.tools.text.LetterScaleSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA: a text with letter scaling (`TextSpec.letterScale`, made with the Text tool:
 * "ELTON JOHN" from 100 % down to 45 %, aligned on the baseline, same ratio) keeps it through save
 * and reload, Duplicate artwork, and SVG / PDF export brought back into an artwork of the same
 * size; each time its pixels are its rendering (I1) and a tap with the Text tool reopens it with
 * the same scaling. Its export is outlines (no `<text>` in the SVG, shapes in the PDF), as
 * designed for scaled letters.
 */
@RunWith(RobolectricTestRunner::class)
class LetterScaleRoundTripTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val scaling = LetterScaleSpec(
        smallestPercent = 45f, direction = LetterScaleDirection.START_TO_END,
        align = LetterScaleAlign.BASELINE, curve = LetterScaleCurve.RATIO,
    )

    private fun made(): Pair<EditorController, Layer> {
        val c = Smoke.controller(app, Smoke.document(400, 300, layers = 1, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.color = 0xFF203040.toInt()
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText("ELTON JOHN")
        tool.setSizePx(48f)
        tool.updateSpec { it.copy(letterScale = scaling) }
        tool.commit()
        assertEquals("✓ is one step", 1, c.undoManager.undoCount)
        val layer = c.doc.layers.single { it.isTextLayer }
        assertEquals(scaling, item(layer).spec.letterScale)
        assertRendered(layer, "made")
        return c to layer
    }

    private fun item(l: Layer): TextItem = TextCodec.decode(l.textData)!!

    /** I1: the layer's pixels are the rendering of the item it stores (alpha-aware for PNG round trips). */
    private fun assertRendered(l: Layer, where: String, tolerance: Int = 0) {
        val it = item(l)
        val fresh = BitmapUtils.createLayerBitmap(l.width, l.height)
        TextRenderer.drawItem(Canvas(fresh), it, TextRenderer.prepare(it), null)
        val d = V15Fixtures.maxDiff(fresh, l.bitmap)
        assertTrue("$where: pixels vs a fresh rendering differ by $d", d <= tolerance)
    }

    /** A tap with the Text tool on the letters reopens the text with its scaling; ✕ changes nothing. */
    private fun assertReopens(c: EditorController, l: Layer, where: String) {
        c.viewTransform.set(Matrix())
        c.selectLayer(l)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        val data = l.textData
        val steps = c.undoManager.undoCount
        val it = item(l)
        c.pointerDown(ToolPoint(it.cx, it.cy))
        c.pointerUp(ToolPoint(it.cx, it.cy))
        assertTrue("$where: reopened", tool.editingLayer === l)
        assertEquals("$where: with its scaling", scaling, tool.item!!.spec.letterScale)
        tool.discard()
        assertEquals("$where: ✕ keeps the data", data, l.textData)
        assertEquals("$where: ✕ records nothing", steps, c.undoManager.undoCount)
        c.selectTool(ToolId.BRUSH)
    }

    @Test
    fun saveReloadAndDuplicateArtworkKeepTheScaling() {
        val (c, layer) = made()
        val repo = ProjectRepository(app)
        runBlocking { repo.save(c.doc, null) }
        for ((where, id) in listOf("reloaded" to c.doc.id, "duplicate" to runBlocking { repo.duplicate(c.doc.id) })) {
            val doc = runBlocking { repo.load(id) }
            assertEquals(emptyList<String>(), doc.loadWarnings)
            val lc = Smoke.controller(app, doc)
            val l = doc.layers.single { it.isTextLayer }
            assertEquals("$where: the same item", item(layer), item(l))
            assertTrue("$where: the same pixels", layer.bitmap.sameAs(l.bitmap))
            assertRendered(l, where)
            assertReopens(lc, l, where)
        }
    }

    @Test
    fun exportsAreOutlinesAndBringTheScalingBack() {
        val (c, layer) = made()
        for (format in VectorFormat.entries) {
            val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
            val items = scene.layers.single { it.name == layer.name }.items
            assertTrue("$format: outlines, no text items: $items", items.isNotEmpty() && items.none { it is SceneItem.Text })
            val bytes = V15Fixtures.export(c, ExportOptions(format))
            if (format == VectorFormat.SVG) assertFalse("no <text> in the SVG", String(bytes, Charsets.UTF_8).contains("<text"))
            val file = File(app.cacheDir, "letter-scale.${format.extension}").apply { writeBytes(bytes) }
            val fresh = Smoke.controller(app, Smoke.document(400, 300, layers = 1, whiteBottom = true))
            val state = ExchangeUiState(fresh)
            state.context = app
            state.importUri(Uri.fromFile(file))
            assertTrue("$format: asked", Smoke.pumpUntil { state.dialog is ExchangeDialog.MadeWithBrushwork && fresh.busyMessage == null })
            state.answerEditable()
            assertTrue("$format: imported", Smoke.pumpUntil { fresh.busyMessage == null && fresh.doc.layers.any { it.isTextLayer } })
            val l = fresh.doc.layers.single { it.isTextLayer }
            assertEquals("$format: the same item", item(layer), item(l))
            assertRendered(l, "$format import", tolerance = 0)
            assertReopens(fresh, l, "$format import")
        }
    }
}
