package com.brushwork.paint.array

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
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerDataAction
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.vector.VectorLayers
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
import kotlin.math.abs

/**
 * v1.7 (§6.3; area E, integration pass "arrayrender"): the caches of text, shape and raster
 * arrays rendered off the main thread. (A vector array renders through `VectorLayers`.)
 *
 * An array edit changes the layer's data and its cache together, as ONE step (I1, I2). [update]
 * is `EditorController.updateLayerData` for an arrayed layer's cache: the same area (dirty ∪ the
 * old and the new copies' bounds), the same drawing, the same step. When drawing it would hold the
 * main thread longer than [syncBudgetMs] ([estimateMs]: the pixels the copies cover, at this
 * device's measured speed), the Array tool is current and nothing else is under way, the cache is
 * drawn on a worker instead, from immutable inputs only (the after data, its source pixels, a
 * text or shape prepared on the main thread), never from the layer's bitmap: into a patch the
 * size of that area, one render at a time. The main thread then swaps it in together with the
 * data, as ONE step ([finish]): the area is snapshotted for undo, the patch is copied over it
 * (SRC: the old copies go too) and the data is set, exactly as a synchronous draw would leave it.
 * Until then the layer keeps its old data AND its old pixels (the Array tool's preview shows the
 * new copies), so data and pixels never disagree, on screen or in history.
 *
 * - **Superseded**: a newer "Edit array" of the same layer ([supersede], first thing in
 *   `ArrayOps.edit`) drops the render still running: no step, its caller hears `onDone(false)`,
 *   and the new edit is made from the layer's data, which the dropped one never changed.
 * - **Anything else first lands it** ([flush]: waits for the worker, then swaps): it is a
 *   [DeferredStep], so every other step (undo and redo too) records after it; a pixel edit that
 *   starts on the layer (`EditorController.beginEdit`), the Array tool stopping being current,
 *   and `EditorController.settleVectorWork` (closing and saving, canvas operations, export,
 *   filters) land it as well.
 * - **"Rendering array…"**: [isSlow] becomes true once a render has run for [SLOW_AFTER_MS]
 *   (the Array tool's options strip shows it).
 * - **Memory**: no extra document-size bitmap per render. The patch covers the changed area only
 *   and is reused by the next render (let go [KEEP_MS] after the last one); a patch larger than
 *   [patchBudget] (an eighth of the heap, as an edit session's floating copies) or one that
 *   can't be allocated renders synchronously instead.
 * - **Small arrays stay synchronous** ([syncBudgetMs], [SYNC_BUDGET_MS], as `VectorLayers`): a
 *   background render still costs the main thread its scheduling and the swap (the area's undo
 *   snapshot and the patch copied in: measured on the JVM, 1.9 ms for a small array that draws
 *   in 0.8 ms) and reaches the screen a main-thread turn after it ends (about a frame), so below
 *   about one and a half frames of drawing it would only add latency. The speed starts from the
 *   JVM's measured cost times the phone factor `VectorLayers` uses ([INITIAL_NS_PIXELS],
 *   [INITIAL_NS_SOURCE]) and follows this device's own renders.
 *
 * Main thread (the worker only draws into its patch).
 */
internal class ArrayRenders(private val c: EditorController) {

    /** What a cache draws, given to [update]. */
    sealed interface Cache {
        /** A raster array's whole cache: [ArrayDraw.drawPixels] of [array] (stops early when superseded). */
        class Pixels(val array: LayerArray) : Cache

        /**
         * What `EditorController.updateLayerData` would get as `draw`: a text or shape source
         * alone (repeated per copy of the after data's array), or a raster source alone at its
         * place ([pixels] true: the cost is counted as bitmap pixels).
         */
        class Draw(val draw: (Canvas) -> Unit, val pixels: Boolean = false) : Cache
    }

    /** How renders are scheduled (as `VectorLayers.policy`: SYNC under Robolectric, tests opt in). */
    internal var policy: VectorLayers.Policy = if (isTestRuntime) VectorLayers.Policy.SYNC else VectorLayers.Policy.AUTO

