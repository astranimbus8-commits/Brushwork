package com.brushwork.paint.v19.cross

import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.v19.a4.A4Kit
import com.brushwork.paint.v19.cross.CrossUndoSnap.oneExactStep
import com.brushwork.paint.v19.cross.CrossUndoSnap.px
import com.brushwork.paint.vector.select.ObjectEdits
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.9 Gate 2, lane text T1 (A4 x A3; I1, I2, I24): two text layers of two texts each merged
 * down (TEXT_ITEMS), then one text of the result edited and texts arranged in it with Bring
 * forward and Send backward (the Text tool's More row). The merged layer is a text layer whose
 * every text opens in the Text tool; the merge, the edit and each arrange are ONE document step
 * each, whose Undo gives the document back exactly and whose Redo the result exactly; after each
 * the layer's pixels are a fresh rendering of its texts in their order. Saved and opened again,
 * every text, its order and the layer's pixels are kept, and every text still opens.
 */
@RunWith(RobolectricTestRunner::class)
class CrossTextMergeEditArrangeTest {
    private val kit = A4Kit(320, 240)
    private val app get() = RuntimeEnvironment.getApplication()
    private val red = 0xFFC02020.toInt()
    private val green = 0xFF208040.toInt()

    @Before
    fun setUp() {
        File(app.filesDir, "projects").deleteRecursively()
    }

    @After
    fun tearDown() = kit.close()

    private fun items(layer: Layer): List<TextItem> = TextCodec.decodeAll(layer.textData)

    private fun texts(layer: Layer): List<String> = items(layer).map { it.text }

    /** I1: the layer's pixels are a fresh rendering of its stored texts, bottom first. */
    private fun assertRendered(what: String, layer: Layer) =
        assertArrayEquals("$what: the pixels are the texts (I1)", kit.renderTexts(layer), kit.pixels(layer.bitmap))

    private fun textTool(c: EditorController): TextTool {
        c.selectTool(ToolId.TEXT)
        return c.tools.getValue(ToolId.TEXT) as TextTool
    }

    /** Every text of [layer] opens in [tool] as itself, at its place; opening records nothing. */
    private fun assertEveryTextOpens(what: String, c: EditorController, tool: TextTool, layer: Layer) {
        val all = items(layer)
        val steps = c.undoManager.undoCount
        for (k in all.indices) {
            assertTrue("$what: text $k opens", tool.editLayer(layer, index = k))
            assertEquals("$what: text $k", all[k], tool.item)
            assertEquals("$what: at its place", k, tool.openIndex)
            assertEquals("$what: as itself", all[k].id, tool.openId)
            tool.discard()
        }
        assertEquals("$what: opening is no step", steps, c.undoManager.undoCount)
    }

    /**
     * "Lower" holds "Arrange" with "Over" on it (overlapping); "Upper" holds "Three" and "Four",
     * apart from each other and from them. Merged down: one text layer of the four, bottom first.
     */
    private fun merged(c: EditorController, exact: Boolean): Layer {
        val lower = kit.textLayer(c, kit.text("Arrange", 110f, 70f), kit.text("Over", 140f, 82f, red), name = "Lower")
        val upper = kit.textLayer(c, kit.text("Three", 80f, 180f, green), kit.text("Four", 240f, 180f), name = "Upper")
        assertEquals(MergeKind.TEXT_ITEMS, c.layerMerge.kindOf(upper, lower))
        if (exact) oneExactStep("the merge", c, "Merge down") { c.mergeDown(upper) } else c.mergeDown(upper)
        kit.settle(c)
        assertTrue("the upper layer went", c.doc.indexOf(upper) < 0)
        assertTrue("the merge keeps a text layer", lower.isTextLayer)
        assertEquals(listOf("Arrange", "Over", "Three", "Four"), texts(lower))
        assertEquals("each text has its own id", 4, items(lower).map { it.id }.toSet().size)
        assertRendered("merged", lower)
        return lower
    }

    /** The edit of "Over" and the two arranges, each checked as one exact step when [exact]. */
    private fun editAndArrange(c: EditorController, tool: TextTool, layer: Layer, exact: Boolean) {
        fun step(why: String, label: String, act: () -> Unit) {
            if (exact) oneExactStep(why, c, label, act) else {
                val steps = c.undoManager.undoCount
                act()
                assertEquals("$why: one step", steps + 1, c.undoManager.undoCount)
            }
            assertRendered(why, layer)
        }
        // Edit "Over": its words and color change; the other texts are stored as they were.
        val before = items(layer)
        assertTrue(tool.editLayer(layer, index = 1))
        step("the edit", "Edit text") {
            tool.setText("Over it")
            tool.updateSpec { it.copy(color = green) }
            assertTrue(tool.commitItem())
        }
        val edited = items(layer)
        assertEquals(listOf("Arrange", "Over it", "Three", "Four"), edited.map { it.text })
        assertEquals("the edited text keeps its id", before[1].id, edited[1].id)
        assertEquals(green, edited[1].spec.color)
        assertEquals("the other texts are untouched", before.filterIndexed { i, _ -> i != 1 }, edited.filterIndexed { i, _ -> i != 1 })

        // Bring forward "Arrange": past "Over it", which it overlaps (pixels change where they meet).
        assertTrue(tool.editLayer(layer, index = 0))
        assertTrue(tool.arrangeShown)
        assertTrue(tool.canBringForward)
        assertFalse(tool.canSendBackward)
        val under = kit.pixels(layer.bitmap)
        step("bring forward", ObjectEdits.Arrange.FORWARD.label) {
            assertTrue(tool.arrangeOpen(1))
            assertEquals("the text stays open in its new place", 1, tool.openIndex)
            assertEquals(edited[0].id, tool.openId)
            tool.discard()
        }
        assertEquals(listOf("Over it", "Arrange", "Three", "Four"), texts(layer))
        assertFalse("where the two texts meet, \"Arrange\" is on top now", under.contentEquals(kit.pixels(layer.bitmap)))

        // Send backward "Four": it meets no text below, so one plain step down.
        assertTrue(tool.editLayer(layer, index = 3))
        assertTrue(tool.canSendBackward)
        assertFalse(tool.canBringForward)
        step("send backward", ObjectEdits.Arrange.BACKWARD.label) {
            assertTrue(tool.arrangeOpen(-1))
            assertEquals(2, tool.openIndex)
            tool.discard()
        }
        assertEquals(listOf("Over it", "Arrange", "Four", "Three"), texts(layer))
        assertEquals("the ids go with their texts", edited.map { it.id }.let { listOf(it[1], it[0], it[3], it[2]) }, items(layer).map { it.id })
    }

    @Test
    fun aMergedLayerOfSeveralTextsEditsAndArrangesOneExactStepEach() {
        val c = kit.controller()
        val layer = merged(c, exact = true)
        val tool = textTool(c)
        assertEveryTextOpens("after the merge", c, tool, layer)
        editAndArrange(c, tool, layer, exact = true)
        assertEveryTextOpens("after the arranges", c, tool, layer)

        // Undo walks the four steps back to the two layers, and Redo forward to the end.
        val end = items(layer)
        repeat(4) { c.undo() }
        assertEquals(listOf("Background", "Lower", "Upper"), c.doc.layers.map { it.name })
        assertEquals(listOf("Arrange", "Over"), texts(layer))
        assertRendered("back at the start", layer)
        repeat(4) { c.redo() }
        kit.settle(c)
        assertEquals(listOf("Background", "Lower"), c.doc.layers.map { it.name })
        assertEquals(end, items(layer))
        assertRendered("forward at the end", layer)
    }

    @Test
    fun saveAndReopenKeepEveryTextItsOrderAndItsPixels() {
        val c = kit.controller()
        val layer = merged(c, exact = false)
        val tool = textTool(c)
        editAndArrange(c, tool, layer, exact = false)
        kit.settle(c)

        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val loaded: Document = runBlocking { ProjectRepository(app).load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        assertEquals(c.doc.layers.map { it.name }, loaded.layers.map { it.name })
        for ((a, b) in c.doc.layers.zip(loaded.layers)) {
            val what = "\"${a.name}\""
            assertEquals("$what: a text layer", a.isTextLayer, b.isTextLayer)
            assertEquals("$what: its texts, in their order", a.textData, b.textData)
            assertEquals("$what: its properties", a.props(), b.props())
            assertTrue("$what: its pixels", px(a.bitmap).contentEquals(px(b.bitmap)))
        }
        val reopened = loaded.layers[c.doc.indexOf(layer)]
        assertEquals(items(layer), items(reopened))
        assertEquals(listOf("Over it", "Arrange", "Four", "Three"), texts(reopened))

        // Every text still opens in the Text tool of the reopened document.
        val rc = EditorController(app, loaded, kit.scope, AppSettings(app))
        assertEveryTextOpens("reopened", rc, textTool(rc), reopened)
    }
}
