package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.5 A1 review: layer operations on vector layers keep the objects in the cases the raster
 * operation also handles (merging down into a locked or hidden vector layer).
 */
@RunWith(RobolectricTestRunner::class)
class VectorReviewOpsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.layers += Layer(doc.newLayerId(), "Vector 2", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 2
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private val EditorController.v1: Layer get() = doc.layers.first { it.name == "Vector 1" }
    private val EditorController.v2: Layer get() = doc.layers.first { it.name == "Vector 2" }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private fun maxDiff(a: IntArray, b: IntArray): Int {
        var m = 0
        for (i in a.indices) for (s in intArrayOf(24, 16, 8, 0)) m = max(m, abs(((a[i] ushr s) and 0xFF) - ((b[i] ushr s) and 0xFF)))
        return m
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, seed: Long = 3L): VStroke {
        val n = 12
        return VStroke(
            0, preset = BrushLibrary.defaultBrush.copy(size = 9f), color = 0xFF2050C0.toInt(), seed = seed, stylus = false,
            points = PackedPoints(FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }, FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }, FloatArray(n) { 1f }),
        )
    }

    private fun box(l: Float, t: Float, r: Float, b: Float) = VPath(
        0, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = VPaint.Solid(0xFFE04020.toInt()),
    )

    @Test
    fun mergingIntoALockedOrHiddenVectorLayerKeepsTheObjects() {
        for (prepare in listOf<(Layer) -> Unit>({ it.locked = true }, { it.visible = false })) {
            val c = setup()
            c.vectors.addObjects(c.v1, listOf(box(20f, 20f, 160f, 120f)), "Add")
            c.vectors.addObjects(c.v2, listOf(stroke(10f, 200f, 300f, 30f, 8L), box(200f, 150f, 300f, 230f)), "Add")
            val lower = c.v1.vector!!
            val lowerPixels = px(c.v1.bitmap)
            val upper = c.v2
            prepare(c.v1)
            val steps = c.undoManager.undoCount
            c.mergeDown(upper)
            assertEquals(-1, c.doc.indexOf(upper))
            val merged = c.v1.vector!!
            assertEquals("the objects stay editable", 3, merged.objects.size)
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals("Merge down", c.undoManager.undoLabel)
            assertTrue(maxDiff(fresh(merged), px(c.v1.bitmap)) <= 2)
            c.undo()
            assertSame(lower, c.v1.vector)
            assertArrayEquals(lowerPixels, px(c.v1.bitmap))
            assertTrue(c.doc.indexOf(upper) >= 0)
            c.redo()
            assertEquals(3, c.v1.vector!!.objects.size)
        }
    }

    @Test
    fun mergingAnEmptyVectorLayerIsADataStep() {
        val c = setup()
        c.vectors.addObjects(c.v1, listOf(box(20f, 20f, 160f, 120f)), "Add")
        val pixels = px(c.v1.bitmap)
        val steps = c.undoManager.undoCount
        c.mergeDown(c.v2)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(1, c.v1.vector!!.objects.size)
        assertArrayEquals(pixels, px(c.v1.bitmap))
        c.undo()
        assertEquals(3, c.doc.layers.size)
    }
}
