package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.hypot
import kotlin.math.max

/**
 * Painting tool for BRUSH, ERASER, SMUDGE and BLUR (preset = controller.presetFor(id)).
 *
 * Paint and erase strokes stamp dabs into a document-sized ALPHA_8 coverage buffer that is
 * shown live through `controller.renderOverride` and composited into the layer (or its mask)
 * on release, so the stroke opacity caps overlapping dabs. Smudge, blur and watercolor dabs
 * edit the pixels directly, recording undo tiles as they go.
 */
class BrushTool(controller: EditorController, override val id: ToolId) : Tool(controller) {
    override val usesStrokeAssist: Boolean = true

    private val store = BrushPresetStore.get(controller.appContext)
    private val res = StrokeResources.of(controller)
    private var stroke: Stroke? = null
    private var strokeCounter = 0L

    init {
        // Tools are created lazily; bring back the preset (and edits) the user had last time.
        controller.updatePreset(id, store.current(id))
    }

    /** The preset this tool paints with. */
    val preset: BrushPreset get() = controller.presetFor(id) ?: BrushLibrary.defaultFor(id)

    /** True while a stroke is in progress. */
    val isStroking: Boolean get() = stroke != null

    override fun onDown(p: ToolPoint) {
        stroke?.let { stroke = null; it.cancel() }
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return
        val preset = preset.sanitized()
        val kind = StrokeKind.of(id, preset)
        val maskTarget = controller.editTargetOf(layer) == EditTarget.MASK
        if (kind == StrokeKind.ERASE && layer.alphaLocked && !maskTarget) {
            controller.toast("Transparency is locked on \"${layer.name}\": the eraser can't remove pixels")
            return
        }
        val seed = System.nanoTime() xor (++strokeCounter * -0x61c8864680b583ebL)
        val s = try {
            if (kind.isDirect) DirectStroke(layer, preset, kind, p, seed, maskTarget)
            else BufferStroke(layer, preset, kind, p, seed, maskTarget)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for this brush")
            return
        }
        stroke = s
        s.begin(p)
    }

    override fun onMove(p: ToolPoint) {
        stroke?.move(p)
    }

    override fun onUp(p: ToolPoint) {
        val s = stroke ?: return
        stroke = null
        s.finish(p)
        store.persist(controller, id)
    }

    override fun onCancel() {
        val s = stroke ?: return
        stroke = null
        s.cancel()
    }

    override fun onDeactivate() {
        // Only reachable mid-stroke if the editor closes or switches tools under the finger.
        val s = stroke ?: return
        stroke = null
        s.finish(null)
        store.persist(controller, id)
    }

    // ------------------------------------------------------------------ strokes

    private fun undoLabel(kind: StrokeKind) = when (kind) {
        StrokeKind.PAINT -> "Brush"
        StrokeKind.ERASE -> "Eraser"
        StrokeKind.SMUDGE -> "Smudge"
        StrokeKind.BLUR -> "Blur"
        StrokeKind.WATERCOLOR -> "Watercolor"
    }

    /** Color the stroke paints with on [layer] (luminance when painting a mask). */
    private fun strokeColor(maskTarget: Boolean): Int {
        val c = controller.color or 0xFF000000.toInt()
        return if (maskTarget) ColorUtils.gray(ColorUtils.luminance(c)) else ColorModeOps.displayColor(c, controller.doc.colorMode)
    }

    private abstract inner class Stroke(
        val layer: Layer,
        val preset: BrushPreset,
        val kind: StrokeKind,
        first: ToolPoint,
        seed: Long,
    ) {
        val isStylus = first.isStylus
        val dynamics = StrokeDynamics(preset, isStylus, seed)
        val selection: Selection? = controller.selection
        val dirty = Rect()
        private var spacingScale = 1f
        private var lastX = first.x
        private var lastY = first.y
        val sampler = StrokeSampler(
            spacingAt = { pr, d -> dynamics.spacing(pr, d) * spacingScale },
            onSample = { x, y, pr, d -> onDab(dynamics.newDab(x, y, pr, d)) },
        )

        /** Pixel operations allowed per input event before dab spacing is stretched. */
        abstract val budget: Float

        /** Approximate pixel operations of one dab of diameter [d]. */
        open fun dabCost(d: Float): Float = d * d

        fun pressureOf(p: ToolPoint): Float =
            if (isStylus && !p.pressure.isNaN()) p.pressure.coerceIn(0f, 1f) else 1f

        fun begin(p: ToolPoint) {
            sampler.begin(p.x, p.y, pressureOf(p))
            afterEvent()
        }

        fun move(p: ToolPoint) {
            limitCost(p)
            sampler.add(p.x, p.y, pressureOf(p))
            afterEvent()
        }

        /** Ends the stroke (adding [p] as the last point) and commits it. */
        fun finish(p: ToolPoint?) {
            if (p != null) {
                limitCost(p)
                sampler.add(p.x, p.y, pressureOf(p))
            }
            sampler.end()
            complete()
        }

        /** Very large brushes on fast strokes would stall a frame: space their dabs further apart. */
        private fun limitCost(p: ToolPoint) {
            val len = hypot(p.x - lastX, p.y - lastY)
            lastX = p.x
            lastY = p.y
            val d = max(1f, dynamics.size)
            val nominal = max(StrokeDynamics.MIN_SPACING_PX, preset.spacing * d)
            val cost = len / nominal * dabCost(d)
            spacingScale = if (cost > budget) cost / budget else 1f
        }

        protected fun flushDirty() {
            if (dirty.isEmpty) return
            controller.invalidateDoc(dirty)
            dirty.setEmpty()
        }

        abstract fun onDab(dab: Dab)
        abstract fun afterEvent()
        abstract fun complete()
        abstract fun cancel()
    }

