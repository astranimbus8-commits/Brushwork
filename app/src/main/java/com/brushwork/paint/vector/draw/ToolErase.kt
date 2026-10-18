package com.brushwork.paint.vector.draw

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerConvert
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.vector.VectorCommit
import com.brushwork.paint.tools.vector.VectorPaintSpec
import com.brushwork.paint.tools.vector.VectorPreview
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.pathfinder.PathConvert
import kotlin.math.max

/** v1.9 (R11, design 2.4.12; owner A5): what a tool's erase ended in. */
sealed interface ToolEraseResult {
    /** Nothing was erased; no step. */
    data object Nothing : ToolEraseResult

    /** One undo step [label] was recorded. */
    data class Done(val label: String) : ToolEraseResult

    /** The erase was refused; [message] says why. */
    data class Refused(val message: String) : ToolEraseResult
}

/**
 * v1.9 (R11 a, design 2.4.12; A5): one erase with a tool's own outline (Curve, Polyline, Path,
 * Shape; [ToolErase.begin]). Main thread.
 *
 * Its footprint is what the tool would paint: the outline stroked at its width (round caps and
 * joins), plus its fill for a filled closed outline ([update]). The erase shows live in the layer
 * through the editor's render override, as the result will look:
 * - on a raster layer, a text layer or a layer mask ([EraseRoute.RASTER],
 *   [EraseRoute.RASTERIZE_DATA]) the footprint takes the layer's pixels away
 *   ([VectorPaintSpec.erase], DST_OUT; into a mask it paints black, as the Eraser does there),
 *   inside the selection; [commit] bakes it ([VectorCommit]), and on a text layer the same step
 *   turns the text into pixels with v1.8's toast;
 * - on a vector layer or a legacy shape layer ([EraseRoute.OBJECTS],
 *   [EraseRoute.CONVERT_THEN_OBJECTS]) it erases objects in the Eraser's mode
 *   ([VectorEraserModes]; the selection doesn't limit it): every object it touches, or the parts
 *   of strokes and lines under it, or the part of a line up to its crossings;
 *   [VectorEraseMode.PARTIAL] also cuts the shapes and filled paths it touches by the
 *   footprint's region ([EraseCut]). The shape layer becomes a vector layer in the erase's step
 *   ([EraseLanding]).
 *
 * The session owns the editor's render override while it is open: the tool draws its outline in
 * its own overlay only, never a paint preview into the layer. ONE undo step with [commit]'s
 * label, or none ([cancel], or nothing under the outline).
 */
