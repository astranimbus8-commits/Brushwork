package com.brushwork.paint.assist

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.vector.StrokeCopies
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * v1.7 (item 18, §3.18): where the symmetry rulers put the copies of a stroke. Each copy is a
 * row-major 3×3 map of the document plane (affine, or a homography for the perspective array),
 * the identity first: the brush stamps every dab through each map ([com.brushwork.paint.brush.DabMapping])
 * and a vector stroke keeps them as `VStroke.copies`.
 *
 * - Mirror: 2 maps (the reflection about the axis through the centre at `angleDeg`).
 * - Kaleidoscope n: 2n maps (n rotations about the centre, and n reflections whose axes are
 *   180°/n apart, the first at `angleDeg`).
 * - Rotation n: n rotations about the centre.
 * - Array: translations of the grid (cells `spacingX` × `spacingY`, turned by `angleDeg` − 90°,
 *   one corner at the centre) that take the cell the stroke starts in to every cell meeting the
 *   canvas (and the ring of cells just outside it, so a stroke reaching into a neighbouring cell
 *   repeats up to the canvas edges), nearest to the stroke start first; a translation by a
 *   canvas width or height or more is culled (nothing on the canvas lands on it).
 * - Perspective array: H·T(i, j)·H⁻¹, where H maps the unit square onto the quad (one cell of
 *   the grid in perspective), for the cells (i, j) in front of the horizon that meet the canvas
 *   (and the ring outside it) and are not too small to draw into, nearest (in cells) first.
 *
 * At most [MAX] maps (the identity included) are returned; with nothing but the identity (or
 * symmetry off) the list is EMPTY, which is "no symmetry" everywhere. Every map is usable by
 * `StrokeCopies.isUsable`. Positions are document px. Pure arithmetic; thread-safe.
 */
object SymmetryMaps {
    /** Most maps a stroke gets, the identity included (`StrokeCopies.MAX`). */
    const val MAX = StrokeCopies.MAX

    /** A perspective cell whose image is smaller than this (document px²) is too small to draw into. */
    const val MIN_CELL_AREA = 16.0

    /** Array grids with more candidate cells than this only look at the cells around the stroke start. */
    private const val MAX_ARRAY_SCAN = 40_000L

    /** Cells on each side of the start cell looked at by a very fine array grid (see [MAX_ARRAY_SCAN]). */
    private const val ARRAY_WINDOW = 24

    /** Cells a perspective grid looks at, at most, and how far (in cells) from the start cell. */
    private const val MAX_PERSPECTIVE_VISITS = 20_000
    private const val MAX_PERSPECTIVE_INDEX = 512

    /** A perspective corner this close to the horizon (homogeneous w) is behind it. */
    private const val MIN_W = 1e-6

    /** Side of the default perspective cell, as a fraction of the canvas's shorter side. */
    private const val DEFAULT_QUAD_SIDE = 0.2f

    /** Top edge of the default perspective cell, as a fraction of its bottom edge (a gentle perspective). */
    private const val DEFAULT_QUAD_TOP = 0.8f

    /** The identity map (a new array each time: maps are never shared mutable state). */
    fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** True when [m] is exactly the identity. */
    fun isIdentity(m: FloatArray): Boolean =
        m.size == 9 && m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f &&
            m[6] == 0f && m[7] == 0f && m[8] == 1f

    /** The centre of [s] on a [docW] × [docH] canvas: an unplaced coordinate (-1) is the canvas centre's. */
    fun center(s: SymmetrySettings, docW: Int, docH: Int): Vec2 = Vec2(
        if (s.centerX == UNPLACED) docW / 2f else s.centerX,
        if (s.centerY == UNPLACED) docH / 2f else s.centerY,
    )

    /** The perspective cell of [s] (TL, TR, BR, BL, 8 floats): its own quad, else [defaultQuad]. */
    fun quad(s: SymmetrySettings, docW: Int, docH: Int): List<Float> =
        if (s.quad.size == 8 && SymmetrySettings.isConvexQuad(s.quad)) s.quad else defaultQuad(docW, docH)

    /** The first perspective cell: a gentle trapezoid (narrower at the top) in the middle of the canvas. */
    fun defaultQuad(docW: Int, docH: Int): List<Float> {
        val side = max(8f, min(docW, docH) * DEFAULT_QUAD_SIDE)
        val cx = docW / 2f
        val cy = docH / 2f
        val top = side * DEFAULT_QUAD_TOP / 2f
        val bottom = side / 2f
        return listOf(cx - top, cy - side / 2f, cx + top, cy - side / 2f, cx + bottom, cy + side / 2f, cx - bottom, cy + side / 2f)
    }

