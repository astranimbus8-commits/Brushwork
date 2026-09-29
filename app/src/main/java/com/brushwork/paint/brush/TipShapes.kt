package com.brushwork.paint.brush

import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * Procedural brush tip shapes and textures. Pure Kotlin: the same profiles feed the ALPHA_8 tip
 * bitmaps (see TipCache) and the direct smudge/blur/watercolor painters.
 */
object TipShapes {

    /** Number of texture variants generated for [tip] (1 = untextured). */
    fun variants(tip: BrushTip): Int = when (tip) {
        BrushTip.PENCIL, BrushTip.CHALK -> 4
        BrushTip.SPRAY -> 6
        else -> 1
    }

    /** Textured tips are rotated randomly per dab so the texture doesn't repeat visibly. */
    fun isTextured(tip: BrushTip): Boolean = variants(tip) > 1

    /**
     * Coverage 0..1 of an (untextured) tip at offset ([dx], [dy]) from its center, in the tip's
     * own (unrotated) frame. [radius] is half the diameter; [roundness] squashes the y axis.
     * With [antiAlias] the edge gets a 1 px coverage ramp; without it the shape is binary at the
     * exact edge (used for supersampling).
     */
    fun coverage(tip: BrushTip, hardness: Float, roundness: Float, dx: Float, dy: Float, radius: Float, antiAlias: Boolean = true): Float {
        if (radius <= 0f) return 0f
        val rho = roundness.coerceIn(BrushLimits.MIN_ROUNDNESS, 1f)
        val ey = dy / rho
        return when (tip) {
            BrushTip.AIRBRUSH -> {
                val q2 = (dx * dx + ey * ey) / (radius * radius)
                if (q2 >= 1f) 0f else {
                    val k = 2.2f + 4.5f * hardness.coerceIn(0f, 1f)
                    val fade = 1f - q2
                    exp(-q2 * k) * fade * fade
                }
            }
            BrushTip.SQUARE -> {
                val ax = abs(dx)
                val ay = abs(ey)
                val edge = if (antiAlias) ramp(radius - ax) * ramp((radius - ay) * rho) else if (ax <= radius && ay <= radius) 1f else 0f
                if (edge <= 0f) 0f else edge * soft(max(ax, ay), radius, hardness)
            }
            BrushTip.MARKER -> {
                val x2 = dx * dx
                val y2 = ey * ey
                val r = sqrt(sqrt(x2 * x2 + y2 * y2))
                val edge = if (antiAlias) ramp(radius - r) else if (r <= radius) 1f else 0f
                if (edge <= 0f) 0f else edge * soft(r, radius, hardness)
            }
            else -> {
                val r = sqrt(dx * dx + ey * ey)
                val edge = if (antiAlias) {
                    // Distance to the ellipse edge in pixels ~ (radius - r) / |grad r|.
                    val g = if (r > 1e-4f) sqrt(dx * dx + (ey / rho) * (ey / rho)) / r else 1f
                    ramp((radius - r) / max(g, 1e-4f))
                } else if (r <= radius) 1f else 0f
                if (edge <= 0f) 0f else edge * soft(r, radius, hardness)
            }
        }
    }

    /**
     * Rasterizes a tip of [diameter] px into a [size] x [size] coverage array (row-major,
     * unsigned bytes 0..255) centered at size / 2. [angleDeg] rotates the shape (pass 0 for tips
     * that are rotated when drawn). Without [antiAlias] every pixel is either 0 or 255.
     */
    fun rasterize(
        tip: BrushTip,
        diameter: Float,
        size: Int,
        hardness: Float,
        roundness: Float,
        angleDeg: Float,
        variant: Int,
        antiAlias: Boolean,
    ): ByteArray {
        val out = ByteArray(size * size)
        if (size <= 0 || diameter <= 0f) return out
        val c = size / 2f
        val radius = diameter / 2f
        if (tip == BrushTip.SPRAY) {
            rasterizeSpray(out, size, c, radius, hardness, variant, antiAlias)
            return out
        }
        val rad = -angleDeg * (PI.toFloat() / 180f)
        val cosA = cos(rad)
        val sinA = sin(rad)
        val supersample = !antiAlias || diameter < 6f
        val chalk = if (tip == BrushTip.CHALK) ChalkEdge(variant) else null
        val shapeTip = if (chalk != null || tip == BrushTip.PENCIL) BrushTip.ROUND_SOFT else tip
        val cell = max(1.5f, diameter / 7f)

        fun sample(px: Float, py: Float, aa: Boolean): Float {
            val dx = px - c
            val dy = py - c
            val lx = dx * cosA - dy * sinA
            val ly = dx * sinA + dy * cosA
            val r = if (chalk != null) radius * chalk.scale(atan2(ly, lx)) else radius
            return coverage(shapeTip, hardness, roundness, lx, ly, r, aa)
        }

        for (y in 0 until size) {
            for (x in 0 until size) {
                var v = if (supersample) {
                    var sum = 0f
                    for (sy in 0 until 4) for (sx in 0 until 4) {
                        sum += sample(x + (sx + 0.5f) / 4f, y + (sy + 0.5f) / 4f, false)
                    }
                    sum / 16f
                } else {
                    sample(x + 0.5f, y + 0.5f, true)
                }
                if (v <= 0f) continue
                when (tip) {
                    BrushTip.PENCIL -> v *= 0.45f + 0.55f * hash(x, y, variant * 131 + 7)
                    BrushTip.CHALK -> {
                        val n = 0.55f * valueNoise(x / cell, y / cell, variant * 17 + 3) + 0.45f * hash(x, y, variant * 29 + 11)
                        v *= smoothstep(0.22f, 0.62f, n)
                    }
                    else -> {}
                }
                if (!antiAlias) v = if (v >= 0.5f) 1f else 0f
                out[y * size + x] = (v.coerceIn(0f, 1f) * 255f + 0.5f).toInt().toByte()
            }
        }
        return out
    }

