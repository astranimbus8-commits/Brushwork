package com.brushwork.paint.v110.a6

import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeToSpline
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A6 (R11 (e)): "Turn into shape" and the existing "Turn into path"
 * ([ShapeToSpline.convert]) undo each other's look. A path turned into a shape and back into a
 * path renders within 1 px of the first path, with its line and fill; a shape turned into a path
 * and back into a shape renders within 1 px of the first shape.
 */
@RunWith(RobolectricTestRunner::class)
class PathShapeRoundTripTest {
    private val red = 0xFFE04020.toInt()

    /** [p] as a shape, then as a path again (as the Shape tool's "Turn into path" makes it). */
    private fun backToPath(p: VPath): VPath {
        val shape = PathToShape.convert(p).shape.shape
        val back = ShapeToSpline.convert(shape, IntArray(0)) ?: throw AssertionError("Turn into path refused $shape")
        assertNotNull("a Path-tool path again", back.path.spline)
        return back.path
    }

    @Test
    fun pathShapePathLooksTheSame() {
        for (p in listOf(A6Paths.curve(false), A6Paths.curve(true).copy(fill = VPaint.Solid(red)), A6Paths.polyline(true), A6Paths.spline(false), A6Paths.spline(true))) {
            val back = backToPath(p)
            assertEquals("the line", p.stroke!!.color, back.stroke!!.color)
            assertEquals(p.stroke!!.width, back.stroke!!.width)
            assertEquals("the fill", p.fill, back.fill)
            A6Render.assertWithinOnePx("$p and back", p, back)
        }
    }

    @Test
    fun shapePathShapeLooksTheSame() {
        val shapes = listOf(
            ShapeObject(ShapeType.ELLIPSE, cx = 200f, cy = 150f, w = 220f, h = 140f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f, strokeColor = 0xFF203080.toInt(), fillColor = red),
            ShapeObject(ShapeType.STAR, cx = 200f, cy = 150f, w = 200f, h = 200f, style = ShapeStyle.STROKE, strokeWidth = 5f, corner = CornerStyle.ROUND),
            ShapeObject(ShapeType.RECTANGLE, cx = 200f, cy = 150f, w = 240f, h = 160f, style = ShapeStyle.FILL, corner = CornerStyle.ROUND, cornerRadius = 30f, fillColor = red),
        )
        for (s in shapes) {
            val path = ShapeToSpline.convert(s, IntArray(0))?.path ?: throw AssertionError("Turn into path refused $s")
            assertNull("convertible", PathToShape.refusal(path))
            val again = PathToShape.convert(path).shape
            A6Render.assertWithinOnePx("${s.type} and back", VShape(1L, shape = s), again)
        }
    }
}
