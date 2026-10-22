package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 review (R10): the object bar's Edit on a layer locked or hidden after the object was
 * selected says so (the layer's message, `EditorController.checkEditable`) and changes nothing:
 * the object stays selected, the tool stays, no editor opens, no step. Once the layer is usable
 * again the same Edit opens the object.
 */
@RunWith(RobolectricTestRunner::class)
class EditLockedLayerTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    /** Selects [o] alone, makes the layer unusable with [block] and taps Edit: refused, nothing changed. */
    private fun assertRefused(c: EditorController, o: VObject, what: String, block: (Layer) -> Unit): Long {
        val id = A6Paths.add(kit, c, o).single()
        A6Paths.select(c, id)
        block(c.vec)
        val tool = c.activeToolId
        val steps = c.undoManager.undoCount
        val content = c.vec.vector
        c.message = null
        assertFalse("$what: nothing opens", ObjectActions.openInEditor(c))
        kit.idle()
        val message = c.message
        assertNotNull("$what: the layer's message", message)
        assertTrue("$what: names the layer: $message", message!!.contains(c.vec.name))
        assertEquals("$what: the selection stays", setOf(id), c.vectors.selectedIds)
        assertEquals("$what: the tool stays", tool, c.activeToolId)
        assertEquals("$what: no step", steps, c.undoManager.undoCount)
        assertEquals("$what: the objects stay", content, c.vec.vector)
        assertFalse("$what: no shape open", (c.tools.getValue(ToolId.SHAPE) as ShapeTool).editingObject)
        assertFalse("$what: no path open", (c.tools.getValue(ToolId.CURVE) as CurveTool).isReopened)
        return id
    }

    @Test
    fun aLockedLayerRefusesEditAndThenOpensOnceUnlocked() {
        val c = kit.controller()
        val id = assertRefused(c, kit.rectangle(200f, 150f, 120f, 80f), "a shape on a locked layer") { it.locked = true }
        c.vec.locked = false
        assertTrue(ObjectActions.openInEditor(c))
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue("unlocked, Edit opens it", kit.idleUntil { tool.editingObject })
        assertEquals(ToolId.SHAPE, c.activeToolId)
        assertTrue("then the selection is cleared", c.vectors.selectedIds.isEmpty())
        assertTrue(c.vec.vector!!.byId(id) != null)
    }

    @Test
    fun aLockedLayerRefusesEditOnAPath() {
        assertRefused(kit.controller(), A6Paths.curve(), "a path on a locked layer") { it.locked = true }
    }

    @Test
    fun aHiddenLayerRefusesEdit() {
        val c = kit.controller()
        assertRefused(c, kit.rectangle(200f, 150f, 120f, 80f), "a shape on a hidden layer") { it.visible = false }
        c.vec.visible = true
        c.vectors.setSelection(null, emptySet())
        assertRefused(c, A6Paths.polyline(), "a polyline on a hidden layer") { it.visible = false }
    }
}
