package com.brushwork.paint.assist

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.GridSettings
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.RulerSettings
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.model.StabilizerMode
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** Renderers and controller glue on real Skia (Robolectric NATIVE graphics). */
@RunWith(RobolectricTestRunner::class)
class AssistRobolectricTest {

    private fun doc(w: Int, h: Int) = Document("t", "t", w, h).also { it.layers += Layer(1, "l", BitmapUtils.createLayerBitmap(w, h)) }

    private fun transform(scale: Float, dx: Float = 0f, dy: Float = 0f, rotate: Float = 0f) = ViewTransform().apply {
        set(Matrix().apply { setScale(scale, scale); postRotate(rotate); postTranslate(dx, dy) })
        density = 1f
    }

    private fun alphaAt(bmp: Bitmap, x: Int, y: Int) = Color.alpha(bmp.getPixel(x, y))

    /** Ink at screen x = [x] (a hairline on a pixel boundary may land on either side). */
    private fun inkNear(bmp: Bitmap, x: Int, y: Int) = maxOf(alphaAt(bmp, x - 1, y), alphaAt(bmp, x, y))

    private fun inkedColumns(bmp: Bitmap, y: Int) = (0 until bmp.width).count { alphaAt(bmp, it, y) > 0 }

    private val red = 0xFFFF0000.toInt()

    @Test
    fun squareGridDrawsMinorAndMajorLines() {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        val grid = GridSettings(enabled = true, spacingPx = 20f, majorEvery = 5, color = red, opacity = 1f)
        GridRenderer.draw(Canvas(bmp), transform(1f), doc(200, 100), grid)
        assertEquals(255, inkNear(bmp, 100, 50))           // major line (every 5th)
        assertEquals(128f, inkNear(bmp, 20, 50).toFloat(), 2f) // minor line at half the opacity
        assertEquals(0, alphaAt(bmp, 30, 50))              // between lines
        assertEquals(0, alphaAt(bmp, 10, 30))
        assertTrue(maxOf(alphaAt(bmp, 10, 39), alphaAt(bmp, 10, 40)) > 0) // horizontal line at y = 40
        assertEquals(Color.RED, bmp.getPixel(if (alphaAt(bmp, 100, 50) > 0) 100 else 99, 50))
    }

    @Test
    fun gridSkipsDenseLinesWhenZoomedOut() {
        // 20 px spacing at 10% zoom = 2 px on screen: minor lines are skipped, bold lines (every 4 = 8 px) stay.
        val d = doc(2000, 1000)
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        GridRenderer.draw(Canvas(bmp), transform(0.1f), d, GridSettings(enabled = true, spacingPx = 20f, majorEvery = 4, color = red, opacity = 1f))
        assertTrue(inkedColumns(bmp, 50) in 20..30)
        // Without bold lines every line is equal: they are thinned to every 4th line (8 px).
        bmp.eraseColor(0)
        GridRenderer.draw(Canvas(bmp), transform(0.1f), d, GridSettings(enabled = true, spacingPx = 20f, majorEvery = 0, color = red, opacity = 1f))
        assertTrue(inkedColumns(bmp, 50) in 20..30)
    }

    @Test
    fun gridIsClippedToTheCanvas() {
        val bmp = Bitmap.createBitmap(200, 100, Bitmap.Config.ARGB_8888)
        GridRenderer.draw(Canvas(bmp), transform(1f, dx = 50f), doc(100, 100), GridSettings(enabled = true, spacingPx = 10f, majorEvery = 0, color = red, opacity = 1f))
        for (x in 0 until 49) assertEquals(0, alphaAt(bmp, x, 55))
        for (x in 151 until 200) assertEquals(0, alphaAt(bmp, x, 55))
        assertTrue(inkedColumns(bmp, 55) > 0)
    }

    @Test
    fun otherGridTypesAndRotatedViewsDraw() {
        for (type in GridType.entries) {
            val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
            GridRenderer.draw(Canvas(bmp), transform(1f, dx = 150f, dy = 20f, rotate = 30f), doc(200, 200), GridSettings(enabled = true, type = type, spacingPx = 25f, color = red, opacity = 1f))
            assertTrue("$type drew nothing", (0 until 300).any { y -> inkedColumns(bmp, y) > 0 })
        }
        // Rule of thirds at identity: vertical line at x = 100 of a 300 px canvas.
        val bmp = Bitmap.createBitmap(300, 300, Bitmap.Config.ARGB_8888)
        GridRenderer.draw(Canvas(bmp), transform(1f), doc(300, 300), GridSettings(enabled = true, type = GridType.RULE_OF_THIRDS, color = red, opacity = 1f))
        assertEquals(255, inkNear(bmp, 100, 50))
        assertEquals(0, alphaAt(bmp, 50, 50))
    }

