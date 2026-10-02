package com.brushwork.paint.exchange

import android.graphics.Canvas
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.export.ColorGlyphs
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.CurveWidths
import com.brushwork.paint.tools.vector.VariableWidthOutline
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 review (A8 export x A7 text, §4.10b "otherwise outlines, else the layer's pixels"): color
 * emoji are pictures in their font, with no glyph outlines, so a text holding one is exported as
 * its pixels wherever it would be outlines (PDF, SVG "Outlines") — before, the outlines silently
 * left the emoji out. A real SVG `<text>` keeps them. And a varying-width plain line is exported
 * with exactly the renderer's outline (A4's CurveWidths, one implementation).
 */
@RunWith(RobolectricTestRunner::class)
class ExportTextEmojiRobolectricTest {

    private fun scene(doc: Document, options: ExportOptions): ExportScene =
        runBlocking { ExportSceneBuilder(controller(doc), options, TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun textItems(doc: Document, options: ExportOptions) = scene(doc, options).layers.first { it.name == "Text" }.items

    /** The fixture document with its text layer saying [text]. */
    private fun withText(text: String): Document {
        val doc = document()
        val layer = doc.layers.first { it.name == "Text" }
        val item = TextItem(text, TextSpec(sizePx = 40f, color = 0xFF883300.toInt()), cx = 150f, cy = 100f)
        layer.bitmap.eraseColor(0)
        TextRenderer.drawItem(Canvas(layer.bitmap), item, TextRenderer.prepare(item), null)
        layer.textData = TextCodec.encode(item)
        return doc
    }

    @Test
    fun outlinedTextWithAColorEmojiIsExportedAsItsPixels() {
        val doc = withText("Hi 😀 there")
        for (opts in listOf(ExportOptions(VectorFormat.PDF), ExportOptions(VectorFormat.SVG, text = TextExportMode.OUTLINES))) {
            val items = textItems(doc, opts)
            assertTrue("${opts.format} ${opts.text}: the layer's pixels, emoji included: $items", items.single() is SceneItem.Image)
        }
        // Real text keeps the emoji (the viewer's own emoji font draws it).
        val svg = textItems(doc, ExportOptions(VectorFormat.SVG)).single() as SceneItem.Text
        assertTrue(svg.lines.joinToString("") { it.text }.contains("😀"))
        // Text without emoji is still outlined (letters filled with the text color).
        val plain = textItems(withText("Hi there"), ExportOptions(VectorFormat.PDF)).single() as SceneItem.Shape
        assertEquals(VPaint.Solid(0xFF883300.toInt()), plain.fill)
    }

    @Test
    fun colorEmojiAreRecognized() {
        for (s in listOf("😀", "⚡", "✅", "❤️", "#️⃣", "🇺🇸", "a🦄b", "⌚")) {
            assertTrue("emoji in \"$s\"", ColorGlyphs.has(s))
        }
        for (s in listOf("", "Hello", "★ stars", "été", "你好", "مرحبا", "1 + 2 = 3", "← →")) {
            assertFalse("no emoji in \"$s\"", ColorGlyphs.has(s))
        }
    }

    @Test
    fun aVaryingWidthPlainLineExportsTheRenderersOutline() {
        val doc = document()
        val layer = doc.layers.first { it.name == "Vector" }
        val sub = VSubpath(listOf(VAnchor(30f, 150f, true, width = 0.3f), VAnchor(140f, 60f, false, width = 2.4f), VAnchor(270f, 160f, true, width = 1f)))
        val path = VPath(0, subpaths = listOf(sub), stroke = VStrokeStyle(color = 0xFF2266AA.toInt(), width = 8f))
        val content = VectorContent.EMPTY.plus(listOf(path)).first
        layer.vector = content
        layer.bitmap.eraseColor(0)
        Canvas(layer.bitmap).drawBitmap(ExchangeFixtures.render(content, doc.width, doc.height), 0f, 0f, null)
        val item = scene(doc, ExportOptions(VectorFormat.SVG)).layers.first { it.name == "Vector" }.items.single() as SceneItem.Shape
        assertEquals(VPaint.Solid(0xFF2266AA.toInt()), item.fill)
        assertFalse("non-zero contours", item.evenOdd)
        val line = CurveWidths.line(VectorOps.curveAnchors(sub), false, path.tension, path.polyline, 8f, CurveWidths.LINE_TOLERANCE)!!
        val expected = VariableWidthOutline.build(line.xs, line.ys, line.ws, line.n, false, CurveWidths.LINE_TOLERANCE)
        assertEquals(expected.ops, item.path.ops)
    }
}
