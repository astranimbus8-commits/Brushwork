package com.brushwork.paint.masks

import android.graphics.Bitmap
import android.graphics.Rect
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.DeferredStep
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import kotlin.math.max
import kotlin.math.min

/**
 * v1.9 A6 (R13): a released Masks-tool edit of a large mask, rendered at full resolution in bands
 * over the next frames instead of in one go at the release (the Masks tool's preview shows the
 * edit meanwhile). [layer]'s spec goes from [before] to [after] as ONE step [label] when the last
 * band is done ("lands"): [MaskEdits.applyWith] with the bands' pixels, so the step, the data and
 * the pixels are exactly what [MaskEdits.apply] makes (I1; `MaskReleaseParityTest`).
 *
 * Each slice renders bands on the main thread until [budgetNanos] of the live-adjust clock is
 * spent (main-thread Handler, one slice per message). It is a [DeferredStep]: anything that pushes
 * a step, undo / redo or a flush lands it first (the bands still missing are rendered then), so
 * the history keeps its order. A layer that changed under it in another way (gone, its mask
 * deleted, another spec) drops the edit: the mask still renders the spec it has (I1). [onLand]
 * runs first when it lands or is dropped (the tool ends its preview). Main thread.
 */
class MaskRefine(
    private val c: EditorController,
    val layer: Layer,
    val before: MaskSpec,
    val after: MaskSpec,
    private val label: String,
    region: Rect,
    private val brushes: BrushSource?,
    private val budgetNanos: Long = BUDGET_NANOS,
    private val onLand: (MaskRefine) -> Unit,
) : DeferredStep {
    /** The document area rendered (where [before] and [after] can render differently). */
    val region = Rect(region)
    private val w = this.region.width()
    private val h = this.region.height()

    /** The gray byte of every pixel of [region] rendered so far (row by row). */
    private var patch: ByteArray? = ByteArray(w * h)
    private var buf = IntArray(0)

    /** Rows of [region] rendered so far. */
    var rowsDone = 0
        private set

    /** Slices that ran (each within the budget). */
    var slices = 0
        private set

    /** Null while pending; then true (the step was recorded) or false (dropped or refused). */
    var result: Boolean? = null
        private set

    val isPending: Boolean get() = result == null

    private val handler: Handler? = Looper.getMainLooper()?.let { Handler(it) }
    private val slice = Runnable { step() }

    /** Registers the step and schedules the first slice (none: it lands at once). */
    fun start() {
        c.addDeferredStep(this)
        if (handler == null) land() else handler.post(slice)
    }

    /** One slice: bands until the budget is spent; the last one lands the edit. */
    fun step() {
        if (!isPending) return
        slices++
        val t0 = c.liveAdjust.clock()
        while (rowsDone < h) {
            renderBand()
            if (c.liveAdjust.clock() - t0 >= budgetNanos) break
        }
        if (rowsDone >= h) land() else handler?.post(slice) ?: land()
    }

    private fun renderBand() {
        val rows = min(max(1, BAND_PIXELS / max(1, w)), h - rowsDone)
        if (buf.size < w * rows) buf = IntArray(w * rows)
        MaskSpecRenderer.render(after, c.doc.width, c.doc.height, region.left, region.top + rowsDone, w, rows, buf, w, brushes)
        val p = patch ?: return
        val base = rowsDone * w
        // The render is opaque gray (MaskSpecRenderer.gray): its blue byte is the value.
        for (i in 0 until w * rows) p[base + i] = buf[i].toByte()
        rowsDone += rows
    }

    /** [DeferredStep]: lands now (the bands still missing are rendered first). */
    override fun flush() = land()

    /** Ends the refine: the edit is recorded (or dropped, see the class), at most once. */
    fun land() {
        if (!isPending) return
        handler?.removeCallbacks(slice)
        c.removeDeferredStep(this)
        val same = c.doc.indexOf(layer) >= 0 && layer.mask != null && layer.maskSpec == before
        if (!same) {
            result = false
            release()
            onLand(this)
            return
        }
        while (rowsDone < h) renderBand()
        // Set before [onLand] and the step: a re-entrant flush finds nothing pending.
        result = true
        onLand(this)
        result = MaskEdits.applyWith(c, layer, after, label, region, brushes) { mask, reg -> write(mask, reg) }
        release()
    }

    /** Writes the rendered pixels of [region] into [mask] (another region: rendered there instead). */
    private fun write(mask: Bitmap, reg: Rect) {
        val p = patch
        if (p == null || reg != region || rowsDone < h) {
            MaskSpecs.renderInto(mask, after, reg, brushes)
            return
        }
        val band = min(h, max(1, BAND_PIXELS / max(1, w)))
        if (buf.size < w * band) buf = IntArray(w * band)
        var y = 0
        while (y < h) {
            val rows = min(band, h - y)
            val base = y * w
            for (i in 0 until w * rows) {
                val g = p[base + i].toInt() and 0xFF
                buf[i] = (0xFF shl 24) or (g shl 16) or (g shl 8) or g
            }
            mask.setPixels(buf, 0, w, region.left, region.top + y, w, rows)
            y += rows
        }
    }

    private fun release() {
        patch = null
        buf = IntArray(0)
    }

    companion object {
        /** Main-thread time per slice (ns of the live-adjust clock): half a 60 Hz frame. */
        const val BUDGET_NANOS = 6_000_000L

        /** Pixels per band (a band above 64 K px is split over the cores by the renderer). */
        const val BAND_PIXELS = 1 shl 16
    }
}
