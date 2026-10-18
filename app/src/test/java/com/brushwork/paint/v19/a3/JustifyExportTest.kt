package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextExport
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextJustify
import com.brushwork.paint.tools.text.TextLineRun
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 2 (R4): justified text in SVG / PDF. A justified line exports as one run per
 * word, each keeping its space, at the x its pixels have (the last run ends at the box edge); the
 * last line under "Justify lines" stays one run, under "Justify the last line too" it splits too;
 * text that isn't justified exports whole lines as in v1.8; a line spread between its letters
 * (Japanese) exports as outlines; the SVG scene carries the runs.
 */
@RunWith(RobolectricTestRunner::class)
class JustifyExportTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val para = "Brushwork sets justified lines flush with both edges of the box, word by word, while the last line keeps its alignment."

    private fun boxed(j: TextJustify, text: String = para) =
        TextItem(text, TextSpec(sizePx = 24f, justify = j, box = TextBoxSpec(width = 300f, padding = 6f)), 200f, 200f)

    /** [runs] grouped by line (baseline), in order. */
    private fun byLine(runs: List<TextLineRun>): List<List<TextLineRun>> = runs.groupBy { it.baseline }.values.toList()

    @Test
    fun justifiedLinesAreRunsOfWords() {
        val item = boxed(TextJustify.ON)
        val block = TextRenderer.prepare(item).block!!
        val lines = block.wrapLines!!.filter { it.end > it.start }
        val runs = TextExport.lines(item)
        assertNotNull(runs)
        val grouped = byLine(runs!!)
        assertEquals(lines.size, grouped.size)
        val inset = block.inset
        val edge = inset + block.contentWidth
        for ((i, l) in lines.withIndex()) {
            val g = grouped[i]
            assertEquals("line $i reads as its text", para.substring(l.start, l.end), g.joinToString("") { it.text })
            val last = i == lines.lastIndex
            if (last) {
                assertEquals("ON: the last line is one run", 1, g.size)
                assertEquals(inset, g[0].x, 0.01f)
            } else {
                assertEquals("one run per word", para.substring(l.start, l.end).split(' ').size, g.size)
                for (r in g.dropLast(1)) assertTrue("'${r.text}' keeps its space", r.text.endsWith(" "))
                assertEquals(inset, g[0].x, 0.01f)
                for (k in 1 until g.size) assertTrue(g[k].x > g[k - 1].x)
                // The runs sit where the pixels are: the last word ends at the box edge.
                val end = g.last()
                assertEquals("line $i", edge, end.x + block.textPaint.measureText(end.text), 1.5f)
                assertEquals(l.baseline + inset, end.baseline, 0f)
            }
        }
    }

    @Test
    fun justifyAllSplitsTheLastLineToo() {
        // At 300 px the paragraph ends in the one-word line "alignment.": under ALL its letters
        // spread, which a run can't carry, so the text exports as outlines.
        val oneWord = boxed(TextJustify.ALL)
        val ob = TextRenderer.prepare(oneWord).block!!
        val lastLine = ob.wrapLines!!.last { it.end > it.start }
        assertEquals("alignment.", para.substring(lastLine.start, lastLine.end))
        assertTrue(ob.justified!!.spreadsClusters)
        assertNull(TextExport.lines(oneWord))
        assertNotNull(TextExport.outlineParts(oneWord))
        // A box whose last line holds several words: that line splits into word runs too.
        val item = (240..340 step 4).asSequence()
            .map { w -> oneWord.copy(spec = oneWord.spec.copy(box = TextBoxSpec(width = w.toFloat(), padding = 6f))) }
            .first { val b = TextRenderer.prepare(it).block!!; val l = b.wrapLines!!.last { x -> x.end > x.start }; para.substring(l.start, l.end).contains(' ') }
        val runs = byLine(TextExport.lines(item)!!)
        val last = runs.last()
        assertTrue("the last line is spread too", last.size > 1)
        val block = TextRenderer.prepare(item).block!!
        assertEquals(block.inset + block.contentWidth, last.last().x + block.textPaint.measureText(last.last().text), 1.5f)
    }

    @Test
    fun textThatIsNotJustifiedExportsWholeLines() {
        val off = TextExport.lines(boxed(TextJustify.OFF))!!
        val sl = TextRenderer.prepare(boxed(TextJustify.OFF)).block!!.staticLayout!!
        assertEquals("one run per line, as v1.8", sl.lineCount, off.size)
        // "Justify lines" on point text moves nothing: the v1.8 runs.
        val point = TextItem("One line", TextSpec(sizePx = 24f, justify = TextJustify.ON), 100f, 100f)
        fun placed(runs: List<TextLineRun>?) = runs!!.map { Triple(it.text, it.x, it.baseline) }
        assertEquals(placed(TextExport.lines(point.copy(spec = point.spec.copy(justify = TextJustify.OFF)))), placed(TextExport.lines(point)))
    }

    @Test
    fun lettersSpreadBetweenClustersExportAsOutlines() {
        val jp = boxed(TextJustify.ON, "テキストが入ります。このテキストはダミーです。文字の大きさや行間を確かめるために入れています。")
        assertTrue(TextRenderer.prepare(jp).block!!.justified!!.spreadsClusters)
        assertNull(TextExport.lines(jp))
        assertNotNull(TextExport.outlineParts(jp))
    }

    @Test
    fun theSvgSceneCarriesTheWordRuns() {
        val d = Document("t", "t", 400, 400).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 400)) }
        val c = EditorController(context, d, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 200f)
        tool.setText(para)
        tool.updateSpec { it.copy(sizePx = 24f, justify = TextJustify.ON, box = TextBoxSpec(width = 300f)) }
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val scene = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, text = TextExportMode.EDITABLE), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val text = scene.layers.single { it.name == layer.name }.items.filterIsInstance<SceneItem.Text>().single()
        val item = TextCodec.decodeAll(layer.textData).single()
        assertEquals(TextJustify.ON, item.spec.justify)
        val want = TextExport.lines(item)!!
        assertEquals(want.map { it.text }, text.lines.map { it.text })
        assertEquals(want.map { it.x }, text.lines.map { it.x })
        assertTrue("more runs than lines", text.lines.size > TextRenderer.prepare(item).block!!.lineCount)
    }
}