    /** Main-thread time a cache may take (ms) before it renders in the background. */
    internal var syncBudgetMs: Double = SYNC_BUDGET_MS

    /** Test seam: the worker's dispatcher (null = Dispatchers.Default); read once, one render at a time either way. */
    internal var workerDispatcher: CoroutineDispatcher? = null

    /** Test seam: runs on the worker before each render (a slow renderer). */
    internal var workerHook: (() -> Unit)? = null

    /** Test seam: the bytes a patch may take (larger: synchronous). */
    internal var patchBudget: () -> Long = { Runtime.getRuntime().maxMemory() / 8 }

    /** Nanoseconds per cost unit: [KIND_PIXELS] (bitmap pixels drawn) and [KIND_SOURCE] (text and shape area drawn). */
    private val nsPerUnit = doubleArrayOf(INITIAL_NS_PIXELS, INITIAL_NS_SOURCE)

    private val worker: CoroutineDispatcher by lazy { (workerDispatcher ?: Dispatchers.Default).limitedParallelism(1) }

    /** A render running on the worker (at most one). */
    private inner class Pending(
        val layer: Layer,
        val before: LayerData,
        val after: LayerData,
        val label: String,
        val dirty: Rect?,
        val area: Rect,
        val cache: Cache,
        val patch: Bitmap,
        val kind: Int,
        val units: Double,
        val supersedable: Boolean,
        val release: (() -> Unit)?,
        val onDone: (Boolean) -> Unit,
        val stats: Stats,
    ) {
        val contentVersion = layer.contentVersion
        val bitmap: Bitmap = layer.bitmap
        val docW = c.doc.width
        val docH = c.doc.height
        /** The worker's time (ns), or -1 when it failed. */
        var job: Deferred<Long>? = null
        var finished = false
        val completion = CompletableDeferred<Unit>()
    }

    private var pending: Pending? = null

    /** The last render's job: the patch is not let go before it ends. */
    private var lastJob: Job? = null

    /** The reused patch (or null). */
    private var buffer: Bitmap? = null
    private var bufferRelease: Job? = null

    private var flushing = false
    private var disposed = false

    /** Lands a pending render before any other step (undo and redo too). */
    private val deferredStep = DeferredStep { flush() }

    /** True once a render has run for [SLOW_AFTER_MS] and has not landed (Compose state: "Rendering array…"). */
    var isSlow by mutableStateOf(false)
        private set

    /** What a render cost: its kind and units, and the time it took. */
    internal class Stats(val kind: Int, val units: Double, val background: Boolean) {
        /** The main thread's time (ns): the whole render when synchronous, else scheduling it plus the swap. */
        var mainNs = 0L

        /** The worker's drawing time (ns; -1: synchronous, dropped or failed). */
        var workerNs = -1L
    }

    /** The last render's cost (the probes print it). */
    internal var lastStats: Stats? = null
        private set

    /** True while a render runs in the background. */
    val isPending: Boolean get() = pending != null

    /** True while a render of [layer]'s cache runs in the background. */
    fun isPendingOn(layer: Layer): Boolean = pending?.layer === layer

