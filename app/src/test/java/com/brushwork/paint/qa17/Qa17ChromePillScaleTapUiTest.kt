package com.brushwork.paint.qa17

import android.graphics.Paint
import android.view.MotionEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.editor.HistoryTapFingers
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.SEED
import com.brushwork.paint.ui.editor.HistoryTapFingers.Companion.seed
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 items 9 and 10 (design §3.9, §3.10) together on the REAL tools, on the 392 dp phone: the
 * first finger drags the pill's "Scale X" (1 % per dp, the phone's 8 dp touch slop) and a second
 * finger lands on the top row before the 12 dp tap slop: a two-finger tap. What the drag scaled is
 * put back, then exactly ONE undo runs, as the hotbar's Undo would have from before the drag
 * (the fake-tool version is HistoryOverUiRobolectricTest's, the Path tool's width slider
 * HistoryTapsArrayAndPathUiTest's):
 * - **Shape:** a placed rectangle opened again by a finger, untouched: it is untouched again, so
 *   the one undo is the document's last step ("Seed"); the rectangle is as it was placed.
 * - **Transform** on the same shape layer, lifted and untouched: likewise ("Seed" undone, the
 *   shape layer as it was). Before the fix the lift kept the drag's scale, so the one undo
 *   discarded the lift and "Seed" stayed.
 * - **Transform, the canvas finger first:** it moves the lifted box 6 dp, then a finger lands on
 *   the top row: the canvas drops its move and the tap does not bring it back; "Seed" is undone.
 * One UI test, own sandbox; one fresh editor per section.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromepillscaletapsandbox"])
class Qa17ChromePillScaleTapUiTest {

    @Test
    fun scaleXDraggedThenASecondFinger() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("Shape: Scale X dragged on a shape opened again, then a second finger") { shape(h) }
        h.section("Transform: Scale X dragged on an untouched lift, then a second finger") { transform(h) }
        h.section("Transform: a canvas finger moves the lift, then a second finger on the UI") { transformCanvasFirst(h) }
        dog.interrupt()
        h.finish()
    }

    private fun scaleX(): String? = SmokeUi.find(PillLabels.SCALE_X, exact = true)?.stateDescription

    /** The first finger drags "Scale X" 10 dp, [whileDragging] checks it, then a second finger lands on the top row. */
    private fun dragScaleXThenTap(f: HistoryTapFingers, whileDragging: () -> Unit) = with(f) {
        phoneTouchSlop()
        dragThenSecondFinger(control(PillLabels.SCALE_X).at(0.5f), region(ChromeTags.TOP_ROW).at(0.5f, 0.9f), whileDragging)
    }

    /** A filled rectangle layer (one step "Add shape") and the [SEED] step after it; the layer. */
    private fun shapeLayer(c: EditorController): Layer {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 120f, h = 80f, style = ShapeStyle.FILL, fillColor = 0xFF2244CC.toInt())
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
            cv.drawRect(140f, 110f, 260f, 190f, Paint().apply { color = 0xFF2244CC.toInt() })
        }!!
        settle()
        // The step the tap must undo (on the bottom layer; the shape layer stays).
        seed(c)
        return layer
    }

    /** One document step undone ("Seed"), the shape layer as it was. */
    private fun assertOneUndo(f: HistoryTapFingers, c: EditorController, steps: Int, layer: Layer) {
        assertEquals("exactly one document step undone", steps - 1, f.steps)
        assertTrue("the feedback: ${SmokeUi.shown().take(60)}", f.saw("Undo: $SEED"))
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); c.busyMessage == null })
        val kept = ShapeCodec.decode(layer.shapeData) ?: throw AssertionError("no longer a shape")
        assertEquals("the shape as it was", listOf(200f, 150f, 120f, 80f), listOf(kept.cx, kept.cy, kept.w, kept.h))
    }

    private fun shape(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val ui = Qa16Ui(s)
        val layer = shapeLayer(c)
        ui.tool("Shape")
        val tool = c.currentTool as ShapeTool
        ui.tap(140f, 150f)
        assertTrue("the shape is open", Smoke.pumpUntil(10_000) { settle(1); tool.box != null })
        val w0 = tool.box!!.w
        assertFalse("nothing changed yet", tool.hasUserChanges)
        assertEquals("100 %", scaleX())
        val steps = f.steps
        assertEquals(SEED, c.undoManager.undoLabel)

        dragScaleXThenTap(f) {
            assertNotEquals("the finger scaled the shape", w0, tool.box?.w)
            assertTrue(tool.hasUserChanges)
        }
        assertOneUndo(f, c, steps, layer)
        Smoke.assertQuiet(c, "shape")
    }

    private fun transform(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val ui = Qa16Ui(s)
        val layer = shapeLayer(c)
        ui.tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        assertEquals(TransformTool.Lifted.SHAPE, tt.lifted)
        assertFalse("nothing changed yet", tt.hasUserChanges)
        assertEquals("100 %", scaleX())
        val steps = f.steps
        assertEquals(SEED, c.undoManager.undoLabel)

        dragScaleXThenTap(f) {
            assertTrue("the finger scaled the lift", tt.hasUserChanges)
        }
        assertOneUndo(f, c, steps, layer)
        Smoke.assertQuiet(c, "transform")
    }

    /**
     * The first finger moves the lifted box on the canvas (6 dp, under the tap slop), then a
     * second finger lands on the top row: the canvas drops its move, and the tap must not bring
     * it back (the mark is where the canvas gesture started).
     */
    private fun transformCanvasFirst(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val f = HistoryTapFingers(s)
        val ui = Qa16Ui(s)
        val layer = shapeLayer(c)
        ui.tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        assertFalse("nothing changed yet", tt.hasUserChanges)
        val steps = f.steps
        val st0 = tt.transformState
        val (x, y) = s.screen(200f, 150f)
        val other = with(f) { px(region(ChromeTags.TOP_ROW).at(0.5f, 0.9f)) }
        val t = s.touch
        t.idle(400)
        t.send(MotionEvent.ACTION_DOWN, P(0, x, y))
        for (i in 1..3) {
            t.idle(16)
            t.send(MotionEvent.ACTION_MOVE, P(0, x + 2f * i * s.density, y))
        }
        settle(2, 10)
        assertNotEquals("the canvas finger moved the box", st0, tt.transformState)
        val moved = x + 6f * s.density
        t.send(MotionEvent.ACTION_POINTER_DOWN, P(0, moved, y), P(1, other.first, other.second), index = 1)
        t.idle(40)
        t.send(MotionEvent.ACTION_POINTER_UP, P(0, moved, y), P(1, other.first, other.second), index = 0)
        t.idle(20)
        t.send(MotionEvent.ACTION_UP, P(1, other.first, other.second))
        settle()
        assertOneUndo(f, c, steps, layer)
        Smoke.assertQuiet(c, "transform, canvas finger first")
    }
}
