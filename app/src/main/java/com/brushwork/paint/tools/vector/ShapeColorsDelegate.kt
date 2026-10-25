package com.brushwork.paint.tools.vector

import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.vector.ObjectColorExtras
import com.brushwork.paint.ui.vector.ObjectColorSheetState
import com.brushwork.paint.ui.vector.paintMode
import com.brushwork.paint.ui.vector.shapeStyle
import com.brushwork.paint.vector.ColorSlot
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VShape
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch

/**
 * v1.10 (R2, R7; design 4.4 A2 step 3): what [ShapeColorsDelegate] needs of the Shape tool. The
 * tool's region `v110-A2 colors` gives it; the tool's state stays private.
 */
internal interface ShapeColorsHost {
    val controller: EditorController

    /** The options the open shape shows (Compose state). */
    val settings: ShapeSettings

    /** The open shape as it would be placed now, or null when no shape is open. */
    fun current(): ShapeObject?

    /** A shape is open (Compose state: it changes when the shape opens or closes). */
    val open: Boolean

    /** Changes each time a shape opens or closes (`ShapeTool.openCount`). */
    val session: Int

    /** A placed shape is open again: its options and the user's main color come back when it closes. */
    val reopened: Boolean

    /** A reopened shape's main color: while the main color still equals it, the user's own comes back at the close. */
    var openedColor: Int

    /** The user's own main color that comes back when a reopened shape closes. */
    var userColor: Int?

    /** Sets the options the open shape shows, without saving them as the user's (no redraw). */
    fun show(s: ShapeSettings)

    /** Gives the user's options [s] back, saved as theirs (`ShapeTool.update`). */
    fun restoreUser(s: ShapeSettings)

    /** Redraws the open shape's preview. */
    fun redraw()
}

/**
 * v1.10 (R2, R7, C4, J21; design 4.4 A2 step 3): the Object colors editor of the shape open in
 * the Shape tool, new or reopened. Box shapes edit [ObjectColorTarget.FILL] and
 * [ObjectColorTarget.STROKE] and have a paint mode; a line or an arrow edits its stroke only.
 *
 * Every edit is a pending one, shown live on the canvas (R7): the fill and the paint mode are the
 * options the open shape shows (`ShapeSettings.fillColor` and `style`, not saved as the user's),
 * the stroke is the main color the shape is drawn with. The colors go through
 * [ObjectColorSlots.set], so a stroke edit pins the fill to its current color and the fill follows
 * the stroke only while the two are equal (C4). A fill color while the mode is Stroke turns the mode
 * to Both, and a stroke color while it is Fill does too. No edit is an undo step of its own: the
 * shape's ✓ step carries the colors.
 *
 * Object-only (the lead's choice): the user's drawing color and options come back.
 * - A reopened shape already brings back the user's options and main color when it closes (the
 *   tool's `restoreUserOptions`); a stroke edit moves the shape's opened color with it, so that
 *   still happens (and a main color the user picked meanwhile is the one that comes back).
 * - A new shape takes a restore point at its first edit here; when its session ends (✓, ✕ or
 *   discard), each value that still holds what this editor set gets the user's back.
 *
 * Cancel (and Back) restores the values the sheet opened with ([begin]). Once no shape is open,
 * [preview], [setPaintMode] and [cancel] do nothing (C13).
 */
internal class ShapeColorsDelegate(private val host: ShapeColorsHost) : ObjectColorEditor, ObjectColorExtras {

    /** The colors and paint mode of the open shape as this editor sets them. */
    private data class Look(val style: ShapeStyle, val fill: Int?, val color: Int)

    /** The values [begin] found (Cancel restores them), with the reopened shape's color bookkeeping. */
    private data class Start(val look: Look, val session: Int, val openedColor: Int, val userColor: Int?)

    private val controller get() = host.controller
    private val lineLike: Boolean get() = host.settings.type.isLineLike

    private var start: Start? = null

    /** A new shape's session: its user values (restored at its end), what this editor set last, and the watch. */
    private var restore: Look? = null
    private var restoreSession = -1
    private var lastSet: Look? = null
    private var watch: Job? = null

    override val targets: List<ObjectColorTarget>
        get() = if (lineLike) listOf(ObjectColorTarget.STROKE) else listOf(ObjectColorTarget.FILL, ObjectColorTarget.STROKE)

    override val paintMode: PaintMode
        get() = if (lineLike) PaintMode.STROKE else host.settings.style.paintMode()

    override fun face(t: ObjectColorTarget): ObjectColorFace = when (t) {
        ObjectColorTarget.FILL -> if (lineLike || !host.settings.style.fill) ObjectColorFace.None else ObjectColorFace.Solid(fillColor())
        ObjectColorTarget.STROKE -> ObjectColorFace.Solid(controller.color)
        else -> ObjectColorFace.None
    }

