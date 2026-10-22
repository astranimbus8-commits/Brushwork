package com.brushwork.paint.v110.a6

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue

/**
 * Renders of single vector objects for the A6 "Turn into shape" tests (v1.10, R11): an object
 * alone on a [W] × [H] layer, as the vector layer renderer draws it, and "within 1 px" compares
 * of two renders' ink (alpha ≥ 128).
 */
internal object A6Render {
    const val W = 400
    const val H = 300

    /** [o] alone, rendered on a clear [W] × [H] layer: its ARGB pixels. */
    fun render(o: VObject): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        VectorLayerRenderer.render(Canvas(b), VectorContent(objects = listOf(o.withId(1L)), nextId = 2), Rect(0, 0, W, H), tips = TipCache(), document = Rect(0, 0, W, H))
        val px = IntArray(W * H)
        b.getPixels(px, 0, W, 0, 0, W, H)
        b.recycle()
        return px
    }

    /** The inked pixels (alpha ≥ 128) of [px]. */
    fun ink(px: IntArray): BooleanArray = BooleanArray(px.size) { (px[it] ushr 24) >= 128 }

    /** Pixels inked in [a] with no ink of [b] at or next to them (8 neighbours). */
    fun strays(a: BooleanArray, b: BooleanArray): Int {
        var n = 0
        for (y in 0 until H) for (x in 0 until W) {
            if (!a[y * W + x]) continue
            var near = false
            for (dy in -1..1) for (dx in -1..1) {
                val xx = x + dx
                val yy = y + dy
                if (xx in 0 until W && yy in 0 until H && b[yy * W + xx]) near = true
            }
            if (!near) n++
        }
        return n
    }

    /** [a] and [b] draw the same ink within 1 px both ways, and some ink. */
    fun assertWithinOnePx(what: String, a: VObject, b: VObject) {
        val ia = ink(render(a))
        val ib = ink(render(b))
        assertTrue("$what: something is drawn", ia.count { it } > 100)
        assertEquals("$what: ink of the first not within 1 px of the second's", 0, strays(ia, ib))
        assertEquals("$what: ink of the second not within 1 px of the first's", 0, strays(ib, ia))
    }
}
