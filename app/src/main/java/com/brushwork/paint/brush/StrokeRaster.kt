package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.core.PackedPoints
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
 * F2 reference: the coverage buffer covers [render]'s clip (∩ the stroke) with its origin on the
 * paper-grain grid ([PaperGrain.SIZE]), so grain stays anchored to the document. Only PAINT
 * coverage strokes are replayed (smudge / blur / watercolor tips are drawn as coverage too; vector
 * layers never record them).
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
        if (!RectF.intersects(reach, RectF(clip))) return Rect()

        val dynamics = StrokeDynamics(p, stylus, seed)
        sample(dynamics, p, stylus, points)

        // The dabs as the live stroke leaves them, and the part of the clip they reach.
        val total = lastLength
        val region = Rect()
        for (dab in dabs) {
            dynamics.resolve(dab, total)
            stamper.measure(p, dab)
            if (!dab.hasBounds) continue
            if (!Rect.intersects(clip, Rect(dab.left, dab.top, dab.right, dab.bottom))) continue
            region.union(dab.left, dab.top, dab.right, dab.bottom)
        }
        if (region.isEmpty || !region.intersect(clip)) { dabs.clear(); return Rect() }

        // Coverage buffer whose origin lies on the 256 px grain grid (document-anchored grain).
        val left = Math.floorDiv(region.left, PaperGrain.SIZE) * PaperGrain.SIZE
        val top = Math.floorDiv(region.top, PaperGrain.SIZE) * PaperGrain.SIZE
        val cov = coverage(region.right - left, region.bottom - top)
        val cc = bufferCanvas!!
        cc.save()
        cc.clipRect(0, 0, region.right - left, region.bottom - top)
        cc.drawColor(0, PorterDuff.Mode.CLEAR)
        cc.translate(-left.toFloat(), -top.toFloat())
        cc.clipRect(region)
        val tmp = Rect()
        for (dab in dabs) {
            if (!dab.hasBounds) continue
            tmp.set(dab.left, dab.top, dab.right, dab.bottom)
            if (Rect.intersects(region, tmp)) stamper.stamp(cc, p, dab)
        }
        cc.restore()
        dabs.clear()

        canvas.save()
        canvas.translate(left.toFloat(), top.toFloat())
        val local = Rect(region).apply { offset(-left, -top) }
        painter.draw(canvas, cov, local, style, null)
        canvas.restore()
        return region
    }

    /** Everything the stroke can paint (document px). */
    fun bounds(preset: BrushPreset, sizeScale: Float, points: PackedPoints): RectF = strokeBounds(preset, sizeScale, points)

    /** Length of the last sampled stroke. */
    private var lastLength = 0f

    /** Feeds [points] to a sampler exactly as BrushTool's stroke does, collecting the dabs. */
    private fun sample(dynamics: StrokeDynamics, preset: BrushPreset, stylus: Boolean, points: PackedPoints) {
        dabs.clear()
        val xs = points.x; val ys = points.y; val ps = points.p
        var spacingScale = 1f
        val sampler = StrokeSampler(
            spacingAt = { pr, d -> dynamics.spacing(pr, d) * spacingScale },
            onSample = { x, y, pr, d -> dabs += dynamics.newDab(x, y, pr, d) },
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
        lastLength = sampler.length
    }

    private fun coverage(w: Int, h: Int): Bitmap {
        val b = buffer
        if (b != null && !b.isRecycled && b.width >= w && b.height >= h) return b
        b?.recycle()
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
        /** The live stroke's `pressureOf`: stylus pressure clamped, 1 for fingers (and NaN). */
        fun pressureOf(stylus: Boolean, raw: Float): Float = if (stylus && !raw.isNaN()) raw.coerceIn(0f, 1f) else 1f

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
