package com.brushwork.paint.tools.mask

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskComponent
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.snap.Increments
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
import kotlin.math.IEEErem
import kotlin.math.abs
import kotlin.math.atan2

/**
 * v1.6 §3.4c, area A's hooks: the Masks tool's handles with increments. Off (the default, I8):
 * every handle drag, creating drag and pinch is exactly what it was in v1.5. On: pins move by
 * multiples of the Length step, a linear ramp's width and a radial's radii land on Length
 * multiples, rotation knobs turn to Angle multiples, the feather follows its own custom step
 * ("mask.feather", in %), and the readout says what the gesture is at.
 */
@RunWith(RobolectricTestRunner::class)
class MaskHandlesIncrementsRobolectricTest {
    private val w = 400
    private val h = 300
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    private fun freshIncrements(on: Boolean): Increments {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        return Increments(settings).also { inc -> if (on) inc.update { it.copy(enabled = true) } }
    }

    private val linear = LinearMask(1, x0 = 100f, y0 = 150f, x1 = 147f, y1 = 163f)
    private val radial = RadialMask(2, cx = 200f, cy = 150f, rx = 83f, ry = 41f, rotationDeg = 7f, feather = 0.37f)
    private val brush = BrushMask(3)

    private val moves = listOf(Vec2(13.3f, -7.9f), Vec2(-27.6f, 4.2f), Vec2(0.4f, 31.7f), Vec2(-3f, -3f))

    private fun startOf(c: MaskComponent, k: MaskHandles.Kind): Vec2 {
        val t = com.brushwork.paint.engine.ViewTransform().also { it.set(Matrix()) }
        return MaskHandles.position(c, k, t) ?: Vec2(150f, 150f)
    }

    @Test
    fun offEveryHandleDragIsExactlyV15() {
        val off = freshIncrements(on = false)
        for (comp in listOf(linear, radial)) {
            for (k in MaskHandles.Kind.entries) {
                val from = startOf(comp, k)
                for (d in moves) {
                    val to = from + d
                    assertEquals("$k of ${comp::class.simpleName} by $d", MaskHandles.dragged(comp, k, from, to), MaskHandles.dragged(comp, k, from, to, off))
                    assertNull(MaskHandles.readout(comp, k, comp, from, to, off))
                }
            }
        }
        val a = Vec2(10f, 20f); val b = Vec2(57.3f, 61.9f)
        assertEquals(b, MaskHandles.steppedLinearEnd(a, b, off))
        assertEquals(23.7f, MaskHandles.steppedRadius(23.7f, off), 0f)
        val p = MaskHandles.steppedPinch(radial, Vec2(3.3f, 4.4f), 1.234f, 17.7f, off)
        assertEquals(MaskHandles.Pinch(Vec2(3.3f, 4.4f), 1.234f, 17.7f), p)
        assertEquals(0.37f, MaskHandles.steppedFeather(0.37f, off), 0f)
    }

    private fun assertMultiple(what: String, v: Float, step: Float, tol: Float = 1e-3f) {
        val r = v.toDouble().IEEErem(step.toDouble())
        assertTrue("$what = $v is not a multiple of $step", abs(r) < tol)
    }

    @Test
    fun onPinsMoveByLengthStepsFromWhereTheDragBegan() {
        val on = freshIncrements(on = true)
        for (comp in listOf<MaskComponent>(linear, radial)) {
            val from = startOf(comp, MaskHandles.Kind.PIN)
            for (d in moves) {
                val r = MaskHandles.dragged(comp, MaskHandles.Kind.PIN, from, from + d, on)
                val (x0, y0) = com.brushwork.paint.masks.MaskGeometry.pin(comp)!!
                val (x1, y1) = com.brushwork.paint.masks.MaskGeometry.pin(r)!!
                assertMultiple("dx", x1 - x0, 10f, 1e-2f)
                assertMultiple("dy", y1 - y0, 10f, 1e-2f)
            }
        }
        assertEquals(Vec2(10f, -10f), MaskHandles.pinDelta(Vec2(0f, 0f), Vec2(13.3f, -7.9f), on))
        assertEquals("X +10 px  Y −10 px", MaskHandles.readout(radial, MaskHandles.Kind.PIN, radial, Vec2(0f, 0f), Vec2(13.3f, -7.9f), on))
        // A brush part's pin too.
        val moved = MaskHandles.dragged(brush, MaskHandles.Kind.PIN, Vec2(5f, 5f), Vec2(18f, 5f), on)
        assertEquals(brush.id, moved.id)
    }

