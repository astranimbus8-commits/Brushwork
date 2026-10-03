package com.brushwork.paint.ui.editor.chrome

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.MotionEvent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.6 §3.4 (areas E and G): while a stepped gesture runs, the InfoChip slot under the top row
 * shows where it is (`controller.increments.readout`), and the chip goes when the finger lifts.
 * A real one-finger drag of the Transform tool's box on the user's phone, increments on with a
 * 10 px Length step: the box lands on multiples of 10 px and the chip says by how much.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.editor.chrome.incrementsreadoutsandbox"])
class IncrementsReadoutChipUiTest {

    @Test
    fun aSteppedTransformDragShowsItsReadoutInTheChipUntilTheFingerLifts() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("a stepped Transform move") {
            val s = h.editor { c ->
                val layer = c.activeLayer
                Canvas(layer.bitmap).drawRect(Rect(100, 100, 200, 160), Paint().apply { color = Color.RED })
                layer.markChanged()
                c.increments.update { it.copy(enabled = true, lengthPx = 10f) }
            }
            val c = s.c
            c.selectTool(ToolId.TRANSFORM)
            assertTrue("the box", Smoke.pumpUntil { settle(1); (c.currentTool as TransformTool).transformState != null })
            val t = c.currentTool as TransformTool
            t.snapToObjects = false
            settle()
            val before = t.transformState!!.bounds()
            assertEquals("the red block", 100f, before.left, 0.5f)
            assertNull("nothing stepped yet", c.increments.readout)

            // One finger inside the box, 33 px left and 4 px down (document px), held there.
            val touch = s.touch
            touch.idle(300)
            val (x0, y0) = s.screen(150f, 130f)
            touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, x0, y0))
            var end = x0 to y0
            for (i in 1..8) {
                touch.idle(16)
                end = s.screen(150f - 33f * i / 8f, 130f + 4f * i / 8f)
                touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, end.first, end.second))
            }
            touch.idle(16)
            settle(4)
            val readout = c.increments.readout
            assertNotNull("a stepped move says where it is", readout)
            assertEquals("−30 px, 0 px", readout)
            val chip = SmokeUi.find(readout!!, exact = true) ?: throw AssertionError("no chip \"$readout\"; shown: ${SmokeUi.shown().take(60)}")
            assertTrue("in the editor's window", chip.window === s.activity.window.decorView)
            val box = s.dp(chip.bounds)
            assertTrue("under the top row, in the upper half: $box", box.top > 40f && box.center.y < s.heightDp / 2f)
            assertEquals("centred", s.widthDp / 2f, box.center.x, 24f)
            val moved = t.transformState!!.bounds()
            assertEquals("on the step", -30f, moved.left - before.left, 0.01f)
            assertEquals(0f, moved.top - before.top, 0.01f)

            touch.send(MotionEvent.ACTION_UP, Smoke.P(0, end.first, end.second))
            touch.idle(50)
            assertNull("the readout clears when the finger lifts", c.increments.readout)
            Smoke.pump(400)
            settle()
            assertFalse("the chip is gone", has(readout, exact = true))
            assertTrue("the move stays", abs(t.transformState!!.bounds().left - (before.left - 30f)) < 0.01f)
            t.discard()
            settle()
            Smoke.assertQuiet(c, "readout chip")
        }
        dog.interrupt()
        h.finish()
    }
}
