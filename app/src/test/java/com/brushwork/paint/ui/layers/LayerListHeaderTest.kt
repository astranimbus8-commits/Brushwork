package com.brushwork.paint.ui.layers

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 F5 (design §4.6): the layer list has two header items (the Selection row and the ONE
 * saved-selections item): neither is dragged or dropped on, and the list still opens with one
 * row of context above the active layer (the top of the list when the top layer is active).
 */
class LayerListHeaderTest {
    @Test
    fun theTwoHeadersAreNotLayerItems() {
        assertFalse("the Selection row", isLayerItem(0))
        assertFalse("the saved selections", isLayerItem(1))
        assertTrue("the top layer row", isLayerItem(2))
    }

    @Test
    fun theListOpensWithOneRowOfContextAboveTheActiveLayer() {
        assertEquals("top layer active: the list's top (the Selection row shows)", 0, contextItem(0))
        assertEquals("the second row active: the top row first", 2, contextItem(1))
        assertEquals("the fifth row active: the fourth first", 5, contextItem(4))
    }
}
