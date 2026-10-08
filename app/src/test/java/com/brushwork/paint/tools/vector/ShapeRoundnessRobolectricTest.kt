package com.brushwork.paint.tools.vector

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.Mixed
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.7 item 2 (design §3.2, area C): "Point roundness" of the selected Shape corners. Two corners
 * set to 30 px are ONE in-tool step (typed, set or scrubbed), `*2` doubles each, "Reset point
 * roundness" gives them back to the shape's "Corner radius", only corners between straight sides
 * can be rounded, and a shape object on a vector layer keeps its points' radii through save and
 * load.
 */
@RunWith(RobolectricTestRunner::class)
class ShapeRoundnessRobolectricTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun releaseEditors() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private val w = 240
    private val h = 200

    /** A raster layer and a vector layer (active), identity view. */
    private fun controller(): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        ctx.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
        val d = Document("round", "round", w, h)
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 1
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Unconfined).also { scopes += it }
        return EditorController(ctx, d, scope, AppSettings(ctx)).also {
            it.viewTransform.set(Matrix())
            it.snapping.enabled = false
            it.tools
            it.brush = BrushLibrary.defaultBrush.copy(size = 6f, pressureSize = false, taperStart = 0f, taperEnd = 0f)
        }
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** A pending sharp rectangle 50..150 × 50..130 in Points mode, its top left and bottom right corners selected. */
    private fun twoCorners(c: EditorController): ShapeTool {
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f,
                corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false,
            )
        }
        c.drag(50f to 50f, 100f to 90f, 150f to 130f)
        tool.setPointEditing(true)
        assertEquals(4, tool.pointCount)
        tool.selectPoints(PointSelection.of(4, at(tool, 50f, 50f), at(tool, 150f, 130f)))
        return tool
    }

    private fun at(tool: ShapeTool, x: Float, y: Float): Int {
        val i = tool.docAnchors()!!.indexOfFirst { it.pos.distanceTo(Vec2(x, y)) < 0.01f }
        assertTrue("a point at $x, $y", i >= 0)
        return i
    }

    /** The radius of the point at ([x], [y]) (null: the shape's own). */
    private fun radiusAt(tool: ShapeTool, x: Float, y: Float): Float? = tool.docAnchors()!![at(tool, x, y)].radius

    private fun assertRadii(tool: ShapeTool, tl: Float?, tr: Float?, br: Float?, bl: Float?) {
        assertEquals("top left", tl, radiusAt(tool, 50f, 50f))
        assertEquals("top right", tr, radiusAt(tool, 150f, 50f))
        assertEquals("bottom right", br, radiusAt(tool, 150f, 130f))
        assertEquals("bottom left", bl, radiusAt(tool, 50f, 130f))
    }

    /** The shortest distance from [p] to [o]'s outline (document px). */
    private fun distanceToOutline(o: ShapeObject, p: Vec2): Float {
        var best = Float.MAX_VALUE
        for (poly in ShapeOutlines.outline(o).flatten(0.01f)) {
            val pts = poly.points
            for (i in 1 until pts.size) best = minOf(best, Geometry.distanceToSegment(p, pts[i - 1], pts[i]))
            if (poly.closed && pts.size > 2) best = minOf(best, Geometry.distanceToSegment(p, pts.last(), pts[0]))
        }
        return best
    }

    @Test
    fun twoCornersSetToThirtyAreOneStep() {
        val c = controller()
        val tool = twoCorners(c)
        assertEquals(Mixed.Same(0f), tool.pointRoundness)
        assertFalse(tool.canResetPointRoundness)
        assertTrue(tool.typePointRoundness("30"))
        assertRadii(tool, 30f, null, 30f, null)
        assertEquals(Mixed.Same(30f), tool.pointRoundness)
        assertTrue(tool.canResetPointRoundness)
        // ONE in-tool step back, the selection with it.
        assertTrue(tool.undoStep())
        assertRadii(tool, null, null, null, null)
        assertEquals(2, tool.pointSelection.count)
        assertTrue(tool.redoStep())
        assertRadii(tool, 30f, null, 30f, null)
        // A relative value applies to each corner: *2 on 10 and 30 gives 20 and 60.
        tool.selectPoints(PointSelection.of(4, at(tool, 50f, 50f)))
        tool.setPointRoundness(10f)
        tool.selectPoints(PointSelection.of(4, at(tool, 50f, 50f), at(tool, 150f, 130f)))
        assertEquals(Mixed.Spread(10f, 30f), tool.pointRoundness)
        assertTrue(tool.typePointRoundness("*2"))
        assertRadii(tool, 20f, null, 60f, null)
        assertTrue(tool.undoStep())
        assertRadii(tool, 10f, null, 30f, null)
        // Invalid text changes nothing.
        assertFalse(tool.typePointRoundness("abc"))
        assertRadii(tool, 10f, null, 30f, null)
        // "Reset point roundness": back to the shape's own (null), one step.
        tool.resetPointRoundness()
        assertRadii(tool, null, null, null, null)
        assertTrue(tool.undoStep())
        assertRadii(tool, 10f, null, 30f, null)
    }

    @Test
    fun aScrubIsOneStepAndClampsToFiveHundred() {
        val c = controller()
        val tool = twoCorners(c)
        tool.setPointRoundness(10f)
        tool.selectPoints(PointSelection.of(4, at(tool, 50f, 50f), at(tool, 150f, 50f)))
        // The scrub adds the same amount to each (10 and 0), as one step.
        tool.beginPointRoundness()
        tool.dragPointRoundness(10f, 15f)
        tool.dragPointRoundness(10f, 30f)
        tool.endPointRoundness()
        assertRadii(tool, 30f, 20f, 10f, null)
        assertTrue(tool.undoStep())
        assertRadii(tool, 10f, null, 10f, null)
        // A typed 1 000 is kept at 500; the outline cuts it to half the shorter side.
        tool.setPointRoundness(1000f)
        assertEquals(Mixed.Same(ShapeRoundness.MAX), tool.pointRoundness)
    }

    @Test
    fun theRoundedCornersAreArcsInTheCommittedShape() {
        val c = controller()
        val tool = twoCorners(c)
        tool.setPointRoundness(30f)
        tool.commit()
        val o = (c.doc.layers[1].vector!!.objects.single() as VShape).shape
        // An arc of radius 30 passes r(√2 − 1) from the corner it rounds; the others stay sharp.
        val arc = 30f * (Math.sqrt(2.0).toFloat() - 1f)
        assertEquals(arc, distanceToOutline(o, Vec2(50f, 50f)), 0.5f)
        assertEquals(arc, distanceToOutline(o, Vec2(150f, 130f)), 0.5f)
        assertEquals(0f, distanceToOutline(o, Vec2(150f, 50f)), 0.05f)
        assertEquals(0f, distanceToOutline(o, Vec2(50f, 130f)), 0.05f)
        // One app step for the whole shape.
        assertEquals(1, c.undoManager.undoCount)
    }

    @Test
    fun onlyCornersBetweenStraightSidesCanBeRounded() {
        val c = controller()
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f, keepProportions = false, fromCenter = false) }
        c.drag(50f to 50f, 150f to 130f)
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.all(tool.pointCount))
        assertNull(tool.pointRoundness)
        assertEquals(0, tool.roundnessTargets().size)
        val canUndo = tool.canUndoStep
        val before = tool.points
        tool.setPointRoundness(30f)
        assertFalse(tool.typePointRoundness("30"))
        assertEquals(before, tool.points)
        assertEquals(canUndo, tool.canUndoStep)
    }

    @Test
    fun aShapeObjectKeepsItsRadiiThroughSaveAndLoad() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val c = controller()
        val tool = twoCorners(c)
        tool.setPointRoundness(30f)
        tool.commit()
        val shape = (c.doc.layers[1].vector!!.objects.single() as VShape).shape
        val radii = shape.points!!.map { it.radius }
        assertEquals(listOf(30f, 30f), radii.filterNotNull())
        val repo = ProjectRepository(app)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        val back = (loaded.layers[1].vector!!.objects.single() as VShape).shape
        assertEquals(radii, back.points!!.map { it.radius })
        assertEquals(shape, back)
        assertTrue(loaded.loadWarnings.isEmpty())
        // The loaded layer draws like the original.
        val a = IntArray(w * h).also { c.doc.layers[1].bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { loaded.layers[1].bitmap.getPixels(it, 0, w, 0, 0, w, h) }
        assertArrayEquals(a, b)
    }
}
