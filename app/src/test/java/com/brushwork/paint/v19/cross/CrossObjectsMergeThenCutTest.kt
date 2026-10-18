package com.brushwork.paint.v19.cross

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeStroke
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.v19.a9.A9Rig
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorDrawState
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.draw.VectorEraserRecorder
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 Gate 2 cross test X2 (design 3.4; A4 x A5; R19, R11 b): two shape layers drawn with the
 * Shape tool ("New layer each time") merge down into one vector layer that keeps both shapes as
 * objects (R19: the type is kept, the picture unchanged), ONE step "Merge down"; the partial
 * eraser then cuts one of the shapes into editable paths in its place, ONE step "Erase", the
 * other shape untouched. Undo of the cut gives back the merged layer exactly, undo of the merge
 * both shape layers exactly (data, properties, pixels, the layer list); redo both the cut layer.
 */
@RunWith(RobolectricTestRunner::class)
class CrossObjectsMergeThenCutTest {
    private val rig = A9Rig()

    @After
    fun close() = rig.close()

    private val blue = 0xFF3070C0.toInt()
    private val white = 0xFFFFFFFF.toInt()

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle(c: EditorController) {
        Snapshot.sendApplyNotifications()
        c.vectors.flushPending()
        repeat(400) {
            idle()
            if (!c.vectors.isRendering) return
            Thread.sleep(5)
        }
        throw AssertionError("the layer is still rendering")
    }

    private fun pixels(b: Bitmap): List<Int> = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.toList()

    /** The document flattened on white. */
    private fun picture(c: EditorController): List<Int> {
        val b = Compositor(c.doc) { null }.renderFlattened(white)
        return pixels(b).also { b.recycle() }
    }

    /** A fresh rendering of [content] over the whole document. */
    private fun rendered(c: EditorController, content: VectorContent): List<Int> {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        val doc = Rect(0, 0, c.doc.width, c.doc.height)
        VectorLayerRenderer.render(Canvas(b), content, doc, tips = TipCache(), document = doc)
        return pixels(b).also { b.recycle() }
    }

    /** One layer as the user has it: its place, properties, data and pixels. */
    private data class LayerSnap(val id: Long, val props: LayerProps, val data: LayerData, val pixels: List<Int>)

    /** The whole document: every layer, and which one is active. */
    private data class DocSnap(val layers: List<LayerSnap>, val active: Int)

    private fun snap(c: EditorController): DocSnap {
        settle(c)
        return DocSnap(c.doc.layers.map { LayerSnap(it.id, it.props(), it.dataSnapshot(), pixels(it.bitmap)) }, c.doc.activeLayerIndex)
    }

    @Test
    fun twoShapeLayersMergeIntoObjectsThenOneShapeIsCutEachInOneStepThatUndoesExactly() {
        val c = rig.controller()
        c.snapping.enabled = false
        val shape = rig.shapeTool(c)
        // A rectangle (25, 35) - (95, 85), then an ellipse (135, 85) - (205, 135), each on a shape layer of its own.
        shape.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, strokeWith = ShapeStroke.PLAIN, useBrushSize = false,
                strokeWidth = 4f, fillColor = blue, editable = true, newLayerEachTime = true,
            )
        }
        rig.drag(c, 25f to 35f, 60f to 60f, 95f to 85f)
        shape.commit()
        val lower = c.activeLayer
        shape.update { it.copy(type = ShapeType.ELLIPSE) }
        rig.drag(c, 135f to 85f, 170f to 110f, 205f to 135f)
        shape.commit()
        val upper = c.activeLayer
        assertEquals("two shape layers", listOf(true, true), listOf(lower, upper).map { it.isShapeLayer && !it.isVectorLayer })
        assertEquals("the ellipse's layer right above the rectangle's", c.doc.indexOf(lower) + 1, c.doc.indexOf(upper))
        val unmerged = snap(c)
        val drawn = picture(c)
        val lowerName = lower.name

        // Merge down: ONE step, the objects kept (R19), the picture as it was.
        assertEquals(MergeKind.VECTOR_OBJECTS, c.layerMerge.kindOf(upper, lower))
        val steps = c.undoManager.undoCount
        c.mergeDown(upper)
        settle(c)
        assertEquals("the merge is ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertEquals("the ellipse's layer went", -1, c.doc.indexOf(upper))
        assertSame(lower, c.activeLayer)
        assertEquals("the lower layer's name", lowerName, lower.name)
        assertNull("converted", lower.shapeData)
        val merged = lower.vector ?: throw AssertionError("R19: a vector layer, not pixels")
        val (rect, ellipse) = merged.objects.also { assertEquals(2, it.size) }
        assertTrue("both still shapes", rect is VShape && ellipse is VShape)
        assertEquals(ShapeType.RECTANGLE, (rect as VShape).shape.type)
        assertEquals(ShapeType.ELLIPSE, (ellipse as VShape).shape.type)
        assertEquals("the picture as it was", drawn, picture(c))
        val mergedSnap = snap(c)

        // The partial eraser down across the rectangle only: ONE step, the ellipse untouched.
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        VectorDrawState.of(c).cutDispatcher = Dispatchers.Unconfined
        c.pointerDown(ToolPoint(60f, 10f))
        c.pointerMove(ToolPoint(60f, 60f))
        c.pointerMove(ToolPoint(60f, 120f))
        c.pointerUp(ToolPoint(60f, 120f))
        settle(c)
        assertNull("the erase's preview went", c.renderOverride)
        assertEquals("the cut is ONE step", steps + 2, c.undoManager.undoCount)
        assertEquals(VectorEraserRecorder.ERASE_LABEL, c.undoManager.undoLabel)
        val cut = lower.vector ?: throw AssertionError("still a vector layer (I20)")
        assertEquals("two pieces of the rectangle, then the ellipse", 3, cut.objects.size)
        assertEquals("the ellipse untouched", ellipse, cut.objects[2])
        val pieces = cut.objects.take(2).map { it as? VPath ?: throw AssertionError("a piece is an editable path, not $it") }
        assertEquals("the first piece keeps the rectangle's id", rect.id, pieces[0].id)
        assertTrue("the pieces keep the fill", pieces.all { it.fill != null })
        assertEquals("the cut is a gap", 0, lower.bitmap.getPixel(60, 60) ushr 24)
        assertEquals("beside it the fill stays", blue, lower.bitmap.getPixel(35, 60))
        assertEquals("the ellipse's pixels stay", blue, lower.bitmap.getPixel(170, 110))
        assertEquals("I1: the layer is a fresh rendering of its objects", rendered(c, cut), pixels(lower.bitmap))
        val cutSnap = snap(c)

        // Undo the cut, then the merge: each gives back exactly what was there.
        c.undo()
        assertEquals("undo of the cut: the merged layer exactly", mergedSnap, snap(c))
        c.undo()
        assertEquals("undo of the merge: both shape layers exactly", unmerged, snap(c))
        assertEquals(steps, c.undoManager.undoCount)
        assertEquals(drawn, picture(c))
        c.redo()
        assertEquals("redo of the merge", mergedSnap, snap(c))
        c.redo()
        assertEquals("redo of the cut", cutSnap, snap(c))
        assertEquals(steps + 2, c.undoManager.undoCount)
    }
}
