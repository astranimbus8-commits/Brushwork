package com.brushwork.paint.tools.pathfinder

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeOutlines
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import com.brushwork.paint.vector.pathfinder.PathfinderOperand
import com.brushwork.paint.vector.pathfinder.PathfinderOps
import com.brushwork.paint.vector.pathfinder.PathfinderResult
import com.brushwork.paint.vector.pathfinder.PathfinderStyle
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.math.hypot

/**
 * The Pathfinder tool (v1.7 item 20, §3.20; area G): tap shapes and paths on any layer to pick
 * the operands, then combine them (Unite, Minus front, Minus back, Intersect, Exclude, Divide,
 * Trim, Merge, Crop, Outline). It picks its operands itself, so `LayerToolRules` never refuses it,
 * and it works whatever layer is active.
 *
 * - A tap toggles the topmost eligible object under the finger, hit-tested top-down across every
 *   effectively visible, unlocked shape layer (one operand) and vector layer (each path or shape
 *   object one operand). Brush strokes and arrayed layers are skipped with their message; a miss
 *   brings back the hint ([missed]). A drag does nothing.
 * - [selectAll] picks every eligible object, up to [PathfinderOps.MAX_OPERANDS] (with more: none,
 *   and "Select up to 12 objects").
 * - [apply] computes on [computeDispatcher] ("Working…" after [BUSY_AFTER_MS]), then lands the
 *   result as ONE step named "Pathfinder: <op>": in the operands' own vector layer when they all
 *   are objects of one, else in a new vector layer "Pathfinder N" directly above the top operand's
 *   layer (`LayerStructure.insert`), the operand objects removed from their layers and the operand
 *   shape layers deleted (`LayerStructure.delete`).
 *
 * The picks are kept across layer changes and re-checked on every use (a pick whose layer or
 * object is gone, hidden or locked no longer counts); choosing the tool starts with none. Picks
 * are not document edits: the tool never has pending work.
 */
class PathfinderTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.PATHFINDER

    /** One picked operand: a shape layer ([objectId] null) or an object of a vector layer. */
    data class Pick(val layer: Layer, val objectId: Long?)

    private val picks = mutableStateListOf<Pick>()

    /**
     * The picks that are operands now, in picture order (bottom first: the layer order, then the
     * object order inside a layer). Reads Compose state, so the options strip follows edits.
     */
    val operands: List<Pick>
        get() {
            controller.layersVersion
            controller.editCount
            val doc = controller.doc
            return picks.filter(::eligible).sortedWith(
                compareBy<Pick>({ doc.indexOf(it.layer) }, { p -> p.objectId?.let { p.layer.vector?.indexOf(it) } ?: -1 }),
            )
        }

    /** How many operands are picked (Compose state). */
    val count: Int get() = operands.size

    /** True while an operation is being computed (Compose state): taps and buttons wait. */
    var busy by mutableStateOf(false)
        private set

    /** The last tap found nothing to pick: the strip shows the hint again (Compose state). */
    var missed by mutableStateOf(false)
        private set

    /** Where operations are computed (`Dispatchers.Default`; tests run them inline). */
    internal var computeDispatcher: CoroutineDispatcher = Dispatchers.Default

    private var job: Job? = null
    private var down: ToolPoint? = null
    private var dragged = false

    override fun onSelected() {
        picks.clear()
        missed = false
        controller.invalidateOverlay()
    }

    override fun onDown(p: ToolPoint) {
        down = p
        dragged = false
    }

    override fun onMove(p: ToolPoint) {
        val d = down ?: return
        val t = controller.viewTransform
        if (!dragged && hypot(p.x - d.x, p.y - d.y) > t.screenToDocLength(t.dp(TAP_SLOP_DP))) dragged = true
    }

    override fun onUp(p: ToolPoint) {
        val d = down ?: return
        down = null
        if (!dragged) tapAt(Vec2(d.x, d.y))
        dragged = false
    }

    override fun onCancel() {
        down = null
        dragged = false
    }

    override fun onDispose() {
        job?.cancel()
    }

    // ------------------------------------------------------------------ picking

    /** Toggles the topmost eligible object at document point [p] (see the class docs). */
    fun tapAt(p: Vec2) {
        if (busy || !p.x.isFinite() || !p.y.isFinite()) return
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(TAP_TOLERANCE_DP))
        val doc = controller.doc
        var skipped: String? = null
        for (i in doc.layers.indices.reversed()) {
            val layer = doc.layers[i]
            if (!usable(layer)) continue
            if (layer.isShapeLayer) {
                val shape = ShapeCodec.decode(layer.shapeData) ?: continue
                if (!ShapeOutlines.hits(shape, p, tol)) continue
                if (layer.array != null) { skipped = skipped ?: PathfinderLabels.ARRAY_SKIPPED; continue }
                toggle(Pick(layer, null))
                return
            }
            val content = layer.vector ?: continue
            for (j in content.objects.indices.reversed()) {
                val o = content.objects[j]
                if (!VectorOps.hit(o, p, tol)) continue
                if (layer.array != null) { skipped = skipped ?: PathfinderLabels.ARRAY_SKIPPED; break }
                if (o is VStroke) { skipped = skipped ?: PathfinderLabels.STROKES_SKIPPED; continue }
                toggle(Pick(layer, o.id))
                return
            }
        }
        if (skipped != null) controller.toast(skipped) else missed = true
    }

    /**
     * "Select all objects": every eligible object on visible, unlocked layers, bottom first. More
     * than [PathfinderOps.MAX_OPERANDS] selects none ("Select up to 12 objects"); skipped brush
     * strokes and arrayed layers are reported.
     */
    fun selectAll() {
        if (busy) return
        val doc = controller.doc
        val all = ArrayList<Pick>()
        var skipped: String? = null
        for (layer in doc.layers) {
            if (!usable(layer)) continue
            if (layer.isShapeLayer) {
                if (layer.array != null) skipped = skipped ?: PathfinderLabels.ARRAY_SKIPPED else all += Pick(layer, null)
                continue
            }
            val content = layer.vector ?: continue
            if (layer.array != null) {
                if (content.objects.isNotEmpty()) skipped = skipped ?: PathfinderLabels.ARRAY_SKIPPED
                continue
            }
            for (o in content.objects) {
                if (o is VStroke) skipped = skipped ?: PathfinderLabels.STROKES_SKIPPED else all += Pick(layer, o.id)
            }
        }
        picks.clear()
        missed = false
        if (all.size > PathfinderOps.MAX_OPERANDS) {
            controller.toast(PathfinderLabels.TOO_MANY_OPERANDS)
        } else {
            picks.addAll(all)
            skipped?.let { controller.toast(it) }
        }
        controller.invalidateOverlay()
    }

    /** Forgets every pick. */
    fun clearPicks() {
        picks.clear()
        missed = false
        controller.invalidateOverlay()
    }

    private fun toggle(p: Pick) {
        missed = false
        if (picks.remove(p)) { controller.invalidateOverlay(); return }
        // Picks that no longer count make room.
        picks.retainAll(::eligible)
        if (picks.size >= PathfinderOps.MAX_OPERANDS) { controller.toast(PathfinderLabels.TOO_MANY_OPERANDS); return }
        picks += p
        controller.invalidateOverlay()
    }

    /** A layer the tool may take operands from: a shown, unlocked pixel layer (rule L). */
    private fun usable(layer: Layer): Boolean {
        val doc = controller.doc
        return !layer.isFolder && doc.effectiveVisible(layer) && !doc.effectiveLocked(layer)
    }

    /** [p] is an operand now: its layer is in the document, usable, not arrayed, and has the object. */
    private fun eligible(p: Pick): Boolean {
        val layer = p.layer
        if (controller.doc.indexOf(layer) < 0 || layer.array != null || !usable(layer)) return false
        val id = p.objectId ?: return layer.isShapeLayer
        if (layer.isShapeLayer) return false
        val o = layer.vector?.byId(id)
        return o is VPath || o is VShape
    }

    // ------------------------------------------------------------------ operations

    /** What an operand was when the operation started (to land the result only on that). */
    private class Taken(
        val pick: Pick,
        val content: VectorContent?,
        val shapeData: String?,
        val opacity: Float,
        val input: PathfinderOperand,
    )

    /**
     * Combines the operands with [op] (2 or more; ignored while [busy]). The result lands when
     * the computation ends, if the operands are still as they were and the tool is still in use.
     */
    fun apply(op: PathfinderOp) {
        if (busy) return
        // An earlier edit still rendering in the background lands first: the snapshots below
        // must be of the content the user sees, or the result would be landed over that edit.
        controller.vectors.flushPending()
        val ops = operands
        if (ops.size < 2) return
        val oneLayer = ops.all { it.objectId != null } && ops.all { it.layer === ops[0].layer }
        val taken = ops.mapNotNull { take(it, scale = !oneLayer) }
        if (taken.size != ops.size) return
        val inputs = taken.map { it.input }
        busy = true
        job = controller.scope.launch {
            var handedOver = false
            try {
                val work = async(computeDispatcher) {
                    try {
                        PathfinderOps.run(op, inputs)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Throwable) {
                        PathfinderResult.Failed
                    }
                }
                val quick = withTimeoutOrNull(BUSY_AFTER_MS) { work.await() }
                if (quick == null) {
                    // Slow: the busy overlay (with Stop) until it ends.
                    handedOver = true
                    controller.runBusy(WORKING, onCancel = { work.cancel() }) {
                        try {
                            val r = work.await()
                            busy = false
                            finish(op, taken, oneLayer, r)
                        } finally {
                            busy = false
                        }
                    }
                } else {
                    busy = false
                    finish(op, taken, oneLayer, quick)
                }
            } finally {
                if (!handedOver) busy = false
            }
        }
    }

    /** [p]'s snapshot and operand; [scale]: its layer's opacity goes into its style (it moves to a new layer). */
    private fun take(p: Pick, scale: Boolean): Taken? {
        val layer = p.layer
        val opacity = layer.opacity
        val id = p.objectId
        if (id == null) {
            val shape = ShapeCodec.decode(layer.shapeData) ?: return null
            return Taken(p, null, layer.shapeData, opacity, PathfinderOperand(PathConvert.region(shape), PathfinderStyle.of(shape, opacity)))
        }
        val content = layer.vector ?: return null
        val f = if (scale) opacity else 1f
        val input = when (val o = content.byId(id)) {
            is VPath -> PathfinderOperand(PathConvert.region(o), PathfinderStyle.of(o, f))
            is VShape -> PathfinderOperand(PathConvert.region(o.shape), PathfinderStyle.of(o, f))
            else -> return null
        }
        return Taken(p, content, null, opacity, input)
    }

    private fun finish(op: PathfinderOp, taken: List<Taken>, oneLayer: Boolean, result: PathfinderResult) {
        // The user moved on to another tool: the result is not wanted any more.
        if (controller.currentTool !== this) return
        // An edit made meanwhile that still renders lands now, so the check below sees it.
        controller.vectors.flushPending()
        if (!taken.all(::unchanged)) { controller.toast(CHANGED); return }
        when (result) {
            PathfinderResult.TooMany -> controller.toast(PathfinderLabels.TOO_MANY)
            PathfinderResult.Failed -> controller.toast(FAILED)
            PathfinderResult.Empty -> controller.toast(NOTHING_LEFT)
            is PathfinderResult.Done -> {
                val ok = if (oneLayer) landInLayer(op, taken, result.objects) else landInNewLayer(op, taken, result.objects)
                if (ok) clearPicks()
            }
        }
    }

    private fun unchanged(t: Taken): Boolean {
        val layer = t.pick.layer
        if (!eligible(t.pick) || layer.opacity != t.opacity) return false
        return if (t.pick.objectId == null) layer.shapeData == t.shapeData else layer.vector === t.content
    }

    /** All operands are objects of one vector layer: the results take the anchor's place there (one step). */
    private fun landInLayer(op: PathfinderOp, taken: List<Taken>, results: List<VPath>): Boolean {
        val layer = taken[0].pick.layer
        val anchor = (if (op == PathfinderOp.MINUS_FRONT) taken.first() else taken.last()).pick.objectId ?: return false
        val others = taken.mapNotNull { it.pick.objectId }.filter { it != anchor }.toSet()
        val label = op.historyLabel
        var ok = false
        controller.groupUndo(label) {
            // Read inside the step: whatever the step lets land first is in the content replaced.
            val content = layer.vector ?: return@groupUndo
            controller.vectors.update(layer, content.replaced(mapOf(anchor to results)).without(others), label) { ok = it }
        }
        return ok
    }

    /**
     * Operands on several layers, or a shape layer among them: the results go into a new vector
     * layer above the top operand's layer, the operand objects leave their layers and the operand
     * shape layers are deleted, as ONE step. When a part is refused (no memory), the parts done
     * so far are taken back and nothing is recorded.
     */
    private fun landInNewLayer(op: PathfinderOp, taken: List<Taken>, results: List<VPath>): Boolean {
        val c = controller
        val doc = c.doc
        val shapeLayers = taken.filter { it.pick.objectId == null }.map { it.pick.layer }
        // The operand shape layers go: only the layers the document ends with count.
        if (!roomForResult(c.effectiveLayerCount, shapeLayers.size, c.maxLayers)) { c.toast(c.layerLimitMessage()); return false }
        val bitmap = try {
            BitmapUtils.createLayerBitmap(doc.width, doc.height)
        } catch (e: OutOfMemoryError) {
            c.toast(NO_MEMORY); return false
        }
        val top = taken.maxBy { doc.indexOf(it.pick.layer) }.pick.layer
        val layer = Layer(doc.newLayerId(), PathfinderLabels.resultLayer(nextNumber()), bitmap).also { it.vector = VectorContent.EMPTY }
        val removals = taken.filter { it.pick.objectId != null }.groupBy({ it.pick.layer }, { it.pick.objectId!! })
        val label = op.historyLabel
        var ok = false
        c.groupUndo(label) {
            val mark = c.undoManager.undoCount
            ok = run {
                // Refused before anything was recorded: the new bitmap belongs to no one.
                if (!c.structure.insert(layer, c.structure.above(top), label)) { bitmap.recycle(); return@run false }
                if (c.vectors.addObjects(layer, results, label).size != results.size) return@run false
                for ((l, ids) in removals) {
                    val content = l.vector ?: return@run false
                    var done = false
                    c.vectors.update(l, content.without(ids.toSet()), label) { done = it }
                    if (!done) return@run false
                }
                for (l in shapeLayers) if (!c.structure.delete(l, keepChildren = false, label)) return@run false
                true
            }
            if (!ok) {
                for (a in c.undoManager.takeSince(mark).asReversed()) { a.undo(c); a.dispose() }
                c.toast(NO_MEMORY)
            }
        }
        if (ok) c.selectLayer(layer)
        return ok
    }

    /** The N of the next "Pathfinder N" layer: one more than the highest in the document. */
    private fun nextNumber(): Int {
        val prefix = PathfinderLabels.resultLayer(0).removeSuffix("0")
        val used = controller.doc.layers.mapNotNull { l -> l.name.takeIf { it.startsWith(prefix) }?.removePrefix(prefix)?.toIntOrNull() }
        return (used.maxOrNull() ?: 0) + 1
    }

    // ------------------------------------------------------------------ overlay

    /** A pick's outline (document px), kept while its source is the same instance. */
    private class Outline(val source: Any, val path: Path, val bounds: RectF)

    private val outlines = HashMap<Pick, Outline>()
    private val screenPath = Path()
    private val boxPath = Path()
    private val shadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = SHADOW }
    private val solid = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val dashed = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private var dashDensity = 0f

    /** Each operand's outline (accent over a dark halo) and its dashed box. */
    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val ops = operands
        outlines.keys.retainAll(ops.toSet())
        if (ops.isEmpty()) return
        if (dashDensity != t.density) {
            dashDensity = t.density
            dashed.pathEffect = DashPathEffect(floatArrayOf(t.dp(5f), t.dp(4f)), 0f)
        }
        shadow.strokeWidth = t.dp(4f)
        solid.strokeWidth = t.dp(2f)
        dashed.strokeWidth = t.dp(1.25f)
        for (p in ops) {
            val o = outlineOf(p) ?: continue
            o.path.transform(t.matrix, screenPath)
            canvas.drawPath(screenPath, shadow)
            canvas.drawPath(screenPath, solid)
            boxPath.rewind()
            boxPath.addRect(o.bounds, Path.Direction.CW)
            boxPath.transform(t.matrix, screenPath)
            canvas.drawPath(screenPath, dashed)
        }
    }

    private fun outlineOf(p: Pick): Outline? {
        val id = p.objectId
        val source: Any = if (id == null) p.layer.shapeData ?: return null else p.layer.vector?.byId(id) ?: return null
        outlines[p]?.takeIf { it.source === source }?.let { return it }
        val path = when (source) {
            is VObject -> PathConvert.region(source)
            else -> ShapeCodec.decode(p.layer.shapeData)?.let { PathConvert.region(it) }
        } ?: return null
        val bounds = RectF()
        @Suppress("DEPRECATION") path.computeBounds(bounds, true)
        return Outline(source, path, bounds).also { outlines[p] = it }
    }

    companion object {
        /** A finger that moves farther than this is a drag (which does nothing), as in the other tools. */
        const val TAP_SLOP_DP = 16f

        /** How far from an object a tap still picks it, as when tapping objects to lift them. */
        const val TAP_TOLERANCE_DP = 10f

        /** "Working…" shows when an operation takes longer than this (ms). */
        const val BUSY_AFTER_MS = 300L

        /** The busy overlay's text. */
        const val WORKING = "Working…"

        /** Intersect or Crop of shapes that don't overlap. */
        const val NOTHING_LEFT = "Nothing is left: the shapes don't overlap"

        /** Skia's PathOps gave up. */
        const val FAILED = "Pathfinder couldn't combine these shapes"

        /** An operand was edited (undo, another window) while the operation ran. */
        const val CHANGED = "The objects changed: try again"

        const val NO_MEMORY = "Not enough memory for Pathfinder"

        /** The strip's count of picked operands. */
        fun picked(n: Int) = if (n == 1) "1 object" else "$n objects"

        /**
         * Whether a new result layer fits the layer limit: the document ends with one layer more
         * than its [effectiveLayers] less the [removedShapeLayers] operand shape layers deleted
         * (vector layers stay, even when emptied), and that must not pass [maxLayers]. So two
         * shape layers combine even when the document is at its limit.
         */
        internal fun roomForResult(effectiveLayers: Int, removedShapeLayers: Int, maxLayers: Int): Boolean =
            effectiveLayers + 1 - removedShapeLayers <= maxLayers

        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val SHADOW = 0x99000000.toInt()
    }
}
