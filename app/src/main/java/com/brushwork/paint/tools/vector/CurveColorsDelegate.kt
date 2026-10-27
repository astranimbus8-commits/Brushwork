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
import com.brushwork.paint.ui.vector.ObjectColorSheetState
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

    /**
     * Which path is open (Compose state reads), null when none: another value is another path's
     * session (a placed path and the next one opened in the same call too).
     */
    val session: Any?

    /** The open path can be filled (3 points; `CurveTool.fillPossible`). */
    val fillPossible: Boolean

    /** The open path's paint mode (`CurveTool.paintMode`). */
    val paintMode: CurvePaint?

    /** A path object is open again: its options and the user's main color come back when it closes. */
    val reopened: Boolean

    /**
     * A new path shows a look of its own (a quick start's, a path handed over by To Bézier:
     * `CurveTool.showLook`): the user's options come back as a whole when it ends.
     */
    val ownLook: Boolean

    /** A reopened path's main color: while the main color still equals it, the user's own comes back at the close. */
    var openedColor: Int

    /** The user's own main color that comes back when a reopened path closes. */
    var userColor: Int

    /** The colors of the reopened path's gradient fill while it is kept (no fill color of its own), else null. */
    fun keptGradient(): List<Int>?

    /** Shows [s] on the open path and redraws it; not saved as the user's options. */
    fun show(s: CurveSettings)

    /** `CurveTool.update`: the options, saved as the user's (only their own while a path shows its look), redrawn. */
    fun update(transform: (CurveSettings) -> CurveSettings)

    /** Redraws the open path's preview. */
    fun redraw()
}

/**
 * v1.10 (R2, R7, C4, J21; design 4.4 A2 step 4): the Object colors editor of the path open in a
 * curve tool (Curve, Polyline, Path), new or reopened: [ObjectColorTarget.FILL] and
 * [ObjectColorTarget.STROKE], and the paint mode.
 *
 * Every edit is a pending one, shown live on the canvas (R7): the fill and the paint mode are the
 * options the open path shows (`CurveSettings.fillColor`, `fill`, `stroke`; [CurveColorsHost.show],
 * not saved as the user's), the stroke is the main color the path is drawn with. The colors go
 * through [ObjectColorSlots.set] (C4): a stroke color leaves the fill at its current color, and the
 * fill follows the main color (no fill color of its own) only while the two are equal. A fill color
 * while the mode is Stroke turns it to Both; a stroke color while it is Fill turns it to Both with
 * the stroke kind the path had (`lastStroke`, as `CurveTool.setPaintMode`). Fill needs 3 points
 * ([unavailable]: [CurveLabels17.FILL_NEEDS_3]). A reopened path's gradient fill is kept until a
 * fill color is picked, which says [ObjectColorLabels110.GRADIENT_REPLACED] once per opening of
 * the sheet; Cancel brings the gradient back. No edit is an undo step of its own: the path's ✓
 * step carries the colors.
 *
 * Object-only (the lead's choice): the user's drawing color and options come back, and only what
 * this editor set does.
 * - A reopened path (and a new path that shows a look of its own) already brings back the user's
 *   options when it ends. A stroke edit moves a reopened path's opened color with it, so its close
 *   still gives back the user's main color (and a color the user picked meanwhile is the one that
 *   comes back).
 * - A new path takes a restore point of the options at its first edit of them here, and one of
 *   the main color at its first stroke edit; when it ends (✓, ✕ or discard), each value that still
 *   holds what this editor set gets the user's back (the paint mode as one value: fill, stroke kind
 *   and the kept kind). An edit on the strip meanwhile stays the tool's setting, saved as usual, as
 *   in the Shape tool ([ShapeColorsDelegate]).
 *
 * Cancel (and Back) restores the values the sheet opened with ([begin]). Once no path is open,
 * [preview], [setPaintMode] and [cancel] do nothing (C13).
 */
internal class CurveColorsDelegate(private val host: CurveColorsHost) : ObjectColorEditor, ObjectColorExtras {

    /** The values [begin] found (Cancel restores them) in the path's [session]. */
    private data class Start(
        val fill: Boolean,
        val fillColor: Int?,
        val stroke: CurveStroke,
        val lastStroke: CurveStroke,
        val color: Int,
        val openedColor: Int,
        val userColor: Int,
        val session: Any?,
    )

    /** The options of the open path this editor sets: the paint mode (fill, stroke kind, kept kind) and the fill color. */
    private data class Look(val fill: Boolean, val fillColor: Int?, val stroke: CurveStroke, val lastStroke: CurveStroke)

    private val controller get() = host.controller

    private var start: Start? = null

    /** [ObjectColorLabels110.GRADIENT_REPLACED] was said in this opening of the sheet. */
    private var gradientSaid = false

    /**
     * A new path: the user's options at the first edit of them here and what this editor showed
     * last; the user's main color at the first stroke edit and what this editor set last; the
     * watch that gives them back when the path ends.
     */
    private var restoreLook: Look? = null
    private var lastLook: Look? = null
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

    private fun look(): Look = host.settings.let { Look(it.fill, it.fillColor, it.stroke, it.lastStroke) }

    override val sheet = ObjectColorSheetState(host.controller.scope, host.controller, ::cancel) { host.session }

    override fun begin() {
        val session = host.session ?: return
        // An earlier opening on this path, neither kept nor cancelled (the sheet went away with
        // the pill's cell), keeps its start values: Cancel restores what it found.
        if (start?.session == session) return
        val s = host.settings
        start = Start(s.fill, s.fillColor, s.stroke, s.lastStroke, controller.color, host.openedColor, host.userColor, session)
        gradientSaid = false
    }

