package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.RectF
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
 * v1.7 manual kerning in SVG / PDF export (item 17, area D accept test): a run of `<text>` can't
 * carry a manual kern, nor "Font kerning" off (a viewer sets the letters with the font's kerning),
 * so `TextExport.lines` is null for both and the text exports as its outlines (`<path>`, the look
 * exact). Unkerned text, and kerns that don't apply (vertical text, outside a frame's slice),
 * keep the real `<text>`.
 */
@RunWith(RobolectricTestRunner::class)
class TextKernExportTest {

    private val kern = listOf(TextKern(0, -200))

    @Test
    fun linesAreNullForKernedTextAndFontKerningOff() {
        val plain = TextItem("AVATAR", TextSpec(sizePx = 40f))
        assertNotNull("unkerned text keeps its runs", TextExport.lines(plain))
        assertFalse(TextExport.kerned(plain))
        val kerned = plain.copy(kerns = kern)
        assertTrue(TextExport.kerned(kerned))
        assertNull(TextExport.lines(kerned))
        assertNull("Font kerning off", TextExport.lines(plain.copy(spec = plain.spec.copy(fontKerning = false))))
        // Wrapped text and a fixed box too.
        val boxed = kerned.copy(spec = kerned.spec.copy(box = TextBoxSpec(width = 200f)))
        assertNull(TextExport.lines(boxed))
        // A kern that doesn't apply (beside a line break) changes nothing: runs stay.
        val nearBreak = TextItem("AV\nAT", TextSpec(sizePx = 40f), kerns = listOf(TextKern(1, 300)))
        assertFalse(TextExport.kerned(nearBreak))
        assertNotNull(TextExport.lines(nearBreak))
    }

    @Test
    fun aFrameIsKernedOnlyByTheKernsOfItsSlice() {
        val story = "First frame words. Second frame AVATAR"
        val start = story.indexOf("Second")
        val box = TextBoxSpec(width = 400f, minHeight = 60f)
        fun frame(kerns: List<TextKern>) = TextItem(
            spec = TextSpec(sizePx = 20f, box = box),
            thread = TextThreadSpec(storyId = 3, index = 1, story = story, start = start, end = story.length),
            kerns = kerns,
        ).sanitized()
        // A story kern in the frame before: this frame keeps its runs.
        val before = frame(listOf(TextKern(1, -200)))
        assertFalse(TextExport.kerned(before))
        assertNotNull(TextExport.lines(before))
        val own = frame(listOf(TextKern(story.indexOf("AV"), -200)))
        assertTrue(TextExport.kerned(own))
        assertNull(TextExport.lines(own))
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
    fun theSvgOfAKernedTextHasAPathAndNoText() {
        val item = TextItem("AVATAR", TextSpec(sizePx = 40f, color = 0xFF223344.toInt()), cx = 150f, cy = 100f)
        val plain = svg(scene(withText(item), ExportOptions(VectorFormat.SVG)))
        assertTrue("unkerned: real text", plain.contains("<text"))
        for ((name, it) in listOf("kerned" to item.copy(kerns = kern), "font kerning off" to item.copy(spec = item.spec.copy(fontKerning = false)))) {
            val s = scene(withText(it), ExportOptions(VectorFormat.SVG))
            val items = s.layers.first { l -> l.name == "Text" }.items
            assertTrue("$name: $items", items.none { i -> i is SceneItem.Text })
            assertEquals(VPaint.Solid(0xFF223344.toInt()), items.filterIsInstance<SceneItem.Shape>().last().fill)
            val out = svg(s)
            assertTrue("$name: a <path>", out.contains("<path"))
            assertFalse("$name: no <text>", out.contains("<text"))
        }
        // The outline is where the pixels are: the kerned letters are a fifth of an em closer.
        val wide = RectF().also { TextExport.outlineParts(item)!!.last().path.computeBounds(it, true) }
        val tight = RectF().also { TextExport.outlineParts(item.copy(kerns = kern))!!.last().path.computeBounds(it, true) }
        assertEquals(8f, wide.width() - tight.width(), 1.5f)
    }
}
