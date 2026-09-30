package com.brushwork.paint.tools.remove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.inpaint.ContentAwareFill
import com.brushwork.paint.inpaint.HoleMask
import com.brushwork.paint.inpaint.IRect
import com.brushwork.paint.inpaint.InpaintException
import com.brushwork.paint.inpaint.InpaintMonitor
import com.brushwork.paint.inpaint.InpaintParams
import com.brushwork.paint.inpaint.InpaintResult
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.delay
import kotlinx.coroutines.job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.WeakHashMap
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger

/**
 * Runs content-aware fills in the editor: the selection's "Content-aware fill" (with Refill) and
 * the Remove tool's painted areas. Main thread only.
 *
 * A fill is planned in the background, reads its pixels on the main thread (the active layer or
 * all visible layers, only the region it needs), runs the engine on [Dispatchers.Default] under
 * the editor's busy overlay (progress + Stop) and is applied as ONE undo step: a new layer, or a
 * pixel edit of the layer it was started on (skipped with a message if that layer changed).
 */
object ContentAwareFillJob {

    const val FILL_LABEL = "Content-aware fill"
    const val REMOVE_LABEL = "Remove"
    private const val FIRST_SEED = 1
    private const val PROGRESS_MS = 80L

    /** What Refill needs to know about the last selection fill. */
    internal class LastFill(val action: UndoAction, val sourceLayer: Layer, val seed: Int, val editCount: Int, val undoCount: Int)

    /** Per-editor state (options, last fill). */
    internal class State(settings: AppSettings) {
        var options: CafOptions by StoredSetting(settings, "caf.options", CafOptions.serializer(), CafOptions())
        var running by mutableStateOf(false)
        var lastFill: LastFill? = null
    }

    private val states = WeakHashMap<EditorController, State>()

    internal fun state(controller: EditorController): State = states.getOrPut(controller) { State(controller.settings) }

    /** Options of the selection fill (persisted across documents). */
    fun options(controller: EditorController): CafOptions = state(controller).options

    fun setOptions(controller: EditorController, options: CafOptions) { state(controller).options = options }

    /** True while a fill runs in [controller]. */
    fun isRunning(controller: EditorController): Boolean = state(controller).running

    /** True when the latest step of the history is a selection fill that Refill can replace. */
    fun canRefill(controller: EditorController): Boolean {
        val last = state(controller).lastFill ?: return false
        val um = controller.undoManager
        return controller.editCount == last.editCount && um.undoCount == last.undoCount && um.undoLabel == last.action.label
    }

