package com.brushwork.paint.v19.a1

import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveWidths
import com.brushwork.paint.v19.a1.A1Tools.CURVES
import com.brushwork.paint.v19.a1.A1Tools.withPoints
import com.brushwork.paint.vector.VSpline
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 R2 (design 1 R2 (d), fact V1): one clamp constant for point thickness. The curve tools'
 * largest factor ([CurveWidths.MAX_FACTOR]) and the path model's ([VSpline.MAX_WIDTH]) are one
 * value; `CurveTool.setWidth` (one point) and `setWidths` (several) both clamp with
 * [CurveWidths.MAX_FACTOR], on Curve, Polyline and Path alike.
 */
@RunWith(RobolectricTestRunner::class)
class CurveWidthConstantsTest {

    @Test
    fun theTwoNamesAreOneValue() {
        assertEquals(VSpline.MAX_WIDTH, CurveWidths.MAX_FACTOR, 0f)
        assertEquals(3f, CurveWidths.MAX_FACTOR, 0f)
        assertEquals(CurveWidths.MAX_FACTOR, CurveWidths.clamp(10f), 0f)
    }

    @Test
    fun setWidthAndSetWidthsClampAlikeOnEveryCurveTool() {
        for (id in CURVES) {
            val (_, t) = withPoints(id)
            t.select(1)
            t.setWidth(1, 7f)
            t.endNumericEdit()
            assertEquals("$id: one point", CurveWidths.MAX_FACTOR, t.widthOf(1), 0f)
            t.setWidth(1, -2f)
            t.endNumericEdit()
            assertEquals("$id: one point, below 0", 0f, t.widthOf(1), 0f)
            t.selectPoints(PointSelection.of(t.pointCount, 2, 3, 4))
            t.setWidths(t.pointSelection.indices, floatArrayOf(9f, -1f, 2.5f))
            t.endNumericEdit()
            assertArrayEquals("$id: several points", floatArrayOf(CurveWidths.MAX_FACTOR, 0f, 2.5f), t.selectedWidths(), 0f)
            // A non-finite value leaves its point as it is.
            t.setWidths(t.pointSelection.indices, floatArrayOf(Float.NaN, Float.POSITIVE_INFINITY, 1f))
            t.endNumericEdit()
            assertArrayEquals("$id: non-finite skipped", floatArrayOf(CurveWidths.MAX_FACTOR, 0f, 1f), t.selectedWidths(), 0f)
        }
    }

    /** The tool names one constant for the clamp (the source, read from the module or the repo root). */
    @Test
    fun theToolNamesOneConstant() {
        val rel = "src/main/java/com/brushwork/paint/tools/vector/CurveTool.kt"
        val file = listOf(File(rel), File("app/$rel")).firstOrNull { it.isFile } ?: return
        val src = file.readText()
        assertFalse("CurveTool clamps widths with CurveWidths.MAX_FACTOR only", src.contains("VSpline.MAX_WIDTH"))
    }
}
