package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.sign

/**
 * Rectangle / ellipse selection by dragging, optionally square (1:1) and from the center. Shows
 * the live outline and its size in pixels. A plain tap in "New" mode deselects.
 */
class MarqueeTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.MARQUEE

    var settings: MarqueeSettings by PersistedOption(controller.settings, "select.marquee", MarqueeSettings.serializer(), MarqueeSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    var busy by mutableStateOf(false)
        private set

    private var startX = 0f
    private var startY = 0f
    private var curX = 0f
    private var curY = 0f
    private var dragging = false
    private var committing: Path? = null
    private val previewPath = Path()

    override fun onDown(p: ToolPoint) {
        if (busy) return
        startX = p.x; startY = p.y; curX = p.x; curY = p.y
        dragging = true
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (!dragging) return
        curX = p.x; curY = p.y
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        if (!dragging) return
        dragging = false
        curX = p.x; curY = p.y
        val t = controller.viewTransform
        val r = shapeRect(startX, startY, curX, curY, settings)
        val tooSmall = max(abs(curX - startX), abs(curY - startY)) < t.screenToDocLength(t.dp(8f)) || r.width() < 1f || r.height() < 1f
        if (tooSmall) {
            if (mode == SelectionMode.REPLACE && controller.selection != null) controller.deselect()
        } else {
            apply(shapePath(r, settings.shape), settings.shape == MarqueeShape.ELLIPSE)
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        dragging = false
        controller.invalidateOverlay()
    }

    private fun apply(path: Path, antiAlias: Boolean) {
        val docW = controller.doc.width; val docH = controller.doc.height
        val work = Path(path)
        committing = path
        busy = true
        val label = if (settings.shape == MarqueeShape.ELLIPSE) "Ellipse selection" else "Rectangle selection"
        SelectionJobs.applyAsync(controller, label, mode, "Selecting…", onFinished = {
            busy = false
            committing = null
            controller.invalidateOverlay()
        }) { cancelled ->
            if (cancelled()) null else Selection.fromPath(work, docW, docH, antiAlias)
        }
    }

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        committing?.let { SelectionOverlay.drawDocPath(canvas, t, it) }
        if (!dragging) return
        val r = shapeRect(startX, startY, curX, curY, settings)
        previewPath.reset()
        addShape(previewPath, r, settings.shape)
        SelectionOverlay.drawDocPath(canvas, t, previewPath)
        val pos = t.docToScreen(curX, curY)
        val label = "${r.width().roundToInt()} × ${r.height().roundToInt()} px"
        // Above the finger so it stays readable (below it near the top edge of the view).
        val above = pos.y - t.dp(56f)
        SelectionOverlay.drawLabel(canvas, t, label, pos.x, if (above > t.dp(20f)) above else pos.y + t.dp(56f))
    }

    companion object {
        /**
         * The selection rectangle for a drag from (x0, y0) to (x1, y1), with the square and
         * from-center constraints. Edges are snapped to whole pixels so rectangles are crisp and
         * the size readout matches the result.
         */
        internal fun shapeRect(x0: Float, y0: Float, x1: Float, y1: Float, s: MarqueeSettings): RectF {
            var dx = x1 - x0
            var dy = y1 - y0
            if (s.square) {
                val side = max(abs(dx), abs(dy))
                dx = side * (if (dx < 0f) -1f else 1f)
                dy = side * (if (dy < 0f) -1f else 1f)
            }
            val r = if (s.fromCenter) {
                val cx = x0.roundToInt().toFloat(); val cy = y0.roundToInt().toFloat()
                val hx = abs(dx).roundToInt().toFloat(); val hy = abs(dy).roundToInt().toFloat()
                RectF(cx - hx, cy - hy, cx + hx, cy + hy)
            } else {
                val ax = x0.roundToInt().toFloat(); val ay = y0.roundToInt().toFloat()
                val bx = ax + sign(dx) * abs(dx).roundToInt(); val by = ay + sign(dy) * abs(dy).roundToInt()
                RectF(minOf(ax, bx), minOf(ay, by), maxOf(ax, bx), maxOf(ay, by))
            }
            return r
        }

        internal fun shapePath(r: RectF, shape: MarqueeShape): Path = Path().also { addShape(it, r, shape) }

        private fun addShape(path: Path, r: RectF, shape: MarqueeShape) {
            if (shape == MarqueeShape.ELLIPSE) path.addOval(r, Path.Direction.CW) else path.addRect(r, Path.Direction.CW)
        }
    }
}
