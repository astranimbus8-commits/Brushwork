package com.brushwork.paint.vector.draw

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.engine.LayerConvert
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent
import kotlin.math.max

/**
 * The eraser on a vector layer (v1.5 §4.9, A3): it paints nothing; what it will remove (objects,
 * or parts of strokes and lines, by [VectorEraseMode]) is taken away from the layer's image while
 * the finger moves ([DoomPreview]; v1.8 dimmed it), and lifting the finger removes it as ONE undo
 * step "Erase" (`VectorLayers.update`, which re-renders the changed area). A second finger
 * (cancel) leaves no trace.
 *
 * v1.9 (R11 b, I20; A5): on a legacy shape layer ([convertFirst]) the eraser works on the object
 * the shape becomes ([LayerConvert.shapeToVector]); the layer becomes a vector layer in the same
 * step ([ERASE_LABEL]), and only when the gesture erases something. The lift lands through
 * [EraseLanding] (the partial eraser's region cut in the background), as a tool's erase does.
 */
internal class VectorEraserRecorder(
    private val c: EditorController,
    private val layer: Layer,
    /** The eraser's (sanitized) brush: its size is the eraser's diameter. */
    private val preset: BrushPreset,
    private val stylus: Boolean,
    private val mode: VectorEraseMode,
    /**
     * v1.9 (design 2.4.12): [layer] is a legacy shape layer ([EraseRoute.CONVERT_THEN_OBJECTS]):
     * it becomes a vector layer ([LayerConvert.toVectorAction]) in the erase's own step.
     */
    private val convertFirst: Boolean = false,
) : StrokeRecorder {
    override val replacesStroke: Boolean = true

    /** The eraser removes objects, not pixels: the pixel selection doesn't limit it. */
    override val ignoresSelection: Boolean = true

    private val state = VectorDrawState.of(c)

    /** The eraser's path (x, y, radius per point), to apply it again if the layer changes meanwhile. */
    private var path = FloatArray(96)
    private var pathSize = 0

    /** Every region cut runs in the background (none on this thread by count). */
    private val landing: EraseLanding = EraseLanding(c, layer, mode, convertFirst, ERASE_LABEL, Swept(), syncTargets = 0) { removePreview() }
    private val session = EraseSession(landing.contentNow() ?: VectorContent.EMPTY, mode, state.targets)
    private val preview = DoomPreview(layer, session)
    private var ended = false

    /** The gesture as the erase's footprint: its points again, and the region they swept. */
    private inner class Swept : EraseFootprint {
        override fun feed(s: EraseSession) {
            var k = 0
            while (k < pathSize) { s.add(path[k], path[k + 1], path[k + 2]); k += 3 }
        }

        override fun region(): Path {
            val n = pathSize / 3
            val xs = FloatArray(n) { path[3 * it] }
            val ys = FloatArray(n) { path[3 * it + 1] }
            val rs = FloatArray(n) { path[3 * it + 2] }
            return EraseCut.sweptRegion(xs, ys, rs, n)
        }
    }

    /** The eraser's radius at raw pressure [raw] (size follows a stylus' pressure like the brush). */
    private fun radius(raw: Float): Float {
        val p = StrokeRaster.pressureOf(stylus, raw)
        val f = if (preset.pressureSize) preset.minSizeRatio + (1f - preset.minSizeRatio) * p else 1f
        return max(0.5f, preset.size * f / 2f)
    }

    override fun point(x: Float, y: Float, rawPressure: Float) {
        if (ended) return
        val r = radius(rawPressure)
        if (pathSize + 3 > path.size) path = path.copyOf(path.size * 2)
        path[pathSize++] = x; path[pathSize++] = y; path[pathSize++] = r
        // v1.9 (design 2.4.4): the preview's rebuild is timed as PerfTrace.ERASE_REBUILD.
        val dirty = PerfTrace.span(PerfTrace.ERASE_REBUILD) {
            val overRegion = session.add(x, y, r)
            val changed = session.takeChanged()
            val parts = if (changed.isEmpty()) null else preview.update(changed)
            // v1.9 (R11 c): over a shape or filled path the partial eraser punches its capsule
            // (the region cut itself comes at the lift).
            if (!overRegion) parts else preview.punch(session.capsule).also { if (parts != null) it.union(parts) }
        } ?: return
        preview.install(c)
        if (!dirty.isEmpty) c.invalidateDoc(dirty)
    }

    override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
        if (ended) return false
        if (landing.contentNow() == null || c.doc.indexOf(layer) < 0) { end(); return false }
        // No more points. The preview stays until the new content is on the layer: the
        // update of an earlier gesture may still be on its way, and this one may render (or
        // cut) in the background.
        ended = true
        landing.start(session)
        return true
    }

    override fun cancel() {
        if (ended) return
        end()
    }

    private fun end() {
        ended = true
        removePreview()
    }

    private fun removePreview() {
        preview.remove(c)
    }

    companion object {
        /** The undo step of one eraser gesture on a vector layer. */
        const val ERASE_LABEL = "Erase"

        /** A region cut still running after this long (ms) shows the busy overlay ([EraseLanding.CUT_BUSY_AFTER_MS]). */
        const val CUT_BUSY_AFTER_MS = EraseLanding.CUT_BUSY_AFTER_MS
    }
}

