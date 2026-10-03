package com.brushwork.paint.qa16

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
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
import kotlin.math.roundToInt

/**
 * v1.6 final QA (increments everywhere: mask handles) on the real editor at the user's phone size,
 * with real fingers: Masks in the tool menu, "+ Radial", a creating drag from (200, 150) 58 px
 * outwards, then the pin dragged by (+37, +12) and the right radius handle by (+23.4, +3). With "#"
 * on (More › Increments…, Length 25 px) the radius lands on a whole step (50), the pin moves by
 * whole steps per axis (+25, 0), the radius handle lands on the step nearest the finger (75), and
 * the info chip shows each while the finger is down; each gesture is one undo step. With "#" off
 * every gesture is exact (v1.5).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.maskhandlessandbox"])
class QaMaskHandlesIncrementsUiTest {

    private fun radial(l: Layer): RadialMask = l.maskSpec!!.components.single() as RadialMask

    /** Masks, "+ Radial", a finger from (200, 150) 58 px to the right: the new adjustment layer. */
    private fun createRadial(s: ChromeScreen): Layer {
        val c = s.c
        QaCurves.tool(s, "Masks")
        assertEquals(ToolId.MASK, c.activeToolId)
        val tool = c.currentTool as MaskTool
        if (c.snapping.enabled) {
            if (has("Snap to objects", exact = true) || has("Snap", exact = true)) QaCurves.snapOff(c) else c.snapping.enabled = false
        }
        assertFalse(c.snapping.enabled)
        QaCurves.scrollStripTo(s, "+ Radial")
        click("+ Radial", exact = true)
        assertEquals(MaskTool.Kind.RADIAL, tool.armed)
        val steps = c.undoManager.undoCount
        QaCurves.drag(s, Vec2(200f, 150f), Vec2(58f, 0f))
        val adj = c.activeLayer
        assertTrue("the creating drag made an adjustment layer", adj.isAdjustmentLayer)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        return adj
    }

    @Test
    fun maskHandlesStepByTheLengthIncrementOnAndAreExactOff() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("\"#\" on (Length 25): the creating drag, the pin, the radius handle") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOn(length = "25")
            val adj = createRadial(s)
            val r0 = radial(adj)
            assertEquals("centre where the finger went down", 200f, r0.cx, 0.05f)
            assertEquals(150f, r0.cy, 0.05f)
            assertEquals("58 px → two whole steps", 50f, r0.rx, 1e-3f)
            assertEquals(50f, r0.ry, 1e-3f)

            // The pin (+37, +12): +25, 0.
            var held: String? = null
            var steps = c.undoManager.undoCount
            QaCurves.drag(s, Vec2(r0.cx, r0.cy), Vec2(37f, 12f)) { held = c.increments.readout }
            val r1 = radial(adj)
            assertEquals("x: one step", r0.cx + 25f, r1.cx, 1e-3f)
            assertEquals("y: under half a step", r0.cy, r1.cy, 1e-3f)
            assertNotNull("the info chip while the pin moves", held)
            assertTrue("it says +25 px: $held", held!!.contains("+25 px"))
            assertNull(c.increments.readout)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)

            // The right radius handle (+23.4, +3): the nearest step to 73.4 px.
            held = null
            steps = c.undoManager.undoCount
            QaCurves.drag(s, Vec2(r1.cx + r1.rx, r1.cy), Vec2(23.4f, 3f)) { held = c.increments.readout }
            val r2 = radial(adj)
            assertEquals("Radius X on a whole step", ((r1.rx + 23.4f) / 25f).roundToInt() * 25f, r2.rx, 1e-3f)
            assertEquals("the centre stays", r1.cx, r2.cx, 1e-3f)
            assertEquals("the info chip", "Radius X 75 px", held)
            assertEquals("one step", steps + 1, c.undoManager.undoCount)
            QaCurves.shot(s, "mask-handles-increments")
            Smoke.assertQuiet(c, "masks on")
        }
        h.section("\"#\" off: the same gestures are exact (v1.5)") {
            val s = h.editor()
            val c = s.c
            QaCurves.incrementsOff()
            val adj = createRadial(s)
            val r0 = radial(adj)
            assertEquals("58 px as the finger went", 58f, r0.rx, 0.05f)
            QaCurves.drag(s, Vec2(r0.cx, r0.cy), Vec2(37f, 12f))
            val r1 = radial(adj)
            assertEquals(r0.cx + 37f, r1.cx, 0.05f)
            assertEquals(r0.cy + 12f, r1.cy, 0.05f)
            QaCurves.drag(s, Vec2(r1.cx + r1.rx, r1.cy), Vec2(23.4f, 3f))
            assertEquals(r1.rx + 23.4f, radial(adj).rx, 0.05f)
            assertNull(c.increments.readout)
            Smoke.assertQuiet(c, "masks off")
        }
        dog.interrupt()
        h.finish()
    }
}
