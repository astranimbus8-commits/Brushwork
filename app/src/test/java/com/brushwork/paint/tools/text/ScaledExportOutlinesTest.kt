package com.brushwork.paint.tools.text

import android.graphics.Canvas
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.vector.VPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * v1.6 §3.5(c, d) "ScaledExportOutlinesTest" (V9): `TextExport.lines` is null for text with
 * letter scaling on — its FIRST check, before the laid-out lines are looked at — so SVG and PDF
 * export such a text as its outlines (every letter at its size and place, the look exact): the
 * SVG has no `<text>` for that layer. Unscaled text keeps its real `<text>`.
 */
@RunWith(RobolectricTestRunner::class)
class ScaledExportOutlinesTest {

    private val on = LetterScaleSpec(smallestPercent = 50f)

    @Test
    fun linesAreNullFirstForScaledText() {
        val plain = TextItem("ELTON JOHN", TextSpec(sizePx = 40f))
        assertNotNull(TextExport.lines(plain))
        val scaled = plain.copy(spec = plain.spec.copy(letterScale = on))
        assertNull(TextExport.lines(scaled))
        // Even where the lines would come first (wrapped text, a frame) or nothing is laid out.
        assertNull(TextExport.lines(TextItem("", TextSpec(letterScale = on))))
        val wrapped = scaled.copy(spec = scaled.spec.copy(box = TextBoxSpec(width = 200f)), wrap = TextWrapSpec(sourceLayerId = 4, polygons = listOf(WrapPolygon(listOf(0f, 10f, 10f), listOf(0f, 0f, 10f)))))
        assertNull(TextExport.lines(wrapped))
        val frame = TextItem(spec = TextSpec(sizePx = 20f, box = TextBoxSpec(width = 200f, minHeight = 60f), letterScale = on), thread = TextThreadSpec(storyId = 3, story = "A story", end = 7)).sanitized()
        assertNull(TextExport.lines(frame))
        // Its parts are the letters (filled with the text color) at their sizes.
        val parts = TextExport.outlineParts(scaled)!!
        assertEquals(TextOutlinePart.Kind.TEXT, parts.last().kind)
        assertFalse(parts.last().path.isEmpty)
    }

    private fun withText(item: TextItem): Document {
        val doc = document()
        val layer = doc.layers.first { it.name == "Text" }
        layer.bitmap.eraseColor(0)
        TextRenderer.drawItem(Canvas(layer.bitmap), item, TextRenderer.prepare(item), null)
        layer.textData = TextCodec.encode(item)
        return doc
    }

    private fun scene(doc: Document, options: ExportOptions): ExportScene =
        runBlocking { ExportSceneBuilder(controller(doc), options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun svg(scene: ExportScene): String {
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        return out.toString("UTF-8")
    }

    @Test
    fun theSvgHasNoTextElementForAScaledLayer() {
        val item = TextItem("ELTON JOHN", TextSpec(sizePx = 40f, color = 0xFF223344.toInt()), cx = 150f, cy = 100f)
        // Unscaled: real, editable text.
        val plainScene = scene(withText(item), ExportOptions(VectorFormat.SVG))
        assertTrue(plainScene.layers.first { it.name == "Text" }.items.any { it is SceneItem.Text })
        assertTrue(svg(plainScene).contains("<text"))
        // Scaled: outlines, filled with the text color, and no <text> at all.
        val scaled = item.copy(spec = item.spec.copy(letterScale = on))
        val s = scene(withText(scaled), ExportOptions(VectorFormat.SVG))
        val items = s.layers.first { it.name == "Text" }.items
        assertTrue("$items", items.none { it is SceneItem.Text })
        val shape = items.filterIsInstance<SceneItem.Shape>().last()
        assertEquals(VPaint.Solid(0xFF223344.toInt()), shape.fill)
        assertFalse(svg(s).contains("<text"))
        // PDF: outlines too.
        val pdf = scene(withText(scaled), ExportOptions(VectorFormat.PDF)).layers.first { it.name == "Text" }.items
        assertTrue(pdf.isNotEmpty() && pdf.all { it is SceneItem.Shape })
    }
}
