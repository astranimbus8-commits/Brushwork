package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import kotlin.math.abs
import kotlin.math.floor

/**
 * v1.7 (item 11, design §3.11 b): the mesh of the Transform tool's "Free deform" mode. The lifted
 * rectangle ([left], [top], [width] x [height] document px) is divided into [cols] x [rows] equal
 * cells; its (cols + 1) x (rows + 1) vertices (row by row, top first, document px) are where the
 * cells' corners go. Pure Kotlin: no android.graphics, so it is unit-tested on the JVM.
 *
 * Between the vertices the image follows either the cells' bilinear patches, or (smooth) a
 * Catmull-Rom surface through every vertex (C1 across cells). Past the outer vertices the surface
 * continues linearly (ghost vertices extrapolated from the last two), so a mesh that is an affine
 * map of its rectangle stays exactly that map, smooth or not.
 *
 * Changing the number of cells of a deformed mesh ([resampled]) keeps its surface EXACTLY: the
 * new mesh remembers the old one as its [base] and adds only what its own vertices change (the
 * surface through the new vertices, plus the old surface's difference from the surface through
 * its samples at the new vertices; zero at every vertex). The image never jumps when cells are
 * added or removed, and the new vertices lie on the image as it was.
 *
 * Immutable: every change returns a new mesh.
 */
