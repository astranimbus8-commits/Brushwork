package com.brushwork.paint.tools.vector

import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.CurveLabels17
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.vector.ObjectColorExtras
import com.brushwork.paint.ui.vector.curvePaint
import com.brushwork.paint.ui.vector.paintMode
import com.brushwork.paint.vector.ColorSlot
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.10 (R2, R7; design 4.4 A2 step 4): what [CurveColorsDelegate] needs of a curve tool (Curve,
 * Polyline, Path). The tool's region `v110-A2 colors` gives it; the tool's state stays private.
 */
internal interface CurveColorsHost {
    val controller: EditorController

    /** The options the open path shows (Compose state). */
    val settings: CurveSettings

    /** A path is open, new or reopened (Compose state). */
    val open: Boolean

    /** The open path can be filled (3 points; `CurveTool.fillPossible`). */
    val fillPossible: Boolean

    /** The open path's paint mode (`CurveTool.paintMode`). */
    val paintMode: CurvePaint?

    /** A path object is open again: its options and the user's main color come back when it closes. */
    val reopened: Boolean

    /** A reopened path's main color: while the main color still equals it, the user's own comes back at the close. */
    var openedColor: Int

    /** The user's own main color that comes back when a reopened path closes. */
    var userColor: Int

    /** The colors of the reopened path's gradient fill while it is kept (no fill color of its own), else null. */
    fun keptGradient(): List<Int>?

    /**
     * A new path shows its own look from now on (`CurveTool.showLook`): the tool's settings stay
     * the user's and come back when the path ends. Nothing for a reopened path (it already does).
     */
    fun ownLook()

    /** `CurveTool.update`: the open path's options (only the user's own are saved), redrawn. */
    fun update(transform: (CurveSettings) -> CurveSettings)

    /** `CurveTool.setPaintMode` (Fill keeps the stroke kind in `lastStroke`; Stroke and Both bring it back). */
    fun setPaintMode(m: CurvePaint)

    /** Redraws the open path's preview. */
    fun redraw()
}

/**
 * v1.10 (R2, R7, C4, J21; design 4.4 A2 step 4): the Object colors editor of the path open in a
 * curve tool (Curve, Polyline, Path), new or reopened: [ObjectColorTarget.FILL] and
 * [ObjectColorTarget.STROKE], and the paint mode.
 *
 * Every edit is a pending one, shown live on the canvas (R7): the fill and the paint mode are the
 * open path's options (`CurveSettings.fillColor`, `fill`, `stroke`), the stroke is the main color
 * the path is drawn with. The colors go through [ObjectColorSlots.set] (C4): a stroke color leaves
 * the fill at its current color, and the fill follows the main color (no fill color of its own)
 * only while the two are equal. A fill color while the mode is Stroke turns it to Both; a stroke
 * color while it is Fill turns it to Both with the stroke kind the path had (`lastStroke`). Fill
 * needs 3 points ([unavailable]: [CurveLabels17.FILL_NEEDS_3]). A reopened path's gradient fill
 * is kept until a fill color is picked, which says [ObjectColorLabels110.GRADIENT_REPLACED] once
 * per opening of the sheet; Cancel brings the gradient back. No edit is an undo step of its own:
 * the path's ✓ step carries the colors.
 *
 * Object-only (the lead's choice): the user's drawing color and options come back.
 * - Options: a new path shows its own look from the first edit here, so the tool's settings stay
 *   the user's and come back when it ends ([CurveColorsHost.ownLook]); a reopened path already
 *   brings them back.
 * - Main color: a reopened path gives back the user's own when it closes; a stroke edit moves the
 *   path's opened color with it, so that still happens (and a color the user picked meanwhile is
 *   the one that comes back). A new path takes a restore point at its first stroke edit; when it
 *   ends, the main color gets it back while it still holds what this editor set.
 *
 * Cancel (and Back) restores the values the sheet opened with ([begin]). Once no path is open,
 * [preview], [setPaintMode] and [cancel] do nothing (C13).
 */
internal class CurveColorsDelegate(private val host: CurveColorsHost) : ObjectColorEditor, ObjectColorExtras {

    /** The values [begin] found (Cancel restores them). */
    private data class Start(
        val fill: Boolean,
        val fillColor: Int?,
        val stroke: CurveStroke,
        val lastStroke: CurveStroke,
        val color: Int,
        val openedColor: Int,
        val userColor: Int,
    )

    private val controller get() = host.controller

    private var start: Start? = null

    /** [ObjectColorLabels110.GRADIENT_REPLACED] was said in this opening of the sheet. */
    private var gradientSaid = false

    /** A new path: the user's main color at the first stroke edit, what this editor set last, and the watch. */
    private var restoreColor: Int? = null
    private var lastColor: Int? = null
    private var watch: Job? = null