    @Test
    fun onLinearWidthAndRadialRadiiLandOnLengthMultiples() {
        val on = freshIncrements(on = true)
        for (k in listOf(MaskHandles.Kind.LINEAR_START, MaskHandles.Kind.LINEAR_END)) {
            val from = startOf(linear, k)
            for (d in moves) {
                val r = MaskHandles.dragged(linear, k, from, from + d, on) as LinearMask
                val width = Vec2(r.x1 - r.x0, r.y1 - r.y0).length
                assertMultiple("$k width", width, 10f, 2e-2f)
                // The ramp keeps its direction.
                val a0 = atan2(linear.y1 - linear.y0, linear.x1 - linear.x0)
                val a1 = atan2(r.y1 - r.y0, r.x1 - r.x0)
                assertEquals(a0, a1, 1e-4f)
                assertTrue(MaskHandles.readout(linear, k, r, from, from + d, on)!!.startsWith("Width "))
            }
        }
        for (k in listOf(MaskHandles.Kind.RX_POS, MaskHandles.Kind.RX_NEG, MaskHandles.Kind.RY_POS, MaskHandles.Kind.RY_NEG)) {
            val from = startOf(radial, k)
            for (d in moves) {
                val r = MaskHandles.dragged(radial, k, from, from + d, on) as RadialMask
                assertMultiple("$k rx", if (k == MaskHandles.Kind.RX_POS || k == MaskHandles.Kind.RX_NEG) r.rx else r.ry, 10f)
            }
        }
        // A radius dragged to nothing stays at one step.
        val from = startOf(radial, MaskHandles.Kind.RX_POS)
        val tiny = MaskHandles.dragged(radial, MaskHandles.Kind.RX_POS, from, from - Vec2(200f, 0f).let { Vec2(it.x * kotlin.math.cos(Math.toRadians(7.0)).toFloat(), it.x * kotlin.math.sin(Math.toRadians(7.0)).toFloat()) }, on) as RadialMask
        assertEquals(10f, tiny.rx, 1e-3f)
    }

    @Test
    fun onRotationKnobsTurnToAngleMultiples() {
        val on = freshIncrements(on = true)
        val fromR = startOf(radial, MaskHandles.Kind.ROTATE)
        for (d in moves) {
            val r = MaskHandles.dragged(radial, MaskHandles.Kind.ROTATE, fromR, fromR + d * 3f, on) as RadialMask
            assertMultiple("radial rotation", r.rotationDeg, 15f)
            assertTrue(MaskHandles.readout(radial, MaskHandles.Kind.ROTATE, r, fromR, fromR + d * 3f, on)!!.endsWith("°"))
        }
        val fromL = startOf(linear, MaskHandles.Kind.LINEAR_ROTATE)
        for (d in moves) {
            val r = MaskHandles.dragged(linear, MaskHandles.Kind.LINEAR_ROTATE, fromL, fromL + d * 3f, on) as LinearMask
            val deg = Math.toDegrees(atan2(r.y1 - r.y0, r.x1 - r.x0).toDouble()).toFloat()
            assertMultiple("linear direction", deg, 15f, 2e-2f)
            // Turned around its centre, same width.
            assertEquals((linear.x0 + linear.x1) / 2f, (r.x0 + r.x1) / 2f, 1e-3f)
            assertEquals(Vec2(linear.x1 - linear.x0, linear.y1 - linear.y0).length, Vec2(r.x1 - r.x0, r.y1 - r.y0).length, 1e-3f)
        }
    }

    @Test
    fun theFeatherFollowsItsOwnCustomStep() {
        val on = freshIncrements(on = true)
        val from = startOf(radial, MaskHandles.Kind.FEATHER)
        val to = from + Vec2(-9f, 6f)
        // No custom step for the feather: unchanged even with increments on.
        val plain = MaskHandles.dragged(radial, MaskHandles.Kind.FEATHER, from, to) as RadialMask
        assertEquals(plain, MaskHandles.dragged(radial, MaskHandles.Kind.FEATHER, from, to, on))
        on.update { it.withCustom(MaskHandles.FEATHER_KEY, 10f) }
        val r = MaskHandles.dragged(radial, MaskHandles.Kind.FEATHER, from, to, on) as RadialMask
        assertMultiple("feather %", r.feather * 100f, 10f, 1e-2f)
        assertEquals("Feather ${MaskHandles.number(r.feather * 100f)} %", MaskHandles.readout(radial, MaskHandles.Kind.FEATHER, r, from, to, on))
        // 0 and 100 % stay reachable.
        assertEquals(1f, MaskHandles.steppedFeather(0.99f, on), 1e-6f)
        assertEquals(0f, MaskHandles.steppedFeather(0.004f, on), 1e-6f)
    }

