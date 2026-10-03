package com.brushwork.paint.qa16

import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.Smoke.P
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.6 final QA (increments everywhere: the X / Y pill on the Transform tool) with real fingers on
 * the real editor at the user's phone size. A 120 × 100 px block on the raster layer is lifted by
 * the Transform tool (the pill places its Center at (160, 130)), snapping off. With "#" on (set
 * through More › Increments…, Length 25 px): a drag of the X number lands the centre on a multiple
 * of 25 (absolute, not a delta), the Y number too, "Fine" (the finger far below the cell) moves a
 * tenth as fast and stays on the steps, a typed X is kept exactly; the block keeps its size and
 * follows the centre; everything is part of the pending transform (✓ is one undo step). With "#"
 * off the same drag follows the finger (dx / zoom), as in v1.5.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.transformpillsandbox"])
class QaTransformPillIncrementsUiTest {

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
        assertEquals("the pill places the Center", 160f, t.anchorPosition!!.x, 0.01f)
        assertEquals(130f, t.anchorPosition!!.y, 0.01f)
        return t
    }

    /** One finger from window px ([x0], [y0]) through [to] (window px), 16 ms per event; [held] runs before the lift. */
    private fun finger(s: ChromeScreen, x0: Float, y0: Float, to: List<Pair<Float, Float>>, held: () -> Unit = {}) {
        s.touch.idle(400)
        s.touch.send(MotionEvent.ACTION_DOWN, P(0, x0, y0))
        var last = x0 to y0
        for (p in to) {
            for (i in 1..8) {
                s.touch.idle(16)
                s.touch.send(MotionEvent.ACTION_MOVE, P(0, last.first + (p.first - last.first) * i / 8, last.second + (p.second - last.second) * i / 8))
            }
            last = p
        }
        s.touch.idle(16)
        settle(2)
        held()
        s.touch.send(MotionEvent.ACTION_UP, P(0, last.first, last.second))
        s.touch.idle(50)
        settle()
    }

    /** Drags the number of the [axis] cell by [dx] window px (sideways). */
    private fun dragCell(s: ChromeScreen, axis: String, dx: Float) {
        val b = QaCurves.slider("$axis slider").bounds.center
        finger(s, b.x, b.y, listOf(b.x + dx to b.y))
    }

    @Test
    fun theTransformPillStepsWithIncrementsOnAndFollowsTheFingerOff() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on (Length 25): X and Y drags land on multiples, Fine, typed exact, ✓ one step") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25")
            val t = startTransform(s)
            assertTrue("the \"#\" cell shows it on", c.increments.enabled)
            val steps = c.undoManager.undoCount
            val zoom = c.viewTransform.zoom

            // X: +67 screen px from 160 → the nearest multiple of 25 to 160 + 67 / zoom.
            dragCell(s, "X", 67f)
            val x1 = t.anchorPosition!!.x
            val rawX = 160f + 67f / zoom
            assertTrue("X on a 25 px step: $x1", QaCurves.onStep(x1, 25f))
            assertTrue("X is the step nearest to $rawX: $x1", abs(x1 - rawX) <= 12.5f + 1e-3f)
            assertTrue("X moved", x1 != 160f)
            var b = t.transformState!!.bounds()
            assertEquals("the block follows its centre", x1 - 60f, b.left, 0.02f)
            assertEquals("and keeps its size", 120f, b.right - b.left, 0.02f)
            assertEquals("Y untouched", 130f, t.anchorPosition!!.y, 0.01f)

            // Y: −90 screen px from 130 (a non-multiple of 25): absolute steps, not 130 − k·25.
            dragCell(s, "Y", -90f)
            val y1 = t.anchorPosition!!.y
            val rawY = 130f - 90f / zoom
            assertTrue("Y on a 25 px step (absolute): $y1", QaCurves.onStep(y1, 25f))
            assertTrue("Y is the step nearest to $rawY: $y1", abs(y1 - rawY) <= 12.5f + 1e-3f)
            b = t.transformState!!.bounds()
            assertEquals(100f, b.bottom - b.top, 0.02f)

            // Fine: far below the cell a tenth of the speed, still on the steps.
            val far = 60f * s.density
            val c0 = QaCurves.slider("X slider").bounds.center
            var sawFine = false
            finger(s, c0.x, c0.y, listOf(c0.x to c0.y + far, c0.x + 900f to c0.y + far)) { sawFine = has("Fine", exact = true) }
            assertTrue("\"Fine\" while the finger was far below", sawFine)
            assertFalse("\"Fine\" goes with the finger", has("Fine", exact = true))
            val x2 = t.anchorPosition!!.x
            assertTrue("fine: on a 25 px step: $x2", QaCurves.onStep(x2, 25f))
            assertTrue("fine: a tenth of 900 px (${900f / zoom / 10f}) from $x1: $x2", abs(x2 - (x1 + 900f / zoom / 10f)) <= 12.5f + 1e-3f)

            // Typed: exact, never stepped.
            click("Type X")
            assertTrue("the X field: ${SmokeUi.shown()}", has("X position", exact = true))
            SmokeUi.typeAndDone("X", "171.3")
            assertEquals("typed: exact", 171.3f, t.anchorPosition!!.x, 1e-3f)
            assertEquals(171.3f - 60f, t.transformState!!.bounds().left, 1e-3f)
            assertEquals("all of it is the pending transform", steps, c.undoManager.undoCount)
            QaCurves.shot(s, "transform-pill-stepped")

            click("Apply transform edit")
            assertTrue(Smoke.pumpUntil { settle(1); !t.hasPendingWork && c.busyMessage == null })
            assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
            Smoke.assertQuiet(c, "pill on")
        }
        h.section("\"#\" off: the same X drag follows the finger (v1.5)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            val t = startTransform(s)
            val zoom = c.viewTransform.zoom
            dragCell(s, "X", 67f)
            assertEquals("free: +67 screen px", 160f + 67f / zoom, t.anchorPosition!!.x, 0.06f)
            dragCell(s, "Y", -90f)
            assertEquals("free: −90 screen px", 130f - 90f / zoom, t.anchorPosition!!.y, 0.06f)
            Smoke.assertQuiet(c, "pill off")
        }
        dog.interrupt()
        h.finish()
    }
}
