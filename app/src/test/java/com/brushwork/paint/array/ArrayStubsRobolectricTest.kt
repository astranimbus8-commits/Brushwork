package com.brushwork.paint.array

import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.StubToolFixtures
import com.brushwork.paint.tools.ToolId
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F5 (design §4.6): the Array stubs keep v1.6 behaviour on `main`: the Array tool leaves no
 * trace on any layer kind, every `ArrayOps` entry (and the controller's entry points) refuses
 * with no effect, and `ArrayTransforms` declines. Area E deletes or rewrites this test when it
 * fills the stubs (`StubToolsRobolectricTest` must keep passing with the real tool).
 */
@RunWith(RobolectricTestRunner::class)
class ArrayStubsRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @Test
    fun theArrayToolLeavesNoTrace() {
        val c = Smoke.controller(app)
        StubToolFixtures.assertLeavesNoTrace(c, ToolId.ARRAY, StubToolFixtures.everyKind(c))
    }

    @Test
    fun arrayOpsRefuseAndTheTransformMapperDeclines() {
        val c = Smoke.controller(app)
        val layer = c.activeLayer
        val steps = c.undoManager.undoCount
        assertFalse(ArrayOps.fromSelection(c))
        assertFalse(ArrayOps.fromObjects(c, setOf(1L)))
        assertFalse(ArrayOps.fromLayer(c, layer))
        assertFalse(ArrayOps.edit(c, layer, ArraySpec()))
        assertFalse(ArrayOps.apply(c, layer))
        assertFalse(ArrayOps.remove(c, layer))
        assertFalse(ArrayOps.editSource(c, layer))
        assertFalse(ArrayOps.finishSource(c, layer))
        c.setSelection(Selection.all(c.doc.width, c.doc.height), recordUndo = false)
        assertFalse(c.arrayFromSelection())
        assertFalse(c.arrayFromObjects(setOf(1L)))
        assertFalse(c.arrayWholeLayer(layer))
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertNull(layer.array)
        // Transform keeps refusing arrayed layers.
        assertNull(ArrayTransforms.mapped(layer, floatArrayOf(2f, 0f, 0f, 0f, 2f, 0f, 0f, 0f, 1f)))
    }
}