    @Test
    fun onCreatingDragsAndPinchesStep() {
        val on = freshIncrements(on = true)
        val a = Vec2(10f, 20f)
        val e = MaskHandles.steppedLinearEnd(a, Vec2(57.3f, 61.9f), on)
        assertMultiple("created width", (e - a).length, 10f, 2e-2f)
        assertMultiple("created direction", Math.toDegrees(atan2(e.y - a.y, e.x - a.x).toDouble()).toFloat(), 15f, 2e-2f)
        assertEquals(30f, MaskHandles.steppedRadius(27.3f, on), 0f)
        assertEquals(10f, MaskHandles.steppedRadius(0.5f, on), 0f)
        val p = MaskHandles.steppedPinch(radial, Vec2(13f, -4f), 1.234f, 17.7f, on)
        assertEquals(Vec2(10f, 0f), p.translation)
        assertEquals(1.2f, p.scale, 1e-5f)
        assertMultiple("pinched rotation", radial.rotationDeg + p.rotationDeg, 15f)
        assertEquals("120 %  30°", MaskHandles.pinchReadout(radial, p, on))
        // A brush part has no angle of its own: its turn is stepped.
        val pb = MaskHandles.steppedPinch(brush, Vec2(0f, 0f), 1f, 22f, on)
        assertEquals(15f, pb.rotationDeg, 1e-4f)
    }

    // ------------------------------------------------------------------ through the tool

    private fun setup(on: Boolean): Layer {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("i", "i", w, h)
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(20f, 20f, 380f, 280f, Paint().apply { color = 0xFF3366AA.toInt() })
        }
        doc.activeLayerIndex = 0
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        if (on) c.increments.update { it.copy(enabled = true) }
        c.selectTool(ToolId.MASK)
        val tone = FilterRegistry.byId("adjust.tone")!!
        val spec = MaskSpec(components = listOf(radial.copy(id = 1, rotationDeg = 0f)), nextId = 2)
        return c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), spec)!!
    }

    private val tool get() = c.tools.getValue(ToolId.MASK) as MaskTool

    private fun dragHandle(from: Vec2, to: Vec2, check: () -> Unit = {}) {
        c.pointerDown(ToolPoint(from.x, from.y))
        for (i in 1..10) c.pointerMove(ToolPoint(from.x + (to.x - from.x) * i / 10, from.y + (to.y - from.y) * i / 10))
        check()
        c.pointerUp(ToolPoint(to.x, to.y))
    }

    @Test
    fun aRadiusDragThroughTheToolLandsOnAStepAndShowsTheReadout() {
        val adj = setup(on = true)
        tool.select(1L)
        val r0 = adj.maskSpec!!.components[0] as RadialMask
        val from = Vec2(r0.cx + r0.rx, r0.cy)
        dragHandle(from, from + Vec2(23.4f, 3f)) {
            assertEquals("Radius X 110 px", c.increments.readout)
        }
        assertNull("the readout goes with the gesture", c.increments.readout)
        val r1 = adj.maskSpec!!.components[0] as RadialMask
        assertEquals(110f, r1.rx, 1e-3f)
        assertEquals("Edit mask", c.undoManager.undoLabel)
    }

    @Test
    fun theSameDragWithIncrementsOffIsV15() {
        val adj = setup(on = false)
        tool.select(1L)
        val r0 = adj.maskSpec!!.components[0] as RadialMask
        val from = Vec2(r0.cx + r0.rx, r0.cy)
        dragHandle(from, from + Vec2(23.4f, 3f)) { assertNull(c.increments.readout) }
        val r1 = adj.maskSpec!!.components[0] as RadialMask
        assertEquals(MaskHandles.dragged(r0, MaskHandles.Kind.RX_POS, from, from + Vec2(23.4f, 3f)), r1)
        assertEquals(r0.rx + 23.4f, r1.rx, 1e-3f)
    }

    @Test
    fun aSteppedPinMoveThroughTheTool() {
        val adj = setup(on = true)
        tool.select(1L)
        val r0 = adj.maskSpec!!.components[0] as RadialMask
        dragHandle(Vec2(r0.cx, r0.cy), Vec2(r0.cx + 26f, r0.cy - 14f)) {
            assertEquals("X +30 px  Y −10 px", c.increments.readout)
        }
        val r1 = adj.maskSpec!!.components[0] as RadialMask
        assertEquals(r0.cx + 30f, r1.cx, 1e-3f)
        assertEquals(r0.cy - 10f, r1.cy, 1e-3f)
        assertEquals("Move mask", c.undoManager.undoLabel)
    }
}