    /**
     * The maps of [settings] for a stroke starting at ([startX], [startY]) on a [docW] × [docH]
     * canvas: the identity first, at most [MAX]; empty when symmetry is off or nothing but the
     * identity remains. Only the array and the perspective array depend on the start.
     */
    fun transforms(settings: SymmetrySettings, docW: Int, docH: Int, startX: Float = docW / 2f, startY: Float = docH / 2f): List<FloatArray> {
        if (docW <= 0 || docH <= 0) return emptyList()
        val s = settings.sanitized()
        val c = center(s, docW, docH)
        val maps = when (s.type) {
            SymmetryType.OFF -> return emptyList()
            SymmetryType.MIRROR -> listOf(identity(), reflection(c.x, c.y, s.angleDeg.toDouble()))
            SymmetryType.KALEIDOSCOPE -> {
                val n = s.divisions
                val out = ArrayList<FloatArray>(2 * n)
                for (k in 0 until n) {
                    out += if (k == 0) identity() else rotation(c.x, c.y, 360.0 * k / n)
                    out += reflection(c.x, c.y, s.angleDeg + 180.0 * k / n)
                }
                out
            }
            SymmetryType.ROTATION -> List(s.divisions) { k -> if (k == 0) identity() else rotation(c.x, c.y, 360.0 * k / s.divisions) }
            SymmetryType.ARRAY -> array(s, c, docW, docH, startX, startY)
            SymmetryType.PERSPECTIVE_ARRAY -> perspective(s, docW, docH, startX, startY)
        }
        val usable = maps.filter { StrokeCopies.isUsable(it) }.take(MAX)
        return if (usable.size < 2 || !isIdentity(usable[0])) emptyList() else usable
    }

    /** The number of maps [settings] gives a stroke starting at ([startX], [startY]) (see [transforms]). */
    fun count(settings: SymmetrySettings, docW: Int, docH: Int, startX: Float = docW / 2f, startY: Float = docH / 2f): Int =
        transforms(settings, docW, docH, startX, startY).size

    // ------------------------------------------------------------------ affine maps

    /** The reflection about the line through ([cx], [cy]) at [angleDeg] (90° = a vertical axis: x ↦ 2cx − x). */
    fun reflection(cx: Float, cy: Float, angleDeg: Double): FloatArray {
        val a = Math.toRadians(2.0 * angleDeg)
        return about(cx, cy, clean(cos(a)), clean(sin(a)), clean(sin(a)), clean(-cos(a)))
    }

    /** The rotation by [deg] (clockwise on screen, as y points down) about ([cx], [cy]). */
    fun rotation(cx: Float, cy: Float, deg: Double): FloatArray {
        val a = Math.toRadians(deg)
        val co = clean(cos(a))
        val si = clean(sin(a))
        return about(cx, cy, co, -si, si, co)
    }

    /** The linear map (r00 r01; r10 r11) about the point ([cx], [cy]). */
    private fun about(cx: Float, cy: Float, r00: Double, r01: Double, r10: Double, r11: Double): FloatArray {
        val x = cx.toDouble()
        val y = cy.toDouble()
        val tx = x - (r00 * x + r01 * y)
        val ty = y - (r10 * x + r11 * y)
        return floatArrayOf(r00.toFloat(), r01.toFloat(), tx.toFloat(), r10.toFloat(), r11.toFloat(), ty.toFloat(), 0f, 0f, 1f)
    }

    /** Rounding noise of cos / sin at multiples of 90° (6e-17 for 0) made exact. */
    private fun clean(v: Double): Double = when {
        abs(v) < 1e-12 -> 0.0
        abs(v - 1.0) < 1e-12 -> 1.0
        abs(v + 1.0) < 1e-12 -> -1.0
        else -> v
    }

    // ------------------------------------------------------------------ array

    /**
     * The array grid's cell edges (document px): u = (ux, uy) along the first axis (`spacingX`
     * long), v = (vx, vy) along the second (`spacingY` long); at `angleDeg` = 90 they are the
     * canvas axes.
     */
    fun arrayBasis(s: SymmetrySettings): DoubleArray {
        val a = Math.toRadians(s.angleDeg - 90.0)
        val co = clean(cos(a))
        val si = clean(sin(a))
        return doubleArrayOf(co * s.spacingX, si * s.spacingX, -si * s.spacingY, co * s.spacingY)
    }

