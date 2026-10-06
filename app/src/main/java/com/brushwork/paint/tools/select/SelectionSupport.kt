package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.mutableStateOf
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlin.math.max
import kotlin.math.min
import kotlin.properties.ReadWriteProperty
import kotlin.reflect.KProperty

/** Which pixels a tool analyses. */
@Serializable
enum class SampleSource(val label: String) {
    LAYER("Layer"),
    CANVAS("Canvas"),
}

/**
 * A plain tap of Lasso / Select shape in "New" mode: nothing is selected any more. That is the
 * pixel selection (one "Deselect" step, as before) and, on a vector layer, the selected objects
 * too (v1.5: object selection is not history, so that part records nothing).
 */
internal fun deselectOnTap(c: EditorController) {
    if (c.selection != null) c.deselect()
    if (c.vectors.selectedLayer != null) c.vectors.setSelection(null, emptySet())
}

/** ALPHA_8 selection-mask helpers (thread-safe: they only touch the bitmaps passed in). */
internal object SelectionMasks {
    /** Reads [rect] (inside the mask) of an ALPHA_8 mask as packed bytes. */
    fun crop(mask: Bitmap, rect: Rect): ByteArray {
        if (rect.left == 0 && rect.top == 0 && rect.width() == mask.width && rect.height() == mask.height) {
            return BitmapUtils.alpha8ToBytes(mask)
        }
        val tmp = Bitmap.createBitmap(rect.width(), rect.height(), Bitmap.Config.ALPHA_8)
        Canvas(tmp).drawBitmap(mask, -rect.left.toFloat(), -rect.top.toFloat(), Paint())
        val out = BitmapUtils.alpha8ToBytes(tmp)
        tmp.recycle()
        return out
    }

    /** A [docW] x [docH] selection whose mask is the [w] x [h] window [bytes] placed at (left, top). */
    fun fromWindow(bytes: ByteArray, left: Int, top: Int, w: Int, h: Int, docW: Int, docH: Int): Selection {
        if (left == 0 && top == 0 && w == docW && h == docH) return Selection.fromBytes(bytes, w, h)
        val full = Bitmap.createBitmap(docW, docH, Bitmap.Config.ALPHA_8)
        if (w > 0 && h > 0) {
            val win = BitmapUtils.bytesToAlpha8(bytes, w, h)
            Canvas(full).drawBitmap(win, left.toFloat(), top.toFloat(), Paint())
            win.recycle()
        }
        return Selection.wrap(full)
    }

    /** [rect] grown by [margin] and clamped to the document. */
    fun window(rect: Rect, margin: Int, docW: Int, docH: Int): Rect =
        Rect(max(0, rect.left - margin), max(0, rect.top - margin), min(docW, rect.right + margin), min(docH, rect.bottom + margin))

    /**
     * Combines a new selection with the existing one. Returns null for "nothing selected"
     * (e.g. subtracting from or intersecting with no selection).
     */
    fun combine(base: Selection?, new: Selection, mode: SelectionMode): Selection? = when {
        mode == SelectionMode.REPLACE -> new
        base == null -> if (mode == SelectionMode.ADD) new else null
        mode == SelectionMode.INTERSECT -> intersect(base, new)
        else -> base.combine(new, mode)
    }

    /**
     * [a] × [b] per pixel. Done on bytes because Skia draws an ALPHA_8 bitmap as coverage, which
     * makes a DST_IN draw of one mask onto the other (Selection.combine) a no-op.
     */
    fun intersect(a: Selection, b: Selection): Selection {
        val r = Rect(a.bounds)
        if (!r.intersect(b.bounds)) return Selection.empty(a.width, a.height)
        val x = crop(a.mask, r)
        val y = crop(b.mask, r)
        for (i in x.indices) {
            val p = (x[i].toInt() and 0xFF) * (y[i].toInt() and 0xFF)
            x[i] = ((p + 127) / 255).toByte()
        }
        return fromWindow(x, r.left, r.top, r.width(), r.height(), a.width, a.height)
    }
}