    override val targets: List<ObjectColorTarget> get() = listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE)

    override val paintMode: PaintMode? get() = host.paintMode?.paintMode()

    override fun face(t: ObjectColorTarget): ObjectColorFace = when (t) {
        ObjectColorTarget.FILL -> when {
            !host.settings.fill -> ObjectColorFace.None
            else -> host.keptGradient()?.let { ObjectColorFace.Gradient(it) } ?: ObjectColorFace.Solid(fillColor())
        }
        ObjectColorTarget.STROKE -> ObjectColorFace.Solid(controller.color)
        else -> ObjectColorFace.None
    }

    override fun colorOf(t: ObjectColorTarget): Int =
        if (t == ObjectColorTarget.FILL) host.keptGradient()?.firstOrNull() ?: fillColor() else controller.color

    override fun toast(text: String) = controller.toast(text)

    override fun unavailable(t: ObjectColorTarget): String? =
        if (t == ObjectColorTarget.FILL && !host.fillPossible) CurveLabels17.FILL_NEEDS_3 else null

    /** The fill the open path has, painted or not (it follows the main color while it has none of its own). */
    private fun fillColor(): Int = host.settings.fillColor ?: controller.color

    override fun begin() {
        if (!host.open) return
        val s = host.settings
        start = Start(s.fill, s.fillColor, s.stroke, s.lastStroke, controller.color, host.openedColor, host.userColor)
        gradientSaid = false
    }

    override fun setPaintMode(m: PaintMode) {
        if (!host.open) return
        if (m != PaintMode.STROKE && !host.fillPossible) return
        if (host.paintMode == m.curvePaint()) return
        host.ownLook()
        host.setPaintMode(m.curvePaint())
    }

    override fun preview(t: ObjectColorTarget, argb: Int) {
        if (!host.open) return
        when (t) {
            ObjectColorTarget.FILL -> previewFill(argb)
            ObjectColorTarget.STROKE -> previewStroke(argb)
            else -> Unit
        }
    }

    private fun previewFill(argb: Int) {
        if (!host.fillPossible) return
        host.ownLook()
        val gradient = host.keptGradient() != null
        if (gradient && !gradientSaid) {
            gradientSaid = true
            controller.toast(ObjectColorLabels110.GRADIENT_REPLACED)
        }
        // A kept gradient goes for a solid fill of its own (no fill color would keep the gradient).
        val fill = if (gradient) argb else fillOf(pinned(ColorSlot.FILL, argb))
        if (!host.settings.fill) host.setPaintMode(CurvePaint.BOTH)
        host.update { it.copy(fillColor = fill) }
    }

    private fun previewStroke(argb: Int) {
        if (host.reopened) {
            // The main color comes back at the close: a color the user picked meanwhile, else the
            // one they had before the path opened.
            if (controller.color != host.openedColor) host.userColor = controller.color
            host.openedColor = argb
        } else {
            takeRestorePoint()
        }
        host.ownLook()
        val s = host.settings
        if (s.stroke == CurveStroke.NONE) host.setPaintMode(if (s.fill) CurvePaint.BOTH else CurvePaint.STROKE)
        // C4: the fill keeps its color and follows only while it equals the stroke (a kept
        // gradient is not a fill color: it stays).
        val fill = if (host.keptGradient() != null) host.settings.fillColor else fillOf(pinned(ColorSlot.STROKE, argb))
        controller.color = argb
        if (!host.reopened) lastColor = argb
        if (fill != host.settings.fillColor) host.update { it.copy(fillColor = fill) } else host.redraw()
    }

    override fun setNone(t: ObjectColorTarget) {
        if (t == ObjectColorTarget.FILL) setPaintMode(PaintMode.STROKE)
    }

    override fun commit() {
        start = null
    }

    override fun cancel() {
        val st = start ?: return
        start = null
        if (!host.open) return
        if (host.reopened) {
            host.openedColor = st.openedColor
            host.userColor = st.userColor
        }
        val s = host.settings
        val back = s.copy(fill = st.fill, fillColor = st.fillColor, stroke = st.stroke, lastStroke = st.lastStroke)
        val colorBack = controller.color != st.color
        if (colorBack) {
            controller.color = st.color
            if (!host.reopened) lastColor = st.color
        }
        if (back != s) host.update { back } else if (colorBack) host.redraw()
    }

    /**
     * The open path with [slot] painted [argb] through [ObjectColorSlots] (C4): a path of the
     * open one's fill and line colors, the geometry left out (only its colors are read back).
     */
    private fun pinned(slot: ColorSlot, argb: Int): VPath {
        val o = VPath(0, subpaths = emptyList(), fill = VPaint.Solid(fillColor()), stroke = VStrokeStyle(color = controller.color, width = 1f))
        return ObjectColorSlots.set(o, slot, argb) as VPath
    }

    /** The options' fill color of [p]: none of its own (null) while it equals the line color (it follows it). */
    private fun fillOf(p: VPath): Int? {
        val fill = ObjectColorSlots.colorOf(p, ColorSlot.FILL)
        return if (fill == null || fill == ObjectColorSlots.colorOf(p, ColorSlot.STROKE)) null else fill
    }

    /** A new path's first stroke edit here: the user's main color, given back when the path ends. */
    private fun takeRestorePoint() {
        if (restoreColor != null) return
        restoreColor = controller.color
        watch?.cancel()
        watch = controller.scope.launch {
            snapshotFlow { host.open }.first { !it }
            endOfNewPath()
        }
    }

    /** A new path ended: the main color gets the user's back while it still holds what this editor set. */
    private fun endOfNewPath() {
        val r = restoreColor ?: return
        restoreColor = null
        val last = lastColor
        lastColor = null
        if (last != null && controller.color == last) controller.color = r
    }
}