internal class MeshDeform private constructor(
    val left: Float,
    val top: Float,
    val width: Float,
    val height: Float,
    val cols: Int,
    val rows: Int,
    /** x0, y0, x1, y1... for (cols + 1) x (rows + 1) vertices, row by row. */
    private val xy: FloatArray,
    /** The mesh this one was resampled from (its surface is kept), or null. */
    private val base: MeshDeform? = null,
) {
    /** The number of vertices, (cols + 1) x (rows + 1). */
    val vertexCount: Int get() = (cols + 1) * (rows + 1)

    /** How many cell changes this mesh carries (0: a plain mesh). */
    val depth: Int get() = base?.let { it.depth + 1 } ?: 0

    /** The index of the vertex in column [i] (0..cols) and row [j] (0..rows). */
    fun index(i: Int, j: Int): Int = j * (cols + 1) + i

    /** Vertex [k] (document px). */
    fun vertex(k: Int): Vec2 = Vec2(xy[2 * k], xy[2 * k + 1])

    /** Every vertex, row by row (document px). */
    fun vertices(): List<Vec2> = List(vertexCount) { vertex(it) }

    /** This mesh with its vertices replaced by [points] (one per vertex, row by row). */
    fun withVertices(points: List<Vec2>): MeshDeform {
        require(points.size == vertexCount) { "${points.size} points for a mesh of $vertexCount vertices" }
        val out = FloatArray(xy.size)
        for (k in points.indices) { out[2 * k] = points[k].x; out[2 * k + 1] = points[k].y }
        return copy(out)
    }

    /** The vertices [indices] moved by ([dx], [dy]). */
    fun moved(indices: Collection<Int>, dx: Float, dy: Float): MeshDeform {
        val out = xy.copyOf()
        for (k in indices) if (k in 0 until vertexCount) { out[2 * k] += dx; out[2 * k + 1] += dy }
        return copy(out)
    }

    /** The vertices [indices] mapped by [m] (a group's move, scale or turn). */
    fun mapped(indices: Collection<Int>, m: Affine2): MeshDeform {
        val out = xy.copyOf()
        for (k in indices) if (k in 0 until vertexCount) {
            val p = m.map(Vec2(xy[2 * k], xy[2 * k + 1]))
            out[2 * k] = p.x; out[2 * k + 1] = p.y
        }
        return copy(out)
    }

    private fun copy(points: FloatArray) = MeshDeform(left, top, width, height, cols, rows, points, base)

    /**
     * Where the source point at ([s], [t]) of the rectangle (0..1 each: 0, 0 its top-left corner,
     * 1, 1 its bottom-right) goes, through the bilinear cells or the [smooth] surface.
     */
    fun at(s: Float, t: Float, smooth: Boolean): Vec2 {
        val u = s * cols
        val v = t * rows
        val ox = surface(xy, u, v, smooth, true)
        val oy = surface(xy, u, v, smooth, false)
        val b = base ?: return Vec2(ox, oy)
        // The base's surface minus the surface through its samples at this mesh's vertices.
        val samples = baseSamples(smooth)
        val bp = b.at(s, t, smooth)
        return Vec2(ox + bp.x - surface(samples, u, v, smooth, true), oy + bp.y - surface(samples, u, v, smooth, false))
    }

    private var samplesBilinear: FloatArray? = null
    private var samplesSmooth: FloatArray? = null

    /** The base's surface at this mesh's vertex places (cached per kind of surface). */
    private fun baseSamples(smooth: Boolean): FloatArray {
        (if (smooth) samplesSmooth else samplesBilinear)?.let { return it }
        val b = base!!
        val out = FloatArray(xy.size)
        var k = 0
        for (j in 0..rows) for (i in 0..cols) {
            val p = b.at(i.toFloat() / cols, j.toFloat() / rows, smooth)
            out[k++] = p.x; out[k++] = p.y
        }
        if (smooth) samplesSmooth = out else samplesBilinear = out
        return out
    }

    /**
     * The mesh with [newCols] x [newRows] cells (each clamped to 1..[MAX_CELLS]) showing exactly
     * this mesh's [smooth] (or bilinear) surface: its vertices lie on it at their own places in
     * the rectangle. Past [MAX_DEPTH] cell changes in a row the old surface is approximated by the
     * new vertices instead (no base), so evaluation stays cheap.
     */
    fun resampled(newCols: Int, newRows: Int, smooth: Boolean): MeshDeform {
        val nc = newCols.coerceIn(1, MAX_CELLS)
        val nr = newRows.coerceIn(1, MAX_CELLS)
        if (nc == cols && nr == rows) return this
        val out = FloatArray((nc + 1) * (nr + 1) * 2)
        var k = 0
        for (j in 0..nr) for (i in 0..nc) {
            val p = at(i.toFloat() / nc, j.toFloat() / nr, smooth)
            out[k++] = p.x; out[k++] = p.y
        }
        // An untouched mesh needs no base: its surface is the identity at any cell count.
        val keep = if (isIdentity(0f) || depth >= MAX_DEPTH) null else this
        return MeshDeform(left, top, width, height, nc, nr, out, keep)
    }

    /**
     * A finer mesh for drawing ([sub] parts per cell along each axis): (cols x sub + 1) x
     * (rows x sub + 1) points, row by row, x then y, as `Canvas.drawBitmapMesh` takes them with
     * `meshWidth = cols x sub`, `meshHeight = rows x sub`.
     */
    fun dense(sub: Int, smooth: Boolean): FloatArray {
        val n = sub.coerceAtLeast(1)
        val w = cols * n
        val h = rows * n
        val out = FloatArray((w + 1) * (h + 1) * 2)
        var k = 0
        for (j in 0..h) for (i in 0..w) {
            val p = at(i.toFloat() / w, j.toFloat() / h, smooth)
            out[k++] = p.x; out[k++] = p.y
        }
        return out
    }

    /** The bounds (left, top, right, bottom; document px) of [dense] points: what the deformed image covers. */
    fun bounds(sub: Int, smooth: Boolean): FloatArray = boundsOf(dense(sub, smooth))

    /** The vertex nearest to ([x], [y]) within [radius] (document px), or -1. */
    fun nearest(x: Float, y: Float, radius: Float): Int {
        var best = -1
        var bestD = radius * radius
        for (k in 0 until vertexCount) {
            val dx = xy[2 * k] - x
            val dy = xy[2 * k + 1] - y
            val d = dx * dx + dy * dy
            if (d <= bestD) { best = k; bestD = d }
        }
        return best
    }

    /** True when [o] is the same mesh: rectangle, cells, vertices and base. */
    fun sameAs(o: MeshDeform): Boolean =
        o.cols == cols && o.rows == rows && o.left == left && o.top == top && o.width == width && o.height == height &&
            o.xy.contentEquals(xy) && (if (base == null) o.base == null else o.base != null && base.sameAs(o.base))

    /** True when the image is unchanged: every vertex on the even grid (within [eps] px) and no deformed base. */
    fun isIdentity(eps: Float = 1e-3f): Boolean {
        if (base != null && !base.isIdentity(eps)) return false
        for (j in 0..rows) for (i in 0..cols) {
            val k = index(i, j)
            val gx = left + width * i / cols
            val gy = top + height * j / rows
            if (abs(xy[2 * k] - gx) > eps || abs(xy[2 * k + 1] - gy) > eps) return false
        }
        return true
    }

    override fun equals(other: Any?): Boolean = other is MeshDeform && sameAs(other)

    override fun hashCode(): Int = ((cols * 31 + rows) * 31 + xy.contentHashCode()) * 31 + (base?.hashCode() ?: 0)

    override fun toString(): String = "MeshDeform(${cols}x$rows over $left,$top ${width}x$height, depth $depth)"

    // ------------------------------------------------------------------ evaluation

    /** The x ([x]) or y coordinate at cell coordinates ([u], [v]) of the surface through [pts]. */
    private fun surface(pts: FloatArray, u: Float, v: Float, smooth: Boolean, x: Boolean): Float {
        val i = floor(u).toInt().coerceIn(0, cols - 1)
        val j = floor(v).toInt().coerceIn(0, rows - 1)
        val fu = u - i
        val fv = v - j
        if (!smooth) {
            val a = p(pts, i, j, x) + (p(pts, i + 1, j, x) - p(pts, i, j, x)) * fu
            val b = p(pts, i, j + 1, x) + (p(pts, i + 1, j + 1, x) - p(pts, i, j + 1, x)) * fu
            return a + (b - a) * fv
        }
        val c0 = catmullRom(g(pts, i - 1, j - 1, x), g(pts, i, j - 1, x), g(pts, i + 1, j - 1, x), g(pts, i + 2, j - 1, x), fu)
        val c1 = catmullRom(g(pts, i - 1, j, x), g(pts, i, j, x), g(pts, i + 1, j, x), g(pts, i + 2, j, x), fu)
        val c2 = catmullRom(g(pts, i - 1, j + 1, x), g(pts, i, j + 1, x), g(pts, i + 1, j + 1, x), g(pts, i + 2, j + 1, x), fu)
        val c3 = catmullRom(g(pts, i - 1, j + 2, x), g(pts, i, j + 2, x), g(pts, i + 1, j + 2, x), g(pts, i + 2, j + 2, x), fu)
        return catmullRom(c0, c1, c2, c3, fv)
    }

    private fun p(pts: FloatArray, i: Int, j: Int, x: Boolean): Float = pts[2 * index(i, j) + if (x) 0 else 1]

    /** Vertex (i, j) of [pts], i in -1..cols + 1, j in -1..rows + 1: outside the grid, linearly extrapolated. */
    private fun g(pts: FloatArray, i: Int, j: Int, x: Boolean): Float = when {
        j < 0 -> 2f * row(pts, i, 0, x) - row(pts, i, 1, x)
        j > rows -> 2f * row(pts, i, rows, x) - row(pts, i, rows - 1, x)
        else -> row(pts, i, j, x)
    }

    private fun row(pts: FloatArray, i: Int, j: Int, x: Boolean): Float = when {
        i < 0 -> 2f * p(pts, 0, j, x) - p(pts, 1, j, x)
        i > cols -> 2f * p(pts, cols, j, x) - p(pts, cols - 1, j, x)
        else -> p(pts, i, j, x)
    }

    companion object {
        /** The most cells along either side ("Mesh columns" / "Mesh rows" go 1..12). */
        const val MAX_CELLS = 12

        /** The most cell changes a mesh keeps exactly (see [resampled]). */
        const val MAX_DEPTH = 6

        /** The even grid of [cols] x [rows] cells over the rectangle: the image unchanged. */
        fun identity(left: Float, top: Float, width: Float, height: Float, cols: Int, rows: Int): MeshDeform =
            fromMap(left, top, width, height, cols, rows, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f))

        /**
         * The grid of [cols] x [rows] cells over the rectangle with every vertex mapped by [m]
         * (3 x 3 row-major, document px to document px; a homography is applied projectively):
         * Free deform starts from what Free or Distort showed.
         */
        fun fromMap(left: Float, top: Float, width: Float, height: Float, cols: Int, rows: Int, m: FloatArray): MeshDeform {
            val c = cols.coerceIn(1, MAX_CELLS)
            val r = rows.coerceIn(1, MAX_CELLS)
            val out = FloatArray((c + 1) * (r + 1) * 2)
            var k = 0
            for (j in 0..r) for (i in 0..c) {
                val x = left + width * i / c
                val y = top + height * j / r
                val w = m[6] * x + m[7] * y + m[8]
                out[k++] = (m[0] * x + m[1] * y + m[2]) / w
                out[k++] = (m[3] * x + m[4] * y + m[5]) / w
            }
            return MeshDeform(left, top, width, height, c, r, out)
        }

        /** The bounds (left, top, right, bottom) of x, y pairs. */
        fun boundsOf(d: FloatArray): FloatArray {
            var l = Float.POSITIVE_INFINITY
            var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY
            var b = Float.NEGATIVE_INFINITY
            var k = 0
            while (k + 1 < d.size) {
                val x = d[k]; val y = d[k + 1]
                if (x < l) l = x
                if (x > r) r = x
                if (y < t) t = y
                if (y > b) b = y
                k += 2
            }
            return floatArrayOf(l, t, r, b)
        }

        /** The uniform Catmull-Rom segment from [p1] to [p2] at [t] (0..1). */
        private fun catmullRom(p0: Float, p1: Float, p2: Float, p3: Float, t: Float): Float {
            val t2 = t * t
            val t3 = t2 * t
            return 0.5f * (2f * p1 + (p2 - p0) * t + (2f * p0 - 5f * p1 + 4f * p2 - p3) * t2 + (3f * p1 - p0 - 3f * p2 + p3) * t3)
        }
    }
}