    /**
     * Fills the selection of the active layer with [options]. With [refill] the previous fill (if
     * it is still the latest step) is undone first and a different result (next seed) replaces
     * it. Returns false if nothing was started (a message says why).
     */
    fun fillSelection(controller: EditorController, options: CafOptions, refill: Boolean = false): Boolean {
        if (controller.busyMessage != null) return false
        if (controller.isInteracting) controller.pointerCancel()
        val st = state(controller)
        var seed = FIRST_SEED
        val last = st.lastFill
        if (refill && last != null) {
            seed = last.seed + 1
            val tool = controller.currentTool
            // Settle pending tool work first, so undo takes back the fill and nothing else.
            tool.onDeactivate()
            if (canRefill(controller) && !tool.hasPendingWork && controller.filterSession == null && isTopStep(controller, last.action)) {
                controller.undo()
            }
            tool.onActivate()
            if (controller.doc.indexOf(last.sourceLayer) >= 0 && controller.activeLayer !== last.sourceLayer) controller.selectLayer(last.sourceLayer)
            st.lastFill = null
        }
        val sel = controller.selection ?: run { controller.toast("Select the area to fill first"); return false }
        val layer = controller.activeLayer
        if (options.output == CafOutput.CURRENT_LAYER && !checkWritable(controller, layer)) return false
        if (options.output == CafOutput.NEW_LAYER && !controller.canAddLayer) {
            controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size")
            return false
        }
        val hole = try { holeOf(sel, controller.doc.width, controller.doc.height) } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for $FILL_LABEL"); return false
        }
        val params = InpaintParams(
            sampling = options.sampling,
            expand = options.expand.coerceIn(0, InpaintParams.MAX_EXPAND),
            colorAdaptation = options.colorAdaptation,
            seed = seed,
        )
        val req = Request(layer, layer.bitmap, hole, params, options.source, options.output, FILL_LABEL, "Content-aware fill…", clip = null)
        return start(controller, req) { action ->
            if (action != null) {
                st.lastFill = LastFill(action, layer, seed, controller.editCount, controller.undoManager.undoCount)
            }
        }
    }

    /**
     * The Remove tool: fills [hole] (the painted area) of [layer] in place, limited to [clip] (the
     * selection) when given. [onFinished] runs on the main thread when done, stopped or refused.
     */
    fun removeArea(
        controller: EditorController,
        layer: Layer,
        hole: HoleMask,
        clip: Selection?,
        settings: RemoveSettings,
        onFinished: (applied: Boolean) -> Unit,
    ): Boolean {
        if (controller.busyMessage != null || !checkWritable(controller, layer)) { onFinished(false); return false }
        val params = InpaintParams(expand = RemoveSettings.EXPAND, colorAdaptation = settings.colorAdaptation, seed = FIRST_SEED)
        val req = Request(layer, layer.bitmap, hole, params, settings.source, CafOutput.CURRENT_LAYER, REMOVE_LABEL, "Removing…", clip)
        return start(controller, req) { action -> onFinished(action != null) }
    }

    // ------------------------------------------------------------------ internals

    private class Request(
        val layer: Layer,
        val bitmap: Bitmap,
        val hole: HoleMask,
        val params: InpaintParams,
        val source: CafSource,
        val output: CafOutput,
        val label: String,
        val busyText: String,
        val clip: Selection?,
    )

    /** The layer can be written (not locked or hidden; says why otherwise). */
    private fun checkWritable(controller: EditorController, layer: Layer): Boolean = controller.checkEditable(layer)

    /** The selection as a hole mask (its bounding box only). */
    internal fun holeOf(sel: Selection, docW: Int, docH: Int): HoleMask {
        val b = Rect(sel.bounds)
        if (!b.intersect(0, 0, docW, docH)) return HoleMask(docW, docH, IRect(0, 0, 0, 0), ByteArray(0))
        return HoleMask(docW, docH, IRect(b.left, b.top, b.right, b.bottom), alphaCrop(sel.mask, b))
    }

    /** Coverage bytes of the [r] part of an ALPHA_8 [mask]. */
    internal fun alphaCrop(mask: Bitmap, r: Rect): ByteArray {
        val crop = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ALPHA_8)
        try {
            Canvas(crop).drawBitmap(mask, -r.left.toFloat(), -r.top.toFloat(), BitmapUtils.srcPaint)
            return BitmapUtils.alpha8ToBytes(crop)
        } finally {
            crop.recycle()
        }
    }

    /** Is the layer still there, with the same pixels object, at the document's size? */
    private fun layerUsable(controller: EditorController, req: Request): Boolean {
        val ok = controller.doc.indexOf(req.layer) >= 0 && req.layer.bitmap === req.bitmap && !req.bitmap.isRecycled &&
            req.bitmap.width == controller.doc.width && req.bitmap.height == controller.doc.height
        if (!ok) controller.toast("The layer changed, so ${req.label.lowercase()} was cancelled")
        return ok
    }

    private fun start(controller: EditorController, req: Request, onFinished: (UndoAction?) -> Unit): Boolean {
        val stopped = AtomicBoolean(false)
        val progress = AtomicInteger(-1)
        var job: Job? = null
        val st = state(controller)
        st.running = true
        controller.runBusy(req.busyText, onCancel = { stopped.set(true); job?.cancel() }) {
            job = currentCoroutineContext().job
            var applied: UndoAction? = null
            try {
                if (stopped.get()) return@runBusy
                val plan = withContext(Dispatchers.Default) { ContentAwareFill.plan(req.hole, req.params) }
                if (plan == null) {
                    controller.toast("There is nothing to fill")
                    return@runBusy
                }
                if (!layerUsable(controller, req)) return@runBusy
                val version = req.layer.contentVersion
                val pixels = readSource(controller, req, plan.roi)
                val result = coroutineScope {
                    val ticker = launch {
                        while (true) {
                            val p = progress.get()
                            if (p >= 0) controller.busyProgress = p / 1000f
                            delay(PROGRESS_MS)
                        }
                    }
                    try {
                        withContext(Dispatchers.Default) {
                            val self = currentCoroutineContext().job
                            val monitor = InpaintMonitor(
                                cancelled = { stopped.get() || !self.isActive },
                                onProgress = { progress.set((it * 1000f).toInt()) },
                            )
                            ContentAwareFill.run(plan, pixels, req.params, monitor)
                        }
                    } finally {
                        ticker.cancel()
                    }
                }
                applied = apply(controller, req, result, version)
            } catch (e: InpaintException) {
                controller.toast(e.message ?: "${req.label} failed")
            } finally {
                st.running = false
                onFinished(applied)
            }
        }
        return true
    }

    /** The [roi] pixels the fill samples: the layer's own, or all visible layers merged. */
    private fun readSource(controller: EditorController, req: Request, roi: IRect): PixelBuffer {
        val rect = Rect(roi.left, roi.top, roi.right, roi.bottom)
        return when (req.source) {
            CafSource.LAYER -> BitmapUtils.toPixelBuffer(req.layer.bitmap, rect)
            CafSource.ALL_LAYERS -> {
                val bmp = BitmapUtils.createLayerBitmap(rect.width(), rect.height())
                try {
                    val c = Canvas(bmp)
                    c.translate(-rect.left.toFloat(), -rect.top.toFloat())
                    c.clipRect(rect)
                    controller.compositor.drawDocument(c, rect, useOverrides = false)
                    BitmapUtils.toPixelBuffer(bmp)
                } finally {
                    bmp.recycle()
                }
            }
        }
    }

    /** Writes [result] as ONE undo step; returns that step (null if nothing was applied). */
    private fun apply(controller: EditorController, req: Request, fill: InpaintResult, version: Long): UndoAction? {
        if (!layerUsable(controller, req)) return null
        val layer = req.layer
        if (layer.contentVersion != version) {
            controller.toast("\"${layer.name}\" changed meanwhile, so ${req.label.lowercase()} wasn't applied")
            return null
        }
        val r = fill.rect
        val rect = Rect(r.left, r.top, r.right, r.bottom)
        val result = req.clip?.let { fill.clipped(alphaCrop(it.mask, rect)) } ?: fill
        if (result.isEmpty) return null
        val rw = r.width; val rh = r.height
        val um = controller.undoManager
        val mark = um.undoCount
        when (req.output) {
            CafOutput.NEW_LAYER -> {
                val px = result.layerPixels()
                if (px.all { it == 0 }) {
                    controller.toast("${req.label} found only transparent pixels to fill with")
                    return null
                }
                val bmp = BitmapUtils.createLayerBitmap(rw, rh)
                try {
                    bmp.setPixels(px, 0, rw, 0, 0, rw, rh)
                    controller.addLayerWithContent(FILL_LABEL, req.label) { c -> c.drawBitmap(bmp, r.left.toFloat(), r.top.toFloat(), null) }
                        ?: return null
                } finally {
                    bmp.recycle()
                }
            }
            CafOutput.CURRENT_LAYER -> {
                if (!controller.checkEditable(layer)) return null
                val original = IntArray(rw * rh)
                layer.bitmap.getPixels(original, 0, rw, r.left, r.top, rw, rh)
                val out = result.composite(original, alphaLocked = layer.alphaLocked)
                if (out.contentEquals(original)) {
                    controller.toast("${req.label} didn't change anything on \"${layer.name}\"")
                    return null
                }
                // Pending tool work is settled first (like other layer edits) and resumed after.
                val tool = controller.currentTool
                tool.onDeactivate()
                try {
                    val rec = controller.beginEdit(layer, EditTarget.CONTENT)
                    rec.touch(rect)
                    layer.bitmap.setPixels(out, 0, rw, r.left, r.top, rw, rh)
                    controller.commitEdit(rec, req.label)
                } finally {
                    tool.onActivate()
                }
            }
        }
        // Everything this fill pushed (layer or pixels, plus e.g. a rasterized text layer) is one step.
        val added = um.takeSince(mark)
        if (added.isEmpty()) return null
        val action = if (added.size == 1) added[0] else CompositeAction(req.label, added)
        um.pushRaw(action)
        return action
    }

    /** True if [action] is the newest undo step (checked by identity; the stack is left as it was). */
    private fun isTopStep(controller: EditorController, action: UndoAction): Boolean {
        val um = controller.undoManager
        if (um.undoCount == 0) return false
        val top = um.takeSince(um.undoCount - 1)
        top.forEach { um.pushRaw(it) }
        return top.singleOrNull() === action
    }
}
