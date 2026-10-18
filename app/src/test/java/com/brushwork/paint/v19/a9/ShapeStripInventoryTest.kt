package com.brushwork.paint.v19.a9

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.ShapeStripInfo
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A9 (R11 with R18 and R14, design 3.9 rules 1 to 5): the Shape strip's worst case while
 * erasing keeps the strip inventory. Erasing the objects of a vector or a legacy shape layer adds
 * the Eraser's mode cell to row 1 (56 dp, rank 0) and moves "Points" to the front of the More
 * row; the fullest state is then Points mode on a shape with mirror copies, a smooth point with
 * handles of its own and a corner selected, "Bevel points" last. Its More row stays within
 * [StripLayout.MAX_MORE_LINES] lines at 360 dp with and without the VECTOR cell and at 392 dp,
 * the mode cell in row 1 at each, and [StripInventory.rules] holds; so does erasing outside Points
 * mode (the box's "Center" and "Proportional" back). While erasing, "Editable", "Turn into path"
 * and the object items are hidden.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeStripInventoryTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private val ctx = StripContext(selectionBarShown = false, vectorMode = false)
    private val mode = StripKeysV19.ERASE_MODE_CELL.key

    /** The Shape strip's spec as `shapeStrip` builds it, the mode cell included. */
    private fun spec(c: EditorController, tool: ShapeTool) = StripSpec(
        ShapeStripItems.items(
            tool, c, ShapeStripInfo.of(tool, c.doc.dpi), {}, {}, {}, {}, barShown = false,
            modeCell = ShapeStripItems.modeCell(tool, c, ctx) {},
        ),
    )

    /** The rules, and at 360 and 392 dp, with or without the VECTOR cell: the mode cell in row 1, three More lines at most. */
    private fun keepsTheInventory(spec: StripSpec, what: String) {
        assertEquals("$what: the inventory's rules", emptyList<String>(), StripInventory.rules(spec))
        for (screen in listOf(360f, 392f)) for (chip in listOf(false, true)) {
            val available = StripInventory.content(screen) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f
            val s = StripLayout.split(spec, available)
            val where = "$what at ${screen.toInt()} dp${if (chip) " with the VECTOR cell" else ""}"
            assertTrue("$where: the mode cell in row 1: ${s.row1.map { it.key }}", s.row1.any { it.key == mode })
            val lines = StripLayout.moreLines(s, spec.hint, StripInventory.content(screen))
            assertTrue("$where: ${lines.size} More lines ${lines.map { l -> l.map { it.key } }}", lines.size <= StripLayout.MAX_MORE_LINES)
            for (line in lines) {
                assertTrue("$where: a More line within the row", StripLayout.rowWidth(line) <= StripInventory.content(screen) + 0.01f)
            }
        }
    }

    /** A legacy shape layer (a rectangle) above "Layer 1", selected. */
    private fun shapeLayer(c: EditorController): Layer {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 160f, w = 60f, h = 40f, style = ShapeStyle.STROKE, strokeWidth = 4f)
        val layer = requireNotNull(
            c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, c.doc.width, c.doc.height)),
        )
        c.selectLayer(layer)
        return layer
    }

    private fun fullestWhileErasing(vectorLayer: Boolean) {
        val what = if (vectorLayer) "a vector layer" else "a shape layer"
        val c = rig.controller(vectorLayer)
        c.snapping.enabled = false
        if (!vectorLayer) assertTrue(shapeLayer(c).isShapeLayer)
        val tool = rig.shapeTool(c)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        tool.setErase(true)
        rig.drawRect(c, tool)
        assertNotNull("$what: erasing live", tool.eraseSession)
        assertTrue("$what: the erase outline has copies", tool.hasCopies)

        // Outside Points mode: "Points" leads the More row, "Center" and "Proportional" show.
        val box = spec(c, tool)
        assertEquals(ShapeStripItems.POINTS, box.items.first { it.row == StripRow.MORE }.key)
        for (k in listOf(ShapeStripItems.CENTER, ShapeStripItems.PROPORTIONAL, ShapeStripItems.REMOVE_COPIES)) {
            assertTrue("$what, the box: $k", box.items.any { it.key == k })
        }
        keepsTheInventory(box, "$what, erasing with a box")

        // The fullest Points mode.
        tool.setPointEditing(true)
        assertTrue("$what: Points mode while erasing", tool.pointsMode)
        tool.selectPoints(PointSelection.of(tool.pointCount, 0))
        tool.setSelectedSmooth(true)
        tool.scaleHandles(1.5f)
        tool.selectPoints(PointSelection.of(tool.pointCount, 0, 1))
        assertTrue(tool.selectionHasExplicitHandles)
        assertNotNull("$what: still erasing live", tool.eraseSession)
        val s = spec(c, tool)
        val keys = s.items.map { it.key }
        for (k in listOf(
            mode, ShapeStripItems.POINTS, ShapeStripItems.STROKE_WITH, ShapeStripItems.SELECT_ALL, ShapeStripItems.SMOOTH,
            ShapeStripItems.AUTO_TANGENT, ShapeStripItems.DELETE_POINT, ShapeStripItems.DESELECT_POINT, ShapeStripItems.ROUNDNESS,
            ShapeStripItems.RESET_ROUNDNESS, ShapeStripItems.HANDLES, ShapeStripItems.RESET_SHAPE, ShapeStripItems.REMOVE_COPIES,
            StripKeysV19.BEVEL.key,
        )) assertTrue("$what, Points mode: $k in $keys", k in keys)
        for (k in listOf(
            ShapeStripItems.EDITABLE, ShapeStripItems.TO_PATH, ShapeStripItems.CENTER, ShapeStripItems.PROPORTIONAL,
            StripKeysV19.DUPLICATE.key, StripKeysV19.FORWARD.key, StripKeysV19.BACKWARD.key,
        )) assertFalse("$what, Points mode while erasing: no $k", k in keys)
        assertEquals("$what: the bevel last", StripKeysV19.BEVEL.key, keys.last())
        assertEquals("$what: row 1", listOf(ShapeStripItems.TYPE, ShapeStripItems.STYLE, ShapeStripItems.WIDTH, mode), s.items.filter { it.row == StripRow.ROW1 }.map { it.key })
        keepsTheInventory(s, "$what, the fullest Points mode while erasing")

        // Painting again: no cell, "Points" back in row 1.
        tool.setErase(false)
        val painting = spec(c, tool)
        assertFalse(painting.items.any { it.key == mode })
        if (tool.box != null) assertEquals(StripRow.ROW1, painting.items.single { it.key == ShapeStripItems.POINTS }.row)
        c.updateSymmetry(SymmetrySettings())
    }

    @Test
    fun theFullestEraseOnAVectorLayerKeepsThreeMoreLines() = fullestWhileErasing(vectorLayer = true)

    @Test
    fun theFullestEraseOnAShapeLayerKeepsThreeMoreLines() = fullestWhileErasing(vectorLayer = false)
}
