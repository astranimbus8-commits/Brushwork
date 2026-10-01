package com.brushwork.paint.assist

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.RulerType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapEdge
import com.brushwork.paint.tools.transform.SnapHit
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapSource
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * "Snap to objects" of the ruler tool: its center (handle or body drag) and its radius / semi-axis
 * handles snap so the ruler can sit exactly on a layer's edge, a drawn line or the canvas center.
 */
@RunWith(RobolectricTestRunner::class)
class RulerSnapRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    /** A 400 x 300 canvas at zoom 1, density 1 (snap distance 8 px); "Layer 1" has content at (100, 60)-(160, 120). */
    private fun setup(): Pair<EditorController, RulerTool> {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 400, 300)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(400, 300)) }
        doc.activeLayerIndex = 1
        Canvas(doc.layers[0].bitmap).drawRect(Rect(100, 60, 160, 120), Paint().apply { color = 0xFF000000.toInt() })
        doc.layers[0].markChanged()
        val c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectTool(ToolId.RULER)
        return c to (c.tools.getValue(ToolId.RULER) as RulerTool)
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun center(c: EditorController) = Vec2(c.ruler.centerX, c.ruler.centerY)

    @Test
    fun theCenterHandleSnapsToALayersCornerWithGuidesUntilTheFingerLifts() {
        val (c, tool) = setup()
        assertEquals(Vec2(200f, 150f), center(c))
        c.pointerDown(ToolPoint(200f, 150f))
        c.pointerMove(ToolPoint(180f, 140f))
        c.pointerMove(ToolPoint(163f, 117f))
        assertEquals(Vec2(160f, 120f), center(c))
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 160f })
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.Y && it.pos == 120f })
        c.pointerUp(ToolPoint(163f, 117f))
        assertEquals(Vec2(160f, 120f), center(c))
        assertTrue("guides are hidden when the finger lifts", tool.activeGuides.isEmpty())
        // A cancelled drag puts the ruler back and hides the guides.
        c.pointerDown(ToolPoint(160f, 120f))
        c.pointerMove(ToolPoint(190f, 140f))
        c.pointerMove(ToolPoint(197f, 147f))
        assertEquals(Vec2(200f, 150f), center(c))
        c.pointerCancel()
        assertEquals(Vec2(160f, 120f), center(c))
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun aTapNeverMovesTheRulerAndABodyDragSnapsItsCenter() {
        val (c, _) = setup()
        c.updateRuler(c.ruler.copy(centerX = 163f, centerY = 117f))
        c.pointerDown(ToolPoint(163f, 117f)); c.pointerUp(ToolPoint(163f, 117f))
        assertEquals(Vec2(163f, 117f), center(c))
        // Grabbed away from its handles (the body): the center follows relatively, then snaps.
        c.drag(203f to 117f, 220f to 140f, 237f to 148f)
        assertEquals(Vec2(200f, 150f), center(c))
        // Snapping off: exactly as before.
        c.snapping.enabled = false
        c.drag(200f to 150f, 180f to 140f, 163f to 117f)
        assertEquals(Vec2(163f, 117f), center(c))
    }

    @Test
    fun theCircleRadiusSnapsSoTheCircleTouchesTheClosestLine() {
        val (c, tool) = setup()
        c.updateRuler(c.ruler.copy(type = RulerType.CIRCLE, centerX = 200f, centerY = 150f, radius = 50f))
        c.pointerDown(ToolPoint(250f, 150f))
        c.pointerMove(ToolPoint(270f, 150f))
        c.pointerMove(ToolPoint(296f, 150f))
        // 96: its left side (104) is 4 px from the layer's left edge (its top, 54, is 6 px from 60).
        assertEquals(100f, c.ruler.radius, 1e-3f)
        assertTrue(tool.activeGuides.any { it.axis == SnapAxis.X && it.pos == 100f })
        c.pointerUp(ToolPoint(296f, 150f))
        assertEquals(100f, c.ruler.radius, 1e-3f)
        assertTrue(tool.activeGuides.isEmpty())
    }

    @Test
    fun anEllipseSemiAxisSnapsAlongItsAxis() {
        val (c, _) = setup()
        c.updateRuler(c.ruler.copy(type = RulerType.ELLIPSE, centerX = 200f, centerY = 150f, angleDeg = 0f, radiusX = 80f, radiusY = 40f))
        c.drag(280f to 150f, 330f to 150f, 397f to 150f)
        assertEquals("both ends on the canvas edges", 200f, c.ruler.radiusX, 1e-3f)
        assertEquals(40f, c.ruler.radiusY, 1e-3f)
        // The other semi-axis: its ends 4 px from the canvas' bottom and top edges.
        c.drag(200f to 190f, 200f to 230f, 200f to 296f)
        assertEquals(150f, c.ruler.radiusY, 1e-3f)
    }

    // ------------------------------------------------------------------ pure math

    private fun lines(vararg xs: Float, axis: SnapAxis): List<SnapLine> =
        xs.map { SnapLine(axis, it, SnapEdge.CENTER, SnapSource.OBJECT, "L", 0f, 1f) }

    private fun snapper(xs: List<SnapLine>, ys: List<SnapLine>, threshold: Float = 8f): (Float, SnapAxis) -> SnapHit? = { v, axis ->
        (if (axis == SnapAxis.X) xs else ys).map { SnapHit(it, abs(it.pos - v)) }.filter { it.distance <= threshold }.minByOrNull { it.distance }
    }

    @Test
    fun radiusMathPicksTheClosestSideAndSolvesAlongTurnedAxes() {
        val xs = lines(105f, axis = SnapAxis.X)
        val ys = lines(54f, axis = SnapAxis.Y)
        val s = snapper(xs, ys)
        // Circle at (200, 150), r 96: its top (54) lies on a line and beats its left side (104, 1 px off 105).
        val hit =RulerHandleSnap.radius(Vec2(200f, 150f), 96f, listOf(Vec2(1f, 0f), Vec2(0f, 1f)), 1f, s)!!
        assertEquals(96f, hit.first, 1e-3f)
        assertEquals(Vec2(200f, 54f), hit.second)
        // Nothing within reach.
        assertNull(RulerHandleSnap.radius(Vec2(200f, 150f), 60f, listOf(Vec2(1f, 0f), Vec2(0f, 1f)), 1f, s))
        // A semi-axis turned 60°: only the axis it moves along well enough snaps (x: 0.5, y: 0.87).
        val u = Vec2(0.5f, 0.8660254f)
        val t = RulerHandleSnap.radius(Vec2(0f, 0f), 100f, listOf(u), 1f, snapper(lines(53f, axis = SnapAxis.X), emptyList()))!!
        assertEquals(106f, t.first, 1e-3f)
        assertEquals(53f, t.second.x, 1e-3f)
        // A radius below the minimum is refused.
        assertNull(RulerHandleSnap.radius(Vec2(0f, 0f), 3f, listOf(Vec2(1f, 0f)), 1f, snapper(lines(-0.5f, axis = SnapAxis.X), emptyList())))
    }
}