    /**
     * Sets [layer]'s data to [after] and its cache to [cache] within [dirty] ∪ the old and the
     * new copies' bounds, as ONE step [label]: synchronously (exactly
     * `EditorController.updateLayerData`), or in the background (see the class docs). [onDone]
     * tells whether it was applied: before this returns when synchronous, when the render lands
     * otherwise (false: refused, dropped or superseded). [release] runs once the inputs are no
     * longer read (after the drawing, whichever way). [supersedable]: a newer "Edit array" of
     * the layer drops it while it renders. Returns false when refused (layer gone, locked or
     * hidden, out of memory), true when applied or on its way.
     */
    fun update(
        layer: Layer,
        after: LayerData,
        label: String,
        dirty: Rect?,
        cache: Cache,
        supersedable: Boolean = false,
        release: (() -> Unit)? = null,
        onDone: (Boolean) -> Unit = {},
    ): Boolean {
        if (disposed || c.doc.indexOf(layer) < 0) {
            release?.invoke()
            onDone(false)
            return false
        }
        val t0 = System.nanoTime()
        val before = layer.dataSnapshot()
        val area = areaOf(before, after, dirty)
        val kind = kindOf(cache)
        val units = unitsOf(after, cache, area)
        if (!area.isEmpty && goAsync(kind, units)) {
            if (!c.checkUsable(layer)) {
                release?.invoke()
                onDone(false)
                return false
            }
            val patch = patchFor(area)
            if (patch != null) {
                val stats = Stats(kind, units, background = true)
                lastStats = stats
                schedule(Pending(layer, before, after, label, dirty, area, cache, patch, kind, units, supersedable, release, onDone, stats))
                stats.mainNs += System.nanoTime() - t0
                return true
            }
        }
        val stats = Stats(kind, units, background = false)
        lastStats = stats
        val ok = timed(kind, units) { c.updateLayerData(layer, after, label, dirty, draw = syncDraw(cache)) }
        release?.invoke()
        stats.mainNs = System.nanoTime() - t0
        onDone(ok)
        return ok
    }

    /** Drops the render of [layer] still running when it is an "Edit array" (no step; its caller hears false). */
    fun supersede(layer: Layer) {
        val p = pending?.takeIf { it.layer === layer && it.supersedable && !it.finished } ?: return
        p.finished = true
        pending = null
        c.removeDeferredStep(deferredStep)
        isSlow = false
        p.job?.cancel()
        // The worker stops at its next copy; the next render waits for it (one at a time) and
        // reuses the patch. The inputs are let go once it has stopped.
        afterJob(p) { p.release?.invoke() }
        p.completion.complete(Unit)
        idleRelease()
        p.onDone(false)
    }

    /**
     * Lands the render still running now: waits for the worker and swaps the patch in with the
     * data (one step), or draws it here when the worker failed. Nothing when none runs.
     */
    fun flush() {
        val p = pending ?: return
        val ns = try {
            runBlocking { p.job?.await() } ?: -1L
        } catch (e: CancellationException) {
            -1L
        } catch (e: Throwable) {
            -1L
        }
        finish(p, ns)
    }

    /** The editor closes: a running render is dropped (its caller hears false) and the patch let go. */
    fun dispose() {
        disposed = true
        val p = pending?.takeIf { !it.finished }
        pending = null
        c.removeDeferredStep(deferredStep)
        isSlow = false
        bufferRelease?.cancel()
        if (p != null) {
            p.finished = true
            p.job?.cancel()
            afterJob(p) { p.release?.invoke() }
            p.completion.complete(Unit)
        }
        dropBuffer()
        if (p != null) runCatching { p.onDone(false) }
    }

    // ------------------------------------------------------------------ scheduling

    private fun goAsync(kind: Int, units: Double): Boolean {
        // Synchronous: while a render lands (its re-run), while another runs, inside another step
        // (it must land in that step), when the editor is closing or under another busy overlay,
        // and unless the Array tool is current: it keeps the preview up and shows "Rendering
        // array…" meanwhile, and lands the render when it stops being current.
        if (disposed || flushing || pending != null || c.editDepth != 0 || !c.scope.isActive || c.busyMessage != null) return false
        if (c.currentTool !is ArrayTool) return false
        return when (policy) {
            VectorLayers.Policy.SYNC -> false
            VectorLayers.Policy.ASYNC -> true
            VectorLayers.Policy.AUTO -> estimateMs(kind, units) > syncBudgetMs
        }
    }

    /** The main-thread time [units] of [kind] would take at this device's measured speed (ms). */
    internal fun estimateMs(kind: Int, units: Double): Double = units * nsPerUnit[kind] / 1e6