class ToolEraseSession internal constructor(
    private val c: EditorController,
    private val layer: Layer,
    private val source: EraseSource,
    private var route: EraseRoute,
) {
    /** The erase removes objects (else pixels). */
    private val objects: Boolean get() = removesObjects(route)
    private val state = VectorDrawState.of(c)
    private var footprint: OutlineFootprint? = null
    private var ended = false

    /** Pixels: the preview of [specs], installed while the session is open. */
    private var pixels: VectorPreview? = null
    /** Pixels: the document rects the preview covers (redrawn when it changes). */
    private var shown: List<Rect> = emptyList()

    /** Objects: what the current outline erases (over what the layer held then). */
    private var session: EraseSession? = null
    private var doom: DoomPreview? = null
    /** A legacy shape layer: its shape as an object, decoded once per shape. */
    private var shapeData: String? = null
    private var shapeContent: VectorContent? = null

    private val maskTarget: Boolean get() = c.editTargetOf(layer) == EditTarget.MASK

    /** What the erase works on now (as [EraseLanding.contentNow]); null when it is no longer there. */
    private fun contentNow(): VectorContent? {
        layer.vector?.let { return it }
        if (route != EraseRoute.CONVERT_THEN_OBJECTS) return null
        val data = layer.shapeData ?: return null
        if (data != shapeData) {
            shapeData = data
            shapeContent = LayerConvert.shapeToVector(layer)
        }
        return shapeContent
    }

    /**
     * The tool's current outline as an eraser: a centre line stroked [strokeWidth] wide
     * (document px; 0: no stroke), plus the region it closes when [filledRegion]. The layer shows
     * the erase at once; call it again whenever the outline, its width or the Eraser's mode
     * changes.
     */
    fun update(outline: Path, strokeWidth: Float, filledRegion: Boolean) {
        if (ended) return
        val f = OutlineFootprint(outline, strokeWidth, filledRegion)
        footprint = f
        // (A layer that takes no erase now shows as it is: the commit then erases nothing.)
        if (!reroute()) { dropPreview(); return }
        if (objects) showObjects(f) else showPixels(f)
    }

    /**
     * Follows the layer when the kind of erase it takes changed while the session is open (turned
     * into pixels by [com.brushwork.paint.ui.common.EraseLabels19.TURN_INTO_PIXELS], a mask chosen
     * as the target): the preview starts over in the new kind.
     * False when it takes no erase now ([EraseRoute.REFUSE]).
     */
    private fun reroute(): Boolean {
        val now = EraseRouting.route(layer, source, maskTarget, isPath = false)
        if (now == EraseRoute.REFUSE) return false
        // (A shape layer that became a vector layer in another step keeps its objects' erase.)
        if (now == route || removesObjects(now) && objects) return true
        dropPreview()
        route = now
        return true
    }

    private fun showPixels(f: OutlineFootprint) {
        val specs = listOfNotNull(f.paintSpec(erase = !maskTarget))
        val p = pixels ?: VectorPreview(c, layer).also { pixels = it }
        p.specs = specs
        if (c.renderOverride !== p) c.renderOverride = p
        val regions = specs.flatMap { it.regions }
        invalidate(shown)
        invalidate(regions)
        shown = regions
    }

    private fun showObjects(f: OutlineFootprint) {
        val content = contentNow() ?: return
        val s = EraseSession(content, VectorEraserModes.mode(c), state.targets)
        f.feed(s)
        session = s
        val d = doom ?: DoomPreview(layer, s).also { doom = it }
        val dirty = d.replace(s)
        val changed = s.takeChanged()
        if (changed.isNotEmpty()) dirty.union(d.update(changed))
        // The partial eraser cuts the shapes and fills it touches by its region: that region goes.
        if (s.cutTargets().isNotEmpty()) dirty.union(d.punchOutline(f.path, f.width, f.filled))
        // (Only an earlier erase's preview still on its way comes back after it, never a tool's.)
        d.install(c, restoreAny = false)
        if (!dirty.isEmpty) c.invalidateDoc(dirty)
    }

    /** Draws the layer as it looks now (true removal), in document coordinates. */
    fun drawPreview(canvas: Canvas) {
        val drawn = if (objects) doom?.takeIf { it.alive }?.drawContent(canvas) else pixels?.drawContent(canvas)
        if (drawn != true) canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
    }

    /**
     * Ends the session with ONE undo step [label] (EraseLabels19.STEP_*) when the outline erased
     * something:
     * - [ToolEraseResult.Done]: the step is recorded. Only a partial cut of more than
     *   [SYNC_TARGETS] shapes and fills lands later: it is cut in the background (the busy
     *   overlay shows after [EraseLanding.CUT_BUSY_AFTER_MS], the preview stays meanwhile), and
     *   anything that records a step, undo and redo land it first;
     * - [ToolEraseResult.Nothing]: nothing was under the outline (or the layer is gone, or no
     *   longer takes an erase); no step. A layer that takes another kind of erase now (turned
     *   into pixels meanwhile) gets that one;
     * - [ToolEraseResult.Refused]: the layer can't be edited now (locked, hidden, alpha-locked
     *   pixels); its message is shown; no step.
     */
    fun commit(label: String): ToolEraseResult {
        if (ended) return ToolEraseResult.Nothing
        if (c.doc.indexOf(layer) < 0 || !reroute()) { end(); return ToolEraseResult.Nothing }
        if (!c.checkEditable(layer)) { end(); return ToolEraseResult.Refused(c.message.orEmpty()) }
        if (!objects && !maskTarget && layer.alphaLocked) {
            end()
            val m = alphaLockedMessage(layer)
            c.toast(m)
            return ToolEraseResult.Refused(m)
        }
        return if (objects) commitObjects(label) else commitPixels(label)
    }

    private fun commitPixels(label: String): ToolEraseResult {
        val spec = footprint?.paintSpec(erase = !maskTarget)
        end()
        if (spec == null) return ToolEraseResult.Nothing
        return if (VectorCommit.commit(c, layer, listOf(spec), label)) ToolEraseResult.Done(label) else ToolEraseResult.Nothing
    }

    private fun commitObjects(label: String): ToolEraseResult {
        val f = footprint
        val content = contentNow()
        if (f == null || f.isEmpty || content == null) { end(); return ToolEraseResult.Nothing }
        val mode = VectorEraserModes.mode(c)
        // (The mode changed since the last update: the erase is the one of the mode chosen now.
        // A layer that changed is the landing's: it applies the footprint again.)
        val s = session?.takeIf { it.mode == mode } ?: EraseSession(content, mode, state.targets).also { f.feed(it) }
        if (!s.hasEffect) { end(); return ToolEraseResult.Nothing }
        ended = true
        var applied: Boolean? = null
        val landing = EraseLanding(c, layer, mode, route == EraseRoute.CONVERT_THEN_OBJECTS, label, f, SYNC_TARGETS) { ok ->
            applied = ok
            removePreview()
        }
        if (s.cutTargets().size > SYNC_TARGETS) {
            landing.start(s)
            return ToolEraseResult.Done(label)
        }
        // Inside a scope: the steps on their way land first, the layer's render is synchronous
        // and the step is recorded before this returns.
        c.editScope { landing.start(s) }
        return when (applied) {
            false -> ToolEraseResult.Nothing
            // (null: it waits behind an update still on its way, and lands with it.)
            else -> ToolEraseResult.Done(label)
        }
    }

    /** Ends the session without a step; the layer looks as before. */
    fun cancel() {
        if (ended) return
        end()
    }

    private fun end() {
        ended = true
        removePreview()
    }

    /** The preview goes for good (a [DoomPreview] is never installed again): the next [update] starts over. */
    private fun dropPreview() {
        removePreview()
        doom = null
        session = null
    }

    private fun removePreview() {
        doom?.remove(c)
        val p = pixels ?: return
        pixels = null
        if (c.renderOverride === p) c.renderOverride = null
        invalidate(shown)
        shown = emptyList()
    }

    private fun invalidate(rects: List<Rect>) {
        for (r in rects) c.invalidateDoc(r)
    }

    companion object {
        /**
         * A partial erase that cuts at most this many shapes and fills cuts them on the main
         * thread at [commit] (design 6: 150 ms for 50); more are cut in the background.
         */
        const val SYNC_TARGETS = 50

        private fun removesObjects(route: EraseRoute) = route == EraseRoute.OBJECTS || route == EraseRoute.CONVERT_THEN_OBJECTS

        /**
         * The Eraser's refusal on an alpha-locked layer's pixels, one text for the Eraser brush
         * (`BrushTool`) and the tools' erase (lead, at A5's merge).
         */
        internal fun alphaLockedMessage(layer: Layer): String =
            "Transparency is locked on \"${layer.name}\": the eraser can't remove pixels"
    }
}

