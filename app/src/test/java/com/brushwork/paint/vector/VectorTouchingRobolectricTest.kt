package com.brushwork.paint.vector

import android.graphics.Path
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Selection
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.geom.StrokeHits
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin
import kotlin.random.Random

/**
 * v1.5 A1 review: [VectorOps.touching] (Lasso / Select shape on a vector layer, Clear, Duplicate
 * with a selection) tests only the part of a stroke's dab chain near the selection and accepts a
 * stroke as soon as a dab is centred on a selected pixel: a lasso around hundreds of strokes
 * answers in a few frames instead of seconds, with the same answer.
 */
@RunWith(RobolectricTestRunner::class)
class VectorTouchingRobolectricTest {
    private fun strokes(count: Int, w: Int, h: Int, seed: Int): VectorContent {
        val rnd = Random(seed)
        val objs = ArrayList<VObject>()
        for (k in 0 until count) {
            val n = 160
            val x0 = rnd.nextFloat() * w; val y0 = rnd.nextFloat() * h
            val a = rnd.nextFloat() * 6.28f
            val preset = if (k % 3 == 0) BrushLibrary.byId("gpen")!! else BrushLibrary.defaultBrush.copy(size = 4f + (k % 5) * 3f)
            val xs = FloatArray(n) { x0 + cos(a) * it * 1.5f + sin(it / 9f) * 10f }
            val ys = FloatArray(n) { y0 + sin(a) * it * 1.5f }
            objs += VStroke(0, preset = preset, color = -16777216, seed = k.toLong(), stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
        }
        return VectorContent.EMPTY.plus(objs).first
    }

    private fun disc(cx: Float, cy: Float, r: Float, w: Int, h: Int) = Selection.fromPath(Path().apply { addCircle(cx, cy, r, Path.Direction.CW) }, w, h)

    @Test
    fun strokesClearlyInsideAreTouchedAndStrokesClearlyOutsideAreNot() {
        val w = 400; val h = 300
        val content = strokes(60, w, h, 7)
        val sel = disc(200f, 150f, 80f, w, h)
        val got = VectorOps.touching(content, sel)
        val bytes = BitmapUtils.alpha8ToBytes(sel.mask)
        val stride = bytes.size / h
        var inside = 0
        var outside = 0
        for (o in content.objects) {
            o as VStroke
            // The stroke's distance to the selected pixels (pixel centres): to its dabs' discs.
            val d = StrokeHits.dabs(o)
            var best = Float.POSITIVE_INFINITY
            for (i in 0 until d.size / 3) {
                val cx = d[3 * i]; val cy = d[3 * i + 1]; val r = d[3 * i + 2]
                val e = (r + 4f).toInt()
                for (y in (cy.toInt() - e)..(cy.toInt() + e)) for (x in (cx.toInt() - e)..(cx.toInt() + e)) {
                    if (x < 0 || y < 0 || x >= w || y >= h || bytes[y * stride + x].toInt() == 0) continue
                    val dist = hypot(x + 0.5f - cx, y + 0.5f - cy) - r
                    if (dist < best) best = dist
                }
            }
            if (best <= -1f) { inside++; assertTrue("stroke ${o.id} paints selected pixels", o.id in got) }
            if (best >= 2f) { outside++; assertTrue("stroke ${o.id} is ${best}px from the selection", o.id !in got) }
        }
        assertTrue("both kinds occur ($inside inside, $outside outside)", inside > 5 && outside > 5)
    }

    @Test
    fun aLassoAroundHundredsOfStrokesIsQuick() {
        val w = 1024; val h = 1024
        val content = strokes(500, w, h, 3)
        ObjectIndex.of(content)
        val sel = disc(512f, 512f, 300f, w, h)
        // Warm up both (class loading, JIT).
        VectorOps.touching(content, sel)
        StrokeHits.clear()
        val t0 = System.nanoTime()
        for (o in content.objects) StrokeHits.dabs(o as VStroke)
        val chains = System.nanoTime() - t0
        val t1 = System.nanoTime()
        val ids = VectorOps.touching(content, sel)
        val touching = System.nanoTime() - t1
        assertTrue(ids.isNotEmpty() && ids.size < content.objects.size)
        // Testing every dab of every stroke against the selection took 30-60 times as long as
        // computing the dab chains; now it is a few times that (generous bound: busy machines).
        assertTrue("touching ${touching / 1e6} ms vs dab chains ${chains / 1e6} ms", touching < 25 * chains + 50_000_000L)
    }
}