    /** Paint / erase through the coverage buffer. */
    private inner class BufferStroke(
        layer: Layer,
        preset: BrushPreset,
        kind: StrokeKind,
        first: ToolPoint,
        seed: Long,
        private val maskTarget: Boolean,
    ) : Stroke(layer, preset, kind, first, seed) {
        private val docW = controller.doc.width
        private val docH = controller.doc.height
        private val coverage: Bitmap = res.coverage(docW, docH)
        private val canvas: Canvas = res.coverageCanvas()
        private val dabs = ArrayList<Dab>()
        /** Union of all dab bounds, clipped to the document. */
        private val bounds = Rect()
        private val style = CoverageStyle(
            mode = when {
                maskTarget -> PorterDuff.Mode.SRC_OVER
                kind == StrokeKind.ERASE -> PorterDuff.Mode.DST_OUT
                layer.alphaLocked -> PorterDuff.Mode.SRC_ATOP
                else -> PorterDuff.Mode.SRC_OVER
            },
            color = if (kind == StrokeKind.ERASE) 0xFF000000.toInt() else strokeColor(maskTarget),
            opacity = preset.opacity,
            grain = preset.grain,
        )

        private val override = object : LayerRenderOverride {
            override val layer: Layer get() = this@BufferStroke.layer

            override fun drawContent(canvas: Canvas): Boolean {
                if (maskTarget) return false
                canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                res.painter.draw(canvas, coverage, bounds, style, selection?.mask)
                return true
            }

            override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
                val mask = layer.mask
                if (!maskTarget || mask == null) return false
                val save = canvas.saveLayer(null, maskPaint)
                canvas.drawBitmap(mask, 0f, 0f, null)
                res.painter.draw(canvas, coverage, bounds, style, selection?.mask)
                canvas.restoreToCount(save)
                return true
            }
        }

        override val budget: Float get() = 6_000_000f

        init {
            controller.renderOverride = override
        }

        override fun onDab(dab: Dab) {
            dynamics.resolve(dab, null)
            res.stamper.stamp(canvas, preset, dab)
            dabs += dab
            addBounds(dab)
        }

        private fun addBounds(dab: Dab) {
            if (!dab.hasBounds) return
            val l = max(0, dab.left)
            val t = max(0, dab.top)
            val r = minOf(docW, dab.right)
            val b = minOf(docH, dab.bottom)
            if (r <= l || b <= t) return
            dirty.union(l, t, r, b)
            bounds.union(l, t, r, b)
        }

        override fun afterEvent() = flushDirty()

        /**
         * Re-renders the part of the stroke whose dabs change once the final length is known
         * (end taper, shortened tapers of short strokes, untapered taps).
         */
        private fun retaper() {
            if (!dynamics.hasTaper || dabs.isEmpty()) return
            val total = sampler.length
            val region = Rect()
            for (dab in dabs) {
                val d = dab.diameter
                val a = dab.alpha
                val x = dab.cx
                val y = dab.cy
                dynamics.resolve(dab, total)
                if (dab.diameter == d && dab.alpha == a && dab.cx == x && dab.cy == y) continue
                if (dab.hasBounds) region.union(dab.left, dab.top, dab.right, dab.bottom)
                res.stamper.measure(preset, dab)
                if (dab.hasBounds) region.union(dab.left, dab.top, dab.right, dab.bottom)
            }
            if (region.isEmpty || !region.intersect(0, 0, docW, docH)) return
            canvas.save()
            canvas.clipRect(region)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            for (dab in dabs) {
                if (region.intersects(dab.left, dab.top, dab.right, dab.bottom)) res.stamper.stamp(canvas, preset, dab)
            }
            canvas.restore()
            bounds.union(region)
        }

