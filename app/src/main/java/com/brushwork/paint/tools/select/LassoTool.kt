package com.brushwork.paint.tools.select

import android.graphics.Canvas
import android.graphics.Path
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min

/**
 * Lasso selection. Freehand: drag around the area. Polygon: tap the corners; tapping near the
 * first corner (or ✓) closes the shape, ✕ discards it. A plain tap in "New" mode deselects.
 */
class LassoTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.LASSO

    var settings: LassoSettings by PersistedOption(controller.settings, "select.lasso", LassoSettings.serializer(), LassoSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    /** Committed polygon corners (Compose state so ✓/✕ appear). */
    var vertexCount by mutableIntStateOf(0)
        private set

    /** True while the selection is being rasterized. */
    var busy by mutableStateOf(false)
        private set

    override val hasPendingWork: Boolean get() = settings.polygon && vertexCount > 0

    private val stroke = PointList()
    private val vertices = PointList()
    private var dragging = false
    private var cursorX = 0f
    private var cursorY = 0f
    private var cursorDown = false
    /** Shape shown until its selection has been applied (avoids a blank frame). */
    private var committing: Path? = null
    private val screenPath = Path()
    private val mapped = FloatArray(2)

    /** Switches between freehand and polygon mode (drops an unfinished polygon). */
    fun setPolygonMode(polygon: Boolean) {
        if (polygon == settings.polygon) return
        discard()
        settings = settings.copy(polygon = polygon)
    }

    // ------------------------------------------------------------------ input

    override fun onDown(p: ToolPoint) {
        if (busy) return
        if (settings.polygon) {
            cursorX = p.x; cursorY = p.y; cursorDown = true
        } else {
            stroke.clear()
            stroke.add(p.x, p.y)
            dragging = true
        }
        controller.invalidateOverlay()
    }

    override fun onMove(p: ToolPoint) {
        if (settings.polygon) {
            if (!cursorDown) return
            cursorX = p.x; cursorY = p.y
        } else {
            if (!dragging) return
            val minStep = controller.viewTransform.screenToDocLength(controller.viewTransform.dp(1.5f))
            if (hypot(p.x - stroke.lastX, p.y - stroke.lastY) < minStep) return
            stroke.add(p.x, p.y)
        }
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        val t = controller.viewTransform
        if (settings.polygon) {
            if (!cursorDown) return
            cursorDown = false
            val closeDist = t.screenToDocLength(t.dp(22f))
            when {
                vertices.size >= 3 && hypot(p.x - vertices.x(0), p.y - vertices.y(0)) <= closeDist -> commit()
                vertices.size == 0 || hypot(p.x - vertices.lastX, p.y - vertices.lastY) > t.screenToDocLength(t.dp(3f)) -> {
                    vertices.add(p.x, p.y)
                    vertexCount = vertices.size
                }
            }
        } else {
            if (!dragging) return
            dragging = false
            stroke.add(p.x, p.y)
            val tap = stroke.extent() < t.screenToDocLength(t.dp(8f))
            if (tap || stroke.size < 3) {
                stroke.clear()
                if (tap && mode == SelectionMode.REPLACE && controller.selection != null) controller.deselect()
            } else {
                val path = stroke.toPath()
                stroke.clear()
                apply(path)
            }
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        // Only the current gesture is dropped; committed polygon corners stay.
        dragging = false
        cursorDown = false
        stroke.clear()
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ pending polygon

    override fun commit() {
        if (vertices.size >= 3) {
            val path = vertices.toPath()
            vertices.clear()
            vertexCount = 0
            apply(path)
        } else {
            discard()
        }
        controller.invalidateOverlay()
    }

    override fun discard() {
        vertices.clear()
        vertexCount = 0
        cursorDown = false
        controller.invalidateOverlay()
    }

    private fun apply(path: Path) {
        val docW = controller.doc.width; val docH = controller.doc.height
        val aa = settings.antiAlias
        val work = Path(path) // the background job gets its own copy
        committing = path
        busy = true
        SelectionJobs.applyAsync(controller, "Lasso", mode, "Selecting…", onFinished = {
            busy = false
            committing = null
            controller.invalidateOverlay()
        }) { cancelled ->
            if (cancelled()) null else rasterize(work, docW, docH, aa)
        }
    }

    // ------------------------------------------------------------------ overlay

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        committing?.let { SelectionOverlay.drawDocPath(canvas, t, it) }
        if (dragging && stroke.size > 1) {
            stroke.toScreenPath(t, screenPath, close = true)
            SelectionOverlay.drawScreenPath(canvas, t, screenPath)
        }
        if (settings.polygon && (vertices.size > 0 || cursorDown)) {
            vertices.toScreenPath(t, screenPath, close = false)
            if (cursorDown) {
                map(t, cursorX, cursorY)
                if (vertices.size == 0) screenPath.moveTo(mapped[0], mapped[1]) else screenPath.lineTo(mapped[0], mapped[1])
            }
            SelectionOverlay.drawScreenPath(canvas, t, screenPath)
            for (i in 0 until vertices.size) {
                map(t, vertices.x(i), vertices.y(i))
                SelectionOverlay.drawVertex(canvas, t, mapped[0], mapped[1], highlighted = i == 0 && vertices.size >= 3)
            }
        }
    }

    private fun map(t: ViewTransform, x: Float, y: Float) {
        mapped[0] = x; mapped[1] = y
        t.matrix.mapPoints(mapped)
    }

    companion object {
        /** Rasterizes a closed lasso path into a document-sized selection (blocking; any thread). */
        internal fun rasterize(path: Path, docW: Int, docH: Int, antiAlias: Boolean): Selection =
            Selection.fromPath(path, docW, docH, antiAlias)

        /** Closed polygon path through [points] (x0, y0, x1, y1, ...) in document coordinates. */
        internal fun polygonPath(points: FloatArray): Path = PointList().apply {
            var k = 0
            while (k + 1 < points.size) { add(points[k], points[k + 1]); k += 2 }
        }.toPath()
    }
}

/** Growable list of float points (no boxing). */
internal class PointList {
    private var data = FloatArray(256)
    var size = 0
        private set

    fun add(x: Float, y: Float) {
        if (2 * size + 2 > data.size) data = data.copyOf(data.size * 2)
        data[2 * size] = x
        data[2 * size + 1] = y
        size++
    }

    fun clear() { size = 0 }
    fun x(i: Int): Float = data[2 * i]
    fun y(i: Int): Float = data[2 * i + 1]
    val lastX: Float get() = data[2 * size - 2]
    val lastY: Float get() = data[2 * size - 1]

    /** Larger side of the bounding box. */
    fun extent(): Float {
        if (size == 0) return 0f
        var minX = data[0]; var maxX = data[0]; var minY = data[1]; var maxY = data[1]
        for (i in 1 until size) {
            minX = min(minX, x(i)); maxX = max(maxX, x(i)); minY = min(minY, y(i)); maxY = max(maxY, y(i))
        }
        return max(maxX - minX, maxY - minY)
    }

    /** Closed document-space path. */
    fun toPath(): Path {
        val p = Path()
        if (size == 0) return p
        p.moveTo(x(0), y(0))
        for (i in 1 until size) p.lineTo(x(i), y(i))
        p.close()
        return p
    }

    /** Maps the points to screen space into [out]. */
    fun toScreenPath(t: ViewTransform, out: Path, close: Boolean) {
        out.reset()
        if (size == 0) return
        val pts = data.copyOf(2 * size)
        t.matrix.mapPoints(pts)
        out.moveTo(pts[0], pts[1])
        var k = 2
        while (k < pts.size) { out.lineTo(pts[k], pts[k + 1]); k += 2 }
        if (close) out.close()
    }
}
