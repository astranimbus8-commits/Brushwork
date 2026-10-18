package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.vector.ShapeStripInfo
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9, R14 and I10 (design 3.2 A9 step 5), at the spec level: the Shape More row's object
 * items ([StripKeysV19.DUPLICATE], [StripKeysV19.FORWARD], [StripKeysV19.BACKWARD]) follow v1.8's
 * More items while an open shape is or becomes a shape object, and are left out while the
 * selection bar or the object bar shows (`StripContext.selectionBarShown`, here as the editor
 * computes it): with objects selected under Shape, or a selection, no label shows twice. Never in
 * Points mode (Bevel points is there) nor for a shape of a layer of its own. Gate 2: the paste bar
 * alone (Paste and Hide) does not hide them.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeMoreRowHiddenWithBarsTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private val objectKeys = listOf(StripKeysV19.DUPLICATE, StripKeysV19.FORWARD, StripKeysV19.BACKWARD).map { it.key }
    private val objectLabels = listOf(LayerLabels19.DUPLICATE, ObjectEdits.Arrange.FORWARD.label, ObjectEdits.Arrange.BACKWARD.label)

    /** The strip's items now, with the bars shown or not ([barShown]). */
    private fun items(c: EditorController, tool: ShapeTool, barShown: Boolean): List<StripItem> =
        ShapeStripItems.items(tool, c, ShapeStripInfo.of(tool, c.doc.dpi), {}, {}, {}, {}, barShown = barShown)

    /** What the editor passes as `selectionBarShown` (EditorScreen: the selection bar or the object bar). */
    private fun barsShown(c: EditorController): Boolean = c.selection != null || c.vectors.selectedIds.isNotEmpty()

    private fun assertNoLabelTwice(items: List<StripItem>, where: String) {
        val labels = items.map { it.label }.filter { it.isNotEmpty() }
        assertEquals("$where: a label shows twice: $labels", labels.size, labels.toSet().size)
    }

    private fun assertNoObjectItems(items: List<StripItem>, where: String) {
        assertTrue("$where: ${items.map { it.key }}", items.none { it.key in objectKeys })
        assertTrue("$where: ${items.map { it.label }}", items.none { it.label in objectLabels })
    }

    @Test
    fun withTheBarsHiddenTheObjectItemsFollowV18sMoreItems() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        assertTrue(ShapeStripInfo.of(tool, c.doc.dpi).objectActions)
        val shown = items(c, tool, barShown = barsShown(c))
        val objects = shown.filter { it.key in objectKeys }
        assertEquals("in rank order", objectKeys, objects.map { it.key })
        assertEquals("the object bar's labels", objectLabels, objects.map { it.label })
        assertTrue("in the More row", objects.all { it.row == StripRow.MORE })
        assertTrue("40 dp each", objects.all { it.width.value == 40f })
        val settings = shown.indexOfFirst { it.key == ShapeStripItems.SETTINGS }
        assertTrue("after v1.8's More items", shown.indexOfFirst { it.key in objectKeys } > settings)
        assertNoLabelTwice(shown, "a new shape on a vector layer")

        // An open shape object too.
        tool.commit()
        val vec = c.doc.layers[1]
        assertTrue(tool.editShapeObject(vec, (vec.vector!!.objects.single() as VShape).id))
        val reopened = items(c, tool, barShown = barsShown(c))
        assertEquals(objectKeys, reopened.filter { it.key in objectKeys }.map { it.key })
        assertNoLabelTwice(reopened, "an open shape object")
    }

    @Test
    fun withObjectsSelectedUnderShapeOrASelectionNoLabelShowsTwice() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        val vec = c.doc.layers[1]
        rig.drawRect(c, tool)
        tool.commit()
        val id = (vec.vector!!.objects.single() as VShape).id
        // Objects selected (the object bar shows), then a new shape drawn under Shape.
        c.vectors.setSelection(vec, setOf(id))
        rig.drag(c, 160f to 120f, 190f to 150f, 220f to 180f)
        assertTrue(tool.hasPendingWork)
        assertTrue("the object bar shows", barsShown(c))
        val withObjectBar = items(c, tool, barShown = barsShown(c))
        assertNoObjectItems(withObjectBar, "objects selected")
        assertNoLabelTwice(withObjectBar, "objects selected")

        // A selection (the selection bar shows).
        c.vectors.setSelection(null, emptySet())
        c.setSelection(Selection.all(c.doc.width, c.doc.height), recordUndo = false)
        assertTrue("the shape stays open", tool.hasPendingWork)
        assertTrue("the selection bar shows", barsShown(c))
        val withSelectionBar = items(c, tool, barShown = barsShown(c))
        assertNoObjectItems(withSelectionBar, "a selection")
        assertNoLabelTwice(withSelectionBar, "a selection")

        // Neither: the items are back.
        c.setSelection(null, recordUndo = false)
        assertEquals(objectKeys, items(c, tool, barShown = barsShown(c)).filter { it.key in objectKeys }.map { it.key })
    }

    /**
     * v1.9 Gate 2: the paste bar alone (no selection, no selected objects, something copied) offers
     * Paste and Hide only, so the object items stay, although the editor passes
     * `selectionBarShown = true` for it.
     */
    @Test
    fun underThePasteBarAloneTheObjectItemsStay() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        c.selectLayer(0)
        c.setSelection(Selection.all(c.doc.width, c.doc.height), recordUndo = false)
        assertTrue("something copied", c.copySelection())
        c.setSelection(null, recordUndo = false)
        c.selectLayer(1)
        rig.drawRect(c, tool)
        assertTrue(c.clipboard != null && c.selection == null && c.vectors.selectedIds.isEmpty())
        assertTrue("the editor shows the paste bar", !barsShown(c) && !ShapeStripItems.objectBarUp(c))
        val underPasteBar = items(c, tool, barShown = true)
        assertEquals(objectKeys, underPasteBar.filter { it.key in objectKeys }.map { it.key })
        assertTrue("Paste is not an object item", underPasteBar.none { it.label == "Paste" })
        assertNoLabelTwice(underPasteBar, "the paste bar")

        // The same shown bar with a selection, then with objects selected: they go.
        c.setSelection(Selection.all(c.doc.width, c.doc.height), recordUndo = false)
        assertNoObjectItems(items(c, tool, barShown = true), "a selection with the paste bar's clipboard")
        c.setSelection(null, recordUndo = false)
        tool.commit()
        val vec = c.doc.layers[1]
        c.vectors.setSelection(vec, setOf((vec.vector!!.objects.single() as VShape).id))
        rig.drag(c, 160f to 120f, 190f to 150f, 220f to 180f)
        assertTrue(tool.hasPendingWork)
        assertNoObjectItems(items(c, tool, barShown = true), "objects selected with the paste bar's clipboard")
    }

    @Test
    fun pointsModeAndAShapeOfALayerOfItsOwnHaveNoObjectItems() {
        val c = rig.controller(vectorLayer = true)
        val tool = rig.shapeTool(c)
        rig.drawRect(c, tool)
        tool.setPointEditing(true)
        val points = items(c, tool, barShown = false)
        assertNoObjectItems(points, "Points mode")
        assertNoLabelTwice(points, "Points mode")

        val c2 = rig.controller()
        val tool2 = rig.shapeTool(c2)
        assertNoObjectItems(items(c2, tool2, barShown = false), "nothing open")
        rig.drawRect(c2, tool2)
        assertNoObjectItems(items(c2, tool2, barShown = false), "a new shape for a layer of its own")
        tool2.commit()
        rig.tap(c2, 30f, 60f)
        assertTrue(tool2.editingLayer != null)
        assertNoObjectItems(items(c2, tool2, barShown = false), "a reopened shape layer")
    }
}
