package com.brushwork.paint.tools.select

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 integration (lead): a plain tap of Lasso or Select shape in "New" mode deselects the
 * selected objects of a vector layer too (no undo step: object selection is not history), and
 * still deselects pixels as before (one "Deselect" step). In the other modes a tap keeps both.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectDeselectTapRobolectricTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun mode(c: EditorController, id: ToolId, m: SelectionMode) {
        when (val t = c.tools.getValue(id)) {
            is LassoTool -> t.mode = m
            is MarqueeTool -> t.mode = m
            else -> error("not a selection tool: $t")
        }
    }

    @Test
    fun aPlainTapInNewModeDeselectsObjects() {
        for (id in listOf(ToolId.LASSO, ToolId.MARQUEE)) {
            val c = kit.controller()
            val layer = c.vec
            c.vectors.addObjects(layer, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(300f, 200f)), "Add")
            c.selectTool(id)
            kit.idle()
            c.vectors.setSelection(layer, setOf(1L, 2L))
            // Add / Subtract / Intersect: a tap keeps the objects.
            mode(c, id, SelectionMode.ADD)
            c.tap(250f, 300f)
            assertEquals("$id", setOf(1L, 2L), c.vectors.selectedIds)
            // New: a tap deselects them, with no step.
            mode(c, id, SelectionMode.REPLACE)
            val steps = c.undoManager.undoCount
            c.tap(250f, 300f)
            assertTrue("$id", c.vectors.selectedIds.isEmpty())
            assertNull(c.vectors.selectedLayer)
            assertEquals("$id: selecting objects is not history", steps, c.undoManager.undoCount)
            // Pixels as before: one "Deselect" step.
            c.setSelection(kit.rectSelection(10, 10, 100, 100))
            c.vectors.setSelection(layer, setOf(2L))
            val before = c.undoManager.undoCount
            c.tap(250f, 300f)
            assertNull(c.selection)
            assertTrue(c.vectors.selectedIds.isEmpty())
            assertEquals(before + 1, c.undoManager.undoCount)
            assertEquals("Deselect", c.undoManager.undoLabel)
        }
    }
}
