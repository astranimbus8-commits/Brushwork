package com.brushwork.paint.v110.a5

import android.graphics.Matrix
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_KINDS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_MOVE
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.CURVE_POINTS
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_BR
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_MOVE
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.SHAPE_TL
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.assertVec
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.dragBy
import com.brushwork.paint.v110.a5.GroupSnapFixtures.Companion.idle
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (A5 review; R8 (a) "Undo: unchanged", R12 (a) "Undo: one in-tool step per tap", (b) no
 * model change): what the area adds survives the editor's own round trips.
 * - A snapped group move (a drag of one of its points, and a drag inside the gizmo) is the drag's
 *   existing ONE in-tool step: one Undo puts every point back exactly, one Redo puts the snapped
 *   positions back exactly, in Shape's Points mode and in Curve, Polyline and Path.
 * - A snapped move and a corner made sharp from the pill's cell are committed by ✓ as one step and
 *   reopen exactly after saving (as closing and opening the artwork does, through
 *   [ProjectRepository]).
 * - A v1.9 artwork (a Curve path and a Path with sharp and smooth points) opens unchanged: its
 *   paths open in their tools with the cell reading their state, and ✕ leaves them, the step count
 *   and the saved artwork as they were.
 */
@RunWith(RobolectricTestRunner::class)
class GroupSnapAndSharpRoundTripTest {
    private val fx = GroupSnapFixtures()
    private val app get() = RuntimeEnvironment.getApplication()
    private val repo by lazy { ProjectRepository(app) }
    private val controllers = ArrayList<EditorController>()
    private val projects = ArrayList<String>()

    @After
    fun tearDown() {
        fx.release()
        for (c in controllers) runCatching { c.dispose() }
        for (id in projects) runCatching { runBlocking { repo.delete(id) } }
    }

    @Test
    fun aSnappedShapeGroupMoveIsOneStepThatUndoesAndRedoesExactly() {
        for (gizmo in listOf(false, true)) {
            val (c, tool) = fx.shapeGroup()
            val br = fx.shapeIndex(tool, SHAPE_BR)
            val tl = fx.shapeIndex(tool, SHAPE_TL)
            val start = tool.docAnchors()!!.map { it.pos }
            // A drag of the bottom right point, or inside the gizmo (its centre, 50 px from every point).
            c.dragBy(if (gizmo) Vec2(100f, 100f) else SHAPE_BR, SHAPE_MOVE)
            val moved = tool.docAnchors()!!.map { it.pos }
            val how = if (gizmo) "the gizmo" else "a point"
            assertVec("$how: the bottom right snapped to the canvas edge", Vec2(200f, SHAPE_BR.y), moved[br])
            assertVec("$how: the top left moved with it", SHAPE_TL + Vec2(60f, 0f), moved[tl])
            assertTrue("$how: undo", tool.undoStep())
            assertEquals("$how: one Undo puts every point back exactly", start, tool.docAnchors()!!.map { it.pos })
            assertTrue("$how: redo", tool.redoStep())
            assertEquals("$how: one Redo gives the snapped move back exactly", moved, tool.docAnchors()!!.map { it.pos })
            tool.discard()
        }
    }

    @Test
    fun aSnappedCurveGroupMoveIsOneStepThatUndoesAndRedoesExactly() {
        for (id in CURVE_KINDS) for (gizmo in listOf(false, true)) {
            val (c, tool) = fx.curveGroup(id)
            val start = (0 until 3).map { tool.pointAt(it) }
            // A drag of point 2, or inside the gizmo box (59 px or more from its handles, 80 px from every point).
            c.dragBy(if (gizmo) Vec2(120f, 214f) else CURVE_POINTS[2], CURVE_MOVE)
            val moved = (0 until 3).map { tool.pointAt(it) }
            val how = "$id, " + if (gizmo) "the gizmo" else "a point"
            assertVec("$how: point 2 snapped to the canvas edge", Vec2(400f, CURVE_POINTS[2].y), moved[2])
            assertVec("$how: point 0 moved with it", CURVE_POINTS[0] + Vec2(100f, 0f), moved[0])
            assertTrue("$how: undo", tool.undoStep())
            assertEquals("$how: one Undo puts every point back exactly", start, (0 until 3).map { tool.pointAt(it) })
            assertTrue("$how: redo", tool.redoStep())
            assertEquals("$how: one Redo gives the snapped move back exactly", moved, (0 until 3).map { tool.pointAt(it) })
            tool.discard()
        }
    }

