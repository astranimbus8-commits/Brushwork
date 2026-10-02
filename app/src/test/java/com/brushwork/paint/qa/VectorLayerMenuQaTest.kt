package com.brushwork.paint.qa

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.layers.LayerOps
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectActions
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
import kotlin.math.abs

/**
 * QA (§4.9 "Layer menu", the layer row's eye / lock, masks and clipping on vector layers): what
 * the layers window does to a vector layer and what the tools then do — Rasterize (undo gives the
 * objects and vector mode back), Edit objects, Convert a shape layer, a hidden or locked vector
 * layer refusing every tool and Object bar action with a message and no step, a layer mask
 * painted as pixels without losing the objects, a clipped vector layer merged down, Transform's
 * flip / reset / fit on objects, and the Vector button on an alpha-locked new canvas.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorLayerMenuQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c
    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    /** A new 480 x 320 canvas in vector mode with three finger strokes (y 60, 170, 260). */
    private fun drawing(): Layer {
        r = VectorQaRig(480, 320)
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 70f, 440f to 60f); r.checkpoint("s1")
        r.stroke(40f to 170f, 240f to 180f, 440f to 170f); r.checkpoint("s2")
        r.stroke(60f to 260f, 200f to 290f, 300f to 250f); r.checkpoint("s3")
        return c.activeLayer
    }

    @Test
    fun rasterizeThenUndoGivesTheObjectsAndVectorModeBack() {
        val vec = drawing()
        val content = vec.vector!!
        val before = r.snapshot()
        LayerOps.rasterizeVector(c, vec)
        r.checkpoint("rasterize")
        assertEquals("Rasterize vector layer", c.undoManager.undoLabel)
        assertNull(vec.vector)
        assertFalse(c.isVectorMode)
        // The brush now paints pixels on it.
        r.stroke(60f to 120f, 400f to 120f)
        r.checkpoint("a raster stroke")
        assertNull(vec.vector)
        r.undoAndCheck("undo the raster stroke")
        r.undoAndCheck("undo rasterize")
        r.assertState("objects back", before)
        assertSame(content, vec.vector)
        assertTrue("vector mode again", c.isVectorMode)
        r.stroke(60f to 120f, 400f to 120f)
        r.checkpoint("an object again")
        assertEquals(4, vec.vector!!.objects.size)
    }

    @Test
    fun editObjectsOpensTransformOnTheLayer() {
        val vec = drawing()
        c.selectLayer(c.doc.layers[0])
        r.tool(ToolId.BRUSH)
        LayerOps.editObjects(c, vec)
        Smoke.pump(40)
        assertSame(vec, c.activeLayer)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        assertEquals("every object lifted", vec.vector!!.objects.map { it.id }.toSet(), VectorLift.activeLift(c)!!.ids)
        t.moveBy(12.5f, 0f)
        t.commit()
        r.checkpoint("moved from Edit objects")
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
    }

    @Test
    fun aShapeLayerConvertedToAVectorLayerIsOneShapeObjectThatReopensByTap() {
        r = VectorQaRig(480, 320)
        // A shape in its own layer (raster mode, "Editable (own layer)").
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 6f, fillColor = 0xFF40B060.toInt(), editable = true) }
        r.stroke(100f to 80f, 200f to 160f, 300f to 240f)
        shape.commit()
        r.checkpoint("shape layer")
        val layer = c.activeLayer
        assertTrue("a shape layer", layer.isShapeLayer)
        assertTrue(LayerOps.canConvertToVector(c, layer))
        val pixels = r.pixels(layer.bitmap)
        LayerOps.convertToVector(c, layer)
        r.checkpoint("convert to vector layer")
        assertTrue(layer.isVectorLayer)
        assertNull(layer.shapeData)
        val obj = layer.vector!!.objects.single() as VShape
        assertEquals(ShapeType.RECTANGLE, obj.shape.type)
        assertEquals("the pixels stay", pixels.toList(), r.pixels(layer.bitmap).toList())
        assertTrue(c.isVectorMode)
        // The Shape tool reopens it by a tap; a move; ✓ is one step.
        r.tool(ToolId.BRUSH)
        r.tool(ToolId.SHAPE)
        r.tap(200f, 160f)
        assertTrue("reopened", Smoke.pumpUntil(10_000) { shape.editingObject })
        shape.nudge(10, 0)
        shape.commit()
        r.checkpoint("edit the converted shape")
        assertEquals(ShapeTool.EDIT_SHAPE_LABEL, c.undoManager.undoLabel)
        assertEquals(1, layer.vector!!.objects.size)
        r.undoAndCheck("undo edit")
        r.undoAndCheck("undo convert")
        assertTrue(layer.isShapeLayer)
        assertNull(layer.vector)
    }

    /** Every tool and Object bar action on [vec] while it can't be edited: a message, no step, no change. */
    private fun everythingRefused(vec: Layer, why: String) {
        val before = r.snapshot()
        fun refused(what: String) {
            assertTrue("$what: told ($why): ${c.message}", c.message?.contains(why) == true)
            r.checkpoint("$what refused", steps = 0)
            r.assertState("$what changed nothing", before)
            c.message = null
        }
        c.message = null
        r.tool(ToolId.BRUSH)
        r.stroke(60f to 120f, 400f to 120f); refused("brush")
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        r.stroke(240f to 150f, 240f to 200f); refused("eraser")
        r.tool(ToolId.FILL)
        r.tap(240f, 175f); refused("bucket on an object")
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        r.stroke(100f to 100f, 200f to 150f, 300f to 200f)
        if (shape.hasPendingWork) shape.commit()
        refused("shape")
        r.tool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        r.tap(40f, 200f); r.tap(140f, 280f); r.tap(260f, 200f)
        if (curve.hasPendingWork) curve.commit()
        refused("curve")
        r.tool(ToolId.TRANSFORM)
        Smoke.pump(100)
        refused("transform")
        r.tool(ToolId.BRUSH)
        // The Object bar (objects selected before the layer was hidden / locked).
        c.vectors.setSelection(vec, setOf(vec.vector!!.objects[0].id))
        ObjectActions.delete(c); Smoke.pump(40); refused("Object bar Delete")
        c.vectors.setSelection(vec, setOf(vec.vector!!.objects[0].id))
        ObjectActions.duplicate(c); Smoke.pump(40); refused("Object bar Duplicate")
        c.vectors.setSelection(vec, setOf(vec.vector!!.objects[0].id))
        c.color = 0xFF20A040.toInt()
        ObjectActions.recolor(c, linesOnly = false); Smoke.pump(40); refused("Object bar Recolor")
        c.color = ink
        c.vectors.setSelection(null, emptySet())
    }

    @Test
    fun aLockedVectorLayerRefusesEveryToolWithAMessageAndNoStep() {
        val vec = drawing()
        c.toggleLock(vec)
        r.checkpoint("lock")
        everythingRefused(vec, "locked")
    }

    @Test
    fun aHiddenVectorLayerRefusesEveryToolWithAMessageAndNoStep() {
        val vec = drawing()
        c.toggleVisibility(vec)
        r.checkpoint("hide")
        everythingRefused(vec, "hidden")
    }

    @Test
    fun aLayerMaskOnAVectorLayerIsPaintedAsPixelsAndTheObjectsStay() {
        val vec = drawing()
        val content = vec.vector!!
        LayerOps.addMask(c, vec, fromSelection = false)
        r.checkpoint("add mask")
        assertTrue(vec.editingMask)
        // The brush paints the MASK (black hides): no object, the layer stays a vector layer.
        c.color = 0xFF000000.toInt()
        r.tool(ToolId.BRUSH)
        r.stroke(100f to 40f, 100f to 300f)
        r.checkpoint("paint the mask")
        assertSame("no object added", content, vec.vector)
        assertTrue("the mask hides there", (vec.mask!!.getPixel(100, 170) and 0xFF) < 40)
        // Back to the content: strokes are objects again.
        LayerOps.editTarget(c, vec, mask = false)
        c.color = ink
        r.stroke(60f to 120f, 400f to 120f)
        r.checkpoint("an object with a mask")
        assertEquals(4, vec.vector!!.objects.size)
        // Flip layer with the mask: objects and mask flip in one step.
        c.flipLayer(vec, horizontal = true)
        r.checkpoint("flip with a mask", tolerance = 2, maxOffPermille = 5)
        assertTrue("the mask flipped too", (vec.mask!!.getPixel(480 - 101, 170) and 0xFF) < 40)
        assertTrue((vec.mask!!.getPixel(100, 170) and 0xFF) > 200)
        r.undoAndCheck("undo flip")
        assertTrue((vec.mask!!.getPixel(100, 170) and 0xFF) < 40)
        // Save and reload: the objects and the mask.
        val s = r.snapshot()
        r.saveAndReopen()
        r.assertState("reloaded", s)
        assertNotNull(c.doc.layers.first { it.name == vec.name }.mask)
    }

    @Test
    fun aClippedVectorLayerMergedOntoAVectorLayerBecomesPixelsAsOnScreen() {
        val lower = drawing()
        val upper = c.addVectorLayer()!!
        r.checkpoint("second vector layer")
        r.stroke(20f to 120f, 460f to 120f); r.checkpoint("u1")
        c.toggleClipping(upper)
        r.checkpoint("clip")
        val screen = c.compositor.renderFlattened()
        val before = r.snapshot()
        c.mergeDown(upper)
        r.checkpoint("merge a clipping layer down")
        assertNull("clipped: merged as pixels", lower.vector)
        val after = c.compositor.renderFlattened()
        var off = 0
        for (y in 0 until 320) for (x in 0 until 480) if (screen.getPixel(x, y) != after.getPixel(x, y)) off++
        assertTrue("looks the same after the merge: $off", off < 50)
        r.undoAndCheck("undo merge")
        r.assertState("both back", before)
        assertTrue(lower.isVectorLayer && upper.isVectorLayer)
    }

    @Test
    fun transformFlipResetAndFitWorkOnObjects() {
        val vec = drawing()
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        val s3 = vec.vector!!.objects[2] as VStroke
        t.flip(horizontal = true)
        t.commit()
        r.checkpoint("flip objects", tolerance = 2, maxOffPermille = 5)
        val f3 = vec.vector!!.byId(s3.id) as VStroke
        val box = VectorOps.bounds(s3)
        // Mirrored inside the box of every object: the bottom stroke's start goes to the other side.
        assertTrue("mirrored: ${s3.points.x[0]} -> ${f3.points.x[0]}", f3.points.x[0] > box.centerX())
        // Reset after changes: nothing to confirm, no step.
        relift(t); assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        val content = vec.vector
        t.moveBy(30.5f, 12.5f)
        t.reset()
        t.commit()
        r.checkpoint("reset", steps = 0)
        assertSame(content, vec.vector)
        // Fit to canvas: the objects fill the canvas.
        relift(t); assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        t.fitToCanvas()
        t.commit()
        r.checkpoint("fit to canvas")
        val all = vec.vector!!.objects.map { VectorOps.bounds(it) }
        val l = all.minOf { it.left }; val rr = all.maxOf { it.right }
        assertTrue("fills the width: $l..$rr", l < 30f && rr > 450f)
        assertTrue(vec.vector!!.objects.all { it is VStroke })
    }

    @Test
    fun theVectorButtonOnAnAlphaLockedNewLayerSaysHowToDraw() {
        r = VectorQaRig(480, 320)
        val layer1 = c.activeLayer
        c.toggleAlphaLock(layer1)
        r.checkpoint("lock alpha of Layer 1")
        c.toggleVectorMode()
        r.checkpoint("Vector on", steps = null)
        assertTrue(c.isVectorMode)
        val vec = c.activeLayer
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.message = null
        r.stroke(60f to 120f, 400f to 120f)
        r.checkpoint("stroke", steps = null)
        // Either the stroke is drawn, or the user is told exactly what to do.
        val drawn = vec.vector!!.objects.isNotEmpty()
        assertTrue("drawn, or told why: ${c.message}", drawn || c.message?.contains("unlock", ignoreCase = true) == true)
    }

    @Test
    fun theBucketOnOpenSpaceFillsTheOpenAreaUnderTheLinesLikeTheRasterBucket() {
        val vec = drawing()
        val strokes = vec.vector!!.objects
        r.tool(ToolId.FILL)
        val orange = 0xFFF08020.toInt()
        c.color = orange
        c.message = null
        // Between two open strokes: the open area reaches the canvas edges (as the raster bucket
        // on an empty layer fills the whole area around the lines).
        r.tap(240f, 120f)
        assertTrue("filled", Smoke.pumpUntil(10_000) { vec.vector!!.objects.size > strokes.size && !c.vectors.isRendering && c.busyMessage == null })
        r.checkpoint("bucket on open space")
        assertEquals("Fill", c.undoManager.undoLabel)
        val fill = vec.vector!!.objects.first() as VPath
        assertEquals("under the lines", strokes, vec.vector!!.objects.drop(1))
        assertEquals(VPaint.Solid(orange), fill.fill)
        assertTrue("the lines stay visible over it", (vec.bitmap.getPixel(240, 65) and 0xFFFFFF) != (orange and 0xFFFFFF))
        assertEquals(orange, vec.bitmap.getPixel(240, 120))
        // One undo takes it back; the bucket on it then recolors it as an object.
        r.undoAndCheck("undo the area fill")
        r.redoAndCheck("redo the area fill")
        c.color = 0xFF3060C0.toInt()
        r.tap(240f, 120f)
        r.checkpoint("recolor the fill")
        assertEquals("Fill object", c.undoManager.undoLabel)
        assertEquals(VPaint.Solid(0xFF3060C0.toInt()), (vec.vector!!.objects.first() as VPath).fill)
    }

    /** The Transform tool picked again (it lifts on activation). */
    private fun relift(t: TransformTool) {
        r.tool(ToolId.BRUSH)
        r.tool(ToolId.TRANSFORM)
        check(c.tools.getValue(ToolId.TRANSFORM) === t)
    }

    private fun strokeAt(layer: Layer, y: Float) = layer.vector!!.objects.filterIsInstance<VStroke>().firstOrNull { abs(it.points.bounds().centerY() - y) < 15f }
}
