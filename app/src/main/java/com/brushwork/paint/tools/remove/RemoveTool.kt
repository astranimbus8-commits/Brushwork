package com.brushwork.paint.tools.remove

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.inpaint.HoleMask
import com.brushwork.paint.inpaint.IRect
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.ceil
import kotlin.math.floor

/**
 * Remove tool: brush over something (a round brush; the painted area shows in translucent red)
 * and when the finger lifts the area is filled from its surroundings with content-aware fill
 * (active layer, or sampled from all layers), as one "Remove" undo step. The selection, if any,
 * limits both the painted area and the change. A second finger cancels the stroke without a trace.
 */
class RemoveTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.REMOVE

    var settings: RemoveSettings by StoredSetting(controller.settings, "remove.settings", RemoveSettings.serializer(), RemoveSettings())

    /** True while a painted area is being filled (a new stroke waits until it is done). */
    var busy by mutableStateOf(false)
        private set

    private val path = Path()
    private var stroking = false
    private var strokeSize = 0f
    private var cursorX = 0f
    private var cursorY = 0f

    /** The stroke being filled, shown until the fill is applied or stopped. */
    private var filling: Path? = null
    private var fillingSize = 0f

    private val maskPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply {
        style = Paint.Style.STROKE
        strokeCap = Paint.Cap.ROUND
        strokeJoin = Paint.Join.ROUND
        color = OVERLAY_COLOR
    }
    private val ringPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = -1 }
    private val ringShadow = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }

    override fun onDown(p: ToolPoint) {
        if (busy || controller.busyMessage != null) return
        if (!controller.checkEditable()) return
        stroking = true
        strokeSize = settings.size.coerceIn(RemoveSettings.MIN_SIZE, RemoveSettings.MAX_SIZE)
        path.reset()
        path.moveTo(p.x, p.y)
        // A tap removes a round spot: a zero-length line would draw nothing.
        path.lineTo(p.x + 0.01f, p.y)
        cursorX = p.x; cursorY = p.y
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (!stroking) return
        path.lineTo(p.x, p.y)
        cursorX = p.x; cursorY = p.y
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        if (!stroking) return
        path.lineTo(p.x, p.y)
        stroking = false
        finishStroke()
    }

    override fun onCancel() {
        if (!stroking) return
        stroking = false
        path.reset()
        controller.invalidateOverlay()
    }

    /** A running fill keeps going (it is applied to the layer it started on). */
    override fun onDeactivate() = onCancel()

    override fun onDispose() = onCancel()

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val shown = if (stroking) path else filling
        val size = if (stroking) strokeSize else fillingSize
        if (shown == null) return
        val zoom = t.zoom
        if (zoom <= 1e-6f) return
        canvas.save()
        canvas.concat(t.matrix)
        maskPaint.strokeWidth = size
        canvas.drawPath(shown, maskPaint)
        if (stroking) {
            // Brush outline under the finger, a constant 1.5 dp on screen.
            val w = t.dp(1.5f) / zoom
            ringShadow.strokeWidth = w * 2f
            ringPaint.strokeWidth = w
            canvas.drawCircle(cursorX, cursorY, size / 2f, ringShadow)
            canvas.drawCircle(cursorX, cursorY, size / 2f, ringPaint)
        }
        canvas.restore()
    }

    private fun finishStroke() {
        val stroke = Path(path)
        path.reset()
        val size = strokeSize
        val doc = controller.doc
        val sel = controller.selection
        val hole = try {
            strokeMask(stroke, size, doc.width, doc.height, sel)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to remove this")
            null
        }
        if (hole == null) {
            if (sel != null) controller.toast("Paint inside the selection to remove something")
            controller.invalidateOverlay()
            return
        }
        filling = stroke
        fillingSize = size
        busy = true
        controller.invalidateOverlay()
        ContentAwareFillJob.removeArea(controller, controller.activeLayer, hole, sel, settings) {
            busy = false
            filling = null
            controller.invalidateOverlay()
        }
    }

    companion object {
        /** Translucent red of the painted area. */
        const val OVERLAY_COLOR = 0x80FF3B30.toInt()

        /**
         * Coverage of a round brush of diameter [size] along [path] (document coordinates) as a
         * hole mask, limited to [selection]; null when nothing of it is on the canvas / selected.
         */
        internal fun strokeMask(path: Path, size: Float, docW: Int, docH: Int, selection: Selection?): HoleMask? {
            val b = RectF()
            path.computeBounds(b, true)
            val pad = size / 2f + 2f
            val r = Rect(floor(b.left - pad).toInt(), floor(b.top - pad).toInt(), ceil(b.right + pad).toInt(), ceil(b.bottom + pad).toInt())
            if (!r.intersect(0, 0, docW, docH)) return null
            val bmp = Bitmap.createBitmap(r.width(), r.height(), Bitmap.Config.ALPHA_8)
            val bytes = try {
                val c = Canvas(bmp)
                c.translate(-r.left.toFloat(), -r.top.toFloat())
                c.drawPath(path, Paint(Paint.ANTI_ALIAS_FLAG).apply {
                    style = Paint.Style.STROKE
                    strokeCap = Paint.Cap.ROUND
                    strokeJoin = Paint.Join.ROUND
                    strokeWidth = size
                    color = 0xFF000000.toInt()
                })
                BitmapUtils.alpha8ToBytes(bmp)
            } finally {
                bmp.recycle()
            }
            if (selection != null) {
                val clip = ContentAwareFillJob.alphaCrop(selection.mask, r)
                for (i in bytes.indices) bytes[i] = (((bytes[i].toInt() and 0xFF) * (clip[i].toInt() and 0xFF) + 127) / 255).toByte()
            }
            if (bytes.all { it.toInt() == 0 }) return null
            return HoleMask(docW, docH, IRect(r.left, r.top, r.right, r.bottom), bytes)
        }
    }
}
