package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.segmentation.ObjectPrompt
import com.brushwork.paint.segmentation.SegmentationService
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.Job
import java.util.concurrent.atomic.AtomicBoolean
import kotlin.math.hypot

/**
 * Object select (like Photoshop's Object Selection): tap an object, or scribble over it, and the
 * on-device model (MediaPipe MagicTouch) selects the whole object, refined to its edges; the
 * result is combined with the current selection by [mode]. Where the model cannot run, similar
 * colors connected to the tap are selected instead (and the user is told once).
 *
 * The work runs in the background with a busy indicator and can be cancelled ([cancel], or the
 * Stop button of the busy overlay); taps are ignored while it runs.
 */
class ObjectSelectTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.OBJECT_SELECT

    var settings: ObjectSelectSettings by PersistedOption(controller.settings, "select.object", ObjectSelectSettings.serializer(), ObjectSelectSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    /** True while a selection is being computed (Compose state: the options strip shows it). */
    var busy by mutableStateOf(false)
        private set

    private var job: Job? = null
    private var down: ToolPoint? = null
    private var dragging = false
    private val stroke = ArrayList<Float>()

    /** What is being computed, drawn on the canvas meanwhile (document coordinates). */
    private var pending: ObjectPrompt? = null
    private var toldFallback = false

    override fun onSelected() {
        SegmentationService.get(controller.appContext).prepareObjectSelect()
    }

    override fun onDown(p: ToolPoint) {
        if (busy) return
        down = p
        dragging = false
        stroke.clear()
        stroke += p.x; stroke += p.y
    }

    override fun onMove(p: ToolPoint) {
        val d = down ?: return
        val t = controller.viewTransform
        if (!dragging && hypot(p.x - d.x, p.y - d.y) > t.screenToDocLength(t.dp(TAP_SLOP_DP))) dragging = true
        if (!dragging) return
        val n = stroke.size
        if (hypot(p.x - stroke[n - 2], p.y - stroke[n - 1]) >= t.screenToDocLength(t.dp(3f))) {
            stroke += p.x; stroke += p.y
            controller.invalidateOverlay()
        }
    }

    override fun onUp(p: ToolPoint) {
        val d = down ?: return
        down = null
        if (dragging) {
            stroke += p.x; stroke += p.y
            val pts = stroke.toFloatArray()
            dragging = false
            stroke.clear()
            selectWith(ObjectPrompt(pts))
        } else {
            stroke.clear()
            selectWith(ObjectPrompt.tap(d.x, d.y))
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        down = null
        dragging = false
        stroke.clear()
        controller.invalidateOverlay()
    }

    /** Selects the object at document position (x, y). */
    fun selectAt(x: Float, y: Float) = selectWith(ObjectPrompt.tap(x, y))

    /**
     * Selects the object under [prompt] (document coordinates). Ignored while busy, and when no
     * point of the prompt is on the canvas; a scribble that leaves the canvas keeps its longest
     * part on it ([onCanvas]).
     */
    fun selectWith(prompt: ObjectPrompt) {
        if (busy) return
        val doc = controller.doc
        val w = doc.width; val h = doc.height
        val target = onCanvas(prompt, w, h) ?: return
        val s = settings
        val snapshot: Bitmap = try {
            PixelSnapshot.take(controller, s.source, controller.activeLayer.bitmap)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for object select")
            return
        }
        val usedModel = AtomicBoolean(true)
        val failed = AtomicBoolean(false)
        val service = SegmentationService.get(controller.appContext)
        busy = true
        pending = target
        controller.invalidateOverlay()
        job = SelectionJobs.applyAsync(
            controller, "Object select", mode, "Selecting object…",
            emptyMessage = "No object found there. Try tapping its middle, or scribble over it.",
            onFinished = {
                busy = false
                pending = null
                job = null
                controller.invalidateOverlay()
                if (failed.get()) {
                    controller.toast("Object select didn't work on this picture (not enough memory?)")
                } else if (!usedModel.get() && !toldFallback) {
                    toldFallback = true
                    controller.toast("The object model couldn't run here: selected similar colors instead")
                }
            },
        ) { cancelled ->
            val buffer = try {
                BitmapUtils.toPixelBuffer(snapshot)
            } finally {
                snapshot.recycle()
            }
            if (cancelled()) return@applyAsync null
            val r = service.selectObject(buffer, target, s.refineEdges, cancelled)
            if (r == null) {
                if (!cancelled()) failed.set(true)
                return@applyAsync null
            }
            usedModel.set(r.usedModel)
            if (cancelled()) null else Selection.fromFloats(r.mask, w, h)
        }
    }

    /** Stops the running selection (nothing changes). */
    fun cancel() {
        job?.cancel()
    }

    override fun onDispose() {
        job?.cancel()
    }

    private val path = Path()
    private val mapped = FloatArray(2)
    private val strokePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; strokeCap = Paint.Cap.ROUND; strokeJoin = Paint.Join.ROUND }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG)

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (dragging && stroke.size >= 4) drawStroke(canvas, t, stroke.toFloatArray())
        val p = pending ?: return
        if (p.count > 1) drawStroke(canvas, t, p.points) else drawTap(canvas, t, p.x(0), p.y(0))
    }

    private fun drawStroke(canvas: Canvas, t: ViewTransform, pts: FloatArray) {
        path.reset()
        for (i in 0 until pts.size / 2) {
            mapped[0] = pts[i * 2]; mapped[1] = pts[i * 2 + 1]
            t.matrix.mapPoints(mapped)
            if (i == 0) path.moveTo(mapped[0], mapped[1]) else path.lineTo(mapped[0], mapped[1])
        }
        strokePaint.color = 0xAA000000.toInt()
        strokePaint.strokeWidth = t.dp(6f)
        canvas.drawPath(path, strokePaint)
        strokePaint.color = ACCENT
        strokePaint.strokeWidth = t.dp(3.5f)
        canvas.drawPath(path, strokePaint)
    }

    private fun drawTap(canvas: Canvas, t: ViewTransform, x: Float, y: Float) {
        mapped[0] = x; mapped[1] = y
        t.matrix.mapPoints(mapped)
        val cx = mapped[0]; val cy = mapped[1]
        strokePaint.color = 0xAA000000.toInt()
        strokePaint.strokeWidth = t.dp(4f)
        canvas.drawCircle(cx, cy, t.dp(12f), strokePaint)
        strokePaint.color = ACCENT
        strokePaint.strokeWidth = t.dp(2f)
        canvas.drawCircle(cx, cy, t.dp(12f), strokePaint)
        fillPaint.color = ACCENT
        canvas.drawCircle(cx, cy, t.dp(3f), fillPaint)
    }

    companion object {
        /** A finger that moves farther than this draws a scribble instead of tapping. */
        const val TAP_SLOP_DP = 16f

        private const val ACCENT = 0xFF4DA3FF.toInt()

        /**
         * The part of [prompt] that is on a [w]x[h] canvas: its longest run of consecutive points
         * inside it. A scribble that leaves the canvas is cut there instead of being pulled along
         * the canvas edge (which would ask the model for whatever lies on the edge). Null when no
         * point is on the canvas.
         */
        internal fun onCanvas(prompt: ObjectPrompt, w: Int, h: Int): ObjectPrompt? {
            val pts = prompt.points
            val n = prompt.count
            var bestStart = 0; var bestLen = 0
            var start = -1
            for (i in 0..n) {
                val inside = i < n && pts[2 * i] >= 0f && pts[2 * i + 1] >= 0f && pts[2 * i] < w && pts[2 * i + 1] < h
                if (inside) {
                    if (start < 0) start = i
                } else if (start >= 0) {
                    if (i - start > bestLen) { bestLen = i - start; bestStart = start }
                    start = -1
                }
            }
            if (bestLen == 0) return null
            if (bestLen == n) return prompt
            return ObjectPrompt(pts.copyOfRange(bestStart * 2, (bestStart + bestLen) * 2))
        }
    }
}
