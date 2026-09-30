package com.brushwork.paint.segmentation

import com.brushwork.paint.core.PixelBuffer
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Per-target probabilities from the fused class probabilities (pure Kotlin). Everything here
 * runs on the small fusion grid ([w]x[h] cells, ~128 across the short side).
 *
 * - SKY = P(sky).
 * - WATER = P(water) + P(sea), plus "other" regions below the horizon that look like the water
 *   and touch it (the 32-class model has no river / lake / pool class).
 * - NATURE = P(tree) + P(grass) + P(plant) + P(field), plus "other" regions enclosed by
 *   vegetation or green and textured (flower beds, palms, bushes the model calls "other").
 * - BUILDINGS = P(building) + P(house), plus windows / doors attached to them.
 * - PEOPLE = P(person).
 */
internal object SceneTargets {
    private const val C = SceneClasses.COUNT

    /** Sum of the probabilities of [classes] per cell. */
    fun classSum(probs: FloatArray, n: Int, classes: IntArray): FloatArray {
        require(probs.size == n * C)
        val out = FloatArray(n)
        for (i in 0 until n) {
            var s = 0f
            for (k in classes) s += probs[i * C + k]
            out[i] = min(1f, s)
        }
        return out
    }

    /** Most likely class per cell. */
    fun argmax(probs: FloatArray, n: Int): IntArray = IntArray(n) { i ->
        var best = 0; var bp = -1f
        for (k in 0 until C) { val p = probs[i * C + k]; if (p > bp) { bp = p; best = k } }
        best
    }

    /**
     * Probability of [target] per fusion cell. [cells] is the working image area-averaged to the
     * grid (colors for the "other" adoption rules); [vegetation] the vegetation heuristic on the
     * same grid (optional).
     */
    fun probability(probs: FloatArray, w: Int, h: Int, target: SmartTarget, cells: PixelBuffer?, vegetation: FloatArray? = null): FloatArray {
        val n = w * h
        val base = classSum(probs, n, SceneClasses.classesFor(target))
        return when (target) {
            SmartTarget.WATER -> if (cells != null) adoptWater(probs, base, w, h, cells) else base
            SmartTarget.NATURE -> adoptEnclosedOther(probs, base, w, h, vegetation)
            SmartTarget.BUILDINGS -> attach(probs, base, w, h, SceneClasses.attachedClassesFor(target))
            else -> base
        }
    }

    // ------------------------------------------------------------------ water

    /**
     * Adds "other" cells that lie below the horizon (the lowest row that is mostly sky), have a
     * color close to the confident water (k-means model) and are 4-connected to it.
     */
    internal fun adoptWater(probs: FloatArray, water: FloatArray, w: Int, h: Int, cells: PixelBuffer): FloatArray {
        val n = w * h
        require(cells.width == w && cells.height == h)
        val out = water.copyOf()
        val arg = argmax(probs, n)
        val sky = classSum(probs, n, intArrayOf(SceneClasses.SKY))
        var horizon = 0
        for (y in 0 until h) {
            var s = 0
            for (x in 0 until w) if (sky[y * w + x] > 0.5f) s++
            if (s > w / 5) horizon = y + 1
        }
        val seeds = BooleanArray(n) { water[it] > 0.6f }
        val nSeeds = seeds.count { it }
        if (nSeeds < 2) return out
        val samples = FloatArray(nSeeds * 3)
        var m = 0
        for (i in 0 until n) if (seeds[i]) { rgb01(cells.pixels[i], samples, m * 3); m++ }
        val k = min(3, m)
        val centers = SceneHeuristics.kMeans(samples, m, k, iterations = 6)
        val tmp = FloatArray(3)
        val candidate = BooleanArray(n) { i ->
            val y = i / w
            if (y < horizon || arg[i] != SceneClasses.OTHER || seeds[i]) return@BooleanArray false
            rgb01(cells.pixels[i], tmp, 0)
            nearest(tmp, centers, k) < WATER_COLOR_DIST2
        }
        val reach = Regions.floodFrom(BooleanArray(n) { seeds[it] || candidate[it] }, w, h) { x, y -> seeds[y * w + x] }
        for (i in 0 until n) {
            if (candidate[i] && reach[i]) out[i] = max(out[i], min(1f, water[i] + probs[i * C + SceneClasses.OTHER]))
        }
        return out
    }

    /** Squared RGB distance (0..1 channels) under which an "other" cell looks like the water. */
    private const val WATER_COLOR_DIST2 = 0.012f

    // ------------------------------------------------------------------ nature