        override fun complete() {
            retaper()
            dirty.setEmpty()
            val commitRect = Rect(bounds)
            val sel = selection
            if (sel != null && !commitRect.intersect(sel.bounds)) commitRect.setEmpty()
            if (!commitRect.isEmpty && res.painter.isVisible(style)) {
                val rec = controller.beginEdit(layer)
                val target = if (rec.target == EditTarget.MASK) layer.mask else layer.bitmap
                if (target != null) {
                    // Composite tile by tile, only where dabs landed: long diagonal strokes skip
                    // their empty bounding box, and the grain/selection offscreen layer stays
                    // tile-sized instead of stroke-sized. Coverage, grain and selection are all
                    // document-anchored and drawn unscaled, so the result equals one big draw.
                    val canvas = Canvas(target)
                    for (r in touchedTiles(commitRect)) {
                        rec.touch(r)
                        res.painter.draw(canvas, coverage, r, style, sel?.mask)
                    }
                    controller.commitEdit(rec, undoLabel(kind))
                }
            }
            release()
        }

        /** [COMMIT_TILE]-aligned tiles (clipped to [clip]) that contain part of a dab. */
        private fun touchedTiles(clip: Rect): List<Rect> {
            val cols = (docW + COMMIT_TILE - 1) / COMMIT_TILE
            val rows = (docH + COMMIT_TILE - 1) / COMMIT_TILE
            val hit = BooleanArray(cols * rows)
            val r = Rect()
            for (dab in dabs) {
                if (!dab.hasBounds) continue
                r.set(dab.left, dab.top, dab.right, dab.bottom)
                if (!r.intersect(clip)) continue
                for (row in r.top / COMMIT_TILE..(r.bottom - 1) / COMMIT_TILE) {
                    for (col in r.left / COMMIT_TILE..(r.right - 1) / COMMIT_TILE) hit[row * cols + col] = true
                }
            }
            val out = ArrayList<Rect>()
            for (i in hit.indices) {
                if (!hit[i]) continue
                val x = (i % cols) * COMMIT_TILE
                val y = (i / cols) * COMMIT_TILE
                val t = Rect(x, y, x + COMMIT_TILE, y + COMMIT_TILE)
                if (t.intersect(clip)) out += t
            }
            return out
        }

        override fun cancel() = release()

        private fun release() {
            res.clear(bounds)
            if (controller.renderOverride === override) controller.renderOverride = null
            if (!bounds.isEmpty) controller.invalidateDoc(bounds)
        }
    }

    /** Smudge / blur / watercolor: dabs edit the layer (or mask) pixels directly. */
    private inner class DirectStroke(
        layer: Layer,
        preset: BrushPreset,
        kind: StrokeKind,
        first: ToolPoint,
        seed: Long,
        maskTarget: Boolean,
    ) : Stroke(layer, preset, kind, first, seed) {
        private val rec = controller.beginEdit(layer)
        private val painter = DirectPainter(
            kind = kind,
            preset = preset,
            surface = BitmapSurface(if (rec.target == EditTarget.MASK) layer.mask!! else layer.bitmap),
            selection = selection?.let { AlphaMaskReader(it.mask) },
            alphaLock = layer.alphaLocked && rec.target == EditTarget.CONTENT,
            color = strokeColor(maskTarget),
            limit = selection?.bounds?.let { IntBox(it.left, it.top, it.right, it.bottom) },
        )
        /** Dabs held back until the end taper is known (only with a finger end taper). */
        private val pending = ArrayDeque<Dab>()
        private val box = IntBox()
        private val touchRect = Rect()

        override val budget: Float get() = 1_500_000f

        override fun dabCost(d: Float): Float = if (kind == StrokeKind.BLUR) 2.5f * d * d else d * d

        override fun onDab(dab: Dab) {
            pending.addLast(dab)
        }

        override fun afterEvent() {
            drain(final = false)
            flushDirty()
        }

        private fun drain(final: Boolean) {
            val length = sampler.length
            val hold = dynamics.taperEnd
            while (pending.isNotEmpty()) {
                val dab = pending.first()
                if (!final && dab.distance + hold > length) break
                pending.removeFirst()
                dynamics.resolve(dab, if (final) length else null, scaleShort = false)
                render(dab)
            }
        }

        private fun render(dab: Dab) {
            // Snapshot for undo only what the painter is really about to change, so a smudge
            // tap (which moves nothing) leaves no undo entry.
            if (!painter.prepare(dab, box)) return
            touchRect.set(box.left, box.top, box.right, box.bottom)
            rec.touch(touchRect)
            painter.paint()
            dirty.union(touchRect)
        }

        override fun complete() {
            drain(final = true)
            dirty.setEmpty()
            controller.commitEdit(rec, undoLabel(kind))
        }

        override fun cancel() {
            pending.clear()
            dirty.setEmpty()
            val touched = Rect(rec.touched)
            rec.abort()
            if (!touched.isEmpty) controller.invalidateDoc(touched)
        }
    }

    private companion object {
        /** Commit tile size; matches the undo recorder's tiles so each touch snapshots one tile. */
        const val COMMIT_TILE = 256
    }
}
