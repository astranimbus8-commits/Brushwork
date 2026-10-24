package com.brushwork.paint.v110.a5

import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 (A5, R8): the documents the group snapping tests share. The view is the identity at
 * density 1, so document px are screen px: the snap distance is 8 px, the touch slop 6 px, a
 * gizmo handle reaches 22 px and a point is grabbed within 22 (Shape) or 24 (Curve) px.
 *
 * Every layer here is empty, so the only snap targets are the canvas (its edges and centre
 * lines) and the points that do not move. Each case puts exactly one target within reach of
 * one moving point (5 px or less) and keeps every other candidate 10 px or more from any target
 * on both axes, so "the smallest correction wins" leaves one answer.
 */
internal class GroupSnapFixtures {
    private val scopes = ArrayList<CoroutineScope>()

    /** Cancels the editors' scopes (call from `@After`). */
    fun release() {
        for (s in scopes) s.cancel()
        scopes.clear()
    }

    private fun controller(w: Int, h: Int, vectorLayer: Boolean): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        if (vectorLayer) {
            doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
            doc.activeLayerIndex = 1
        }
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        return EditorController(app, doc, scope, settings).also {
            it.color = 0xFF203080.toInt()
            it.viewTransform.set(Matrix())
            it.tools
        }
    }

    /**
     * A 200 x 200 canvas (targets x / y 0, 100, 200) with a pending sharp rectangle
     * 60..140 x 70..130 in Points mode (drawn with snapping off), its top left and bottom right
     * points selected; snapping is on afterwards. The unselected top right (140, 70) and bottom
     * left (60, 130) are "Vertex" targets.
     */
    fun shapeGroup(): Pair<EditorController, ShapeTool> {
        val c = controller(200, 200, vectorLayer = false)
        c.snapping.enabled = false
        c.selectTool(ToolId.SHAPE)
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f,
                corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false, snapAngle = false,
            )
        }
        c.drag(Vec2(60f, 70f), Vec2(100f, 100f), Vec2(140f, 130f))
        tool.setPointEditing(true)
        assertEquals(4, tool.pointCount)
        tool.selectPoints(PointSelection.of(4, shapeIndex(tool, SHAPE_TL), shapeIndex(tool, SHAPE_BR)))
        c.snapping.enabled = true
        return c to tool
    }

    /** The index of the shape point at [p]. */
    fun shapeIndex(tool: ShapeTool, p: Vec2): Int {
        val i = tool.docAnchors()!!.indexOfFirst { it.pos.distanceTo(p) < 0.01f }
        assertTrue("a point at $p in ${tool.docAnchors()?.map { it.pos }}", i >= 0)
        return i
    }

    /** Where shape point [index] is now (document px). */
    fun shapePoint(tool: ShapeTool, index: Int): Vec2 = tool.docAnchors()!![index].pos

    /**
     * A 400 x 300 canvas (targets x 0, 200, 400 and y 0, 150, 300) with the [id] tool's pending
     * path [CURVE_POINTS] on the empty "Vector 1" layer, points 0 and 2 selected and snapping on.
     * The unselected point 1 (250, 260) is a "Point" target.
     */
    fun curveGroup(id: ToolId): Pair<EditorController, CurveTool> {
        val c = controller(400, 300, vectorLayer = true)
        c.snapping.enabled = true
        c.selectTool(id)
        val tool = c.tools.getValue(id) as CurveTool
        for (p in CURVE_POINTS) assertTrue("$id adds $p", tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.of(3, 0, 2))
        assertEquals("$id", CURVE_POINTS, (0 until 3).map { tool.pointAt(it) })
        return c to tool
    }

    /**
     * A 400 x 300 canvas with the [id] tool's pending path through [pts] (smooth points, in that
     * order) on the empty "Vector 1" layer, nothing selected, snapping off.
     */
    fun curve(id: ToolId, pts: List<Vec2>): Pair<EditorController, CurveTool> {
        val c = controller(400, 300, vectorLayer = true)
        c.snapping.enabled = false
        c.selectTool(id)
        val tool = c.tools.getValue(id) as CurveTool
        for (p in pts) assertTrue("$id adds $p", tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.none(pts.size))
        assertEquals("$id", pts, pts.indices.map { tool.pointAt(it) })
        return c to tool
    }

    companion object {
        val SHAPE_TL = Vec2(60f, 70f)
        val SHAPE_BR = Vec2(140f, 130f)

        /**
         * The group moves 57 px right: the bottom right point ends at x 197, 3 px from the canvas
         * edge (x 200). The top left ends at x 117 (canvas centre 100 is 17 px off, the vertex
         * line x 140 is 23 px off) and the box centre at 157 (17 px from x 140): only the bottom
         * right point is within reach. Snapped as a group, both points move 60 px.
         */
        val SHAPE_MOVE = Vec2(57f, 0f)

        /** 0 and 2 are selected; 1 stays put (a "Point" target at x 250, y 260). */
        val CURVE_POINTS = listOf(Vec2(40f, 200f), Vec2(250f, 260f), Vec2(300f, 220f))

        /**
         * Points 0 and 2 move 97 px right: point 2 ends at x 397, 3 px from the canvas edge
         * (x 400). Point 0 ends at x 137 (63 px from x 200) and the box centre at 267 (17 px from
         * point 1's x 250): only point 2 is within reach. On y nothing is (200, 210 and 220 against
         * 150, 260 and 300). Snapped as a group, both points move 100 px.
         */
        val CURVE_MOVE = Vec2(97f, 0f)

        val CURVE_KINDS = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)

        fun idle() = shadowOf(Looper.getMainLooper()).idle()

        /** A one-finger drag through [pts] (down at the first, up at the last). */
        fun EditorController.drag(vararg pts: Vec2) {
            pointerDown(ToolPoint(pts[0].x, pts[0].y))
            for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].x, pts[i].y))
            pointerUp(ToolPoint(pts.last().x, pts.last().y))
            idle()
        }

        /** A drag from [from] by [by], past the touch slop first. */
        fun EditorController.dragBy(from: Vec2, by: Vec2) = drag(from, from + Vec2(10f, 0f), from + by * 0.5f, from + by)

        fun assertVec(message: String, expected: Vec2, actual: Vec2, eps: Float = 0.01f) {
            assertEquals("$message x of $actual", expected.x, actual.x, eps)
            assertEquals("$message y of $actual", expected.y, actual.y, eps)
        }
    }
}
