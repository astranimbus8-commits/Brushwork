package com.brushwork.paint.filters.pixelate

import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin

/**
 * A partition of the image plane into cells (squares, hexagons, triangles, Voronoi regions).
 *
 * Cells are grouped into rows and indexed densely: row `r` owns the indices
 * `rowStart(r) until rowStart(r + 1)`, so per-cell data fits in plain arrays sized [cellCount]
 * (only cells that can touch the image are indexed, even when the lattice is rotated).
 */
internal abstract class CellLattice {
    abstract val rowCount: Int
    abstract val cellCount: Int

    /** Every image point assigned to a cell lies within this distance of the cell's center. */
    abstract val boundRadius: Float

    /** First cell index of [row]; `rowStart(rowCount) == cellCount`. */
    abstract fun rowStart(row: Int): Int

    /**
     * Index of the cell containing the image point ([px], [py]). When [center] is non-null the
     * cell's center (image coordinates) is written to `center[0], center[1]`.
     */
    abstract fun cellAt(px: Float, py: Float, center: FloatArray?): Int

    /** Center (image coordinates) of [cell], which belongs to [row]; written to `out[0], out[1]`. */
    abstract fun centerOf(row: Int, cell: Int, out: FloatArray)
}

/**
 * The image rectangle `[0,w]x[0,h]` seen in a lattice frame rotated by [angleDeg] about the image
 * center: `(u, v) = rotate(p - center, -angle)`.
 */
internal class RotatedFrame(width: Int, height: Int, angleDeg: Float) {
    val cx = width / 2f
    val cy = height / 2f
    val cos: Float
    val sin: Float
    private val cornerU = FloatArray(4)
    private val cornerV = FloatArray(4)
    val vMin: Float
    val vMax: Float

    init {
        val a = ((angleDeg % 360f) + 360f) % 360f
        // Exact values for right angles so axis-aligned grids have no rounding noise.
        when (a) {
            0f -> { cos = 1f; sin = 0f }
            90f -> { cos = 0f; sin = 1f }
            180f -> { cos = -1f; sin = 0f }
            270f -> { cos = 0f; sin = -1f }
            else -> { val r = a * PI / 180.0; cos = cos(r).toFloat(); sin = sin(r).toFloat() }
        }
        val xs = floatArrayOf(0f, width.toFloat(), width.toFloat(), 0f)
        val ys = floatArrayOf(0f, 0f, height.toFloat(), height.toFloat())
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        for (k in 0 until 4) {
            cornerU[k] = u(xs[k], ys[k]); cornerV[k] = v(xs[k], ys[k])
            lo = min(lo, cornerV[k]); hi = max(hi, cornerV[k])
        }
        vMin = lo; vMax = hi
    }

    fun u(px: Float, py: Float): Float = cos * (px - cx) + sin * (py - cy)
    fun v(px: Float, py: Float): Float = -sin * (px - cx) + cos * (py - cy)
    fun toX(u: Float, v: Float): Float = cx + cos * u - sin * v
    fun toY(u: Float, v: Float): Float = cy + sin * u + cos * v

    /**
     * Horizontal extent (in u) of the image rectangle inside the strip `vLo <= v <= vHi`, written
     * to `out[0], out[1]`. Returns false when the strip misses the image.
     */
    fun stripURange(vLo: Float, vHi: Float, out: FloatArray): Boolean {
        var lo = Float.POSITIVE_INFINITY; var hi = Float.NEGATIVE_INFINITY
        for (k in 0 until 4) {
            val u0 = cornerU[k]; val v0 = cornerV[k]
            val u1 = cornerU[(k + 1) and 3]; val v1 = cornerV[(k + 1) and 3]
            if (v0 >= vLo && v0 <= vHi) { lo = min(lo, u0); hi = max(hi, u0) }
            if ((v0 - vLo) * (v1 - vLo) < 0f) {
                val u = u0 + (vLo - v0) / (v1 - v0) * (u1 - u0); lo = min(lo, u); hi = max(hi, u)
            }
            if ((v0 - vHi) * (v1 - vHi) < 0f) {
                val u = u0 + (vHi - v0) / (v1 - v0) * (u1 - u0); lo = min(lo, u); hi = max(hi, u)
            }
        }
        if (lo > hi) return false
        out[0] = lo; out[1] = hi
        return true
    }
}