    /**
     * Adds connected "other" regions (argmax) that are enclosed by the target (at least 70 % of
     * their outer boundary is the target and they do not touch the image edge), or that border
     * it and look like vegetation (mean heuristic score >= 0.5).
     */
    internal fun adoptEnclosedOther(probs: FloatArray, base: FloatArray, w: Int, h: Int, vegetation: FloatArray?): FloatArray {
        val n = w * h
        val out = base.copyOf()
        val arg = argmax(probs, n)
        val lab = Regions.label(BooleanArray(n) { arg[it] == SceneClasses.OTHER }, w, h, eightConnected = false)
        if (lab.count == 0) return out
        val border = IntArray(lab.count + 1)
        val borderTarget = IntArray(lab.count + 1)
        val touchesEdge = BooleanArray(lab.count + 1)
        val vegSum = FloatArray(lab.count + 1)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            val id = lab.ids[i]
            if (id == 0) continue
            if (x == 0 || y == 0 || x == w - 1 || y == h - 1) touchesEdge[id] = true
            if (vegetation != null) vegSum[id] += vegetation[i]
            for (d in 0 until 4) {
                val nx = x + DX[d]; val ny = y + DY[d]
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (lab.ids[j] == id) continue
                border[id]++
                if (base[j] > 0.5f) borderTarget[id]++
            }
        }
        val adopt = BooleanArray(lab.count + 1) { id ->
            if (id == 0 || border[id] == 0) return@BooleanArray false
            val frac = borderTarget[id].toFloat() / border[id]
            val enclosed = !touchesEdge[id] && frac >= 0.7f
            val green = vegetation != null && frac >= 0.25f && vegSum[id] / lab.sizes[id] >= 0.5f
            enclosed || green
        }
        for (i in 0 until n) {
            val id = lab.ids[i]
            if (id != 0 && adopt[id]) out[i] = max(out[i], min(1f, base[i] + probs[i * C + SceneClasses.OTHER]))
        }
        return out
    }

    // ------------------------------------------------------------------ buildings

    /**
     * Adds [attached] classes (windows, doors) per 8-connected region (argmax) where the region
     * touches a cell that is mostly the target: facade windows, not indoor ones.
     */
    internal fun attach(probs: FloatArray, base: FloatArray, w: Int, h: Int, attached: IntArray): FloatArray {
        if (attached.isEmpty()) return base
        val n = w * h
        val arg = argmax(probs, n)
        val isAttached = BooleanArray(n) { arg[it] in attached }
        if (isAttached.none { it }) return base
        val out = base.copyOf()
        val lab = Regions.label(isAttached, w, h, eightConnected = true)
        val touches = BooleanArray(lab.count + 1)
        for (y in 0 until h) for (x in 0 until w) {
            val id = lab.ids[y * w + x]
            if (id == 0 || touches[id]) continue
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx < 0 || ny < 0 || nx >= w || ny >= h) continue
                val j = ny * w + nx
                if (lab.ids[j] == 0 && base[j] > 0.5f) { touches[id] = true; break@loop }
            }
        }
        val extra = classSum(probs, n, attached)
        for (i in 0 until n) {
            val id = lab.ids[i]
            if (id != 0 && touches[id]) out[i] = min(1f, out[i] + extra[i])
        }
        return out
    }

    // ------------------------------------------------------------------ helpers

    private val DX = intArrayOf(1, -1, 0, 0)
    private val DY = intArrayOf(0, 0, 1, -1)

    private fun rgb01(c: Int, dst: FloatArray, o: Int) {
        dst[o] = ((c shr 16) and 0xFF) / 255f
        dst[o + 1] = ((c shr 8) and 0xFF) / 255f
        dst[o + 2] = (c and 0xFF) / 255f
    }

    private fun nearest(v: FloatArray, centers: FloatArray, k: Int): Float {
        var best = Float.MAX_VALUE
        for (j in 0 until k) {
            val d0 = v[0] - centers[j * 3]; val d1 = v[1] - centers[j * 3 + 1]; val d2 = v[2] - centers[j * 3 + 2]
            best = min(best, d0 * d0 + d1 * d1 + d2 * d2)
        }
        return best
    }
}

/**
 * Hysteresis on a probability plane: connected regions (8-connected) of `p > low` survive only
 * if they contain a seed `p > high` and have at least [minSize] cells; everything else is
 * zeroed. Survivors keep their soft values (one cell of margin keeps the soft edge), and holes
 * of at most [minSize] cells inside them are filled.
 */
internal object Hysteresis {
    fun apply(p: FloatArray, w: Int, h: Int, high: Float = 0.6f, low: Float = 0.35f, minSize: Int = 2): FloatArray {
        val n = w * h
        require(p.size == n)
        val lab = Regions.label(BooleanArray(n) { p[it] > low }, w, h, eightConnected = true)
        val seeded = BooleanArray(lab.count + 1)
        for (i in 0 until n) if (p[i] > high) seeded[lab.ids[i]] = true
        val keep = BooleanArray(n) { val id = lab.ids[it]; id != 0 && seeded[id] && lab.sizes[id] >= minSize }
        if (keep.none { it }) return FloatArray(n)
        val core = keep.copyOf()
        if (minSize > 1) Regions.fillHoles(core, w, h, maxHoleSize = minSize)
        val out = FloatArray(n)
        for (y in 0 until h) for (x in 0 until w) {
            val i = y * w + x
            if (core[i]) { out[i] = if (keep[i]) p[i] else max(p[i], high); continue }
            // One cell of soft margin around kept regions.
            var near = false
            loop@ for (dy in -1..1) for (dx in -1..1) {
                val nx = x + dx; val ny = y + dy
                if (nx in 0 until w && ny in 0 until h && keep[ny * w + nx]) { near = true; break@loop }
            }
            if (near) out[i] = p[i]
        }
        return out
    }
}

/** Euclidean length of an RGB difference in 0..1 channel units. */
internal fun colorDistance(a: Int, b: Int): Float {
    val dr = (((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) / 255f
    val dg = (((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) / 255f
    val db = ((a and 0xFF) - (b and 0xFF)) / 255f
    return sqrt(dr * dr + dg * dg + db * db)
}
