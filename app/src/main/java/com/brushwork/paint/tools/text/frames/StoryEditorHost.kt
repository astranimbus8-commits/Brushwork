package com.brushwork.paint.tools.text.frames

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.tools.text.PlaceholderAmount
import com.brushwork.paint.tools.text.PlaceholderFit
import com.brushwork.paint.tools.text.PlaceholderKind
import com.brushwork.paint.tools.text.TextBoxPreset
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextEditorHost
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.text.TextThreadSpec
import kotlin.math.max

/**
 * The story editor of the Text frames tool (v1.6, §3.6a; area D): what the text editor dialog
 * (`ui/placement/TextEditorDialog`) edits for a linked story, through the frozen
 * [TextEditorHost]. Its [item] is the WHOLE story (`text`) in the story's look, with the box of
 * the frame it was opened from; every change is pending (the frames preview it live, re-flowed at
 * most every 100 ms while typing), OK writes it as one step ("Add text frame" for a new frame,
 * "Edit story" otherwise) and Cancel drops it.
 *
 * Vertical text, text on a path and the box SIZE are not offered (frames are horizontal straight
 * boxes whose size is set on the canvas): [supportsVertical], [supportsPath] and
 * [boxSizeEditable] are false. A story holds at most [TextThreadSpec.MAX_STORY] characters.
 */
class StoryEditorHost internal constructor(private val tool: TextFrameTool) : TextEditorHost {

    override val controller: EditorController get() = tool.controller

    override val fontStore: FontStore = FontStore.get(tool.controller.appContext)

    private var itemState by mutableStateOf<TextItem?>(null)

    /** The story being edited (its whole text), or null while the editor is closed. */
    override val item: TextItem? get() = itemState

    /** True while the editor shows a frame that is not created yet (Cancel creates nothing). */
    override var editingNew by mutableStateOf(false)
        private set

    /** The editor dialog is showing (Compose state). */
    var isOpen by mutableStateOf(false)
        private set

    /** The numbers sheet (frames are placed on the canvas and with the X / Y pill: never shown). */
    override var numbersOpen by mutableStateOf(false)

    override var positionUnit by mutableStateOf(LengthUnit.PX)
    override var sizeUnit by mutableStateOf(LengthUnit.PT)

    override var placeholderKind by mutableStateOf(PlaceholderKind.LOREM)
    override var placeholderAmount by mutableStateOf(PlaceholderAmount.FILL)
    override var placeholderReplace by mutableStateOf(true)

    override val maxSizePx: Float get() = 2f * max(controller.doc.width, controller.doc.height)
    override val maxBoxPx: Float get() = 4f * max(controller.doc.width, controller.doc.height)

    // v1.6 capability flags: a story is horizontal straight text in boxes sized on the canvas.
    override val supportsPath: Boolean get() = false
    override val supportsVertical: Boolean get() = false
    override val supportsWrap: Boolean get() = false
    override val boxSizeEditable: Boolean get() = false

    /** The item the editor opened with (Cancel goes back to it; unchanged = OK records nothing). */
    internal var opened: TextItem? = null
        private set

    /** Opens the editor on [start] (the story text, its look and the frame's box and place). */
    internal fun open(start: TextItem, new: Boolean) {
        opened = start
        itemState = start
        editingNew = new
        numbersOpen = false
        isOpen = true
    }

    /** Closes the editor (nothing written here). */
    internal fun close() {
        isOpen = false
        itemState = null
        opened = null
        editingNew = false
    }

    private fun update(t: (TextItem) -> TextItem) {
        val cur = itemState ?: return
        val next = t(cur)
        if (next == cur) return
        itemState = next
        tool.onStoryChanged()
    }

    /**
     * The placeholder request the editor runs (off the main thread) before [applyPlaceholder].
     * "Fill the box" fills the whole chain in [applyPlaceholder] ([TextFrameTool.fillChain]), so
     * the request for it is only a quick stand-in (a short line replacing the text, which always
     * has an answer): the single-box fill would measure the whole story against the opened
     * frame alone, and say "no room" for a story that already fills that frame while later
     * frames are still empty.
     */
    override fun placeholderRequest(): PlaceholderFit.Request? {
        val cur = itemState ?: return null
        val fill = placeholderAmount == PlaceholderAmount.FILL
        return PlaceholderFit.Request(
            cur, placeholderKind, if (fill) PlaceholderAmount.SHORT else placeholderAmount, fill || placeholderReplace,
            controller.doc.width, controller.doc.height, maxBoxPx,
        )
    }

