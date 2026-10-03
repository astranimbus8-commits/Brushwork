package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
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
import org.robolectric.RuntimeEnvironment
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.6 §3.4(c) increments of the Text tool (area C's hooks): with increments on, a drag moves the
 * text by Length steps from where it started (per axis, where no guide holds it), the size handle
 * and the pinch scale by Scale steps of the size at the start, the rotation handle and the pinch
 * turn to Angle steps (replacing the 45° detents), a box side lands on Length multiples, and the
 * readout shows the stepped value while the finger is down. A guide beats the step. Off, every
 * gesture is exactly v1.5's (I8); typed values are never stepped.
 */
@RunWith(RobolectricTestRunner::class)
class TextIncrementsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** A 600 x 400 canvas at zoom 1; "Layer 1" has content at (100, 60)-(160, 120). */
    private fun setup(on: Boolean): Pair<EditorController, TextTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 600, 400)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(600, 400)) }
        doc.activeLayerIndex = 1
        Canvas(doc.layers[0].bitmap).drawRect(Rect(100, 60, 160, 120), Paint().apply { color = 0xFF000000.toInt() })
        doc.layers[0].markChanged()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        if (on) c.increments.update { it.copy(enabled = true) }
        return c to (c.tools.getValue(ToolId.TEXT) as TextTool)
    }

    private fun text(tool: TextTool, x: Float, y: Float, s: String = "Hello there") {
        tool.startTextAt(x, y)
        tool.setText(s)
        tool.setSizePx(30f)
        tool.confirmEditor()
    }

    private fun drag(c: EditorController, from: Vec2, vararg to: Vec2) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (p in to) c.pointerMove(ToolPoint(p.x, p.y))
    }

    @Test
    fun offAMoveFollowsTheFingerExactly() {
        val (c, tool) = setup(on = false)
        text(tool, 300f, 200f)
        drag(c, Vec2(300f, 200f), Vec2(313.7f, 207.3f))
        assertEquals(300f + (313.7f - 300f), tool.item!!.cx, 0f)
        assertEquals(200f + (207.3f - 200f), tool.item!!.cy, 0f)
        assertNull("no readout while increments are off", c.increments.readout)
        c.pointerUp(ToolPoint(313.7f, 207.3f))
    }

    @Test
    fun aMoveStepsByTheLengthStepAndShowsTheReadout() {
        val (c, tool) = setup(on = true)
        text(tool, 300f, 200f)
        drag(c, Vec2(300f, 200f), Vec2(313.7f, 207.3f))
        assertEquals(310f, tool.item!!.cx, 1e-4f)
        assertEquals(210f, tool.item!!.cy, 1e-4f)
        assertEquals("+10 px, +10 px", c.increments.readout)
        c.pointerMove(ToolPoint(286f, 196f))
        assertEquals(290f, tool.item!!.cx, 1e-4f)
        assertEquals(200f, tool.item!!.cy, 1e-4f)
        assertEquals("-10 px, 0 px", c.increments.readout)
        c.pointerUp(ToolPoint(286f, 196f))
        assertNull("cleared when the finger lifts", c.increments.readout)
        assertEquals(290f, tool.item!!.cx, 1e-4f)
    }

    @Test
    fun aGuideBeatsTheStep() {
        val (c, tool) = setup(on = true)
        c.snapping.enabled = true
        text(tool, 300f, 300f)
        val block = tool.blockFor(tool.item!!)
        // The left side 3 px right of the layer's right edge (160): X snaps there, Y steps.
        val target = 163f + block.width / 2f
        drag(c, Vec2(300f, 300f), Vec2(290f, 304f), Vec2(target, 303f))
        assertEquals("the guide holds X", 160f + block.width / 2f, tool.item!!.cx, 1e-3f)
        assertEquals("Y moves by a step", 300f, tool.item!!.cy, 1e-4f)
        c.pointerUp(ToolPoint(target, 303f))
    }

    /** The rotation handle of the pending text (its stem above the box: 6 dp pad, 30 dp stem). */
    private fun rotateHandle(tool: TextTool): Vec2 {
        val t = tool.item!!
        val b = tool.blockFor(t)
        return Vec2(t.cx, t.cy - b.height / 2f - 6f - 30f)
    }

    private fun turnBy(c: EditorController, tool: TextTool, deg: Float) {
        val center = Vec2(tool.item!!.cx, tool.item!!.cy)
        val h = rotateHandle(tool)
        val r = center.y - h.y
        val a = Math.toRadians(deg.toDouble())
        drag(c, h, Vec2(center.x + (r * sin(a)).toFloat(), center.y - (r * cos(a)).toFloat()))
    }

    @Test
    fun theRotationHandleTurnsInAngleSteps() {
        val (c, tool) = setup(on = true)
        text(tool, 300f, 200f)
        turnBy(c, tool, 37f)
        assertEquals(30f, tool.item!!.rotationDeg, 1e-3f)
        assertEquals("30°", c.increments.readout)
        c.pointerUp(ToolPoint(0f, 0f))
        // Off: the v1.5 soft detents (37° stays 37°, 44° snaps to 45°).
        val (c2, tool2) = setup(on = false)
        text(tool2, 300f, 200f)
        turnBy(c2, tool2, 37f)
        assertEquals(37f, tool2.item!!.rotationDeg, 0.05f)
        c2.pointerUp(ToolPoint(0f, 0f))
    }

    /** The size handle: the bottom-right corner of the padded box. */
    private fun sizeHandle(tool: TextTool): Vec2 {
        val t = tool.item!!
        val b = tool.blockFor(t)
        return Vec2(t.cx + b.width / 2f + 6f, t.cy + b.height / 2f + 6f)
    }

    @Test
    fun theSizeHandleScalesInScaleSteps() {
        for (on in listOf(true, false)) {
            val (c, tool) = setup(on)
            text(tool, 300f, 200f)
            val center = Vec2(300f, 200f)
            val h = sizeHandle(tool)
            drag(c, h, center + (h - center) * 1.2f, center + (h - center) * 1.37f)
            val expected = if (on) 30f * 1.4f else 30f * 1.37f
            assertEquals("increments $on", expected, tool.item!!.spec.sizePx, 0.05f)
            if (on) assertEquals("140 %", c.increments.readout)
            c.pointerUp(ToolPoint(0f, 0f))
        }
    }

    @Test
    fun aBoxSideLandsOnLengthMultiples() {
        val (c, tool) = setup(on = true)
        text(tool, 300f, 200f)
        tool.setFixedBox(true)
        tool.setBoxLength(203f)
        val t = tool.item!!
        val b = tool.blockFor(t)
        val edge = Vec2(t.cx + b.width / 2f + 6f, t.cy)
        drag(c, edge, edge + Vec2(14f, 0f), edge + Vec2(23.4f, 0f))
        val w = tool.item!!.spec.box.width
        assertEquals("$w is on a 10 px step", 0f, w % 10f, 1e-3f)
        assertTrue(w > 203f)
        c.pointerUp(ToolPoint(0f, 0f))
        // Typed values are never stepped.
        tool.setBoxLength(203f)
        assertEquals(203f, tool.item!!.spec.box.width, 0f)
    }

    @Test
    fun aPinchStepsScaleAngleAndMove() {
        val (c, tool) = setup(on = true)
        text(tool, 300f, 200f, "A long line of text")
        val center = Vec2(300f, 200f)
        assertTrue(c.twoFingerStart(center, center - Vec2(40f, 0f), center + Vec2(40f, 0f)))
        c.twoFingerGesture(Vec2(13f, 26f), 1.37f, 17f)
        val it = tool.item!!
        assertEquals(42f, it.spec.sizePx, 0.01f)
        assertEquals(15f, it.rotationDeg, 1e-3f)
        assertEquals(310f, it.cx, 0.01f)
        assertEquals(230f, it.cy, 0.01f)
        assertTrue(c.increments.readout!!.contains("140 %") && c.increments.readout!!.contains("15°"))
        c.twoFingerEnd(false)
        assertNull(c.increments.readout)
    }

    @Test
    fun typedValuesAreNeverStepped() {
        val (_, tool) = setup(on = true)
        text(tool, 300f, 200f)
        tool.setRotation(37f)
        tool.setSizePx(33.3f)
        tool.setCenterX(123.4f)
        assertEquals(37f, tool.item!!.rotationDeg, 0f)
        assertEquals(33.3f, tool.item!!.spec.sizePx, 0f)
        assertEquals(123.4f, tool.item!!.cx, 1e-4f)
    }
}
