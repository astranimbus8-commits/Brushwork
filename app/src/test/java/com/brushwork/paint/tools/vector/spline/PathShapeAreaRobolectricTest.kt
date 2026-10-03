package com.brushwork.paint.tools.vector.spline

import android.graphics.Matrix
import android.graphics.RectF
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2a: the area the Path tool's quick starts fit into is what the free part of the
 * canvas view shows ([com.brushwork.paint.tools.vector.CurveTool.shapeArea]): centred on the
 * document point under its middle, as large as it at the zoom (sides swapped on a quarter
 * turn), cut to the canvas; the whole canvas when the view is unknown.
 */
@RunWith(RobolectricTestRunner::class)
class PathShapeAreaRobolectricTest {

    private fun assertRect(where: String, e: RectF, a: RectF) {
        assertEquals("$where left", e.left, a.left, 1e-3f)
        assertEquals("$where top", e.top, a.top, 1e-3f)
        assertEquals("$where right", e.right, a.right, 1e-3f)
        assertEquals("$where bottom", e.bottom, a.bottom, 1e-3f)
    }

    @Test
    fun theAreaIsWhatTheFreePartOfTheViewShows() {
        val c = CurveToolTestSupport.controller()
        val tool = c.tool(ToolId.PATH)
        val doc = RectF(0f, 0f, 400f, 300f)
        assertRect("no view", doc, tool.shapeArea(null))
        assertRect("an empty view", doc, tool.shapeArea(RectF(0f, 0f, 0f, 0f)))

        // Zoom 4, panned so doc (100, 80) is at screen (0, 0): a 200 × 300 px band at (40, 100)
        // shows doc 110..160 × 105..180.
        c.viewTransform.set(Matrix().apply { setScale(4f, 4f); postTranslate(-400f, -320f) })
        assertRect("zoomed", RectF(110f, 105f, 160f, 180f), tool.shapeArea(RectF(40f, 100f, 240f, 400f)))
        // Past the canvas's corner: cut to it.
        c.viewTransform.set(Matrix().apply { setScale(4f, 4f); postTranslate(-1400f, -1000f) })
        assertRect("cut to the canvas", RectF(360f, 275f, 400f, 300f), tool.shapeArea(RectF(40f, 100f, 240f, 400f)))
        // Nothing of the canvas shows: the whole canvas.
        c.viewTransform.set(Matrix().apply { setScale(4f, 4f); postTranslate(-4000f, -4000f) })
        assertRect("off the canvas", doc, tool.shapeArea(RectF(40f, 100f, 240f, 400f)))

        // A quarter turn (zoom 2): centred on the doc point under the band's middle, its sides
        // swapped (the band's 300 px height runs along the document's x).
        val turned = Matrix().apply { setScale(2f, 2f); postRotate(90f); postTranslate(440f, -200f) }
        c.viewTransform.set(turned)
        val centre = c.viewTransform.screenToDoc(140f, 250f)
        assertRect(
            "turned",
            RectF(centre.x - 75f, centre.y - 50f, centre.x + 75f, centre.y + 50f),
            tool.shapeArea(RectF(40f, 100f, 240f, 400f)),
        )
        // Mirrored (display flip): the same area as unmirrored, around the point under the middle.
        c.viewTransform.set(Matrix().apply { setScale(-4f, 4f); postTranslate(1600f, -320f) })
        val m = c.viewTransform.screenToDoc(140f, 250f)
        assertRect("mirrored", RectF(m.x - 25f, m.y - 37.5f, m.x + 25f, m.y + 37.5f), tool.shapeArea(RectF(40f, 100f, 240f, 400f)))
    }
}
