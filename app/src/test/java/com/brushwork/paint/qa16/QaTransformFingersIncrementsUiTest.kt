package com.brushwork.paint.qa16

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.HandleLayout
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.6 final QA (increments everywhere: Transform move / scale / rotate / pinch) with real fingers
 * on the real editor at the user's phone size, a 120 × 100 px block on the raster layer, snapping
 * off. Increments on (Length 25 px, Scale 25 %, Angle 30°): a move of (+37, +12) px lands on
 * (+25, 0) with the move in the info chip; the bottom-right corner (+33, +20) gives 125 % of the
 * original; the rotation handle turned 40° gives 30°; a pinch spreading the fingers × 1.45 gives
 * 175 % and keeps the 30°; ✓ is one undo step. Increments off: the same move and turn are free
 * (v1.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.transformfingerssandbox"])
class QaTransformFingersIncrementsUiTest {

    private fun startTransform(s: ChromeScreen): TransformTool {
        val c = s.c
        val layer = c.doc.layers[c.doc.activeLayerIndex]
        c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(100f, 80f, 220f, 180f, Paint().apply { color = 0xFF2266CC.toInt() }) }
        settle()
        QaCurves.tool(s, "Transform")
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val t = c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { settle(1); t.transformState != null })
        settle()
        QaCurves.snapOff(c)
        val b = t.transformState!!.bounds()
        assertEquals(100f, b.left, 0.01f); assertEquals(80f, b.top, 0.01f); assertEquals(220f, b.right, 0.01f); assertEquals(180f, b.bottom, 0.01f)
        return t
    }

    private fun toScreen(s: ChromeScreen, p: Vec2): Vec2 = s.screen(p.x, p.y).let { Vec2(it.first, it.second) }

    /** One finger in window px from [a] to [b], [held] checked before it lifts. */
    private fun fingerScreen(s: ChromeScreen, a: Vec2, b: Vec2, held: () -> Unit = {}) {
        s.touch.idle(400)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, a.x, a.y))
        for (i in 1..12) {
            s.touch.idle(16)
            s.touch.send(MotionEvent.ACTION_MOVE, P(0, a.x + (b.x - a.x) * i / 12f, a.y + (b.y - a.y) * i / 12f))
        }
        s.touch.idle(16)
        settle(2)
        held()
        s.touch.send(MotionEvent.ACTION_UP, P(0, b.x, b.y))
        s.touch.idle(50)
        settle()
    }

    /** The rotation handle (window px) turned [deg] about the box center. */
    private fun turn(s: ChromeScreen, t: TransformTool, deg: Double, held: () -> Unit = {}) {
        val st = t.transformState!!
        val handle = HandleLayout.compute(st, { toScreen(s, it) }, s.density).rotateHandle
        val pivot = toScreen(s, st.center())
        val r = handle.distanceTo(pivot)
        val a0 = atan2((handle.y - pivot.y).toDouble(), (handle.x - pivot.x).toDouble())
        val a1 = a0 + Math.toRadians(deg)
        // Along the arc (a straight chord would pass near the center).
        s.touch.idle(400)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, handle.x, handle.y))
        for (i in 1..12) {
            s.touch.idle(16)
            val a = a0 + (a1 - a0) * i / 12.0
            s.touch.send(MotionEvent.ACTION_MOVE, P(0, pivot.x + (r * cos(a)).toFloat(), pivot.y + (r * sin(a)).toFloat()))
        }
        s.touch.idle(16)
        settle(2)
        held()
        val end = Vec2(pivot.x + (r * cos(a1)).toFloat(), pivot.y + (r * sin(a1)).toFloat())
        s.touch.send(MotionEvent.ACTION_UP, P(0, end.x, end.y))
        s.touch.idle(50)
        settle()
    }

    @Test
    fun transformGesturesStepWithIncrementsOn() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("increments on (Length 25, Scale 25, Angle 30): move, corner, rotation, pinch, ✓") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25", scale = "25", angle = "30")
            val t = startTransform(s)
            assertTrue(t.keepAspect)
            val steps = c.undoManager.undoCount

            // Move: (+37, +12) → (+25, 0).
            var held: String? = null
            QaCurves.drag(s, Vec2(130f, 150f), Vec2(37f, 12f)) { held = c.increments.readout }
            var b = t.transformState!!.bounds()
            assertEquals("x: one 25 px step", 125f, b.left, 0.01f)
            assertEquals("y: no step", 80f, b.top, 0.01f)
            assertEquals("the info chip", "+25 px, 0 px", held)
            assertNull(c.increments.readout)

            // The bottom-right corner (+33, +20): 125 % of the original.
            held = null
            QaCurves.drag(s, Vec2(245f, 180f), Vec2(33f, 20f)) { held = c.increments.readout }
            assertEquals("125 %", 125f, t.transformState!!.scalePercent, 1e-2f)
            assertEquals("125 %", held)
            b = t.transformState!!.bounds()
            assertEquals("the top-left corner stays", 125f, b.left, 0.01f)
            assertEquals(80f, b.top, 0.01f)
            assertEquals(150f, b.right - b.left, 0.02f)
            assertEquals(125f, b.bottom - b.top, 0.02f)

            // The rotation handle turned 40°: 30°.
            held = null
            turn(s, t, 40.0) { held = c.increments.readout }
            assertEquals("30°", 30f, t.transformState!!.rotationDeg, 1e-2f)
            assertEquals("30°", held)

            // A pinch on the block spreading × 1.45: 175 % of the original (125 % × 1.45 = 181 %); the angle stays.
            val m = toScreen(s, t.transformState!!.center())
            val a0 = m.x - 90f to m.y
            val b0 = m.x + 90f to m.y
            s.touch.idle(400)
            s.touch.pinch(a0, b0, m.x - 90f * 1.45f to m.y, m.x + 90f * 1.45f to m.y)
            settle()
            assertEquals("175 %", 175f, t.transformState!!.scalePercent, 1e-2f)
            assertEquals("the angle stays", 30f, t.transformState!!.rotationDeg, 1e-2f)
            assertEquals("all of it pending", steps, c.undoManager.undoCount)
            QaCurves.shot(s, "transform-fingers")

            click("Apply transform edit")
            assertTrue(Smoke.pumpUntil { settle(1); !t.hasPendingWork && c.busyMessage == null })
            assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
            Smoke.assertQuiet(c, "on")
        }
        h.section("increments off: the same move and turn are free (v1.5)") {
            val s = h.editor()
            QaCurves.incrementsOff()
            val t = startTransform(s)
            QaCurves.drag(s, Vec2(130f, 150f), Vec2(37f, 12f))
            val b = t.transformState!!.bounds()
            assertEquals(137f, b.left, 0.05f)
            assertEquals(92f, b.top, 0.05f)
            turn(s, t, 40.0)
            assertEquals("free", 40f, t.transformState!!.rotationDeg, 0.3f)
            Smoke.assertQuiet(s.c, "off")
        }
        dog.interrupt()
        h.finish()
    }
}
