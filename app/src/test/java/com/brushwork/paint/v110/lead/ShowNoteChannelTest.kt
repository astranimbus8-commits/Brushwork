package com.brushwork.paint.v110.lead

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 F0 (R11, finding 31; lead, frozen): the long-note channel in the real editor at 392 dp.
 * `EditorController.showNote(text, undoable = true)` made together with a tool switch and that
 * tool's opening message shows the note (the message does not stay over it) with "Undo" and a
 * dismiss action; a later message waits behind it instead of replacing it and shows once the note
 * goes; the note's "Undo" takes back the newest step. A note that is not undoable has no "Undo";
 * its dismiss action closes it. The note's Undo never takes back a newer step than its own.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.lead.shownotesandbox"])
class ShowNoteChannelTest {

    @Test
    fun longNoteStaysUntilDismissedAndItsUndoTakesBackItsStep() = Qa19Ui.uiTest { h ->
        h.section("392 dp") { notes(h) }
    }

    private fun notes(h: Qa19Harness) {
        val s = h.editor(Smoke.document(400, 300, layers = 2, whiteBottom = true))
        val c = s.c
        val undoTexts = texts().count { it == UNDO }
        // The step the note is about.
        c.addLayer()
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount

        // A tool switch, its opening message and the note, all at once (as A6's "Turn into shape").
        c.selectTool(ToolId.SHAPE)
        c.toast(OPENING)
        c.showNote(NOTE, undoable = true)
        Qa19Ui.settle()
        assertNull("the screen took the note", c.pendingNote)
        assertTrue("the note shows: ${texts()}", NOTE in texts())
        assertFalse("the tool's opening message does not stay over it", OPENING in texts())
        assertEquals("the note has Undo", undoTexts + 1, texts().count { it == UNDO })
        assertTrue("the note has a dismiss action", hasDescription(DISMISS))

        // A later message does not replace it.
        c.toast(LATER)
        Qa19Ui.settle()
        assertTrue("the note stays", NOTE in texts())
        assertFalse("the later message waits", LATER in texts())

        // Its Undo takes back the step; the waiting message shows then.
        click(textNode(UNDO, last = true))
        Qa19Ui.settle()
        assertEquals("Undo took back the step", layers - 1, c.doc.layers.size)
        assertEquals(steps - 1, c.undoManager.undoCount)
        assertFalse("the note is gone", NOTE in texts())
        assertTrue("the waiting message shows: ${texts()}", LATER in texts())

        // Not undoable: no Undo; the dismiss action closes it.
        c.showNote(PLAIN, undoable = false)
        Qa19Ui.settle()
        assertTrue("the plain note shows", PLAIN in texts())
        assertEquals("no Undo on it", undoTexts, texts().count { it == UNDO })
        click(descriptionNode(DISMISS))
        Qa19Ui.settle()
        assertFalse("dismissed", PLAIN in texts())

        // The note's Undo is only for its own step: a newer step makes it do nothing.
        c.addLayer()
        val note = EditorController.PendingNote(NOTE, undoable = true, undoCount = c.undoManager.undoCount)
        c.addLayer()
        val after = c.doc.layers.size
        assertFalse("a newer step is on top", c.undoNote(note))
        assertEquals("nothing was taken back", after, c.doc.layers.size)
        Smoke.assertQuiet(c, "after the notes")
    }

    private fun texts(): List<String> =
        RobolectricUi.elements().flatMap { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }.orEmpty() }

    private fun hasDescription(d: String): Boolean =
        RobolectricUi.elements().any { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }

    private fun textNode(t: String, last: Boolean): SemanticsNode {
        val all = RobolectricUi.elements().filter { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == t } == true }
        return (if (last) all.last() else all.first()).node
    }

    private fun descriptionNode(d: String): SemanticsNode =
        RobolectricUi.elements().last { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(d) == true }.node

    private fun click(node: SemanticsNode) {
        var n: SemanticsNode? = node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "not clickable" }.invoke()
    }

    private companion object {
        const val UNDO = "Undo"
        const val DISMISS = "Dismiss"
        const val OPENING = "The tool's opening message"
        const val NOTE = "A long note about the last step; and a second part"
        const val LATER = "A later message"
        const val PLAIN = "A note without Undo"
    }
}
