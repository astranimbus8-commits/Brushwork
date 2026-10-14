package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPaintSpec
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.tools.vector.VectorRenderer
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.vector.VectorEraseChooser
import com.brushwork.paint.ui.vector.VectorEraserStrip
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R11, design 2.4.12): the erase seams of F1b. An erasing [VectorPaintSpec] takes paint away
 * (DST_OUT) and a plain one still paints, also inside a selection (A5: nothing under the alpha
 * lock); [EraseCut.strokeRegion] is the round stroke region; the chooser offers nothing without a
 * vector or shape layer (null items; the tool session is [ToolEraseSession]).
 */
@RunWith(RobolectricTestRunner::class)
class EraseSeamsRobolectricTest {

    private fun square(l: Float, t: Float, r: Float, b: Float) = VectorPath(
        listOf(PathOp.MoveTo(Vec2(l, t)), PathOp.LineTo(Vec2(r, t)), PathOp.LineTo(Vec2(r, b)), PathOp.LineTo(Vec2(l, b)), PathOp.Close),
    )

    @Test
    fun anErasingSpecTakesPaintAwayAndAPlainOneStillPaints() {
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val canvas = Canvas(bmp)
        val renderer = VectorRenderer()
        val erase = VectorPaintSpec.build(square(5f, 5f, 15f, 15f), Color.BLUE, null, 0, 0f, erase = true)!!
        assertTrue(erase.erase)
        renderer.draw(canvas, erase, maskMode = false, colorMode = ColorMode.RGB)
        assertEquals(0, Color.alpha(bmp.getPixel(10, 10)))
        assertEquals(Color.RED, bmp.getPixel(2, 2))
        // The renderer's paints are back to painting for the next, plain spec.
        val paint = VectorPaintSpec.build(square(8f, 8f, 12f, 12f), Color.BLUE, null, 0, 0f)!!
        assertFalse(paint.erase)
        renderer.draw(canvas, paint, maskMode = false, colorMode = ColorMode.RGB)
        assertEquals(Color.BLUE, bmp.getPixel(10, 10))
        assertEquals(Color.RED, bmp.getPixel(2, 2))
    }

    @Test
    fun anErasingSpecTakesAwayOnlyInsideTheSelectionAndNothingUnderTheAlphaLock() {
        // (A5: drawn with DST_OUT into the selection's empty offscreen layer it took nothing away.)
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.RED) }
        val canvas = Canvas(bmp)
        val renderer = VectorRenderer()
        val erase = VectorPaintSpec.build(square(4f, 4f, 16f, 16f), Color.BLUE, null, 0, 0f, erase = true)!!
        val left = Selection.fromPath(Path().apply { addRect(0f, 0f, 10f, 20f, Path.Direction.CW) }, 20, 20, antiAlias = false)
        renderer.drawClipped(canvas, erase, left, alphaLocked = false, clip = Rect(0, 0, 20, 20), maskMode = false, colorMode = ColorMode.RGB)
        assertEquals("inside the spec and the selection", 0, Color.alpha(bmp.getPixel(7, 10)))
        assertEquals("inside the spec, outside the selection", Color.RED, bmp.getPixel(13, 10))
        assertEquals("outside the spec", Color.RED, bmp.getPixel(2, 2))
        // The alpha lock keeps every pixel's alpha: nothing goes.
        renderer.drawClipped(canvas, erase, null, alphaLocked = true, clip = Rect(0, 0, 20, 20), maskMode = false, colorMode = ColorMode.RGB)
        assertEquals(Color.RED, bmp.getPixel(13, 10))
        // A plain spec still paints inside the selection only.
        val paint = VectorPaintSpec.build(square(4f, 4f, 16f, 16f), Color.BLUE, null, 0, 0f)!!
        renderer.drawClipped(canvas, paint, left, alphaLocked = false, clip = Rect(0, 0, 20, 20), maskMode = false, colorMode = ColorMode.RGB)
        assertEquals(Color.BLUE, bmp.getPixel(7, 10))
        assertEquals(Color.RED, bmp.getPixel(13, 10))
    }

    @Test
    fun strokeRegionIsTheRoundCappedBandAlongTheLine() {
        val line = Path().apply { moveTo(10f, 10f); lineTo(30f, 10f) }
        val region = EraseCut.strokeRegion(line, 4f)
        val b = RectF().also { region.computeBounds(it, true) }
        assertEquals(6f, b.left, 0.5f)
        assertEquals(34f, b.right, 0.5f)
        assertEquals(6f, b.top, 0.5f)
        assertEquals(14f, b.bottom, 0.5f)
        assertTrue(EraseCut.strokeRegion(line, 0f).isEmpty)
        assertTrue(EraseCut.strokeRegion(line, Float.NaN).isEmpty)
    }

    @Test
    fun theChooserOffersNothingWithoutALayerOfObjects() {
        val ctx = StripContext(selectionBarShown = false, vectorMode = true)
        assertEquals(VectorEraserStrip.KEY, VectorEraseChooser.KEY)
        assertNull(VectorEraseChooser.item(ctx, null, VectorEraseMode.OBJECT) {})
        assertNull(VectorEraseChooser.pixelsItem(ctx, null) {})
        assertNull(VectorEraseChooser.modeCell(ctx, null, VectorEraseMode.PARTIAL, {}, {}))
    }
}