/**
 * Base for lattices whose rows are strips of the rotated frame. Subclasses give each lattice row
 * `firstRow + r` a column range; cells per column is [cellsPerCol] (2 for triangles).
 */
internal abstract class RotatedRowLattice(protected val frame: RotatedFrame, private val cellsPerCol: Int) : CellLattice() {
    protected var firstRow = 0
    private lateinit var colStart: IntArray
    private lateinit var colCount: IntArray
    private lateinit var starts: IntArray

    final override val rowCount: Int get() = colStart.size
    final override val cellCount: Int get() = starts[rowCount]
    final override fun rowStart(row: Int): Int = starts[row]

    /** Builds the row tables; [colRange] writes the inclusive column range of a lattice row. */
    protected fun build(rowFrom: Int, rowTo: Int, colRange: (row: Int, out: IntArray) -> Unit) {
        firstRow = rowFrom
        val n = max(1, rowTo - rowFrom + 1)
        colStart = IntArray(n); colCount = IntArray(n); starts = IntArray(n + 1)
        val tmp = IntArray(2)
        var total = 0L
        for (r in 0 until n) {
            colRange(rowFrom + r, tmp)
            val cnt = max(1, tmp[1] - tmp[0] + 1)
            colStart[r] = tmp[0]; colCount[r] = cnt
            starts[r] = total.toInt()
            total += cnt.toLong() * cellsPerCol
            require(total < Int.MAX_VALUE) { "Too many cells" }
        }
        starts[n] = total.toInt()
    }

    /** Clamped row slot of lattice row [row]. */
    protected fun slot(row: Int): Int = min(rowCount - 1, max(0, row - firstRow))

    /** Clamped column of lattice column [col] in row slot [slot]. */
    protected fun clampCol(slot: Int, col: Int): Int = min(colStart[slot] + colCount[slot] - 1, max(colStart[slot], col))

    protected fun index(slot: Int, col: Int, sub: Int = 0): Int = starts[slot] + (col - colStart[slot]) * cellsPerCol + sub

    /** Lattice column of [cell], which lies in row slot [slot]. */
    protected fun colOf(slot: Int, cell: Int): Int = colStart[slot] + (cell - starts[slot]) / cellsPerCol

    /** Sub-cell (0 or 1 for triangles) of [cell], which lies in row slot [slot]. */
    protected fun subOf(slot: Int, cell: Int): Int = (cell - starts[slot]) % cellsPerCol

    companion object {
        fun floorInt(v: Float): Int = floor(v).toInt()
        fun ceilInt(v: Float): Int = ceil(v).toInt()
    }
}

/** Square cells of side [side] centered on the image center, rotated by the frame angle. */
internal class SquareLattice(width: Int, height: Int, side: Float, angleDeg: Float) :
    RotatedRowLattice(RotatedFrame(width, height, angleDeg), 1) {
    private val s = max(1f, side)
    private val inv = 1f / s
    override val boundRadius: Float = s * 0.70710677f

    init {
        val span = FloatArray(2)
        build(floorInt(frame.vMin * inv + 0.5f) - 1, floorInt(frame.vMax * inv + 0.5f) + 1) { j, out ->
            if (frame.stripURange((j - 0.5f) * s, (j + 0.5f) * s, span)) {
                out[0] = floorInt(span[0] * inv + 0.5f) - 1; out[1] = floorInt(span[1] * inv + 0.5f) + 1
            } else { out[0] = 0; out[1] = 0 }
        }
    }

    override fun cellAt(px: Float, py: Float, center: FloatArray?): Int {
        val u = frame.u(px, py); val v = frame.v(px, py)
        val sl = slot(floorInt(v * inv + 0.5f))
        val i = clampCol(sl, floorInt(u * inv + 0.5f))
        if (center != null) {
            val cu = i * s; val cv = (sl + firstRow) * s
            center[0] = frame.toX(cu, cv); center[1] = frame.toY(cu, cv)
        }
        return index(sl, i)
    }

    override fun centerOf(row: Int, cell: Int, out: FloatArray) {
        val cu = colOf(row, cell) * s; val cv = (row + firstRow) * s
        out[0] = frame.toX(cu, cv); out[1] = frame.toY(cu, cv)
    }
}

