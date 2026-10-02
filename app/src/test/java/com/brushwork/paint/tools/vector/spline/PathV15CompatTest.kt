package com.brushwork.paint.tools.vector.spline

import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.controller
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.onLine
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.pixels
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.render
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tap
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport.tool
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorCodec
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.6 §3.2b/d: a Path-tool path stays readable by v1.5. Decoded with a frozen copy of the v1.5
 * `VPath` (which has no `spline`), its anchors are exactly the ones v1.6 draws, so v1.5 renders
 * the same curve; nothing that draws a path reads the spline. Through a `.vec` round trip the
 * spline survives, still passes the I9 check, and the path reopens in the Path tool.
 */
@RunWith(RobolectricTestRunner::class)
class PathV15CompatTest {

    /** The v1.5 shape of a path (test-only frozen copy: no `spline`). */
    @Serializable
    @SerialName("path")
    private data class VPathV15(
        val id: Long,
        val opacity: Float = 1f,
        val subpaths: List<VSubpath>,
        val tension: Float = 0f,
        val polyline: Boolean = false,
        val fillRule: VFillRule = VFillRule.NONZERO,
        val fill: VPaint? = null,
        val stroke: VStrokeStyle? = null,
    )

    /** v1.5's reader settings (VectorCodec). */
    private val v15Json = Json { ignoreUnknownKeys = true; coerceInputValues = true; allowSpecialFloatingPointValues = true }

    @Test
    fun v15ReadsTheBezierFormAndDrawsTheSameCurve() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 5f, fill = true) }
        for ((x, y) in listOf(40f to 240f, 100f to 40f, 170f to 260f, 240f to 30f, 310f to 250f, 370f to 60f)) c.tap(x, y)
        t.setOrder(5)
        t.select(2)
        t.setWeight(2, 3.5f)
        t.endNumericEdit()
        t.commit()
        val p = layer.vector!!.objects.single() as VPath
        val json = Json { encodeDefaults = true }.encodeToString(VObject.serializer(), p)
        assertTrue(json.contains("\"spline\""))
        val old = v15Json.decodeFromString(VPathV15.serializer(), json)
        assertEquals("v1.5 reads exactly the anchors v1.6 draws", p.subpaths, old.subpaths)
        assertEquals(p.tension, old.tension, 0f)
        assertEquals(p.polyline, old.polyline)
        assertEquals(p.fill, old.fill)
        assertEquals(p.stroke, old.stroke)
        // v1.5 draws the stored Bézier form: the same pixels as v1.6 (which never reads the spline).
        val asV15 = VPath(old.id, old.opacity, old.subpaths, old.tension, old.polyline, old.fillRule, old.fill, old.stroke)
        assertArrayEquals(pixels(layer.bitmap), render(VectorContent.EMPTY.plus(listOf(asV15)).first))
        assertArrayEquals(pixels(layer.bitmap), render(layer.vector!!))
        // A v1.5 re-save drops the spline: v1.6 then sees a plain Bézier path (the Curve tool edits it).
        assertTrue(!SplineBezier.matches(asV15))
    }

    @Test
    fun aVecRoundTripKeepsTheSplineAndItReopensInPath() {
        val c = controller()
        val layer = c.activeLayer
        val t = c.tool(ToolId.PATH)
        t.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = false, plainWidth = 4f) }
        for ((x, y) in listOf(60f to 200f, 150f to 60f, 250f to 220f, 340f to 80f)) c.tap(x, y)
        t.setCyclic(true)
        t.commit()
        val content = layer.vector!!
        val back = VectorCodec.decode(VectorCodec.encode(content))
        assertEquals(content, back)
        val p = back.objects.single() as VPath
        assertTrue("I9 after reading it back", SplineBezier.matches(p))
        // Reopened from the read-back content.
        layer.vector = back
        c.tap(onLine(p))
        assertTrue(t.isReopened)
        assertEquals(p.spline, t.spline)
        t.discard()
    }
}