/**
 * Draws the layer without what the eraser will remove (v1.9, design 2.4.4: taken away whole,
 * as the result will be; v1.8 dimmed it): whole objects along their centerlines and fills, cut
 * parts along their part of the centerline, each a little wider than its paint. Drawn inside the
 * compositor's layer (any xfermode is safe there).
 *
 * [update] (each move) only rebuilds the parts of the objects that changed. What they cover is
 * kept in an alpha mask the size of the layer, redrawn where it changed at most once per frame
 * ([drawContent]): a frame costs the layer and one masked rect however many parts there are.
 * Without memory for the mask the parts are drawn on every frame instead.
 *
 * Known difference with the result: an object that stays but lies under a removed part is
 * hidden there too until the finger lifts (the layer's pixels are one image).
 *
 * v1.9 (A5): a tool's erase ([ToolEraseSession]) shows its outline's erase with one preview,
 * whose session it [replace]s whenever the outline changes.
 */
internal class DoomPreview(override val layer: Layer, session: EraseSession) : LayerRenderOverride {
    private class Item(val path: Path, val width: Float, val fill: Boolean, val butt: Boolean, val bounds: RectF)

    /** What the preview takes away (the objects of [update]'s indices). */
    private var session: EraseSession = session

    /** False once its gesture ended (cancelled, or its update applied): it must never be installed again. */
    var alive = true

    /** Installed as the editor's render override by [install] (until [remove]). */
    private var installed = false
    /** What was installed before (it comes back at [remove]). */
    private var previous: LayerRenderOverride? = null