/**
 * Pointy-top hexagons with circumradius [radius] (centers `sqrt(3) * radius` apart), one hexagon
 * centered on the image center, rotated by the frame angle. Rows are axial `r`, columns axial `q`.
 */
internal class HexLattice(width: Int, height: Int, radius: Float, angleDeg: Float) :
    RotatedRowLattice(RotatedFrame(width, height, angleDeg), 1) {
    private val r = max(0.63f, radius)
    private val colPitch = SQRT3 * r
    private val rowPitch = 1.5f * r
    override val boundRadius: Float = r

    init {
        val span = FloatArray(2)
        val half = colPitch / 2f
        build(floorInt((frame.vMin - r) / rowPitch) - 1, ceilInt((frame.vMax + r) / rowPitch) + 1) { row, out ->
            val cv = row * rowPitch
            if (frame.stripURange(cv - r, cv + r, span)) {
                out[0] = floorInt((span[0] - half) / colPitch - row * 0.5f) - 1
                out[1] = ceilInt((span[1] + half) / colPitch - row * 0.5f) + 1
            } else { out[0] = 0; out[1] = 0 }
        }
    }

    override fun cellAt(px: Float, py: Float, center: FloatArray?): Int {
        val u = frame.u(px, py); val v = frame.v(px, py)
        val q = (SQRT3 / 3f * u - v / 3f) / r
        val rr = (2f / 3f * v) / r
        // Cube rounding (x = q, z = r, y = -x - z).
        val y = -q - rr
        var rx = floor(q + 0.5f); var ry = floor(y + 0.5f); var rz = floor(rr + 0.5f)
        val dx = kotlin.math.abs(rx - q); val dy = kotlin.math.abs(ry - y); val dz = kotlin.math.abs(rz - rr)
        if (dx > dy && dx > dz) rx = -ry - rz else if (dy > dz) ry = -rx - rz else rz = -rx - ry
        val sl = slot(rz.toInt())
        val qi = clampCol(sl, rx.toInt())
        if (center != null) centerUv(sl, qi, center)
        return index(sl, qi)
    }

    private fun centerUv(slot: Int, qi: Int, out: FloatArray) {
        val ri = slot + firstRow
        val cu = colPitch * (qi + ri * 0.5f); val cv = rowPitch * ri
        out[0] = frame.toX(cu, cv); out[1] = frame.toY(cu, cv)
    }

    override fun centerOf(row: Int, cell: Int, out: FloatArray) = centerUv(row, colOf(row, cell), out)
}

/**
 * Equilateral triangles (alternating up/down) with circumradius [radius], rotated by the frame
 * angle. Lattice basis: `e1 = (side, 0)`, `e2 = (side / 2, height)`; each rhombus holds a lower
 * and an upper triangle.
 */
internal class TriangleLattice(width: Int, height: Int, radius: Float, angleDeg: Float) :
    RotatedRowLattice(RotatedFrame(width, height, angleDeg), 2) {
    private val rad = max(0.9f, radius)
    private val side = rad * SQRT3
    private val rowH = 1.5f * rad
    override val boundRadius: Float = rad

    init {
        val span = FloatArray(2)
        build(floorInt(frame.vMin / rowH) - 1, floorInt(frame.vMax / rowH) + 1) { j, out ->
            if (frame.stripURange(j * rowH, (j + 1) * rowH, span)) {
                out[0] = floorInt(span[0] / side - (j + 1) * 0.5f) - 1
                out[1] = floorInt(span[1] / side - j * 0.5f) + 1
            } else { out[0] = 0; out[1] = 0 }
        }
    }

    override fun cellAt(px: Float, py: Float, center: FloatArray?): Int {
        val u = frame.u(px, py); val v = frame.v(px, py)
        val j = v / rowH
        val i = u / side - j * 0.5f
        val fj = floor(j); val fi = floor(i)
        val upper = if ((i - fi) + (j - fj) > 1f) 1 else 0
        val sl = slot(fj.toInt())
        val col = clampCol(sl, fi.toInt())
        if (center != null) centroid(sl, col, upper, center)
        return index(sl, col, upper)
    }

    private fun centroid(slot: Int, col: Int, upper: Int, out: FloatArray) {
        val off = if (upper == 1) 2f / 3f else 1f / 3f
        val ci = col + off; val cj = slot + firstRow + off
        val cu = side * (ci + cj * 0.5f); val cv = rowH * cj
        out[0] = frame.toX(cu, cv); out[1] = frame.toY(cu, cv)
    }

    override fun centerOf(row: Int, cell: Int, out: FloatArray) = centroid(row, colOf(row, cell), subOf(row, cell), out)
}