    private class Candidate(val tier: Int, val d2: Double, val i: Int, val j: Int, val map: FloatArray)

    private val ORDER = compareBy<Candidate>({ it.tier }, { it.d2 }, { it.j }, { it.i })

    private fun array(s: SymmetrySettings, c: Vec2, w: Int, h: Int, startX: Float, startY: Float): List<FloatArray> {
        val b = arrayBasis(s)
        val ux = b[0]; val uy = b[1]; val vx = b[2]; val vy = b[3]
        val det = ux * vy - uy * vx
        if (!det.isFinite() || abs(det) < 1e-9) return emptyList()
        val ox = c.x.toDouble()
        val oy = c.y.toDouble()
        fun alpha(x: Double, y: Double) = ((x - ox) * vy - (y - oy) * vx) / det
        fun beta(x: Double, y: Double) = (ux * (y - oy) - uy * (x - ox)) / det
        val a0d = floor(alpha(startX.toDouble(), startY.toDouble()))
        val b0d = floor(beta(startX.toDouble(), startY.toDouble()))
        if (!a0d.isFinite() || !b0d.isFinite() || abs(a0d) > 1e7 || abs(b0d) > 1e7) return emptyList()
        val a0 = a0d.toInt()
        val b0 = b0d.toInt()
        // The cell's bounding box size: the margin ring around the canvas is one cell wide.
        val ex = abs(ux) + abs(vx)
        val ey = abs(uy) + abs(vy)
        val l1 = -ex; val t1 = -ey; val r1 = w + ex; val bt1 = h + ey
        var iLo = Int.MAX_VALUE; var iHi = Int.MIN_VALUE; var jLo = Int.MAX_VALUE; var jHi = Int.MIN_VALUE
        for ((x, y) in listOf(l1 to t1, r1 to t1, r1 to bt1, l1 to bt1)) {
            val al = alpha(x, y); val be = beta(x, y)
            iLo = min(iLo, floor(al).toInt() - 1); iHi = max(iHi, ceil(al).toInt() + 1)
            jLo = min(jLo, floor(be).toInt() - 1); jHi = max(jHi, ceil(be).toInt() + 1)
        }
        if ((iHi - iLo + 1).toLong() * (jHi - jLo + 1).toLong() > MAX_ARRAY_SCAN) {
            // A very fine grid: only the cells around the start matter (the cap keeps the nearest).
            iLo = max(iLo, a0 - ARRAY_WINDOW); iHi = min(iHi, a0 + ARRAY_WINDOW)
            jLo = max(jLo, b0 - ARRAY_WINDOW); jHi = min(jHi, b0 + ARRAY_WINDOW)
        }
        val minUx = min(0.0, ux) + min(0.0, vx); val maxUx = max(0.0, ux) + max(0.0, vx)
        val minUy = min(0.0, uy) + min(0.0, vy); val maxUy = max(0.0, uy) + max(0.0, vy)
        val out = ArrayList<Candidate>()
        for (j in jLo..jHi) for (i in iLo..iHi) {
            if (i == a0 && j == b0) continue
            val cx = ox + i * ux + j * vx
            val cy = oy + i * uy + j * vy
            val left = cx + minUx; val right = cx + maxUx
            val top = cy + minUy; val bottom = cy + maxUy
            val tier = when {
                right > 0 && left < w && bottom > 0 && top < h -> 0
                right > l1 && left < r1 && bottom > t1 && top < bt1 -> 1
                else -> continue
            }
            val di = (i - a0).toDouble()
            val dj = (j - b0).toDouble()
            val tx = di * ux + dj * vx
            val ty = di * uy + dj * vy
            // A copy moved a canvas width (or height) away can't bring anything onto the canvas.
            if (abs(tx) >= w || abs(ty) >= h) continue
            out += Candidate(tier, tx * tx + ty * ty, i - a0, j - b0, floatArrayOf(1f, 0f, tx.toFloat(), 0f, 1f, ty.toFloat(), 0f, 0f, 1f))
        }
        return listOf(identity()) + out.sortedWith(ORDER).take(MAX - 1).map { it.map }
    }

    // ------------------------------------------------------------------ perspective array

