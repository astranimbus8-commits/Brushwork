package com.brushwork.paint.model

import android.graphics.RectF
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.vector.VStop
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/** One drawn copy: k = 0 is the source (matrix identity, look null). */
class ArrayInstance(val k: Int, val matrix: FloatArray, val look: CopyLook?) {
    override fun toString(): String = "ArrayInstance($k, ${matrix.toList()}, $look)"
}

/**
 * What a copy's draw applies, besides its matrix: its [opacity] (0..1, multiplied into the
 * copy's alpha) and its tint ([tintColor] at [tintStrength], see [ArrayTint]; strength 0 = none).
 *
 * v1.10 (R5): [colorMode] says how [tintColor] applies: [ArrayColorMode.TINT] is v1.9's tint
 * ([ArrayTint]); [ArrayColorMode.RECOLOR] replaces the copy's colors with [tintColor] at
 * [tintStrength] as `ColorUtils.recolor` does (Change Drawing Color's math). A copy without a
 * tint always says TINT, so a look without one draws and compares as in v1.9.
 */
data class CopyLook(
    val opacity: Float,
    val tintColor: Int,
    val tintStrength: Float,
    val colorMode: ArrayColorMode = ArrayColorMode.TINT,
) {
    /**
     * The 4x5 color matrix for ColorMatrixColorFilter; null when tintStrength == 0. v1.10 (R5):
     * RECOLOR gives `ColorUtils.recolorMatrix` (within +-1 of `ColorUtils.recolor`; `ArrayDraw`
     * keeps its filters in an LRU).
     */
    fun colorMatrix(): FloatArray? = when {
        tintStrength == 0f -> null
        colorMode == ArrayColorMode.RECOLOR -> ColorUtils.recolorMatrix(tintColor, tintStrength).array
        else -> ArrayTint.matrix(tintColor, tintStrength)
    }
}

/**
 * v1.8 (item 2, I19): every drawn instance of an array, its matrix and its look: the one source of
 * per-copy data for every draw, bounds and export caller (`ArrayDraw` and the rest, area B). It
 * wraps [ArrayLayout.matrices] (the v1.7 placement, frozen) and adds the [ArraySpec.look]. Pure
 * arithmetic; thread-safe.
 */
object ArrayInstances {
    /**
     * Every instance of [spec] for a source with bounds [source], in DRAW order (k = n-1 .. 0).
     * Matrices: v1.7's ArrayLayout.matrices(spec, source)[k] = M_k, then, with a look, M_k·T(c)·R(turn_k)·S(size_k)·T(-c)
     * with c = source.center. t_k = ease((k-1)/(n-2)) for n >= 3, t = 1 for n = 2. With look == null
     * every matrix is bit-identical to v1.7 and every look is null (I19).
     *
     * n counts the PLACED instances (a CURVE array drops copies past the guide's end). With a
     * look, every copy (k ≥ 1) has a [CopyLook]; a copy whose size is 1 and turn 0 keeps M_k
     * itself (bit for bit), and the source (k = 0) is always the identity with no look. The
     * returned matrices are the instances' own arrays.
     */
    fun of(spec: ArraySpec, source: RectF): List<ArrayInstance> {
        val ms = ArrayLayout.matrices(spec, source)
        val n = ms.size
        val look = spec.sanitized().look
        val out = ArrayList<ArrayInstance>(n)
        if (look == null) {
            for (k in n - 1 downTo 0) out += ArrayInstance(k, ms[k], null)
            return out
        }
        val cx = source.centerX().toDouble()
        val cy = source.centerY().toDouble()
        val tinted = look.tint.size >= 2 && look.tintStrength > 0f
        for (k in n - 1 downTo 0) {
            if (k == 0) {
                out += ArrayInstance(0, ms[0], null)
                continue
            }
            val t = if (n <= 2) 1f else ease(look.easing, (k - 1).toFloat() / (n - 2))
            val size = lerp(look.sizeStart, look.sizeEnd, t)
            val turn = lerp(look.turnStart, look.turnEnd, t)
            val m = if (size == 1f && turn == 0f) ms[k] else withSizeAndTurn(ms[k], cx, cy, size, turn)
            val copyLook = CopyLook(
                opacity = lerp(look.opacityStart, look.opacityEnd, t).coerceIn(0f, 1f),
                tintColor = if (tinted) ArrayTint.sample(look.tint, t) else 0,
                tintStrength = if (tinted) look.tintStrength else 0f,
                // v1.10 (R5): the mode only with a tint (a copy without one compares as v1.9's).
                colorMode = if (tinted) look.colorMode else ArrayColorMode.TINT,
            )
            out += ArrayInstance(k, m, copyLook)
        }
        return out
    }

    /** Union of the instances' mapped bounds (with each copy's size and turn); equals ArrayLayout.bounds when look == null. */
    fun bounds(spec: ArraySpec, source: RectF): RectF {
        if (spec.sanitized().look == null) return ArrayLayout.bounds(spec, source)
        val out = RectF(source)
        if (source.isEmpty) return out
        val xs = floatArrayOf(source.left, source.right, source.right, source.left)
        val ys = floatArrayOf(source.top, source.top, source.bottom, source.bottom)
        for (inst in of(spec, source)) {
            val m = inst.matrix
            for (i in 0 until 4) {
                val x = m[0] * xs[i] + m[1] * ys[i] + m[2]
                val y = m[3] * xs[i] + m[4] * ys[i] + m[5]
                if (!x.isFinite() || !y.isFinite()) continue
                out.left = min(out.left, x); out.right = max(out.right, x)
                out.top = min(out.top, y); out.bottom = max(out.bottom, y)
            }
        }
        return out
    }