/** Pixel copies for background analysis. */
internal object PixelSnapshot {
    /**
     * An immutable copy to analyse off the main thread: the flattened canvas, or [layerBitmap].
     * v1.7 (rule C): a folder's shared bitmap is never copied; with a folder active the magic
     * wand and object select read the composite, as "Sample all layers" does.
     * MUST be called on the main thread; the caller recycles the result.
     */
    fun take(controller: EditorController, source: SampleSource, layerBitmap: Bitmap): Bitmap =
        if (source == SampleSource.CANVAS || layerBitmap === Layer.FOLDER_BITMAP) controller.compositor.renderFlattened()
        else layerBitmap.copy(Bitmap.Config.ARGB_8888, false)

    /**
     * Builds the [RegionFill] map for a seed at (sx, sy) by reading [snapshot] in bands (no full
     * IntArray copy). Pixels whose [clip] byte is 0 are never passable. Null when cancelled.
     */
    fun similarityMap(snapshot: Bitmap, sx: Int, sy: Int, tolerance: Int, clip: ByteArray?, cancelled: () -> Boolean): ByteArray? {
        val w = snapshot.width; val h = snapshot.height
        val seed = snapshot.getPixel(sx, sy)
        val map = ByteArray(w * h)
        val band = max(1, min(h, (1 shl 19) / w))
        val buf = IntArray(w * band)
        var y = 0
        while (y < h) {
            if (cancelled()) return null
            val rows = min(band, h - y)
            snapshot.getPixels(buf, 0, w, 0, y, w, rows)
            RegionFill.markSimilar(buf, 0, w * rows, seed, tolerance, map, y * w, clip, y * w)
            y += rows
        }
        return map
    }
}

/** Background work helpers shared by the selection tools and the selection panel. */
internal object SelectionJobs {
    /** After this delay a still-running job also shows the controller's busy overlay. */
    private const val OVERLAY_DELAY_MS = 400L

