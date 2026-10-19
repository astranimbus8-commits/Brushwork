package com.brushwork.paint.qa19

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.brush.BrushStripItems
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 (R11 a, b; A5 step 9; I27): the Eraser on a vector layer and on a legacy shape layer at
 * 360 dp, with a finger. Row 1 holds the three modes and the size; a "Partial" swipe through a
 * filled box cuts it into two editable filled paths in ONE "Erase" step (the shape layer becomes
 * a vector layer in the same step); an "Object" swipe then takes one piece away whole; undo
 * brings back what was there. At 392 dp the row is the same, and at font scales 1.3 and 2.0, at
 * 360 and at 392 dp, the strip, its More row and the pixels dialog keep their texts inside their
 * boxes.
 *
 * One test (sections): Compose's frame clock only serves the first test of a Robolectric sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.erasemodessandbox"])
class Qa19EraseModesUiTest {
    private val blue = 0xFF3070C0.toInt()

    /** The Eraser (12 px) on a vector layer holding a filled, stroked box (120..280 × 100..200), or on a legacy shape layer of the same rectangle. */
    private fun objectsLayer(c: EditorController, kind: String) {
        if (kind == "vector") {
            val layer = c.doc.layers[1]
            layer.vector = VectorContent.EMPTY
            c.selectLayer(layer)
            val box = VPath(
                0,
                subpaths = listOf(VSubpath(listOf(VAnchor(120f, 100f, true), VAnchor(280f, 100f, true), VAnchor(280f, 200f, true), VAnchor(120f, 200f, true)), closed = true)),
                fill = VPaint.Solid(blue), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
            )
            c.vectors.addObjects(layer, listOf(box), "Add")
        } else {
            val o = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 160f, h = 100f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, fillColor = blue)
            val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, c.doc.width, c.doc.height))!!
            c.selectLayer(shape)
        }
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
    }

    /** A finger down the document's column [x], from above the box to below it. */
    private fun swipeDown(s: ChromeScreen, x: Float) {
        s.touch.stroke(s.screen(x, 60f), s.screen(x, 150f), s.screen(x, 240f))
        Qa19Ui.settle()
    }

    private fun inRow1(label: String) = StripTestKit.under(Qa19Ui.find(label, exact = true)!!.node, V18Tags.STRIP_ROW1)

    /** Row 1: the chooser and the size, no opacity; the More row: the opacity and, last, the pixels action. */
    private fun assertTheStrip(where: String) {
        val chooser = Qa19Ui.tagged(V19Tags.ERASE_CHOOSER) ?: throw AssertionError("$where: no chooser; shown: ${Qa19Ui.shown()}")
        assertTrue(where, StripTestKit.under(chooser, V18Tags.STRIP_ROW1))
        assertTrue(where, inRow1(BrushStripItems.BRUSH_SIZE))
        assertFalse("$where: the opacity is on the More row", Qa19Ui.has(BrushStripItems.OPACITY_LABEL, exact = true))
        assertTrue(Qa19Ui.openMoreRow())
        for (l in listOf(BrushStripItems.OPACITY_LABEL, EraseLabels19.TURN_INTO_PIXELS)) {
            assertTrue("$where: $l", StripTestKit.under(Qa19Ui.find(l, exact = true)!!.node, V18Tags.STRIP_MORE_ROW))
        }
        assertTrue(Qa19Ui.find(EraseLabels19.TURN_INTO_PIXELS, exact = true)!!.bounds.left > Qa19Ui.find(BrushStripItems.OPACITY_LABEL, exact = true)!!.bounds.left)
    }

    @Test
    fun theEraserCutsAndRemovesObjectsWithAFinger() = Qa19Ui.uiTest { h ->
        for (kind in listOf("vector", "shape")) h.section("the Eraser on a $kind layer at 360 dp") {
            val s = h.editor { objectsLayer(it, kind) }
            val c = s.c
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
            val layer = c.activeLayer
            val data = layer.dataSnapshot()
            val steps = c.undoManager.undoCount
            assertNotNull(Qa19Ui.tagged(V19Tags.ERASE_CHOOSER))

            // Partial: the box falls into two filled paths, in one step.
            Qa19Ui.click(VectorEraseMode.PARTIAL.label, exact = true)
            assertEquals(VectorEraseMode.PARTIAL, VectorEraserModes.mode(c))
            swipeDown(s, 200f)
            assertEquals("$kind: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
            assertTrue("$kind: a vector layer now", layer.isVectorLayer && !layer.isShapeLayer)
            val pieces = layer.vector!!.objects
            assertEquals("$kind: two pieces", 2, pieces.size)
            assertTrue(pieces.all { it is VPath && it.fill == VPaint.Solid(blue) })

            // Object: the left piece goes whole.
            Qa19Ui.click(VectorEraseMode.OBJECT.label, exact = true)
            swipeDown(s, 150f)
            assertEquals(steps + 2, c.undoManager.undoCount)
            assertEquals(1, layer.vector!!.objects.size)

            // Undo, twice: the box (or the shape layer) as it was.
            c.undo()
            c.undo()
            Qa19Ui.settle()
            assertEquals(data, layer.dataSnapshot())
            assertNotNull(Qa19Ui.tagged(V19Tags.ERASE_CHOOSER))
            assertTheStrip("$kind at 360 dp")
            Qa19Ui.assertNoTextOverflow("on a $kind layer at 360 dp, the More row open")
        }

        h.section("the Eraser on a vector layer at 392 dp") {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
            try {
                val s = h.editor { objectsLayer(it, "vector") }
                assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
                assertTheStrip("at 392 dp")
                Qa19Ui.assertNoTextOverflow("at 392 dp, the More row open")
            } finally {
                RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
            }
        }

        val widths = listOf(360 to Qa19Ui.Q360, 392 to Qa19Ui.Q392)
        for ((dp, q) in widths) for (scale in Qa19Ui.FONT_SCALES) for (kind in listOf("vector", "shape")) h.section("a $kind layer at font scale $scale at $dp dp") {
            val where = "$kind layer, $dp dp at font scale $scale"
            RuntimeEnvironment.setQualifiers(q)
            try {
                fontScaleChecks(h, where, dp, scale, kind)
            } finally {
                RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
            }
        }
    }

    /** At font scale [scale] on a phone [dp] wide: the strip, its More row and the pixels dialog keep their texts inside their boxes. */
    private fun fontScaleChecks(h: Qa19Harness, where: String, dp: Int, scale: Float, kind: String) {
        val s = h.editor(fontScale = scale) { objectsLayer(it, kind) }
        assertEquals("$where: the phone's width", dp.toFloat(), s.widthDp, 1f)
        Qa19Ui.click(VectorEraseMode.PARTIAL.label, exact = true)
        assertTheStrip(where)
        assertTrue(Qa19Ui.has(EraseLabels19.PARTIAL_NOTE, exact = true))
        // Gate 2: every text, v1.8's readouts, the brush chip and the Partial hint too (they shrink,
        // FitText); the pixels action and the dialog in full.
        Qa19Ui.assertNoTextOverflow("$where, the More row open")
        Qa19Ui.click(EraseLabels19.TURN_INTO_PIXELS, exact = true)
        assertTrue(Qa19Ui.has(EraseLabels19.PIXELS_BODY, exact = true))
        Qa19Ui.assertNoTextOverflow("$where, the pixels dialog")
        Qa19Ui.click("Cancel", exact = true)
        assertFalse(Qa19Ui.has(EraseLabels19.PIXELS_TITLE, exact = true))
    }
}