    private fun schedule(p: Pending) {
        pending = p
        bufferRelease?.cancel()
        c.addDeferredStep(deferredStep)
        val area = Rect(p.area)
        val patch = p.patch
        val paint = workerPaint(p.after, p.cache)
        val hook = workerHook
        val job = c.scope.async(worker) {
            val ctx = coroutineContext
            try {
                hook?.invoke()
                ctx.ensureActive()
                val t0 = System.nanoTime()
                val cv = Canvas(patch)
                cv.clipRect(0, 0, area.width(), area.height())
                cv.drawColor(0, PorterDuff.Mode.CLEAR)
                cv.translate(-area.left.toFloat(), -area.top.toFloat())
                paint(cv) { ctx.isActive }
                ctx.ensureActive()
                System.nanoTime() - t0
            } catch (e: CancellationException) {
                throw e
            } catch (e: Throwable) {
                // Out of memory, a recycled source...: drawn on the main thread when it lands.
                -1L
            }
        }
        p.job = job
        lastJob = job
        // Dispatched (Main, not immediate): a render already done when this starts still lands on
        // a later main-thread turn, never inside this `update` call.
        c.scope.launch(Dispatchers.Main) {
            val ns = try { job.await() } catch (e: CancellationException) { -1L } catch (e: Throwable) { -1L }
            if (!p.finished) finish(p, ns)
        }
        c.scope.launch {
            delay(SLOW_AFTER_MS)
            if (pending === p && !p.finished) isSlow = true
        }
    }

    /** Swaps [p]'s patch in (or draws it here when the worker failed, [ns] < 0, or the layer changed). */
    private fun finish(p: Pending, ns: Long) {
        if (p.finished) return
        val t0 = System.nanoTime()
        p.finished = true
        if (pending === p) pending = null
        c.removeDeferredStep(deferredStep)
        isSlow = false
        val wasFlushing = flushing
        // Whatever this re-runs is synchronous.
        flushing = true
        var ok = false
        try {
            val layer = p.layer
            val present = c.doc.indexOf(layer) >= 0
            val sameData = present && layer.dataSnapshot() == p.before
            val fresh = sameData && c.doc.width == p.docW && c.doc.height == p.docH &&
                layer.bitmap === p.bitmap && layer.contentVersion == p.contentVersion
            ok = when {
                // A net, never expected: every other step lands this one first (see the class docs).
                !sameData -> false
                ns >= 0 && fresh -> {
                    learn(p.kind, p.units, ns)
                    apply(p)
                }
                // (Hidden meanwhile without a step, by a properties preview: it still lands.)
                else -> c.updateLayerData(layer, p.after, p.label, p.dirty, allowHidden = true, draw = syncDraw(p.cache))
            }
        } finally {
            flushing = wasFlushing
            p.release?.invoke()
            p.completion.complete(Unit)
            idleRelease()
            p.stats.workerNs = ns
            p.stats.mainNs += System.nanoTime() - t0
        }
        p.onDone(ok)
    }

    /**
     * The swap (one step, as `updateLayerData`): the area snapshotted, the patch copied over it,
     * the data set; color modes are applied to the touched tiles by `commitEdit`.
     */
    private fun apply(p: Pending): Boolean = c.editScope {
        val layer = p.layer
        // Committed while usable: a layer hidden since (a properties preview, no step) still takes it.
        if (c.doc.indexOf(layer) < 0 || !c.checkUsable(layer, allowHidden = true)) return@editScope false
        val before = layer.dataSnapshot()
        val rec = c.beginEdit(layer, EditTarget.CONTENT).also { it.preserveData = true }
        try {
            rec.touch(p.area)
            Canvas(layer.bitmap).drawBitmap(p.patch, Rect(0, 0, p.area.width(), p.area.height()), p.area, SRC)
        } catch (e: OutOfMemoryError) {
            rec.abort()
            c.toast("Not enough memory for \"${p.label}\"")
            return@editScope false
        }
        layer.restoreData(p.after)
        val dataAction = LayerDataAction(p.label, layer, before, p.after)
        if (!c.commitEdit(rec, p.label, listOf(dataAction))) {
            if (before != p.after) {
                layer.markChanged()
                c.pushUndo(dataAction)
                c.queueEdit(EditEvent(layer, EditTarget.CONTENT, null, p.label))
            }
            c.notifyLayersChanged()
        }
        true
    }