/** v1.9 (R11, design 2.4.12; owner A5): starts a tool's erase. */
object ToolErase {
    /**
     * Starts an erase with [source]'s outline on [layer] (the active layer, where the tools paint:
     * its content or its mask), routed by [EraseRouting] (I20: never pixels of a vector or shape
     * layer). Null when [layer] can't be erased, after saying why (the tools' check: a folder, an
     * adjustment layer without a mask, a locked or hidden layer; the Eraser's: an alpha-locked
     * layer's pixels; a shape that doesn't decode): the tool shows its outline only.
     */
    fun begin(c: EditorController, layer: Layer, source: EraseSource): ToolEraseSession? {
        if (c.doc.indexOf(layer) < 0) return null
        val mask = c.editTargetOf(layer) == EditTarget.MASK
        val route = EraseRouting.route(layer, source, mask, isPath = false)
        if (!c.checkEditable(layer) || route == EraseRoute.REFUSE) return null
        when (route) {
            EraseRoute.RASTER, EraseRoute.RASTERIZE_DATA -> if (!mask && layer.alphaLocked) {
                c.toast(ToolEraseSession.alphaLockedMessage(layer))
                return null
            }
            EraseRoute.CONVERT_THEN_OBJECTS -> if (LayerConvert.shapeToVector(layer) == null) {
                c.toast(VectorStrokeCapture.SHAPE_UNREADABLE_MESSAGE)
                return null
            }
            else -> Unit
        }
        return ToolEraseSession(c, layer, source, route)
    }
}

/**
 * v1.9 (R11 a, b; A5): a tool's outline as an eraser: [outline] stroked [width] wide (round caps
 * and joins; nothing at 0), plus the region it closes when [filled].
 *
 * Over objects ([feed]) it is runs of the eraser's capsules: one per contour (radius half the
 * width, at least half a pixel), and with [filled] rows across the fill, twice [radius] apart:
 * every point of the fill lies within [radius] of a row or of the outline. The partial eraser's region cut takes [region] away: the stroke's region united with
 * the fill.
 */
internal class OutlineFootprint(outline: Path, val width: Float, val filled: Boolean) : EraseFootprint {
    /** The outline (a copy: the tool's path may change). */
    val path = Path(outline)

    private val stroked = width.isFinite() && width > 0f

    /** The capsules' radius (document px). */
    val radius = max(0.5f, if (stroked) width / 2f else 0f)

    /** The outline's contours, flattened: x, y pairs (a closed one ends where it starts). */
    private val polys: List<FloatArray> = flatten(path)

    /** Nothing to erase with: no contour, or neither a stroke nor a fill. */
    val isEmpty: Boolean = polys.isEmpty() || (!stroked && !filled)

