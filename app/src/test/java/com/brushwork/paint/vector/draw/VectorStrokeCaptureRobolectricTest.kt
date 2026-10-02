package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.sin

/**
 * v1.5 A3: brush strokes on vector layers (§4.9, §6 A3): a stroke is ONE object and ONE undo
 * step whose cache is exactly its replay, equal to the same stroke on a raster layer; a
 * cancelled stroke leaves nothing; the selection doesn't clip it; tips that move pixels and alpha
 * lock are refused; the mask and raster layers paint pixels as before; the layer stays a vector
 * layer after every action.
 */
@RunWith(RobolectricTestRunner::class)
class VectorStrokeCaptureRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    /** Background, "Layer 1" (raster) and "Vector 1" (active). */
    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 2
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.color = 0xFF2060C0.toInt()
            // The tools are made now (a painting tool brings back its stored brush when it is made).
            it.tools
            it.selectTool(ToolId.BRUSH)
        }
    }

    private val EditorController.vec: Layer get() = doc.layers[2]
    private val EditorController.raster: Layer get() = doc.layers[1]

    private fun pixels(b: Bitmap): IntArray = IntArray(w * h).also { b.getPixels(it, 0, w, 0, 0, w, h) }

    /** A fresh render of [content] (as the cache must be). */
    private fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return pixels(b)
    }

    private fun wave(y0: Float, stylus: Boolean = false, n: Int = 30): List<ToolPoint> = List(n) { k ->
        val t = k / (n - 1f)
        ToolPoint(30f + 260f * t, y0 + 30f * sin(t * 7f), if (stylus) 0.3f + 0.6f * t else 1f, k.toLong(), isStylus = stylus)
    }

    private fun EditorController.draw(pts: List<ToolPoint>) {
        pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) pointerMove(p)
        pointerUp(pts.last())
    }

    @Test
    fun aStrokeIsOneObjectAndOneStepWhoseCacheIsItsReplay() {
        val c = setup()
        for ((i, id) in listOf("pen", "chalk", "pencil", "softround", "airbrush").withIndex()) {
            c.brush = BrushLibrary.byId(id) ?: BrushLibrary.defaultBrush
            c.draw(wave(40f + 40f * i, stylus = i % 2 == 1))
            assertEquals(i + 1, c.vec.vector!!.objects.size)
            assertEquals(i + 1, c.undoManager.undoCount)
            assertEquals("Brush", c.undoManager.undoLabel)
            val s = c.vec.vector!!.objects.last() as VStroke
            assertEquals(c.brush.id, s.preset.id)
            assertEquals(0xFF2060C0.toInt(), s.color)
            assertEquals(i % 2 == 1, s.stylus)
            assertArrayEquals("cache after the $id stroke", render(c.vec.vector!!), pixels(c.vec.bitmap))
        }
        val all = c.vec.vector!!
        val cache = pixels(c.vec.bitmap)
        c.undo()
        assertEquals(4, c.vec.vector!!.objects.size)
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        c.redo()
        assertEquals(all, c.vec.vector)
        assertArrayEquals(cache, pixels(c.vec.bitmap))
        repeat(5) { c.undo() }
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
    }

    @Test
    fun theStrokeHasTheSamePixelsAsOnARasterLayer() {
        val c = setup()
        // The pen has no random values: the same points give the same pixels whatever the seed.
        c.brush = BrushLibrary.defaultBrush.copy(size = 9f)
        val pts = wave(100f)
        c.draw(pts)
        assertEquals(1, c.vec.vector!!.objects.size)
        c.selectLayer(c.raster)
        c.draw(pts)
        assertNull(c.raster.vector)
        assertArrayEquals(pixels(c.raster.bitmap), pixels(c.vec.bitmap))
        // Every point the stroke got is in the object (down, moves, up).
        val s = c.vec.vector!!.objects.single() as VStroke
        assertEquals(pts.size, s.points.size)
        assertEquals(pts.last().x, s.points.x[s.points.size - 1])
    }

    @Test
    fun aCancelledStrokeLeavesNoObjectAndNoStep() {
        val c = setup()
        val pts = wave(80f)
        c.pointerDown(pts[0])
        for (p in pts.subList(1, 12)) c.pointerMove(p)
        c.pointerCancel()
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
        assertNull(c.renderOverride)
    }

    @Test
    fun aSelectionDoesNotClipAVectorStroke() {
        val c = setup()
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        Canvas(mask).drawRect(Rect(0, 0, 60, 60), Paint().apply { color = 0xFF000000.toInt() })
        c.setSelection(Selection.wrap(mask), recordUndo = false)
        c.draw(wave(150f))
        assertEquals("Selections don't limit strokes on vector layers", c.message)
        assertEquals(1, c.vec.vector!!.objects.size)
        // Painted far outside the selection, exactly as its replay.
        assertTrue(pixels(c.vec.bitmap).withIndex().any { (i, p) -> p != 0 && i % w > 100 })
        assertArrayEquals(render(c.vec.vector!!), pixels(c.vec.bitmap))
        // The hint is shown once.
        c.message = null
        c.draw(wave(190f))
        assertNull(c.message)
    }

    @Test
    fun tipsThatMovePixelsAndAlphaLockAreRefused() {
        val c = setup()
        c.brush = BrushLibrary.byId("watercolor")!!
        c.draw(wave(100f))
        assertEquals("Watercolor needs a raster layer — tap Vector to switch", c.message)
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue(pixels(c.vec.bitmap).all { it == 0 })
        // The smudge tool is stopped by the pointer-down gate.
        c.selectTool(ToolId.SMUDGE)
        c.draw(wave(100f))
        assertEquals("Smudge works on pixels — tap Vector to switch, or Rasterize this layer", c.message)
        assertEquals(0, c.undoManager.undoCount)
        // Alpha lock: the live stroke would be clipped to the pixels, its replay couldn't be.
        c.selectTool(ToolId.BRUSH)
        c.brush = BrushLibrary.defaultBrush
        c.vec.alphaLocked = true
        c.draw(wave(100f))
        assertEquals(VectorStrokeCapture.ALPHA_LOCK_MESSAGE, c.message)
        assertEquals(VectorContent.EMPTY, c.vec.vector)
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun theMaskAndRasterLayersArePaintedAsBefore() {
        val c = setup()
        c.draw(wave(60f))
        val content = c.vec.vector
        // The vector layer's mask: a normal mask stroke, the objects stay.
        c.addMask(c.vec, fromSelection = false)
        c.draw(wave(120f))
        assertEquals(content, c.vec.vector)
        assertNotNull(c.vec.mask)
        // A raster layer: a raster stroke, no data.
        c.selectLayer(c.raster)
        c.draw(wave(160f))
        assertNull(c.raster.vector)
        assertTrue(pixels(c.raster.bitmap).any { it != 0 })
        assertEquals(content, c.vec.vector)
    }
}