    // ------------------------------------------------------------------ what is drawn

    /** The area `updateLayerData` clears and redraws: [dirty] ∪ the old and the new copies' bounds (grown by 1 px), within the document. */
    private fun areaOf(before: LayerData, after: LayerData, dirty: Rect?): Rect {
        val w = c.doc.width
        val h = c.doc.height
        val area = Rect(dirty ?: Rect(0, 0, w, h))
        if (before.array != null || after.array != null) for (d in listOf(before, after)) ArrayDraw.cacheBounds(d)?.let { b ->
            if (!b.isEmpty) area.union(Rect().also { r -> b.roundOut(r); r.inset(-1, -1) })
        }
        if (!area.intersect(0, 0, w, h)) area.set(0, 0, 0, 0)
        return area
    }

    /** What `updateLayerData` is given to draw. */
    private fun syncDraw(cache: Cache): (Canvas) -> Unit = when (cache) {
        is Cache.Pixels -> { cv -> ArrayDraw.drawPixels(cv, cache.array) }
        is Cache.Draw -> cache.draw
    }

    /** What `updateLayerData` draws for [cache] (a text or shape source repeated per copy), stopping when told to. */
    private fun workerPaint(after: LayerData, cache: Cache): (Canvas, () -> Boolean) -> Unit = when (cache) {
        is Cache.Pixels -> { cv, go -> ArrayDraw.drawPixels(cv, cache.array, go) }
        is Cache.Draw -> {
            val a = after.array
            if (a != null && a.pixels == null && after.vector == null) {
                // Measured here, on the main thread (text layout).
                val bounds = ArrayDraw.sourceBounds(after) ?: RectF()
                val draw = cache.draw
                val paint: (Canvas, () -> Boolean) -> Unit = { cv, go ->
                    ArrayDraw.drawWithArray(cv, a, bounds) { c2 ->
                        if (!go()) throw CancellationException("superseded")
                        draw(c2)
                    }
                }
                paint
            } else {
                val draw = cache.draw
                val paint: (Canvas, () -> Boolean) -> Unit = { cv, _ -> draw(cv) }
                paint
            }
        }
    }

    private fun kindOf(cache: Cache): Int = when (cache) {
        is Cache.Pixels -> KIND_PIXELS
        is Cache.Draw -> if (cache.pixels) KIND_PIXELS else KIND_SOURCE
    }

    /**
     * The cost of a cache (units of [kindOf]): the area cleared and the pixels the copies cover
     * (each copy's source area times its matrix's scale, from the after data).
     */
    private fun unitsOf(after: LayerData, cache: Cache, area: Rect): Double {
        val cleared = area.width().toDouble() * area.height()
        val a = (cache as? Cache.Pixels)?.array ?: after.array
        val src = (if (cache is Cache.Pixels) cache.array.pixels?.let { px ->
            RectF(px.left.toFloat(), px.top.toFloat(), (px.left + px.bitmap.width).toFloat(), (px.top + px.bitmap.height).toFloat())
        } else ArrayDraw.sourceBounds(after)) ?: return cleared
        if (src.isEmpty || !src.width().isFinite() || !src.height().isFinite()) return cleared
        val one = src.width().toDouble() * src.height()
        if (a == null || a.spec.editingSource || (cache is Cache.Draw && cache.pixels)) return cleared + one
        var covered = 0.0
        for (m in ArrayLayout.matrices(a.spec, src)) {
            val det = abs(m[0].toDouble() * m[4] - m[1].toDouble() * m[3])
            if (det.isFinite()) covered += one * det
        }
        return cleared + covered
    }

