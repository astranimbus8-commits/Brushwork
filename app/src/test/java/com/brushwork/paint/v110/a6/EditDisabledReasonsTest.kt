package com.brushwork.paint.v110.a6

import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R10): Edit is shown but refused, with a reason the user can act on, for a path of
 * several parts ([ObjectBarLabels110.NO_EDITOR_PARTS]) and a path of fewer than 2 points
 * ([ObjectBarLabels110.NO_EDITOR_SMALL]): the tap shows the reason and changes nothing (the tool,
 * the selection, the history). A shape and a whole path have no reason.
 */
@RunWith(RobolectricTestRunner::class)
class EditDisabledReasonsTest {
    private val kit = ObjectTestKit(400, 300)

    @After
    fun close() = kit.close()

    private fun assertRefused(c: EditorController, o: VObject, reason: String) {
        val id = A6Paths.add(kit, c, o).single()
        A6Paths.select(c, id)
        assertTrue("Edit shows", ObjectActions.editShown(c))
        assertEquals(reason, ObjectActions.editRefusal(c))
        val tool = c.activeToolId
        val steps = c.undoManager.undoCount
        val content = c.vectors.selectedLayer!!.vector
        c.message = null
        assertFalse("nothing opens", ObjectActions.openInEditor(c))
        kit.idle()
        assertEquals("the reason shows", reason, c.message)
        assertEquals("the tool stays", tool, c.activeToolId)
        assertEquals("the selection stays", setOf(id), c.vectors.selectedIds)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals("the objects stay", content, c.vectors.selectedLayer!!.vector)
    }

    @Test
    fun severalPartsSayTransform() {
        assertRefused(kit.controller(), A6Paths.twoParts(), ObjectBarLabels110.NO_EDITOR_PARTS)
    }

    @Test
    fun onePointIsTooSmall() {
        assertRefused(kit.controller(), A6Paths.onePoint(), ObjectBarLabels110.NO_EDITOR_SMALL)
    }

    @Test
    fun shapesAndWholePathsHaveNoReason() {
        val c = kit.controller()
        val ids = A6Paths.add(kit, c, kit.rectangle(200f, 150f, 80f, 60f), A6Paths.curve(), A6Paths.polyline(), A6Paths.spline(), kit.box(20f, 20f, 60f, 60f))
        for (id in ids) {
            A6Paths.select(c, id)
            assertTrue(ObjectActions.editShown(c))
            assertNull("object $id", ObjectActions.editRefusal(c))
        }
        // Nothing or several selected: no reason either (Edit is hidden then).
        c.vectors.setSelection(null, emptySet())
        assertNull(ObjectActions.editRefusal(c))
        assertFalse(ObjectActions.editShown(c))
    }
}
