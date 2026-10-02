package com.brushwork.paint.qa

import android.graphics.Rect
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.adjust.ToneFilter
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.LayerToolRules
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
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorStrokeCapture
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
 * QA (§4.9 table, §7): what the rest of the editor does to a vector layer, through the user's
 * paths — pixel-only tools refused with the right message and no step, a filter rasterizing
 * undoably, merging two vector layers, Duplicate with a selection, Flip layer, canvas resize /
 * rotate / crop keeping the objects crisp and editable, alpha lock ignored by objects.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorLayerOpsQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c

    private val ink = 0xFF1A2A6C.toInt()

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    /** A new 480 x 320 canvas in vector mode with three finger strokes. */
    private fun drawing(): com.brushwork.paint.model.Layer {
        r = VectorQaRig(480, 320)
        c.toggleVectorMode()
        r.checkpoint("Vector on")
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        r.stroke(40f to 60f, 240f to 80f, 440f to 60f); r.checkpoint("s1")
        r.stroke(40f to 160f, 240f to 180f, 440f to 160f); r.checkpoint("s2")
        r.stroke(60f to 250f, 200f to 290f, 300f to 240f); r.checkpoint("s3")
        return c.activeLayer
    }

    @Test
    fun pixelOnlyToolsAreRefusedWithTheirMessageAndNoStep() {
        val vec = drawing()
        val before = r.snapshot()
        for (id in LayerToolRules.PIXEL_ONLY) {
            r.tool(id)
            c.message = null
            r.stroke(100f to 100f, 200f to 120f, 300f to 100f)
            assertEquals("$id refused", LayerToolRules.pixelOnlyMessage(id), c.message)
            r.checkpoint("$id refused", steps = 0)
            r.assertState("$id changed nothing", before)
            assertTrue(vec.isVectorLayer)
            // A tap too.
            r.tap(240f, 170f)
            r.checkpoint("$id tap refused", steps = 0)
        }
        // A watercolor brush is refused with its own message.
        r.tool(ToolId.BRUSH)
        val wc = BrushLibrary.all.first { it.tip == com.brushwork.paint.brush.BrushTip.WATERCOLOR }
        c.brush = wc
        c.message = null
        r.stroke(100f to 100f, 300f to 100f)
        assertTrue("watercolor refused: ${c.message}", c.message?.contains("needs a raster layer") == true)
        r.checkpoint("watercolor refused", steps = 0)
        r.assertState("nothing changed", before)
    }

    @Test
    fun aFilterRasterizesTheLayerUndoably() {
        val vec = drawing()
        val content = vec.vector!!
        val before = r.snapshot()
        val steps = c.undoManager.undoCount
        c.startFilter(FilterRegistry.byId(ToneFilter.ID)!!)
        val session = assertNotNull(c.filterSession).let { c.filterSession!! }
        session.update(ToneFilter.EXPOSURE, 1.5f)
        session.apply()
        assertTrue("applied", Smoke.pumpUntil(20_000) { c.undoManager.undoCount == steps + 1 && c.busyMessage == null })
        c.filterSession?.cancel()
        Smoke.pump(40)
        assertNull("the layer is raster now", vec.vector)
        assertTrue("told: ${c.message}", c.message?.contains("regular layer") == true)
        assertFalse(c.isVectorMode)
        r.resync()
        c.undo()
        Smoke.pump(20)
        r.assertState("undo restores the vector layer", before)
        assertSame(content, vec.vector)
        assertTrue(c.isVectorMode)
        c.redo()
        assertNull(vec.vector)
        c.undo()
        // Still editable: a stroke after the undo is an object.
        r.tool(ToolId.BRUSH)
        r.resync()
        r.stroke(60f to 300f, 400f to 300f)
        r.checkpoint("stroke after undoing the filter")
        assertEquals(4, vec.vector!!.objects.size)
    }

    @Test
    fun mergingTwoVectorLayersKeepsEveryObjectEditable() {
        val lower = drawing()
        val lowerContent = lower.vector!!
        // A second vector layer above (Layers: New vector layer), two strokes on it.
        val upper = c.addVectorLayer()!!
        r.checkpoint("new vector layer")
        assertSame(upper, c.activeLayer)
        r.stroke(80f to 120f, 400f to 130f); r.checkpoint("u1")
        r.stroke(80f to 210f, 400f to 200f); r.checkpoint("u2")
        val upperContent = upper.vector!!
        val beforeMerge = r.snapshot()
        c.mergeDown(upper)
        r.checkpoint("merge down", tolerance = 2, maxOffPermille = 1000)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertEquals(2, c.doc.layers.size)
        assertSame(lower, c.activeLayer)
        val merged = lower.vector!!
        assertEquals(lowerContent.objects.size + upperContent.objects.size, merged.objects.size)
        assertEquals("ids unique", merged.objects.size, merged.objects.map { it.id }.toSet().size)
        assertEquals(lowerContent.objects, merged.objects.take(3))
        // The upper objects are on top, re-id'd, otherwise the same.
        for ((a, b) in upperContent.objects.zip(merged.objects.drop(3))) assertEquals(a.withId(0), b.withId(0))
        // Still editable: the merged-in strokes can be selected and moved.
        r.tool(ToolId.LASSO)
        r.stroke(60f to 100f, 420f to 100f, 420f to 140f, 60f to 140f, 60f to 102f)
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds.isNotEmpty() })
        r.checkpoint("lasso the merged objects", steps = 0)
        assertEquals(setOf(merged.objects[3].id), c.vectors.selectedIds)
        r.undoAndCheck("undo merge")
        r.assertState("both layers back", beforeMerge)
        assertSame(upperContent, upper.vector)
        r.redoAndCheck("redo merge")

        // A vector layer onto a raster layer: pixels (undoable).
        r.undoAndCheck("undo merge again")
        c.selectLayer(lower)
        c.setBlendMode(upper, LayerBlendMode.MULTIPLY)
        r.checkpoint("multiply")
        c.mergeDown(upper)
        r.checkpoint("merge a multiply layer down")
        assertNull("a non-Normal upper layer merges as pixels", lower.vector)
        r.undoAndCheck("undo raster merge")
        assertSame(lowerContent, lower.vector)
    }

    @Test
    fun duplicateWithAPixelSelectionCopiesTheTouchedObjects() {
        val vec = drawing()
        // The magic wand on the vector layer makes a PIXEL selection (the middle stroke).
        r.tool(ToolId.MAGIC_WAND)
        (c.tools.getValue(ToolId.MAGIC_WAND) as MagicWandTool).selectAt(240f, 178f)
        assertTrue(Smoke.pumpUntil(10_000) { c.selection != null })
        r.checkpoint("wand", steps = null)
        val mid = vec.vector!!.objects[1]
        val copy = c.duplicateLayer(vec)!!
        r.checkpoint("duplicate with a selection")
        assertTrue("a vector layer", copy.isVectorLayer)
        assertEquals("only the touched object", listOf(mid.id), copy.vector!!.objects.map { it.id })
        assertSame(copy, c.activeLayer)
        assertTrue(c.isVectorMode)
        r.undoAndCheck("undo duplicate")
        assertEquals(2, c.doc.layers.size)
        // Without a selection: the whole layer, objects shared.
        c.deselect()
        r.checkpoint("deselect", steps = null)
        val whole = c.duplicateLayer(vec)!!
        r.checkpoint("duplicate")
        assertSame(vec.vector, whole.vector)
    }

    @Test
    fun flipLayerMirrorsTheObjectsAndStaysEditable() {
        val vec = drawing()
        val before = vec.vector!!
        c.flipLayer(vec, horizontal = true)
        r.checkpoint("flip layer", tolerance = 2, maxOffPermille = 5)
        val after = vec.vector!!
        assertEquals(before.objects.map { it.id }, after.objects.map { it.id })
        val s0 = before.objects[0] as VStroke
        val s1 = after.objects[0] as VStroke
        for (i in 0 until s0.points.size) assertEquals(480f - s0.points.x[i], s1.points.x[i], 1e-3f)
        r.undoAndCheck("undo flip")
        r.redoAndCheck("redo flip")
        // A partial erase on the flipped objects.
        c.toggleEraser()
        c.eraser = c.eraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        r.stroke(240f to 40f, 240f to 100f)
        r.checkpoint("erase after flip", tolerance = 2, maxOffPermille = 5)
        assertEquals(4, vec.vector!!.objects.size)
    }

    private fun waitCanvasOp(label: String) {
        assertTrue("$label finished", Smoke.pumpUntil(30_000) { c.busyMessage == null && c.undoManager.undoLabel == label })
    }

    @Test
    fun canvasResizeRotateAndCropKeepTheObjectsCrispAndEditable() {
        val vec = drawing()
        val content = vec.vector!!
        val start = r.snapshot()
        // Resize image to 150 %: the objects are scaled and drawn again (crisp, not resampled).
        assertTrue(CanvasOps.applyResizeImage(c, 720, 480, c.doc.dpi, Resample.BILINEAR))
        waitCanvasOp("Resize image")
        r.checkpoint("resize image")
        assertEquals(720, c.doc.width)
        val s = vec.vector!!.objects[0] as VStroke
        assertEquals(1.5f, s.sizeScale, 1e-3f)
        assertEquals(1.5f * (content.objects[0] as VStroke).points.x[0], s.points.x[0], 1e-2f)
        // Rotate 90°.
        assertTrue(CanvasOps.applyRotate(c, CanvasRotation.CW_90))
        waitCanvasOp(CanvasRotation.CW_90.label)
        r.checkpoint("rotate", tolerance = 2, maxOffPermille = 5)
        assertEquals(480, c.doc.width)
        assertEquals(720, c.doc.height)
        // Crop.
        assertTrue(CanvasOps.applyCrop(c, Rect(40, 40, 440, 680)))
        waitCanvasOp("Crop")
        r.checkpoint("crop", tolerance = 2, maxOffPermille = 5)
        assertEquals(400, c.doc.width)
        assertTrue(vec.isVectorLayer)
        // Still editable after all three: a lasso and a move.
        r.view.fitToScreen()
        Smoke.pump(40)
        r.tool(ToolId.TRANSFORM)
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(10_000) { t.transformState != null })
        t.moveBy(10f, 0f)
        t.commit()
        r.checkpoint("move all after the canvas ops", tolerance = 32, maxOffPermille = 20)
        // Undo everything: the original canvas, exactly.
        repeat(4) { r.undoAndCheck("undo canvas op ${it + 1}") }
        r.assertState("back to the start", start)
        assertEquals(480, c.doc.width)
        assertSame(content, vec.vector)
        // Saved and reloaded at the new size after redo.
        repeat(4) { r.redoAndCheck("redo ${it + 1}") }
        val s2 = r.snapshot()
        r.saveAndReopen()
        r.assertState("reloaded", s2)
    }

    @Test
    fun alphaLockIsIgnoredByObjectsAndTheBrushSaysWhy() {
        val vec = drawing()
        c.toggleAlphaLock(vec)
        r.checkpoint("lock alpha")
        assertTrue(vec.alphaLocked)
        // A shape where nothing is painted: placed whole, not clipped away.
        r.tool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, fillColor = 0xFF40B060.toInt()) }
        r.stroke(330f to 200f, 380f to 240f, 440f to 300f)
        shape.commit()
        r.checkpoint("shape on an alpha-locked vector layer")
        val box = vec.vector!!.objects.last() as VShape
        assertTrue("painted where nothing was", (vec.bitmap.getPixel(385, 250) ushr 24) == 255)
        // A curve.
        r.tool(ToolId.POLYLINE)
        val poly = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        poly.update { it.copy(stroke = CurveStroke.PLAIN, fill = false) }
        r.tap(20f, 300f); r.tap(150f, 310f)
        poly.commit()
        r.checkpoint("polyline on an alpha-locked vector layer")
        assertTrue(vec.vector!!.objects.last() is VPath)
        // The bucket recolors the box.
        r.tool(ToolId.FILL)
        c.color = 0xFFE03030.toInt()
        r.tap(385f, 250f)
        r.checkpoint("bucket on an alpha-locked vector layer")
        assertEquals(0xFFE03030.toInt(), (vec.vector!!.byId(box.id) as VShape).shape.fillColor)
        // The eraser removes objects.
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        r.stroke(240f to 150f, 240f to 190f)
        r.checkpoint("erase on an alpha-locked vector layer")
        assertTrue(vec.vector!!.objects.none { it is VStroke && abs((it as VStroke).points.bounds().centerY() - 170f) < 20f })
        // The brush says why it can't draw (its live stroke would be clipped), no step.
        r.tool(ToolId.BRUSH)
        c.color = ink
        c.message = null
        r.stroke(40f to 120f, 400f to 120f)
        assertEquals(VectorStrokeCapture.ALPHA_LOCK_MESSAGE, c.message)
        r.checkpoint("brush refused", steps = 0)
        assertTrue(vec.isVectorLayer)
        assertTrue(VectorOps.bounds(box).width() > 100f)
    }
}