    @Test
    fun rulerRendersGuideAndHandles() {
        val d = doc(200, 200)
        val straight = RulerSettings(enabled = true, type = RulerType.STRAIGHT, centerX = 100f, centerY = 100f)
        val bmp = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        RulerRenderer.draw(Canvas(bmp), transform(1f), d, straight, editing = false)
        assertTrue(alphaAt(bmp, 5, 100) > 0)
        assertEquals(0, alphaAt(bmp, 188, 93))
        RulerRenderer.draw(Canvas(bmp), transform(1f), d, straight, editing = true)
        assertTrue(alphaAt(bmp, 188, 95) > 0) // rotation handle 88 dp from the center

        val circle = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
        RulerRenderer.draw(Canvas(circle), transform(1f), d, straight.copy(type = RulerType.CIRCLE, radius = 50f), editing = false)
        assertTrue(alphaAt(circle, 150, 100) > 0)
        assertTrue(alphaAt(circle, 100, 100) > 0) // center mark
        assertEquals(0, alphaAt(circle, 125, 100))

        for (type in listOf(RulerType.ELLIPSE, RulerType.RADIAL)) {
            val b = Bitmap.createBitmap(200, 200, Bitmap.Config.ARGB_8888)
            RulerRenderer.draw(Canvas(b), transform(0.5f, rotate = 20f, dx = 100f), d, straight.copy(type = type, angleDeg = 30f), editing = true)
            assertTrue((0 until 200).any { y -> inkedColumns(b, y) > 0 })
        }
    }

    private fun controller(w: Int = 1000, h: Int = 1000): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        return EditorController(ctx, doc(w, h), CoroutineScope(SupervisorJob()), AppSettings(ctx))
    }

    @Test
    fun strokeAssistConvertsScreenSettingsWithTheZoom() {
        val c = controller()
        c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
        c.viewTransform.density = 1f
        assertEquals(500f, c.ruler.centerX, 0f) // placed on the canvas center by the controller
        c.updateRuler(c.ruler.copy(enabled = true, type = RulerType.CIRCLE, radius = 200f))
        c.updateStabilizer(StabilizerSettings(mode = StabilizerMode.ROPE, ropeLengthDp = 60f, catchUp = true))
        val sa = c.strokeAssist

        // 15 doc px from the ruler = 30 dp at 200%: within the 40 dp snap distance.
        val s = sa.down(ToolPoint(715f, 500f))
        assertEquals(200f, hypot(s.x - 500f, s.y - 500f), 1e-3f)
        sa.cancel()

        // 35 doc px = 70 dp away: concentric circle through the start instead.
        sa.down(ToolPoint(735f, 500f))
        val pts = ArrayList<ToolPoint>()
        for (i in 1..30) {
            val a = i * 0.04f
            val out = sa.move(ToolPoint(500f + 240f * cos(a), 500f + 240f * sin(a)))
            if (out.isNotEmpty()) {
                // Rope of 60 dp = 30 doc px at 200% zoom, measured to the projected finger.
                val fx = 500f + 235f * cos(a); val fy = 500f + 235f * sin(a)
                assertEquals(30f, hypot(fx - out.last().x, fy - out.last().y), 0.5f)
            }
            pts += out
        }
        // The finger is at about (1170, 1438) on screen: look at that corner of the view.
        val overlay = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        sa.drawOverlay(Canvas(overlay).apply { translate(-1000f, -1250f) }, c.viewTransform)
        pts += sa.up(ToolPoint(500f + 240f * cos(1.2f), 500f + 240f * sin(1.2f)))
        assertTrue(pts.size > 30)
        for (p in pts) assertEquals(235f, hypot(p.x - 500f, p.y - 500f), 0.05f)
        assertTrue((0 until 400 step 2).any { y -> inkedColumns(overlay, y) > 0 })
        val after = Bitmap.createBitmap(400, 400, Bitmap.Config.ARGB_8888)
        sa.drawOverlay(Canvas(after).apply { translate(-1000f, -1250f) }, c.viewTransform)
        assertTrue((0 until 400 step 2).none { y -> inkedColumns(after, y) > 0 }) // no rope once lifted
    }

    @Test
    fun rulerToolEditsAndCancelRestores() {
        val c = controller()
        c.viewTransform.set(Matrix().apply { setScale(2f, 2f) })
        c.viewTransform.density = 1f
        c.updateRuler(RulerSettings(enabled = false, type = RulerType.STRAIGHT, centerX = 500f, centerY = 500f))
        val tool = RulerTool(c)
        tool.onActivate()
        assertTrue(c.ruler.enabled)
        assertFalse(tool.hasPendingWork)

        // The rotation handle sits 88 dp = 44 doc px from the center at 200%.
        tool.onDown(ToolPoint(544f, 500f))
        tool.onMove(ToolPoint(520f, 530f))
        tool.onUp(ToolPoint(500f, 544f))
        assertEquals(90f, c.ruler.angleDeg, 1e-3f)

        val before = c.ruler
        tool.onDown(ToolPoint(700f, 700f)) // off the handles: moves the ruler
        tool.onMove(ToolPoint(730f, 690f))
        assertEquals(530f, c.ruler.centerX, 1e-3f)
        assertEquals(490f, c.ruler.centerY, 1e-3f)
        tool.onCancel()
        assertEquals(before, c.ruler)
        assertEquals(before, c.doc.ruler)
    }
}
