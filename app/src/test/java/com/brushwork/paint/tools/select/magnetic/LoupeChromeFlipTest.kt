package com.brushwork.paint.tools.select.magnetic

import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, review; design §3.4 D12): the canvas view runs under the editor's top row and options
 * strip, so the loupe's "near the top" is measured from the bars' lower edge (the insets the
 * editor gives the eyedropper), not from the view's: a finger just under the strip gets its loupe
 * below it, never hidden under the bars. Both loupes (Magnetic lasso, Color select) follow it.
 */
@RunWith(RobolectricTestRunner::class)
class LoupeChromeFlipTest {

    private fun setUp(): EditorController {
        ShadowLog.stream = null
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), Smoke.document(400, 512, whiteBottom = true))
        c.viewTransform.set(Matrix())
        c.viewTransform.density = 1.5f
        return c
    }

    private fun eyedropper(c: EditorController) = c.tools.getValue(ToolId.EYEDROPPER) as EyedropperTool

    @Test
    fun theFlipIsMeasuredFromTheBarsLowerEdge() {
        val c = setUp()
        val t = c.viewTransform
        val flipAt = t.dp(Loupe.FLIP_DP)
        val offset = t.dp(Loupe.OFFSET_DP)
        // Before the editor measures its bars: the view's top, as before.
        assertEquals(0f, Loupe.chromeTop(c))
        assertFalse(Loupe.at(Vec2(100f, flipAt + 10f), t).flipped)

        // The bars cover the top 137 dp (status bar, top row, strip).
        val bars = t.dp(137f)
        eyedropper(c).setChromeInsets(0f, bars, 0f, t.dp(20f))
        assertEquals(bars, Loupe.chromeTop(c))
        val y = flipAt + 10f // under the bars, within 140 dp of their lower edge
        assertTrue(y < bars + flipAt)

        c.selectTool(ToolId.MAGNETIC_LASSO)
        val magnetic = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil { magnetic.edgeMapReady && magnetic.idle })
        c.pointerDown(ToolPoint(100f, y))
        val m = magnetic.loupe!!
        assertTrue("flipped below the finger", m.flipped)
        assertEquals(y + offset, m.center.y, 0.01f)
        assertTrue("the circle clears the bars", m.center.y - t.dp(Loupe.DIAMETER_DP) / 2f >= bars)
        // Lower down the loupe is above the finger again.
        val low = bars + flipAt + 20f
        c.pointerMove(ToolPoint(100f, low))
        assertFalse(magnetic.loupe!!.flipped)
        assertEquals(low - offset, magnetic.loupe!!.center.y, 0.01f)
        c.pointerCancel()

        c.selectTool(ToolId.COLOR_SELECT)
        val color = c.currentTool as ColorSelectTool
        c.pointerDown(ToolPoint(100f, y))
        val l = color.loupe!!
        assertTrue(l.flipped)
        assertEquals(y + offset, l.center.y, 0.01f)
        c.pointerCancel()
        assertTrue(Smoke.pumpUntil { color.idle })
        Smoke.assertQuiet(c, "loupe under the bars")
    }
}
