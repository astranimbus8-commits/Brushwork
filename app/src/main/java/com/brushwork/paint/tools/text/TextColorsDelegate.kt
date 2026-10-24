package com.brushwork.paint.tools.text

import androidx.compose.runtime.snapshotFlow
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.vector.ObjectColorExtras
import com.brushwork.paint.ui.vector.ObjectColorSheetState
import kotlinx.coroutines.Job
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import kotlin.math.max

/**
 * v1.10 (R2, R7; design 4.4 A2 step 5): what [TextColorsDelegate] needs of a text tool. The Text
 * tool's region `v110-A2 colors` gives it; the tool's state stays private.
 */
internal interface TextColorsHost {
    val controller: EditorController

    /** The look of the open text, or null when no text is open (Compose state). */
    val spec: TextSpec?

    /**
     * Which text is open (Compose state reads), null when none: another value means another
     * text's session. The Text tool places the open text and opens the next one in one call (a tap
     * away, the next text of a layer), so [spec] alone never shows the change.
     */
    val session: Any?

    /** `TextEditorHost.updateSpec`: a pending change of the open text, redrawn (no undo step). */
    fun updateSpec(transform: (TextSpec) -> TextSpec)
}

/**
 * v1.10 (R2, R7, J21; design 4.4 A2 step 5): the Object colors editor of the text open in the
 * Text tool: [ObjectColorTarget.TEXT] (`TextSpec.color`) and [ObjectColorTarget.OUTLINE]
 * (`TextSpec.strokeColor`, what the lead calls the outline of a text). A text has no paint mode.
 *
 * Every edit is a pending change of the open text (`updateSpec`), shown live on the canvas (R7)
 * and kept by the text's ✓ step (no step of its own). The text color is the text's own: the
 * drawing color is never touched. An outline color picked while the text has no outline (width
 * 0) gives it the default outline width ([withOutlineColor], which the text editor's Outline
 * color picker uses too); the sheet's no-outline button ([setNone]) sets the width to 0. Cancel
 * (and Back) restores the color, the outline color and the outline width the sheet opened with
 * ([begin]).
 *
 * Text colors do not go through `ObjectColorSlots`: it maps a text's slots all together
 * (`mapText`, for Change Drawing Color) and has no setter of one slot of the open text.
 *
 * Once no text is open, [preview], [setNone] and [cancel] do nothing (C13); the start values belong
 * to the text's session ([TextColorsHost.session]) and are dropped when it ends, so a Cancel never
 * reaches the next text, even one the tool opened in the same call.
 */
internal class TextColorsDelegate(private val host: TextColorsHost) : ObjectColorEditor, ObjectColorExtras {

    /** The values [begin] found (Cancel restores them) in the text's [session]. */
    private data class Start(val color: Int, val strokeColor: Int, val strokeWidthPx: Float, val session: Any?)

    private var start: Start? = null
    private var watch: Job? = null

    override val targets: List<ObjectColorTarget> get() = listOf(ObjectColorTarget.TEXT, ObjectColorTarget.OUTLINE)

    override val paintMode: PaintMode? get() = null

    override fun face(t: ObjectColorTarget): ObjectColorFace {
        val s = host.spec ?: return ObjectColorFace.None
        return when (t) {
            ObjectColorTarget.TEXT -> ObjectColorFace.Solid(s.color)
            ObjectColorTarget.OUTLINE -> if (s.strokeWidthPx > 0f) ObjectColorFace.Solid(s.strokeColor) else ObjectColorFace.None
            else -> ObjectColorFace.None
        }
    }

    override fun colorOf(t: ObjectColorTarget): Int {
        val s = host.spec ?: return 0
        return if (t == ObjectColorTarget.OUTLINE) s.strokeColor else s.color
    }

    override fun toast(text: String) = host.controller.toast(text)

    override fun unavailable(t: ObjectColorTarget): String? = null

    override fun setPaintMode(m: PaintMode) = Unit

    override val sheet = ObjectColorSheetState(host.controller.scope, host.controller, ::cancel) { host.session }

    override fun begin() {
        val s = host.spec ?: return
        val session = host.session
        // An earlier opening on this text, neither kept nor cancelled (the sheet went away with
        // the pill's cell), keeps its start values: Cancel restores what it found. (The watch
        // drops them when the session ends.)
        if (start != null && start?.session == session) return
        start = Start(s.color, s.strokeColor, s.strokeWidthPx, session)
        watch?.cancel()
        watch = host.controller.scope.launch {
            snapshotFlow { host.spec != null && host.session == session }.first { !it }
            start = null
        }
    }

    override fun preview(t: ObjectColorTarget, argb: Int) {
        if (host.spec == null) return
        when (t) {
            ObjectColorTarget.TEXT -> host.updateSpec { it.copy(color = argb) }
            ObjectColorTarget.OUTLINE -> host.updateSpec { it.withOutlineColor(argb) }
            else -> Unit
        }
    }

    override fun setNone(t: ObjectColorTarget) {
        val s = host.spec ?: return
        if (t == ObjectColorTarget.OUTLINE && s.strokeWidthPx != 0f) host.updateSpec { it.copy(strokeWidthPx = 0f) }
    }

    override fun commit() {
        start = null
        watch?.cancel()
        watch = null
    }

    override fun cancel() {
        val st = start ?: return
        commit()
        val s = host.spec ?: return
        // Another text is open (placed and the next opened in one call, before the watch ran).
        if (host.session != st.session) return
        if (s.color == st.color && s.strokeColor == st.strokeColor && s.strokeWidthPx == st.strokeWidthPx) return
        host.updateSpec { it.copy(color = st.color, strokeColor = st.strokeColor, strokeWidthPx = st.strokeWidthPx) }
    }

    companion object {
        /** The default outline width as a share of the font size (as the box presets' border). */
        const val DEFAULT_OUTLINE_EM = 0.06f

        /** The outline width a text of [sizePx] gets when an outline color is picked while it has none. */
        fun defaultOutlineWidth(sizePx: Float): Float = max(1f, sizePx * DEFAULT_OUTLINE_EM)
    }
}

/**
 * v1.10 (design 4.4 A2 step 5): this look with the outline color [argb]; a text without an
 * outline (width 0) gets [TextColorsDelegate.defaultOutlineWidth] so the color shows (as picking
 * a box background turns the background on). The Object colors sheet and the text editor's
 * Outline color picker both use it.
 */
internal fun TextSpec.withOutlineColor(argb: Int): TextSpec =
    copy(strokeColor = argb, strokeWidthPx = if (strokeWidthPx > 0f) strokeWidthPx else TextColorsDelegate.defaultOutlineWidth(sizePx))