    private fun rasterizeSpray(out: ByteArray, size: Int, c: Float, radius: Float, hardness: Float, variant: Int, antiAlias: Boolean) {
        val diameter = radius * 2f
        val rng = Random(variant * 7919 + 101)
        val dots = (diameter * diameter * 0.03f).toInt().coerceIn(8, 1600)
        val dotR = max(0.6f, diameter * 0.016f)
        val reach = max(0f, radius - dotR)
        for (i in 0 until dots) {
            val rr = reach * sqrt(rng.nextFloat())
            val a = rng.nextFloat() * 2f * PI.toFloat()
            val px = c + rr * cos(a)
            val py = c + rr * sin(a)
            // Softer sprays have fainter outer dots.
            val intensity = (0.55f + 0.45f * rng.nextFloat()) * max(0.15f, soft(rr, radius, hardness))
            val x0 = max(0, floor(px - dotR - 1f).toInt())
            val x1 = min(size - 1, ceil(px + dotR + 1f).toInt())
            val y0 = max(0, floor(py - dotR - 1f).toInt())
            val y1 = min(size - 1, ceil(py + dotR + 1f).toInt())
            for (y in y0..y1) for (x in x0..x1) {
                val dx = x + 0.5f - px
                val dy = y + 0.5f - py
                var v = ramp(dotR - sqrt(dx * dx + dy * dy)) * intensity
                if (!antiAlias) v = if (v >= 0.5f) 1f else 0f
                val b = (v * 255f + 0.5f).toInt()
                val idx = y * size + x
                if (b > (out[idx].toInt() and 0xFF)) out[idx] = b.toByte()
            }
        }
    }

    /** Ragged radius of the chalk tip as a function of the angle (smooth periodic noise). */
    private class ChalkEdge(variant: Int) {
        private val rng = Random(variant * 977 + 5)
        private val phases = FloatArray(4) { rng.nextFloat() * 2f * PI.toFloat() }
        private val amps = floatArrayOf(0.35f, 0.3f, 0.2f, 0.15f)
        private val freqs = intArrayOf(3, 5, 9, 17)
        fun scale(angle: Float): Float {
            var n = 0f
            for (i in 0 until 4) n += amps[i] * sin(freqs[i] * angle + phases[i])
            // n in about -1..1 -> radius 0.8..1.0
            return 0.9f + 0.1f * n
        }
    }

    // ------------------------------------------------------------------ noise helpers

    /** Deterministic hash noise in [0, 1). */
    fun hash(x: Int, y: Int, seed: Int): Float {
        var h = x * 374761393 + y * 668265263 + seed * 1442695041
        h = (h xor (h ushr 13)) * 1274126177
        h = h xor (h ushr 16)
        return (h and 0xFFFFFF) / 16777216f
    }

    /**
     * Smooth value noise in [0, 1) on a unit lattice. With [period] > 0 the lattice wraps, so
     * the noise tiles every [period] units.
     */
    fun valueNoise(fx: Float, fy: Float, seed: Int, period: Int = 0): Float {
        val x0 = floor(fx).toInt()
        val y0 = floor(fy).toInt()
        val tx = fx - x0
        val ty = fy - y0
        fun h(x: Int, y: Int): Float =
            if (period > 0) hash(Math.floorMod(x, period), Math.floorMod(y, period), seed) else hash(x, y, seed)
        val sx = tx * tx * (3f - 2f * tx)
        val sy = ty * ty * (3f - 2f * ty)
        val a = h(x0, y0) + (h(x0 + 1, y0) - h(x0, y0)) * sx
        val b = h(x0, y0 + 1) + (h(x0 + 1, y0 + 1) - h(x0, y0 + 1)) * sx
        return a + (b - a) * sy
    }

    /**
     * Tileable paper texture ([size] x [size], values 0..1, 1 = paper "tooth" that catches
     * pigment). [size] must be a multiple of 64.
     */
    fun paperTexture(size: Int): FloatArray {
        val out = FloatArray(size * size)
        for (y in 0 until size) for (x in 0 until size) {
            val fine = hash(x, y, 4242)
            val medium = valueNoise(x / 4f, y / 4f, 77, size / 4)
            val coarse = valueNoise(x / 16f, y / 16f, 91, size / 16)
            val v = 0.45f * fine + 0.35f * medium + 0.2f * coarse
            out[y * size + x] = smoothstep(0.2f, 0.8f, v)
        }
        return out
    }

    fun smoothstep(e0: Float, e1: Float, v: Float): Float {
        val t = ((v - e0) / (e1 - e0)).coerceIn(0f, 1f)
        return t * t * (3f - 2f * t)
    }

    private fun ramp(v: Float): Float = (v + 0.5f).coerceIn(0f, 1f)

    /** Hardness falloff: 1 inside hardness * radius, smooth fade to 0 at the radius. */
    private fun soft(r: Float, radius: Float, hardness: Float): Float {
        val inner = radius * hardness.coerceIn(0f, 1f)
        if (r <= inner) return 1f
        val w = radius - inner
        if (w < 1e-3f) return 1f
        val u = 1f - ((r - inner) / w).coerceIn(0f, 1f)
        return u * u * (3f - 2f * u)
    }
}
