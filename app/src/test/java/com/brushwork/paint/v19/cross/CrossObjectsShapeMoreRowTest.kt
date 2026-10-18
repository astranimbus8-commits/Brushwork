package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.vector.ShapeStripInfo
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.v19.a9.A9Rig
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 Gate 2 cross test X3 (design 2.4.11 and 3.4; A9 x A4; R14, R19): the Shape More row's
 * "Duplicate", "Bring forward" and "Send backward" on a reopened shape object of a joined layer
 * ([ShapeTool.duplicateOpen], [ShapeTool.arrangeOpen], through A4's [ObjectActions] bodies) do
 * exactly what the vector object bar's buttons do for the same object selected: ONE step each
 * with the bar's label, the same objects (data, ids, order) and the same pixels; undo gives back
 * the same layer in both. Only what is left open differs, as designed: the Shape tool reopens
 * the copy (or the shape) with no object selection, the bar keeps the result selected.
 */
@RunWith(RobolectricTestRunner::class)
class CrossObjectsShapeMoreRowTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private val blue = 0xFF3070C0.toInt()
    private val green = 0xFF20A040.toInt()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle(c: EditorController) {
        Snapshot.sendApplyNotifications()
        c.vectors.flushPending()
        repeat(400) {
            idle()
            if (!c.vectors.isRendering) return
            Thread.sleep(5)
        }
        throw AssertionError("the layer is still rendering")
    }

    private fun pixels(b: Bitmap): List<Int> = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.toList()

    /** What [layer] holds and shows. */
    private data class State(val vector: VectorContent?, val pixels: List<Int>)

    private fun state(c: EditorController, layer: Layer): State {
        settle(c)
        return State(layer.vector, pixels(layer.bitmap))
    }

    /**
     * A joined layer (S1), active, under the Shape tool: the rectangle (30, 30) - (110, 90) filled
     * blue, then the ellipse (60, 50) - (140, 110) over it filled green, drawn as the user draws them.
     */
    private fun joined(): Triple<EditorController, ShapeTool, Layer> {
        val c = rig.controller()
        c.snapping.enabled = false
        val tool = rig.shapeTool(c)
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN, useBrushSize = false,
                strokeWidth = 4f, fillColor = blue, editable = true, newLayerEachTime = false,
            )
        }
        rig.drag(c, 30f to 30f, 70f to 60f, 110f to 90f)
        tool.commit()
        tool.update { it.copy(type = ShapeType.ELLIPSE, fillColor = green) }
        // (Dragged from outside the rectangle: a press on it would reopen it.)
        rig.drag(c, 140f to 110f, 100f to 80f, 60f to 50f)
        tool.commit()
        val layer = c.activeLayer
        assertEquals("S1: one vector layer of two shapes", listOf(true, true), layer.vector!!.objects.map { it is VShape })
        settle(c)
        return Triple(c, tool, layer)
    }

    /** The Shape More row's items with no bar shown. */
    private fun moreRowKeys(c: EditorController, tool: ShapeTool): List<String> =
        ShapeStripItems.items(tool, c, ShapeStripInfo.of(tool, c.doc.dpi), {}, {}, {}, {}, barShown = false).map { it.key }

    @Test
    fun theShapeMoreRowsObjectItemsDoExactlyWhatTheObjectBarDoes() {
        // Twin documents: A through the Shape More row, B through the object bar.
        val (a, tool, la) = joined()
        val (b, _, lb) = joined()
        // (Each shape drawn keeps its stroke session's random seed: B takes A's objects as they are.)
        b.vectors.update(lb, la.vector!!, "Twin")
        val start = state(a, la)
        assertEquals("twins", start, state(b, lb))
        val (rectId, ellipseId) = la.vector!!.objects.map { it.id }

        /**
         * [shapeRow] on A's rectangle reopened under Shape, [bar] on B's rectangle selected: ONE
         * step each, both labelled [label]; the same layer afterwards. Returns it.
         */
        fun same(what: String, label: String, keyShown: String, shapeRow: () -> Boolean, bar: () -> Boolean): State {
            assertTrue("$what: the rectangle reopens", tool.editShapeObject(la, rectId))
            assertTrue("$what: the items apply", tool.objectActionsApply)
            assertTrue("$what: in the More row", keyShown in moreRowKeys(a, tool))
            val stepsA = a.undoManager.undoCount
            assertTrue("$what: the More row's item", shapeRow())
            assertEquals("$what: ONE step from the More row", stepsA + 1, a.undoManager.undoCount)
            assertEquals(what, label, a.undoManager.undoLabel)
            assertTrue("$what: the result is open under Shape", tool.editingObject)
            assertTrue("$what: no object selection left by the More row", a.vectors.selectedIds.isEmpty())
            // Closed untouched: no step.
            tool.commit()
            assertEquals("$what: closing it is no step", stepsA + 1, a.undoManager.undoCount)

            b.vectors.setSelection(lb, setOf(rectId))
            val stepsB = b.undoManager.undoCount
            assertTrue("$what: the object bar's button", bar())
            assertEquals("$what: ONE step from the bar", stepsB + 1, b.undoManager.undoCount)
            assertEquals(what, label, b.undoManager.undoLabel)
            b.vectors.setSelection(null, emptySet())

            val viaShape = state(a, la)
            assertEquals("$what: the same objects, ids and order, and the same pixels", state(b, lb), viaShape)
            return viaShape
        }

        // Duplicate: the copy right above the rectangle (under the ellipse), one offset right and down.
        val duplicated = same(
            "Duplicate", ObjectActions.DUPLICATE_LABEL, StripKeysV19.DUPLICATE.key,
            { tool.duplicateOpen() }, { ObjectActions.duplicate(b) },
        )
        val objects = duplicated.vector!!.objects
        assertEquals("the copy right above the rectangle", listOf(rectId, ellipseId), listOf(objects[0].id, objects[2].id))
        val d = ObjectActions.duplicateOffset(a)
        val copy = (objects[1] as VShape).shape
        val rect = (objects[0] as VShape).shape
        assertEquals(rect.cx + d, copy.cx, 0.01f)
        assertEquals(rect.cy + d, copy.cy, 0.01f)
        a.undo()
        b.undo()
        assertEquals("undo of Duplicate: A as it was", start, state(a, la))
        assertEquals("undo of Duplicate: B as it was", start, state(b, lb))

        // Bring forward: the rectangle above the ellipse it overlaps.
        val forward = same(
            "Bring forward", ObjectEdits.Arrange.FORWARD.label, StripKeysV19.FORWARD.key,
            { tool.arrangeOpen(ObjectEdits.Arrange.FORWARD) }, { ObjectActions.arrange(b, ObjectEdits.Arrange.FORWARD) },
        )
        assertEquals(listOf(ellipseId, rectId), forward.vector!!.objects.map { it.id })
        assertTrue("the pixels show the new order", forward.pixels != start.pixels)

        // Send backward: below it again.
        val backward = same(
            "Send backward", ObjectEdits.Arrange.BACKWARD.label, StripKeysV19.BACKWARD.key,
            { tool.arrangeOpen(ObjectEdits.Arrange.BACKWARD) }, { ObjectActions.arrange(b, ObjectEdits.Arrange.BACKWARD) },
        )
        assertEquals(listOf(rectId, ellipseId), backward.vector!!.objects.map { it.id })
        assertEquals("the pixels as at the start", start.pixels, backward.pixels)

        // Undo both arrangements in both: the joined layer as it was, and the same.
        repeat(2) { a.undo(); b.undo() }
        assertEquals(start, state(a, la))
        assertEquals(start, state(b, lb))
    }
}
