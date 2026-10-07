package com.brushwork.paint.tools.pathfinder

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.coroutines.CoroutineContext

/**
 * v1.7 (§3.20, area G): the Pathfinder tool on the controller. Two shape LAYERS (the Shape
 * tool's default) united go into one new vector layer "Pathfinder 1" above the upper one, both
 * shape layers gone, and ONE undo restores both with their data and ids; a shape layer and a path
 * on a vector layer combine the same way (the vector layer stays, empty; the path's layer opacity
 * goes into the result); two objects of one vector layer stay in that layer, at the top operand's
 * place (the back one's for Minus front), one undo; splines are dropped; brush strokes and arrayed
 * layers are skipped with their messages; a hidden or locked folder's child is not selectable;
 * "Select all objects" with 13 eligible objects selects none ("Select up to 12 objects"); the
 * result lands inside the top operand's folder; "Working…" shows after 300 ms, and a result whose
 * operands changed meanwhile is not applied.
 */
@RunWith(RobolectricTestRunner::class)
class PathfinderToolRobolectricTest {
    private val red = 0xFFDD2211.toInt()
    private val blue = 0xFF2244CC.toInt()
    private val green = 0xFF22AA44.toInt()

    private fun controller(): EditorController = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(300, 200, layers = 1))

    private fun tool(c: EditorController): PathfinderTool {
        c.selectTool(ToolId.PATHFINDER)
        return (c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
    }

    /** A filled rectangle as the Shape tool's own layer (above the active one). */
    private fun shapeLayer(c: EditorController, name: String, l: Float, t: Float, r: Float, b: Float, color: Int): Layer {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        return c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    /** A new vector layer (above the active one) with [objects]; their ids. */
    private fun vectorLayer(c: EditorController, vararg objects: VObject): Pair<Layer, List<Long>> {
        val layer = c.addVectorLayer()!!
        val ids = c.vectors.addObjects(layer, objects.toList(), "Add")
        assertEquals(objects.size, ids.size)
        return layer to ids
    }

    private fun box(l: Float, t: Float, r: Float, b: Float, color: Int) = ExchangeFixtures.box(l, t, r, b, color)

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    private fun area(o: VObject): Float = PathConvert.area(PathConvert.region(o)!!)

    private fun assertArea(what: String, expected: Float, o: VObject) = assertEquals(what, expected, area(o), expected * 0.01f)

    @Test
    fun twoShapeLayersUniteIntoOneNewLayerAndOneUndoRestoresThem() {
        val c = controller()
        val raster = c.doc.layers[0]
        val a = shapeLayer(c, "A", 20f, 20f, 100f, 100f, red)
        val b = shapeLayer(c, "B", 70f, 20f, 150f, 100f, blue)
        val aData = a.shapeData
        val bData = b.shapeData
        val t = tool(c)
        assertEquals(0, t.count)
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        assertEquals(listOf(a, b), t.operands.map { it.layer })

        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.UNITE)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        assertEquals(2, c.doc.layers.size)
        val result = c.doc.layers[1]
        assertEquals(PathfinderLabels.resultLayer(1), result.name)
        assertTrue(result.isVectorLayer)
        assertSame("the result layer is active", result, c.activeLayer)
        val o = result.vector!!.objects.single() as VPath
        assertArea("Unite", 130f * 80f, o)
        assertEquals("the top object's style", VPaint.Solid(blue), o.fill)
        assertEquals("rendered", 255, Color.alpha(result.bitmap.getPixel(40, 60)))
        assertEquals(0, Color.alpha(result.bitmap.getPixel(200, 150)))
        assertEquals("the picks are done", 0, t.count)

        c.undo()
        assertEquals(listOf(raster.id, a.id, b.id), c.doc.layers.map { it.id })
        assertSame(a, c.doc.layers[1])
        assertSame(b, c.doc.layers[2])
        assertEquals(aData, a.shapeData)
        assertEquals(bData, b.shapeData)
        c.redo()
        assertEquals(listOf(raster.id, result.id), c.doc.layers.map { it.id })
        Smoke.assertQuiet(c, "unite two shape layers")
    }

    @Test
    fun aShapeLayerAndAPathCombineTheSameWayAndTheVectorLayerStays() {
        val c = controller()
        val raster = c.doc.layers[0]
        val (v, ids) = vectorLayer(c, box(70f, 20f, 150f, 100f, blue))
        v.opacity = 0.5f
        val s = shapeLayer(c, "S", 20f, 20f, 100f, 100f, red)
        val before = v.vector
        val t = tool(c)
        tap(c, 140f, 60f)
        tap(c, 40f, 60f)
        assertEquals(2, t.count)

        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.MINUS_FRONT)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Minus front"), c.undoManager.undoLabel)
        assertEquals(listOf(raster, v), c.doc.layers.take(2))
        assertEquals(3, c.doc.layers.size)
        val result = c.doc.layers[2]
        assertEquals(PathfinderLabels.resultLayer(1), result.name)
        assertTrue("the vector layer stays, empty", v.vector!!.objects.isEmpty())
        val o = result.vector!!.objects.single() as VPath
        assertArea("Minus front", 50f * 80f, o)
        assertEquals("the back object's style", VPaint.Solid(blue), o.fill)
        assertEquals("its layer's opacity comes along", 0.5f, o.opacity, 1e-4f)

        c.undo()
        assertEquals(listOf(raster.id, v.id, s.id), c.doc.layers.map { it.id })
        assertEquals(ids, v.vector!!.objects.map { it.id })
        assertEquals(before, v.vector)

        // The next result layer is numbered on.
        c.redo()
        val second = c.addVectorLayer()!!
        c.vectors.addObjects(second, listOf(box(10f, 120f, 60f, 170f, green), box(40f, 120f, 90f, 170f, green)), "Add")
        tap(c, 15f, 145f)
        tap(c, 85f, 145f)
        t.apply(PathfinderOp.UNITE)
        assertEquals("one layer: no new layer", PathfinderLabels.resultLayer(1), c.doc.layers.last { it.name.startsWith("Pathfinder") }.name)
        val third = shapeLayer(c, "T", 200f, 20f, 260f, 80f, red)
        tap(c, 230f, 50f)
        tap(c, 125f, 60f)
        t.apply(PathfinderOp.UNITE)
        assertNotNull(c.doc.layers.firstOrNull { it.name == PathfinderLabels.resultLayer(2) })
        assertEquals(-1, c.doc.indexOf(third))
        Smoke.assertQuiet(c, "shape layer and path")
    }

    @Test
    fun twoObjectsOfOneVectorLayerStayInItAtTheTopOperandsPlace() {
        val c = controller()
        val spline = VSpline(listOf(VSplinePoint(70f, 20f), VSplinePoint(150f, 20f), VSplinePoint(150f, 100f), VSplinePoint(70f, 100f)), cyclic = true)
        val (v, ids) = vectorLayer(c, box(20f, 20f, 100f, 100f, red), box(70f, 20f, 150f, 100f, blue).copy(spline = spline), box(200f, 120f, 260f, 180f, green))
        val original = v.vector!!
        val layers = c.doc.layers.map { it.id }
        val t = tool(c)
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)

        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.UNITE)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        assertEquals("no new layer", layers, c.doc.layers.map { it.id })
        val objs = v.vector!!.objects
        assertEquals("at the top operand's place, under the third object", listOf(ids[1], ids[2]), objs.map { it.id })
        val u = objs[0] as VPath
        assertArea("Unite", 130f * 80f, u)
        assertEquals(VPaint.Solid(blue), u.fill)
        assertNull("splines are dropped", u.spline)
        c.undo()
        assertEquals(original, v.vector)

        // Minus front: at the back operand's place.
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        t.apply(PathfinderOp.MINUS_FRONT)
        assertEquals(listOf(ids[0], ids[2]), v.vector!!.objects.map { it.id })
        assertArea("Minus front", 50f * 80f, v.vector!!.objects[0])
        assertEquals(VPaint.Solid(red), (v.vector!!.objects[0] as VPath).fill)
        c.undo()
        assertEquals(original, v.vector)

        // Divide: the pieces in the top operand's place, the first keeps its id.
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        t.apply(PathfinderOp.DIVIDE)
        val pieces = v.vector!!.objects.dropLast(1)
        assertEquals(3, pieces.size)
        assertEquals(ids[2], v.vector!!.objects.last().id)
        assertEquals(ids[1], pieces[0].id)
        assertTrue("new ids", pieces.drop(1).all { it.id !in ids })
        assertEquals(130f * 80f, pieces.sumOf { area(it).toDouble() }.toFloat(), 130f * 80f * 0.01f)
        c.undo()
        assertEquals(original, v.vector)
        Smoke.assertQuiet(c, "one vector layer")
    }

    @Test
    fun brushStrokesAndArrayedLayersAreSkippedWithTheirMessages() {
        val c = controller()
        val (v, ids) = vectorLayer(c, ExchangeFixtures.stroke(20f, 150f, 200f, 150f), box(20f, 20f, 100f, 100f, red), box(70f, 20f, 150f, 100f, blue))
        val t = tool(c)
        tap(c, 110f, 152f)
        assertEquals(PathfinderLabels.STROKES_SKIPPED, c.message)
        assertEquals(0, t.count)

        c.message = null
        t.selectAll()
        assertEquals(listOf(ids[1], ids[2]), t.operands.map { it.objectId })
        assertEquals(PathfinderLabels.STROKES_SKIPPED, c.message)

        // An arrayed vector layer's objects (their copies are not objects).
        val (arrayed, _) = vectorLayer(c, box(200f, 20f, 280f, 100f, green))
        arrayed.array = LayerArray(ArraySpec())
        c.message = null
        tap(c, 240f, 60f)
        assertEquals(PathfinderLabels.ARRAY_SKIPPED, c.message)
        assertEquals(2, t.count)
        // An arrayed shape layer.
        val s = shapeLayer(c, "S", 200f, 120f, 280f, 190f, green)
        s.array = LayerArray(ArraySpec())
        c.message = null
        tap(c, 240f, 150f)
        assertEquals(PathfinderLabels.ARRAY_SKIPPED, c.message)
        t.selectAll()
        assertEquals("only the paths", listOf(v, v), t.operands.map { it.layer })

        // A picked object whose layer gets an array no longer counts.
        arrayed.array = null
        tap(c, 240f, 60f)
        assertEquals(3, t.count)
        arrayed.array = LayerArray(ArraySpec())
        c.notifyLayersChanged()
        assertEquals(2, t.count)
        Smoke.assertQuiet(c, "skipped")
    }

    @Test
    fun aHiddenOrLockedFoldersChildIsNotSelectable() {
        val c = controller()
        val (v, _) = vectorLayer(c, box(20f, 20f, 100f, 100f, red), box(70f, 20f, 150f, 100f, blue))
        val folder = c.putInNewFolder(v)!!
        assertEquals(folder.id, v.parentId)
        val t = tool(c)
        tap(c, 40f, 60f)
        assertEquals(1, t.count)

        c.toggleVisibility(folder)
        assertFalse(c.doc.effectiveVisible(v))
        assertEquals("a pick in a hidden folder no longer counts", 0, t.count)
        c.message = null
        tap(c, 140f, 60f)
        assertEquals(0, t.count)
        assertTrue("the hint", t.missed)
        assertNull("a miss is quiet", c.message)
        t.selectAll()
        assertEquals(0, t.count)

        c.toggleVisibility(folder)
        t.clearPicks()
        folder.locked = true
        c.notifyLayersChanged()
        tap(c, 40f, 60f)
        assertEquals(0, t.count)
        t.selectAll()
        assertEquals(0, t.count)

        folder.locked = false
        c.notifyLayersChanged()
        t.selectAll()
        assertEquals(2, t.count)
        Smoke.assertQuiet(c, "folder")
    }

    @Test
    fun theResultLandsInTheTopOperandsFolder() {
        val c = controller()
        val (v, _) = vectorLayer(c, box(70f, 20f, 150f, 100f, blue))
        val s = shapeLayer(c, "S", 20f, 20f, 100f, 100f, red)
        val folder = c.putInNewFolder(s)!!
        val t = tool(c)
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.INTERSECT)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(LayerTree.check(c.doc.layers))
        val result = c.doc.layers.single { it.name == PathfinderLabels.resultLayer(1) }
        assertEquals("inside the folder", folder.id, result.parentId)
        assertEquals(-1, c.doc.indexOf(s))
        assertArea("Intersect", 30f * 80f, result.vector!!.objects.single())
        assertEquals("the top object's style", VPaint.Solid(red), (result.vector!!.objects.single() as VPath).fill)
        assertTrue(v.vector!!.objects.isEmpty())

        c.undo()
        assertEquals(folder.id, s.parentId)
        assertTrue(c.doc.indexOf(s) >= 0)
        assertEquals(1, v.vector!!.objects.size)
        assertEquals(-1, c.doc.indexOf(result))
        assertNull(LayerTree.check(c.doc.layers))
        Smoke.assertQuiet(c, "folder result")
    }

    @Test
    fun selectAllTakesUpTo12AndTheThirteenthTapIsRefused() {
        val c = controller()
        val objs = (0 until 13).map { i -> box(10f + 22f * i, 20f, 25f + 22f * i, 40f, red) }
        val (v, ids) = vectorLayer(c, *objs.toTypedArray())
        val t = tool(c)
        t.selectAll()
        assertEquals("13 eligible: none", 0, t.count)
        assertEquals(PathfinderLabels.TOO_MANY_OPERANDS, c.message)

        c.message = null
        for (i in 0 until 12) tap(c, 17.5f + 22f * i, 30f)
        assertEquals(ids.take(12), t.operands.map { it.objectId })
        assertNull(c.message)
        tap(c, 17.5f + 22f * 12, 30f)
        assertEquals(PathfinderLabels.TOO_MANY_OPERANDS, c.message)
        assertEquals(12, t.count)
        // A tap on a picked object takes it back.
        tap(c, 17.5f, 30f)
        assertEquals(11, t.count)
        assertEquals(v, t.operands.first().layer)

        // Taking one away leaves 12: Select all now takes them all.
        c.vectors.update(v, v.vector!!.without(setOf(ids[0])), "Delete")
        t.selectAll()
        assertEquals(12, t.count)
        Smoke.assertQuiet(c, "select all")
    }

    @Test
    fun aDragDoesNothingAMissShowsTheHintAndTheOverlayOutlinesThePicks() {
        val c = controller()
        vectorLayer(c, box(20f, 20f, 100f, 100f, red), box(70f, 20f, 150f, 100f, blue))
        val t = tool(c)
        val steps = c.undoManager.undoCount
        c.pointerDown(ToolPoint(40f, 60f))
        c.pointerMove(ToolPoint(90f, 140f))
        c.pointerUp(ToolPoint(90f, 140f))
        assertEquals("a drag picks nothing", 0, t.count)
        tap(c, 40f, 60f)
        assertEquals(1, t.count)
        tap(c, 250f, 170f)
        assertTrue(t.missed)
        assertEquals("a miss keeps the picks", 1, t.count)
        assertNull(c.message)
        tap(c, 140f, 60f)
        assertFalse(t.missed)
        assertEquals(2, t.count)
        assertFalse(t.hasPendingWork)
        assertEquals("picking is not an edit", steps, c.undoManager.undoCount)

        val overlay = Bitmap.createBitmap(300, 200, Bitmap.Config.ARGB_8888)
        c.drawOverlays(Canvas(overlay), 0f)
        assertTrue("an outline on the first pick's edge", Color.alpha(overlay.getPixel(20, 60)) > 0)
        assertEquals("nothing away from the picks", 0, Color.alpha(overlay.getPixel(250, 170)))

        // Intersect of shapes that don't overlap: nothing happens.
        t.clearPicks()
        val (_, _) = vectorLayer(c, box(200f, 120f, 240f, 160f, green))
        tap(c, 40f, 60f)
        tap(c, 220f, 140f)
        val before = c.undoManager.undoCount
        t.apply(PathfinderOp.INTERSECT)
        assertEquals(PathfinderTool.NOTHING_LEFT, c.message)
        assertEquals("no step", before, c.undoManager.undoCount)
        assertEquals("the picks stay", 2, t.count)

        // Choosing the tool again starts with no picks.
        c.selectTool(ToolId.BRUSH)
        c.selectTool(ToolId.PATHFINDER)
        assertEquals(0, t.count)
        Smoke.assertQuiet(c, "picking")
    }

    /**
     * An edit still rendering in the background (the app renders big edits that way) lands before
     * the operation reads its operands, so the result is of what the user sees and the edit is
     * not lost under it.
     */
    @Test
    fun anEditStillRenderingLandsFirstAndIsKept() {
        val c = controller()
        val (v, ids) = vectorLayer(c, box(20f, 20f, 100f, 100f, red), box(70f, 20f, 150f, 100f, blue))
        val t = tool(c)
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        assertEquals(2, t.count)
        // B moves right, clear of A: its render is still running when Unite is tapped.
        c.vectors.policy = VectorLayers.Policy.ASYNC
        val before = v.vector!!
        val moved = before.replaced(mapOf(ids[1] to listOf(box(110f, 20f, 190f, 100f, blue))))
        c.vectors.update(v, moved, "Move")
        assertSame("not landed yet", before, v.vector)
        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.UNITE)
        assertTrue(Smoke.pumpUntil { !t.busy && !c.vectors.isRendering && c.busyMessage == null })
        assertEquals("the move, then Unite", steps + 2, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        val u = v.vector!!.objects.single() as VPath
        assertArea("Unite of A and the moved B: two squares", 2 * 80f * 80f, u)
        assertEquals("in the moved B's place", listOf(ids[1]), v.vector!!.objects.map { it.id })
        c.undo()
        assertEquals("one undo: the moved B is back", moved, v.vector)
        c.undo()
        assertEquals(before, v.vector)
        Smoke.assertQuiet(c, "pending render")
    }

    /**
     * At the layer limit, operands that include shape layers still combine (the shape layers go,
     * so the document ends with fewer layers); objects of two vector layers need a layer more and
     * are refused with the limit's message.
     */
    @Test
    fun theLayerLimitCountsTheShapeLayersThatGo() {
        assertTrue(PathfinderTool.roomForResult(effectiveLayers = 2, removedShapeLayers = 2, maxLayers = 2))
        assertTrue(PathfinderTool.roomForResult(effectiveLayers = 2, removedShapeLayers = 1, maxLayers = 2))
        assertFalse(PathfinderTool.roomForResult(effectiveLayers = 2, removedShapeLayers = 0, maxLayers = 2))
        assertTrue(PathfinderTool.roomForResult(effectiveLayers = 1, removedShapeLayers = 0, maxLayers = 2))

        val app = RuntimeEnvironment.getApplication()
        val max = Smoke.controller(app, Smoke.document(300, 200, layers = 1)).maxLayers
        assumeTrue("room for the setup ($max)", max >= 6)
        // max − 4 layers, two vector layers and two shape layers: the document is at its limit.
        val c = Smoke.controller(app, Smoke.document(300, 200, layers = max - 4))
        val (v1, _) = vectorLayer(c, box(20f, 20f, 100f, 100f, red))
        val (v2, _) = vectorLayer(c, box(70f, 20f, 150f, 100f, blue))
        val a = shapeLayer(c, "A", 170f, 20f, 240f, 90f, green)
        val b = shapeLayer(c, "B", 200f, 20f, 280f, 90f, red)
        assertEquals(max, c.effectiveLayerCount)
        assertFalse(c.canAddLayer)
        val t = tool(c)

        // Two vector layers' objects: a new layer would pass the limit.
        tap(c, 30f, 60f)
        tap(c, 140f, 60f)
        assertEquals(listOf(v1, v2), t.operands.map { it.layer })
        val steps = c.undoManager.undoCount
        c.message = null
        t.apply(PathfinderOp.UNITE)
        assertEquals(c.layerLimitMessage(), c.message)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertEquals("the picks stay", 2, t.count)

        // Two shape layers: they go, so the result fits.
        t.clearPicks()
        tap(c, 180f, 50f)
        tap(c, 270f, 50f)
        assertEquals(listOf(a, b), t.operands.map { it.layer })
        t.apply(PathfinderOp.UNITE)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(max - 1, c.effectiveLayerCount)
        assertEquals(-1, c.doc.indexOf(a))
        assertNotNull(c.doc.layers.singleOrNull { it.name == PathfinderLabels.resultLayer(1) })
        c.undo()
        assertEquals(max, c.effectiveLayerCount)

        // A shape layer and a path: as many layers as before.
        tap(c, 180f, 50f)
        tap(c, 30f, 60f)
        t.apply(PathfinderOp.UNITE)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(max, c.effectiveLayerCount)
        assertTrue(v1.vector!!.objects.isEmpty())
        Smoke.assertQuiet(c, "layer limit")
    }

    /** Holds what is dispatched to it until [release]. */
    private class HeldDispatcher : CoroutineDispatcher() {
        private val tasks = ArrayList<Runnable>()

        override fun dispatch(context: CoroutineContext, block: Runnable) { tasks += block }

        fun release() {
            while (tasks.isNotEmpty()) tasks.removeAt(0).run()
        }
    }

    @Test
    fun workingShowsAfter300MsAndAChangedOperandStopsTheResult() {
        val c = controller()
        val a = shapeLayer(c, "A", 20f, 20f, 100f, 100f, red)
        val b = shapeLayer(c, "B", 70f, 20f, 150f, 100f, blue)
        val t = tool(c)
        val held = HeldDispatcher()
        t.computeDispatcher = held
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.UNITE)
        assertTrue(t.busy)
        Smoke.pump(200)
        assertNull("not yet", c.busyMessage)
        Smoke.pump(200)
        assertEquals(PathfinderTool.WORKING, c.busyMessage)
        tap(c, 140f, 60f)
        assertEquals("taps wait", 2, t.count)
        held.release()
        assertTrue(Smoke.pumpUntil { !t.busy && c.busyMessage == null })
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(-1, c.doc.indexOf(a))
        assertEquals(-1, c.doc.indexOf(b))
        c.undo()

        // An operand changes while the operation runs: its result is not applied.
        tap(c, 40f, 60f)
        tap(c, 140f, 60f)
        t.apply(PathfinderOp.UNITE)
        c.undo()
        assertEquals("B's insert is undone", -1, c.doc.indexOf(b))
        val after = c.undoManager.undoCount
        held.release()
        assertTrue(Smoke.pumpUntil { !t.busy && c.busyMessage == null })
        assertEquals(PathfinderTool.CHANGED, c.message)
        assertEquals(after, c.undoManager.undoCount)
        assertTrue(c.doc.layers.none { it.name.startsWith("Pathfinder") })
        Smoke.assertQuiet(c, "working")
    }
}
