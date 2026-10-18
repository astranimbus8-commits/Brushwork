package com.brushwork.paint.v19.cross

import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.cross.CrossUndoSnap.oneExactStep
import com.brushwork.paint.vector.select.ObjectActions
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 Gate 2, lane text T2 (A3 x A4; I1, I2, I24): Duplicate of a text of a layer of several
 * texts made by a merge, from the Text tool's More row ([TextTool.duplicateOpen]) and from the
 * selection bar ([SelectionDuplicate.duplicate]). Each is ONE document step
 * [LayerLabels19.DUPLICATE_STEP]; the copy is a new editable text of the same layer, right above
 * its original, offset like the object bar's Duplicate, with an id of its own; the other texts are
 * stored as they were; one Undo removes exactly the copy (the document as it was, pixels and
 * data) and one Redo brings exactly it back.
 */
@RunWith(RobolectricTestRunner::class)
class CrossTextDuplicateTest {
    private val kit = A4Kit(320, 240)

    @After
    fun tearDown() = kit.close()

    private fun items(layer: Layer): List<TextItem> = TextCodec.decodeAll(layer.textData)

    private fun assertRendered(what: String, layer: Layer) =
        assertArrayEquals("$what: the pixels are the texts (I1)", kit.renderTexts(layer), kit.pixels(layer.bitmap))

    /** [copy] is [original] moved by [d] right and down, with a new id among [before]'s. */
    private fun assertCopyOf(what: String, original: TextItem, copy: TextItem, d: Float, before: List<TextItem>) {
        assertEquals("$what: its words", original.text, copy.text)
        assertEquals("$what: its look", original.spec, copy.spec)
        assertEquals("$what: offset right", original.cx + d, copy.cx, 0.01f)
        assertEquals("$what: offset down", original.cy + d, copy.cy, 0.01f)
        assertFalse("$what: an id of its own", copy.id in before.map { it.id })
    }

    /** Duplicated at [index] of [before] (the copy right above it): every other text as it was. */
    private fun assertOnlyTheCopyAdded(what: String, layer: Layer, before: List<TextItem>, index: Int, d: Float) {
        val after = items(layer)
        assertEquals("$what: one text more", before.size + 1, after.size)
        assertCopyOf(what, before[index], after[index + 1], d, before)
        assertEquals("$what: the other texts are untouched", before, after.filterIndexed { i, _ -> i != index + 1 })
        assertEquals("$what: every id is its own", after.size, after.map { it.id }.toSet().size)
        assertRendered(what, layer)
    }

    @Test
    fun duplicateFromTheMoreRowAndFromTheSelectionBarIsOneExactStepEach() {
        val c = kit.controller()
        // Two layers of two texts each, far apart, merged down: one text layer of the four.
        val lower = kit.textLayer(c, kit.text("One", 60f, 50f), kit.text("Two", 200f, 50f, 0xFFC02020.toInt()), name = "Lower")
        val upper = kit.textLayer(c, kit.text("Three", 60f, 170f, 0xFF208040.toInt()), kit.text("Four", 230f, 170f), name = "Upper")
        assertEquals(MergeKind.TEXT_ITEMS, c.layerMerge.kindOf(upper, lower))
        c.mergeDown(upper)
        kit.settle(c)
        assertEquals(listOf("One", "Two", "Three", "Four"), items(lower).map { it.text })
        val d = ObjectActions.duplicateOffset(c)
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool

        // The More row: "Two" open, Duplicate; the copy opens in its place, right above "Two".
        val start = items(lower)
        assertTrue(tool.editLayer(lower, index = 1))
        assertTrue(tool.canDuplicate)
        oneExactStep("More row", c, LayerLabels19.DUPLICATE_STEP) {
            assertTrue(tool.duplicateOpen())
            assertEquals("the copy opens", 2, tool.openIndex)
            assertEquals(items(lower)[2].id, tool.openId)
            tool.discard()
        }
        assertOnlyTheCopyAdded("More row", lower, start, 1, d)
        val afterRow = items(lower)
        assertTrue("the copy is an editable text", tool.editLayer(lower, index = 2))
        assertEquals(afterRow[2], tool.item)
        tool.discard()

        // The selection bar: a selection over "Three" only, Duplicate in layer.
        val sel = Path().apply { addRect(20f, 140f, 110f, 200f, Path.Direction.CW) }
        c.setSelection(Selection.fromPath(sel, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        assertEquals(SelectionDuplicate.Kind.TEXTS, SelectionDuplicate.kindOf(c))
        oneExactStep("selection bar", c, LayerLabels19.DUPLICATE_STEP) {
            c.message = null
            assertTrue(SelectionDuplicate.duplicate(c))
            assertEquals(String.format(LayerLabels19.DUPLICATED, 1), c.message)
        }
        assertOnlyTheCopyAdded("selection bar", lower, afterRow, 3, d)
        assertTrue("the copy is an editable text", tool.editLayer(lower, index = 4))
        assertEquals(items(lower)[4], tool.item)
        tool.discard()

        // Undo removes the two copies, newest first, and nothing else.
        c.undo()
        assertEquals(afterRow, items(lower))
        c.undo()
        assertEquals(start, items(lower))
        assertRendered("both copies undone", lower)
        assertNull("no pending text", tool.item)
    }
}
