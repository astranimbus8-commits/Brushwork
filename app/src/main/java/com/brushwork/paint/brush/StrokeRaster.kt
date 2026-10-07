package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.vector.StrokeCopies
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Offline, thread-safe replay of a recorded brush stroke (`VStroke`): the same sampler,
 * dynamics, dab stamper, coverage painter and [StrokeCost] spacing as the live BufferStroke, so a
 * vector stroke re-renders like it was drawn (v1.5 §5.4; API frozen, owned by A1 after F2). Use
 * one instance per thread (it reuses its coverage buffer, paints and tips).
 *
 * The replay feeds the points exactly as `BrushTool`'s stroke does: the first point begins the
 * stroke, every later one (the up point included) first sets the cost-limited spacing for the
 * distance from the previous point, then joins the sampler; the sampler is ended and every dab is
 * resolved with the final length (what the live stroke's end-taper pass leaves), then stamped in
 * order into an ALPHA_8 coverage buffer that is composited with the stroke opacity and grain.
 *
 * Every dab that reaches [render]'s clip is stamped into the coverage buffer cut only by the
 * `cut` rect (the document, where the live stroke's document-sized buffer cuts it; the buffer
 * covers those dabs: about the clip plus a dab's size), whose origin lies on the paper-grain grid
 * ([PaperGrain.SIZE]) so grain stays anchored to the document, and only the clip is composited:
 * a dab cut by a clip can round a pixel by one level differently, so with `cut` = the document
 * the pixels inside a clip never depend on where the clip is, and equal the live stroke's. A
 * grain stroke is composited in [COMPOSITE_TILE] squares, so its offscreen layer stays small
 * however large the stroke. Only PAINT coverage strokes are replayed (smudge / blur / watercolor
 * tips are drawn as coverage too; vector layers never record them). The replay always composites
 * SRC_OVER (an alpha-locked layer's live stroke is SRC_ATOP: vector strokes must not be recorded
 * there).
 */
class StrokeRaster(private val tips: TipCache = TipCache(8L shl 20)) {
    private val stamper = DabStamper(tips)
    private val painter = CoveragePainter()
    private val dabs = ArrayList<Dab>()
    private var buffer: Bitmap? = null
    private var bufferCanvas: Canvas? = null

    /**
     * Replays the stroke into [canvas] (document px) clipped to [clip]: [points] with their RAW
     * pressures (Stroke.pressureOf is applied here), [seed] for the random values, tapers at
     * the ends unless [taperIn] / [taperOut] are false. [sizeScale] multiplies the brush size and
     * the taper lengths (a scaled stroke); [opacity] multiplies the stroke opacity. Returns what
     * was painted (document px, within [clip]).
     *
     * [cut] (v1.5 F2 review, additive): where the dabs are cut, as the live stroke's coverage
     * buffer cuts them — pass the document rect. The dabs reaching [clip] are then stamped whole
     * within it, so the pixels inside [clip] are exactly those of a full render and of the live
     * stroke wherever the clip lies. Null cuts the dabs at [clip] itself: a clip cutting through
     * the stroke can then differ from a full render by one level at a few pixels.
     *
     * [copies] (v1.7 item 18, the seam of F1): a vector stroke's symmetry maps (`VStroke.copies`,
     * row-major 3×3, the identity first). Every dab is stamped, then each of its copies through
     * [DabMapping] in map order, into the ONE buffer, exactly as the live stroke did (so
     * overlapping copies don't darken each other and the replay equals the live stroke); an empty
     * list (or the identity alone) replays the v1.6 stroke unchanged.
     */
    fun render(
        canvas: Canvas,
        clip: Rect,
        preset: BrushPreset,
        color: Int,
        seed: Long,
        stylus: Boolean,
        points: PackedPoints,
        sizeScale: Float = 1f,
        opacity: Float = 1f,
        taperIn: Boolean = true,
        taperOut: Boolean = true,
        cut: Rect? = null,
        copies: List<FloatArray> = emptyList(),
    ): Rect {
        val n = points.size
        if (n == 0 || clip.isEmpty) return Rect()
        val p = replayPreset(preset, sizeScale, taperIn, taperOut)
        val style = CoverageStyle(
            mode = PorterDuff.Mode.SRC_OVER,
            color = color,
            opacity = (p.opacity * opacity.coerceIn(0f, 1f)),
            grain = p.grain,
        )
        if (!painter.isVisible(style)) return Rect()
        // Cheap reject: nothing the stroke can paint reaches the clip.
        val reach = bounds(p, 1f, points)
        val mapping = DabMapping.of(copies, stamper)
        if (mapping == null) {
            if (!RectF.intersects(reach, RectF(clip))) return Rect()
        } else {
            val all = copiesReach(p, reach, copies)
            if (all != null && !RectF.intersects(all, RectF(clip))) return Rect()
        }

        val dynamics = StrokeDynamics(p, stylus, seed)
        lastLength = collectDabs(dynamics, p, stylus, points, dabs)

        // The dabs as the live stroke leaves them. Those reaching the clip are stamped whole but
        // for [cut] (the buffer covers [area], their parts within it, past the clip if need be): a
        // dab cut by a clip can round a pixel by one level differently, so the pixels inside the
        // clip must not depend on where the clip is. Only [region], within the clip, is composited.
        val bound = cut ?: clip
        val total = lastLength
        val area = Rect()
        val tmp = Rect()
        for (dab in dabs) {
            dynamics.resolve(dab, total)
            stamper.measure(p, dab)
            if (mapping != null) copiesArea(mapping, p, dab, clip, bound, area, tmp)
            if (!dab.hasBounds) continue
            tmp.set(dab.left, dab.top, dab.right, dab.bottom)
            if (Rect.intersects(clip, tmp) && tmp.intersect(bound)) area.union(tmp)
        }
        val region = Rect(area)
        if (area.isEmpty || !region.intersect(clip)) { dabs.clear(); return Rect() }

        // Coverage buffer whose origin lies on the 256 px grain grid (document-anchored grain).
        val left = Math.floorDiv(area.left, PaperGrain.SIZE) * PaperGrain.SIZE
        val top = Math.floorDiv(area.top, PaperGrain.SIZE) * PaperGrain.SIZE
        val w = area.right - left
        val h = area.bottom - top
        try {
            val cov = coverage(w, h)
            val cc = bufferCanvas!!
            cc.save()
            cc.clipRect(0, 0, w, h)
            cc.drawColor(0, PorterDuff.Mode.CLEAR)
            cc.translate(-left.toFloat(), -top.toFloat())
            // Within [area] only where [cut] ends (no dab reaching the clip extends past it otherwise).
            cc.clipRect(area)
            for (dab in dabs) {
                if (mapping != null) {
                    // The live stroke's order: the dab, then each of its copies.
                    if (dab.hasBounds) {
                        tmp.set(dab.left, dab.top, dab.right, dab.bottom)
                        if (Rect.intersects(clip, tmp) && Rect.intersects(area, tmp)) stamper.stamp(cc, p, dab)
                    }
                    stampCopies(cc, mapping, p, dab, clip, area, tmp)
                    continue
                }
                if (!dab.hasBounds) continue
                tmp.set(dab.left, dab.top, dab.right, dab.bottom)
                if (Rect.intersects(clip, tmp) && Rect.intersects(area, tmp)) stamper.stamp(cc, p, dab)
            }
            cc.restore()

            canvas.save()
            canvas.translate(left.toFloat(), top.toFloat())
            if (style.grain <= 0f) {
                // One plain draw of the coverage, with no offscreen layer.
                painter.draw(canvas, cov, Rect(region).apply { offset(-left, -top) }, style, null)
            } else {
                // Grain composites through an offscreen layer as large as what is drawn: tile by
                // tile ([COMPOSITE_TILE] squares on the buffer's grid, only where dabs landed),
                // like the live commit, so that layer stays tile-sized however large the stroke.
                // Unscaled, clipped composites of the same coverage give the same pixels.
                // (Copies aren't listed: a symmetric stroke composites every square of its region.)
                val tiles = if (mapping == null) touchedTiles(region, left, top, w, h) else allTiles(w, h)
                val cols = (w + COMPOSITE_TILE - 1) / COMPOSITE_TILE
                val t = Rect()
                for (i in tiles.indices) {
                    if (!tiles[i]) continue
                    val x = (i % cols) * COMPOSITE_TILE
                    val y = (i / cols) * COMPOSITE_TILE
                    t.set(x, y, x + COMPOSITE_TILE, y + COMPOSITE_TILE)
                    if (t.intersect(region.left - left, region.top - top, w, h)) painter.draw(canvas, cov, t, style, null)
                }
            }
            canvas.restore()
        } finally {
            dabs.clear()
        }
        return region
    }

    /** [COMPOSITE_TILE] squares of the buffer (origin [left], [top], [w] x [h]) that hold part of a dab within [region]. */
    private fun touchedTiles(region: Rect, left: Int, top: Int, w: Int, h: Int): BooleanArray {
        val cols = (w + COMPOSITE_TILE - 1) / COMPOSITE_TILE
        val rows = (h + COMPOSITE_TILE - 1) / COMPOSITE_TILE
        val hit = BooleanArray(cols * rows)
        val r = Rect()
        for (dab in dabs) {
            if (!dab.hasBounds) continue
            r.set(dab.left, dab.top, dab.right, dab.bottom)
            if (!r.intersect(region)) continue
            r.offset(-left, -top)
            for (row in r.top / COMPOSITE_TILE..(r.bottom - 1) / COMPOSITE_TILE) {
                for (col in r.left / COMPOSITE_TILE..(r.right - 1) / COMPOSITE_TILE) hit[row * cols + col] = true
            }
        }
        return hit
    }

    private fun allTiles(w: Int, h: Int): BooleanArray =
        BooleanArray(((w + COMPOSITE_TILE - 1) / COMPOSITE_TILE) * ((h + COMPOSITE_TILE - 1) / COMPOSITE_TILE)) { true }

    /** Unions into [area] the parts within [bound] of [dab]'s copies that reach [clip] (as for the dab itself). */
    private fun copiesArea(m: DabMapping, p: BrushPreset, dab: Dab, clip: Rect, bound: Rect, area: Rect, tmp: Rect) {
        for (k in 0 until m.copies) {
            val c = m.place(k, p, dab) ?: continue
            if (!c.hasBounds) continue
            tmp.set(c.left, c.top, c.right, c.bottom)
            if (Rect.intersects(clip, tmp) && tmp.intersect(bound)) area.union(tmp)
        }
    }

    /** Stamps [dab]'s copies that reach [clip] and [area], in map order. */
    private fun stampCopies(canvas: Canvas, m: DabMapping, p: BrushPreset, dab: Dab, clip: Rect, area: Rect, tmp: Rect) {
        for (k in 0 until m.copies) {
            val c = m.place(k, p, dab) ?: continue
            if (!c.hasBounds) continue
            tmp.set(c.left, c.top, c.right, c.bottom)
            if (Rect.intersects(clip, tmp) && Rect.intersects(area, tmp)) m.stamp(canvas, k, c)
        }
    }

    /**
     * Everything the copies of a stroke reaching [own] (its own [bounds]) can paint: each map's
     * image of that box grown by the dab reach × the largest √|det J| at its corners (where a
     * perspective map's scale peaks). Null when a map takes part of the box over its horizon (no
     * cheap reject then).
     */
    private fun copiesReach(p: BrushPreset, own: RectF, copies: List<FloatArray>): RectF? {
        if (own.isEmpty) return own
        val e = reach(p, 1f)
        val out = RectF()
        val xs = floatArrayOf(own.left, own.right, own.right, own.left)
        val ys = floatArrayOf(own.top, own.top, own.bottom, own.bottom)
        for (m in copies) {
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            var s = 0f
            for (i in 0 until 4) {
                val w = m[6] * xs[i] + m[7] * ys[i] + m[8]
                if (!(w > 0f)) return null
                val x = (m[0] * xs[i] + m[1] * ys[i] + m[2]) / w
                val y = (m[3] * xs[i] + m[4] * ys[i] + m[5]) / w
                if (!x.isFinite() || !y.isFinite()) return null
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
                s = max(s, StrokeCopies.scaleAt(m, xs[i], ys[i]))
            }
            val g = e * s
            if (out.isEmpty) out.set(l - g, t - g, r + g, b + g) else out.union(l - g, t - g, r + g, b + g)
        }
        return out
    }

    /** Everything the stroke can paint (document px). */
    fun bounds(preset: BrushPreset, sizeScale: Float, points: PackedPoints): RectF = strokeBounds(preset, sizeScale, points)

    /** Length of the last sampled stroke. */
    private var lastLength = 0f

    private fun coverage(w: Int, h: Int): Bitmap {
        val b = buffer
        if (b != null && !b.isRecycled && b.width >= w && b.height >= h) return b
        b?.recycle()
        // Grown to hold both shapes: every buffer area lies within its render's clip grown by a
        // dab's size (+ the grain grid margin), so a buffer never outgrows the largest clip
        // rendered since it was freed by much.
        val nw = max(w, b?.width ?: 0)
        val nh = max(h, b?.height ?: 0)
        val n = Bitmap.createBitmap(max(1, nw), max(1, nh), Bitmap.Config.ALPHA_8)
        buffer = n
        bufferCanvas = Canvas(n)
        return n
    }

    /**
     * Frees the coverage buffer (it is allocated again on demand, as large as the largest region
     * rendered since); the tips are kept. Call it when this instance's renders are done.
     */
    fun releaseCoverage() {
        buffer?.recycle()
        buffer = null
        bufferCanvas = null
        dabs.clear()
    }

    /** Frees the coverage buffer and the tips (they are recreated on demand). */
    fun release() {
        releaseCoverage()
        tips.clear()
    }

    companion object {
        /**
         * Side of the squares a grain stroke is composited in (a multiple of [PaperGrain.SIZE]):
         * the grain's offscreen layer is at most this square (1 MB) whatever the stroke's size.
         */
        const val COMPOSITE_TILE = 512

        /** The live stroke's `pressureOf`: stylus pressure clamped, 1 for fingers (and NaN). */
        fun pressureOf(stylus: Boolean, raw: Float): Float = if (stylus && !raw.isNaN()) raw.coerceIn(0f, 1f) else 1f

        /**
         * Feeds [points] to a sampler exactly as BrushTool's stroke does (the first point begins
         * the stroke; every later one first sets the cost-limited spacing for the distance from
         * the previous point), collecting the dabs into [out] (cleared first). Returns the length
         * of the sampled path (the dabs are not resolved yet).
         */
        internal fun collectDabs(dynamics: StrokeDynamics, preset: BrushPreset, stylus: Boolean, points: PackedPoints, out: MutableList<Dab>): Float {
            out.clear()
            if (points.size == 0) return 0f
            val xs = points.x; val ys = points.y; val ps = points.p
            var spacingScale = 1f
            val sampler = StrokeSampler(
                spacingAt = { pr, d -> dynamics.spacing(pr, d) * spacingScale },
                onSample = { x, y, pr, d -> out += dynamics.newDab(x, y, pr, d) },
            )
            var lastX = xs[0]
            var lastY = ys[0]
            sampler.begin(xs[0], ys[0], pressureOf(stylus, ps[0]))
            for (i in 1 until points.size) {
                val x = xs[i]; val y = ys[i]
                // Stroke.limitCost: large brushes on long segments space their dabs further apart.
                val len = hypot(x - lastX, y - lastY)
                lastX = x; lastY = y
                spacingScale = StrokeCost.spacingScale(len, dynamics.size, preset.spacing, { d -> d * d }, StrokeCost.BUFFER_BUDGET)
                sampler.add(x, y, pressureOf(stylus, ps[i]))
            }
            sampler.end()
            return sampler.length
        }

        /**
         * The dabs a replay of the stroke stamps, as (centre x, centre y, radius) triples in
         * stamping order: exactly the replay's dabs ([render]: same sampler, dynamics, spacing
         * and final-length tapers), the radius half the dab's diameter (its tip's visible disc).
         * For precise hit tests and erasers (a tapered end is thin, a pressure swell wide).
         */
        fun dabCircles(
            preset: BrushPreset,
            sizeScale: Float,
            stylus: Boolean,
            seed: Long,
            points: PackedPoints,
            taperIn: Boolean = true,
            taperOut: Boolean = true,
        ): FloatArray {
            if (points.size == 0) return FloatArray(0)
            val p = replayPreset(preset, sizeScale, taperIn, taperOut)
            val dynamics = StrokeDynamics(p, stylus, seed)
            val dabs = ArrayList<Dab>()
            val total = collectDabs(dynamics, p, stylus, points, dabs)
            val out = FloatArray(dabs.size * 3)
            for ((i, dab) in dabs.withIndex()) {
                dynamics.resolve(dab, total)
                out[3 * i] = dab.cx
                out[3 * i + 1] = dab.cy
                out[3 * i + 2] = dab.diameter / 2f
            }
            return out
        }

        /**
         * The brush a replay paints with: [preset] sanitized (as at stroke start); [sizeScale]
         * multiplies its size and taper lengths; a cut end ([taperIn] / [taperOut] false) has no
         * taper. Unchanged (equal) for a plain replay.
         */
        fun replayPreset(preset: BrushPreset, sizeScale: Float, taperIn: Boolean, taperOut: Boolean): BrushPreset {
            var p = preset.sanitized()
            val s = if (sizeScale.isFinite() && sizeScale > 0f) sizeScale else 1f
            if (s != 1f) p = p.copy(size = p.size * s, taperStart = p.taperStart * s, taperEnd = p.taperEnd * s)
            if (!taperIn && p.taperStart != 0f) p = p.copy(taperStart = 0f)
            if (!taperOut && p.taperEnd != 0f) p = p.copy(taperEnd = 0f)
            return p
        }

        /**
         * How far a dab of [preset] (scaled by [sizeScale]) can paint from its path point: the
         * largest dab (pressure and taper never enlarge it), its tip margin and rotation, the
         * scatter and the rounding of dab bounds. Conservative.
         */
        fun reach(preset: BrushPreset, sizeScale: Float): Float {
            val p = preset.sanitized()
            val s = if (sizeScale.isFinite() && sizeScale > 0f) sizeScale else 1f
            val d = max(1f, p.size * s)
            return d * (0.75f + p.scatter) + 5f
        }

        /** Everything a stroke of [preset] along [points] can paint (document px; empty without points). */
        fun strokeBounds(preset: BrushPreset, sizeScale: Float, points: PackedPoints): RectF {
            if (points.size == 0) return RectF()
            var l = Float.POSITIVE_INFINITY; var t = Float.POSITIVE_INFINITY
            var r = Float.NEGATIVE_INFINITY; var b = Float.NEGATIVE_INFINITY
            val xs = points.x; val ys = points.y
            for (i in 0 until points.size) {
                val x = xs[i]; val y = ys[i]
                if (!x.isFinite() || !y.isFinite()) continue
                l = min(l, x); r = max(r, x); t = min(t, y); b = max(b, y)
            }
            if (l > r) return RectF()
            val e = reach(preset, sizeScale)
            return RectF(l - e, t - e, r + e, b + e)
        }
    }
}
