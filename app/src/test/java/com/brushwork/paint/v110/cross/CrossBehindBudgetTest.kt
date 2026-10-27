package com.brushwork.paint.v110.cross

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a11.A11Kit
import com.brushwork.paint.v110.a3.BehindKit
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 G2 (compat, C3; A3 x A7): "array behind" adds a layer, so it meets the layer budget. On a
 * phone whose memory is known and small (J = 8 MiB, so Classic's limit is a few 400 x 300
 * layers), with as many layers as Classic allows: at CLASSIC "Behind" is refused with v1.9's
 * frozen "Layer limit reached (N) for this canvas size" and changes nothing (no step, no layer,
 * every pixel, the active layer, the selection and the tool as they were); at FULL with a swap
 * store (no fixed limit) the same "Behind" is one step, and undo puts the document back exactly,
 * redo brings the layer back.
 */
abstract class CrossBehindBudgetBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(
        level,
        budgets = Budgets(J_SMALL, (J_SMALL * 0.55).toLong(), known = true, stepDown = 0),
        swap = if (level == EngineLevel.FULL) FakeSwapStore(capacityBytes = 1L shl 30) else null,
    )

    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    /** Everything "Behind" may change: the layers (id, name, folder, pixels), the active layer, the selection's bounds and the tool. */
    private class State(c: EditorController) {
        val ids = c.doc.layers.map { it.id }
        val names = c.doc.layers.map { it.name }
        val parents = c.doc.layers.map { it.parentId }
        val pixels = c.doc.layers.map { A11Kit.bytesOf(it.pixels, c.doc.width, c.doc.height) }
        val active = c.activeLayer.id
        val selection = c.selection?.bounds?.let { android.graphics.Rect(it) }
        val tool = c.activeToolId

        fun assertSame(what: String, c: EditorController, withTool: Boolean = true) {
            val now = State(c)
            assertEquals("$what: the layers", ids, now.ids)
            assertEquals("$what: their names", names, now.names)
            assertEquals("$what: their folders", parents, now.parents)
            for (i in pixels.indices) assertArrayEquals("$what: the pixels of layer ${ids[i]}", pixels[i], now.pixels[i])
            assertEquals("$what: the active layer", active, now.active)
            assertEquals("$what: the selection", selection, now.selection)
            if (withTool) assertEquals("$what: the tool", tool, now.tool)
        }
    }

    @Test
    fun behindAtClassicsLayerLimit() {
        val c = ArrayLookKit.controller(app)
        val n = LayerBudgetV110.classicMaxLayers(J_SMALL * 55 / 100, J_SMALL, c.doc.layerBytes)
        assertEquals("Classic's limit on this memory: a few layers", 6, n)
        val budget = LayerBudget.current as LayerBudgetV110
        if (level == EngineLevel.CLASSIC) {
            assertEquals(n, c.maxLayers)
            assertFalse("Classic keeps a limit", budget.noFixedLimit)
        } else {
            assertTrue("FULL with a swap store has no fixed limit", budget.noFixedLimit)
            assertEquals(LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
        }
        // As many layers as Classic allows: new (blank) layers above the two, then a painted
        // square selected on layer 2, the source of "Behind".
        while (c.doc.layers.size < n) assertNotNull(c.addLayer())
        val src = BehindKit.selectedSquare(c)
        ArrayLookKit.settle(c)
        assertEquals(n, c.doc.layers.size)
        val before = State(c)
        val steps = c.undoManager.undoCount

        if (level == EngineLevel.CLASSIC) {
            assertFalse("Classic is full", c.canAddLayer)
            BehindKit.refused(c, "Layer limit reached ($n) for this canvas size") { ArrayBehind.fromSelectionBelow(c) }
            before.assertSame("$level: refused", c)
            assertEquals("no array layer", 0, c.doc.layers.count { ArrayBehind.isCopiesOnly(it) })
            return
        }

        BehindKit.oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromSelectionBelow(c)) }
        assertEquals("one more layer than Classic allows", n + 1, c.doc.layers.size)
        val made = c.activeLayer
        assertTrue("copies only", ArrayBehind.isCopiesOnly(made))
        assertEquals("directly below the source", c.doc.indexOf(src) - 1, c.doc.indexOf(made))
        assertNull(LayerTree.check(c.doc.layers))
        val after = State(c)

        BehindKit.undo(c)
        assertEquals(steps, c.undoManager.undoCount)
        // The document as it was (the Array tool "Behind" opened is not part of it and stays on).
        before.assertSame("$level: undo", c, withTool = false)
        assertSame("undo selects the source again", src, c.activeLayer)

        BehindKit.redo(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(n + 1, c.doc.layers.size)
        assertEquals("redo brings the same layer back", made.id, c.activeLayer.id)
        assertEquals("redo: the layers", after.ids, c.doc.layers.map { it.id })
        assertTrue(ArrayBehind.isCopiesOnly(c.activeLayer))
    }

    private companion object {
        const val J_SMALL = 8L shl 20
    }
}

@RunWith(RobolectricTestRunner::class)
class CrossBehindBudgetClassicTest : CrossBehindBudgetBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class CrossBehindBudgetFullTest : CrossBehindBudgetBase(EngineLevel.FULL)
