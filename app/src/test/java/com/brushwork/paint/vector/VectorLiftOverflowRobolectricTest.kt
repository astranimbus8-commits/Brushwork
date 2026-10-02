package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.5 review (B-e): lifting every object of a vector layer renders only what lies PAST the
 * canvas (the cache is copied for the rest). Before, every object reaching past an edge was
 * replayed whole for the Transform preview — a long stroke that merely touched the edge cost its
 * full replay on each Transform activation, and on the phone that went to the background with
 * the "Rendering vectors…" overlay.
 */
@RunWith(RobolectricTestRunner::class)
class VectorLiftOverflowRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 600
    private val h = 400

    private fun setup(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    /** A long wavy pen stroke from [x0] to [x1] at about [y]. */
    private fun stroke(x0: Float, x1: Float, y: Float): VStroke {
        val n = 160
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y + 40f * kotlin.math.sin(it / 9f) }
        return VStroke(0, preset = BrushLibrary.byId("pen")!!.copy(size = 16f), color = 0xFF2050C0.toInt(), seed = 3L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    @Test
    fun theOffCanvasBandsAreTheFloatingRectMinusTheDocument() {
        val doc = Rect(0, 0, 600, 400)
        fun area(rs: List<Rect>) = rs.sumOf { it.width().toLong() * it.height() }
        for (r in listOf(Rect(-100, -50, 700, 450), Rect(20, 30, 612, 380), Rect(700, 50, 900, 300), Rect(0, -300, 600, -100), Rect(-40, 350, 100, 420), Rect(10, 10, 590, 390))) {
            val bands = VectorLayers.outside(r, doc)
            val inside = Rect(r).let { if (it.intersect(doc)) it.width().toLong() * it.height() else 0L }
            assertEquals("$r: bands + canvas part = the rect", r.width().toLong() * r.height(), area(bands) + inside)
            for (b in bands) {
                assertFalse("$r: $b is past the canvas", Rect.intersects(b, doc))
                assertTrue("$r: $b is inside the rect", Rect(r).contains(b))
            }
            for (i in bands.indices) for (j in i + 1 until bands.size) assertFalse("$r: disjoint", Rect.intersects(bands[i], bands[j]))
        }
        assertTrue(VectorLayers.outside(Rect(10, 10, 590, 390), doc).isEmpty())
    }

    @Test
    fun aStrokeThatJustReachesPastAnEdgeIsNotReplayedWholeForThePreview() {
        val c = setup()
        val layer = c.doc.layers[0]
        // Across the whole canvas, its end a few px past the right edge.
        c.vectors.addObjects(layer, listOf(stroke(10f, 596f, 200f)), "Add")
        val content = layer.vector!!
        val bounds = VectorOps.bounds(content.objects.single())
        assertTrue("reaches past the right edge: $bounds", bounds.right > w)
        val fr = Rect(kotlin.math.floor(bounds.left).toInt(), kotlin.math.floor(bounds.top).toInt(), kotlin.math.ceil(bounds.right).toInt(), kotlin.math.ceil(bounds.bottom).toInt())
        val whole = VectorLayerRenderer.estimateUnits(content, fr)
        val past = VectorLayerRenderer.estimateUnits(content, VectorLayers.outside(fr, Rect(0, 0, w, h)))
        assertTrue("the part past the edge is a small share: $past of $whole", past * 4 < whole)
        // A main-thread budget between the two: the off-canvas part fits it, the whole replay doesn't.
        c.vectors.policy = VectorLayers.Policy.AUTO
        c.vectors.nsPerUnit = 1.0
        c.vectors.syncBudgetMs = (past + whole) / 2 / 1e6
        var session: VectorEditSession? = null
        var answered = false
        c.vectors.beginEdit(layer, setOf(content.objects.single().id)) { answered = true; session = it }
        assertTrue("prepared at once, on the main thread", answered)
        assertFalse(c.vectors.isRendering)
        assertNotNull(session)
        val s = session!!
        val floating: Bitmap = s.floating!!
        val f = s.floatingRect
        fun alphaAt(x: Int, y: Int) = floating.getPixel(((x - f.left) * s.floatingScale).toInt(), ((y - f.top) * s.floatingScale).toInt()) ushr 24
        // The end of the stroke past the canvas shows in the preview.
        val endY = (200f + 40f * kotlin.math.sin(159 / 9f)).toInt()
        assertTrue("past the edge", (endY - 3..endY + 3).any { y -> alphaAt(w + 2, y) > 0 })
        // On the canvas: the cache itself.
        for (y in maxOf(f.top, 0) until minOf(f.bottom, h) step 3) for (x in maxOf(f.left, 0) until w step 3) {
            assertEquals("at $x,$y", layer.bitmap.getPixel(x, y), floating.getPixel(((x - f.left) * s.floatingScale).toInt(), ((y - f.top) * s.floatingScale).toInt()))
        }
        s.cancel()
    }
}