    /**
     * The homography (row-major 3×3, doubles) mapping the unit square (0,0), (1,0), (1,1), (0,1)
     * onto [q]'s TL, TR, BR, BL; null for a degenerate quad.
     */
    fun squareToQuad(q: List<Float>): DoubleArray? {
        if (q.size != 8) return null
        val x0 = q[0].toDouble(); val y0 = q[1].toDouble()
        val x1 = q[2].toDouble(); val y1 = q[3].toDouble()
        val x2 = q[4].toDouble(); val y2 = q[5].toDouble()
        val x3 = q[6].toDouble(); val y3 = q[7].toDouble()
        val sx = x0 - x1 + x2 - x3
        val sy = y0 - y1 + y2 - y3
        val dx1 = x1 - x2; val dx2 = x3 - x2
        val dy1 = y1 - y2; val dy2 = y3 - y2
        val den = dx1 * dy2 - dx2 * dy1
        if (!den.isFinite() || abs(den) < 1e-12) return null
        val g = (sx * dy2 - dx2 * sy) / den
        val h = (dx1 * sy - sx * dy1) / den
        val m = doubleArrayOf(
            x1 - x0 + g * x1, x3 - x0 + h * x3, x0,
            y1 - y0 + g * y1, y3 - y0 + h * y3, y0,
            g, h, 1.0,
        )
        return if (m.all { it.isFinite() }) m else null
    }

    /**
     * The cells (i, j) of [s]'s perspective grid for a stroke starting at ([startX], [startY]),
     * the start cell first then nearest (in cells) first, at most [limit]; each as (i, j)
     * relative to the unit square (cell (0, 0) is the quad itself). Empty when the start lies
     * beyond the horizon or the quad is degenerate. The guides draw these cells.
     */
    fun perspectiveCells(s: SymmetrySettings, docW: Int, docH: Int, startX: Float, startY: Float, limit: Int = MAX): List<IntArray> {
        val hm = squareToQuad(quad(s, docW, docH)) ?: return emptyList()
        val inv = inverse(hm) ?: return emptyList()
        val st = apply(inv, startX.toDouble(), startY.toDouble()) ?: return emptyList()
        // The start must be on the quad's side of the horizon.
        val wStart = hm[6] * st[0] + hm[7] * st[1] + hm[8]
        if (!(wStart > MIN_W)) return emptyList()
        if (abs(st[0]) > 1e6 || abs(st[1]) > 1e6) return emptyList()
        val a0 = floor(st[0]).toInt()
        val b0 = floor(st[1]).toInt()
        val found = ArrayList<Pair<Candidate, IntArray>>()
        val seen = HashSet<Long>()
        val queue = ArrayDeque<IntArray>()
        queue.addLast(intArrayOf(a0, b0))
        seen += key(a0, b0)
        val corners = DoubleArray(8)
        while (queue.isNotEmpty() && seen.size <= MAX_PERSPECTIVE_VISITS) {
            val cell = queue.removeFirst()
            val i = cell[0]; val j = cell[1]
            val start = i == a0 && j == b0
            val tier = cellTier(hm, i, j, docW, docH, corners)
            if (tier < 0 && !start) continue
            val di = i - a0; val dj = j - b0
            if (!start) found += Candidate(tier, (di.toDouble() * di + dj.toDouble() * dj), di, dj, EMPTY) to cell
            for (n in NEIGHBOURS) {
                val ni = i + n[0]; val nj = j + n[1]
                if (abs(ni - a0) > MAX_PERSPECTIVE_INDEX || abs(nj - b0) > MAX_PERSPECTIVE_INDEX) continue
                if (seen.add(key(ni, nj))) queue.addLast(intArrayOf(ni, nj))
            }
        }
        val sorted = found.sortedWith { x, y -> ORDER.compare(x.first, y.first) }.take(max(0, limit - 1)).map { it.second }
        return listOf(intArrayOf(a0, b0)) + sorted
    }

