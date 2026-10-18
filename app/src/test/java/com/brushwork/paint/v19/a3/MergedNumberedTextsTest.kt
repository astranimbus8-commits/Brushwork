package com.brushwork.paint.v19.a3

import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.a4.A4Kit.Companion.assertClose
import com.brushwork.paint.v19.a4.A4Kit.Companion.premultiplied
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.9 A3 step 11 (C22, after A4's merge; R7, R19): list numbers count per text and go on only
 * through the frames of a threaded story, so texts that come together in one layer keep their
 * own numbers:
 * - "Merge down" of two text layers with numbered texts keeps the texts (one step); each text's
 *   markers count from its own "First number", the picture is unchanged and the merged layer's
 *   pixels are a fresh rendering of its texts (I1). Numbers going on from the text below would
 *   draw other pixels, so the comparison tells the two apart.
 * - "Merge folder" of numbered text layers (one of them holding two texts already) does the same.
 * - A text edited after the merge renumbers itself only (the Text tool's item edit).
 * - A frame of a story never joins another layer's texts: such a merge is pixels
 *   ([LayerLabels19.WHY_FRAMES]), so a story's numbers and a text's never share a count.
 */
@RunWith(RobolectricTestRunner::class)
class MergedNumberedTextsTest {
    private val kit = A4Kit(w = 320, h = 240)

    @After
    fun tearDown() = kit.close()

    private val red = 0xFFC02020.toInt()
    private val ink = 0xFF203080.toInt()

    private fun numbered(start: Int = 1, style: TextNumbering = TextNumbering.DECIMAL_DOT) =
        TextListSpec(type = TextListType.NUMBERS, numbering = style, start = start)

    /** A numbered text of [paras] one-word paragraphs at ([cx], [cy]), 20 px. */
    private fun list(paras: List<String>, cx: Float, cy: Float, start: Int = 1, color: Int = ink, style: TextNumbering = TextNumbering.DECIMAL_DOT) =
        TextItem(paras.joinToString("\n"), TextSpec(sizePx = 20f, color = color, list = numbered(start, style)), cx, cy)

    /** The markers [item] draws, top to bottom. */
    private fun markers(item: TextItem): List<String> = TextRenderer.prepare(item).block!!.listMarkers?.markers.orEmpty().map { it.text }

    private fun items(layer: Layer): List<TextItem> = TextCodec.decodeAll(layer.textData)

    /** [items] drawn fresh, bottom first. */
    private fun render(items: List<TextItem>): IntArray {
        val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        val cv = Canvas(b)
        for (t in items) TextRenderer.drawItem(cv, t, TextRenderer.prepare(t), null)
        return kit.pixels(b).also { b.recycle() }
    }

    /** The largest channel difference between [a] and [b]. */
    private fun worst(a: IntArray, b: IntArray): Int {
        var w = 0
        for (i in a.indices) if (a[i] != b[i]) for (s in intArrayOf(0, 8, 16, 24)) w = maxOf(w, abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)))
        return w
    }

    private val lowerParas = listOf("Alpha", "Bravo", "Charlie")
    private val upperParas = listOf("Delta", "Echo")

    /** Two numbered text layers, "Lower" right under "Upper", both counting from 1. */
    private fun twoLayers(c: EditorController): Pair<Layer, Layer> {
        val lower = kit.textLayer(c, list(lowerParas, 80f, 80f), name = "Lower")
        val upper = kit.textLayer(c, list(upperParas, 230f, 170f, color = red), name = "Upper")
        return lower to upper
    }

    @Test
    fun mergeDownKeepsEachTextsOwnNumbers() {
        val c = kit.controller()
        val (lower, upper) = twoLayers(c)
        assertEquals(listOf("1.", "2.", "3."), markers(items(lower).single()))
        assertEquals(listOf("1.", "2."), markers(items(upper).single()))
        assertEquals("texts merge as texts", MergeKind.TEXT_ITEMS, c.layerMerge.kindOf(upper, lower))
        val before = kit.picture(c)
        val steps = c.undoManager.undoCount
        c.mergeDown(upper)
        kit.settle(c)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(-1, c.doc.indexOf(upper))
        val merged = items(lower)
        assertEquals("the texts, bottom first", listOf(lowerParas, upperParas).map { it.joinToString("\n") }, merged.map { it.text })
        // C22: each text counts from its own first number; nothing goes on from the text below.
        assertEquals(listOf(listOf("1.", "2.", "3."), listOf("1.", "2.")), merged.map(::markers))
        assertEquals(listOf(1, 1), merged.map { it.spec.list.start })
        assertClose("the picture", before, kit.picture(c))
        val pixels = kit.pixels(lower.bitmap)
        assertClose("pixels and data (I1)", premultiplied(kit.renderTexts(lower)), premultiplied(pixels))
        // Numbers going on from the three paragraphs below ("4.", "5.") draw other pixels.
        val goingOn = merged.last().let { it.copy(spec = it.spec.copy(list = it.spec.list.copy(start = 4))) }
        assertEquals(listOf("4.", "5."), markers(goingOn))
        assertTrue("numbers going on would show", worst(premultiplied(render(listOf(merged.first(), goingOn))), premultiplied(pixels)) > 32)

        c.undo()
        assertEquals(listOf("Background", "Lower", "Upper"), c.doc.layers.map { it.name })
        assertEquals(listOf("1.", "2.", "3."), markers(items(lower).single()))
        c.redo()
        assertEquals(listOf(listOf("1.", "2.", "3."), listOf("1.", "2.")), items(c.doc.layers[1]).map(::markers))
    }

    @Test
    fun mergeFolderKeepsEachTextsOwnNumbers() {
        val c = kit.controller()
        val f = c.addFolder()!!
        // A layer of two numbered texts (decimal from 1, roman from 3), and one of a lettered text.
        kit.textLayer(c, list(lowerParas, 70f, 60f), list(upperParas, 70f, 170f, start = 3, style = TextNumbering.UPPER_ROMAN), name = "Lower")
        kit.textLayer(c, list(listOf("Foxtrot", "Golf"), 230f, 120f, color = red, style = TextNumbering.LOWER_ALPHA), name = "Upper")
        kit.settle(c)
        val before = kit.picture(c)
        assertTrue(c.mergeFolder(f))
        kit.settle(c)
        assertEquals(FolderLabels.MERGE, c.undoManager.undoLabel)
        assertEquals(listOf("Background", "Folder 1"), c.doc.layers.map { it.name })
        val merged = c.doc.layers[1]
        assertTrue("the folder's texts stay texts", merged.isTextLayer)
        assertEquals(listOf(listOf("1.", "2.", "3."), listOf("III.", "IV."), listOf("a.", "b.")), items(merged).map(::markers))
        assertClose("the picture", before, kit.picture(c))
        assertClose("pixels and data (I1)", premultiplied(kit.renderTexts(merged)), premultiplied(kit.pixels(merged.bitmap)))
    }

    @Test
    fun aTextEditedAfterTheMergeRenumbersOnlyItself() {
        val c = kit.controller()
        val (lower, upper) = twoLayers(c)
        c.mergeDown(upper)
        kit.settle(c)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        assertTrue(tool.editLayer(lower, index = 1))
        assertEquals("the upper text is open", upperParas.joinToString("\n"), tool.item!!.text)
        tool.updateSpec { it.copy(list = it.list.copy(start = 7)) }
        assertTrue(tool.commitItem())
        kit.idle()
        assertNull(tool.item)
        val after = items(lower)
        assertEquals("still two texts in one layer", 2, after.size)
        assertEquals(listOf(listOf("1.", "2.", "3."), listOf("7.", "8.")), after.map(::markers))
        assertClose("pixels and data (I1)", premultiplied(kit.renderTexts(lower)), premultiplied(kit.pixels(lower.bitmap)))
    }

    @Test
    fun aFrameOfAStoryMergesAsPixels() {
        val story = "Hotel\nIndia\nJuliet"
        // (A frame is a box of a fixed size: TextItem.sanitized.)
        val frame = list(story.split("\n"), 90f, 90f).let {
            it.copy(
                spec = it.spec.copy(box = TextBoxSpec(width = 140f, minHeight = 100f)),
                thread = TextThreadSpec(storyId = 9, index = 0, story = story, start = 0, end = story.length),
            )
        }
        assertTrue(frame.sanitized().threaded)
        assertEquals("the frame counts its story", listOf("1.", "2.", "3."), markers(frame))
        val plain = list(upperParas, 230f, 170f, color = red)
        for (frameBelow in listOf(true, false)) {
            val c = kit.controller()
            val lower = kit.textLayer(c, if (frameBelow) frame else plain, name = "Lower")
            val upper = kit.textLayer(c, if (frameBelow) plain else frame, name = "Upper")
            val where = if (frameBelow) "a frame below" else "a frame above"
            assertEquals(where, MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
            assertEquals(where, LayerLabels19.WHY_FRAMES, c.layerMerge.rasterReason(upper, lower))
        }
        // Nor does "Merge folder" put them in one text layer (I24: a frame stays alone in its layer).
        val c = kit.controller()
        val f = c.addFolder()!!
        kit.textLayer(c, frame, name = "Story")
        kit.textLayer(c, plain, name = "Plain")
        kit.settle(c)
        assertTrue(c.mergeFolder(f))
        kit.settle(c)
        val merged = c.doc.layers[1]
        assertEquals("Folder 1", merged.name)
        assertFalse("a frame and a text in a folder merge as pixels", merged.isTextLayer)
    }
}
