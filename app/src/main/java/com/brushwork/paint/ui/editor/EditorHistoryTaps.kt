package com.brushwork.paint.ui.editor

import androidx.compose.ui.input.pointer.PointerEvent
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.changedToDownIgnoreConsumed
import androidx.compose.ui.input.pointer.changedToUpIgnoreConsumed
import com.brushwork.paint.EditorController
import com.brushwork.paint.UiMark
import com.brushwork.paint.ui.common.HistoryTapSink

/**
 * v1.7 (item 10, design §3.10; area I): the editor's history taps over the UI. Turns the pointer
 * events of the editor's windows ([HistoryTapSink], fed by `Modifier.historyTaps`) into
 * [HistoryTapHub] calls, and acts for it on [controller]: the mark is a [UiMark]
 * ([EditorController.uiMark]); the tap puts it back ([EditorController.restoreUiMark]: a slider
 * the first finger moved, the active tool's in-tool steps, steps pushed meanwhile) and then undoes
 * or redoes once exactly as the hotbar's buttons do ([HistoryLabels]), with the same feedback
 * ([onFeedback]). The canvas ([canvas]) drops what its fingers started
 * ([CanvasView.yieldToHistoryTap]).
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

    private var mark: UiMark? = null

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
        if (mark == null) mark = controller.uiMark()
    }

    override fun releaseMark() {
        val m = mark ?: return
        mark = null
        controller.releaseUiMark(m)
    }

    override fun yieldCanvas() {
        canvas()?.yieldToHistoryTap()
    }

    override fun historyTap(redo: Boolean) {
        mark?.let { controller.restoreUiMark(it) }
        controller.endCanvasGesture()
        onFeedback(if (redo) HistoryLabels.performRedo(controller) else HistoryLabels.performUndo(controller))
    }

    private fun isFinger(type: PointerType): Boolean =
        type != PointerType.Stylus && type != PointerType.Eraser && type != PointerType.Mouse
}