    private val items = HashMap<Int, List<Item>>()
    private val union = RectF()
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt()
    }
    private val butt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF000000.toInt() }
    private val tmp = FloatArray(2)

    /** The parts' coverage (ALPHA_8, document px), allocated at the first frame; null without memory. */
    private var mask: Bitmap? = null
    private var maskCanvas: Canvas? = null
    private var noMask = false

    /** Where [mask] is out of date (document px): redrawn at the next frame. */
    private val stale = RectF()
    private val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
    /** Takes [mask]'s coverage out of the layer (a shader: Skia draws a bare ALPHA_8 bitmap as coverage, see VectorRenderer). */
    private val punch = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) }
    private val erase = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)

    /** Mask rebuilds so far (one per frame at most; tests). */
    var rebuilds = 0
        private set

    /** Everything taken away now (document px). */
    fun bounds(): Rect {
        val r = Rect()
        if (!union.isEmpty) union.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    /** Rebuilds the removed parts of the objects [indices]; returns the area to redraw. */
    fun update(indices: List<Int>): Rect {
        val dirty = RectF()
        for (i in indices) {
            items[i]?.forEach { dirty.union(it.bounds) }
            val built = build(i)
            if (built.isEmpty()) items.remove(i) else items[i] = built
            built.forEach { dirty.union(it.bounds) }
        }
        union.setEmpty()
        for (list in items.values) for (it in list) union.union(it.bounds)
        stale.union(dirty)
        val r = Rect()
        if (!dirty.isEmpty) dirty.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    /** Capsules [punch]ed so far (their items' keys are -1, -2, ...: object indices are not negative). */
    private var punches = 0

    /**
     * v1.9 (R11 c): takes the eraser's capsule [cap] (x0, y0, x1, y1, radius; [EraseSession.capsule])
     * away, as the partial eraser's region cut will at the lift; returns the area to redraw.
     */
    fun punch(cap: FloatArray): Rect {
        val p = Path()
        p.moveTo(cap[0], cap[1])
        p.lineTo(cap[2], cap[3])
        val it = item(p, 2f * cap[4], fill = false, butt = false)
        items[-(++punches)] = listOf(it)
        union.union(it.bounds)
        stale.union(it.bounds)
        val r = Rect()
        it.bounds.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    /**
     * v1.9 (R11 c): takes a tool's footprint away (its [outline] stroked [width] wide, round, plus
     * its fill when [filled]), as the partial eraser's region cut will at the commit; returns the
     * area to redraw.
     */
    fun punchOutline(outline: Path, width: Float, filled: Boolean): Rect {
        val list = ArrayList<Item>(2)
        if (width > 0f) list += item(outline, width, fill = false, butt = false)
        if (filled) list += item(outline, 0f, fill = true, butt = false)
        val r = Rect()
        if (list.isEmpty()) return r
        items[-(++punches)] = list
        val dirty = RectF()
        for (it in list) dirty.union(it.bounds)
        union.union(dirty)
        stale.union(dirty)
        dirty.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    /**
     * v1.9 (A5): a tool's outline changed: what it took away comes back and [s] is what it
     * erases now (its changes are [update]d next); returns the area to redraw.
     */
    fun replace(s: EraseSession): Rect {
        session = s
        val r = Rect()
        if (!union.isEmpty) {
            union.roundOut(r)
            r.inset(-2, -2)
        }
        stale.union(union)
        items.clear()
        union.setEmpty()
        punches = 0
        return r
    }

    /**
     * Installs it as [c]'s render override (again, when something else took its place). What
     * was installed before comes back at [remove]: any override with [restoreAny], else only
     * the preview of an earlier erase still on its way.
     */
    fun install(c: EditorController, restoreAny: Boolean = true) {
        val current = c.renderOverride
        if (current === this) return
        if (!installed) {
            installed = true
            previous = current?.takeIf { restoreAny || (it is DoomPreview && it.alive) }
        }
        c.renderOverride = this
    }

    /** Its erase ended (applied or cancelled): it goes for good, and what it covered is redrawn. */
    fun remove(c: EditorController) {
        alive = false
        release()
        if (!installed) return
        installed = false
        // What was installed before comes back, unless it is the preview of an earlier erase
        // whose update landed meanwhile (with background updates a second gesture can start
        // before the first one's preview goes): that one must not return.
        if (c.renderOverride === this) c.renderOverride = previous?.takeIf { it !is DoomPreview || it.alive }
        previous = null
        val r = bounds()
        if (!r.isEmpty) c.invalidateDoc(r)
    }

    /** The gesture ended: the mask goes (this preview is never drawn again). */
    fun release() {
        noMask = true
        mask = null
        maskCanvas = null
        punch.shader = null
    }

    private fun build(i: Int): List<Item> {
        val tg = session.target(i)
        val out = ArrayList<Item>()
        if (session.isWhole(i)) {
            val w = max(1f, 2f * tg.reach) + 2f * MARGIN
            for (line in tg.lines) out += item(pathOf(line, 0f, line.uMax, line.closed), w, fill = false, butt = false)
            // (v1.8: an object with live copies takes each copy's fill on its own.)
            for (part in tg.parts ?: listOf(tg)) {
                if (part.fills.isEmpty()) continue
                val p = Path()
                p.fillType = if (part.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                for (f in part.fills) p.addPath(pathOf(f, 0f, f.uMax, true))
                out += item(p, 0f, fill = true, butt = false)
            }
            return out
        }
        val set = session.removedOf(i) ?: return out
        val line = tg.lines[0]
        val w = max(1f, 2f * tg.cutReach) + 2f * MARGIN
        for (k in 0 until set.size) {
            val a = set.start(k).coerceIn(0f, line.uMax)
            val b = set.end(k).coerceIn(0f, line.uMax)
            if (b <= a && line.n > 1) continue
            out += item(pathOf(line, a, b, false), w, fill = false, butt = line.n > 1)
        }
        return out
    }

    private fun item(p: Path, width: Float, fill: Boolean, butt: Boolean): Item {
        val b = RectF()
        p.computeBounds(b, true)
        b.inset(-width / 2f - 1f, -width / 2f - 1f)
        return Item(p, width, fill, butt, b)
    }

    /** [line] from parameter [a] to [b] as a path (the whole line, closed, when [closed]). */
    private fun pathOf(line: FlatLine, a: Float, b: Float, closed: Boolean): Path {
        val p = Path()
        if (line.n == 0) return p
        line.pointAt(a, tmp)
        p.moveTo(tmp[0], tmp[1])
        var i = kotlin.math.floor(a).toInt() + 1
        while (i < b && i < line.n) {
            p.lineTo(line.xs[i], line.ys[i])
            i++
        }
        line.pointAt(b, tmp)
        p.lineTo(tmp[0], tmp[1])
        if (closed && line.n > 2) p.close()
        return p
    }

    /** Draws every part that reaches [clip] (null: all) with [mode] (null: plain coverage). */
    private fun drawItems(canvas: Canvas, clip: RectF?, mode: PorterDuffXfermode?) {
        fillPaint.xfermode = mode; round.xfermode = mode; butt.xfermode = mode
        for (list in items.values) for (it in list) {
            if (clip != null && !RectF.intersects(it.bounds, clip)) continue
            if (it.fill) {
                canvas.drawPath(it.path, fillPaint)
            } else {
                val paint = if (it.butt) butt else round
                paint.strokeWidth = it.width
                canvas.drawPath(it.path, paint)
            }
        }
    }

    /** [mask], brought up to date (once per frame: what changed since the last one); null without memory. */
    private fun maskNow(): Bitmap? {
        if (noMask) return null
        val m = mask ?: try {
            Bitmap.createBitmap(layer.bitmap.width, layer.bitmap.height, Bitmap.Config.ALPHA_8).also {
                mask = it
                maskCanvas = Canvas(it)
                stale.set(union)
            }
        } catch (e: OutOfMemoryError) {
            noMask = true
            return null
        }
        if (!stale.isEmpty) {
            val r = Rect()
            stale.roundOut(r)
            r.inset(-1, -1)
            stale.setEmpty()
            if (r.intersect(0, 0, m.width, m.height)) {
                val mc = maskCanvas!!
                val save = mc.save()
                mc.clipRect(r)
                mc.drawPaint(clear)
                drawItems(mc, RectF(r), null)
                mc.restoreToCount(save)
                rebuilds++
            }
        }
        return m
    }

    override fun drawContent(canvas: Canvas): Boolean {
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        if (items.isEmpty() || union.isEmpty) return true
        val m = maskNow()
        if (m == null) {
            drawItems(canvas, null, erase)
            return true
        }
        if (punch.shader == null) punch.shader = BitmapShader(m, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP)
        canvas.drawRect(union, punch)
        return true
    }

    private companion object {
        /**
         * How much wider than its paint a removed part is taken away, each side (document px): the
         * paint's anti-aliased edge (half a pixel), the centerlines' flattening (a quarter) and the
         * part's own edge (half a pixel) must all fall inside, or a faint rim stays.
         */
        const val MARGIN = 2f
    }
}
