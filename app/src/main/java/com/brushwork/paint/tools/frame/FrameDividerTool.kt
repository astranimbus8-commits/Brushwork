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
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.UndoAction
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.POINT_GUIDE_EPS
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.select.snapPointWhenOn
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapEdge
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.transform.SnapLine
import com.brushwork.paint.tools.transform.SnapSource
import java.lang.ref.WeakReference
import java.util.WeakHashMap
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.min

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
 *
 * Cuts snap to objects with the app-wide "Snap to objects" setting (see [snap]): their start, the
 * end of slanted cuts, never fighting the cut's own straightening.
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

    /** Panel model of one frame layer and the layer version it was rendered at. */
    private class FrameRecord(model: FrameModel, version: Long) {
        var model = model
            private set
        var version = version
            private set
        /** A later layer version already compared with [model] and found different. */
        var mismatchVersion = NEVER_IN_SYNC

        fun set(model: FrameModel, version: Long) {
            this.model = model
            this.version = version
            mismatchVersion = NEVER_IN_SYNC
        }

        fun adoptVersion(version: Long) { this.version = version }
    }

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

    /**
     * Re-checks an out-of-sync frame layer against its model without changing it. When the pixels
     * match (an unrelated edit was undone, only the mask changed, ...) the frame is usable again.
     * Each layer version is compared at most once. Returns true if the frame became ready.
     */
    fun refreshSync(): Boolean {
        val layer = targetLayer() ?: return false
        val rec = frames[layer] ?: return false
        val v = layer.contentVersion
        if (rec.version == v || rec.mismatchVersion == v) return false
        val same = try {
            FrameRenderer.matches(layer.bitmap, rec.model, doc.colorMode)
        } catch (e: OutOfMemoryError) {
            false
        }
        if (!same) {
            rec.mismatchVersion = v
            return false
        }
        rec.adoptVersion(v)
        revision++
        return true
    }

    override fun onActivate() { refreshSync() }

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
        // addLayerWithContent applies the color mode and handles a failed layer allocation itself;
        // the catch covers its grayscale/1-bit conversion, which allocates a canvas-sized buffer.
        val layer = try {
            controller.addLayerWithContent("Frame", "New frame layer") { c -> FrameRenderer.render(c, model) }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            null
        } ?: return false
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
        val beforeInSync = rec.version == layer.contentVersion
        // While in sync, pixels outside the changed panels render identically: only look there.
        val area: Rect = if (incremental && beforeInSync) {
            val changed = HashSet<Panel>(before.panels).apply { removeAll(newModel.panels.toSet()) } +
                HashSet<Panel>(newModel.panels).apply { removeAll(before.panels.toSet()) }
            FrameRenderer.dirtyRect(changed, layer.width, layer.height) ?: return false
        } else {
            Rect(0, 0, layer.width, layer.height)
        }
        val recorder = controller.beginEdit(layer, EditTarget.CONTENT)
        val dirty = try {
            FrameRenderer.renderChangedTiles(layer.bitmap, newModel, area, doc.colorMode) { recorder.touch(it) }
        } catch (e: OutOfMemoryError) {
            recorder.abort()
            controller.toast("Not enough memory to update the frame")
            return false
        }
        val pixels = recorder.finish(label)
        if (pixels == null) {
            // Pixels already match (e.g. redrawing an unchanged frame): just adopt the model.
            rec.set(newModel, layer.contentVersion)
            lastFrame = WeakReference(layer)
            revision++
            return true
        }
        layer.markChanged()
        rec.set(newModel, layer.contentVersion)
        lastFrame = WeakReference(layer)
        controller.pushUndo(FrameEditAction(label, pixels, this, layer, before, beforeInSync, newModel))
        controller.notifyLayersChanged()
        controller.invalidateDoc(dirty)
        revision++
        return true
    }

    /**
     * Called by [FrameEditAction] after its pixels were swapped back or forth. [inSync] is false
     * when the restored pixels did not match the model (an edit made while out of sync).
     */
    private fun syncModel(layer: Layer, model: FrameModel, inSync: Boolean) {
        val version = if (inSync) layer.contentVersion else NEVER_IN_SYNC
        val rec = frames[layer]
        if (rec == null) frames[layer] = FrameRecord(model, version) else rec.set(model, version)
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
        private val beforeInSync: Boolean,
        private val after: FrameModel,
    ) : UndoAction {
        override val byteSize: Long get() = pixels.byteSize
        override fun undo(c: EditorController) { pixels.undo(c); tool.syncModel(layer, before, beforeInSync); c.notifyLayersChanged() }
        override fun redo(c: EditorController) { pixels.redo(c); tool.syncModel(layer, after, true); c.notifyLayersChanged() }
        override fun dispose() = pixels.dispose()
    }

    // ------------------------------------------------------------------ gestures

    private var cutStart: Vec2? = null
    private var cutEnd: Vec2? = null
    /** Where the finger went down (taps are told from cuts by the finger, not the snapped start). */
    private var downPoint = Vec2.ZERO
    /** Panels that the cut in progress would create (drawn as a preview). */
    private var previewPanels: List<Panel> = emptyList()
    private var gestureActive = false
    /** The frame was usable when the gesture started (else no cut preview is shown). */
    private var cutAllowed = false

    /**
     * "Snap to objects" (the app-wide setting) for the cut: its start, and the end of a slanted cut,
     * snap to the canvas, other layers' content bounds and drawn lines (never the frame layer's own
     * borders: a cut along a border can't divide anything) and to the "gutter lines" of the frame
     * (half a gutter outside the level / upright panel edges: a cut there lines its panels up with
     * the neighbouring ones). The frame's own straightening ([FrameMath.snapCut]) keeps priority:
     * the end of a cut it made level or upright isn't moved (that would tilt it again).
     */
    private val snap = controller.newSnapSession()

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = snap.guides

    private fun cancelCut() {
        gestureActive = false
        cutStart = null
        cutEnd = null
        previewPanels = emptyList()
        snap.end()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() = cancelCut()

    override fun onDown(p: ToolPoint) {
        gestureActive = true
        refreshSync()
        cutAllowed = status() == Status.READY
        downPoint = Vec2(p.x, p.y)
        snap.end()
        var start = downPoint
        if (cutAllowed && !removeMode) {
            val frame = targetLayer()
            val gutters = model?.let { gutterLines(it) } ?: emptyList()
            snap.begin(exclude = listOfNotNull(frame)) { gutters }
            // The start of a cut is a new point: it may snap right away.
            start = snap.snapPointWhenOn(downPoint, controller.snapping)
        }
        cutStart = start
        cutEnd = cutStart
        previewPanels = emptyList()
        controller.invalidateOverlay()
    }

    /**
     * The end of the cut from [a] for the finger at [raw]: straightened by [FrameMath.snapCut]
     * first; a cut it left slanted has its end snapped to objects (and straightened again if that
     * brings it within the straightening angle). Shows the guides of the cut.
     */
    private fun cutEndFor(a: Vec2, raw: Vec2): Vec2 {
        val straight = FrameMath.snapCut(a, raw)
        val b = if (straight.x == a.x || straight.y == a.y) straight
        else FrameMath.snapCut(a, snap.snapPointWhenOn(raw, controller.snapping))
        if (controller.snapping.enabled && a != b) {
            snap.showGuidesFor(DocBox(min(a.x, b.x), min(a.y, b.y), max(a.x, b.x), max(a.y, b.y)), POINT_GUIDE_EPS)
        } else {
            snap.clearGuides()
        }
        return b
    }

    /**
     * Lines a cut can line its panels up with: half a gutter outside every level / upright panel
     * edge (a cut on such a line leaves a panel edge exactly in line with that one, and with the
     * gutters of this frame's settings the gutters line up too).
     */
    private fun gutterLines(m: FrameModel): List<SnapLine> {
        val s = settings
        val out = ArrayList<SnapLine>()
        for (panel in m.panels) {
            val pts = panel.points
            if (pts.size < 3) continue
            var cx = 0f; var cy = 0f
            for (v in pts) { cx += v.x; cy += v.y }
            cx /= pts.size; cy /= pts.size
            for (i in pts.indices) {
                val u = pts[i]
                val v = pts[(i + 1) % pts.size]
                if (abs(u.y - v.y) <= EDGE_EPS && abs(u.x - v.x) > EDGE_EPS) {
                    val y = if (u.y < cy) u.y - s.gutterH / 2f else u.y + s.gutterH / 2f
                    out += SnapLine(SnapAxis.Y, y, SnapEdge.CENTER, SnapSource.LINE, GUTTER_LABEL, min(u.x, v.x), max(u.x, v.x))
                } else if (abs(u.x - v.x) <= EDGE_EPS && abs(u.y - v.y) > EDGE_EPS) {
                    val x = if (u.x < cx) u.x - s.gutterV / 2f else u.x + s.gutterV / 2f
                    out += SnapLine(SnapAxis.X, x, SnapEdge.CENTER, SnapSource.LINE, GUTTER_LABEL, min(u.y, v.y), max(u.y, v.y))
                }
            }
        }
        return out
    }

    override fun onMove(p: ToolPoint) {
        val a = cutStart ?: return
        if (!gestureActive || removeMode || !cutAllowed) return
        val b = cutEndFor(a, Vec2(p.x, p.y))
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
        val raw = Vec2(p.x, p.y)
        // The cut as shown (snapped like the last move), before the session ends.
        val end = if (wasActive && a != null && !removeMode && cutAllowed) cutEndFor(a, raw) else null
        cancelCut()
        if (!wasActive || a == null) return
        val t = controller.viewTransform
        val isTap = t.docToScreen(raw).distanceTo(t.docToScreen(downPoint)) < t.dp(MIN_CUT_DP)
        if (isTap && !removeMode) return
        when (status()) {
            Status.NONE -> { controller.toast("Create a frame layer first (\"New frame layer\")"); return }
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
        val b = end ?: FrameMath.snapCut(a, raw)
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
    private var dashDensity = 0f

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        if (!gestureActive || removeMode || !cutAllowed) return
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
        if (dashDensity != t.density) {
            dashDensity = t.density
            guidePaint.strokeWidth = t.dp(1f)
            guidePaint.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
        }
        canvas.drawLine(sa.x - dir.x * far, sa.y - dir.y * far, sb.x + dir.x * far, sb.y + dir.y * far, guidePaint)
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, haloPaint)
        canvas.drawLine(sa.x, sa.y, sb.x, sb.y, accentPaint)
        canvas.drawCircle(sa.x, sa.y, t.dp(4f), dotPaint)
        canvas.drawCircle(sb.x, sb.y, t.dp(4f), dotPaint)
        // Smart guides of the cut (labels away from the finger at its end).
        snap.draw(canvas, t, pointBox(b))
    }

    companion object {
        /** Guide label of the lines a cut lines its panels up on (see gutterLines). */
        private const val GUTTER_LABEL = "Gutter"
        /** Panel edges this close (px) to level / upright count as such. */
        private const val EDGE_EPS = 0.01f
        private const val ACCENT = 0xFF4DA3FF.toInt()
        /** Shorter drags (screen dp) count as taps. */
        private const val MIN_CUT_DP = 20f
        /** Recorded version for a model known not to match the layer pixels. */
        private const val NEVER_IN_SYNC = -1L
    }
}
