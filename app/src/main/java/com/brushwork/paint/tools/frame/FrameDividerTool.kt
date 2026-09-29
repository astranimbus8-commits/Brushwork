package com.brushwork.paint.tools.frame

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.max

/**
 * Manga frame divider (like the "frame border" tools of ibisPaint / Clip Studio). "New frame
 * layer" creates a layer with one panel (or a grid) inside the page margins; dragging a straight
 * line across panels splits every crossed panel, leaving a gutter. Each step re-renders the frame
 * layer with undo.
 *
 * The panel model of every frame layer created in this session is kept here. Undo/redo of a frame
 * edit restores the matching model (see [FrameEditAction]); if the layer is changed by something
 * else (brush, filter, canvas resize...) the frame becomes [Status.OUT_OF_SYNC] and the UI offers
 * to redraw it from the model or to start a new frame layer.
 */
class FrameDividerTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.FRAME_DIVIDER

    enum class Status { NONE, READY, OUT_OF_SYNC }

    /** Settings for new frame layers and cuts. */
    var settings by mutableStateOf(FrameSettings.defaultsFor(controller.doc.width, controller.doc.height))

    /** Unit for the length fields of the settings sheet. */
    var unit by mutableStateOf(if (controller.doc.dpi >= 150f) LengthUnit.MM else LengthUnit.PX)

    var settingsOpen by mutableStateOf(false)
    var gridOpen by mutableStateOf(false)

    /** When on, tapping a panel removes it instead of dividing. */
    var removeMode by mutableStateOf(false)

    /** Bumped whenever the frame bookkeeping changes (the options strip reads it to recompose). */
    var revision by mutableIntStateOf(0)
        private set

    private class FrameRecord(var model: FrameModel, var version: Long)

    // Weak keys: a frame layer dropped from the document and from the undo history is freed.
    private val frames = WeakHashMap<Layer, FrameRecord>()
    private var lastFrame: WeakReference<Layer>? = null

    private val doc get() = controller.doc

    // ------------------------------------------------------------------ frame state

    /** The frame layer the tool works on: the active layer if it is one, else the latest frame layer. */
    fun targetLayer(): Layer? {
        val active = doc.activeLayer
        if (frames[active] != null) return active
        val last = lastFrame?.get() ?: return null
        return if (doc.indexOf(last) >= 0 && frames[last] != null) last else null
    }

    fun status(): Status {
        val layer = targetLayer() ?: return Status.NONE
        val rec = frames[layer] ?: return Status.NONE
        return if (rec.version == layer.contentVersion) Status.READY else Status.OUT_OF_SYNC
    }

    /** Model of the target frame layer (may be out of sync, see [status]). */
    val model: FrameModel? get() = targetLayer()?.let { frames[it]?.model }

    private fun minPieceSize(style: FrameStyle) = max(4f, style.borderWidth * 2f + 2f)

    // ------------------------------------------------------------------ operations (UI)

    /** Creates a new "Frame" layer from [settings] (one panel, or rows x cols). */
    fun createFrameLayer(): Boolean {
        val s = settings
        val area = FrameMath.frameArea(doc.width, doc.height, s)
        if (area == null) { controller.toast("The margins leave no room for panels"); return false }
        val panels = FrameMath.grid(area, s.rows, s.cols, s.gutterH, s.gutterV)
        if (panels == null) { controller.toast("The gutters are too wide for ${s.rows} × ${s.cols} panels"); return false }
        val model = FrameModel(area, panels, FrameStyle(s.borderWidth, s.borderColor, s.fillOutside))
        cancelCut()
        val layer = try {
            controller.addLayerWithContent("Frame", "New frame layer") { c -> FrameRenderer.render(c, model) }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            null
        } ?: return false
        if (doc.colorMode != ColorMode.RGB) {
            ColorModeOps.constrain(layer.bitmap, doc.bounds, doc.colorMode)
            layer.markChanged()
            controller.invalidateDoc(null)
            controller.notifyLayersChanged()
        }
        frames[layer] = FrameRecord(model, layer.contentVersion)
        lastFrame = WeakReference(layer)
        removeMode = false
        revision++
        return true
    }

    /** Replaces the panels of the current frame with a rows x cols grid of its frame area. */
    fun applyGrid(rows: Int, cols: Int): Boolean {
        val layer = targetLayer() ?: return false
        val rec = frames[layer] ?: return false
        val s = settings
        val panels = FrameMath.grid(rec.model.area, rows, cols, s.gutterH, s.gutterV)
        if (panels == null) { controller.toast("The gutters are too wide for $rows × $cols panels"); return false }
        settings = s.copy(rows = rows, cols = cols)
        return applyModel(layer, rec, rec.model.copy(panels = panels), "Frame grid", incremental = false)
    }

    /** Re-renders the current frame with the border/fill settings of [settings]. */
    fun applyStyleToCurrent(): Boolean {
        val layer = targetLayer() ?: return false
        val rec = frames[layer] ?: return false
        val s = settings
        return applyModel(layer, rec, rec.model.copy(style = FrameStyle(s.borderWidth, s.borderColor, s.fillOutside)), "Frame style", incremental = false)
    }

    /** Redraws an out-of-sync frame layer from its model (discarding other edits on that layer). */
    fun redraw(): Boolean {
        val layer = targetLayer() ?: return false
        val rec = frames[layer] ?: return false
        return applyModel(layer, rec, rec.model, "Redraw frame", incremental = false)
    }

    /**
     * Renders [newModel] into [layer] with undo. When [incremental] (layer in sync), only the
     * area of panels that changed is redrawn and snapshotted.
     */
    private fun applyModel(layer: Layer, rec: FrameRecord, newModel: FrameModel, label: String, incremental: Boolean): Boolean {
        if (!controller.checkEditable(layer)) return false
        if (layer.alphaLocked) { controller.toast("Turn off \"Lock alpha\" on \"${layer.name}\" to edit the frame"); return false }
        val before = rec.model
        val dirty: Rect = if (incremental && rec.version == layer.contentVersion) {
            val changed = HashSet<Panel>(before.panels).apply { removeAll(newModel.panels.toSet()) } +
                HashSet<Panel>(newModel.panels).apply { removeAll(before.panels.toSet()) }
            FrameRenderer.dirtyRect(changed, layer.width, layer.height) ?: return false
        } else {
            Rect(0, 0, layer.width, layer.height)
        }
        val recorder = controller.beginEdit(layer, EditTarget.CONTENT)
        try {
            recorder.touch(dirty)
            val c = Canvas(layer.bitmap)
            c.clipRect(dirty)
            FrameRenderer.render(c, newModel)
        } catch (e: OutOfMemoryError) {
            recorder.abort()
            controller.toast("Not enough memory to update the frame")
            return false
        }
        if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(layer.bitmap, dirty, doc.colorMode)
        val pixels = recorder.finish(label) ?: return false
        layer.markChanged()
        rec.model = newModel
        rec.version = layer.contentVersion
        lastFrame = WeakReference(layer)
        controller.pushUndo(FrameEditAction(label, pixels, this, layer, before, newModel))
        controller.notifyLayersChanged()
        controller.invalidateDoc(dirty)
        revision++
        return true
    }

    /** Called by [FrameEditAction] after its pixels were swapped back or forth. */
    private fun syncModel(layer: Layer, model: FrameModel) {
        val rec = frames[layer]
        if (rec == null) frames[layer] = FrameRecord(model, layer.contentVersion)
        else { rec.model = model; rec.version = layer.contentVersion }
        lastFrame = WeakReference(layer)
        revision++
    }

    /**
     * One frame edit: the pixel change plus the panel model before/after. The model is restored
     * AFTER the pixels so the recorded layer version matches exactly.
     */
    private class FrameEditAction(
        override val label: String,
        private val pixels: UndoAction,
        private val tool: FrameDividerTool,
        private val layer: Layer,
        private val before: FrameModel,
        private val after: FrameModel,
    ) : UndoAction {
        override val byteSize: Long get() = pixels.byteSize
        override fun undo(c: EditorController) { pixels.undo(c); tool.syncModel(layer, before); c.notifyLayersChanged() }
        override fun redo(c: EditorController) { pixels.redo(c); tool.syncModel(layer, after); c.notifyLayersChanged() }
        override fun dispose() = pixels.dispose()
    }

    // ------------------------------------------------------------------ gestures

    private var cutStart: Vec2? = null
    private var cutEnd: Vec2? = null
    /** Panels that the cut in progress would create (drawn as a preview). */
    private var previewPanels: List<Panel> = emptyList()
    private var gestureActive = false

    private fun cancelCut() {
        gestureActive = false
        cutStart = null
        cutEnd = null
        previewPanels = emptyList()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() = cancelCut()

    override fun onDown(p: ToolPoint) {
        gestureActive = true
        cutStart = Vec2(p.x, p.y)
        cutEnd = cutStart
        previewPanels = emptyList()
    }

    override fun onMove(p: ToolPoint) {
        val a = cutStart ?: return
        if (!gestureActive || removeMode) return
        val b = FrameMath.snapCut(a, Vec2(p.x, p.y))
        cutEnd = b
        val m = model
        previewPanels = if (m != null && status() == Status.READY) {
            val s = settings
            val result = FrameMath.divide(m.panels, a, b, s.gutterH, s.gutterV, minPieceSize(m.style))
            if (result == null) emptyList() else result.filter { it !in m.panels }
        } else emptyList()
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        val a = cutStart
        val wasActive = gestureActive
        cancelCut()
        if (!wasActive || a == null) return
        val t = controller.viewTransform
        val raw = Vec2(p.x, p.y)
        val isTap = t.docToScreen(raw).distanceTo(t.docToScreen(a)) < t.dp(MIN_CUT_DP)
        when (status()) {
            Status.NONE -> { controller.toast("Create a frame layer first (\"New frame\")"); return }
            Status.OUT_OF_SYNC -> { controller.toast("The frame layer was edited: redraw it or start a new frame layer"); return }
            Status.READY -> {}
        }
        val layer = targetLayer() ?: return
        val rec = frames[layer] ?: return
        if (removeMode) {
            if (!isTap) return
            val i = FrameMath.panelAt(rec.model.panels, raw)
            if (i < 0) { controller.toast("Tap inside a panel to remove it"); return }
            applyModel(layer, rec, rec.model.copy(panels = rec.model.panels.filterIndexed { j, _ -> j != i }), "Remove panel", incremental = true)
            return
        }
        if (isTap) return
        val b = FrameMath.snapCut(a, raw)
        val s = settings
        val result = FrameMath.divide(rec.model.panels, a, b, s.gutterH, s.gutterV, minPieceSize(rec.model.style))
        if (result == null) { controller.toast("Drag across a panel to divide it"); return }
        applyModel(layer, rec, rec.model.copy(panels = result), "Divide frame", incremental = true)
    }

    override fun onCancel() = cancelCut()

    // ------------------------------------------------------------------ overlay

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt(); strokeJoin = Paint.Join.MITER }
    private val accentPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT; strokeJoin = Paint.Join.MITER }
    private val guidePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val dotPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL; color = ACCENT }
    private val tmpPath = Path()

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (!gestureActive || removeMode) return
        val a = cutStart ?: return
        val b = cutEnd ?: return
        if (a == b) return
        haloPaint.strokeWidth = t.dp(3.5f)
        accentPaint.strokeWidth = t.dp(2f)
        for (panel in previewPanels) {
            tmpPath.rewind()
            panel.points.forEachIndexed { i, v ->
                val s = t.docToScreen(v)
                if (i == 0) tmpPath.moveTo(s.x, s.y) else tmpPath.lineTo(s.x, s.y)
            }
            tmpPath.close()
            canvas.drawPath(tmpPath, haloPaint)
            canvas.drawPath(tmpPath, accentPaint)
        }
        // The cut line, extended faintly across the page.
        val sa = t.docToScreen(a)
        val sb = t.docToScreen(b)
        val dir = (sb - sa).normalized()
        val far = t.dp(4000f)
        guidePaint.strokeWidth = t.dp(1f)
        guidePaint.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
        canvas.drawLine(sa.x - dir.x * far, sa.y - dir.y * far, sb.x + dir.x * far, sb.y + dir.y * far, guidePaint)
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, haloPaint)
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, accentPaint)
        canvas.drawCircle(sa.x, sa.y, t.dp(4f), dotPaint)
        canvas.drawCircle(sb.x, sb.y, t.dp(4f), dotPaint)
    }

    companion object {
        private const val ACCENT = 0xFF4DA3FF.toInt()
        /** Shorter drags (screen dp) count as taps. */
        private const val MIN_CUT_DP = 20f
    }
}
