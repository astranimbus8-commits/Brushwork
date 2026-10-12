package com.brushwork.paint

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.ShapeSettings
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1a (design 2.3.4): the new AppSettings keys have their defaults and keep what is
 * written (also through a new AppSettings); the curve and shape tool options keep their new
 * fields through `vec.*` and through `sanitized`, and `ShapeSettings.withBehaviourOf` carries
 * `newLayerEachTime` with the other drawing options.
 */
@RunWith(RobolectricTestRunner::class)
class AppSettingsV19Test {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    @Test
    fun theNewKeysHaveTheirDefaults() {
        val s = AppSettings(app)
        assertEquals(AppSettings.DRAW_PREVIEW_FASTER, s.drawPreview)
        assertFalse(s.showFrameTimes)
        assertEquals(AppSettings.NEW_CANVAS_WHITE, s.newCanvasBackground)
        assertFalse(s.textNewLayerEachTime)
        assertEquals(0.15f, s.curveBevelAmount)
        assertEquals(1, s.curveBevelSegments)
        assertEquals(0.15f, s.shapeBevelAmount)
        assertEquals(1, s.shapeBevelSegments)
        assertEquals(listOf("FASTER", "EXACT"), listOf(AppSettings.DRAW_PREVIEW_FASTER, AppSettings.DRAW_PREVIEW_EXACT))
        assertEquals(
            listOf("WHITE", "TRANSPARENT", "CUSTOM", "PAPER"),
            listOf(AppSettings.NEW_CANVAS_WHITE, AppSettings.NEW_CANVAS_TRANSPARENT, AppSettings.NEW_CANVAS_CUSTOM, AppSettings.NEW_CANVAS_PAPER),
        )
    }

    @Test
    fun theNewKeysKeepWhatIsWritten() {
        val s = AppSettings(app)
        s.drawPreview = AppSettings.DRAW_PREVIEW_EXACT
        s.showFrameTimes = true
        s.newCanvasBackground = AppSettings.NEW_CANVAS_TRANSPARENT
        s.textNewLayerEachTime = true
        s.curveBevelAmount = 0.4f
        s.curveBevelSegments = 4
        s.shapeBevelAmount = 0.05f
        s.shapeBevelSegments = 6
        for (read in listOf(s, AppSettings(app))) {
            assertEquals(AppSettings.DRAW_PREVIEW_EXACT, read.drawPreview)
            assertTrue(read.showFrameTimes)
            assertEquals(AppSettings.NEW_CANVAS_TRANSPARENT, read.newCanvasBackground)
            assertTrue(read.textNewLayerEachTime)
            assertEquals(0.4f, read.curveBevelAmount)
            assertEquals(4, read.curveBevelSegments)
            assertEquals(0.05f, read.shapeBevelAmount)
            assertEquals(6, read.shapeBevelSegments)
        }
        s.newCanvasBackground = AppSettings.NEW_CANVAS_CUSTOM
        assertEquals(AppSettings.NEW_CANVAS_CUSTOM, AppSettings(app).newCanvasBackground)
        s.newCanvasBackground = AppSettings.NEW_CANVAS_PAPER
        assertEquals(AppSettings.NEW_CANVAS_PAPER, AppSettings(app).newCanvasBackground)
    }

    /** F1a review: the bevel keys stay within R18's 1-50 % and 1-8 segments, written or read raw. */
    @Test
    fun theBevelKeysStayWithinTheirRanges() {
        val s = AppSettings(app)
        s.curveBevelAmount = 2f
        s.curveBevelSegments = 0
        s.shapeBevelAmount = Float.NaN
        s.shapeBevelSegments = 40
        assertEquals(0.5f, s.curveBevelAmount)
        assertEquals(1, s.curveBevelSegments)
        assertEquals(0.15f, s.shapeBevelAmount)
        assertEquals(8, s.shapeBevelSegments)
        app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit()
            .putFloat("curve.bevel.amount", -1f).putInt("curve.bevel.segments", -3)
            .putFloat("shape.bevel.amount", Float.POSITIVE_INFINITY).putInt("shape.bevel.segments", 9)
            .commit()
        val read = AppSettings(app)
        assertEquals(0.01f, read.curveBevelAmount)
        assertEquals(1, read.curveBevelSegments)
        assertEquals(0.15f, read.shapeBevelAmount)
        assertEquals(8, read.shapeBevelSegments)
    }

    @Test
    fun theToolOptionsKeepTheirNewFields() {
        val s = AppSettings(app)
        for (curve in listOf(CurveSettings(ownLayer = true), CurveSettings(), CurveSettings(ownLayer = true, closed = true, tension = 2f))) {
            s.putObject("vec.path", CurveSettings.serializer(), curve)
            val back = AppSettings(app).getObject("vec.path", CurveSettings.serializer())
            assertEquals(curve, back)
            assertEquals("sanitized keeps it", curve.ownLayer, back!!.sanitized().ownLayer)
        }
        for (shape in listOf(ShapeSettings(newLayerEachTime = true), ShapeSettings(), ShapeSettings(newLayerEachTime = true, editable = false))) {
            s.putObject("vec.shape", ShapeSettings.serializer(), shape)
            val back = AppSettings(app).getObject("vec.shape", ShapeSettings.serializer())
            assertEquals(shape, back)
            assertEquals("sanitized keeps it", shape.newLayerEachTime, back!!.sanitized().newLayerEachTime)
        }
        val drawing = ShapeSettings(newLayerEachTime = true, fromCenter = true)
        val placed = ShapeSettings(strokeWidth = 3f).withBehaviourOf(drawing)
        assertTrue("a behaviour option, carried with the others", placed.newLayerEachTime)
        assertTrue(placed.fromCenter)
        assertEquals(3f, placed.strokeWidth)
        assertFalse(ShapeSettings(newLayerEachTime = true).withBehaviourOf(ShapeSettings()).newLayerEachTime)
    }
}
