package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.6 §3.4 (area G): increments in the shape tool. With Length 10 px, Angle 15° and Scale 10 %:
 * a new box gets its dragged size on multiples of 10 px, a moved shape and a dragged point move by
 * multiples of 10 px, a resize handle puts the dragged sides' sizes on multiples, the rotation
 * handle and a line's direction land on 15° steps (instead of the 15° option), a line's length on
 * 10 px, a pinch scales by 10 % steps. A guide (snap to objects) and the grid each beat the step
 * on their axis. Off, every gesture is the v1.5 one (I8). Zoom 1, density 1.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeIncrementsRobolectricTest {

    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    /** A 200 x 200 canvas whose bottom layer holds a filled box (150..190, 20..60); snapping off. */
    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", 200, 200)
        val art = Layer(doc.newLayerId(), "Art", BitmapUtils.createLayerBitmap(200, 200))
        Canvas(art.bitmap).drawRect(150f, 20f, 190f, 60f, Paint().apply { color = 0xFF000000.toInt() })
        art.markChanged()
        doc.layers += art
        doc.layers += Layer(doc.newLayerId(), "Layer 2", BitmapUtils.createLayerBitmap(200, 200))
        doc.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, doc, scope, AppSettings(ctx)).also {
            it.color = 0xFFFF0000.toInt()
            it.viewTransform.set(Matrix())
            it.tools
            it.snapping.enabled = false
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun shapeTool(c: EditorController, type: ShapeType = ShapeType.RECTANGLE): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        return (c.tools.getValue(ToolId.SHAPE) as ShapeTool).also {
            it.update { s -> s.copy(type = type, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, keepProportions = false, fromCenter = false, snapAngle = false) }
        }
    }

    private fun stepsOn(c: EditorController) = c.increments.update { it.copy(enabled = true, lengthPx = 10f, angleDeg = 15f, scalePercent = 10f) }

    private fun assertBox(expected: ShapeBox, actual: ShapeBox?, eps: Float = 1e-2f) {
        requireNotNull(actual)
        assertEquals("cx of $actual", expected.cx, actual.cx, eps)
        assertEquals("cy of $actual", expected.cy, actual.cy, eps)
        assertEquals("w of $actual", expected.w, actual.w, eps)
        assertEquals("h of $actual", expected.h, actual.h, eps)
        assertEquals("rotation of $actual", expected.rotationDeg, actual.rotationDeg, eps)
    }

    @Test
    fun newBoxesMovesAndResizesLandOnTheLengthStep() {
        val c = controller()
        val tool = shapeTool(c)
        stepsOn(c)
        // Dragged out 83 x 77: 80 x 80, from the first corner.
        c.pointerDown(ToolPoint(40f, 80f))
        c.pointerMove(ToolPoint(80f, 120f))
        c.pointerMove(ToolPoint(123f, 157f))
        assertEquals("80 × 80 px", c.increments.readout)
        c.pointerUp(ToolPoint(123f, 157f))
        assertNull(c.increments.readout)
        assertBox(ShapeBox(80f, 120f, 80f, 80f), tool.box)

        // Moved by (33, 7): (30, 10).
        c.pointerDown(ToolPoint(80f, 120f))
        c.pointerMove(ToolPoint(95f, 120f))
        c.pointerMove(ToolPoint(113f, 127f))
        assertEquals("+30 px, +10 px", c.increments.readout)
        c.pointerUp(ToolPoint(113f, 127f))
        assertBox(ShapeBox(110f, 130f, 80f, 80f), tool.box)

        // The bottom-right handle (150, 170) dragged by (13, 6): 93 x 86 → 90 x 90, the top-left corner stays.
        c.drag(150f to 170f, 156f to 172f, 163f to 176f)
        assertBox(ShapeBox(115f, 135f, 90f, 90f), tool.box)
        // The right side (160, 135) dragged by 4: 94 → 90 (no change), by 7: 97 → 100; the height stays.
        c.drag(160f to 135f, 167f to 135f)
        assertBox(ShapeBox(120f, 135f, 100f, 90f), tool.box)
    }

    @Test
    fun rotationsLinesAndPinchesStep() {
        val c = controller()
        val tool = shapeTool(c)
        c.drag(40f to 80f, 120f to 160f)
        assertBox(ShapeBox(80f, 120f, 80f, 80f), tool.box)
        stepsOn(c)
        // The rotation handle (34 px above the top edge) turned by 20°: 15°.
        val r = 74.0
        fun at(deg: Double) = (80f + (r * sin(Math.toRadians(deg))).toFloat()) to (120f - (r * cos(Math.toRadians(deg))).toFloat())
        c.drag(80f to 46f, at(10.0), at(20.0))
        assertEquals(15f, tool.box!!.rotationDeg, 1e-3f)
        c.drag(at(15.0), at(40.0), at(52.0))
        assertEquals(45f, tool.box!!.rotationDeg, 1e-3f)

        // A pinch: ×1.07 → ×1.1 from its start, a 20° turn → 60° (45 + 20 = 65 → 60).
        val b = tool.box!!
        assertTrue(tool.onTwoFingerStart(b.center, b.center, Vec2(190f, 190f)))
        tool.onTwoFingerGesture(Vec2.ZERO, 1.07f, 20f)
        assertEquals("110 % · 60°", c.increments.readout)
        tool.onTwoFingerEnd(cancelled = false)
        assertNull(c.increments.readout)
        assertEquals(88f, tool.box!!.w, 1e-3f)
        assertEquals(60f, tool.box!!.rotationDeg, 1e-3f)
        tool.commit()

        // A line dragged to (+67, +20): 16.6° → 15°, its length (69.9 on that direction) → 70.
        val line = shapeTool(c, ShapeType.LINE)
        c.drag(40f to 40f, 70f to 50f, 107f to 60f)
        val l = line.box!!
        assertEquals(15f, l.rotationDeg, 1e-3f)
        assertEquals(70f, l.w, 1e-3f)
        assertEquals(40f, l.start.x, 1e-3f)
        assertEquals(40f, l.start.y, 1e-3f)
    }

    @Test
    fun pointsMoveByTheStep() {
        val c = controller()
        val tool = shapeTool(c)
        c.drag(40f to 80f, 120f to 160f)
        tool.setPointEditing(true)
        stepsOn(c)
        val before = tool.docAnchors()!!
        val i = before.indexOfFirst { it.pos.x == 120f && it.pos.y == 80f }
        assertTrue("a corner at (120, 80): $before", i >= 0)
        c.drag(120f to 80f, 126f to 80f, 133f to 84f)
        val p = tool.docAnchors()!![i].pos
        assertEquals(130f, p.x, 1e-3f)
        assertEquals(80f, p.y, 1e-3f)
    }

    @Test
    fun aGuideAndTheGridEachBeatTheStepOnTheirAxis() {
        val c = controller()
        val tool = shapeTool(c)
        c.drag(40f to 80f, 120f to 160f)
        stepsOn(c)
        c.snapping.enabled = true
        // Moved by (27, 7): the right edge would be at 147, 3 px from the art's left edge (150):
        // the guide decides X (+30); Y takes the step (+10).
        c.drag(80f to 120f, 95f to 120f, 107f to 127f)
        assertBox(ShapeBox(110f, 130f, 80f, 80f), tool.box)
        // Grid snapping on (every 25 px), snapping to objects off: the grid decides both axes
        // (the box's top-left corner on the grid), not the step.
        c.snapping.enabled = false
        c.updateGrid(GridSettings(enabled = true, spacingPx = 25f, snap = true))
        c.drag(110f to 130f, 120f to 130f, 133f to 136f)
        // (The step alone would have given (130, 140).)
        assertBox(ShapeBox(140f, 140f, 80f, 80f), tool.box)
    }

    @Test
    fun withIncrementsOffTheGesturesAreTheV15Ones() {
        val c = controller()
        val tool = shapeTool(c)
        c.drag(40f to 80f, 80f to 120f, 123f to 157f)
        assertBox(ShapeBox(81.5f, 118.5f, 83f, 77f), tool.box)
        c.drag(81.5f to 118.5f, 95f to 118.5f, 114.5f to 125.5f)
        assertBox(ShapeBox(114.5f, 125.5f, 83f, 77f), tool.box)
        assertNull(c.increments.readout)
    }
}
