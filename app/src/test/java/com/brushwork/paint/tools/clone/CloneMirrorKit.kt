package com.brushwork.paint.tools.clone

import android.graphics.Bitmap
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs
import kotlin.math.hypot
import kotlin.math.roundToInt

/**
 * v1.8 D: shared pieces of the clone-with-symmetry tests ([CloneMirrorTest], [CloneMirrorEdgeTest]).
 *
 * The layer holds a linear gradient ([pattern]: red = x, green = y), so a bilinear sample at any
 * point inside the canvas is known exactly ([expectedAt]), and [checkCopies] can verify every
 * pixel of every copy against the rule "copy k at q shows S(G_k(q))", G_k = M_k·T(−o)·M_k⁻¹
 * (S(q − A_k·o) for an affine map), without knowing how the engine got there.
 */
internal object CloneMirrorKit {
    const val BLUE_CHANNEL = 0x40

    /** A controller with two layers, the second active, the Clone stamp selected with a hard [size] px pen. */
    fun setup(scope: CoroutineScope, w: Int, h: Int, size: Float = 10f, opacity: Float = 1f): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        app.getSharedPreferences(BrushPresetStore.PREFS_NAME, android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val doc = Document("mirror", "Mirror", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        val c = EditorController(app, doc, scope, settings)
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.CLONE)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(
            ToolId.CLONE,
            pen.copy(size = size, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = opacity, flow = 1f, taperStart = 0f, taperEnd = 0f),
        )
        return c
    }

    fun tool(c: EditorController) = c.tools.getValue(ToolId.CLONE) as CloneTool

    /** Red = x, green = y, blue = [BLUE_CHANNEL], opaque (the canvas is at most 256 px a side). */
    fun pattern(b: Bitmap) {
        val w = b.width
        val h = b.height
        require(w <= 256 && h <= 256)
        val px = IntArray(w * h) { i -> (0xFF shl 24) or ((i % w) shl 16) or ((i / w) shl 8) or BLUE_CHANNEL }
        b.setPixels(px, 0, w, 0, 0, w, h)
    }

    fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A straight stroke through the controller (pointer gate, stroke assist). */
    fun stroke(c: EditorController, x0: Float, y0: Float, x1: Float, y1: Float, steps: Int = 8) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1 until steps) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / steps, y0 + (y1 - y0) * i / steps))
        c.pointerUp(ToolPoint(x1, y1))
    }

    /** The 3 × 3 row-major map [m] applied to ([x], [y]). */
    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray {
        val w = m[6] * x + m[7] * y + m[8]
        return doubleArrayOf((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    fun inverse(f: FloatArray): DoubleArray {
        val m = DoubleArray(9) { f[it].toDouble() }
        val a = m[4] * m[8] - m[5] * m[7]
        val b = m[5] * m[6] - m[3] * m[8]
        val cc = m[3] * m[7] - m[4] * m[6]
        val det = m[0] * a + m[1] * b + m[2] * cc
        val inv = doubleArrayOf(
            a, m[2] * m[7] - m[1] * m[8], m[1] * m[5] - m[2] * m[4],
            b, m[0] * m[8] - m[2] * m[6], m[2] * m[3] - m[0] * m[5],
            cc, m[1] * m[6] - m[0] * m[7], m[0] * m[4] - m[1] * m[3],
        )
        return DoubleArray(9) { inv[it] / det }
    }

    private fun distToSegment(px: Double, py: Double, ax: Double, ay: Double, bx: Double, by: Double): Double {
        val vx = bx - ax
        val vy = by - ay
        val len2 = vx * vx + vy * vy
        val t = if (len2 == 0.0) 0.0 else (((px - ax) * vx + (py - ay) * vy) / len2).coerceIn(0.0, 1.0)
        return hypot(px - (ax + t * vx), py - (ay + t * vy))
    }

    /**
     * The gradient sampled bilinearly at ([u], [v]) (document px, continuous), or null within a
     * pixel and a half of the canvas edge (where the edge treatment decides).
     */
    fun expectedAt(u: Double, v: Double, w: Int, h: Int): IntArray? {
        if (u < 1.5 || v < 1.5 || u > w - 1.5 || v > h - 1.5) return null
        return intArrayOf((u - 0.5).roundToInt(), (v - 0.5).roundToInt())
    }

    /** True when ([u], [v]) lies more than a pixel and a half outside the canvas. */
    fun outside(u: Double, v: Double, w: Int, h: Int): Boolean = u < -1.5 || v < -1.5 || u > w + 1.5 || v > h + 1.5

    /**
     * Checks every pixel of [after] (the layer after a stroke along ([ax], [ay])–([bx], [by])
     * with offset ([dx], [dy]) and [maps], the identity first, over the gradient [before]):
     *
     * - a pixel no copy comes within [rOut] of (in the coverage's space) is unchanged;
     * - a pixel only copy k reaches, within [rIn] of the path, shows the gradient at
     *   G_k(q) = M_k(M_k⁻¹(q) − o) within [tol] per channel (the original, k = 0, exactly) when
     *   that lies inside the canvas, and is unchanged when it lies outside (nothing is copied
     *   from outside: no repeated edge).
     *
     * Returns how many pixels were checked per copy.
     */
    fun checkCopies(
        after: IntArray, before: IntArray, w: Int, h: Int, maps: List<FloatArray>,
        ax: Float, ay: Float, bx: Float, by: Float, dx: Int, dy: Int,
        rIn: Double, rOut: Double, tol: Int,
    ): IntArray {
        val fwd = maps.map { f -> DoubleArray(9) { f[it].toDouble() } }
        val inv = maps.map { inverse(it) }
        val checked = IntArray(maps.size)
        val touched = IntArray(maps.size)
        for (j in 0 until h) for (i in 0 until w) {
            val idx = j * w + i
            val qx = i + 0.5
            val qy = j + 0.5
            var n = 0
            var only = -1
            var onlyP: DoubleArray? = null
            for (k in maps.indices) {
                val m = inv[k]
                val wq = m[6] * qx + m[7] * qy + m[8]
                if (wq <= 0.0) continue
                val p = apply(m, qx, qy)
                val d = distToSegment(p[0], p[1], ax.toDouble(), ay.toDouble(), bx.toDouble(), by.toDouble())
                if (d < rOut) {
                    n++
                    if (d <= rIn) {
                        only = k
                        onlyP = p
                    }
                }
            }
            if (n == 0) {
                if (after[idx] != before[idx]) fail("($i, $j): no copy reaches it, yet it changed: ${hex(before[idx])} -> ${hex(after[idx])}")
                continue
            }
            if (n != 1 || only < 0) continue
            val p = onlyP!!
            val s = apply(fwd[only], p[0] - dx, p[1] - dy)
            if (outside(s[0], s[1], w, h)) {
                if (after[idx] != before[idx]) fail("($i, $j), copy $only samples (${s[0]}, ${s[1]}) outside the canvas, yet it changed: ${hex(after[idx])}")
                checked[only]++
                touched[only]++
                continue
            }
            val e = expectedAt(s[0], s[1], w, h) ?: continue
            val got = after[idx]
            val r = (got shr 16) and 0xFF
            val g = (got shr 8) and 0xFF
            val t = if (only == 0) 0 else tol
            if (got ushr 24 != 0xFF || (got and 0xFF) != BLUE_CHANNEL || abs(r - e[0]) > t || abs(g - e[1]) > t) {
                fail("($i, $j), copy $only: expected the gradient at (${s[0]}, ${s[1]}) = (${e[0]}, ${e[1]}), got ${hex(got)}")
            }
            checked[only]++
        }
        return checked
    }

    fun hex(c: Int) = "#" + Integer.toHexString(c).padStart(8, '0')

    fun assertAllChecked(what: String, checked: IntArray, least: Int) {
        for (k in checked.indices) if (checked[k] < least) fail("$what: copy $k has only ${checked[k]} pixels checked (${checked.toList()})")
        assertEquals(checked.size, checked.count { it >= least })
    }
}