    @Test
    fun aSnappedMoveAndATappedCornerReopenExactlyAfterSaving() {
        for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            val c = newArtwork(vectorLayer = true)
            val layer = c.activeLayer
            c.selectTool(id)
            val tool = c.tools.getValue(id) as CurveTool
            for (p in CURVE_POINTS) assertTrue("$id adds $p", tool.addAnchor(p))
            tool.deselect()
            tool.selectPoints(PointSelection.of(3, 0, 2))
            c.snapping.enabled = true
            c.dragBy(CURVE_POINTS[2], CURVE_MOVE)
            assertVec("$id: point 2 snapped to the canvas edge", Vec2(400f, CURVE_POINTS[2].y), tool.pointAt(2))
            // The middle point made a corner from the pill's cell.
            tool.selectPoints(PointSelection.of(3, 1))
            val cell = tool.pointCorners!!
            assertEquals("$id", CornerState.SMOOTH, cell.corner)
            assertTrue("$id: the cell's tap", cell.setSelectedSharp(true))
            assertEquals("$id", CornerState.SHARP, cell.corner)
            val steps = c.undoManager.undoCount
            tool.commit()
            idle()
            assertEquals("$id: ✓ is one step", steps + 1, c.undoManager.undoCount)
            val made = layer.vector!!.objects.single() as VPath
            if (id == ToolId.PATH) {
                val s = made.spline!!
                assertEquals("$id: the corner is the control point's flag", listOf(false, true, false), s.points.map { it.sharp })
                assertEquals("$id: the snapped end", 400f, s.points[2].x, 0.01f)
            } else {
                val a = made.subpaths.single().anchors
                assertEquals("$id: the corner", listOf(false, true, false), a.map { it.sharp })
                assertEquals("$id: the snapped end", 400f, a[2].x, 0.01f)
            }
            val c2 = reopen(c)
            assertEquals("$id: the path as saved", made, c2.doc.layers.single { it.id == layer.id }.vector!!.objects.single())
        }
    }

    @Test
    fun aV19ArtworkOpensUnchanged() {
        val c = newArtwork(vectorLayer = true)
        val layer = c.activeLayer
        val curve = VPath(
            id = 0,
            subpaths = listOf(VSubpath(listOf(VAnchor(60f, 200f), VAnchor(150f, 90f, sharp = true), VAnchor(240f, 210f), VAnchor(330f, 100f)))),
            stroke = VStrokeStyle(color = INK, width = 4f),
        )
        val spline = VSpline(
            listOf(VSplinePoint(60f, 260f), VSplinePoint(120f, 160f, sharp = true), VSplinePoint(200f, 280f), VSplinePoint(280f, 150f), VSplinePoint(340f, 250f)),
            order = 4,
        )
        val path = VPath(id = 0, subpaths = listOf(SplineBezier.toSubpath(spline)), stroke = VStrokeStyle(color = INK, width = 4f), spline = spline)
        c.vectors.addObjects(layer, listOf(curve, path), "Import")
        idle()
        val saved = layer.vector!!.objects.toList()
        assertEquals(2, saved.size)
        val c2 = reopen(c)
        val layer2 = c2.doc.layers.single { it.id == layer.id }
        assertEquals("the artwork as saved", saved, layer2.vector!!.objects)
        for ((obj, id) in listOf(saved[0] to ToolId.CURVE, saved[1] to ToolId.PATH)) {
            val steps = c2.undoManager.undoCount
            c2.selectLayer(layer2)
            c2.selectTool(id)
            val tool = c2.tools.getValue(id) as CurveTool
            assertTrue("$id: opens", tool.reopen(obj as VPath))
            idle()
            assertTrue("$id: open", tool.isReopened)
            val cell = tool.pointCorners!!
            assertEquals("$id: no point selected, no cell", null, cell.corner)
            tool.selectPoints(PointSelection.all(tool.pointCount))
            // Curve: every anchor can be a corner (one of four is); Path: the inner points decide.
            assertEquals("$id: a mix of corners and smooth points", CornerState.MIXED, cell.corner)
            tool.discard()
            idle()
            assertFalse("$id: closed", tool.isReopened)
            assertEquals("$id: ✕ makes no step", steps, c2.undoManager.undoCount)
            assertEquals("$id: the paths as they were", saved, layer2.vector!!.objects)
        }
        val c3 = reopen(c2)
        assertEquals("saved again, still the same", saved, c3.doc.layers.single { it.id == layer.id }.vector!!.objects)
    }

    private fun controller(doc: com.brushwork.paint.model.Document): EditorController = Smoke.controller(app, doc).also {
        controllers += it
        it.viewTransform.set(Matrix())
        it.snapping.enabled = false
        it.tools
        it.color = INK
        Smoke.pump(100)
    }

    /**
     * A 400 x 300 artwork made as the gallery makes it (Background, "Layer 1"); with
     * [vectorLayer], an empty vector layer "Vector 1" above them, selected.
     */
    private fun newArtwork(vectorLayer: Boolean): EditorController {
        val id = runBlocking { repo.create(NewCanvasSpec("A5", 400, 300, 300f)) }
        projects += id
        val doc = runBlocking { repo.load(id) }
        if (vectorLayer) {
            doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            doc.activeLayerIndex = doc.layers.lastIndex
        }
        return controller(doc)
    }

    /** Saves [c]'s artwork and opens it again in a new editor. */
    private fun reopen(c: EditorController): EditorController {
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertTrue("no load warnings: ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
        return controller(loaded)
    }

    private companion object {
        const val INK = 0xFF203080.toInt()
    }
}
