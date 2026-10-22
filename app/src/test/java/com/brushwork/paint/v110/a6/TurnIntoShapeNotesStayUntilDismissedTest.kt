package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R11 (a), finding 31): what a lossy "Turn into shape" changed shows as ONE long note
 * (`EditorController.showNote`), the notes joined by "; ", asked for after the Shape tool opened
 * the shape; tool messages leave it waiting. Its "Undo" ([EditorController.undoNote]) drops the
 * open shape and takes the step back, the path returns; once another step followed it does
 * nothing. A path that loses nothing has no note.
 */
@RunWith(RobolectricTestRunner::class)
class TurnIntoShapeNotesStayUntilDismissedTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    /** A closed curve with a gradient fill and varying thickness: two notes. */
    private val lossy: VPath = A6Paths.curve(closed = true).let { p ->
        p.copy(
            subpaths = listOf(p.subpaths[0].copy(anchors = p.subpaths[0].anchors.mapIndexed { i, a -> a.copy(width = if (i % 2 == 0) 1f else 2f) })),
            fill = VPaint.Linear(60f, 0f, 180f, 0f, listOf(VStop(0f, RED), VStop(1f, BLUE))),
        )
    }

    /** Adds [p], selects it alone and turns it into a shape; its id and the path as it was stored. */
    private fun convert(c: EditorController, p: VPath): Pair<Long, VPath> {
        val id = A6Paths.add(kit, c, p).single()
        val stored = c.vec.vector!!.byId(id) as VPath
        A6Paths.select(c, id)
        assertTrue(ObjectActions.turnIntoShape(c))
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        return id to stored
    }

    @Test
    fun theNotesWaitAsOneNoteWhoseUndoTakesTheStepBack() {
        val c = kit.controller()
        val (id, path) = convert(c, lossy)
        val note = c.pendingNote ?: throw AssertionError("a note")
        assertEquals("${ObjectBarLabels110.NOTE_GRADIENT}; ${ObjectBarLabels110.NOTE_WIDTH}", note.text)
        assertTrue("with Undo", note.undoable)
        assertEquals("about the newest step", c.undoManager.undoCount, note.undoCount)
        assertEquals(ObjectBarLabels110.TO_SHAPE, c.undoManager.undoLabel)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("asked for once the Shape tool opened the shape", kit.idleUntil { tool.editingObject })

        // Tool messages leave it waiting.
        c.toast("A tool message")
        kit.idle()
        assertSame(note, c.pendingNote)

        // Its Undo drops the open shape and takes the step back.
        val steps = c.undoManager.undoCount
        assertTrue(c.undoNote(note))
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals(steps - 1, c.undoManager.undoCount)
        assertFalse("the open shape is dropped", tool.hasPendingWork)
        assertEquals("the path is back", path, c.vec.vector!!.byId(id))
        assertFalse("once", c.undoNote(note))
    }

    @Test
    fun afterAnotherStepItsUndoDoesNothing() {
        val c = kit.controller()
        val (id, _) = convert(c, lossy)
        val note = c.pendingNote ?: throw AssertionError("a note")
        (c.tools.getValue(ToolId.SHAPE) as ShapeTool).discard()
        kit.idle()
        A6Paths.add(kit, c, A6Paths.polyline())
        val content = c.vec.vector
        assertFalse(c.undoNote(note))
        assertSame(content, c.vec.vector)
        assertTrue("still the shape", c.vec.vector!!.byId(id) !is VPath)
    }

    @Test
    fun nothingLostNoNote() {
        val c = kit.controller()
        c.pendingNote = null
        convert(c, A6Paths.curve(closed = true).copy(fill = VPaint.Solid(RED)))
        assertNull(c.pendingNote)
        assertEquals(ToolId.SHAPE, c.activeToolId)
    }

    private companion object {
        const val RED = 0xFFE04020.toInt()
        const val BLUE = 0xFF203080.toInt()
    }
}
