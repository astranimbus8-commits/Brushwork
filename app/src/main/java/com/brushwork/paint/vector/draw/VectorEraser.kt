package com.brushwork.paint.vector.draw

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.StrokeRaster
import com.brushwork.paint.brush.StrokeRecorder
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.vector.VectorContent
import kotlin.math.max

/**
 * The eraser on a vector layer (v1.5 §4.9, A3): it paints nothing; what it will remove (objects,
 * or parts of strokes and lines, by [VectorEraseMode]) is shown dimmed while the finger moves,
 * and lifting the finger removes it as ONE undo step "Erase" (`VectorLayers.update`, which
 * re-renders the changed area). A second finger (cancel) leaves no trace.
 */
internal class VectorEraserRecorder(
    private val c: EditorController,
    private val layer: Layer,
    /** The eraser's (sanitized) brush: its size is the eraser's diameter. */
    private val preset: BrushPreset,
    private val stylus: Boolean,
    private val mode: VectorEraseMode,
) : StrokeRecorder {
    override val replacesStroke: Boolean = true

    /** The eraser removes objects, not pixels: the pixel selection doesn't limit it. */
    override val ignoresSelection: Boolean = true

    private val state = VectorDrawState.of(c)
    private val session = EraseSession(layer.vector ?: VectorContent.EMPTY, mode, state.targets)
    private val preview = DoomPreview(layer, session)
    private var previous: LayerRenderOverride? = null
    private var installed = false
    private var ended = false

    /** The eraser's path (x, y, radius per point), to apply it again if the layer changes meanwhile. */
    private var path = FloatArray(96)
    private var pathSize = 0

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
        session.add(x, y, r)
        val changed = session.takeChanged()
        if (changed.isEmpty()) return
        val dirty = preview.update(changed)
        if (!installed) {
            installed = true
            previous = c.renderOverride?.takeIf { it !== preview }
            c.renderOverride = preview
        }
        if (!dirty.isEmpty) c.invalidateDoc(dirty)
    }

    override fun commit(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
        if (ended) return false
        if (layer.vector == null || c.doc.indexOf(layer) < 0) { end(); return false }
        // No more points. The dimmed preview stays until the new content is on the layer: the
        // update of an earlier gesture may still be on its way, and this one may render in the
        // background.
        ended = true
        state.serial(layer) { finish -> apply(finish) }
        return true
    }

    /**
     * Removes what the gesture erased from what the layer holds NOW (ONE undo step "Erase");
     * [finish] is called once it was applied (or nothing was to be done).
     */
    private fun apply(finish: () -> Unit) {
        val current = layer.vector
        if (current == null || c.doc.indexOf(layer) < 0) { removePreview(); finish(); return }
        // The layer changed since the gesture began (an update landed meanwhile): the same
        // eraser path is applied to what the layer holds now.
        val final = if (current === session.content) session else EraseSession(current, mode, state.targets).also { s ->
            var k = 0
            while (k < pathSize) { s.add(path[k], path[k + 1], path[k + 2]); k += 3 }
        }
        val after = final.result()
        if (after == null) { removePreview(); finish(); return }
        if (final.removedWholeInPartial && !state.partialWholeHintShown) {
            state.partialWholeHintShown = true
            c.toast("Closed shapes and fills are erased whole (\"Partial\" cuts strokes and lines)")
        }
        try {
            state.update(c, layer, after, ERASE_LABEL) { removePreview(); finish() }
        } catch (e: Throwable) {
            removePreview()
            throw e
        }
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
        preview.alive = false
        if (installed) {
            installed = false
            // What was installed before this gesture comes back, unless it is the preview of an
            // earlier gesture whose update landed meanwhile (with background updates a second
            // gesture can start before the first one's preview goes): that one must not return.
            if (c.renderOverride === preview) c.renderOverride = previous?.takeIf { it !is DoomPreview || it.alive }
            previous = null
            val r = preview.bounds()
            if (!r.isEmpty) c.invalidateDoc(r)
        }
    }

    companion object {
        /** The undo step of one eraser gesture on a vector layer. */
        const val ERASE_LABEL = "Erase"
    }
}

/**
 * Draws the layer with what the eraser will remove dimmed (punched out at [DIM_ALPHA]): whole
 * objects along their centerlines and fills, cut parts along their part of the centerline.
 * Drawn inside the compositor's layer (any xfermode is safe there).
 */
internal class DoomPreview(override val layer: Layer, private val session: EraseSession) : LayerRenderOverride {
    private class Item(val path: Path, val width: Float, val fill: Boolean, val butt: Boolean, val bounds: RectF)

    /** False once its gesture ended (cancelled, or its update applied): it must never be installed again. */
    var alive = true

    private val items = HashMap<Int, List<Item>>()
    private val union = RectF()
    private val round = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt()
    }
    private val butt = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE; strokeCap = Paint.Cap.BUTT; strokeJoin = Paint.Join.ROUND; color = 0xFF000000.toInt()
    }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = 0xFF000000.toInt() }
    private val dim = Paint().apply {
        alpha = DIM_ALPHA
        xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
    }
    private val tmp = FloatArray(2)

    /** Everything drawn dimmed now (document px). */
    fun bounds(): Rect {
        val r = Rect()
        if (!union.isEmpty) union.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    /** Rebuilds the dimmed parts of the objects [indices]; returns the area to redraw. */
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
        val r = Rect()
        if (!dirty.isEmpty) dirty.roundOut(r)
        r.inset(-2, -2)
        return r
    }

    private fun build(i: Int): List<Item> {
        val tg = session.target(i)
        val out = ArrayList<Item>()
        if (session.isWhole(i)) {
            val w = max(1f, 2f * tg.reach)
            for (line in tg.lines) out += item(pathOf(line, 0f, line.uMax, line.closed), w, fill = false, butt = false)
            if (tg.fills.isNotEmpty()) {
                val p = Path()
                p.fillType = if (tg.evenOdd) Path.FillType.EVEN_ODD else Path.FillType.WINDING
                for (f in tg.fills) p.addPath(pathOf(f, 0f, f.uMax, true))
                out += item(p, 0f, fill = true, butt = false)
            }
            return out
        }
        val set = session.removedOf(i) ?: return out
        val line = tg.lines[0]
        val w = max(1f, 2f * tg.cutReach)
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

    override fun drawContent(canvas: Canvas): Boolean {
        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
        if (items.isEmpty() || union.isEmpty) return true
        val save = canvas.saveLayer(union, dim)
        for (list in items.values) for (it in list) {
            if (it.fill) {
                canvas.drawPath(it.path, fillPaint)
            } else {
                val paint = if (it.butt) butt else round
                paint.strokeWidth = it.width
                canvas.drawPath(it.path, paint)
            }
        }
        canvas.restoreToCount(save)
        return true
    }

    private companion object {
        /** How much of a doomed part's paint the preview takes away (0..255). */
        const val DIM_ALPHA = 165
    }
}
