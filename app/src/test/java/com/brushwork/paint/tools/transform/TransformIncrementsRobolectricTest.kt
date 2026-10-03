package com.brushwork.paint.tools.transform

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
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
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.6 §3.4 / §6.4 checklist 4 (area G): with increments on, the Transform tool's handles step.
 * A corner (keeping the aspect ratio) scales to 110 % and then 120 % of the original; a side to
 * multiples of 10 % on its axis (mirrored too); the rotation handle turns in 15° steps; a pinch
 * steps both, keeping small turns; a distort corner moves by 10 px steps. Every stepped gesture
 * shows a readout that goes when it ends, and a tap changes nothing. Snapping to objects is off,
 * so only the steps act (their precedence: IncrementPrecedenceRobolectricTest).
 *
 * 400 x 300 canvas, zoom 1, density 1; a 100 x 60 block at (100, 100).
 */
@RunWith(RobolectricTestRunner::class)
class TransformIncrementsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(): Pair<EditorController, TransformTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        Canvas(doc.layers[0].bitmap).drawRect(Rect(100, 100, 200, 160), Paint().apply { color = RED })
        doc.layers[0].markChanged()
        c.selectTool(ToolId.TRANSFORM)
        shadowOf(Looper.getMainLooper()).idle()
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        t.snapToObjects = false
        assertEquals(DocBox(100f, 100f, 200f, 160f), t.transformState!!.bounds())
        c.increments.update { it.copy(enabled = true, lengthPx = 10f, scalePercent = 10f, angleDeg = 15f) }
        return c to t
    }

    private fun bounds(t: TransformTool) = t.transformState!!.bounds()

    private fun assertBox(expected: DocBox, actual: DocBox) {
        assertEquals("left of $actual", expected.left, actual.left, 1e-2f)
        assertEquals("top of $actual", expected.top, actual.top, 1e-2f)
        assertEquals("right of $actual", expected.right, actual.right, 1e-2f)
        assertEquals("bottom of $actual", expected.bottom, actual.bottom, 1e-2f)
    }

    @Test
    fun cornersAndSidesScaleToMultiplesOfTheStepOfTheOriginalSize() {
        val (c, t) = setup()
        assertTrue(t.keepAspect)
        // The bottom-right corner to (207, 164): 106.9 % raw, 110 % stepped.
        c.pointerDown(ToolPoint(200f, 160f))
        c.pointerMove(ToolPoint(204f, 162f))
        c.pointerMove(ToolPoint(207f, 164f))
        assertBox(DocBox(100f, 100f, 210f, 166f), bounds(t))
        assertEquals("110 %", c.increments.readout)
        c.pointerUp(ToolPoint(207f, 164f))
        assertNull(c.increments.readout)
        assertEquals(110f, t.transformState!!.scalePercent, 1e-3f)
        // Again from there: 119.3 % raw, 120 % of the ORIGINAL (not 110 % × 110 %).
        c.pointerDown(ToolPoint(210f, 166f))
        c.pointerMove(ToolPoint(215f, 170f))
        c.pointerMove(ToolPoint(219f, 172f))
        c.pointerUp(ToolPoint(219f, 172f))
        assertEquals(120f, t.transformState!!.scalePercent, 1e-3f)
        assertBox(DocBox(100f, 100f, 220f, 172f), bounds(t))

        // The right side to x = 213: 113 % of the width → 110 %; the height stays.
        t.reset()
        c.pointerDown(ToolPoint(200f, 130f))
        c.pointerMove(ToolPoint(207f, 130f))
        c.pointerMove(ToolPoint(213f, 130f))
        assertEquals("110 × 100 %", c.increments.readout)
        c.pointerUp(ToolPoint(213f, 130f))
        assertBox(DocBox(100f, 100f, 210f, 160f), bounds(t))
        // Dragged past the left side: mirrored at −125 % raw → 130 %, still mirrored.
        t.reset()
        c.pointerDown(ToolPoint(200f, 130f))
        c.pointerMove(ToolPoint(100f, 130f))
        c.pointerMove(ToolPoint(-25f, 130f))
        c.pointerUp(ToolPoint(-25f, 130f))
        assertEquals(-1.3f, t.transformState!!.sx, 1e-4f)
        assertBox(DocBox(-30f, 100f, 100f, 160f), bounds(t))

        // A tap on a corner changes nothing.
        t.reset()
        c.pointerDown(ToolPoint(200f, 160f))
        c.pointerUp(ToolPoint(200f, 160f))
        assertBox(DocBox(100f, 100f, 200f, 160f), bounds(t))
        assertFalse(c.canUndo)
    }

    @Test
    fun theRotationHandleTurnsInStepsOfTheAngleIncrement() {
        val (c, t) = setup()
        val st = t.transformState!!
        val handle = HandleLayout.compute(st, { it }, 1f).rotateHandle
        val pivot = st.center()
        val r = handle.distanceTo(pivot)
        fun at(deg: Double) = Vec2(pivot.x + (r * sin(Math.toRadians(deg))).toFloat(), pivot.y - (r * cos(Math.toRadians(deg))).toFloat())
        c.pointerDown(ToolPoint(handle.x, handle.y))
        c.pointerMove(at(10.0).let { ToolPoint(it.x, it.y) })
        c.pointerMove(at(20.0).let { ToolPoint(it.x, it.y) })
        assertEquals(15f, t.transformState!!.rotationDeg, 1e-3f)
        assertEquals("15°", c.increments.readout)
        c.pointerMove(at(52.0).let { ToolPoint(it.x, it.y) })
        assertEquals(45f, t.transformState!!.rotationDeg, 1e-3f)
        c.pointerUp(at(52.0).let { ToolPoint(it.x, it.y) })
        assertEquals(45f, t.transformState!!.rotationDeg, 1e-3f)
        assertNull(c.increments.readout)

        // Off again: the v1.5 handle (free, 45° detents 2° wide).
        c.increments.update { it.copy(enabled = false) }
        t.reset()
        c.pointerDown(ToolPoint(handle.x, handle.y))
        c.pointerMove(at(10.0).let { ToolPoint(it.x, it.y) })
        c.pointerMove(at(20.0).let { ToolPoint(it.x, it.y) })
        c.pointerUp(at(20.0).let { ToolPoint(it.x, it.y) })
        assertEquals(20f, t.transformState!!.rotationDeg, 0.05f)
    }

    @Test
    fun aPinchStepsItsScaleAndAngle() {
        val (c, t) = setup()
        assertTrue(t.onTwoFingerStart(Vec2(150f, 130f), Vec2(150f, 130f), Vec2(260f, 130f)))
        t.onTwoFingerGesture(Vec2.ZERO, 1.07f, 2f)
        assertEquals(110f, t.transformState!!.scalePercent, 1e-3f)
        assertEquals("small turns keep the angle", 0f, t.transformState!!.rotationDeg, 0f)
        assertEquals("110 % · 0°", c.increments.readout)
        t.onTwoFingerGesture(Vec2.ZERO, 1.16f, 20f)
        assertEquals(120f, t.transformState!!.scalePercent, 1e-3f)
        assertEquals(15f, t.transformState!!.rotationDeg, 1e-3f)
        t.onTwoFingerEnd(cancelled = false)
        assertNull(c.increments.readout)
        assertEquals(120f, t.transformState!!.scalePercent, 1e-3f)
    }

    @Test
    fun aDistortCornerMovesInLengthSteps() {
        val (c, t) = setup()
        t.mode = TransformTool.Mode.DISTORT
        c.pointerDown(ToolPoint(200f, 160f))
        c.pointerMove(ToolPoint(206f, 162f))
        c.pointerMove(ToolPoint(213f, 164f))
        assertEquals("+10 px, 0 px", c.increments.readout)
        c.pointerUp(ToolPoint(213f, 164f))
        val q = t.transformState!!.corner(2)
        assertEquals(210f, q.x, 1e-2f)
        assertEquals(160f, q.y, 1e-2f)
        assertEquals(100f, t.transformState!!.corner(0).x, 1e-2f)
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