    /** The maps of the perspective array (see [perspectiveCells]): H·T(i − a, j − b)·H⁻¹ for each cell, (a, b) the start cell. */
    private fun perspective(s: SymmetrySettings, w: Int, h: Int, startX: Float, startY: Float): List<FloatArray> {
        val hm = squareToQuad(quad(s, w, h)) ?: return emptyList()
        val inv = inverse(hm) ?: return emptyList()
        val cells = perspectiveCells(s, w, h, startX, startY)
        if (cells.size < 2) return emptyList()
        val a0 = cells[0][0]; val b0 = cells[0][1]
        val out = ArrayList<FloatArray>(cells.size)
        out += identity()
        for (k in 1 until cells.size) {
            val t = doubleArrayOf(1.0, 0.0, (cells[k][0] - a0).toDouble(), 0.0, 1.0, (cells[k][1] - b0).toDouble(), 0.0, 0.0, 1.0)
            val m = multiply(multiply(hm, t), inv)
            val z = m[8]
            if (!z.isFinite() || abs(z) < 1e-12) continue
            val f = FloatArray(9) { (m[it] / z).toFloat() }
            f[8] = 1f
            if (StrokeCopies.isUsable(f)) out += f
        }
        return out
    }

    /**
     * 0 when cell (i, j)'s image meets the canvas, 1 when it meets only the ring one cell wide
     * around it, -1 when it is behind the horizon, too small or elsewhere. [corners] receives
     * the image's corners (TL, TR, BR, BL).
     */
    internal fun cellTier(hm: DoubleArray, i: Int, j: Int, w: Int, h: Int, corners: DoubleArray): Int {
        var k = 0
        for ((u, v) in CORNERS) {
            val x = (i + u).toDouble(); val y = (j + v).toDouble()
            val z = hm[6] * x + hm[7] * y + hm[8]
            if (!(z > MIN_W)) return -1
            corners[2 * k] = (hm[0] * x + hm[1] * y + hm[2]) / z
            corners[2 * k + 1] = (hm[3] * x + hm[4] * y + hm[5]) / z
            k++
        }
        var area = 0.0
        var l = Double.POSITIVE_INFINITY; var t = Double.POSITIVE_INFINITY
        var r = Double.NEGATIVE_INFINITY; var b = Double.NEGATIVE_INFINITY
        for (n in 0 until 4) {
            val x = corners[2 * n]; val y = corners[2 * n + 1]
            val nx = corners[2 * ((n + 1) % 4)]; val ny = corners[2 * ((n + 1) % 4) + 1]
            area += x * ny - nx * y
            l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
        }
        if (!(abs(area) / 2.0 >= MIN_CELL_AREA) || !(r - l).isFinite() || !(b - t).isFinite()) return -1
        if (r > 0 && l < w && b > 0 && t < h) return 0
        val ex = r - l; val ey = b - t
        return if (r > -ex && l < w + ex && b > -ey && t < h + ey) 1 else -1
    }

    private val CORNERS = listOf(0 to 0, 1 to 0, 1 to 1, 0 to 1)
    private val NEIGHBOURS = arrayOf(intArrayOf(1, 0), intArrayOf(-1, 0), intArrayOf(0, 1), intArrayOf(0, -1))
    private val EMPTY = FloatArray(0)

    private fun key(i: Int, j: Int): Long = (i.toLong() shl 32) or (j.toLong() and 0xFFFFFFFFL)

    /** [m] applied to (x, y): the mapped point (2 doubles), null at the horizon. */
    fun apply(m: DoubleArray, x: Double, y: Double): DoubleArray? {
        val z = m[6] * x + m[7] * y + m[8]
        if (!z.isFinite() || abs(z) < 1e-12) return null
        val px = (m[0] * x + m[1] * y + m[2]) / z
        val py = (m[3] * x + m[4] * y + m[5]) / z
        return if (px.isFinite() && py.isFinite()) doubleArrayOf(px, py) else null
    }

    internal fun multiply(a: DoubleArray, b: DoubleArray): DoubleArray = DoubleArray(9) { n ->
        val r = n / 3; val c = n % 3
        a[3 * r] * b[c] + a[3 * r + 1] * b[3 + c] + a[3 * r + 2] * b[6 + c]
    }

    internal fun inverse(a: DoubleArray): DoubleArray? {
        val d = a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
        if (!d.isFinite() || abs(d) < 1e-18) return null
        val r = doubleArrayOf(
            a[4] * a[8] - a[5] * a[7], a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            a[5] * a[6] - a[3] * a[8], a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            a[3] * a[7] - a[4] * a[6], a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return DoubleArray(9) { r[it] / d }
    }

    /** The "not placed" coordinate of [SymmetrySettings.centerX] / [SymmetrySettings.centerY]. */
    const val UNPLACED = -1f
}