/**
 * Voronoi cells around one jittered seed per `size x size` grid square (a "crystal" pattern).
 * [irregularity] 0..1 scales the jitter (0 = seeds at square centers). The nearest seed is found
 * among the 3x3 neighbouring grid squares, so every point is within `sqrt(2) * size` of its seed.
 */
internal class VoronoiLattice(width: Int, height: Int, size: Float, irregularity: Float, seed: Int) : CellLattice() {
    private val s = max(1f, size)
    private val inv = 1f / s
    private val gw = max(1, ceil(width / s).toInt())
    private val gh = max(1, ceil(height / s).toInt())
    private val sx = FloatArray(gw * gh)
    private val sy = FloatArray(gw * gh)

    override val rowCount: Int = gh
    override val cellCount: Int = gw * gh
    override val boundRadius: Float = s * 1.4142135f

    init {
        val j = irregularity.coerceIn(0f, 1f)
        // Seeds are hashed by grid coordinates (not the linear index) so the pattern does not
        // depend on the grid width, which may round differently in the downscaled preview.
        for (gy in 0 until gh) for (gx in 0 until gw) {
            val k = gy * gw + gx
            sx[k] = (gx + 0.5f + (PixelRandom.rand01(gx, gy, 11, seed) - 0.5f) * j) * s
            sy[k] = (gy + 0.5f + (PixelRandom.rand01(gx, gy, 12, seed) - 0.5f) * j) * s
        }
    }

    override fun rowStart(row: Int): Int = row * gw

    override fun cellAt(px: Float, py: Float, center: FloatArray?): Int {
        val gx = min(gw - 1, max(0, floor(px * inv).toInt()))
        val gy = min(gh - 1, max(0, floor(py * inv).toInt()))
        var best = gy * gw + gx
        var bestD = Float.MAX_VALUE
        for (y in max(0, gy - 1)..min(gh - 1, gy + 1)) {
            val rowK = y * gw
            for (x in max(0, gx - 1)..min(gw - 1, gx + 1)) {
                val k = rowK + x
                val dx = sx[k] - px; val dy = sy[k] - py
                val d = dx * dx + dy * dy
                if (d < bestD) { bestD = d; best = k }
            }
        }
        if (center != null) { center[0] = sx[best]; center[1] = sy[best] }
        return best
    }

    override fun centerOf(row: Int, cell: Int, out: FloatArray) {
        out[0] = sx[cell]; out[1] = sy[cell]
    }
}

internal const val SQRT3 = 1.7320508f

/** Deterministic, well-mixed random numbers for the pixelate filters. */
internal object PixelRandom {
    private fun fmix(h0: Int): Int {
        var h = h0
        h = h xor (h ushr 16); h *= -0x7a143595
        h = h xor (h ushr 13); h *= -0x3d4d51cb
        return h xor (h ushr 16)
    }

    /** Uniform value in [0, 1) for ([index], [stream], [seed]). */
    fun rand01(index: Int, stream: Int, seed: Int): Float {
        val h = fmix(fmix(seed * -0x61c88647 + index) xor (stream * 0x27d4eb2f))
        return (h ushr 8) / 16777216f
    }

    /** Uniform value in [0, 1) for the 2D cell ([x], [y]), [stream] and [seed]. */
    fun rand01(x: Int, y: Int, stream: Int, seed: Int): Float {
        val h = fmix(fmix(fmix(seed * -0x61c88647 + x) xor (y * 0x27d4eb2f)) xor (stream * 0x165667b1))
        return (h ushr 8) / 16777216f
    }
}
