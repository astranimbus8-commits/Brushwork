package com.brushwork.paint.tools.select.magnetic

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.4, D12, §6.3 Q7): the magnetic lasso's loupe on a phone's density (2.75).
 * It shows from finger-down (and on every move) to lift, for a touch that places an anchor and
 * for one that drags an anchor; it sits 96 dp above the finger, and flips below it when the
 * finger is in the canvas's top 140 dp. It shows the canvas under the finger magnified 3 times:
 * a black square 6 doc px right of and below the finger is 18 view px right of and below the
 * loupe's centre.
 */
@RunWith(RobolectricTestRunner::class)
class MagneticLoupeUiTest {

    @Test
    fun theLoupeFollowsTheFingerFlipsAtTheTopAndMagnifiesTheCanvas() {
        ShadowLog.stream = null
        val doc = Smoke.document(400, 800, whiteBottom = true)
        // The probe: a black 4 × 4 square 6 px right of and below the finger's point.
        Canvas(doc.layers[0].bitmap).drawRect(206f, 606f, 210f, 610f, Paint().apply { color = Color.BLACK })
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        val t = c.viewTransform
        t.set(Matrix())
        t.density = 2.75f
        c.selectTool(ToolId.MAGNETIC_LASSO)
        val tool = c.currentTool as MagneticLassoTool
        assertTrue(Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        val offset = t.dp(Loupe.OFFSET_DP)

        // A tap that places an anchor: the loupe from down to lift, above the finger.
        assertNull(tool.loupe)
        c.pointerDown(ToolPoint(200f, 600f))
        val down = assertNotNull(tool.loupe)
        assertFalse(down.flipped)
        assertEquals(200f, down.center.x)
        assertEquals(600f - offset, down.center.y, 0.01f)

        // It shows the canvas magnified 3 times about the finger: the square is dark, the paper
        // on the other side is white.
        val view = Bitmap.createBitmap(400, 800, Bitmap.Config.ARGB_8888)
        view.eraseColor(Color.GRAY)
        tool.drawOverlay(Canvas(view), t)
        val k = Loupe.MAGNIFICATION
        val cx = down.center.x.toInt(); val cy = down.center.y.toInt()
        val dark = view.getPixel(cx + (8 * k).toInt(), cy + (8 * k).toInt())
        assertTrue("the square in the loupe: ${Integer.toHexString(dark)}", Color.red(dark) < 64)
        val paper = view.getPixel(cx - (8 * k).toInt(), cy - (8 * k).toInt())
        assertTrue("the paper in the loupe: ${Integer.toHexString(paper)}", Color.red(paper) > 220)
        // Outside the loupe the overlay leaves the view alone.
        assertEquals(Color.GRAY, view.getPixel(20, 20))
        view.recycle()

        c.pointerMove(ToolPoint(200f, 601f))
        assertNotNull("the loupe on the first move", tool.loupe)
        c.pointerUp(ToolPoint(200f, 601f))
        assertNull("no loupe after the lift", tool.loupe)
        assertEquals(1, tool.anchorCount)

        // Near the top (under 140 dp) the loupe flips below the finger.
        val topY = t.dp(Loupe.FLIP_DP) - 20f
        c.pointerDown(ToolPoint(100f, topY))
        val top = assertNotNull(tool.loupe)
        assertTrue(top.flipped)
        assertEquals(topY + offset, top.center.y, 0.01f)
        c.pointerUp(ToolPoint(100f, topY))
        assertNull(tool.loupe)
        assertEquals(2, tool.anchorCount)

        // A drag of an anchor: the loupe from down, through the moves, to the lift.
        val ax = tool.points.x(1); val ay = tool.points.y(1)
        c.pointerDown(ToolPoint(ax, ay))
        assertNotNull(tool.loupe)
        for (i in 1..4) {
            c.pointerMove(ToolPoint(ax + 10f * i, ay + 10f * i))
            val l = assertNotNull(tool.loupe)
            assertEquals(ax + 10f * i, l.focus.x)
        }
        c.pointerUp(ToolPoint(ax + 40f, ay + 40f))
        assertNull(tool.loupe)
        assertEquals("a drag moves, it never adds", 2, tool.anchorCount)
        Smoke.assertQuiet(c, "magnetic loupe")
    }

    private fun <T> assertNotNull(v: T?): T {
        org.junit.Assert.assertNotNull(v)
        return v!!
    }

    private fun assertNotNull(message: String, v: Any?) = org.junit.Assert.assertNotNull(message, v)
}
