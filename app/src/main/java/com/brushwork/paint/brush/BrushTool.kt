package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.SymmetryLabels
import kotlin.math.ceil
import kotlin.math.hypot
import kotlin.math.log2
import kotlin.math.max
import kotlin.math.min
import kotlin.math.pow

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

    /**
     * Decides, when a stroke starts, whether it is a normal raster stroke, refused, or recorded
     * (v1.5 seam: vector layers record strokes as objects). Path strokes ([beginPath]) are never
     * asked: they always get [StrokeHook.None].
     */
    var strokeHook: (StrokeInfo) -> StrokeHook = { controller.vectors.strokeHook(it) }

    /** Non-null: the coverage is painted with this source's shader instead of the color (clone stamp). */
    var coverageSource: CoverageSource? = null

    /** Non-null: the undo label of every stroke instead of "Brush" / "Eraser"... (e.g. "Clone stamp"). */
    var undoLabelOverride: String? = null

    /** True while a stroke is in progress. */
    val isStroking: Boolean get() = stroke != null

    /**
     * True while the stroke in progress is a path stroke ([beginPath]) with parts drawn as a
     * draft (fewer dabs, see [updatePath]); an exact update or [onUp] makes it exact.
     */
    val isDraft: Boolean get() = (stroke as? BufferStroke)?.isDraft == true

    /**
     * Approximate work of every dab drawn so far by the painting tools of this editor
     * ([pathDabCost] units, growing): the time a path update took divided by the work it did
     * tells how fast this device draws dabs, which sets the draft budgets of live previews.
     */
    val dabWork: Double get() = res.stamper.work

    override fun onDown(p: ToolPoint) {
        val s = start(p, System.nanoTime() xor (++strokeCounter * -0x61c8864680b583ebL)) ?: return
        s.begin(p)
    }

    /**
     * Cancels any stroke and creates a new one starting at [p] (null when the layer or the
     * [strokeHook] refuses). [isPath]: a stroke along a vector path (never hooked).
     */
    private fun start(p: ToolPoint, seed: Long, isPath: Boolean = false): Stroke? {
        stroke?.let { stroke = null; it.cancel() }
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return null
        val preset = preset.sanitized()
        val kind = StrokeKind.of(id, preset)
        val target = controller.editTargetOf(layer)
        val maskTarget = target == EditTarget.MASK
        // On a vector layer's content the eraser removes objects (the stroke hook), not pixels:
        // alpha lock doesn't apply there (v1.5). A path stroke is never hooked: it erases pixels.
        val erasesObjects = layer.isVectorLayer && !maskTarget && !isPath
        if (kind == StrokeKind.ERASE && layer.alphaLocked && !maskTarget && !erasesObjects) {
            controller.toast("Transparency is locked on \"${layer.name}\": the eraser can't remove pixels")
            return null
        }
        // v1.7 (item 18): the symmetry copies, placed from the first point (after the ruler).
        val maps = symmetryMaps(p, isPath)
        val toastBefore = controller.message
        val hook = if (isPath) StrokeHook.None else strokeHook(StrokeInfo(id, kind, layer, target, preset, seed, p.isStylus, strokeColor(maskTarget), isPath = false, copies = maps))
        val recorder = when (hook) {
            StrokeHook.None -> null
            is StrokeHook.Refuse -> { controller.toast(hook.message); return null }
            is StrokeHook.Record -> hook.recorder
        }
        // The vector eraser removes objects (whole strokes, copies and all): it is never replicated.
        val copies = if (maps.isNotEmpty() && recorder?.replacesStroke == true) {
            // Said once, and not over the eraser's own first hint (that one shows now; this one next time).
            if (!symmetryEraserNoteShown && controller.message == toastBefore) {
                symmetryEraserNoteShown = true
                controller.toast(SymmetryLabels.ERASER_NOTE)
            }
            emptyList()
        } else maps
        val s = try {
            if (kind.isDirect) DirectStroke(layer, preset, kind, p, seed, maskTarget, recorder, copies)
            else BufferStroke(layer, preset, kind, p, seed, maskTarget, recorder, copies)
        } catch (e: OutOfMemoryError) {
            recorder?.cancel()
            controller.toast("Not enough memory for this brush")
            return null
        }
        stroke = s
        return s
    }

    // ------------------------------------------------------------------ path strokes

    /**
     * Starts an unfinished stroke along [input] (stylus points, so their pressure is honored),
     * like [onDown] + [onMove] for every point, as the live preview of a vector path. [seed]
     * fixes the random values of the dabs, so the same input always gives the same pixels;
     * [updatePath] changes the path later and [onUp] finishes the stroke (one undo step) or
     * [onCancel] drops it without a trace.
     *
     * With a [draftBudget] (> 0, in [pathDabCost] units) a long path is drawn with fewer dabs
     * while a finger drags it (see [updatePath]). Returns false when nothing was started
     * (fewer than two points, or the layer refused with a message).
     */
    fun beginPath(input: PathStrokeInput, seed: Long, draftBudget: Float = 0f): Boolean {
        if (input.size < 2) return false
        val first = ToolPoint(input.x[0], input.y[0], input.pressure[0], isStylus = true)
        val s = start(first, seed, isPath = true) ?: return false
        if (s is BufferStroke) {
            s.startPath(input, draftBudget)
        } else {
            // Smudge / blur / watercolor dabs change the pixels as they go: no rewinding.
            s.begin(first)
            for (i in 1 until input.size) s.move(input.x[i], input.y[i], s.pressureOf(input.pressure[i]))
        }
        return true
    }

    /**
     * Makes the stroke in progress (started by [beginPath]) follow [input] instead, re-rendering
     * only from the first point that changed: the part before it is kept as it is on screen, so
     * editing the end of a long path costs only its end. The result is exactly the stroke
     * [beginPath] would draw for [input] with the same seed.
     *
     * With [draftBudget] > 0 (a finger is dragging) the re-rendered part is a draft whose dabs
     * are spaced further apart (at most half the brush width; the flow compensates)
     * when drawing it exactly would cost more than [draftBudget] ([pathDabCost] units per dab); an
     * update with no budget, or [onUp], redraws the draft parts exactly.
     *
     * Returns false when the stroke can't be updated (no path stroke in progress, or a
     * smudge / blur / watercolor stroke): the caller cancels it and starts again.
     */
    fun updatePath(input: PathStrokeInput, draftBudget: Float = 0f): Boolean {
        val s = stroke as? BufferStroke ?: return false
        if (!s.isPath || input.size < 2) return false
        s.updatePath(input, draftBudget)
        return true
    }

    /**
     * Redraws the next part of a draft path stroke exactly, doing about [budget] ([pathDabCost]
     * units) of work: the draft stays on screen beyond it, so a long stroke becomes exact over a
     * few frames without stalling one. Once every part is exact the stroke is exactly the one
     * [beginPath] draws. Returns true while parts are still drafts.
     */
    fun refinePath(budget: Float): Boolean {
        val s = stroke as? BufferStroke ?: return false
        if (!s.isPath) return false
        return s.refine(budget)
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

    override fun onDispose() {
        // Shared by the brush/eraser/smudge/blur instances; releasing twice is harmless.
        StrokeResources.releaseFor(controller)
    }

    /** A recorded stroke's own feedback (e.g. what the vector eraser will remove). */
    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        stroke?.recorder?.drawOverlay(canvas, t)
    }

    // ------------------------------------------------------------------ strokes

    private fun undoLabel(kind: StrokeKind) = undoLabelOverride ?: when (kind) {
        StrokeKind.PAINT -> "Brush"
        StrokeKind.ERASE -> "Eraser"
        StrokeKind.SMUDGE -> "Smudge"
        StrokeKind.BLUR -> "Blur"
        StrokeKind.WATERCOLOR -> "Watercolor"
    }

    /** "Symmetry doesn't apply to erasing vector objects" was said (once per tool). */
    private var symmetryEraserNoteShown = false

    /**
     * v1.7 (item 18, §3.18): the maps a stroke starting at [p] is replicated with (the identity
     * first; empty without symmetry). Every brush-type stroke of this tool is replicated (brush,
     * eraser, smudge, blur, on content or a mask); path strokes and the clone stamp are not.
     */
    private fun symmetryMaps(p: ToolPoint, isPath: Boolean): List<FloatArray> {
        if (isPath || coverageSource != null || id !in REPLICATED) return emptyList()
        val s = controller.symmetry
        if (s.type == SymmetryType.OFF) return emptyList()
        val d = controller.doc
        return SymmetryMaps.transforms(s, d.width, d.height, p.x, p.y)
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
        /** Receives the input points and the commit ([StrokeHook.Record]); null for normal strokes. */
        val recorder: StrokeRecorder?,
    ) {
        val isStylus = first.isStylus
        val dynamics = StrokeDynamics(preset, isStylus, seed)
        /** True when BrushTool paints nothing (the recorder replaces the stroke, e.g. the vector eraser). */
        val replaces: Boolean = recorder?.replacesStroke == true
        val selection: Selection? = if (recorder?.ignoresSelection == true) null else controller.selection
        val dirty = Rect()
        protected var spacingScale = 1f
        /** Draft spacing multiplier of a path stroke (see [updatePath]); 1 = exact. */
        protected var draftScale = 1f
        protected var lastX = first.x
        protected var lastY = first.y
        val sampler = StrokeSampler(
            spacingAt = { pr, d -> dynamics.spacing(pr, d) * spacingScale * draftScale },
            onSample = { x, y, pr, d -> onDab(dynamics.newDab(x, y, pr, d, recycledDab())) },
        )

        /** A dab object no longer used by this stroke, to be reused for the next dab (or null). */
        protected open fun recycledDab(): Dab? = null

        /** Pixel operations allowed per input event before dab spacing is stretched. */
        abstract val budget: Float

        /** Approximate pixel operations of one dab of diameter [d]. */
        open fun dabCost(d: Float): Float = d * d

        fun pressureOf(p: ToolPoint): Float = pressureOf(p.pressure)

        fun pressureOf(pressure: Float): Float =
            if (isStylus && !pressure.isNaN()) pressure.coerceIn(0f, 1f) else 1f

        fun begin(p: ToolPoint) {
            recorder?.point(p.x, p.y, p.pressure)
            begin(p.x, p.y, pressureOf(p))
        }

        fun begin(x: Float, y: Float, pressure: Float) {
            sampler.begin(x, y, pressure)
            afterEvent()
        }

        fun move(p: ToolPoint) {
            recorder?.point(p.x, p.y, p.pressure)
            move(p.x, p.y, pressureOf(p))
        }

        /** Adds an input point ([pressure] already resolved by [pressureOf]). */
        fun move(x: Float, y: Float, pressure: Float) {
            limitCost(x, y)
            sampler.add(x, y, pressure)
            afterEvent()
        }

        /** Ends the stroke (adding [p] as the last point) and commits it. */
        open fun finish(p: ToolPoint?) {
            if (p != null) {
                recorder?.point(p.x, p.y, p.pressure)
                limitCost(p.x, p.y)
                sampler.add(p.x, p.y, pressureOf(p))
            }
            sampler.end()
            complete()
        }

        /** Very large brushes on fast strokes would stall a frame: space their dabs further apart. */
        private fun limitCost(x: Float, y: Float) {
            val len = hypot(x - lastX, y - lastY)
            lastX = x
            lastY = y
            spacingScale = StrokeCost.spacingScale(len, dynamics.size, preset.spacing, { d -> dabCost(d) }, budget)
        }

        /**
         * Commits through the [recorder] when there is one ([commitPixels] is the normal commit),
         * else directly. [bounds] = what the stroke painted.
         */
        protected fun commitThrough(label: String, bounds: Rect, commitPixels: () -> Boolean): Boolean {
            val r = recorder ?: return commitPixels()
            return r.commit(label, Rect(bounds), commitPixels)
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
        recorder: StrokeRecorder?,
        /** The symmetry maps (identity first; empty: no copies, the v1.6 stroke). */
        copies: List<FloatArray>,
    ) : Stroke(layer, preset, kind, first, seed, recorder) {
        private val docW = controller.doc.width
        private val docH = controller.doc.height
        private val coverage: Bitmap = res.coverage(docW, docH)
        private val canvas: Canvas = res.coverageCanvas()
        private val dabs = ArrayList<Dab>()
        /**
         * v1.7 (item 18): every dab is followed by its symmetry copies in the same buffer (so
         * overlapping copies never darken each other). The copies are derived from the dabs
         * whenever they are drawn; [copyTiles] remembers the [COMMIT_TILE]s they reached.
         */
        private val mapping: DabMapping? = DabMapping.of(copies, res.stamper)
        private val tileCols = (docW + COMMIT_TILE - 1) / COMMIT_TILE
        private val copyTiles: BooleanArray? = mapping?.let { BooleanArray(tileCols * ((docH + COMMIT_TILE - 1) / COMMIT_TILE)) }
        /** Union of all dab bounds, clipped to the document. */
        private val bounds = Rect()
        /** Paints the coverage with pixels from elsewhere (clone stamp); fixed for the stroke. */
        private val source: CoverageSource? = coverageSource
        private var style = CoverageStyle(
            mode = when {
                maskTarget -> PorterDuff.Mode.SRC_OVER
                kind == StrokeKind.ERASE -> PorterDuff.Mode.DST_OUT
                layer.alphaLocked -> PorterDuff.Mode.SRC_ATOP
                else -> PorterDuff.Mode.SRC_OVER
            },
            color = if (kind == StrokeKind.ERASE) 0xFF000000.toInt() else strokeColor(maskTarget),
            opacity = preset.opacity,
            grain = preset.grain,
            shader = source?.shader,
        )
        private val clipRect = Rect()

        /** The style to draw [region] with: the coverage source prepares it and may have a new shader. */
        private fun styleFor(region: Rect?): CoverageStyle {
            val src = source ?: return style
            if (region != null && !region.isEmpty) src.prepare(region)
            val sh = src.shader
            if (style.shader !== sh) style = style.copy(shader = sh)
            return style
        }

        /** The part of the stroke [canvas] redraws (document px). */
        private fun drawRegion(canvas: Canvas): Rect? {
            if (source == null) return null
            if (!canvas.getClipBounds(clipRect)) return null
            return if (clipRect.intersect(bounds)) clipRect else null
        }

        /** 1-bit documents: the preview is thresholded like the commit will be (no gray edges). */
        private val monochrome: Paint? =
            if (!maskTarget && controller.doc.colorMode == ColorMode.MONOCHROME) Paint().apply { colorFilter = MONOCHROME_FILTER } else null

        private val override = object : LayerRenderOverride {
            override val layer: Layer get() = this@BufferStroke.layer

            override fun drawContent(canvas: Canvas): Boolean {
                if (maskTarget) return false
                val save = monochrome?.let { canvas.saveLayer(null, it) }
                canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                res.painter.draw(canvas, coverage, bounds, styleFor(drawRegion(canvas)), selection?.mask)
                if (save != null) canvas.restoreToCount(save)
                return true
            }

            override fun drawMask(canvas: Canvas, maskPaint: Paint): Boolean {
                val mask = layer.mask
                if (!maskTarget || mask == null) return false
                val save = canvas.saveLayer(null, maskPaint)
                canvas.drawBitmap(mask, 0f, 0f, null)
                res.painter.draw(canvas, coverage, bounds, styleFor(drawRegion(canvas)), selection?.mask)
                canvas.restoreToCount(save)
                return true
            }
        }

        override val budget: Float get() = StrokeCost.BUFFER_BUDGET

        init {
            // A replaced stroke (vector eraser) shows nothing of its own: its recorder draws feedback.
            if (!replaces) controller.renderOverride = override
        }

        override fun onDab(dab: Dab) {
            dynamics.resolve(dab, null)
            // Spread draft dabs each lay down as much paint as the dabs they stand for together.
            if (drafting && draftScale > 1f) dab.alpha = 1f - (1f - dab.alpha).pow(draftScale)
            if (replaces) res.stamper.measure(preset, dab) else res.stamper.stamp(canvas, preset, dab, draft = drafting)
            dabs += dab
            addBounds(dab)
            if (mapping != null) stampCopies(mapping, dab, null)
        }

        /**
         * Stamps the symmetry copies of resolved [dab] after it, in map order (as `StrokeRaster`
         * replays them), each one that lands on the document; only those reaching [within] when
         * it is given (a redraw clipped to it).
         */
        private fun stampCopies(m: DabMapping, dab: Dab, within: Rect?) {
            for (k in 0 until m.copies) {
                val c = m.place(k, preset, dab) ?: continue
                if (!c.hasBounds || c.right <= 0 || c.bottom <= 0 || c.left >= docW || c.top >= docH) continue
                if (within != null && !within.intersects(c.left, c.top, c.right, c.bottom)) continue
                m.stamp(canvas, k, c)
                addBounds(c)
                markCopyTiles(c)
            }
        }

        /** Unions the bounds of [dab]'s copies (as they are resolved now) into [region]. */
        private fun copyBounds(m: DabMapping, dab: Dab, region: Rect) {
            for (k in 0 until m.copies) {
                val c = m.place(k, preset, dab) ?: continue
                if (c.hasBounds) region.union(c.left, c.top, c.right, c.bottom)
            }
        }

        private fun markCopyTiles(c: Dab) {
            val tiles = copyTiles ?: return
            val l = max(0, c.left); val t = max(0, c.top)
            val r = min(docW, c.right); val b = min(docH, c.bottom)
            if (r <= l || b <= t) return
            for (row in t / COMMIT_TILE..(b - 1) / COMMIT_TILE) {
                for (col in l / COMMIT_TILE..(r - 1) / COMMIT_TILE) tiles[row * tileCols + col] = true
            }
        }

        // ---------------------------------------------------------- path strokes

        /** True for a stroke started by [beginPath] (it can be rewound and re-rendered). */
        var isPath = false
            private set

        /** The input points fed so far, exactly as given. */
        private var input = PathStrokeInput(0)
        /** Reused copy of [input] (an exact update of the whole path takes a separate input). */
        private val scratch = PathStrokeInput(0)

        private fun inputCopy(): PathStrokeInput = scratch.also { it.set(input) }
        private val checkpoints = ArrayList<Checkpoint>()
        /**
         * First input point rendered as a draft (Int.MAX_VALUE: everything is exact). Drafts
         * are always the end of the stroke: the dabs from [draftDabFrom] on.
         */
        private var draftFrom = Int.MAX_VALUE
        private var draftDabFrom = Int.MAX_VALUE
        /** New dabs are drafts (while [feedPlanned] feeds a draft). */
        private var drafting = false
        val isDraft: Boolean get() = draftFrom != Int.MAX_VALUE
        /**
         * Dabs removed by a rewind, reused for the next dabs: a finger dragging a long path
         * re-renders thousands of dabs per frame without allocating them again.
         */
        private val pool = ArrayList<Dab>()

        override fun recycledDab(): Dab? = if (pool.isEmpty()) null else pool.removeAt(pool.lastIndex)

        /** Cells cleared by a rewind. */
        private var cleared: CellGrid? = null
        /** Cells to redraw on screen after an update. */
        private var dirtyCells: CellGrid? = null
        private val runRect = Rect()

        fun startPath(points: PathStrokeInput, draftBudget: Float) {
            isPath = true
            val cs = cellSizeFor(dynamics.size)
            cleared = CellGrid(docW, docH, cs)
            dirtyCells = CellGrid(docW, docH, cs)
            input = PathStrokeInput(max(256, points.size))
            checkpoints += Checkpoint(-1, 0, sampler.snapshot(), 0L, points.x[0], points.y[0], 1f)
            feedPlanned(points, 0, draftPlan(points, 0, draftBudget, mustDraft = false))
            flushCells()
        }

        fun updatePath(points: PathStrokeInput, draftBudget: Float) {
            val n = points.size
            val old = input.size
            val lim = min(n, old)
            val ox = input.x; val oy = input.y; val op = input.pressure
            val nx = points.x; val ny = points.y; val np = points.pressure
            var i = 0
            while (i < lim && ox[i] == nx[i] && oy[i] == ny[i] && op[i] == np[i]) i++
            val exact = draftBudget <= 0f
            if (i == n && n == old && (!exact || !isDraft)) return
            // Everything up to the point before the first change stays (for an exact update:
            // also before the first draft point); restart from the closest state kept for it.
            var target = i - 1
            if (exact) target = min(target, draftFrom - 1)
            var k = checkpoints.lastIndex
            while (k > 0 && checkpoints[k].input > target) k--
            val cp = checkpoints[k]
            // Drafts stay the end of the stroke: after a kept draft part, the rest is one too.
            val plan = if (exact) null else draftPlan(points, cp.input + 1, draftBudget, mustDraft = draftFrom <= cp.input)
            rewind(cp)
            if (draftFrom > cp.input) {
                // Every draft dab was removed.
                draftFrom = Int.MAX_VALUE
                draftDabFrom = Int.MAX_VALUE
            }
            feedPlanned(points, cp.input + 1, plan)
            flushCells()
        }

        /** Feeds [points] from [from] on: exactly (null [plan]) or as a draft with that spacing multiplier. */
        private fun feedPlanned(points: PathStrokeInput, from: Int, plan: Float?) {
            if (plan != null && draftFrom == Int.MAX_VALUE) {
                draftFrom = from
                draftDabFrom = dabs.size
            }
            drafting = plan != null
            draftScale = plan ?: 1f
            try {
                feed(points, from)
            } finally {
                drafting = false
                draftScale = 1f
            }
        }

        /**
         * Feeds input points [from] until [until] of [points], recording a checkpoint every few
         * points; [record] adds them to the stroke's input (false: they are already there).
         */
        private fun feed(points: PathStrokeInput, from: Int, until: Int = points.size, record: Boolean = true) {
            for (i in from until until) {
                val x = points.x[i]
                val y = points.y[i]
                val p = pressureOf(points.pressure[i])
                if (i == 0) {
                    lastX = x; lastY = y; spacingScale = 1f
                    begin(x, y, p)
                } else {
                    move(x, y, p)
                }
                if (record) input.add(x, y, points.pressure[i])
                if (i % CHECKPOINT_EVERY == 0) {
                    checkpoints += Checkpoint(i, dabs.size, sampler.snapshot(), dynamics.randomDraws, lastX, lastY, spacingScale)
                }
            }
        }

        /**
         * Goes back to the state after input point [Checkpoint.input]: the dabs drawn since are
         * removed (see [removeDabs]), then the stroke continues from there.
         */
        private fun rewind(cp: Checkpoint) {
            removeDabs(cp.dabCount, dabs.size)
            restoreState(cp)
            input.truncate(cp.input + 1)
            while (checkpoints.last().input > cp.input) checkpoints.removeAt(checkpoints.lastIndex)
        }

        private fun restoreState(cp: Checkpoint) {
            sampler.restore(cp.sampler)
            dynamics.restoreRandom(cp.draws)
            lastX = cp.lastX
            lastY = cp.lastY
            spacingScale = cp.spacingScale
        }

        /**
         * Removes dabs [from] until [until] from the list and the coverage buffer: the cells
         * they touched are cleared, then the other dabs reaching into those cells are redrawn
         * there (clipped to them, in their order, drafts as drafts), so every pixel ends up as if
         * the removed dabs had never been drawn.
         */
        private fun removeDabs(from: Int, until: Int) {
            if (from >= until) return
            val grid = cleared!!
            val screen = dirtyCells!!
            grid.clear()
            for (k in from until until) {
                val d = dabs[k]
                if (d.hasBounds) grid.mark(d.left, d.top, d.right, d.bottom)
            }
            grid.forEachRun { row, c0, c1 ->
                grid.runRect(row, c0, c1, runRect)
                canvas.save()
                canvas.clipRect(runRect)
                canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                canvas.restore()
                screen.mark(runRect)
            }
            val cs = grid.cellSize
            for (k in dabs.indices) {
                if (k in from until until) continue
                val d = dabs[k]
                if (!d.hasBounds) continue
                val l = max(0, d.left); val t = max(0, d.top)
                val r = min(docW, d.right); val b = min(docH, d.bottom)
                if (r <= l || b <= t) continue
                val c0 = l / cs
                val c1 = (r - 1) / cs
                for (row in t / cs..(b - 1) / cs) {
                    grid.forEachRunIn(row, c0, c1) { a, e ->
                        grid.runRect(row, a, e, runRect)
                        canvas.save()
                        canvas.clipRect(runRect)
                        res.stamper.stamp(canvas, preset, d, draft = k >= draftDabFrom)
                        canvas.restore()
                    }
                }
            }
            for (k in from until until) pool.add(dabs[k])
            dabs.subList(from, until).clear()
        }

        /**
         * Redraws the first part of the draft exactly (about [budget] of work, see
         * [BrushTool.refinePath]); returns true while a draft part remains.
         *
         * The draft dabs of that part are removed and exact dabs drawn in their place, from the
         * exact state at the start of the draft, and put before the remaining draft dabs in the
         * list. The pixels are right once the whole draft is replaced: every cell a draft dab
         * touched is cleared when that dab is removed and redrawn from the dabs in list order,
         * and new exact dabs only ever come after the exact dabs before them.
         */
        fun refine(budget: Float): Boolean {
            if (!isDraft) return false
            val e = draftFrom
            val n = input.size
            // The part: from the start of the draft to about [budget] of exact work further, up
            // to a draft checkpoint (whose dab count tells where its draft dabs end).
            val d = max(1f, dynamics.size)
            val perPx = pathDabCost(d) / max(StrokeDynamics.MIN_SPACING_PX, preset.spacing * d)
            var j = max(e, 1)
            var len = 0f
            while (j < n && len * perPx < budget) {
                len += hypot(input.x[j] - input.x[j - 1], input.y[j] - input.y[j - 1])
                j++
            }
            var end = -1
            for (k in checkpoints.indices) {
                val c = checkpoints[k]
                if (c.input >= e && c.input >= j - 1) { end = k; break }
            }
            if (end < 0 || checkpoints[end].input >= n - 1) {
                // The last part: an exact update from the start of the draft.
                updatePath(inputCopy(), 0f)
                return false
            }
            val cpEnd = checkpoints[end]
            val f = cpEnd.input + 1
            var startIdx = checkpoints.lastIndex
            while (startIdx > 0 && checkpoints[startIdx].input > e - 1) startIdx--
            val cpStart = checkpoints[startIdx]
            if (cpStart.input != e - 1) {
                // (Never expected: the exact state at the start of the draft is always kept.)
                updatePath(inputCopy(), 0f)
                return false
            }
            val dStart = draftDabFrom
            val dEnd = cpEnd.dabCount
            val restCps = ArrayList(checkpoints.subList(end + 1, checkpoints.size))
            // The part's draft dabs leave; the draft beyond the part stays on screen and is set
            // aside while the part is redrawn exactly in front of it.
            removeDabs(dStart, dEnd)
            val restDabs = ArrayList(dabs.subList(dStart, dabs.size))
            dabs.subList(dStart, dabs.size).clear()
            while (checkpoints.last().input > cpStart.input) checkpoints.removeAt(checkpoints.lastIndex)
            restoreState(cpStart)
            feed(input, e, f, record = false)
            val delta = dabs.size - dEnd
            draftDabFrom = dabs.size
            dabs.addAll(restDabs)
            for (c in restCps) {
                c.dabCount += delta
                checkpoints += c
            }
            draftFrom = f
            flushCells()
            return true
        }

        /**
         * How to re-render [points] from index [from] on within [budget] ([pathDabCost] units):
         * null = exactly, when that fits (and no draft part is kept before it: [mustDraft]);
         * otherwise as a draft ([DabStamper.stamp]) whose dabs are spread by the returned
         * multiplier when even that doesn't fit, in steps of a fourth of an octave (so the dabs
         * don't shimmer while a drag changes the length a little) and at most up to a spacing
         * that still looks solid.
         */
        private fun draftPlan(points: PathStrokeInput, from: Int, budget: Float, mustDraft: Boolean): Float? {
            if (budget <= 0f) return null
            var len = 0f
            for (j in max(1, from) until points.size) len += hypot(points.x[j] - points.x[j - 1], points.y[j] - points.y[j - 1])
            val d = max(1f, dynamics.size)
            val nominal = max(StrokeDynamics.MIN_SPACING_PX, preset.spacing * d)
            val count = len / nominal
            if (!mustDraft && count * pathDabCost(d) <= budget) return null
            val cost = count * (DRAFT_DAB_OVERHEAD + d * d)
            if (cost <= budget) return 1f
            // Pixel brushes keep their (1 px) dabs touching.
            val cap = if (!preset.antiAlias) 1f else d * DRAFT_SPACING
            val maxScale = max(1f, cap / nominal)
            val steps = ceil(4f * log2(cost / budget))
            return min(2f.pow(steps / 4f), maxScale)
        }

        private fun flushCells() {
            val grid = dirtyCells ?: return
            grid.forEachRun { row, c0, c1 -> controller.invalidateDoc(grid.runRect(row, c0, c1, runRect)) }
            grid.clear()
        }

        /** Ends a path stroke: a draft is redrawn exactly first. */
        override fun finish(p: ToolPoint?) {
            if (isPath && isDraft) updatePath(inputCopy(), 0f)
            super.finish(p)
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

        override fun afterEvent() {
            if (!isPath) { flushDirty(); return }
            // A path is redrawn on screen once per update, cell by cell.
            if (dirty.isEmpty) return
            dirtyCells?.mark(dirty)
            dirty.setEmpty()
        }

        /**
         * Re-renders the part of the stroke whose dabs change once the final length is known
         * (end taper, shortened tapers of short strokes, untapered taps).
         */
        private fun retaper() {
            if (!dynamics.hasTaper || dabs.isEmpty()) return
            val total = sampler.length
            val region = Rect()
            val m = mapping
            for (dab in dabs) {
                val d = dab.diameter
                val a = dab.alpha
                val x = dab.cx
                val y = dab.cy
                dynamics.resolve(dab, total)
                if (dab.diameter == d && dab.alpha == a && dab.cx == x && dab.cy == y) continue
                if (dab.hasBounds) region.union(dab.left, dab.top, dab.right, dab.bottom)
                if (m != null) {
                    // Where the copies were (resolved as before) and where they go now.
                    val nd = dab.diameter; val na = dab.alpha; val nx = dab.cx; val ny = dab.cy
                    dab.cx = x; dab.cy = y; dab.diameter = d; dab.alpha = a
                    copyBounds(m, dab, region)
                    dab.cx = nx; dab.cy = ny; dab.diameter = nd; dab.alpha = na
                }
                res.stamper.measure(preset, dab)
                if (dab.hasBounds) region.union(dab.left, dab.top, dab.right, dab.bottom)
                if (m != null) copyBounds(m, dab, region)
            }
            if (region.isEmpty || !region.intersect(0, 0, docW, docH)) return
            canvas.save()
            canvas.clipRect(region)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            for (dab in dabs) {
                if (region.intersects(dab.left, dab.top, dab.right, dab.bottom)) res.stamper.stamp(canvas, preset, dab)
                if (m != null) stampCopies(m, dab, region)
            }
            canvas.restore()
            bounds.union(region)
        }

        override fun complete() {
            if (replaces) {
                // Nothing was painted: the recorder does the whole edit.
                dirty.setEmpty()
                commitThrough(undoLabel(kind), bounds) { false }
                release()
                return
            }
            retaper()
            dirty.setEmpty()
            val commitRect = Rect(bounds)
            val sel = selection
            if (sel != null && !commitRect.intersect(sel.bounds)) commitRect.setEmpty()
            commitThrough(undoLabel(kind), bounds) { commitPixels(commitRect, sel) }
            release()
        }

        /** Composites the coverage into the layer (or its mask) within [commitRect]: the normal commit. */
        private fun commitPixels(commitRect: Rect, sel: Selection?): Boolean {
            if (commitRect.isEmpty || !res.painter.isVisible(style)) return false
            val rec = controller.beginEdit(layer)
            val target = (if (rec.target == EditTarget.MASK) layer.mask else layer.bitmap) ?: return false
            // The clone stamp copies its source region first: painting must never read pixels
            // this very commit has already changed (V13).
            source?.let { src ->
                src.prepareCommit(commitRect)
                styleFor(null)
            }
            // Composite tile by tile, only where dabs landed: long diagonal strokes skip
            // their empty bounding box, and the grain/selection offscreen layer stays
            // tile-sized instead of stroke-sized. Coverage, grain and selection are all
            // document-anchored and drawn unscaled, so the result equals one big draw.
            val canvas = Canvas(target)
            for (r in touchedTiles(commitRect)) {
                rec.touch(r)
                res.painter.draw(canvas, coverage, r, style, sel?.mask)
            }
            return controller.commitEdit(rec, undoLabel(kind))
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
            // The symmetry copies' tiles (kept as they were stamped: the copies aren't listed).
            copyTiles?.let { t -> for (i in t.indices) if (t[i]) hit[i] = true }
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

        override fun cancel() {
            recorder?.cancel()
            release()
        }

        private var released = false

        private fun release() {
            if (controller.renderOverride === override) controller.renderOverride = null
            if (!released) {
                released = true
                source?.endStroke()
            }
            val grid = cleared
            if (isPath && grid != null) {
                // Only the cells under the current dabs hold coverage (rewinds cleared the rest):
                // a long path doesn't clear and redraw its whole bounding box.
                grid.clear()
                for (d in dabs) if (d.hasBounds) grid.mark(d.left, d.top, d.right, d.bottom)
                grid.forEachRun { row, c0, c1 ->
                    grid.runRect(row, c0, c1, runRect)
                    res.clear(runRect)
                    controller.invalidateDoc(runRect)
                }
                grid.clear()
                return
            }
            res.clear(bounds)
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
        recorder: StrokeRecorder?,
        /** The symmetry maps (identity first; empty: no copies, the v1.6 stroke). */
        copies: List<FloatArray>,
    ) : Stroke(layer, preset, kind, first, seed, recorder) {
        private val rec = controller.beginEdit(layer)
        private val painter = newPainter(maskTarget)

        private fun newPainter(maskTarget: Boolean) = DirectPainter(
            kind = kind,
            preset = preset,
            surface = BitmapSurface(if (rec.target == EditTarget.MASK) layer.mask!! else layer.bitmap),
            selection = selection?.let { AlphaMaskReader(it.mask) },
            alphaLock = layer.alphaLocked && rec.target == EditTarget.CONTENT,
            color = strokeColor(maskTarget),
            limit = selection?.bounds?.let { IntBox(it.left, it.top, it.right, it.bottom) },
        )

        /**
         * v1.7 (item 18): the symmetry copies are independent sub-strokes, one painter each (its
         * own smudge transport / paint load), fed the mapped dabs right after the stroke's own.
         */
        private val mapping: DabMapping? = DabMapping.of(copies, res.stamper)
        private val copyPainters: Array<DirectPainter> = Array(mapping?.copies ?: 0) { newPainter(maskTarget) }
        private val copyDab = Dab(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0, 0f)
        /** Dabs held back until the end taper is known (only with a finger end taper). */
        private val pending = ArrayDeque<Dab>()
        private val box = IntBox()
        private val touchRect = Rect()

        /**
         * 1-bit documents: the dabs build up intermediate grays in the layer (thresholding each
         * dab would stop low-flow strokes from accumulating), so the layer is shown thresholded
         * exactly as the commit will constrain it.
         */
        private val monochrome: LayerRenderOverride? =
            if (rec.target == EditTarget.CONTENT && controller.doc.colorMode == ColorMode.MONOCHROME) {
                object : LayerRenderOverride {
                    private val paint = Paint().apply { colorFilter = MONOCHROME_FILTER }
                    override val layer: Layer get() = this@DirectStroke.layer
                    override fun drawContent(canvas: Canvas): Boolean {
                        val save = canvas.saveLayer(null, paint)
                        canvas.drawBitmap(layer.bitmap, 0f, 0f, null)
                        canvas.restoreToCount(save)
                        return true
                    }
                }
            } else null

        init {
            if (monochrome != null) controller.renderOverride = monochrome
        }

        override val budget: Float get() = StrokeCost.DIRECT_BUDGET

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

        /** What the dabs reached (document px), also when the recorder replaces the stroke. */
        private val reached = Rect()

        private fun render(dab: Dab) {
            render(painter, dab)
            val m = mapping ?: return
            for (k in 0 until m.copies) {
                if (m.mapInto(k, dab, copyDab)) render(copyPainters[k], copyDab)
            }
        }

        private fun render(painter: DirectPainter, dab: Dab) {
            // Snapshot for undo only what the painter is really about to change, so a smudge
            // tap (which moves nothing) leaves no undo entry.
            if (!painter.prepare(dab, box)) return
            reached.union(box.left, box.top, box.right, box.bottom)
            if (replaces) return
            touchRect.set(box.left, box.top, box.right, box.bottom)
            rec.touch(touchRect)
            painter.paint()
            dirty.union(touchRect)
        }

        override fun complete() {
            drain(final = true)
            dirty.setEmpty()
            dropOverride()
            commitThrough(undoLabel(kind), reached) { controller.commitEdit(rec, undoLabel(kind)) }
        }

        override fun cancel() {
            recorder?.cancel()
            pending.clear()
            dirty.setEmpty()
            dropOverride()
            val touched = Rect(rec.touched)
            rec.abort()
            if (!touched.isEmpty) controller.invalidateDoc(touched)
        }

        private fun dropOverride() {
            if (monochrome != null && controller.renderOverride === monochrome) controller.renderOverride = null
        }
    }

    /** State of a path stroke after one of its input points (see [BufferStroke.updatePath]). */
    private class Checkpoint(
        val input: Int,
        var dabCount: Int,
        val sampler: StrokeSampler.State,
        val draws: Long,
        val lastX: Float,
        val lastY: Float,
        val spacingScale: Float,
    )

    companion object {
        /** The tools whose strokes the symmetry rulers replicate (v1.7 item 18). */
        private val REPLICATED = setOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)

        /** Commit tile size; matches the undo recorder's tiles so each touch snapshots one tile. */
        private const val COMMIT_TILE = 256

        /** A path stroke keeps its state every this many input points (to restart from there). */
        private const val CHECKPOINT_EVERY = 32

        /**
         * Largest draft dab spacing, as a fraction of the diameter: the edge of a hard tip
         * ripples by about an eighth of its radius (half a pixel for the default pen) while the
         * finger drags; the exact stroke replaces the draft when it lifts.
         */
        private const val DRAFT_SPACING = 0.5f

        /**
         * Fixed cost of drawing one dab, in the units of [pathDabCost] (about a nanosecond of
         * pixel work each): small dabs cost mostly this, whatever their size.
         */
        const val DAB_OVERHEAD = 5000f

        /** The same for a draft dab (no anti-aliased box edge: several times cheaper). */
        internal const val DRAFT_DAB_OVERHEAD = 1000f

        /** Approximate cost of one dab of [diameter] px (the unit of draft budgets). */
        fun pathDabCost(diameter: Float): Float = DAB_OVERHEAD + diameter * diameter

        /**
         * Cells of a path stroke's redraw grid: about three dabs wide (at least 32 px). Where a
         * path is re-rendered, the kept dabs in the cells it touches are redrawn, so small cells
         * redraw little; each dab is redrawn once per row of cells it overlaps.
         */
        private fun cellSizeFor(size: Float): Int {
            var cs = 32
            while (cs < 3f * size && cs < 1024) cs *= 2
            return cs
        }

        /**
         * Same result as `ColorModeOps` MONOCHROME on (unpremultiplied) pixels: alpha >= 128 ->
         * opaque else transparent, luminance >= 128 -> white else black. A steep linear ramp
         * clamped to 0..255 acts as the threshold.
         */
        private val MONOCHROME_FILTER: ColorMatrixColorFilter by lazy {
            val k = 65536f
            val lr = 0.299f * k
            val lg = 0.587f * k
            val lb = 0.114f * k
            val off = -127.5f * k
            ColorMatrixColorFilter(
                ColorMatrix(
                    floatArrayOf(
                        lr, lg, lb, 0f, off,
                        lr, lg, lb, 0f, off,
                        lr, lg, lb, 0f, off,
                        0f, 0f, 0f, k, off,
                    )
                )
            )
        }
    }
}