    /**
     * Applies a placeholder [edit] computed for [basedOn] (ignored when the story or its look
     * changed meanwhile). "Fill the box" fills the whole CHAIN of frames (as InDesign fills a
     * thread): exactly as much placeholder text as all frames hold, measured with the frames' own
     * layout. Other amounts insert the text the edit gives. True when the request is handled
     * (also when nothing fits: a message says so), false only when the story changed meanwhile.
     */
    override fun applyPlaceholder(basedOn: TextItem, edit: PlaceholderFit.Edit?): Boolean {
        val cur = itemState ?: return false
        if (cur.text != basedOn.text || cur.spec != basedOn.spec) return false
        val text = if (placeholderAmount == PlaceholderAmount.FILL) {
            tool.fillChain(cur, placeholderKind, placeholderReplace) ?: run {
                controller.toast(NO_ROOM)
                return true
            }
        } else {
            if (edit == null) {
                controller.toast(NO_ROOM)
                return true
            }
            edit.text
        }
        val capped = TextThreadFlow.cap(text)
        // The look may change (never the frame's box size, which belongs to the frame).
        val spec = edit?.spec?.let { FrameGeometry.withFrameBox(it, cur.spec.box) } ?: cur.spec
        update { it.copy(text = capped, spec = spec) }
        return true
    }

    override fun anchorOf(t: TextItem): Vec2 = Vec2(t.cx, t.cy)

    override fun applyBoxPreset(preset: TextBoxPreset) = updateSpec { it.copy(box = preset.applyTo(it.box, it.sizePx)) }

    /** Closes the editor reverting its changes (a new frame is not created). */
    override fun cancelEditor() = tool.cancelStoryEditor()

    /** Closes the editor writing its changes (one step). */
    override fun confirmEditor() = tool.confirmStoryEditor()

    /** Moves the frame being edited (applied with the story on OK). */
    override fun nudge(dx: Float, dy: Float) {
        if (!dx.isFinite() || !dy.isFinite()) return
        update { it.copy(cx = it.cx + dx, cy = it.cy + dy) }
    }

    override fun onFontsChanged() {
        tool.onStoryChanged()
    }

    /** Frames have a fixed box: its height is set by dragging the frame's handles (kept here). */
    override fun setBoxDepth(px: Float) {
        if (!px.isFinite()) return
        updateBoxSize { it.copy(minHeight = px.coerceIn(1f, maxBoxPx)) }
    }

    /** Frames have a fixed box: its width is set by dragging the frame's handles (kept here). */
    override fun setBoxLength(px: Float) {
        if (!px.isFinite()) return
        updateBoxSize { it.copy(width = Math.round(px.coerceIn(1f, maxBoxPx)).toFloat()) }
    }

    private fun updateBoxSize(t: (TextBoxSpec) -> TextBoxSpec) = update { cur ->
        // The frame's top-left corner stays where it is.
        val left = cur.cx - FrameGeometry.contentWidth(cur) / 2f - cur.spec.box.inset
        val top = cur.cy - FrameGeometry.contentHeight(cur) / 2f - cur.spec.box.inset
        val next = cur.copy(spec = cur.spec.copy(box = t(cur.spec.box)))
        val w = FrameGeometry.contentWidth(next) + 2f * next.spec.box.inset
        val h = FrameGeometry.contentHeight(next) + 2f * next.spec.box.inset
        next.copy(cx = left + w / 2f, cy = top + h / 2f)
    }

    override fun setBuiltInFont(font: TextFont) = updateSpec { it.copy(font = font, fontId = null, fontName = null) }

    override fun setImportedFont(font: ImportedFont) = updateSpec { it.copy(fontId = font.id, fontName = font.name) }

    override fun setCenterX(x: Float) {
        if (!x.isFinite()) return
        update { it.copy(cx = x) }
    }

    override fun setCenterY(y: Float) {
        if (!y.isFinite()) return
        update { it.copy(cy = y) }
    }

    /** A frame always has a fixed width and height: nothing to switch. */
    override fun setFixedBox(on: Boolean) {}

    /** A frame always has a fixed width and height: nothing to switch. */
    override fun setFixedDepth(on: Boolean) {}

    /** Frames are straight boxes (text on a path is not offered: [supportsPath] is false). */
    override fun setPath(spec: TextPathSpec) {}

    /** Frames are not rotated (§7: frame rotation is deferred). */
    override fun setRotation(deg: Float) {}

    override fun setSizePx(px: Float) {
        if (!px.isFinite()) return
        updateSpec { it.copy(sizePx = px.coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)) }
    }

    private companion object {
        const val NO_ROOM = "No room for placeholder text in these frames: make a frame bigger or the text smaller"
    }

    /** The whole story; longer than [TextThreadSpec.MAX_STORY] characters is cut (with a message). */
    override fun setText(text: String) {
        val capped = TextThreadFlow.cap(text)
        if (capped.length < text.length) controller.toast("A linked story holds at most ${TextThreadSpec.MAX_STORY} characters")
        update { it.copy(text = capped) }
    }

    /** The box look (padding, background, border, rounding); its size stays the frame's. */
    override fun updateBox(transform: (TextBoxSpec) -> TextBoxSpec) = updateSpec { s ->
        val b = transform(s.box)
        s.copy(box = b.copy(width = s.box.width, minHeight = s.box.minHeight, height = 0f, minWidth = 0f))
    }

    /** The story's look (shared by its frames): always horizontal, its box size the frame's. */
    override fun updateSpec(transform: (TextSpec) -> TextSpec) = update { cur ->
        val next = transform(cur.spec)
        cur.copy(spec = next.copy(vertical = false, box = next.box.copy(width = cur.spec.box.width, minHeight = cur.spec.box.minHeight, height = 0f, minWidth = 0f)))
    }
}