    override fun colorOf(t: ObjectColorTarget): Int = if (t == ObjectColorTarget.FILL) fillColor() else controller.color

    override fun toast(text: String) = controller.toast(text)

    override fun unavailable(t: ObjectColorTarget): String? = null

    /** The fill the open shape has, painted or not (it follows the main color while it has none of its own). */
    private fun fillColor(): Int = host.settings.fillColor ?: controller.color

    private fun look() = Look(host.settings.style, host.settings.fillColor, controller.color)

    override val sheet = ObjectColorSheetState(host.controller.scope, host.controller, ::cancel) { if (host.open) host.session else null }

    override fun begin() {
        if (host.current() == null) return
        val session = host.session
        // An earlier opening on this shape, neither kept nor cancelled (the sheet went away with
        // the pill's cell), keeps its start values: Cancel restores what it found. The session
        // (`openCount`) never comes back, so a start of an ended shape never matches.
        if (start?.session == session) return
        start = Start(look(), session, host.openedColor, host.userColor)
    }

    override fun setPaintMode(m: PaintMode) {
        if (host.current() == null || lineLike) return
        val s = host.settings
        if (s.style == m.shapeStyle()) return
        apply(s.copy(style = m.shapeStyle()), controller.color)
    }

    override fun preview(t: ObjectColorTarget, argb: Int) {
        val o = host.current() ?: return
        val s = host.settings
        when (t) {
            ObjectColorTarget.FILL -> {
                if (lineLike) return
                val shape = pinned(o, ColorSlot.FILL, argb)
                val style = if (s.style.fill) s.style else ShapeStyle.STROKE_FILL
                apply(s.copy(style = style, fillColor = fillOf(shape)), controller.color)
            }
            ObjectColorTarget.STROKE -> {
                if (host.reopened) {
                    // The main color comes back at the close: a color the user picked meanwhile, else
                    // the one they had before the shape opened.
                    if (controller.color != host.openedColor) host.userColor = controller.color
                    host.openedColor = argb
                }
                val next = if (lineLike) s else {
                    val shape = pinned(o, ColorSlot.STROKE, argb)
                    val style = if (s.style.stroke) s.style else ShapeStyle.STROKE_FILL
                    s.copy(style = style, fillColor = fillOf(shape))
                }
                apply(next, argb)
            }
            else -> Unit
        }
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
        if (host.current() == null || host.session != st.session) return
        if (look() == st.look && (!host.reopened || host.openedColor == st.openedColor)) return
        if (host.reopened) {
            host.openedColor = st.openedColor
            host.userColor = st.userColor
        }
        apply(host.settings.copy(style = st.look.style, fillColor = st.look.fill), st.look.color)
    }

    /** [o] with [slot] painted [argb] through [ObjectColorSlots] (the C4 pin). */
    private fun pinned(o: ShapeObject, slot: ColorSlot, argb: Int): ShapeObject =
        (ObjectColorSlots.set(VShape(0, shape = o), slot, argb) as VShape).shape

    /** The options' fill of [shape]: none of its own (null) while it follows the main color. */
    private fun fillOf(shape: ShapeObject): Int? = if (shape.fillFollowsColor) null else shape.fillColor

    /** Shows [s] and the main color [color] on the open shape. */
    private fun apply(s: ShapeSettings, color: Int) {
        if (!host.reopened) takeRestorePoint()
        if (s != host.settings) host.show(s)
        if (controller.color != color) controller.color = color
        lastSet = look()
        host.redraw()
    }

    /** A new shape's first edit here: its user values, given back when its session ends. */
    private fun takeRestorePoint() {
        val session = host.session
        if (restore != null && restoreSession == session) return
        restore = look()
        restoreSession = session
        lastSet = null
        watch?.cancel()
        watch = controller.scope.launch {
            snapshotFlow { host.open && host.session == session }.first { !it }
            endOfNewShape(session)
        }
    }

    /**
     * A new shape's session ended: each value that still holds what this editor set gets the
     * user's back (the options saved again: a strip edit meanwhile saved the shape's).
     */
    private fun endOfNewShape(session: Int) {
        val r = restore ?: return
        if (restoreSession != session) return
        restore = null
        restoreSession = -1
        val last = lastSet ?: return
        lastSet = null
        val s = host.settings
        val style = if (s.style == last.style) r.style else s.style
        val fill = if (s.fillColor == last.fill) r.fill else s.fillColor
        if (style != s.style || fill != s.fillColor) host.restoreUser(s.copy(style = style, fillColor = fill))
        if (controller.color == last.color) controller.color = r.color
    }
}
