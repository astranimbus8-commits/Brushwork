package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.DeferredStep
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.PaperGrain
import com.brushwork.paint.brush.StrokeHook
import com.brushwork.paint.brush.StrokeInfo
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.transform.ObjectLiftProvider
import com.brushwork.paint.tools.vector.BrushStrokePreview
import com.brushwork.paint.vector.draw.VectorStrokeCapture
import com.brushwork.paint.vector.edit.VectorEditSession
import com.brushwork.paint.vector.geom.ContentDiff
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.geom.StrokeHits
import com.brushwork.paint.vector.geom.TileSet
import com.brushwork.paint.vector.lift.VectorLift
import com.brushwork.paint.vector.render.RenderCache
import com.brushwork.paint.vector.render.VectorLayerRenderer
import com.brushwork.paint.vector.select.VectorObjectSelection
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineDispatcher
import kotlinx.coroutines.Deferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.async
import kotlinx.coroutines.delay
import kotlinx.coroutines.ensureActive
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.ceil
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * The vector layer service of one editor (`EditorController.vectors`, v1.5 §5.4 and §6 A1). Its
 * API is frozen; this is A1's implementation.
 *
 * Every edit keeps a vector layer's pixels (its render cache) equal to the rendering of its
 * [VectorContent], changing both in ONE undo step on the main thread (I1, I2): pixel tiles plus a
 * [LayerDataAction]. Undo and redo swap tiles and data and never re-render.
 *
 * **Dirty regions** are sets of whole [VectorLayerRenderer.TILE] grid tiles ([TileSet]): the
 * tiles each changed object can paint (a stroke's tiles along its points), so deleting two
 * distant objects re-renders their tiles only. Re-rendering whole grid tiles gives exactly a full
 * render's pixels there. Objects are found through a 128 px spatial grid ([ObjectIndex]).
 *
 * **Sync or async** (I3): the cost of a re-render is estimated ([VectorLayerRenderer.estimateUnits]
 * × the measured speed); up to [SYNC_BUDGET_MS] it runs on the main thread, beyond it on a
 * background worker (its own [TipCache], [RenderCache] and [com.brushwork.paint.brush.StrokeRaster])
 * from the immutable content, into patches of at most [MAX_PIECE]² px. The patch is applied on the
 * main thread together with the data, as one step, only if the layer is still exactly as it was
 * (content instance, pixels version, bitmap); otherwise the edit is re-based onto what the layer
 * holds now ([ContentDiff.merge3]) and rendered again. While a render runs ("pending"), the
 * service registers a [DeferredStep]: any other edit, undo or redo first completes it (waits for
 * the worker and applies it), so steps never interleave. A render expected to take more than
 * [BUSY_AFTER_MS] shows the busy overlay ("Rendering vectors…") at once, a shorter one after that
 * delay if it is still running. Edits made inside another step (`editScope` depth > 0: groupUndo,
 * undoStepNamed...) always render synchronously, so they stay in that step.
 *
 * **Pure moves** ([ShiftHint]): a whole-pixel translation of objects that no other object
 * touches shifts their cache pixels instead of re-rendering them (see [update]).
 *
 * **Live arrays** (v1.7, I14): on a layer with a [LayerArray] every diff and render uses the
 * EXPANDED content ([ArrayDraw.effectiveVector]: the copies, then the source objects), so
 * [ContentDiff] gives the copies' tiles too; the layer's DATA stays the source objects. A live
 * stroke's [appendData] re-renders the copies in the same step, and [updateArray] changes the
 * array itself. Without an array the expanded content is the content (the same instance): every
 * render is exactly v1.6's.
 */
class VectorLayers internal constructor(private val c: EditorController) {

    /** The main thread's brush tips and prepared objects for re-renders. */
    private val tips = TipCache(8L shl 20)
    private val renderCache = RenderCache()

    /** How re-renders are scheduled. */
    internal enum class Policy {
        /** By the cost estimate (the app). */
        AUTO,
        /** Always on the main thread (unit tests by default, so tests stay deterministic). */
        SYNC,
        /** In the background whenever possible (tests of the async path). */
        ASYNC,
    }

    /**
     * The scheduling policy: [Policy.AUTO] in the app, [Policy.SYNC] under Robolectric (a test
     * that exercises background renders sets [Policy.ASYNC] or [Policy.AUTO]).
     */
    internal var policy: Policy = if (isTestRuntime) Policy.SYNC else Policy.AUTO

    /** Main-thread time a re-render may take (ms) before it goes to the background. */
    internal var syncBudgetMs: Double = SYNC_BUDGET_MS

    /** Nanoseconds per cost unit on this device (measured on the main thread's larger renders). */
    internal var nsPerUnit: Double = initialSpeed()

    // ------------------------------------------------------------------ object edits

    /**
     * New topmost objects: drawn over the cache in their tiles (no re-render of what is under
     * them), tiles + LayerDataAction, one step. Returns the new ids, or an empty list for
     * non-vector, locked, hidden or missing layers and on OOM. Objects entirely off the canvas
     * make a data-only step. A large batch (an imported drawing) renders in the background; the
     * ids are returned at once.
     */
    fun addObjects(layer: Layer, objects: List<VObject>, label: String): List<Long> {
        if (disposed || objects.isEmpty() || c.doc.indexOf(layer) < 0) return emptyList()
        flushPending()
        val current = layer.vector ?: return emptyList()
        if (!usable(layer)) return emptyList()
        val (content, ids) = current.plus(objects)
        if (layer.array != null) {
            // v1.7 (I14): the new objects' copies sit under the source, not on top of the cache:
            // the changed tiles of the expanded content are re-rendered ([update]).
            var applied = false
            updateInternal(layer, content, label, null, null, null, { applied = it }, 0)
            val rendering = pending?.let { it.layer === layer && it.after === content } == true
            return if ((applied && layer.vector === content) || rendering) ids else emptyList()
        }
        val added = content.objects.subList(content.objects.size - objects.size, content.objects.size).toList()
        val tiles = TileSet(c.doc.width, c.doc.height, VectorLayerRenderer.TILE)
        val index = ObjectIndex.of(content)
        for (i in content.objects.size - objects.size until content.objects.size) tiles.addObject(content.objects[i], index.bounds(i))
        val rects = tiles.rects()
        val addedContent = VectorContent(objects = added)
        if (rects.isNotEmpty() && goAsync(VectorLayerRenderer.estimateUnits(addedContent, rects))) {
            schedule(layer, current, content, label, rects, added, beforeApply = null)
            return ids
        }
        val ok = timed(addedContent, rects) {
            applyRender(layer, content, label, rects, null) { canvas ->
                for (r in rects) {
                    canvas.save()
                    canvas.clipRect(r)
                    VectorLayerRenderer.renderWith(canvas, addedContent, r, emptySet(), tips, docBounds(), renderCache, null)
                    canvas.restore()
                }
            }
        }
        return if (ok && layer.vector === content) ids else emptyList()
    }

    /**
     * Data-only append; the caller already committed the pixels inside keepLayerData (live brush
     * stroke). Call inside groupUndo. Only for vector layers (a raster layer's pixels are not a
     * rendering of objects): returns no ids otherwise.
     */
    fun appendData(layer: Layer, objects: List<VObject>, label: String): List<Long> {
        if (disposed || objects.isEmpty() || c.doc.indexOf(layer) < 0) return emptyList()
        flushPending()
        val before = layer.dataSnapshot()
        val current = before.vector ?: return emptyList()
        val (content, ids) = current.plus(objects)
        val array = before.array
        if (array != null) {
            // v1.7 (I14): the live pixels are the new objects at identity, on top (where the
            // source's new objects are in the expanded content). Their copies, and every copy
            // when the source's bounds moved, are rendered here, in the same step.
            val after = view(content, array)
            val live = view(current, array).let { e -> e.copy(objects = e.objects + content.objects.takeLast(objects.size)) }
            val rects = ContentDiff.changedTiles(live, after, c.doc.width, c.doc.height, VectorLayerRenderer.TILE).rects()
            applyRender(layer, content, label, rects, null, renderInto(after, rects))
            return if (layer.vector === content) ids else emptyList()
        }
        c.setLayerData(layer, before.copy(vector = content), label)
        // Refused (locked or hidden layer): nothing was added.
        return if (layer.vector === content) ids else emptyList()
    }

    /**
     * New content; re-renders [dirty] (document px; null = the tiles of everything that changed:
     * objects added, removed, replaced or moved in z-order, old and new versions), grown to whole
     * grid tiles. Sync or async; data and pixels are applied together, as ONE step (I1). [onDone]
     * tells whether it was applied (true also when [after] equals the content: then nothing is
     * recorded and the layer keeps its instance; false for a layer that is not a vector layer, is
     * gone, locked or hidden). A synchronous update calls [onDone] before returning.
     *
     * [after] is taken as computed from the layer's content at the time of the call: when an
     * earlier edit of the layer was still rendering, that edit is completed first and [after] is
     * re-based onto its result ([ContentDiff.merge3]), so neither edit is lost.
     *
     * [shift]: the objects [ShiftHint.ids] moved by whole pixels ([ShiftHint.dx], [ShiftHint.dy])
     * and nothing else changed. Their cache pixels are moved instead of re-rendered when that is
     * equivalent: no other object's paint bounds reach the box around the moved objects' old or
     * new paint bounds, no moved object paints with paper grain (grain is anchored to the
     * document) unless the move is a multiple of [PaperGrain.SIZE] px, and every pixel that lands
     * on the canvas was on it.
     * The result is the old cache shifted exactly (it can differ from a re-render only at the
     * anti-aliased edges where the renderer's tile grid cuts a path). Otherwise it is re-rendered
     * (also when [dirty] is given).
     */
    fun update(
        layer: Layer,
        after: VectorContent,
        label: String,
        dirty: List<Rect>? = null,
        /** A pure whole-pixel move of [ShiftHint.ids]: cache tiles shifted, no re-render. */
        shift: ShiftHint? = null,
        onDone: (applied: Boolean) -> Unit = {},
    ) = updateInternal(layer, after, label, dirty, shift, null, onDone, 0)

    /**
     * v1.7 (item 3, I14): sets [layer]'s live array to [array] (null removes it), and its content
     * to [after] when given, re-rendering the tiles where the expanded content
     * ([ArrayDraw.effectiveVector]) changed: the old and the new copies' rectangles. Data and
     * pixels change together, as ONE step [label]; sync or async and re-based like [update]
     * ([onDone] as there). For area E's `ArrayOps` on vector layers.
     */
    fun updateArray(layer: Layer, array: LayerArray?, label: String, after: VectorContent? = null, onDone: (applied: Boolean) -> Unit = {}) {
        val content = after ?: layer.vector
        if (content == null) { onDone(false); return }
        updateInternal(layer, content, label, null, null, null, onDone, 0, ArrayChange(array))
    }

    /** v1.7: the array an edit sets ([ArrayChange.array]; null = removed). No change = the layer's array is kept. */
    internal class ArrayChange(val array: LayerArray?)

    /**
     * [update] for an edit session's commit: [beforeApply] runs right before the pixels change
     * (sync: before the render; async: when the patch is applied, so the session's preview stays
     * up meanwhile), or before [onDone] when nothing is applied.
     *
     * v1.7 (I14): the before and after content are diffed and rendered EXPANDED
     * ([ArrayDraw.effectiveVector] under the layer's array, and under [newArray]'s when given:
     * the array is then changed in the same step). On an arrayed layer [dirty] only adds tiles
     * (it covers the source, not the copies) and [shift] is ignored (moving cache pixels would
     * leave the copies behind).
     */
    internal fun updateInternal(
        layer: Layer,
        after: VectorContent,
        label: String,
        dirty: List<Rect>?,
        shift: ShiftHint?,
        beforeApply: (() -> Unit)?,
        onDone: (Boolean) -> Unit,
        attempt: Int,
        newArray: ArrayChange? = null,
    ) {
        fun done(ok: Boolean) { beforeApply?.invoke(); onDone(ok) }
        val callerBase = layer.vector
        if (disposed || callerBase == null || c.doc.indexOf(layer) < 0) { done(false); return }
        var target = after
        val p = pending
        if (p != null) {
            flushPending()
            if (p.layer === layer) {
                // [after] was computed from the content before that edit landed: re-base it.
                val now = layer.vector
                if (now == null || c.doc.indexOf(layer) < 0) { done(false); return }
                if (now !== callerBase) target = ContentDiff.merge3(callerBase, after, now)
            }
        }
        val base = layer.vector ?: run { done(false); return }
        val arrayBefore = layer.array
        val arrayAfter = if (newArray != null) newArray.array else arrayBefore
        // Nothing changes (also an equal copy): no re-render and no step.
        if ((target === base || target == base) && arrayAfter == arrayBefore) { done(true); return }
        if (!usable(layer)) { done(false); return }
        val arrayed = arrayBefore != null || arrayAfter != null
        if (!arrayed && shift != null && dirty == null && tryShift(layer, base, target, label, shift, beforeApply)) {
            onDone(layer.vector === target)
            return
        }
        // What the cache shows before and after (the content itself without an array).
        val baseView = view(base, arrayBefore)
        val targetView = view(target, arrayAfter)
        val tiles = if (dirty != null && !arrayed) {
            TileSet(c.doc.width, c.doc.height, VectorLayerRenderer.TILE).also { s -> dirty.forEach { s.addRect(it) } }
        } else {
            ContentDiff.changedTiles(baseView, targetView, c.doc.width, c.doc.height, VectorLayerRenderer.TILE).also { s -> dirty?.forEach { s.addRect(it) } }
        }
        val rects = tiles.rects()
        if (rects.isNotEmpty() && attempt < MAX_ATTEMPTS && goAsync(VectorLayerRenderer.estimateUnits(targetView, rects))) {
            schedule(layer, base, target, label, rects, null, beforeApply, onDone, attempt, targetView, newArray)
            return
        }
        beforeApply?.invoke()
        val ok = timed(targetView, rects) { applyRender(layer, target, label, rects, newArray, renderInto(targetView, rects)) }
        onDone(ok && layer.vector === target && (newArray == null || layer.array == newArray.array))
    }

    /**
     * v1.7 (I14): what a vector layer's cache shows for [content] under [array]:
     * [ArrayDraw.effectiveVector] (the copies, then the source objects), or [content] itself, the
     * same instance, without an array (so every v1.6 diff and render is unchanged).
     */
    private fun view(content: VectorContent, array: LayerArray?): VectorContent =
        if (array == null) content else ArrayDraw.effectiveVector(LayerData(vector = content, array = array)) ?: content

    /** A pure whole-pixel translation of the objects [ids] by ([dx], [dy]) document px. */
    data class ShiftHint(val ids: Set<Long>, val dx: Int, val dy: Int)

    private var rendering by mutableStateOf(false)

    /** True while a render runs in the background (Compose state). */
    val isRendering: Boolean get() = rendering

    /** Everything [obj] can paint (document px, including the brush radius). */
    fun paintBounds(obj: VObject): RectF = VectorOps.bounds(obj)

    /**
     * The topmost object of [layer] under [p] within [tolDoc] px; [below] = only objects under
     * that id, and when none is, from the top again (repeated taps cycle through overlaps).
     * Only the objects the spatial index finds near [p] are tested.
     */
    fun hitTest(layer: Layer, p: Vec2, tolDoc: Float, below: Long? = null): VObject? {
        val content = layer.vector ?: return null
        val objs = content.objects
        if (objs.isEmpty()) return null
        val index = ObjectIndex.of(content)
        // Invisible paths have control bounds only: search a little wider than the tolerance.
        val near = index.queryPoint(p.x, p.y, (if (tolDoc.isFinite()) max(0f, tolDoc) else 0f) + 1f)
        if (near.isEmpty()) return null
        val from = below?.let { index.indexOfId(it) } ?: -1
        for (k in near.indices.reversed()) {
            val i = near[k]
            if (from >= 0 && i >= from) continue
            if (VectorOps.hit(objs[i], p, tolDoc)) return objs[i]
        }
        if (from >= 0) {
            for (k in near.indices.reversed()) {
                val i = near[k]
                if (i < from) break
                if (VectorOps.hit(objs[i], p, tolDoc)) return objs[i]
            }
        }
        return null
    }

    /** Ids of the objects of [layer] that [sel] touches. */
    fun touching(layer: Layer, sel: Selection): Set<Long> {
        val content = layer.vector ?: return emptySet()
        return VectorOps.touching(content, sel)
    }

    // ------------------------------------------------------------------ edit sessions

    /** A background preparation of an edit session (one at a time). */
    private var preparing: PreparingEdit? = null

    private class PreparingEdit(val onReady: (VectorEditSession?) -> Unit) {
        /** The main-thread part (waits, then installs). */
        var job: Job? = null
        /** The worker's render of the hole and floating bitmaps (null while waiting for a pending render). */
        var render: Job? = null
        var done = false
            private set
        /** Completes when the preparation ends (installed, refused, cancelled). */
        val completion = CompletableDeferred<Unit>()

        fun markDone() {
            done = true
            completion.complete(Unit)
        }
    }

    /**
     * Prepares an edit preview of the objects [ids] (hole + floating) and installs it as the
     * render override (with no [VectorEditSession.inner]: a painting tool's live stroke is
     * adopted later through [VectorEditSession.adoptInner]); [onReady] gets null when refused
     * (not a vector layer, none of the ids exist, locked or hidden layer, no memory). Ids that no
     * longer exist are left out of the session's [VectorEditSession.ids].
     *
     * Lifting every object uses a cropped copy of the cache as the floating bitmap and an empty
     * hole; the objects reaching past the canvas are rendered only in the bands past its edges,
     * so their off-canvas parts show in the preview. Otherwise the hole (the other objects within
     * the edited ones' grid tiles) and the floating bitmap (the edited objects, at most 2048 px) are rendered: on
     * the main thread when cheap ([onReady] runs before this returns), else in the background
     * ([onReady] runs later on the main thread; a newer request answers an older one with null;
     * a long preparation shows the busy overlay "Rendering vectors…", whose Stop answers null).
     * Memory guard: hole + floating within a heap / 8 budget (else both are rendered smaller).
     *
     * While an edit still renders in the background, the session is prepared once that edit has
     * landed ([onReady] runs later): the main thread never waits for the worker here (the
     * Transform tool lifts again right after a commit, and on activation).
     */
    fun beginEdit(layer: Layer, ids: Set<Long>, onReady: (VectorEditSession?) -> Unit) {
        if (disposed) { onReady(null); return }
        preparing?.let { old -> cancelPreparing(old) }
        val p = pending
        if (p != null && !flushing) {
            val prep = PreparingEdit(onReady)
            preparing = prep
            updateRendering()
            // (Dispatchers.Main, not immediate: the render may land inside another operation
            // — an undo, an edit's flush — and the session must be prepared after it, not in it.)
            prep.job = c.scope.launch(Dispatchers.Main) {
                p.completion.await()
                if (prep.done) return@launch
                prep.markDone()
                if (preparing === prep) preparing = null
                updateRendering()
                // Another render may have started meanwhile: then this waits for it too.
                beginEdit(layer, ids, onReady)
            }
            return
        }
        beginEditAttempt(layer, ids, onReady, 0)
    }

    private fun cancelPreparing(p: PreparingEdit) {
        if (p.done) return
        p.markDone()
        p.job?.cancel()
        p.render?.cancel()
        if (preparing === p) preparing = null
        updateRendering()
        p.onReady(null)
    }

    /** [isRendering]: a background render or an edit preparation is running. */
    private fun updateRendering() {
        rendering = pending != null || preparing != null
    }

    private class EditPlan(
        val present: Set<Long>,
        val edited: List<VObject>,
        val others: VectorContent,
        val all: Boolean,
        val floatingRect: Rect,
        val holeRect: Rect,
        val fScale: Float,
        val hScale: Float,
        /**
         * Every object lifted ([all]): those whose paint reaches past the canvas. The floating
         * bitmap is a copy of the cache (exactly every object's rendering on the canvas), and
         * these are rendered past its edges, so their off-canvas parts show in the Transform
         * preview too.
         */
        val overflow: VectorContent,
        /**
         * The parts of the floating rect past the canvas (disjoint, document px; empty unless
         * [overflow] has objects): only these are rendered, so a stroke that merely reaches past
         * an edge costs its dabs there, not its whole replay.
         */
        val overflowBands: List<Rect>,
        /** Where the floating render of some objects cuts brush dabs: the document, or (reaching past it) nowhere but the floating rect. */
        val floatingCut: Rect?,
    )

    private fun beginEditAttempt(layer: Layer, ids: Set<Long>, onReady: (VectorEditSession?) -> Unit, attempt: Int) {
        val content = layer.vector
        if (content == null || c.doc.indexOf(layer) < 0 || ids.isEmpty()) { onReady(null); return }
        val present = ids.filterTo(LinkedHashSet()) { content.byId(it) != null }
        if (present.isEmpty() || !usable(layer)) { onReady(null); return }
        val plan = planEdit(content, present, layer.array)
        val units = editUnits(plan)
        if ((plan.all && plan.overflowBands.isEmpty()) || !goAsyncUnits(units)) {
            val parts = try {
                renderEdit(layer.bitmap, plan, tips, renderCache, null) { true }
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory to edit these objects")
                onReady(null)
                return
            }
            install(layer, plan, parts.first, parts.second, onReady)
            return
        }
        // Every object lifted: the cache part of the floating bitmap is copied here, on the main
        // thread (the worker never reads the layer's pixels).
        val base = if (plan.all) {
            try {
                floatingFromCache(layer.bitmap, plan)
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory to edit these objects")
                onReady(null)
                return
            }
        } else null
        // In the background, from the immutable content; installed only if nothing changed.
        val prep = PreparingEdit(onReady)
        preparing = prep
        updateRendering()
        val version = layer.contentVersion
        val bitmap = layer.bitmap
        val job = c.scope.async(worker.dispatcher) {
            val ctx = coroutineContext
            val r = renderEdit(null, plan, worker.tips, worker.cache, base) { ctx.isActive }
            if (!ctx.isActive) {
                recycle(r.first, r.second)
                throw CancellationException("Edit preparation cancelled")
            }
            r
        }
        // (Cancelled before it ran, or failed: the copied cache part is not needed any more.)
        if (base != null) job.invokeOnCompletion { cause -> if (cause != null) recycle(base) }
        prep.render = job
        // (Dispatched, like a pending render's landing: a background preparation never answers
        // inside this call, even when the worker is already done.)
        prep.job = c.scope.launch(Dispatchers.Main) {
            val parts = try { job.await() } catch (e: CancellationException) { null } catch (e: Throwable) { null }
            if (prep.done) { parts?.let { recycle(it.first, it.second) }; return@launch }
            prep.markDone()
            if (preparing === prep) preparing = null
            updateRendering()
            if (parts == null) {
                c.toast("Not enough memory to edit these objects")
                onReady(null)
                return@launch
            }
            val fresh = layer.vector === content && layer.contentVersion == version && layer.bitmap === bitmap && c.doc.indexOf(layer) >= 0
            if (!fresh) {
                recycle(parts.first, parts.second)
                if (attempt < MAX_ATTEMPTS) beginEditAttempt(layer, ids, onReady, attempt + 1) else onReady(null)
                return@launch
            }
            install(layer, plan, parts.first, parts.second, onReady)
        }
        // Feedback for a long preparation (the tool waits for it): the busy overlay, at once when
        // it is expected to take long, else after a short delay; Stop gives up the edit.
        if (estimateMs(units) > BUSY_AFTER_MS) showPreparing(prep) else c.scope.launch { delay(BUSY_AFTER_MS.toLong()); showPreparing(prep) }
    }

    private fun showPreparing(prep: PreparingEdit) {
        if (prep.done || c.busyMessage != null) return
        c.runBusy(BUSY_LABEL, onCancel = { cancelPreparing(prep) }) { prep.completion.await() }
    }

    private fun recycle(vararg b: Bitmap?) { for (x in b) if (x != null && !x.isRecycled) x.recycle() }

    /**
     * v1.7 (I14): on an arrayed layer ([array] non-null) the cache also shows the copies, so a
     * lift is never "every object" (a copy of the cache would carry the copies along), and the
     * hole is the EXPANDED content without the edited source objects: their copies stay in place
     * in the preview until the commit re-renders them.
     */
    private fun planEdit(content: VectorContent, present: Set<Long>, array: LayerArray?): EditPlan {
        val index = ObjectIndex.of(content)
        val edited = content.objects.filter { it.id in present }
        val all = present.size == content.objects.size && array == null
        val docW = c.doc.width
        val docH = c.doc.height
        // The edited objects' box, kept within a document's size around the canvas.
        val union = RectF()
        for ((i, o) in content.objects.withIndex()) if (o.id in present) { val b = index.bounds(i); if (!b.isEmpty) union.union(b) }
        val floatingRect = roundOut(union)
        if (!floatingRect.intersect(-docW, -docH, 2 * docW, 2 * docH)) floatingRect.setEmpty()
        // On the renderer's tile grid, so the other objects re-rendered there are exactly the cache's pixels.
        val holeRect = Rect(floatingRect)
        if (!holeRect.intersect(0, 0, docW, docH)) holeRect.setEmpty()
        holeRect.set(gridRect(holeRect))

        val budget = Runtime.getRuntime().maxMemory() / 8
        val fw = floatingRect.width().toLong()
        val fh = floatingRect.height().toLong()
        val holeArea = if (all) 0L else holeRect.width().toLong() * holeRect.height()
        var fScale = if (fw <= 0 || fh <= 0) 1f else min(1f, MAX_FLOATING / max(fw, fh).toFloat())
        var hScale = 1f
        val need = 4L * (holeArea + (fw * fh * fScale * fScale).toLong())
        if (need > budget && need > 0) {
            val s = sqrt(budget.toDouble() / need).toFloat()
            fScale *= s
            hScale = s
        }
        val docRect = Rect(0, 0, docW, docH)
        val overflow = if (!all) emptyList() else content.objects.filterIndexed { i, _ ->
            val b = index.bounds(i)
            !b.isEmpty && !(b.left >= 0f && b.top >= 0f && b.right <= docW && b.bottom <= docH)
        }
        return EditPlan(
            present, edited, view(content, array).without(present), all, floatingRect, holeRect, fScale, hScale,
            overflow = VectorContent(objects = overflow),
            overflowBands = if (overflow.isEmpty()) emptyList() else outside(floatingRect, docRect),
            // On the canvas, dabs are cut at the document exactly as in the cache; past it they
            // are drawn whole, so a stroke reaching off the canvas shows there while it is moved.
            floatingCut = if (docRect.contains(floatingRect)) docRect else null,
        )
    }

    /** Cost units of preparing [plan] (every object lifted: only what is drawn past the canvas). */
    private fun editUnits(plan: EditPlan): Double {
        if (plan.all) return VectorLayerRenderer.estimateUnits(plan.overflow, plan.overflowBands)
        var u = VectorLayerRenderer.estimateUnits(VectorContent(objects = plan.edited), plan.floatingRect)
        if (!plan.holeRect.isEmpty) u += VectorLayerRenderer.estimateUnits(plan.others, plan.holeRect)
        return u
    }

    /** A new floating bitmap for [plan] and a canvas drawing into it in document px. */
    private fun newFloating(plan: EditPlan): Pair<Bitmap, Canvas> {
        val fr = plan.floatingRect
        val b = BitmapUtils.createLayerBitmap(max(1, ceil(fr.width() * plan.fScale).toInt()), max(1, ceil(fr.height() * plan.fScale).toInt()))
        return b to documentCanvas(b, plan)
    }

    private fun documentCanvas(b: Bitmap, plan: EditPlan): Canvas = Canvas(b).also { cv ->
        cv.scale(plan.fScale, plan.fScale)
        cv.translate(-plan.floatingRect.left.toFloat(), -plan.floatingRect.top.toFloat())
    }

    /** The floating bitmap of an every-object lift with the cache copied in (main thread). */
    private fun floatingFromCache(cache: Bitmap, plan: EditPlan): Bitmap? {
        if (plan.floatingRect.isEmpty) return null
        val (b, cv) = newFloating(plan)
        // Every object is lifted: on the canvas, the cache is exactly their rendering.
        cv.drawBitmap(cache, 0f, 0f, if (plan.fScale == 1f) null else Paint(Paint.FILTER_BITMAP_FLAG))
        return b
    }

    /**
     * Renders an edit session's floating and hole bitmaps. An every-object lift copies [cache]
     * (the layer's bitmap, read on the main thread only), or takes [prefilled] (that copy, made on
     * the main thread for a background preparation), and draws the [EditPlan.overflow] objects
     * past the canvas. [active] false stops (returns what is done, freed by the caller).
     */
    private fun renderEdit(cache: Bitmap?, plan: EditPlan, tips: TipCache, rc: RenderCache, prefilled: Bitmap?, active: () -> Boolean): Pair<Bitmap?, Bitmap?> {
        var floating: Bitmap? = prefilled
        var hole: Bitmap? = null
        val doc = docBounds()
        try {
            val fr = plan.floatingRect
            if (!fr.isEmpty) {
                if (plan.all) {
                    val b = floating ?: floatingFromCache(requireNotNull(cache) { "an every-object lift copies the cache" }, plan)?.also { floating = it }
                    if (b != null && plan.overflowBands.isNotEmpty()) {
                        // Only past the canvas (the bands exclude it): dabs are cut at each band.
                        val cv = documentCanvas(b, plan)
                        for (band in plan.overflowBands) {
                            if (!active()) break
                            VectorLayerRenderer.renderWith(cv, plan.overflow, band, emptySet(), tips, null, rc) { _, _ -> active() }
                        }
                    }
                } else {
                    val (b, cv) = newFloating(plan)
                    floating = b
                    VectorLayerRenderer.renderWith(cv, VectorContent(objects = plan.edited), fr, emptySet(), tips, plan.floatingCut, rc) { _, _ -> active() }
                }
            }
            val hr = plan.holeRect
            if (!plan.all && !hr.isEmpty && plan.others.objects.isNotEmpty() && active()) {
                val bw = max(1, ceil(hr.width() * plan.hScale).toInt())
                val bh = max(1, ceil(hr.height() * plan.hScale).toInt())
                hole = BitmapUtils.createLayerBitmap(bw, bh).also { b ->
                    val cv = Canvas(b)
                    cv.scale(plan.hScale, plan.hScale)
                    cv.translate(-hr.left.toFloat(), -hr.top.toFloat())
                    VectorLayerRenderer.renderWith(cv, plan.others, hr, emptySet(), tips, doc, rc) { _, _ -> active() }
                }
            }
        } catch (t: Throwable) {
            recycle(floating, hole)
            throw t
        }
        return floating to hole
    }

    private fun install(layer: Layer, plan: EditPlan, floating: Bitmap?, hole: Bitmap?, onReady: (VectorEditSession?) -> Unit) {
        val session = VectorEditSession(c, layer, plan.present, floating, plan.floatingRect, plan.fScale, plan.holeRect, hole, plan.hScale)
        // Installed on its own: a painting tool's live stroke is adopted by the caller
        // (VectorEditSession.adoptInner), never whatever override happens to be installed.
        c.renderOverride = session
        c.invalidateDoc(null)
        onReady(session)
    }

    // ------------------------------------------------------------------ object selection
    // Runtime only, not history; cleared when the layer goes or its ids vanish.

    private var selLayer by mutableStateOf<Layer?>(null)
    private var selIds by mutableStateOf<Set<Long>>(emptySet())

    /** The vector layer whose objects are selected (null when none is). */
    val selectedLayer: Layer?
        get() {
            c.layersVersion // re-read when layers change (deleted, rasterized)
            val l = selLayer ?: return null
            return if (l.isVectorLayer && c.doc.indexOf(l) >= 0) l else null
        }

    /**
     * The selected objects of [selectedLayer] that still exist (Compose state). While an edit of
     * that layer renders in the background, the content it is becoming counts (objects it adds,
     * e.g. a duplicate, are selectable at once; the Object bar doesn't flicker).
     */
    val selectedIds: Set<Long>
        get() {
            val ids = selIds
            if (ids.isEmpty()) return ids
            val layer = selectedLayer ?: return emptySet()
            // (Read for Compose: re-evaluated when a background render starts or lands.)
            val busy = rendering
            val content = pending?.takeIf { busy && it.layer === layer && !it.finished }?.after ?: layer.vector ?: return emptySet()
            val index = ObjectIndex.of(content)
            return if (ids.all { index.indexOfId(it) >= 0 }) ids else ids.filterTo(HashSet()) { index.indexOfId(it) >= 0 }
        }

    /** Selects the objects [ids] of [layer] (null or no ids = nothing selected). */
    fun setSelection(layer: Layer?, ids: Set<Long>) {
        if (layer == null || ids.isEmpty()) {
            selLayer = null
            selIds = emptySet()
        } else {
            selLayer = layer
            selIds = ids.toSet()
        }
        c.invalidateOverlay()
    }

    // ------------------------------------------------------------------ seams
    // Lines written by the lead, delegating to area-owned objects; A1 must keep them.

    /** Decides what a starting brush stroke does on a vector layer (A3). A pending render lands first. */
    fun strokeHook(info: StrokeInfo): StrokeHook {
        if (pending != null) flushPending()
        return VectorStrokeCapture.hookFor(c, info)
    }

    /** Lifts vector objects for the Transform tool (A2). */
    val liftProvider: ObjectLiftProvider get() = VectorLift.provider(c)

    /** Lasso / Select shape results on a vector layer select objects (A2). True when handled. */
    fun selectObjects(sel: Selection, mode: SelectionMode): Boolean =
        selectObjectsHook?.invoke(sel, mode) ?: VectorObjectSelection.select(c, sel, mode)

    /** Test seam: replaces [selectObjects]' delegate (null = the A2 object selection). */
    internal var selectObjectsHook: ((Selection, SelectionMode) -> Boolean)? = null

    /** Object selection feedback (A2). */
    fun drawOverlay(canvas: Canvas, t: ViewTransform) = VectorObjectSelection.drawOverlay(c, canvas, t)

    /**
     * The editor closes: drop every cache and background job. A pending render is abandoned and
     * its caller told so (`onDone(false)`, exactly once), as is a preparing edit session
     * (`onReady(null)`); every request after this is refused the same way.
     */
    fun dispose() {
        disposed = true
        selLayer = null
        selIds = emptySet()
        val prep = preparing?.takeIf { !it.done }
        preparing?.let { it.markDone(); it.job?.cancel(); it.render?.cancel() }
        preparing = null
        val p = pending?.takeIf { !it.finished }
        pending?.let { pd ->
            pd.finished = true
            pd.job?.cancel()
            pd.copies?.forEach { if (!it.isRecycled) it.recycle() }
            pd.completion.complete(Unit)
        }
        pending = null
        c.removeDeferredStep(deferredStep)
        rendering = false
        tips.clear()
        renderCache.clear()
        // Process-wide caches keyed by objects of this document.
        ObjectIndex.clearCache()
        ArrayDraw.clearCaches()
        StrokeHits.clear()
        VectorLayerRenderer.clearCaches()
        // Whoever waits hears it once, after the service is quiet (a callback that starts more
        // work is refused). A failing callback must not stop the editor from closing.
        if (prep != null) runCatching { prep.onReady(null) }
        if (p != null) runCatching { p.beforeApply?.invoke(); p.onDone(false) }
    }

    /** True once [dispose] ran: every request is refused (onDone(false), no ids, no session). */
    private var disposed = false

    // ------------------------------------------------------------------ background renders

    /** The background worker: one render at a time, with its own tips and prepared objects. */
    private class Worker(val dispatcher: CoroutineDispatcher) {
        val tips = TipCache(8L shl 20)
        val cache = RenderCache()
    }

    /**
     * Test seam: the background worker's dispatcher (null = one thread of Dispatchers.Default).
     * Read once, when the first background job starts.
     */
    internal var workerDispatcher: CoroutineDispatcher? = null

    private val worker by lazy { Worker(workerDispatcher ?: Dispatchers.Default.limitedParallelism(1)) }

    /** An edit whose pixels are rendering in the background (at most one at a time). */
    private inner class Pending(
        val layer: Layer,
        val base: VectorContent,
        val after: VectorContent,
        val label: String,
        val pieces: List<Rect>,
        /** New top objects drawn over [copies] of the cache (addObjects), or null: [after] re-rendered. */
        val added: List<VObject>?,
        val copies: List<Bitmap>?,
        val beforeApply: (() -> Unit)?,
        val onDone: (Boolean) -> Unit,
        val attempt: Int,
        /** v1.7 (I14): what is rendered: [after] expanded under the array ([after] itself without one). */
        val render: VectorContent,
        /** v1.7: the array the edit sets (null = the layer's is kept). */
        val newArray: ArrayChange?,
    ) {
        /** v1.7: the layer's array when the render started (another array makes the render stale). */
        val arrayBefore: LayerArray? = layer.array
        val contentVersion = layer.contentVersion
        val bitmap: Bitmap = layer.bitmap
        var job: Deferred<List<Bitmap>>? = null
        var finished = false
        val completion = CompletableDeferred<Unit>()
    }

    private var pending: Pending? = null

    /** Completes a pending render when anything else records a step (undo, redo, other edits). */
    private val deferredStep = DeferredStep { flushPending() }

    /**
     * Completes a render that is still running in the background now: waits for the worker and
     * applies its step (or re-renders when the layer changed meanwhile). Called before any other
     * edit (it is the service's [DeferredStep]), by canvas operations, and by tests.
     */
    fun flushPending() {
        val p = pending ?: return
        val pieces = try {
            runBlocking { p.job?.await() }
        } catch (e: CancellationException) {
            null
        } catch (e: Throwable) {
            null
        }
        // Whatever this completion re-runs happens now, on this thread.
        flushing = true
        try {
            finish(p, pieces)
        } finally {
            flushing = false
        }
    }

    /** True while [flushPending] applies: re-runs are synchronous then. */
    private var flushing = false

    /** True when [estimate] cost units should render in the background. */
    private fun goAsync(estimate: Double): Boolean = goAsyncUnits(estimate)

    private fun goAsyncUnits(units: Double): Boolean {
        // Synchronous: while a render completes, inside another step (it must land in that
        // step), when the editor is closing, and under another busy operation's overlay (e.g.
        // "Saving…" while closing commits the tool's pending work: a background render would be
        // abandoned unsaved when the editor goes).
        if (flushing || pending != null || c.editDepth != 0 || !c.scope.isActive || c.busyMessage != null) return false
        return when (policy) {
            Policy.SYNC -> false
            Policy.ASYNC -> true
            Policy.AUTO -> estimateMs(units) > syncBudgetMs
        }
    }

    private fun estimateMs(units: Double): Double = units * nsPerUnit / 1e6

    /** Runs a synchronous render [block], measuring this device's speed on larger ones. */
    private inline fun <T> timed(content: VectorContent, rects: List<Rect>, block: () -> T): T {
        val t0 = System.nanoTime()
        val r = block()
        val dt = System.nanoTime() - t0
        if (policy == Policy.AUTO && rects.isNotEmpty()) {
            val units = VectorLayerRenderer.estimateUnits(content, rects)
            if (units >= MIN_MEASURED_UNITS && dt > 0) {
                val sample = (dt / units).coerceIn(MIN_NS_PER_UNIT, MAX_NS_PER_UNIT)
                nsPerUnit += (sample - nsPerUnit) * SPEED_SMOOTHING
            }
        }
        return r
    }

    /** Starts a background render of [rects] of [after] (or of [added] over the cache). */
    private fun schedule(
        layer: Layer,
        base: VectorContent,
        after: VectorContent,
        label: String,
        rects: List<Rect>,
        added: List<VObject>?,
        beforeApply: (() -> Unit)?,
        onDone: (Boolean) -> Unit = {},
        attempt: Int = 0,
        render: VectorContent = after,
        newArray: ArrayChange? = null,
    ) {
        val pieces = split(rects)
        val copies = if (added != null) {
            try {
                // The cache under the new objects (they are drawn over it, as on the main thread).
                pieces.map { r -> BitmapUtils.createLayerBitmap(r.width(), r.height()).also { b -> BitmapUtils.blitExact(b, layer.bitmap, -r.left, -r.top) } }
            } catch (e: OutOfMemoryError) {
                c.toast("Not enough memory for \"$label\"")
                beforeApply?.invoke()
                onDone(false)
                return
            }
        } else null
        val p = Pending(layer, base, after, label, pieces, added, copies, beforeApply, onDone, attempt, render, newArray)
        pending = p
        updateRendering()
        c.addDeferredStep(deferredStep)
        val doc = docBounds()
        val units = VectorLayerRenderer.estimateUnits(added?.let { VectorContent(objects = it) } ?: render, rects)
        val total = pieces.size
        p.job = c.scope.async(worker.dispatcher) {
            val ctx = coroutineContext
            val out = ArrayList<Bitmap>(pieces.size)
            // Progress reaches the main thread at most once per percent.
            var posted = -1
            fun progress(f: Float) {
                val pct = (f * 100f).toInt()
                if (pct != posted) { posted = pct; postProgress(f) }
            }
            try {
                for ((k, r) in pieces.withIndex()) {
                    ctx.ensureActive()
                    val b = copies?.get(k) ?: BitmapUtils.createLayerBitmap(r.width(), r.height())
                    if (copies == null) out += b
                    val cv = Canvas(b)
                    cv.translate(-r.left.toFloat(), -r.top.toFloat())
                    val content = added?.let { VectorContent(objects = it) } ?: render
                    val finished = VectorLayerRenderer.renderWith(cv, content, r, emptySet(), worker.tips, doc, worker.cache) { done, n ->
                        progress((k + done.toFloat() / max(1, n)) / total)
                        ctx.isActive
                    }
                    if (!finished) ctx.ensureActive()
                    progress((k + 1f) / total)
                }
            } catch (t: Throwable) {
                for (b in out) b.recycle()
                throw t
            }
            copies ?: out
        }
        // Dispatched (Main, not immediate): a render that is already done when this starts still
        // lands on a later main-thread turn, never inside this `update` call. So an async edit
        // is always pending when `update` returns, whatever the worker's timing: an edit the
        // caller computes next from the old content is re-based onto it (see [updateInternal]).
        c.scope.launch(Dispatchers.Main) {
            val result = try { p.job?.await() } catch (e: CancellationException) { null } catch (e: Throwable) { null }
            if (!p.finished) finish(p, result) else if (result != null && result !== p.copies) result.forEach { if (!it.isRecycled) it.recycle() }
        }
        val ms = estimateMs(units)
        if (ms > BUSY_AFTER_MS) showBusy(p) else c.scope.launch { delay(BUSY_AFTER_MS.toLong()); showBusy(p) }
    }

    private fun showBusy(p: Pending) {
        if (p.finished || c.busyMessage != null) return
        c.runBusy(BUSY_LABEL) { p.completion.await() }
    }

    private fun postProgress(f: Float) {
        c.scope.launch { if (c.busyMessage == BUSY_LABEL) c.busyProgress = f.coerceIn(0f, 1f) }
    }

    /** Applies (or re-runs) the pending render [p] on the main thread; [pieces] null = failed. */
    private fun finish(p: Pending, pieces: List<Bitmap>?) {
        if (p.finished) return
        p.finished = true
        if (pending === p) pending = null
        c.removeDeferredStep(deferredStep)
        updateRendering()
        try {
            if (pieces == null || pieces.size != p.pieces.size) {
                c.toast("Not enough memory for \"${p.label}\"")
                p.beforeApply?.invoke()
                p.onDone(false)
                return
            }
            val layer = p.layer
            val fresh = c.doc.indexOf(layer) >= 0 && layer.vector === p.base && layer.contentVersion == p.contentVersion && layer.bitmap === p.bitmap &&
                layer.array === p.arrayBefore
            if (!fresh) {
                // The layer changed meanwhile: the edit is re-based onto what it holds now.
                val now = layer.vector
                if (now == null || c.doc.indexOf(layer) < 0) { p.beforeApply?.invoke(); p.onDone(false); return }
                val target = ContentDiff.merge3(p.base, p.after, now)
                updateInternal(layer, target, p.label, null, null, p.beforeApply, p.onDone, p.attempt + 1, p.newArray)
                return
            }
            p.beforeApply?.invoke()
            val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val ok = applyRender(layer, p.after, p.label, p.pieces, p.newArray) { canvas ->
                for ((k, r) in p.pieces.withIndex()) canvas.drawBitmap(pieces[k], r.left.toFloat(), r.top.toFloat(), src)
            }
            p.onDone(ok && layer.vector === p.after && (p.newArray == null || layer.array == p.newArray.array))
        } finally {
            pieces?.forEach { if (!it.isRecycled) it.recycle() }
            p.copies?.forEach { if (!it.isRecycled) it.recycle() }
            p.completion.complete(Unit)
        }
    }

    /** [rects] cut into pieces of at most [MAX_PIECE] x [MAX_PIECE] px (still on the tile grid). */
    private fun split(rects: List<Rect>): List<Rect> {
        val out = ArrayList<Rect>()
        for (r in rects) {
            var y = r.top
            while (y < r.bottom) {
                var x = r.left
                val b = min(r.bottom, y + MAX_PIECE)
                while (x < r.right) {
                    out += Rect(x, y, min(r.right, x + MAX_PIECE), b)
                    x += MAX_PIECE
                }
                y = b
            }
        }
        return out
    }

    // ------------------------------------------------------------------ applying

    /** Clears each of [rects] and renders [content] there (the cache's own rendering of them). */
    private fun renderInto(content: VectorContent, rects: List<Rect>): (Canvas) -> Unit = { canvas ->
        for (r in rects) {
            canvas.save()
            canvas.clipRect(r)
            canvas.drawColor(0, PorterDuff.Mode.CLEAR)
            VectorLayerRenderer.renderWith(canvas, content, r, emptySet(), tips, docBounds(), renderCache, null)
            canvas.restore()
        }
    }

    /**
     * Changes [layer]'s content to [after] and lets [paint] change its pixels within [touch]
     * (document px; snapshotted for undo first), as ONE step [label] (I1, I2): the tiles plus a
     * [LayerDataAction]. Without any area on the canvas, a data-only step. False when the layer
     * is gone, locked or hidden, or memory ran out (nothing changes then). v1.7: [newArray]
     * changes the layer's array in the same step (null = the array is kept).
     */
    private fun applyRender(layer: Layer, after: VectorContent, label: String, touch: List<Rect>, newArray: ArrayChange?, paint: (Canvas) -> Unit): Boolean = c.editScope {
        if (c.doc.indexOf(layer) < 0 || !usable(layer)) return@editScope false
        val before = layer.dataSnapshot()
        val data = before.copy(vector = after, array = if (newArray != null) newArray.array else before.array)
        val doc = docBounds()
        val rects = touch.mapNotNull { r -> Rect(r).takeIf { it.intersect(doc) } }
        if (rects.isEmpty()) {
            c.setLayerData(layer, data, label)
            return@editScope layer.vector === after
        }
        val rec = c.beginEdit(layer, EditTarget.CONTENT).also { it.preserveData = true }
        try {
            for (r in rects) rec.touch(r)
            paint(Canvas(layer.bitmap))
        } catch (e: OutOfMemoryError) {
            rec.abort()
            c.toast("Not enough memory for \"$label\"")
            return@editScope false
        }
        layer.restoreData(data)
        val dataAction = LayerDataAction(label, layer, before, data)
        if (!c.commitEdit(rec, label, listOf(dataAction))) {
            if (before != data) {
                layer.markChanged()
                c.pushUndo(dataAction)
                c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, label))
            }
            c.notifyLayersChanged()
        }
        true
    }

    // ------------------------------------------------------------------ pure moves

    /**
     * The [ShiftHint] fast path (see [update]): true when the move was applied by shifting the
     * cache pixels (one step), false to re-render instead.
     */
    private fun tryShift(layer: Layer, base: VectorContent, target: VectorContent, label: String, hint: ShiftHint, beforeApply: (() -> Unit)?): Boolean {
        val dx = hint.dx
        val dy = hint.dy
        if ((dx == 0 && dy == 0) || hint.ids.isEmpty()) return false
        val n = base.objects.size
        if (target.objects.size != n) return false
        val bi = ObjectIndex.of(base)
        val ti = ObjectIndex.of(target)
        val oldPaintF = RectF()
        for (i in 0 until n) {
            val o = base.objects[i]
            val t = target.objects[i]
            if (o.id != t.id) return false
            if (o.id in hint.ids) {
                if (o::class != t::class) return false
                val ob = bi.bounds(i)
                val tb = ti.bounds(i)
                if (ob.isEmpty != tb.isEmpty) return false
                if (!ob.isEmpty) {
                    val tol = 0.05f + 1e-5f * (abs(ob.left) + abs(ob.top))
                    if (abs(tb.left - ob.left - dx) > tol || abs(tb.top - ob.top - dy) > tol ||
                        abs(tb.right - ob.right - dx) > tol || abs(tb.bottom - ob.bottom - dy) > tol) return false
                    oldPaintF.union(ob)
                }
                if (usesGrain(o) && (dx % PaperGrain.SIZE != 0 || dy % PaperGrain.SIZE != 0)) return false
            } else if (o !== t && o != t) {
                return false
            }
        }
        if (oldPaintF.isEmpty) return false
        val oldPaint = roundOut(oldPaintF)
        val newPaint = Rect(oldPaint).apply { offset(dx, dy) }
        // No other object may paint where the moved ones were or go.
        for (i in bi.query(RectF(oldPaint))) if (base.objects[i].id !in hint.ids) return false
        for (i in ti.query(RectF(newPaint))) if (target.objects[i].id !in hint.ids) return false
        // Every pixel landing on the canvas must come from the canvas.
        val doc = docBounds()
        val landing = Rect(newPaint)
        val anyLanding = landing.intersect(doc)
        if (anyLanding) {
            val from = Rect(landing).apply { offset(-dx, -dy) }
            if (!doc.contains(from)) return false
        }
        val oldOnCanvas = Rect(oldPaint).takeIf { it.intersect(doc) }
        val touch = listOfNotNull(oldOnCanvas, if (anyLanding) landing else null)
        if (touch.isEmpty()) return false
        val bytes = touch.sumOf { it.width().toLong() * it.height() * 4L }
        if (bytes > Runtime.getRuntime().maxMemory() / 8) return false
        val src = try {
            if (anyLanding) {
                val from = Rect(landing).apply { offset(-dx, -dy) }
                Bitmap.createBitmap(layer.bitmap, from.left, from.top, from.width(), from.height())
            } else null
        } catch (e: OutOfMemoryError) {
            return false
        }
        beforeApply?.invoke()
        shiftCount++
        try {
            applyRender(layer, target, label, touch, null) { canvas ->
                for (r in touch) {
                    canvas.save()
                    canvas.clipRect(r)
                    canvas.drawColor(0, PorterDuff.Mode.CLEAR)
                    canvas.restore()
                }
                if (src != null) canvas.drawBitmap(src, landing.left.toFloat(), landing.top.toFloat(), null)
            }
        } finally {
            // (createBitmap may hand back its source for a whole-bitmap subset: never recycle the layer.)
            src?.takeIf { it !== layer.bitmap }?.recycle()
        }
        return true
    }

    /** Moves applied by shifting cache pixels (tests). */
    internal var shiftCount = 0
        private set

    /** True when [o] paints with paper grain (anchored to the document: a shifted copy would not match). */
    private fun usesGrain(o: VObject): Boolean = when (o) {
        is VStroke -> o.preset.grain > 0f
        is VPath -> o.stroke?.takeIf { it.kind == VStrokeKind.BRUSH }?.let { VectorOps.brushOf(it).grain > 0f } ?: false
        is VShape -> o.shape.paintsWithBrush && VectorOps.brushPresetOf(o.shape).grain > 0f
    }

    // ------------------------------------------------------------------ helpers

    /**
     * False (with the controller's message) when [layer] is locked or hidden. v1.7 (rule L): its
     * own lock and eye, then its folders' (the same messages).
     */
    private fun usable(layer: Layer): Boolean = c.checkUsable(layer)

    private fun roundOut(r: RectF): Rect =
        if (r.isEmpty) Rect() else Rect(floor(r.left).toInt(), floor(r.top).toInt(), ceil(r.right).toInt(), ceil(r.bottom).toInt())

    /** The document rect: where brush dabs are cut in every render (as the live stroke cuts them). */
    private fun docBounds(): Rect = Rect(0, 0, c.doc.width, c.doc.height)

    /**
     * [r] grown to whole squares of the renderer's tile grid ([VectorLayerRenderer.TILE]) and
     * clipped to the document (empty when outside).
     */
    private fun gridRect(r: Rect): Rect {
        if (r.isEmpty) return Rect()
        val t = VectorLayerRenderer.TILE
        val out = Rect(
            Math.floorDiv(r.left, t) * t, Math.floorDiv(r.top, t) * t,
            -Math.floorDiv(-r.right, t) * t, -Math.floorDiv(-r.bottom, t) * t,
        )
        if (!out.intersect(0, 0, c.doc.width, c.doc.height)) return Rect()
        return out
    }

    internal companion object {
        /**
         * The parts of [r] outside [doc], as at most four disjoint rects: the bands above and
         * below the document (full width of [r]), then those left and right of it (between them).
         */
        fun outside(r: Rect, doc: Rect): List<Rect> {
            if (r.isEmpty) return emptyList()
            val out = ArrayList<Rect>(4)
            fun add(l: Int, t: Int, rt: Int, b: Int) { if (l < rt && t < b) out += Rect(l, t, rt, b) }
            val top = max(r.top, min(r.bottom, doc.top))
            val bottom = min(r.bottom, max(r.top, doc.bottom))
            add(r.left, r.top, r.right, top)
            add(r.left, bottom, r.right, r.bottom)
            add(r.left, top, min(r.right, doc.left), bottom)
            add(max(r.left, doc.right), top, r.right, bottom)
            return out
        }

        /** Largest side of a floating preview bitmap (px). */
        const val MAX_FLOATING = 2048f

        /** Main-thread budget of a re-render (I3). */
        const val SYNC_BUDGET_MS = 25.0

        /** A background render expected to last longer shows the busy overlay at once (else after this delay). */
        const val BUSY_AFTER_MS = 400.0

        const val BUSY_LABEL = "Rendering vectors…"

        /** Side of the patches a background render produces. */
        const val MAX_PIECE = 1024

        /** Re-runs of a stale background render before it is done on the main thread. */
        const val MAX_ATTEMPTS = 3

        /** A phone's speed before anything was measured (ns per cost unit; a desktop is about 1). */
        private const val PHONE_NS_PER_UNIT = 3.0
        private const val MIN_NS_PER_UNIT = 0.2
        private const val MAX_NS_PER_UNIT = 50.0
        private const val MIN_MEASURED_UNITS = 300_000.0
        private const val SPEED_SMOOTHING = 0.3

        /** Robolectric runs the unit tests: renders default to the main thread there. */
        private val isTestRuntime: Boolean = "robolectric" == android.os.Build.FINGERPRINT

        private fun initialSpeed(): Double {
            val measured = BrushStrokePreview.nsPerUnit
            return (if (measured != 1.0) measured else PHONE_NS_PER_UNIT).coerceIn(MIN_NS_PER_UNIT, MAX_NS_PER_UNIT)
        }
    }
}
