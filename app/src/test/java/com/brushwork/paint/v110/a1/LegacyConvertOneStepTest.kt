package com.brushwork.paint.v110.a1

import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.ui.common.MirrorLabels110
import com.brushwork.paint.v110.a1.A1Kit.identity
import com.brushwork.paint.v110.a1.A1Kit.mirrorX
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.copies.CopyPick
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/**
 * v1.10 (R1 (a), C9): a legacy one-shape layer whose shape has live copies takes part in the side
 * picks. A tap on its copy picks that side (the layer converts in place and is held, no step);
 * ✕, or ✓ with nothing moved, leaves the shape layer as it was (its data and pixels) with no step;
 * ✓ after a move is ONE step "Transform objects" that converts the layer and moves the side, still
 * linked; undo gives the shape layer back, redo the vector layer. Delete one side is one step too.
 */
@RunWith(RobolectricTestRunner::class)
class LegacyConvertOneStepTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    private fun setUp(): Pair<EditorController, Layer> {
        val c = kit.controller()
        val o = kit.rectangle(90f, 120f, 60f, 40f).shape.copy(copies = CopyMaps.of(listOf(identity(), mirrorX())))
        val layer = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, kit.w, kit.h))!!
        assertSame(layer, c.activeLayer)
        assertTrue(layer.isShapeLayer)
        return c to layer
    }

    /** Pixels of [layer] whose alpha differs from [was] by more than 8 (antialiasing of a re-render). */
    private fun differ(layer: Layer, was: IntArray): Int {
        val now = kit.pixels(layer.bitmap)
        return now.indices.count { abs(Color.alpha(now[it]) - Color.alpha(was[it])) > 8 }
    }

    /** Taps the copy with a new tap cycle (the tool chosen again) and waits for its side to be lifted. */
    private fun pickCopy(c: EditorController): TransformTool {
        c.selectTool(ToolId.BRUSH)
        val tool = kit.transform(c)
        c.tap(422f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 1 })
        return tool
    }

    @Test
    fun aSideOfALegacyShapeLayerIsOneStepAndCrossLeavesTheShapeLayer() {
        val (c, layer) = setUp()
        val shapeData = layer.shapeData
        val pixels = kit.pixels(layer.bitmap)
        val steps = c.undoManager.undoCount

        // The tap picks the side of the converted (held) layer: no step yet.
        var tool = pickCopy(c)
        assertTrue("converted while picked", layer.isVectorLayer)
        assertEquals(steps, c.undoManager.undoCount)

        // ✕: the shape layer as it was, nothing recorded.
        tool.discard()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("✕: a shape layer again", layer.isShapeLayer)
        assertEquals(shapeData, layer.shapeData)
        assertNull(layer.vector)
        assertEquals(steps, c.undoManager.undoCount)
        assertTrue("✕: the same pixels", differ(layer, pixels) <= 8)
        assertNull("no object selection on a shape layer", c.vectors.selectedLayer)

        // ✓ with nothing moved: the same.
        tool = pickCopy(c)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("unchanged ✓: a shape layer again", layer.isShapeLayer)
        assertEquals(steps, c.undoManager.undoCount)

        // A move, then ✓: one step that converts and moves the side, still linked.
        tool = pickCopy(c)
        tool.moveBy(0f, 60f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertTrue("a vector layer now", layer.isVectorLayer)
        val o = layer.vector!!.objects.single()
        assertEquals("still linked", 2, ObjectCopies.of(o).maps.size)
        assertEquals("the side moved", 180f, CopyPick.boundsOf(o, 1).centerY(), 1f)
        assertEquals("the source stayed", 120f, CopyPick.boundsOf(o, 0).centerY(), 1f)

        // Undo: the shape layer back (its data and pixels); redo: the vector layer.
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("undo: a shape layer", layer.isShapeLayer)
        assertEquals(shapeData, layer.shapeData)
        assertNull(layer.vector)
        assertTrue("undo: the pixels before", differ(layer, pixels) <= 8)
        c.redo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("redo: the vector layer", layer.isVectorLayer)
        assertEquals(180f, CopyPick.boundsOf(layer.vector!!.objects.single(), 1).centerY(), 1f)
    }

    /**
     * Review fix: with the copy picked, a tap on the source picks the source (the conversion stays
     * held, no step), not the shape layer's data lift of all sides; ✕ then leaves the shape layer;
     * a re-pick, a move and ✓ are ONE step that moves the source while the copy stays.
     */
    @Test
    fun tappingTheOtherSideOfALegacyShapeLayerPicksThatSide() {
        val (c, layer) = setUp()
        val shapeData = layer.shapeData
        val steps = c.undoManager.undoCount

        var tool = pickCopy(c)
        c.tap(90f, 120f)
        assertTrue("the source picked", kit.idleUntil { VectorLift.activeLift(c)?.side == 0 })
        assertTrue("still held as a vector layer", layer.isVectorLayer)
        assertTrue(tool.sidePicked)
        assertEquals("no step", steps, c.undoManager.undoCount)

        // ✕: the shape layer as it was, nothing recorded.
        tool.discard()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("✕: a shape layer again", layer.isShapeLayer)
        assertEquals(shapeData, layer.shapeData)
        assertEquals(steps, c.undoManager.undoCount)

        // Picked again, the source moved, ✓: one step; the copy stays where it is drawn.
        tool = pickCopy(c)
        c.tap(90f, 120f)
        assertTrue(kit.idleUntil { VectorLift.activeLift(c)?.side == 0 })
        tool.moveBy(0f, 60f)
        tool.commit()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        assertTrue(layer.isVectorLayer)
        val o = layer.vector!!.objects.single()
        assertEquals("still linked", 2, ObjectCopies.of(o).maps.size)
        assertEquals("the source moved", 180f, CopyPick.boundsOf(o, 0).centerY(), 1f)
        assertEquals("the copy stayed", 120f, CopyPick.boundsOf(o, 1).centerY(), 1f)
        assertEquals("the copy stayed", 422f, CopyPick.boundsOf(o, 1).centerX(), 1f)
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue("undo: a shape layer", layer.isShapeLayer)
        assertEquals(shapeData, layer.shapeData)
    }

    @Test
    fun deleteOneSideOfALegacyShapeLayerIsOneStep() {
        val (c, layer) = setUp()
        val shapeData = layer.shapeData
        val steps = c.undoManager.undoCount
        val tool = pickCopy(c)
        assertEquals(MirrorLabels110.DELETE_SIDE, tool.objectDeletion?.deleteLabel)
        tool.objectDeletion!!.delete()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(MirrorLabels110.DELETE_SIDE, c.undoManager.undoLabel)
        assertTrue(layer.isVectorLayer)
        assertTrue("plain: the copy went", ObjectCopies.of(layer.vector!!.objects.single()).isEmpty)
        c.undo()
        assertTrue(kit.idleUntil { !c.vectors.isRendering })
        assertTrue(layer.isShapeLayer)
        assertEquals(shapeData, layer.shapeData)
    }
}
