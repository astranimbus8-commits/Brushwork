package com.brushwork.paint.ui.editor

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiMark
import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.HistoryTapSink

/**
 * v1.7 (item 10, design §3.10; area I): the editor's history taps over the UI. Turns the pointer
 * events of the editor's windows ([HistoryTapSink], fed by `Modifier.historyTaps`) into
 * [HistoryTapHub] calls, and acts for it on [controller]. The mark has two parts:
 * - at the first down ([openMark]) a light one that records no step and waits for nothing: the
 *   active tool's in-tool history ([Tool.historyMark]), the settings journal, the live brush
 *   presets, the colour and an open filter's parameters (what a control changes on touch-down);
 * - at the claim ([claimMark]) the full [UiMark] ([EditorController.uiMark]). It records a pending
 *   live edit (the Adjust sheet's) as its step and completes a vector render still running, which
 *   only a history tap needs: taken at every first down it would split a live edit into one step
 *   per tap and stall every touch while a render runs.
 * The tap puts both back ([EditorController.restoreUiMark], then the light part) and undoes or
 * redoes once exactly as the hotbar's buttons do ([HistoryLabels]), with the same feedback
 * ([onFeedback]). The canvas ([canvas]) drops what its fingers started
 * ([CanvasView.yieldToHistoryTap]).
 *
 * Not put back: a document step a control pushes on the first finger's DOWN, before the claim
 * (the touch-down audit found none in the chrome: its controls step on move or on up, or change
 * a preset, a setting, the colour, the tool's own history or a live edit).
 *
 * [tapSlopPx]: the canvas' tap slop in px ([TouchGestureClassifier.TAP_SLOP_DP]).
 */
internal class EditorHistoryTaps(
    private val controller: EditorController,
    tapSlopPx: Float,
) : HistoryTapSink, HistoryTapHub.Host {

    /** The canvas, while it is shown. */
    var canvas: () -> CanvasView? = { null }

    /** Shows the undo / redo feedback ("Undo: Brush stroke"). */
    var onFeedback: (String) -> Unit = {}

    val hub = HistoryTapHub(this, tapSlopPx)

    /** The full mark, taken at the claim ([claimMark]). */
    private var mark: UiMark? = null

    // ---- the light mark, taken at the first down ([openMark])
    private var lightOpen = false
    private var lightTool: Tool? = null
    private var lightToolMark: Any? = null
    private var lightJournal = 0
    private var lightColor = 0
    /** The live brush presets then (reused: no allocation per gesture). */
    private val lightPresets = HashMap<ToolId, BrushPreset>()

    /**
     * The open filter and its parameters at [openMark]: a filter panel's tone curve, gradient bar,
     * − / + and typed fields change them on the first finger's DOWN, and a [UiMark] holds the
     * document, the settings, the brush and the colour but not an open filter (the touch-down
     * audit, A1 / A4 / A5 / A6). [FilterSession.values] is replaced on every change, never
     * mutated, so holding it is the snapshot.
     */
    private var markFilter: FilterSession? = null
    private var markFilterValues: FilterValues? = null

    // ------------------------------------------------------------------ HistoryTapSink

    override fun onPointerEvent(slot: Int, event: PointerEvent, pass: PointerEventPass): Boolean {
        val changes = event.changes
        if (pass == PointerEventPass.Final) {
            for (i in changes.indices) {
                val c = changes[i]
                if (c.changedToDownIgnoreConsumed()) {
                    hub.downsDone(c.uptimeMillis)
                    break
                }
            }
            hub.upsDone()
            return false
        }
        var consume = false
        for (i in changes.indices) {
            val c = changes[i]
            val id = c.id.value
            when {
                c.changedToDownIgnoreConsumed() -> {
                    val p = c.position
                    if (hub.down(slot, id, p.x, p.y, c.uptimeMillis, isFinger(c.type))) consume = true
                }
                c.changedToUpIgnoreConsumed() -> if (hub.up(slot, id, c.uptimeMillis)) consume = true
                c.pressed -> {
                    val p = c.position
                    hub.move(slot, id, p.x, p.y, c.uptimeMillis)
                }
            }
        }
        return consume || hub.claimed
    }

    override fun onCancel(slot: Int) = hub.cancel(slot)

    override fun onCanvasDown(slot: Int, id: Long) = hub.canvasDown(slot, id)

    // ------------------------------------------------------------------ HistoryTapHub.Host

    override fun allowed(redo: Boolean): Boolean {
        // While a long operation runs the canvas ignores touches and the history waits.
        if (controller.busyMessage != null) return false
        val settings = controller.settings
        return if (redo) settings.threeFingerRedo else settings.twoFingerUndo
    }

    override fun openMark() {
        if (lightOpen) return
        lightOpen = true
        // As a UiMark does: the journal records the settings' writes while it is open.
        controller.settings.openJournal()
        lightJournal = controller.settings.journalPosition()
        val tool = controller.currentTool
        lightTool = tool
        lightToolMark = tool.historyMark()
        lightPresets.clear()
        for (id in EditorController.PAINT_TOOLS) controller.presetFor(id)?.let { lightPresets[id] = it }
        lightColor = controller.color
        val filter = controller.filterSession
        markFilter = filter
        markFilterValues = filter?.values
    }

    override fun claimMark() {
        if (mark == null) mark = controller.uiMark()
    }

    override fun releaseMark() {
        markFilter = null
        markFilterValues = null
        mark?.let {
            mark = null
            controller.releaseUiMark(it)
        }
        if (lightOpen) {
            lightOpen = false
            lightTool = null
            lightToolMark = null
            lightPresets.clear()
            controller.settings.closeJournal()
        }
    }

    override fun yieldCanvas() {
        canvas()?.yieldToHistoryTap()
    }

    override fun historyTap(redo: Boolean) {
        mark?.let { controller.restoreUiMark(it) }
        restoreLightMark()
        restoreFilterValues()
        controller.endCanvasGesture()
        onFeedback(if (redo) HistoryLabels.performRedo(controller) else HistoryLabels.performUndo(controller))
    }

    /**
     * Puts back what the first finger changed before the claim, as [EditorController.restoreUiMark]
     * does for the time after it: the tool's in-tool steps (if it is still the active tool), the
     * settings, the live presets (persisted, as the side slider's step end does) and the colour.
     */
    private fun restoreLightMark() {
        if (!lightOpen) return
        val tool = controller.currentTool
        if (tool === lightTool && tool.historyMark() != lightToolMark) {
            tool.rollbackHistory(lightToolMark)
            controller.invalidateOverlay()
        }
        controller.settings.rollbackJournal(lightJournal)
        for ((id, preset) in lightPresets) {
            if (controller.presetFor(id) === preset) continue
            controller.updatePreset(id, preset)
            BrushPresetStore.get(controller.appContext).persist(controller, id)
        }
        if (controller.color != lightColor) controller.color = lightColor
    }

    /**
     * Puts back the parameters the open filter had at [openMark]. (Undo then cancels the filter
     * anyway, as the hotbar's does; a redo leaves it open, with what the first finger changed
     * undone.)
     */
    private fun restoreFilterValues() {
        val filter = markFilter ?: return
        val before = markFilterValues ?: return
        if (controller.filterSession !== filter || filter.isClosed || filter.values === before) return
        for ((key, value) in before.asMap()) {
            if (filter.values.raw(key) != value) filter.update(key, value)
        }
    }

    private fun isFinger(type: PointerType): Boolean =
        type != PointerType.Stylus && type != PointerType.Eraser && type != PointerType.Mouse
}
