package com.brushwork.paint.v19.a5

import androidx.compose.foundation.layout.Box
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.brush.BrushStripItems
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.vector.VectorEraseChooser
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 (R11 a, A5 step 7): the Eraser's chooser on a vector layer and on a legacy shape layer, at
 * 360 dp. Row 1 holds v1.8's three mode buttons (one item, tagged) and "Brush size"; the More row,
 * under the selected mode's description (its hint), "Opacity", "Choose brush", "Pressure changes
 * size" and, last and in the warning tint, "Turn into pixels", which asks first: Cancel changes
 * nothing, "Erase pixels" turns the layer into pixels in ONE step (undo turns it back), and the
 * Eraser then has v1.8's raster row. The four tools' mode cell lists the modes with their
 * descriptions, a divider and "Turn into pixels".
 *
 * One test (sections): Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v19.a5.erasechoosersandbox"])
class EraseChooserRobolectricTest {

    private fun box() = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(60f, 60f, true), VAnchor(200f, 60f, true), VAnchor(200f, 180f, true), VAnchor(60f, 180f, true)), closed = true)),
        fill = VPaint.Solid(0xFF3070C0.toInt()),
    )

    private val shape = ShapeObject(ShapeType.ELLIPSE, cx = 150f, cy = 120f, w = 120f, h = 80f, style = ShapeStyle.STROKE_FILL, strokeWidth = 4f)

    /** Layer 2 of the editor's document becomes a vector layer (with a box), or a legacy shape layer is added; [tool]. */
    private fun objectsLayer(c: EditorController, kind: String, tool: ToolId = ToolId.ERASER) {
        val layer = c.doc.layers[1]
        when (kind) {
            "vector" -> {
                layer.vector = VectorContent.EMPTY
                c.selectLayer(layer)
                c.vectors.addObjects(layer, listOf(box()), "Add")
            }
            else -> {
                c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(shape), draw = ArraySources.shapeDraw(shape, ColorMode.RGB, 400, 300))!!
                    .also { c.selectLayer(it) }
            }
        }
        c.selectTool(tool)
    }

    private fun inRow1(n: SemanticsNode) = StripTestKit.under(n, V18Tags.STRIP_ROW1)

    private fun inMoreRow(n: SemanticsNode) = StripTestKit.under(n, V18Tags.STRIP_MORE_ROW)

    private fun warned(n: SemanticsNode?) = n?.config?.getOrNull(VectorEraseChooser.WarningTint) == true

    /** The content descriptions of [n] and of the nodes inside it. */
    private fun descriptions(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() + n.children.flatMap { descriptions(it) }

    private fun left(label: String): Float = Qa19Ui.find(label, exact = true)!!.bounds.left.toFloat()

    @Test
    fun theEraserChoosesItsModeAndTurnsTheLayerIntoPixels() = Qa19Ui.uiTest { h ->
        for (kind in listOf("vector", "shape")) h.section("the Eraser on a $kind layer at 360 dp") {
            val s = h.editor { objectsLayer(it, kind) }
            val c = s.c
            val layer = c.activeLayer
            VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
            Qa19Ui.settle()

            // Row 1: the three mode buttons (one item), then the size; the opacity is on the More row.
            val chooser = Qa19Ui.tagged(V19Tags.ERASE_CHOOSER) ?: throw AssertionError("no chooser; shown: ${Qa19Ui.shown()}")
            assertTrue(inRow1(chooser))
            for (m in VectorEraseMode.entries) {
                val b = Qa19Ui.tagged(V19Tags.ERASE_MODE_PREFIX + m.name)!!
                assertTrue(m.label, inRow1(b) && StripTestKit.under(b, V19Tags.ERASE_CHOOSER))
                assertEquals(m.label, listOf(m.label), descriptions(b))
            }
            assertTrue(Qa19Ui.has(BrushStripItems.BRUSH_SIZE, exact = true))
            assertTrue(inRow1(Qa19Ui.find(BrushStripItems.BRUSH_SIZE, exact = true)!!.node))
            assertFalse("the opacity left row 1", Qa19Ui.has(BrushStripItems.OPACITY_LABEL, exact = true))

            // A mode button picks the mode; its description is the More row's hint.
            Qa19Ui.click(VectorEraseMode.PARTIAL.label, exact = true)
            assertEquals(VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
            assertTrue(Qa19Ui.openMoreRow())
            assertTrue(Qa19Ui.has(EraseLabels19.PARTIAL_NOTE, exact = true))
            Qa19Ui.click(VectorEraseMode.TO_INTERSECTION.label, exact = true)
            assertTrue(Qa19Ui.has(VectorEraseMode.TO_INTERSECTION.note, exact = true))
            assertFalse(Qa19Ui.has(EraseLabels19.PARTIAL_NOTE, exact = true))

            // The More row: the opacity, the brush, the pressure, and last "Turn into pixels" in the warning tint.
            val more = listOf(BrushStripItems.OPACITY_LABEL, BrushStripItems.CHOOSE_BRUSH, BrushStripItems.pressureLabel(false), EraseLabels19.TURN_INTO_PIXELS)
            for (l in more) assertTrue(l, inMoreRow(Qa19Ui.find(l, exact = true)!!.node))
            assertEquals(more, more.sortedBy { left(it) })
            val pixels = Qa19Ui.tagged(V19Tags.ERASE_PIXELS)!!
            assertTrue(inMoreRow(pixels))
            assertTrue("the warning tint", warned(pixels))

            // "Turn into pixels" asks first; Cancel changes nothing.
            val steps = c.undoManager.undoCount
            val data = layer.dataSnapshot()
            Qa19Ui.click(EraseLabels19.TURN_INTO_PIXELS, exact = true)
            for (t in listOf(EraseLabels19.PIXELS_TITLE, EraseLabels19.PIXELS_BODY, EraseLabels19.PIXELS_CONFIRM, "Cancel")) {
                assertTrue("$t; shown: ${Qa19Ui.shown()}", Qa19Ui.has(t, exact = true))
            }
            Qa19Ui.click("Cancel", exact = true)
            assertFalse(Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
            assertEquals(steps, c.undoManager.undoCount)
            assertEquals(data, layer.dataSnapshot())

            // "Erase pixels": v1.8's rasterize step, ONE step; the Eraser has the raster row.
            Qa19Ui.click(EraseLabels19.TURN_INTO_PIXELS, exact = true)
            Qa19Ui.click(EraseLabels19.PIXELS_CONFIRM, exact = true)
            assertFalse(Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(if (kind == "vector") "Rasterize vector layer" else TransformTool.RASTERIZE_SHAPE_LABEL, c.undoManager.undoLabel)
            assertFalse(layer.isVectorLayer || layer.isShapeLayer)
            Qa19Ui.settle()
            assertTrue("the toast; shown: ${Qa19Ui.shown()}", Qa19Ui.shown().any { it.startsWith("\"${layer.name}\" is now a regular layer") })
            assertNull("the raster row", Qa19Ui.tagged(V19Tags.ERASE_CHOOSER))
            assertNull(Qa19Ui.tagged(V19Tags.ERASE_PIXELS))
            assertTrue(inRow1(Qa19Ui.find(BrushStripItems.OPACITY_LABEL, exact = true)!!.node))
            assertEquals(ToolId.ERASER, c.activeToolId)
            // Undo turns it back, and the chooser with it.
            c.undo()
            Qa19Ui.settle()
            assertEquals(data, layer.dataSnapshot())
            assertNotNull(Qa19Ui.tagged(V19Tags.ERASE_CHOOSER))
        }

        h.section("the raster layer and a painted mask keep v1.8's raster row") {
            val s = h.editor { it.selectTool(ToolId.ERASER) }
            assertNull(Qa19Ui.tagged(V19Tags.ERASE_CHOOSER))
            assertTrue(inRow1(Qa19Ui.find(BrushStripItems.OPACITY_LABEL, exact = true)!!.node))
            val ctx = StripContext(selectionBarShown = false, vectorMode = false)
            assertNull(VectorEraseChooser.item(ctx, s.c.activeLayer, VectorEraseMode.OBJECT) {})
            assertNull(VectorEraseChooser.pixelsItem(ctx, s.c.activeLayer) {})
            assertNull(VectorEraseChooser.modeCell(ctx, s.c.activeLayer, VectorEraseMode.OBJECT, {}, {}))
        }

        h.section("the four tools' mode cell") {
            val s = h.editor { objectsLayer(it, "vector", ToolId.CURVE) }
            val ctx = StripContext(selectionBarShown = false, vectorMode = true)
            var mode = VectorEraseMode.OBJECT
            var asked = 0
            val item = VectorEraseChooser.modeCell(ctx, s.c.activeLayer, mode, { mode = it }, { asked++ })!!
            assertEquals(56f, item.width.value)
            assertEquals(StripRow.ROW1, item.row)
            assertEquals("never leaves row 1", 0, item.overflowRank)
            assertEquals(EraseLabels19.MODE_CELL.format(VectorEraseMode.OBJECT.label), item.label)
            h.show { Box { item.content() } }
            // The face is the mode's icon; the cell is described by the mode.
            assertNotNull(Qa19Ui.tagged(V19Tags.ERASE_MODE_CELL))
            assertTrue(StripTestKit.under(Qa19Ui.find(item.label, exact = true)!!.node, V19Tags.ERASE_MODE_CELL))
            // Its menu: the modes with their descriptions, a divider, "Turn into pixels" in the warning tint.
            Qa19Ui.click(item.label, exact = true)
            for (m in VectorEraseMode.entries) {
                assertTrue(m.label, Qa19Ui.has(m.label, exact = true) && Qa19Ui.has(m.note, exact = true))
            }
            assertTrue(Qa19Ui.has(EraseLabels19.TURN_INTO_PIXELS, exact = true))
            assertTrue(warned(Qa19Ui.tagged(V19Tags.ERASE_PIXELS)))
            assertTrue("the actions come after the modes", left(EraseLabels19.TURN_INTO_PIXELS) >= 0f &&
                Qa19Ui.find(EraseLabels19.TURN_INTO_PIXELS, exact = true)!!.bounds.top >= Qa19Ui.find(VectorEraseMode.TO_INTERSECTION.note, exact = true)!!.bounds.bottom)
            Qa19Ui.click(VectorEraseMode.PARTIAL.label, exact = true)
            assertEquals(VectorEraseMode.PARTIAL, mode)
            assertFalse("the menu closed", Qa19Ui.has(VectorEraseMode.PARTIAL.note, exact = true))
            Qa19Ui.click(item.label, exact = true)
            Qa19Ui.click(EraseLabels19.TURN_INTO_PIXELS, exact = true)
            assertEquals(1, asked)
        }
    }
}
