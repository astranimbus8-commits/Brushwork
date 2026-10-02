package com.brushwork.paint.qa

import android.view.MotionEvent
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.adjust.ToneFilter
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa.QaTouch.RawPointer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorLayers
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.lift.VectorLift
import kotlinx.coroutines.CoroutineDispatcher
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.util.concurrent.Executors
import kotlin.coroutines.CoroutineContext
import kotlin.math.abs

/**
 * QA (I1, I2, I3): the next action comes while a vector edit still renders in the background (the
 * ~400 ms before the busy overlay blocks the screen on the phone) — the Vector button, another
 * layer, a brush stroke during which the render lands, a Shape / Curve ✓, a filter, the selection
 * bar's Clear while Transform is open, undo / redo of a Shape edit. Nothing may be lost, come
 * back, or leave pixels that are not the rendering of the objects; every action stays one step.
 *
 * The render worker is a gate: a job waits until the test opens it (or 3 s pass, so a flush that
 * waits for it never hangs), so the order of events is fixed.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorAsyncBurstQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private lateinit var vec: Layer
    private val ink = 0xFF1A2A6C.toInt()
    private val gate = GateWorker()

    /** Jobs start when [open] (or after 3 s). */
    private class GateWorker : CoroutineDispatcher() {
        private val ex = Executors.newSingleThreadExecutor { Thread(it, "gated-vector-worker").apply { isDaemon = true } }
        private val lock = Object()
        @Volatile private var opened = false

        override fun dispatch(context: CoroutineContext, block: Runnable) {
            ex.execute {
                synchronized(lock) {
                    val until = System.currentTimeMillis() + 3000
                    while (!opened && System.currentTimeMillis() < until) lock.wait(20)
                }
                block.run()
            }
        }

        fun open() = synchronized(lock) { opened = true; lock.notifyAll() }
    }

    @After
    fun tearDown() {
        gate.open()
        if (this::r.isInitialized) r.close()
    }

    /** A new canvas in vector mode, three strokes s1 (y 60), s2 (y 170), s3 (y 260); then ASYNC renders. */
    private fun setup() {
        r = VectorQaRig(480, 320)
        c.vectors.workerDispatcher = gate
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        vec = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 70f, 440f to 60f); r.checkpoint("s1")
        r.stroke(40f to 170f, 240f to 180f, 440f to 170f); r.checkpoint("s2")
        r.stroke(40f to 260f, 240f to 270f, 440f to 260f); r.checkpoint("s3")
        c.vectors.policy = VectorLayers.Policy.ASYNC
    }

    private fun strokeAt(y: Float, layer: Layer = vec) = layer.vector!!.objects.filterIsInstance<VStroke>().firstOrNull { abs(it.points.bounds().centerY() - y) < 15f }

    /** Erases s2 with the Object eraser; returns while its render waits at the gate. */
    private fun eraseS2Pending() {
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.eraser = c.eraser.copy(size = 12f)
        r.stroke(240f to 150f, 240f to 175f, 240f to 200f)
        assertTrue("the erase renders in the background", c.vectors.isRendering)
        assertNotNull("not landed yet", strokeAt(175f))
    }

    /** What the layer must hold once the erase landed: s1 and s3 only. */
    private fun assertErased(where: String) {
        assertNull("$where: s2 erased", strokeAt(175f))
        assertNotNull("$where: s1 kept", strokeAt(65f))
        assertNotNull("$where: s3 kept", strokeAt(265f))
    }

    private fun quiet() = assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering && c.busyMessage == null })

    @Test
    fun vectorOffAndOnRightAfterAnEraseKeepsTheEraseAndTheLayer() {
        setup()
        val before = r.snapshot()
        eraseS2Pending()
        c.toggleVectorMode()
        assertFalse(c.isVectorMode)
        c.toggleVectorMode()
        assertSame("the same vector layer again", vec, c.activeLayer)
        gate.open()
        r.checkpoint("erase, Vector off and on")
        assertErased("after the toggles")
        assertEquals(2, c.doc.layers.size)
        r.undoAndCheck("undo the erase")
        r.assertState("before the erase", before)
        r.redoAndCheck("redo the erase")
    }

    @Test
    fun selectingAnotherLayerRightAfterAnEraseLandsItOnTheVectorLayer() {
        setup()
        eraseS2Pending()
        val bg = c.doc.layers[0]
        c.selectLayer(bg)
        assertSame(bg, c.activeLayer)
        gate.open()
        r.checkpoint("erase, then the Background selected")
        assertErased("on the vector layer")
        assertNull("the Background is untouched", bg.vector)
        assertEquals("Erase", c.undoManager.undoLabel)
        c.selectLayer(vec)
        r.undoAndCheck("undo the erase")
        assertNotNull(strokeAt(175f))
    }

    @Test
    fun aBrushStrokeRightAfterAnEraseKeepsBothAndUndoesInOrder() {
        setup()
        val before = r.snapshot()
        eraseS2Pending()
        r.tool(ToolId.BRUSH)
        // A stroke across where s2 is being erased, at once (the stroke lands the render first:
        // it starts on the erased drawing).
        val pts = (0..24).map { k -> r.screen(250f + 2f * k, 100f + 8f * k) }
        r.touch.raw(MotionEvent.ACTION_DOWN, listOf(RawPointer(0, pts[0].first, pts[0].second)))
        for (k in 1..10) { r.touch.idle(16); r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, pts[k].first, pts[k].second))) }
        gate.open()
        for (k in 11..24) { r.touch.idle(16); r.touch.raw(MotionEvent.ACTION_MOVE, listOf(RawPointer(0, pts[k].first, pts[k].second))) }
        r.touch.idle(16)
        r.touch.raw(MotionEvent.ACTION_UP, listOf(RawPointer(0, pts.last().first, pts.last().second)))
        r.touch.idle(60)
        r.checkpoint("erase, then a stroke", steps = 2)
        assertErased("both")
        assertEquals("the new stroke is an object", 3, vec.vector!!.objects.size)
        assertTrue(vec.vector!!.objects.last() is VStroke)
        // Undo the stroke: exactly the erased drawing (pixels = its objects' rendering).
        c.undo()
        quiet()
        assertEquals("Erase", c.undoManager.undoLabel)
        assertErased("stroke undone")
        assertEquals(2, vec.vector!!.objects.size)
        r.assertCacheFresh("stroke undone", vec)
        c.undo()
        quiet()
        r.assertState("both undone", before)
        c.redo(); c.redo()
        quiet()
        assertEquals(3, vec.vector!!.objects.size)
        r.assertCacheFresh("both redone", vec)
    }

    /**
     * s2 selected (a Lasso a moment ago), the [tool] picked, then the Object bar's Delete: its
     * render waits at the gate. (The canvas tools come right after it: the busy overlay blocks the
     * canvas 400 ms after a render started, so the next canvas gesture must be quick.)
     */
    private fun deleteS2Pending(tool: ToolId) {
        c.vectors.setSelection(vec, setOf(strokeAt(175f)!!.id))
        r.tool(tool)
        assertTrue(com.brushwork.paint.vector.select.ObjectActions.delete(c))
        assertTrue("the delete renders in the background", c.vectors.isRendering)
        assertNotNull("not landed yet", strokeAt(175f))
    }

    @Test
    fun aShapeConfirmedRightAfterAnObjectBarDeleteLandsOnTopOfTheResult() {
        setup()
        val before = r.snapshot()
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40B060.toInt()) }
        deleteS2Pending(ToolId.SHAPE)
        r.touch.stroke(listOf(r.screen(180f, 140f), r.screen(300f, 210f)), stylus = false)
        assertTrue("placed", shape.hasPendingWork)
        shape.commit()
        gate.open()
        r.checkpoint("delete + shape", steps = 2)
        assertErased("with the shape")
        assertTrue("the shape is on top", vec.vector!!.objects.last() is VShape)
        assertEquals(3, vec.vector!!.objects.size)
        c.undo(); quiet()
        assertErased("shape undone")
        r.assertCacheFresh("shape undone", vec)
        c.undo(); quiet()
        r.assertState("both undone", before)
    }

    @Test
    fun aCurveConfirmedRightAfterAnObjectBarDeleteLandsOnTopOfTheResult() {
        setup()
        r.tool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        deleteS2Pending(ToolId.CURVE)
        for ((x, y) in listOf(60f to 200f, 240f to 140f, 420f to 200f)) r.screen(x, y).let { r.touch.tap(it.first, it.second) }
        assertEquals(3, curve.anchors.size)
        curve.commit()
        gate.open()
        r.checkpoint("delete + curve", steps = 2)
        assertErased("with the curve")
        assertTrue(vec.vector!!.objects.last() is VPath)
        c.undo(); quiet()
        assertErased("curve undone")
        r.assertCacheFresh("curve undone", vec)
    }

    @Test
    fun aFilterAppliedRightAfterAnEraseRasterizesTheErasedDrawingAndUndoesInOrder() {
        setup()
        val before = r.snapshot()
        eraseS2Pending()
        val steps = c.undoManager.undoCount
        c.startFilter(FilterRegistry.byId(ToneFilter.ID)!!)
        val session = c.filterSession!!
        gate.open()
        session.update(ToneFilter.EXPOSURE, 1.0f)
        session.apply()
        assertTrue("applied", Smoke.pumpUntil(20_000) { c.undoManager.undoCount == steps + 2 && c.busyMessage == null && !c.vectors.isRendering })
        c.filterSession?.cancel()
        Smoke.pump(40)
        assertNull("raster now", vec.vector)
        c.undo()
        Smoke.pump(40)
        assertNotNull("the filter undone: a vector layer again", vec.vector)
        assertErased("filter undone")
        r.assertCacheFresh("filter undone", vec)
        c.undo()
        Smoke.pump(40)
        r.assertState("erase undone", before)
    }

    @Test
    fun clearFromTheSelectionBarWhileTransformIsOpenRelandsTheLiftOnTheResult() {
        setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        // A pixel selection over s2 (Magic wand), then Transform: it lifts the touched object.
        r.tool(ToolId.MAGIC_WAND)
        (c.tools.getValue(ToolId.MAGIC_WAND) as MagicWandTool).selectAt(240f, 178f)
        assertTrue(Smoke.pumpUntil(10_000) { c.selection != null })
        r.checkpoint("wand", steps = null)
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        assertEquals(setOf(strokeAt(175f)!!.id), VectorLift.activeLift(c)!!.ids)
        c.vectors.policy = VectorLayers.Policy.ASYNC
        // The selection bar's Clear (the floating bar under the selection).
        c.clearLayer()
        gate.open()
        r.checkpoint("selection bar Clear with Transform open", steps = 1)
        assertErased("cleared")
        assertEquals("Clear", c.undoManager.undoLabel)
        // The tool goes on with what is there now: nothing of s2 is lifted.
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        val lift = VectorLift.activeLift(c)
        assertTrue("no lift of the cleared object: ${lift?.ids}", lift == null || lift.ids.all { id -> vec.vector!!.byId(id) != null })
        if (t.hasPendingWork) t.discard()
        r.checkpoint("discard", steps = 0)
    }

    @Test
    fun undoAndRedoRightAfterAShapeEditTakeItBackAndForth() {
        setup()
        c.vectors.policy = VectorLayers.Policy.SYNC
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.ELLIPSE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 5f) }
        r.stroke(300f to 100f, 360f to 140f, 420f to 180f)
        shape.commit()
        r.checkpoint("ellipse")
        val ellipse = vec.vector!!.objects.last() as VShape
        val placed = r.snapshot()
        // (Jobs run at once now; their results still land on a later main-thread turn.)
        gate.open()
        c.vectors.policy = VectorLayers.Policy.ASYNC
        // Reopen by a tap, move it, ✓: the edit renders in the background; Undo at once.
        r.tap(300f, 140f)
        assertTrue(Smoke.pumpUntil(10_000) { shape.editingObject && !c.vectors.isRendering })
        shape.nudge(-30, 12)
        shape.commit()
        assertTrue("the edit renders in the background", c.vectors.isRendering)
        c.undo()
        quiet()
        Smoke.pump(40)
        r.assertState("the edit landed and was undone", placed)
        assertSame(ellipse, vec.vector!!.byId(ellipse.id))
        c.redo()
        quiet()
        val moved = vec.vector!!.byId(ellipse.id) as VShape
        assertTrue("redone: moved", moved != ellipse)
        r.assertCacheFresh("redone", vec)
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        // The shape reopens where it is now.
        r.tool(ToolId.BRUSH)
        r.tool(ToolId.SHAPE)
        c.vectors.policy = VectorLayers.Policy.SYNC
        val b = com.brushwork.paint.vector.VectorOps.bounds(moved)
        r.tap(b.left + 3f, b.centerY())
        assertTrue("reopens at its new place", shape.editingObject)
        shape.discard()
        r.resync()
        assertEquals(VectorContent::class, vec.vector!!::class)
    }
}