    override fun feed(s: EraseSession) {
        if (isEmpty) return
        for (p in polys) {
            s.lift()
            var k = 0
            while (k < p.size) { s.add(p[k], p[k + 1], radius); k += 2 }
        }
        if (filled) hatch(s)
        s.lift()
    }

    override fun region(): Path {
        val stroke = if (stroked) EraseCut.strokeRegion(path, width / 2f) else null
        if (!filled) return stroke ?: Path()
        val fill = Path(path)
        if (stroke == null) return fill
        return PathConvert.op(stroke, fill, Path.Op.UNION) ?: stroke.apply { addPath(fill) }
    }

    /** The footprint as paint: black, taking pixels away with [erase] (else painted, into a mask). */
    fun paintSpec(erase: Boolean): VectorPaintSpec? {
        if (isEmpty) return null
        val vp = PathConvert.toVectorPath(path)
        val black = 0xFF000000.toInt()
        val spec = VectorPaintSpec.build(
            fill = if (filled) vp else null,
            fillColor = black,
            stroke = if (stroked) vp else null,
            strokeColor = black,
            strokeWidth = if (stroked) width else 0f,
            erase = erase,
        ) ?: return null
        // (A vector path has no fill rule: the outline's own.)
        spec.fill?.fillType = path.fillType
        return spec
    }

    /** Rows across the fill, every 2 x [radius] (each its own run of the eraser). */
    private fun hatch(s: EraseSession) {
        val b = RectF()
        path.computeBounds(b, true)
        if (!(b.width() >= 0f) || !(b.height() > 0f)) return
        val evenOdd = path.fillType == Path.FillType.EVEN_ODD || path.fillType == Path.FillType.INVERSE_EVEN_ODD
        val step = 2f * radius
        var y = b.top + radius
        while (y < b.bottom) {
            val row = y
            spans(row, evenOdd) { x0, x1 ->
                s.lift()
                s.add(x0, row, radius)
                s.add(x1, row, radius)
            }
            y += step
        }
    }

    private var xs = FloatArray(16)
    private var dirs = IntArray(16)

    /** Calls [out] with each run (x0 to x1) of row [y] inside the fill (the contours closed). */
    private inline fun spans(y: Float, evenOdd: Boolean, out: (Float, Float) -> Unit) {
        var m = 0
        for (p in polys) {
            val n = p.size / 2
            if (n < 2) continue
            for (k in 0 until n) {
                val k1 = if (k + 1 < n) k + 1 else 0
                val x0 = p[2 * k]; val y0 = p[2 * k + 1]
                val x1 = p[2 * k1]; val y1 = p[2 * k1 + 1]
                if ((y0 <= y) == (y1 <= y)) continue
                if (m == xs.size) { xs = xs.copyOf(2 * m); dirs = dirs.copyOf(2 * m) }
                xs[m] = x0 + (y - y0) * (x1 - x0) / (y1 - y0)
                dirs[m] = if (y1 > y0) 1 else -1
                m++
            }
        }
        // (A row crosses few edges: insertion sort by x.)
        for (i in 1 until m) {
            val x = xs[i]; val d = dirs[i]
            var j = i - 1
            while (j >= 0 && xs[j] > x) { xs[j + 1] = xs[j]; dirs[j + 1] = dirs[j]; j-- }
            xs[j + 1] = x; dirs[j + 1] = d
        }
        var wind = 0
        var start = 0f
        for (i in 0 until m) {
            val was = if (evenOdd) wind and 1 != 0 else wind != 0
            wind += if (evenOdd) 1 else dirs[i]
            val inside = if (evenOdd) wind and 1 != 0 else wind != 0
            if (!was && inside) start = xs[i] else if (was && !inside) out(start, xs[i])
        }
    }

    private companion object {
        /** [path]'s contours as polylines (curves within [PathConvert]'s flattening tolerance). */
        fun flatten(path: Path): List<FloatArray> {
            val out = ArrayList<FloatArray>()
            for (contour in PathConvert.contours(path)) {
                val segs = contour.segs
                if (segs.isEmpty()) continue
                var pts = FloatArray(2 * (segs.size + 1))
                var n = 0
                fun add(x: Float, y: Float) {
                    if (n + 2 > pts.size) pts = pts.copyOf(2 * pts.size)
                    pts[n++] = x; pts[n++] = y
                }
                add(segs[0].p0.x, segs[0].p0.y)
                for (sg in segs) {
                    val steps = if (sg.isCubic) PathConvert.cubicSteps(sg) else 1
                    if (steps <= 1) {
                        add(sg.p1.x, sg.p1.y)
                    } else {
                        for (k in 1..steps) {
                            val q = sg.at(k.toFloat() / steps)
                            add(q.x, q.y)
                        }
                    }
                }
                out += pts.copyOf(n)
            }
            return out
        }
    }
}
