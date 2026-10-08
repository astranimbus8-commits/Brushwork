package com.brushwork.paint.tools.vector

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.7 (item 1, design §3.1(d)): several points of the Curve, Polyline and Path tools at once.
 * The selection is a [PointSelection]; a group edit is ONE in-tool step; in-tool undo brings the
 * points and the selection back; thickness and weight take N points; the group is mapped from
 * the points captured at its start (tangent handles as vectors); item 19 still extends a path
 * from its first point.
 */
@RunWith(RobolectricTestRunner::class)
class CurveMultiPointRobolectricTest {

    private val w = 400
    private val h = 300

    private fun controller(vector: Boolean = true): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), if (vector) "Vector 1" else "Layer 2", BitmapUtils.createLayerBitmap(w, h)).also {
            if (vector) it.vector = VectorContent.EMPTY
        }
        doc.activeLayerIndex = 1
        return EditorController(ctx, doc, CoroutineScope(SupervisorJob() + Dispatchers.Unconfined), AppSettings(ctx)).also {
            it.color = 0xFF203080.toInt()
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
            it.snapping.enabled = false
        }
    }

    private fun EditorController.tool(id: ToolId): CurveTool {
        selectTool(id)
        return tools.getValue(id) as CurveTool
    }

    private val five = listOf(Vec2(40f, 60f), Vec2(100f, 40f), Vec2(160f, 80f), Vec2(220f, 50f), Vec2(280f, 90f))

    /** A tool with the five points [five] (each added after the last). */
    private fun withFive(id: ToolId, vector: Boolean = true): Pair<EditorController, CurveTool> {
        val c = controller(vector)
        val t = c.tool(id)
        for (p in five) assertTrue(t.addAnchor(p))
        t.deselect()
        assertEquals(5, t.pointCount)
        return c to t
    }

    private fun steps(t: CurveTool): Int {
        var n = 0
        while (t.undoStep()) n++
        repeat(n) { t.redoStep() }
        return n
    }

    @Test
    fun theSelectionTogglesAndOnePointStaysV16() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val (_, t) = withFive(id)
            t.select(2)
            assertEquals("$id", PointSelection.of(5, 2), t.pointSelection)
            assertEquals("$id", 2, t.selectedIndex)
            t.selectPoints(t.pointSelection.toggled(4).toggled(0))
            assertEquals("$id", listOf(0, 2, 4), t.pointSelection.indices)
            // The primary is the point selected last.
            assertEquals("$id", 0, t.selectedIndex)
            t.selectPoints(t.pointSelection.toggled(0))
            assertEquals("$id", listOf(2, 4), t.pointSelection.indices)
            t.toggleSelectAll()
            assertEquals("$id", 5, t.pointSelection.count)
            t.toggleSelectAll()
            assertTrue("$id", t.pointSelection.isEmpty)
            assertEquals("$id", -1, t.selectedIndex)
        }
    }

    @Test
    fun aGroupDragIsOneInToolStepAndUndoBringsBackThePointsAndTheSelection() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val (_, t) = withFive(id)
            val before = (0 until 5).map { t.pointAt(it) }
            val stepsBefore = steps(t)
            t.selectPoints(PointSelection.of(5, 1, 3))
            t.beginGroupEdit("Move points")
            t.setGroupTransform(Affine2.translate(10f, 0f))
            t.setGroupTransform(Affine2.translate(20f, 5f))
            t.endGroupEdit()
            for (i in 0 until 5) {
                val want = if (i == 1 || i == 3) before[i] + Vec2(20f, 5f) else before[i]
                assertEquals("$id point $i x", want.x, t.pointAt(i).x, 1e-4f)
                assertEquals("$id point $i y", want.y, t.pointAt(i).y, 1e-4f)
            }
            assertEquals("$id: one step", stepsBefore + 1, steps(t))
            // Another selection now; undo brings back the points and the two selected.
            t.select(4)
            assertTrue(t.undoStep())
            for (i in 0 until 5) assertEquals("$id point $i", before[i], t.pointAt(i))
            assertEquals("$id", listOf(1, 3), t.pointSelection.indices)
            assertTrue(t.redoStep())
            assertEquals("$id", before[1] + Vec2(20f, 5f), t.pointAt(1))
        }
    }

    @Test
    fun aGroupEditThatChangesNothingLeavesNoStep() {
        val (_, t) = withFive(ToolId.PATH)
        val n = steps(t)
        t.selectPoints(PointSelection.of(5, 0, 1))
        t.beginGroupEdit("Move points")
        t.setGroupTransform(Affine2.translate(30f, 0f))
        t.setGroupTransform(Affine2.IDENTITY)
        t.endGroupEdit()
        assertEquals(n, steps(t))
    }

    @Test
    fun thicknessZeroOnThreeOfFiveSetsExactlyThoseThree() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val (_, t) = withFive(id)
            val n = steps(t)
            t.selectPoints(PointSelection.of(5, 0, 2, 4))
            t.setWidths(t.pointSelection.indices, floatArrayOf(0f, 0f, 0f))
            assertEquals("$id", listOf(0f, 1f, 0f, 1f, 0f), (0 until 5).map { t.widthOf(it) })
            assertEquals("$id: one step", n + 1, steps(t))
            // Held to 0–300 %.
            t.setWidths(listOf(1, 3), floatArrayOf(-2f, 9f))
            assertEquals("$id", 0f, t.widthOf(1))
            assertEquals("$id", 3f, t.widthOf(3))
            t.select(1)
            assertTrue(t.undoStep())
            assertTrue(t.undoStep())
            assertEquals("$id", listOf(1f, 1f, 1f, 1f, 1f), (0 until 5).map { t.widthOf(it) })
            assertEquals("$id: the selection then", listOf(0, 2, 4), t.pointSelection.indices)
        }
    }

    @Test
    fun weightsOfSeveralPointsClampToTheSplineRange() {
        val (_, t) = withFive(ToolId.PATH)
        val n = steps(t)
        t.selectPoints(PointSelection.of(5, 1, 2, 3))
        t.setWeights(t.pointSelection.indices, floatArrayOf(0.001f, 1000f, 2f))
        assertEquals(VSpline.MIN_WEIGHT, t.weightOf(1))
        assertEquals(VSpline.MAX_WEIGHT, t.weightOf(2))
        assertEquals(2f, t.weightOf(3))
        assertEquals(1f, t.weightOf(0))
        assertEquals(1f, t.weightOf(4))
        assertEquals(n + 1, steps(t))
    }

    @Test
    fun everyPathPointMovedTogetherStillMatchesItsSpline() {
        val (_, t) = withFive(ToolId.PATH)
        t.setWidths(listOf(0, 4), floatArrayOf(0f, 0f))
        t.toggleSelectAll()
        t.beginGroupEdit("Scale")
        t.setGroupTransform(Affine2.scaleAbout(Vec2(160f, 65f), 1.5f, 0.75f))
        t.setGroupTransform(Affine2.rotateAbout(Vec2(160f, 65f), 30f) * Affine2.scaleAbout(Vec2(160f, 65f), 2f, 2f))
        t.endGroupEdit()
        val s = t.spline!!
        val sub = SplineBezier.toSubpath(s)
        assertEquals(sub.anchors.size, t.anchors.size)
        for (i in sub.anchors.indices) {
            assertEquals(sub.anchors[i].x, t.anchors[i].x)
            assertEquals(sub.anchors[i].y, t.anchors[i].y)
        }
        // Thickness is never scaled (§3.12).
        assertEquals(listOf(0f, 1f, 1f, 1f, 0f), (0 until 5).map { t.widthOf(it) })
    }

    @Test
    fun aCurveGroupRotationTurnsItsDraggedHandlesAsVectors() {
        val (_, t) = withFive(ToolId.CURVE)
        // Every handle becomes explicit (a handle scale makes automatic tangents explicit first).
        t.toggleSelectAll()
        t.applyHandleScale(150f)
        val before = t.anchors
        assertTrue(before.all { it.handleOut != null && it.handleIn != null })
        val pivot = Vec2(160f, 65f)
        t.beginGroupEdit("Rotate")
        t.setGroupTransform(Affine2.rotateAbout(pivot, 90f))
        t.endGroupEdit()
        for (i in before.indices) {
            val a = before[i]
            val b = t.anchors[i]
            // The anchor turned about the pivot ...
            assertEquals(pivot.x - (a.y - pivot.y), b.x, 1e-3f)
            assertEquals(pivot.y + (a.x - pivot.x), b.y, 1e-3f)
            // ... and its handle with it, keeping its length.
            val ho = a.handleOut!!
            assertEquals(-ho.y, b.handleOut!!.x, 1e-3f)
            assertEquals(ho.x, b.handleOut!!.y, 1e-3f)
            assertEquals(ho.length, b.handleOut!!.length, 1e-3f)
        }
    }

    @Test
    fun deletingSelectedPointsIsOneStepAndKeepsTwoPoints() {
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            val (_, t) = withFive(id)
            val n = steps(t)
            t.selectPoints(PointSelection.of(5, 0, 2, 3))
            assertTrue(t.deleteSelectedPoints())
            assertEquals("$id", 2, t.pointCount)
            assertEquals("$id", listOf(five[1], five[4]), (0 until 2).map { t.pointAt(it) })
            assertTrue("$id", t.pointSelection.isEmpty)
            assertEquals("$id", n + 1, steps(t))
            assertTrue(t.undoStep())
            assertEquals("$id", 5, t.pointCount)
            assertEquals("$id", listOf(0, 2, 3), t.pointSelection.indices)
            // One point would be left: refused.
            t.selectPoints(PointSelection.of(5, 0, 1, 2, 3))
            assertFalse("$id", t.deleteSelectedPoints())
            assertEquals("$id", 5, t.pointCount)
        }
    }

    @Test
    fun item19StillExtendsAnOpenPathFromItsFirstPoint() {
        val c = controller()
        val t = c.tool(ToolId.PATH)
        t.addAnchor(Vec2(100f, 100f))
        t.addAnchor(Vec2(200f, 100f))
        t.addAnchor(Vec2(300f, 120f))
        t.select(0)
        assertTrue(t.addAnchor(Vec2(50f, 150f)))
        assertEquals(Vec2(50f, 150f), t.pointAt(0))
        assertEquals(PointSelection.of(4, 0), t.pointSelection)
    }

    @Test
    fun aCancelledTouchKeepsTheGroupSelected() {
        val (c, t) = withFive(ToolId.PATH)
        t.selectPoints(PointSelection.of(5, 1, 3))
        c.pointerDown(ToolPoint(5f, 290f))
        c.pointerCancel()
        assertEquals(listOf(1, 3), t.pointSelection.indices)
        assertEquals(5, t.pointCount)
    }
}