    /** [t] (clamped to 0..1) through [e]. */
    fun ease(e: ArrayEasing, t: Float): Float {   // LINEAR t; EASE_IN t²; EASE_OUT 1-(1-t)²; EASE_IN_OUT 3t²-2t³
        val u = if (t.isNaN()) 0f else t.coerceIn(0f, 1f)
        return when (e) {
            ArrayEasing.LINEAR -> u
            ArrayEasing.EASE_IN -> u * u
            ArrayEasing.EASE_OUT -> 1f - (1f - u) * (1f - u)
            ArrayEasing.EASE_IN_OUT -> u * u * (3f - 2f * u)
        }
    }

    private fun lerp(a: Float, b: Float, t: Float): Float = if (t == 0f) a else if (t == 1f) b else a + (b - a) * t

    /**
     * M·T(c)·R(turn)·S(size)·T(−c), computed in double: the copy grows and turns about its own
     * centre c (the source's centre, carried by M). R turns +x towards +y (clockwise on screen,
     * as [ArrayLayout]'s angles).
     */
    private fun withSizeAndTurn(m: FloatArray, cx: Double, cy: Double, size: Float, turnDeg: Float): FloatArray {
        val r = Math.toRadians(turnDeg.toDouble())
        val s = size.toDouble()
        val c = cos(r) * s
        val sn = sin(r) * s
        // L = T(c)·R·S·T(−c) = [c, −sn, cx − c·cx + sn·cy; sn, c, cy − sn·cx − c·cy; 0, 0, 1].
        val l0 = c; val l1 = -sn; val l2 = cx - c * cx + sn * cy
        val l3 = sn; val l4 = c; val l5 = cy - sn * cx - c * cy
        val a0 = m[0].toDouble(); val a1 = m[1].toDouble(); val a2 = m[2].toDouble()
        val a3 = m[3].toDouble(); val a4 = m[4].toDouble(); val a5 = m[5].toDouble()
        return floatArrayOf(
            (a0 * l0 + a1 * l3).toFloat(), (a0 * l1 + a1 * l4).toFloat(), (a0 * l2 + a1 * l5 + a2).toFloat(),
            (a3 * l0 + a4 * l3).toFloat(), (a3 * l1 + a4 * l4).toFloat(), (a3 * l2 + a4 * l5 + a5).toFloat(),
            0f, 0f, 1f,
        )
    }
}

/**
 * v1.8 (item 2, D9): the Array look's tint. A copy is tinted toward a color T by a strength s,
 * keeping its shading and transparency: each channel becomes (1-s)·c + s·T_c·Y, where Y is the
 * Rec.709 luma of the copy's own color (0..255) and T_c the tint's channel as a fraction (0..1).
 * At full strength a gray v becomes T·v (the tint's shade of that brightness); a white tint keeps
 * every gray as it is; strength 0 keeps every color; alpha never changes. The tint's own alpha is
 * ignored. Pixels apply it as a [matrix] (`ColorMatrixColorFilter`), vector copies per paint
 * color ([apply]). Pure arithmetic; thread-safe.
 */
object ArrayTint {
    private const val LR = 0.2126f
    private const val LG = 0.7152f
    private const val LB = 0.0722f

    /** rgb' = (1-s)·rgb + s·T·Y(rgb), Y = Rec.709 luma of rgb; alpha unchanged. As a 4x5 matrix. */
    fun matrix(tint: Int, strength: Float): FloatArray {
        val s = strength(strength)
        val k = 1f - s
        val tr = s * ((tint shr 16) and 0xFF) / 255f
        val tg = s * ((tint shr 8) and 0xFF) / 255f
        val tb = s * (tint and 0xFF) / 255f
        return floatArrayOf(
            k + tr * LR, tr * LG, tr * LB, 0f, 0f,
            tg * LR, k + tg * LG, tg * LB, 0f, 0f,
            tb * LR, tb * LG, k + tb * LB, 0f, 0f,
            0f, 0f, 0f, 1f, 0f,
        )
    }

    /** The same on one ARGB color (vector paints). */
    fun apply(argb: Int, tint: Int, strength: Float): Int {
        val s = strength(strength)
        if (s == 0f) return argb
        val r = (argb shr 16) and 0xFF
        val g = (argb shr 8) and 0xFF
        val b = argb and 0xFF
        val y = LR * r + LG * g + LB * b
        val k = 1f - s
        fun ch(c: Int, t: Int): Int = (k * c + s * (t / 255f) * y).roundToInt().coerceIn(0, 255)
        return (argb and 0xFF000000.toInt()) or
            (ch(r, (tint shr 16) and 0xFF) shl 16) or (ch(g, (tint shr 8) and 0xFF) shl 8) or ch(b, tint and 0xFF)
    }

    /**
     * The gradient [stops] sampled at [t] (linear in sRGB, as GradientEditor previews it): flat
     * beyond the end stops, linear between the two around [t] (`GradientEditing.sample`'s rule).
     * Opaque black without stops.
     */
    fun sample(stops: List<VStop>, t: Float): Int {
        if (stops.isEmpty()) return 0xFF000000.toInt()
        val s = stops.sortedBy { it.offset }
        if (t <= s.first().offset) return s.first().color
        if (t >= s.last().offset) return s.last().color
        for (i in 0 until s.lastIndex) {
            val a = s[i]; val b = s[i + 1]
            if (t <= b.offset) {
                val span = b.offset - a.offset
                return if (span <= 1e-6f) b.color else ColorUtils.lerp(a.color, b.color, (t - a.offset) / span)
            }
        }
        return s.last().color
    }

    private fun strength(s: Float): Float = if (s.isFinite()) s.coerceIn(0f, 1f) else 0f
}