    override fun setPaintMode(m: PaintMode) {
        if (!host.open) return
        if (m != PaintMode.STROKE && !host.fillPossible) return
        if (host.paintMode == m.curvePaint()) return
        show(painted(host.settings, m.curvePaint()))
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
        val gradient = host.keptGradient() != null
        if (gradient && !gradientSaid) {
            gradientSaid = true
            controller.toast(ObjectColorLabels110.GRADIENT_REPLACED)
        }
        // A kept gradient goes for a solid fill of its own (no fill color would keep the gradient).
        val fill = if (gradient) argb else fillOf(pinned(ColorSlot.FILL, argb))
        val s = host.settings
        show((if (s.fill) s else painted(s, CurvePaint.BOTH)).copy(fillColor = fill))
    }

    private fun previewStroke(argb: Int) {
        if (host.reopened) {
            // The main color comes back at the close: a color the user picked meanwhile, else the
            // one they had before the path opened.
            if (controller.color != host.openedColor) host.userColor = controller.color
            host.openedColor = argb
        } else {
            takeColorPoint()
        }
        val s = host.settings
        // C4: the fill keeps its color and follows only while it equals the stroke (a kept
        // gradient is not a fill color: it stays).
        val fill = if (host.keptGradient() != null) s.fillColor else fillOf(pinned(ColorSlot.STROKE, argb))
        val next = (if (s.stroke == CurveStroke.NONE) painted(s, if (s.fill) CurvePaint.BOTH else CurvePaint.STROKE) else s).copy(fillColor = fill)
        controller.color = argb
        if (!host.reopened) lastColor = argb
        if (next != s) show(next) else host.redraw()
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
        // (Another path is open: the start values were another path's.)
        if (!host.open || host.session != st.session) return
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
        when {
            back == s -> if (colorBack) host.redraw()
            // This editor showed the options: they go back the same way (no restore point taken now).
            restoreLook != null || host.reopened || host.ownLook -> {
                host.show(back)
                noteShown()
            }
            // Changed on the strip while the sheet was open (saved as the user's): back, saved too.
            else -> host.update { back }
        }
    }

    /**
     * Shows [s] on the open path: the path's own options, not the user's (a new path takes the
     * restore point of the user's at the first one).
     */
    private fun show(s: CurveSettings) {
        takeLookPoint()
        host.show(s)
        noteShown()
    }

    /** A new path: what this editor showed last (its end compares the options with it). */
    private fun noteShown() {
        if (restoreLook != null && !host.reopened && !host.ownLook) lastLook = look()
    }

    /**
     * [s] painted as [m], as `CurveTool.setPaintMode` paints it: Fill sets the stroke kind to
     * [CurveStroke.NONE] and keeps the kind it had in `lastStroke`; Stroke and Both bring it back.
     */
    private fun painted(s: CurveSettings, m: CurvePaint): CurveSettings {
        val kind = if (s.stroke != CurveStroke.NONE) s.stroke else s.lastStroke.takeIf { it != CurveStroke.NONE } ?: CurveStroke.BRUSH
        return when (m) {
            CurvePaint.STROKE -> s.copy(stroke = kind, fill = false, lastStroke = kind)
            CurvePaint.FILL -> s.copy(stroke = CurveStroke.NONE, fill = true, lastStroke = kind)
            CurvePaint.BOTH -> s.copy(stroke = kind, fill = true, lastStroke = kind)
        }
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

    /** A new path's first edit of its options here: the user's, given back when the path ends. */
    private fun takeLookPoint() {
        if (host.reopened || host.ownLook || restoreLook != null) return
        restoreLook = look()
        lastLook = null
        watchEnd()
    }

    /** A new path's first stroke edit here: the user's main color, given back when the path ends. */
    private fun takeColorPoint() {
        if (restoreColor != null) return
        restoreColor = controller.color
        lastColor = null
        watchEnd()
    }

    /**
     * v1.10 G2 (V2): the new path ended (✓, ✕, discard, undone away), told synchronously by the
     * tool once the path's pixels are painted (`CurveTool.endLook`): the restore points are given
     * back now, not a frame later through the watch, so a path object opened in the same call (the
     * bars' Edit while a new path is pending) takes the user's main color and options as the ones
     * to give back, never this path's object-only ones. Nothing while a path is open or no restore
     * point is held.
     */
    fun colorsSessionEnded() {
        if (host.open || (restoreLook == null && restoreColor == null)) return
        watch?.cancel()
        watch = null
        endOfNewPath()
    }

    /** Gives the restore points back once the open path ends. */
    private fun watchEnd() {
        if (watch?.isActive == true) return
        watch = controller.scope.launch {
            snapshotFlow { host.open }.first { !it }
            endOfNewPath()
        }
    }

    /**
     * A new path ended: each value that still holds what this editor set gets the user's back,
     * the options saved as the user's again (a strip edit meanwhile saved the path's).
     */
    private fun endOfNewPath() {
        val r = restoreLook
        val l = lastLook
        restoreLook = null
        lastLook = null
        if (r != null && l != null) {
            val s = host.settings
            val modeBack = s.fill == l.fill && s.stroke == l.stroke && s.lastStroke == l.lastStroke
            val fillBack = s.fillColor == l.fillColor
            val back = s.copy(
                fill = if (modeBack) r.fill else s.fill,
                stroke = if (modeBack) r.stroke else s.stroke,
                lastStroke = if (modeBack) r.lastStroke else s.lastStroke,
                fillColor = if (fillBack) r.fillColor else s.fillColor,
            )
            if (back != s) host.update { back }
        }
        val rc = restoreColor
        val lc = lastColor
        restoreColor = null
        lastColor = null
        if (rc != null && lc != null && controller.color == lc) controller.color = rc
    }
}
