package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.engine.ViewTransform
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Smart guides drawn with real Skia: lines across the canvas, and labels kept where they can be seen. */
@RunWith(RobolectricTestRunner::class)
class SnapGuideRendererRobolectricTest {

    /** A 400 x 600 view showing a 400 x 400 canvas fit between bars: the canvas is at y 100..500. */
    private fun fitView(): Pair<Bitmap, ViewTransform> {
        val view = Bitmap.createBitmap(400, 600, Bitmap.Config.ARGB_8888)
        val t = ViewTransform().apply { set(Matrix().apply { setTranslate(0f, 100f) }); density = 1f }
        return view to t
    }

    private fun countColor(b: Bitmap, rows: IntRange, cols: Iterable<Int>, color: Int): Int {
        var n = 0
        for (y in rows) for (x in cols) if (b.getPixel(x, y) == color) n++
        return n
    }

    private fun blankRows(b: Bitmap, rows: IntRange): Boolean {
        for (y in rows) for (x in 0 until b.width) if ((b.getPixel(x, y) ushr 24) != 0) return false
        return true
    }

    @Test
    fun aLabelBeyondTheCanvasStaysOnTheCanvas() {
        val (view, t) = fitView()
        // The canvas center line; the box is in the lower half, so its label goes to the top end,
        // which is the canvas' top edge: just above it would be under the editor's top bar.
        val g = SnapGuide(SnapAxis.X, 200f, 0f, 400f, "Canvas center", SnapSource.CANVAS)
        SnapGuideRenderer.draw(Canvas(view), t, listOf(g), 400f, 400f, DocBox(180f, 300f, 220f, 350f))
        assertTrue("nothing above the canvas", blankRows(view, 0..90))
        val label = countColor(view, 102..128, (0..190) + (210..399), SnapGuideRenderer.COLOR)
        assertTrue("the label is on the canvas, near its top ($label px)", label > 20)
        // The faint line goes across the whole canvas.
        assertTrue((view.getPixel(200, 450) ushr 24) != 0)
        assertEquals(0, view.getPixel(200, 560))
    }

    @Test
    fun aHorizontalGuideLabelStaysOnTheCanvasToo() {
        val (view, t) = fitView()
        // "Layer 1 top" reaching to the canvas' right edge (x 400); the box is on the left.
        val g = SnapGuide(SnapAxis.Y, 150f, 20f, 400f, "Layer 1 top", SnapSource.OBJECT)
        SnapGuideRenderer.draw(Canvas(view), t, listOf(g), 400f, 400f, DocBox(20f, 150f, 60f, 190f))
        // At the far (right) end the label can't go beyond the view: it is pulled back inside.
        // (Rows just above and below the line itself at y = 250.)
        val label = countColor(view, 241..244, 250..399, SnapGuideRenderer.COLOR) +
            countColor(view, 256..259, 250..399, SnapGuideRenderer.COLOR)
        assertTrue("label on screen ($label px)", label > 20)
        assertTrue(blankRows(view, 0..90))
    }
}
