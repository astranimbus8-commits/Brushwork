package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Matrix
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.vector.geom.ObjectIndex
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 3, design §3.3 b): canvas operations map a vector array's spec with its content
 * ([LayerDataTransforms.transformed]): the copies land where the old ones map to, a centre or
 * pivot that follows the source included. Every other array is baked (dropped), as text and
 * shape data are; a colour-mode change (identity) keeps a vector array only. Data without an
 * array is mapped exactly as in v1.6.
 */
@RunWith(RobolectricTestRunner::class)
class LayerDataTransformsArrayTest {
    private val docW = 400
    private val docH = 300

    /** A horizontal stroke from (60, 100) to (160, 100), 8 px wide. */
    private fun content(): VectorContent {
        val n = 21
        val s = VStroke(
            1L, preset = BrushLibrary.defaultBrush.copy(size = 8f, scatter = 0f), color = -16777216, seed = 7L,
            stylus = false, points = PackedPoints(FloatArray(n) { 60f + it * 5f }, FloatArray(n) { 100f }, FloatArray(n) { 1f }),
        )
        return VectorContent(objects = listOf(s), nextId = 2)
    }

    private fun flipVertical() = Matrix().apply { setValues(floatArrayOf(1f, 0f, 0f, 0f, -1f, docH.toFloat(), 0f, 0f, 1f)) }

    private fun quarterTurn() = Matrix().apply { setValues(floatArrayOf(0f, -1f, docH.toFloat(), 1f, 0f, 0f, 0f, 0f, 1f)) }

    private fun move(dx: Float, dy: Float) = Matrix().apply { setTranslate(dx, dy) }

    @Test
    fun aCircleArrayWithAFollowingCentreKeepsItsPlacementUnderAVerticalFlip() {
        val v = content()
        val spec = ArraySpec(mode = ArrayMode.CIRCLE, count = 6)
        val d = LayerData(vector = v, array = LayerArray(spec))
        val out = LayerDataTransforms.transformed(d, flipVertical(), docW, docH)
        assertNotNull("a vector array is kept", out.array)
        val a = out.array!!.spec
        // The old default centre: 1.2 × the larger side below the source's centre, then flipped.
        val b = ObjectIndex.of(v).unionBounds()
        val side = maxOf(b.width(), b.height())
        assertEquals(b.centerX(), a.centerX!!, 1e-3f)
        assertEquals(docH - (b.centerY() + 1.2f * side), a.centerY!!, 1e-3f)
        assertEquals("a reflection reverses the sweep", -360f, a.sweepDeg, 0f)
        assertEquals("the content is mapped as in v1.6", LayerDataTransforms.mapped(v, floatArrayOf(1f, 0f, 0f, 0f, -1f, docH.toFloat(), 0f, 0f, 1f)), out.vector)
    }

    @Test
    fun aTransformArrayPivotFollowsAQuarterTurn() {
        val v = content()
        val spec = ArraySpec(mode = ArrayMode.TRANSFORM, moveX = 50f, moveY = 0f)
        val out = LayerDataTransforms.transformed(LayerData(vector = v, array = LayerArray(spec)), quarterTurn(), docH, docW)
        val a = out.array!!.spec
        val b = ObjectIndex.of(v).unionBounds()
        // (x, y) -> (H - y, x)
        assertEquals(docH - b.centerY(), a.pivotX!!, 1e-3f)
        assertEquals(b.centerX(), a.pivotY!!, 1e-3f)
        assertEquals(0f, a.moveX, 1e-3f)
        assertEquals(50f, a.moveY, 1e-3f)
        assertNull("a centre the mode does not use stays as it was", a.centerX)
    }

    @Test
    fun aMoveKeepsAFollowingCentreFollowing() {
        val v = content()
        val out = LayerDataTransforms.transformed(LayerData(vector = v, array = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE))), move(-20f, 15f), docW, docH)
        assertNull(out.array!!.spec.centerX)
        assertNull(out.array!!.spec.centerY)
    }

    @Test
    fun everyOtherArrayIsBaked() {
        val spec = ArraySpec(count = 4)
        val pixels = ArrayPixels(Bitmap.createBitmap(10, 10, Bitmap.Config.ARGB_8888), 5, 5)
        val raster = LayerData(array = LayerArray(spec, pixels))
        val text = LayerData(text = "{}", array = LayerArray(spec))
        val shape = LayerData(shape = "{}", array = LayerArray(spec))
        for (d in listOf(raster, text, shape)) {
            assertNull("baked by a flip", LayerDataTransforms.transformed(d, flipVertical(), docW, docH).array)
            assertNull("baked by a colour-mode change", LayerDataTransforms.transformed(d, Matrix(), docW, docH).array)
        }
    }

    @Test
    fun aColourModeChangeKeepsAVectorArray() {
        val arr = LayerArray(ArraySpec(mode = ArrayMode.CIRCLE))
        val out = LayerDataTransforms.transformed(LayerData(vector = content(), array = arr), Matrix(), docW, docH)
        assertSame(arr, out.array)
    }

    @Test
    fun dataWithoutAnArrayIsMappedAsInV16() {
        val v = content()
        val d = LayerData(vector = v)
        val values = floatArrayOf(1f, 0f, 0f, 0f, -1f, docH.toFloat(), 0f, 0f, 1f)
        assertEquals(LayerData(vector = LayerDataTransforms.mapped(v, values)), LayerDataTransforms.transformed(d, flipVertical(), docW, docH))
        assertEquals(d, LayerDataTransforms.transformed(d, Matrix(), docW, docH))
    }
}