    /** Runs a synchronous render [block], measuring this device's speed on the larger ones. */
    private inline fun timed(kind: Int, units: Double, block: () -> Boolean): Boolean {
        val t0 = System.nanoTime()
        val ok = block()
        if (ok) learn(kind, units, System.nanoTime() - t0)
        return ok
    }

    private fun learn(kind: Int, units: Double, ns: Long) {
        if (policy != VectorLayers.Policy.AUTO || units < MIN_MEASURED_UNITS || ns <= 0) return
        val sample = (ns / units).coerceIn(MIN_NS_PER_UNIT, MAX_NS_PER_UNIT)
        nsPerUnit[kind] += (sample - nsPerUnit[kind]) * SPEED_SMOOTHING
    }

    // ------------------------------------------------------------------ the patch

    /** The patch for [area]: the reused one when it is large enough, else a new one; null when memory is short. */
    private fun patchFor(area: Rect): Bitmap? {
        val w = area.width()
        val h = area.height()
        if (4L * w * h > patchBudget()) return null
        buffer?.let { b -> if (!b.isRecycled && b.width >= w && b.height >= h) return b }
        dropBuffer()
        return try {
            BitmapUtils.createLayerBitmap(w, h).also { buffer = it }
        } catch (e: OutOfMemoryError) {
            null
        }
    }

    /** Lets the patch go (once the worker is done with it). */
    private fun dropBuffer() {
        val b = buffer ?: return
        buffer = null
        val j = lastJob
        if (j == null || j.isCompleted) b.recycle() else j.invokeOnCompletion { b.recycle() }
    }

    /** Lets the patch go [KEEP_MS] after the last render (a following edit reuses it). */
    private fun idleRelease() {
        bufferRelease?.cancel()
        if (disposed || !c.scope.isActive) {
            dropBuffer()
            return
        }
        bufferRelease = c.scope.launch {
            delay(KEEP_MS)
            if (pending == null) dropBuffer()
        }
    }

    /** Runs [block] once [p]'s worker job has ended (now when it has). */
    private fun afterJob(p: Pending, block: () -> Unit) {
        val j = p.job
        if (j == null || j.isCompleted) block() else j.invokeOnCompletion { block() }
    }

    companion object {
        /** Main-thread drawing (ms) above which a cache renders in the background (as `VectorLayers`). */
        const val SYNC_BUDGET_MS = 25.0

        /** "Rendering array…" shows once a render has run this long (ms). */
        const val SLOW_AFTER_MS = 300L

        /** The patch is kept this long after a render for the next one (ms). */
        const val KEEP_MS = 3_000L

        const val KIND_PIXELS = 0
        const val KIND_SOURCE = 1

        /**
         * Starting speeds (ns per unit) for a phone: an "Edit array" through the controller
         * (the undo snapshot, the drawing, the commit) measured on the JVM (Robolectric NATIVE,
         * software Skia; `ArrayPerfProbeTest.theBackgroundRenderCostModel`) costs about 13 ns per
         * bitmap pixel drawn for a raster cache (bilinear, turned copies) and about 2 ns per pixel
         * of the copies' area for a text or shape cache (glyphs and outlines cover little of it),
         * times 3 for a phone (`VectorLayers`' phone factor). Rather too slow than too fast: a
         * render sent to the worker that needn't be costs a frame of latency, one kept on the
         * main thread that shouldn't be drops frames. The device's own renders take over from there.
         */
        const val INITIAL_NS_PIXELS = 40.0
        const val INITIAL_NS_SOURCE = 6.0

        private const val MIN_MEASURED_UNITS = 1_000_000.0
        private const val MIN_NS_PER_UNIT = 0.05
        private const val MAX_NS_PER_UNIT = 200.0
        private const val SPEED_SMOOTHING = 0.3

        private val SRC = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

        private val isTestRuntime: Boolean = "robolectric" == android.os.Build.FINGERPRINT
    }
}