    /**
     * Runs [build] on Dispatchers.Default and publishes its result combined (by [mode]) with the
     * selection that is current when it finishes. Undoable (setSelection records it). [build]
     * returns null to change nothing. If [emptyMessage] is given, an empty result shows it
     * instead of changing the selection. [onFinished] runs on the main thread in every case.
     *
     * [toObjects] (lasso, select shape; v1.5): when the active layer is a vector layer, the area
     * selects the objects it touches (`controller.vectors.selectObjects`) instead of pixels; a
     * selection the vector service doesn't take is published as pixels as before.
     */
    fun applyAsync(
        controller: EditorController,
        label: String,
        mode: SelectionMode,
        busyLabel: String,
        emptyMessage: String? = null,
        onFinished: () -> Unit = {},
        toObjects: Boolean = false,
        build: (cancelled: () -> Boolean) -> Selection?,
    ): Job {
        val base = controller.selection
        val docW = controller.doc.width; val docH = controller.doc.height
        val job = controller.scope.launch {
            val self = coroutineContext[Job]
            try {
                val (fresh, combined) = withContext(Dispatchers.Default) {
                    val s = build { self?.isActive == false } ?: return@withContext null to null
                    if (s.isEmpty && emptyMessage != null) return@withContext s to null
                    s to SelectionMasks.combine(base, s, mode)
                }
                if (fresh == null) return@launch
                if (fresh.isEmpty && emptyMessage != null) {
                    controller.toast(emptyMessage)
                    return@launch
                }
                if (controller.doc.width != docW || controller.doc.height != docH) return@launch
                // On a vector layer the area selects objects (the funnel of every area tool).
                if (toObjects && controller.activeLayer.isVectorLayer && controller.vectors.selectObjects(fresh, mode)) return@launch
                val current = controller.selection
                val result = if (current === base) combined else SelectionMasks.combine(current, fresh, mode)
                controller.setSelection(result, label = label)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                controller.toast("Not enough memory for \"$label\"")
            } catch (e: Exception) {
                controller.toast("$label failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                onFinished()
            }
        }
        showBusyIfSlow(controller, job, busyLabel)
        return job
    }

    /**
     * Shows the busy overlay while [job] runs, but only if it takes noticeably long. Its Stop
     * button cancels the job (the jobs poll for cancellation and change nothing when stopped).
     */
    fun showBusyIfSlow(controller: EditorController, job: Job, label: String) {
        controller.scope.launch {
            delay(OVERLAY_DELAY_MS)
            if (job.isActive && controller.busyMessage == null) {
                controller.runBusy(label, onCancel = { job.cancel() }) { job.join() }
            }
        }
    }
}

/**
 * A tool option stored in [AppSettings] (JSON) and exposed as Compose state, so option strips
 * recompose and values survive restarts.
 */
internal class PersistedOption<T>(
    private val store: AppSettings,
    private val key: String,
    private val serializer: KSerializer<T>,
    default: T,
) : ReadWriteProperty<Any?, T> {
    private val state = mutableStateOf(runCatching { store.getObject(key, serializer) }.getOrNull() ?: default)

    override fun getValue(thisRef: Any?, property: KProperty<*>): T = state.value

    override fun setValue(thisRef: Any?, property: KProperty<*>, value: T) {
        if (value == state.value) return
        state.value = value
        runCatching { store.putObject(key, serializer, value) }
    }
}

/** Screen-space drawing helpers for the selection tools' live previews. */
internal object SelectionOverlay {
    private val white = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1 }
    private val black = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0xFF000000.toInt() }
    private val dot = Paint(Paint.ANTI_ALIAS_FLAG)
    private val labelBg = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xCC1E1F22.toInt() }
    private val labelText = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = -1; textAlign = Paint.Align.CENTER }
    private val tmp = RectF()

    /** Dashed black-on-white outline of a DOCUMENT-space path, constant width on screen. */
    fun drawDocPath(canvas: Canvas, t: ViewTransform, path: Path, phase: Float = 0f) {
        val zoom = t.zoom
        if (zoom <= 1e-6f) return
        canvas.save()
        canvas.concat(t.matrix)
        prepare(t, 1f / zoom, phase)
        canvas.drawPath(path, white)
        canvas.drawPath(path, black)
        canvas.restore()
    }

    /** Same as [drawDocPath] for a document-space rectangle. */
    fun drawDocRect(canvas: Canvas, t: ViewTransform, rect: RectF, phase: Float = 0f) {
        val zoom = t.zoom
        if (zoom <= 1e-6f) return
        canvas.save()
        canvas.concat(t.matrix)
        prepare(t, 1f / zoom, phase)
        canvas.drawRect(rect, white)
        canvas.drawRect(rect, black)
        canvas.restore()
    }

    /** Dashed outline of a SCREEN-space path. */
    fun drawScreenPath(canvas: Canvas, t: ViewTransform, path: Path) {
        prepare(t, 1f, 0f)
        canvas.drawPath(path, white)
        canvas.drawPath(path, black)
    }

    private fun prepare(t: ViewTransform, scale: Float, phase: Float) {
        val width = max(1f, t.dp(1f)) * scale
        white.strokeWidth = width
        black.strokeWidth = width
        val dash = t.dp(4f) * scale
        black.pathEffect = DashPathEffect(floatArrayOf(dash, dash), (phase * scale) % (2f * dash))
    }

    /** A vertex handle in screen space. */
    fun drawVertex(canvas: Canvas, t: ViewTransform, x: Float, y: Float, highlighted: Boolean) {
        val r = t.dp(if (highlighted) 7f else 4f)
        dot.style = Paint.Style.FILL
        dot.color = if (highlighted) 0xFF4DA3FF.toInt() else -1
        canvas.drawCircle(x, y, r, dot)
        dot.style = Paint.Style.STROKE
        dot.strokeWidth = t.dp(1.5f)
        dot.color = 0xFF000000.toInt()
        canvas.drawCircle(x, y, r, dot)
    }

    /** A small rounded label centered at (cx, cy) in screen space. */
    fun drawLabel(canvas: Canvas, t: ViewTransform, text: String, cx: Float, cy: Float) {
        labelText.textSize = t.dp(13f)
        val tw = labelText.measureText(text)
        val padH = t.dp(8f); val padV = t.dp(5f)
        val fm = labelText.fontMetrics
        val th = fm.descent - fm.ascent
        tmp.set(cx - tw / 2 - padH, cy - th / 2 - padV, cx + tw / 2 + padH, cy + th / 2 + padV)
        canvas.drawRoundRect(tmp, t.dp(6f), t.dp(6f), labelBg)
        canvas.drawText(text, cx, cy - (fm.ascent + fm.descent) / 2, labelText)
    }
}
