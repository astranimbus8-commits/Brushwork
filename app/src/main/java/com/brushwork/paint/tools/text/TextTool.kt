package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerHandleSnap
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.PinchTargeting
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.POINT_GUIDE_EPS
import com.brushwork.paint.tools.select.pointBox
import com.brushwork.paint.tools.select.pointLines
import com.brushwork.paint.tools.text.frames.FrameGeometry
import com.brushwork.paint.tools.transform.DocBox
import com.brushwork.paint.tools.transform.SnapAxis
import com.brushwork.paint.tools.transform.SnapGuide
import com.brushwork.paint.tools.transform.TransformHandles
import com.brushwork.paint.tools.transform.offset
import kotlin.math.abs
import kotlin.math.max

/**
 * Text tool (like ibisPaint's text): tap the canvas to place a text object, type in the editor
 * dialog, then move it (drag), rotate it (top handle), resize it (corner handle), set the width
 * of its box (side handle; lines wrap inside it), or pinch it with two fingers, before
 * committing it with ✓ into a new TEXT LAYER. Supports horizontal and vertical text (upright
 * letters or Japanese mixed orientation), outline, spacing, alignment, boxes (background,
 * border, rounding) and text following a shape (line, circle, rectangle, curve; see [TextOnPath]).
 *
 * Text layers keep the text object ([Layer.textData], JSON via [TextCodec]), so the text can be
 * EDITED AGAIN: tapping the text of a text layer (or "Edit text" in the layers window / options
 * strip) loads it as pending work, hides the layer's old pixels while editing, and ✓ re-renders
 * that same layer as one undo step ([EditorController.updateTextLayer]); ✕ leaves it untouched.
 * A re-edited text ignores the selection (it is re-rendered whole, so a leftover selection can
 * never cut away part of the old text); new text is clipped to the selection. A re-edited text
 * that is emptied deletes its layer (one undo step, no question asked).
 *
 * Fonts: the built-in families or fonts the user imported ([FontStore], e.g. from dafont),
 * referenced by their content hash; a missing imported font falls back to the built-in family.
 * Placeholder text (Lorem ipsum, English, Japanese dummy text) can be inserted, or fill a fixed
 * text box exactly ([PlaceholderFit]).
 *
 * The editor dialog and the "Numbers" sheet are hosted by `TextToolOptions` and driven by the
 * Compose state here. While the editor is open the text can still be dragged, resized and
 * pinched on the canvas (taps don't apply or reopen it).
 *
 * "Snap to objects" (the app-wide setting, the Snap chip): dragging the text snaps its box like
 * the transform tool does (left / center / right, top / center / bottom to the canvas, the
 * selection, other layers' content, the lines drawn in layers, shape vertices), the box edge
 * handles snap the dragged edge (text turned by a multiple of 90°), the point handles of a text
 * path snap to the same and to the path's other points, and the circle's radius (a square's size)
 * snaps so its outline touches a line. The text layer being edited is never a target. Pinching
 * isn't snapped.
 */
class TextTool(controller: EditorController) : Tool(controller), TextEditorHost {
    override val id = ToolId.TEXT

    // v1.6 TextEditorHost capability flags: the Text tool offers everything.
    override val supportsPath: Boolean get() = true
    override val supportsVertical: Boolean get() = true
    override val supportsWrap: Boolean get() = true
    override val boxSizeEditable: Boolean get() = true

    /** The app's imported fonts (also makes text rendering resolve them). */
    override val fontStore: FontStore = FontStore.get(controller.appContext)

    private var itemState by mutableStateOf<TextItem?>(null)

    /**
     * The text object being placed or edited, null when there is none. While a text layer is
     * edited, every change also redraws that layer's in-place preview (see [LayerPreview]).
     */
    override var item: TextItem?
        get() = itemState
        private set(value) {
            itemState = value
            if (layerPreview != null) refreshLayerPreview()
        }

    /** Appearance of the next new text (its color is taken from the drawing color). */
    var nextSpec by mutableStateOf<TextSpec?>(null)
        private set

    /** The text editor dialog is showing. */
    var editorOpen by mutableStateOf(false)
        private set

    /** True while the editor shows a text that was just created (cancel removes it). */
    override var editingNew by mutableStateOf(false)
        private set

    /** The numeric position/size sheet is showing. */
    override var numbersOpen by mutableStateOf(false)

    /** Units used by the size and position fields. */
    override var sizeUnit by mutableStateOf(LengthUnit.PT)
    override var positionUnit by mutableStateOf(LengthUnit.PX)

    /** The text layer being edited again (null while placing a new text). */
    var editingLayer by mutableStateOf<Layer?>(null)
        private set

    /** Placeholder text options of the editor (kept while the editor is closed and reopened). */
    override var placeholderKind by mutableStateOf(PlaceholderKind.LOREM)
    override var placeholderAmount by mutableStateOf(PlaceholderAmount.PARAGRAPH)
    /** Replace the text (true) or add to it. */
    override var placeholderReplace by mutableStateOf(true)

    /** The "Wrap around a picture" sheet is showing (v1.5). */
    var wrapSheetOpen by mutableStateOf(false)

    /** The outline of the picture a pending text wraps around is drawn (dashed). */
    var showWrapOutline by mutableStateOf(true)

    /** The small "Letter scaling" sheet is showing (v1.6, the options strip's "Letters" chip). */
    var lettersSheetOpen by mutableStateOf(false)

    init {
        // The re-flow listener skips the text open here and tells this tool when its picture changes.
        controller.textWrap.attach(this)
    }

    override val hasPendingWork: Boolean get() = item != null

    /** A text layer that was only tapped (nothing changed yet) doesn't swallow an undo. */
    override val hasUserChanges: Boolean
        get() {
            val cur = item ?: return false
            return editingLayer == null || cur != loadedItem
        }

    private val doc get() = controller.doc
    private var editorBackup: TextItem? = null

    /** The text as loaded from [editingLayer]. */
    private var loadedItem: TextItem? = null

    /** Where the edited layer's old pixels are (hidden while editing). */
    private var loadedInk: Rect? = null
    private var layerPreview: LayerPreview? = null

    /** Document area the in-layer preview covered when it was last redrawn (null = nothing). */
    private var previewRect: Rect? = null

    /** Largest font size allowed (twice the canvas' longer side). */
    override val maxSizePx: Float get() = 2f * max(doc.width, doc.height)

    /** Largest box width / height (a few canvases; more is never useful). */
    override val maxBoxPx: Float get() = 4f * max(doc.width, doc.height)

    /** Default font size for new text: 5 % of the canvas height. */
    val defaultSizePx: Float get() = (doc.height * 0.05f).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)

    /** The spec a new text starts with. */
    fun specForNewText(): TextSpec = (nextSpec ?: TextSpec(sizePx = defaultSizePx)).copy(color = controller.color)

    /**
     * The look of a placed or edited text, kept for the next new text: everything but the fixed
     * box size, which belonged to that text's words (a new text starts fitting its own).
     */
    private fun styleToRemember(spec: TextSpec): TextSpec =
        spec.copy(box = spec.box.copy(width = 0f, height = 0f, minHeight = 0f, minWidth = 0f))

    // ------------------------------------------------------------------ layout cache

    private var prepared: PreparedText? = null

    /** [t] ready to draw (cached while its text, look and path are unchanged). */
    fun preparedFor(t: TextItem): PreparedText = TextRenderer.prepare(t, prepared).also { prepared = it }

    /** Straight layout of [t] (cached while text and spec are unchanged). */
    fun blockFor(t: TextItem): TextBlock = preparedFor(t).block ?: TextRenderer.layout(t.text, t.spec)

    // ------------------------------------------------------------------ editing API (UI)

    /**
     * Creates a new, empty text centered at ([x], [y]) and opens the editor. Refused (with a
     * message) at the layer limit, so nothing is typed that could not be committed.
     */
    fun startTextAt(x: Float, y: Float) {
        if (!controller.canAddLayer) {
            controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size: delete or merge a layer to add text")
            return
        }
        rememberVectorLayer()
        endLayerEdit()
        item = TextItem("", specForNewText(), x.coerceIn(0f, doc.width.toFloat()), y.coerceIn(0f, doc.height.toFloat()))
        editorBackup = null
        editingNew = true
        editorOpen = true
        controller.invalidateOverlay()
    }

    /**
     * Loads the text of the text layer [layer] as pending work to edit it again: the layer
     * becomes active, its old pixels are hidden while the text is edited, and ✓ re-renders it
     * (one undo step). [openEditor] opens the editor dialog at once. Returns false (with a
     * message) when the layer can't be edited.
     */
    fun editLayer(layer: Layer, openEditor: Boolean = false): Boolean {
        // v1.6 seam: a frame of a linked story is edited by the Text frames tool (it switches
        // tools and selects the frame; false for anything else).
        if (controller.textThreads.openForEditing(layer, openEditor)) return true
        if (editingLayer === layer && item != null) {
            if (openEditor) openEditor()
            return true
        }
        if (doc.indexOf(layer) < 0) return false
        val loaded = TextCodec.decode(layer.textData)
        if (loaded == null) {
            controller.toast("\"${layer.name}\" is not an editable text layer")
            return false
        }
        if (!controller.checkEditable(layer)) return false
        // The vector layer to go back to (v1.5): the active one, or the one an earlier text came from.
        val back = controller.activeLayer.takeIf { it.isVectorLayer } ?: vectorReturn
        // Finish another pending text first (placing it adds a layer).
        if (item != null && !commitItem()) discardItem()
        if (doc.indexOf(layer) < 0) return false
        // Selecting pauses this tool (onDeactivate/onActivate); nothing is pending at this point.
        controller.selectLayer(layer)
        vectorReturn = back
        editingLayer = layer
        loadedItem = loaded
        loadedInk = inkOf(layer, loaded)
        editingNew = false
        editorBackup = null
        // The old pixels are hidden and the pending text is drawn in their place (see LayerPreview).
        layerPreview = LayerPreview(layer).also { controller.renderOverride = it }
        loadedInk?.let { controller.tiles.invalidate(it) }
        item = loaded
        // A wrapped text whose picture changed while it couldn't follow (it was locked, or the
        // picture's mask was switched on or off): shown, and on ✓ committed, around the picture
        // as it is now. (Unchanged: nothing is pending, a tap records no step.)
        refreshedWrap(loaded)?.let { item = it }
        if (TextRenderer.isFontMissing(loaded.spec)) {
            controller.toast("The font \"${loaded.spec.fontLabel}\" isn't on this device: the text shows in ${loaded.spec.font.label} until it is imported again")
        }
        if (openEditor) openEditor()
        controller.invalidateOverlay()
        return true
    }

    /**
     * Draws the text layer being edited: its old pixels are hidden and the pending text is drawn
     * in their place THROUGH THE COMPOSITOR, so the layer's order, opacity, blend mode, mask and
     * the layers clipped to it look exactly like the result while editing (like the vector tools'
     * previews). The text is already laid out ([preparedFor] cache), so a tile redraw only draws it.
     */
    private inner class LayerPreview(override val layer: Layer) : LayerRenderOverride {
        override fun drawContent(canvas: Canvas): Boolean {
            val cur = item ?: return true
            TextRenderer.drawItem(canvas, cur, preparedFor(cur), null, doc.colorMode)
            return true
        }
    }

    /** Whether the edited text layer currently shows the pending text in place (else the overlay does). */
    private val previewInLayer: Boolean get() = layerPreview.let { it != null && controller.renderOverride === it }

    /**
     * Redraws the regions the in-layer preview covered before and covers now (separately, so a
     * big jump doesn't redraw everything between them).
     */
    private fun refreshLayerPreview() {
        val cur = item
        val now = cur?.let { t ->
            val prep = preparedFor(t)
            if (prep.isEmpty) null else Rect().also { r ->
                prep.docBounds(t).roundOut(r)
                // A little slack: path bounds come from another engine; never leave a ghost.
                r.inset(-PREVIEW_SLACK_PX, -PREVIEW_SLACK_PX)
            }.takeUnless { it.isEmpty }
        }
        val before = previewRect
        previewRect = now
        // The content may have changed within the same area (color, text of a fixed box...).
        if (before != null && before != now) controller.tiles.invalidate(before)
        now?.let { controller.tiles.invalidate(it) }
        controller.invalidateOverlay()
    }

    /** Stops editing a text layer (no pixel change): shows its pixels again. */
    private fun endLayerEdit() {
        val ov = layerPreview
        if (ov != null && controller.renderOverride === ov) controller.renderOverride = null
        layerPreview = null
        previewRect?.let { controller.tiles.invalidate(it) }
        previewRect = null
        loadedInk?.let { controller.tiles.invalidate(it) }
        loadedInk = null
        loadedItem = null
        editingLayer = null
        controller.invalidateOverlay()
    }

    /**
     * Topmost visible, unlocked text layer whose text is at [p] (the active layer first), or null.
     * v1.6: a frame of a linked story is hit anywhere in its box, also when it shows no text (an
     * empty frame after "Unlink here", a frame past its story's end), so a tap hands it to the
     * Text frames tool ([editLayer]) instead of starting a new text on it; its box is known
     * without laying the frame out.
     */
    fun textLayerAt(p: Vec2): Layer? {
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        val active = doc.activeLayer
        val box = RectF()
        fun hits(l: Layer): Boolean {
            if (!l.isTextLayer || !doc.effectiveVisible(l) || doc.effectiveLocked(l)) return false
            controller.textThreads.frameOf(l)?.let { frame ->
                FrameGeometry.outerRect(frame, box)
                return p.x >= box.left - tol && p.x <= box.right + tol && p.y >= box.top - tol && p.y <= box.bottom + tol
            }
            val (it, prep) = layerText(l) ?: return false
            return it.text.isNotBlank() && prep.contains(it, p, tol)
        }
        if (hits(active)) return active
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (l !== active && hits(l)) return l
        }
        return null
    }

    /** Decoded text of text layers, keyed by layer id (dropped when the stored text changes). */
    private val layerTexts = HashMap<Long, Triple<String, TextItem, PreparedText>>()

    private fun layerText(layer: Layer): Pair<TextItem, PreparedText>? {
        val data = layer.textData ?: return null
        layerTexts[layer.id]?.let { (d, it, p) -> if (d == data && p.fontsCurrent) return it to p }
        val decoded = TextCodec.decode(data) ?: return null
        val prep = TextRenderer.prepare(decoded)
        if (layerTexts.size >= LAYER_CACHE_SIZE) layerTexts.clear()
        layerTexts[layer.id] = Triple(data, decoded, prep)
        return decoded to prep
    }

    /**
     * Reopens the editor for the current text. Already open (its sheet minimized, and "Edit text"
     * tapped): nothing changes, so Cancel still takes back everything since it opened and still
     * removes a new text.
     */
    fun openEditor() {
        val cur = item ?: return
        if (editorOpen) return
        editorBackup = cur
        editingNew = false
        editorOpen = true
    }

    /**
     * Closes the editor keeping the changes; an empty new text is removed. An edited text layer
     * whose text was emptied is deleted right away as one undo step (see [deleteEmptiedLayer]).
     */
    override fun confirmEditor() {
        val cur = item
        if (cur != null && cur.text.isBlank() && editingLayer != null) {
            // Refused (the layer was locked or hidden meanwhile; a message says so): the editor
            // stays open, so the text can be typed again or the edit cancelled.
            if (deleteEmptiedLayer()) {
                editorOpen = false
                editorBackup = null
                returnToVectorLayer()
            }
            return
        }
        editorOpen = false
        editorBackup = null
        if (cur == null || cur.text.isBlank()) {
            item = null
            // Nothing was placed: the active layer never changed.
            vectorReturn = null
            wrapSheetOpen = false
            lettersSheetOpen = false
        } else {
            nextSpec = styleToRemember(cur.spec)
        }
        controller.invalidateOverlay()
    }

    /** Closes the editor reverting its changes (a new text is removed). */
    override fun cancelEditor() {
        editorOpen = false
        item = if (editingNew) null else editorBackup ?: item
        editorBackup = null
        if (item == null) {
            vectorReturn = null
            wrapSheetOpen = false
            lettersSheetOpen = false
        }
        controller.invalidateOverlay()
    }

    /**
     * The text of the edited text layer was emptied: the layer is deleted without asking, as one
     * undo step ("undo to restore" brings it back with its text). The only layer of a drawing
     * can't be deleted: its old text is kept then. Returns false (keep editing) when the layer
     * got locked or hidden meanwhile.
     */
    private fun deleteEmptiedLayer(): Boolean {
        val layer = editingLayer ?: return true
        if (doc.indexOf(layer) < 0) { discardItem(); return true }
        if (doc.pixelLayerCount <= 1) {
            discardItem()
            controller.toast("The text is empty, but a drawing needs at least one layer: the old text was kept")
            return true
        }
        if (!controller.checkEditable(layer)) return false
        discardItem()
        controller.deleteLayer(layer)
        controller.toast("Text layer deleted — undo to restore")
        return true
    }

    override fun setText(text: String) = update { it.copy(text = text) }

    override fun updateSpec(transform: (TextSpec) -> TextSpec) = update { it.copy(spec = transform(it.spec)) }

    // ------------------------------------------------------------------ fonts

    /** Uses the built-in family [font]. */
    override fun setBuiltInFont(font: TextFont) = updateSpec { it.copy(font = font, fontId = null, fontName = null) }

    /**
     * Uses the imported font [font]; the built-in family stays as its fallback (drawn when the
     * font file is missing, e.g. in a project opened on another device).
     */
    override fun setImportedFont(font: ImportedFont) = updateSpec { it.copy(fontId = font.id, fontName = font.name) }

    /** True when the current text asks for an imported font that isn't available. */
    val fontMissing: Boolean get() = item?.spec?.let { TextRenderer.isFontMissing(it) } ?: false

    /**
     * An imported font was deleted or imported: the current text is laid out again (with its
     * fallback font or the font that is back).
     */
    override fun onFontsChanged() {
        val cur = item ?: return
        if (cur.spec.fontId == null) return
        prepared = null
        if (layerPreview != null) refreshLayerPreview()
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ placeholder text

    /**
     * Inserts placeholder text into the current text (see [PlaceholderFit.edit]): replacing or
     * after the text, a short line, one or three paragraphs, or exactly as much as fills the
     * text box. Returns false (with a message) when nothing could be inserted.
     */
    fun insertPlaceholder(
        kind: PlaceholderKind = placeholderKind,
        amount: PlaceholderAmount = placeholderAmount,
        replace: Boolean = placeholderReplace,
    ): Boolean {
        val cur = item ?: return false
        return applyPlaceholder(cur, PlaceholderFit.edit(cur, kind, amount, replace, doc.width, doc.height, maxBoxPx))
    }

    /** Inputs of [PlaceholderFit.edit] for the current text (to compute it off the main thread). */
    override fun placeholderRequest(): PlaceholderFit.Request? {
        val cur = item ?: return null
        return PlaceholderFit.Request(cur, placeholderKind, placeholderAmount, placeholderReplace, doc.width, doc.height, maxBoxPx)
    }

    /**
     * Applies a placeholder [edit] computed for [basedOn]; ignored (false) when the text or its
     * look changed meanwhile. A null edit means the box has no room: a message says so.
     */
    override fun applyPlaceholder(basedOn: TextItem, edit: PlaceholderFit.Edit?): Boolean {
        val cur = item ?: return false
        if (cur.text != basedOn.text || cur.spec != basedOn.spec) return false
        if (edit == null) {
            controller.toast("No room for placeholder text in this box: make the box bigger or the text smaller")
            return false
        }
        update { it.copy(text = edit.text, spec = edit.spec) }
        return true
    }

    /**
     * Where the text object is on the canvas (document px): the center of straight text, or the
     * center of the text as drawn along its path (its path handles and settings move it, not
     * [TextItem.cx]/[TextItem.cy]); ([TextItem.cx], [TextItem.cy]) while nothing is measured.
     * Shown as the position, and the pivot for turning.
     */
    override fun anchorOf(t: TextItem): Vec2 {
        if (!t.path.isActive) return Vec2(t.cx, t.cy)
        val b = preparedFor(t).docBounds(t)
        return if (b.isEmpty || !b.centerX().isFinite() || !b.centerY().isFinite()) Vec2(t.cx, t.cy) else Vec2(b.centerX(), b.centerY())
    }

    /** Moves the text object (and its path) so its center ([anchorOf]) is at ([x], [y]). */
    fun setCenter(x: Float, y: Float) = update { val a = anchorOf(it); translated(it, x - a.x, y - a.y) }

    override fun setCenterX(x: Float) = update { translated(it, x - anchorOf(it).x, 0f) }

    override fun setCenterY(y: Float) = update { translated(it, 0f, y - anchorOf(it).y) }

    override fun nudge(dx: Float, dy: Float) = update { translated(it, dx, dy) }

    /** Turns the text object (and its path, around the text's center [anchorOf]) to [deg]. */
    override fun setRotation(deg: Float) = update {
        if (!deg.isFinite()) return@update it
        val r = TextItem.normalizeDegrees(deg)
        if (!it.path.isActive) return@update it.copy(rotationDeg = r)
        val pivot = anchorOf(it)
        val turn = TextItem.normalizeDegrees(r - it.rotationDeg)
        // The straight position turns along, so going back to straight text stays nearby.
        val c = Vec2(it.cx, it.cy) - pivot
        val c2 = c.rotated(Math.toRadians(turn.toDouble()).toFloat()) + pivot
        it.copy(rotationDeg = r, cx = c2.x, cy = c2.y, path = TextOnPath.transformed(it.path, Vec2.ZERO, 1f, turn, pivot))
    }

    override fun setSizePx(px: Float) {
        if (!px.isFinite()) return
        updateSpec { it.copy(sizePx = px.coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)) }
    }

    /** Toggles vertical text on the current text, or for the next one when there is none. */
    fun toggleVertical() {
        val cur = item
        if (cur != null) updateSpec { it.copy(vertical = !it.vertical) }
        else nextSpec = specForNewText().let { it.copy(vertical = !it.vertical) }
    }

    /** Whether the current (or next) text is vertical. */
    val isVertical: Boolean get() = (item?.spec ?: nextSpec)?.vertical ?: false

    /**
     * Turns the fixed box size on (lines wrap at the current natural width; columns at the
     * current natural height) or off (the box fits the text).
     */
    override fun setFixedBox(on: Boolean) {
        // Lines can only flow around a picture inside a box of fixed width (a box fitting the
        // text would be one long line).
        if (!on && item?.wrapActive == true) {
            controller.toast(WRAP_NEEDS_WIDTH)
            return
        }
        setFixedBoxSpec(on)
    }

    private fun setFixedBoxSpec(on: Boolean) = updateSpec { s ->
        val box = s.box
        // Off: the box fits the text again (its fixed other side goes too).
        if (!on) return@updateSpec s.copy(box = if (s.vertical) box.copy(height = 0f, minWidth = 0f) else box.copy(width = 0f, minHeight = 0f))
        val natural = TextRenderer.layout(item?.text ?: "", s.copy(box = box.copy(width = 0f, height = 0f)), measureInk = false)
        if (s.vertical) s.copy(box = box.copy(height = natural.contentHeight.coerceIn(s.sizePx, maxBoxPx)))
        else s.copy(box = box.copy(width = natural.contentWidth.coerceIn(s.sizePx, maxBoxPx)))
    }

    /** Sets the fixed box length (width of horizontal text, height of vertical text) in px. */
    override fun setBoxLength(px: Float) = updateSpec { s ->
        if (!px.isFinite()) return@updateSpec s
        val v = px.coerceIn(s.sizePx.coerceAtMost(maxBoxPx), maxBoxPx)
        if (s.vertical) s.copy(box = s.box.copy(height = v)) else s.copy(box = s.box.copy(width = v))
    }

    /**
     * With a fixed box: gives it a fixed other side too (height of horizontal text, width of
     * vertical text), starting at the current size, so the box is an area to fill; off = the box
     * fits the text again.
     */
    override fun setFixedDepth(on: Boolean) = updateSpec { s ->
        if (s.box.wrapFor(s.vertical) <= 0f) return@updateSpec s
        val v = if (!on) 0f else {
            val block = TextRenderer.layout(item?.text ?: "", s, measureInk = false)
            (if (s.vertical) block.contentWidth else block.contentHeight).coerceIn(s.sizePx.coerceAtMost(maxBoxPx), maxBoxPx)
        }
        s.copy(box = if (s.vertical) s.box.copy(minWidth = v) else s.box.copy(minHeight = v))
    }

    /** Sets the fixed other side of the box (see [setFixedDepth]) in px. */
    override fun setBoxDepth(px: Float) = updateSpec { s ->
        if (!px.isFinite() || s.box.wrapFor(s.vertical) <= 0f) return@updateSpec s
        val v = px.coerceIn(s.sizePx.coerceAtMost(maxBoxPx), maxBoxPx)
        s.copy(box = if (s.vertical) s.box.copy(minWidth = v) else s.box.copy(minHeight = v))
    }

    override fun updateBox(transform: (TextBoxSpec) -> TextBoxSpec) = updateSpec { it.copy(box = transform(it.box)) }

    override fun applyBoxPreset(preset: TextBoxPreset) = updateSpec { it.copy(box = preset.applyTo(it.box, it.sizePx)) }

    /**
     * Sets the text path. Switching to another shape places a sensible default of that shape
     * around the text ([TextOnPath.defaultFor]); straight keeps the shape for later.
     */
    override fun setPath(spec: TextPathSpec) = update { cur ->
        if (spec.type == cur.path.type) return@update cur.copy(path = spec)
        // Where the text is now (along its current path, or straight): the new shape goes there.
        val at = anchorOf(cur)
        if (!spec.isActive) {
            // Back to straight text: it appears where the text along the path was.
            cur.copy(path = spec, cx = at.x, cy = at.y)
        } else {
            val next = TextOnPath.defaultFor(spec.type, at, TextRenderer.lineWidth(cur.text, cur.spec), cur.spec.sizePx, spec)
            cur.copy(path = next)
        }
    }

    // ------------------------------------------------------------------ letter scaling (v1.6 §3.5)

    /**
     * The "Letters" chip: opens the "Letter scaling" sheet for the pending text; with none, the
     * active text layer is opened for editing first (a message says what to do otherwise).
     */
    fun openLettersSheet() {
        if (item == null) {
            val active = controller.activeLayer
            if (!active.isTextLayer) {
                controller.toast(LETTERS_NEED_TEXT)
                return
            }
            if (!editLayer(active)) return
        }
        // A frame of a linked story switched tools (editLayer's seam): nothing pending here.
        if (item == null) return
        numbersOpen = false
        wrapSheetOpen = false
        lettersSheetOpen = true
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ wrap around a picture (v1.5 §4.1)

    /** Outlines of layers, shared with the re-flow listener. */
    private val contours: WrapContours get() = controller.textWrap.contours

    /** Whether the current text can wrap around a picture (horizontal straight text). */
    val canWrap: Boolean get() = item?.canWrap == true

    /**
     * Layers the current text can wrap around, top first: every layer but text layers,
     * adjustment layers and the text's own layer (v1.7: and folders, which have no pixels). A
     * picture may be above or below the text.
     */
    fun wrapSources(): List<Layer> = doc.layers.asReversed().filter { it !== editingLayer && !it.isTextLayer && !it.isAdjustmentLayer && !it.isFolder }

    /** The layer the current text wraps around (null when wrap is off or that layer was deleted). */
    fun wrapSourceLayer(): Layer? {
        val w = item?.wrap ?: return null
        if (!w.isOn) return null
        return doc.layerById(w.sourceLayerId)?.takeIf { !it.isTextLayer && !it.isAdjustmentLayer }
    }

    /** Wrap is on but its picture layer is gone: the text keeps the last outline. */
    val wrapSourceDeleted: Boolean get() = item?.wrap?.isOn == true && wrapSourceLayer() == null

    /** Document bounds of [t]'s box (straight text), or null. */
    private fun boxBounds(t: TextItem): RectF? {
        val block = preparedFor(t).block ?: return null
        val c = t.corners(block.width, block.height)
        return RectF(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y }).takeIf { it.width() > 0f && it.height() > 0f }
    }

    /**
     * The picture a text wraps around by default: the topmost visible layer of [wrapSources]
     * whose content overlaps [t]'s box and covers less than 90 % of it (so a background is never
     * chosen); null when none does.
     */
    fun defaultWrapSource(t: TextItem? = item): Layer? {
        val text = t ?: return null
        val box = boxBounds(text) ?: return null
        // Only the part of the box on the canvas counts: a line typed past the canvas edges
        // would otherwise find a full-canvas background covering "less than 90 %" of it (and
        // wrapping around a background leaves no room for any text).
        if (!box.intersect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())) return null
        val area = box.width() * box.height()
        for (l in wrapSources()) {
            if (!doc.effectiveVisible(l)) continue
            val b = contours.outline(l)?.bounds ?: continue
            val inter = RectF(b)
            if (!inter.intersect(box)) continue
            if (inter.width() * inter.height() < WRAP_DEFAULT_MAX_COVER * area) return l
        }
        return null
    }

    /**
     * The Wrap chip: opens the wrap sheet. A text that doesn't wrap yet starts wrapping around
     * [defaultWrapSource] (when there is one; the sheet shows Off otherwise). Vertical text and
     * text on a path can't wrap (a message says so).
     */
    fun openWrapSheet() {
        if (item == null) {
            val active = controller.activeLayer
            // No pending text but a text layer is active (the strip offers "Edit text"): wrap that
            // one (editLayer says why when it can't be edited).
            if (!active.isTextLayer) {
                controller.toast("Tap the canvas to add a text, then wrap it around a picture")
                return
            }
            if (!editLayer(active)) return
        }
        val cur = item ?: return
        if (!cur.canWrap) {
            controller.toast(WRAP_HORIZONTAL_ONLY)
            return
        }
        numbersOpen = false
        lettersSheetOpen = false
        if (!cur.wrap.isOn) defaultWrapSource(cur)?.let { setWrapSource(it) }
        wrapSheetOpen = true
        controller.invalidateOverlay()
    }

    /**
     * Wraps the current text around [layer]'s picture (null = off). The first time, the distance
     * becomes 0.3 em; a text without a fixed box width gets one (its width, at least 8 em, at most
     * the canvas width), keeping its left edge in place.
     */
    fun setWrapSource(layer: Layer?) {
        val cur = item ?: return
        if (layer == null) {
            if (cur.wrap.isOn) update { it.copy(wrap = it.wrap.copy(sourceLayerId = 0L, polygons = emptyList())) }
            return
        }
        if (!cur.canWrap || doc.indexOf(layer) < 0 || layer.isTextLayer || layer.isAdjustmentLayer || layer === editingLayer) return
        val polys = contours.polygons(layer, cur.wrap.contour) ?: run {
            controller.toast("Not enough memory to trace \"${layer.name}\"")
            return
        }
        update { t ->
            val first = t.wrap == TextWrapSpec()
            val gap = if (first) (WRAP_DEFAULT_GAP_EM * t.spec.sizePx).coerceIn(0f, TextWrapSpec.MAX_GAP_PX) else t.wrap.gapPx
            val wrapped = t.copy(wrap = t.wrap.copy(sourceLayerId = layer.id, polygons = polys, gapPx = gap))
            if (t.spec.box.width > 0f) wrapped else withFixedWidth(wrapped)
        }
    }

    /** [t] (auto width) with its box width fixed at max(its width, 8 em) within the canvas, left edge kept. */
    private fun withFixedWidth(t: TextItem): TextItem {
        val spec = t.spec
        val natural = TextRenderer.layout(t.text, spec, measureInk = false).contentWidth
        val width = max(natural, WRAP_MIN_WIDTH_EM * spec.sizePx).coerceAtMost(doc.width.toFloat()).coerceIn(spec.sizePx.coerceAtMost(maxBoxPx), maxBoxPx)
        val dx = (width - natural) / 2f
        val shift = Vec2(dx, 0f).rotated(Math.toRadians(t.rotationDeg.toDouble()).toFloat())
        return onCanvasHorizontally(t.copy(spec = spec.copy(box = spec.box.copy(width = width)), cx = t.cx + shift.x, cy = t.cy + shift.y))
    }

    /**
     * [t] moved sideways so its box lies on the canvas (centred on it when it is wider). A line
     * typed without a fixed width can run far past the canvas edges: keeping its left edge when
     * wrap fixes the width would leave the whole text off the canvas.
     */
    private fun onCanvasHorizontally(t: TextItem): TextItem {
        val block = blockFor(t)
        val xs = t.corners(block.width, block.height).map { it.x }
        if (xs.any { !it.isFinite() }) return t
        val l = xs.min()
        val r = xs.max()
        val w = doc.width.toFloat()
        val dx = when {
            r - l >= w -> w / 2f - (l + r) / 2f
            l < 0f -> -l
            r > w -> w - r
            else -> 0f
        }
        return if (dx == 0f) t else translated(t, dx, 0f)
    }

    /** Follows the picture's opaque pixels ([WrapContour.SHAPE]) or its content bounds ([WrapContour.BOX]). */
    fun setWrapContour(contour: WrapContour) {
        val cur = item ?: return
        if (cur.wrap.contour == contour) return
        val src = wrapSourceLayer()
        val polys = when {
            !cur.wrap.isOn -> cur.wrap.polygons
            src != null -> contours.polygons(src, contour) ?: return
            // The picture is gone: its box can still be had from the kept outline.
            contour == WrapContour.BOX -> boxOfPolygons(cur.wrap.polygons)
            else -> cur.wrap.polygons
        }
        update { it.copy(wrap = it.wrap.copy(contour = contour, polygons = polys)) }
    }

    /** Distance between the text and the picture (px, 0..[TextWrapSpec.MAX_GAP_PX]). */
    fun setWrapGap(px: Float) {
        if (!px.isFinite()) return
        val v = px.coerceIn(0f, TextWrapSpec.MAX_GAP_PX)
        update { if (it.wrap.gapPx == v) it else it.copy(wrap = it.wrap.copy(gapPx = v)) }
    }

    fun setWrapSides(sides: WrapSides) = update { if (it.wrap.sides == sides) it else it.copy(wrap = it.wrap.copy(sides = sides)) }

    /**
     * [t] with the current outline of its picture, or null when that is what [t] already has (or
     * it doesn't wrap, its picture is gone or can't be traced).
     */
    private fun refreshedWrap(t: TextItem): TextItem? {
        if (!t.wrapActive) return null
        val src = doc.layerById(t.wrap.sourceLayerId)?.takeIf { !it.isTextLayer && !it.isAdjustmentLayer } ?: return null
        val polys = contours.polygons(src, t.wrap.contour) ?: return null
        return if (polys == t.wrap.polygons) null else t.copy(wrap = t.wrap.copy(polygons = polys))
    }

    /**
     * The picture of the pending text was edited (the re-flow listener skips text open here):
     * its outline is traced again and the text re-flows live, as part of the pending edit.
     */
    internal fun onWrapSourceEdited(source: Layer) {
        val cur = item ?: return
        if (!cur.wrap.isOn || cur.wrap.sourceLayerId != source.id || !cur.canWrap) return
        val polys = contours.polygons(source, cur.wrap.contour) ?: return
        if (polys != cur.wrap.polygons) update { it.copy(wrap = it.wrap.copy(polygons = polys)) }
    }

    private fun boxOfPolygons(polys: List<WrapPolygon>): List<WrapPolygon> {
        if (polys.isEmpty()) return polys
        val l = polys.minOf { p -> p.xs.min() }
        val r = polys.maxOf { p -> p.xs.max() }
        val t = polys.minOf { p -> p.ys.min() }
        val b = polys.maxOf { p -> p.ys.max() }
        return listOf(WrapPolygon(listOf(l, r, r, l), listOf(t, t, b, b)))
    }

    private fun update(transform: (TextItem) -> TextItem) {
        val cur = item ?: return
        item = transform(cur)
        controller.invalidateOverlay()
    }

    /** [t] moved by ([dx], [dy]), its path too. */
    private fun translated(t: TextItem, dx: Float, dy: Float): TextItem {
        if (!dx.isFinite() || !dy.isFinite()) return t
        val path = if (t.path.isActive) TextOnPath.transformed(t.path, Vec2(dx, dy), 1f, 0f, Vec2(t.cx, t.cy)) else t.path
        return t.copy(cx = t.cx + dx, cy = t.cy + dy, path = path)
    }

    // ------------------------------------------------------------------ commit / discard

    /** ✓: bakes the text; a text placed or opened while a vector layer was active goes back to it (v1.5). */
    override fun commit() {
        if (commitItem()) returnToVectorLayer()
    }

    /** ✕ (and undo of pending text): drops the pending text, then back to the vector layer it came from (v1.5). */
    override fun discard() {
        discardItem()
        returnToVectorLayer()
    }

    /** Drops the pending text (no layer change). */
    private fun discardItem() {
        item = null
        editorOpen = false
        numbersOpen = false
        wrapSheetOpen = false
        lettersSheetOpen = false
        editingNew = false
        editorBackup = null
        mode = Mode.NONE
        gestureStart = null
        pinchStart = null
        endSnap()
        clearReadout()
        endLayerEdit()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() {
        endSnap()
        // A gesture cut short by a tool switch never leaves its increments readout behind.
        clearReadout()
        if (hasPendingWork) {
            if (!commitItem()) discardItem()
            // Switching tools with a text pending: vector mode doesn't flip off. (Not while
            // committing, when addLayerWithContent pauses this tool with nothing pending.)
            returnToVectorLayer()
        }
        // Never leave the old pixels of a text layer hidden.
        if (item == null && layerPreview != null) endLayerEdit()
    }

    override fun onDispose() {
        layerTexts.clear()
        prepared = null
    }

    // ------------------------------------------------------------------ vector mode (v1.5 §4.9)

    /**
     * The vector layer that was active when the pending text was placed or opened: placing a
     * text adds (and selects) a text layer, opening one selects it, which would turn vector mode
     * off; after ✓ or ✕ that vector layer is selected again.
     */
    private var vectorReturn: Layer? = null

    private fun rememberVectorLayer() {
        val active = controller.activeLayer
        if (active.isVectorLayer) vectorReturn = active
    }

    /** Selects [vectorReturn] again (once), when nothing is pending and it is still a vector layer. */
    private fun returnToVectorLayer() {
        val back = vectorReturn ?: return
        vectorReturn = null
        if (item != null || doc.indexOf(back) < 0 || !back.isVectorLayer || controller.activeLayer === back) return
        controller.selectLayer(back)
    }

    /**
     * Where the pixels of the text layer [layer] (drawn from [item]) really are: fonts may differ
     * from the device that drew them, so the pixels are scanned. Only the area around the
     * computed bounds is read, unless the ink reaches its edge (then the whole layer is).
     */
    private fun inkOf(layer: Layer, item: TextItem): Rect? = textInkOf(layer, item)

    /**
     * Bakes the text: a new text goes into a new text layer above the active one (clipped to the
     * selection); an edited text layer is re-rendered in place. One undo step either way.
     * Returns false, keeping the text editable, if nothing could be placed.
     */
    fun commitItem(): Boolean {
        val cur = item ?: return true
        val layer = editingLayer
        if (layer != null) {
            if (doc.indexOf(layer) >= 0) return commitLayerEdit(layer, cur)
            // The layer went away meanwhile: place the text as a new one.
            endLayerEdit()
        }
        if (cur.text.isBlank()) { discardItem(); return true }
        val prep = preparedFor(cur)
        val rect = Rect()
        prep.docBounds(cur).roundOut(rect)
        if (prep.isEmpty || !rect.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The text is outside the canvas")
            return false
        }
        val sel = controller.selection
        if (sel != null && !rect.intersect(sel.bounds)) {
            controller.toast("The text is outside the selection")
            return false
        }
        val json = TextCodec.encode(cur)
        // Clear the pending state first: addLayerWithContent deactivates the current tool (this one).
        item = null
        editorOpen = false
        numbersOpen = false
        wrapSheetOpen = false
        lettersSheetOpen = false
        editorBackup = null
        // addLayerWithContent applies the color mode and handles a failed layer allocation itself;
        // the catch covers its grayscale/1-bit conversion, which allocates a canvas-sized buffer.
        val added = try {
            controller.addLayerWithContent(cur.layerName(), "Add text", textData = json) { c ->
                c.clipRect(rect)
                TextRenderer.drawItem(c, cur, prep, sel)
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            null
        }
        if (added == null) {
            item = cur
            return false
        }
        nextSpec = styleToRemember(cur.spec)
        controller.invalidateOverlay()
        return true
    }

    /** Re-renders the edited text layer [layer] with [cur] (see [commitItem]). */
    private fun commitLayerEdit(layer: Layer, cur: TextItem): Boolean {
        val loaded = loadedItem
        // An emptied text deletes its layer (undoable, see deleteEmptiedLayer).
        if (cur.text.isBlank()) return deleteEmptiedLayer()
        if (cur == loaded) { discardItem(); return true }
        val prep = preparedFor(cur)
        val newRect = Rect()
        prep.docBounds(cur).roundOut(newRect)
        val onCanvas = Rect(newRect)
        if (prep.isEmpty || !onCanvas.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The text is outside the canvas")
            return false
        }
        // Everything the old text covered (its real pixels and its computed bounds) plus the new text.
        val dirty = Rect(newRect)
        loadedInk?.let { dirty.union(it) }
        loaded?.let { textRectOf(it) }?.let { dirty.union(it) }
        dirty.inset(-1, -1)
        val json = TextCodec.encode(cur)
        val oldName = layer.name
        val oldAuto = loaded?.layerName()
        var done = false
        try {
            controller.groupUndo("Edit text") {
                done = controller.updateTextLayer(layer, json, "Edit text", dirty) { c -> TextRenderer.drawItem(c, cur, prep, null) }
                // A name that was made from the old text follows the new text.
                if (done && oldAuto != null && isAutoName(oldName, oldAuto) && cur.layerName() != oldName) controller.renameLayer(layer, cur.layerName())
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to update the text")
        }
        // Not applied (layer locked or hidden meanwhile, out of memory): keep editing.
        if (!done) return false
        item = null
        editorOpen = false
        numbersOpen = false
        wrapSheetOpen = false
        lettersSheetOpen = false
        editorBackup = null
        endLayerEdit()
        nextSpec = styleToRemember(cur.spec)
        controller.invalidateOverlay()
        return true
    }

    private fun isAutoName(name: String, auto: String): Boolean =
        name == auto || (name.startsWith("$auto ") && name.substring(auto.length + 1).all { it.isDigit() })

    // ------------------------------------------------------------------ gestures

    /** [DEPTH]: the box's other side (height of horizontal text, width of vertical text). */
    private enum class Mode { NONE, CREATE, MOVE, ROTATE, SCALE, BOX, DEPTH, PATH_HANDLE }

    private var mode = Mode.NONE
    private var downDoc = Vec2.ZERO
    private var downInside = false
    private var moved = false
    private var gestureStart: TextItem? = null
    private var gesturePrepared: PreparedText? = null
    /** Box drag: finger offset from the dragged box edge (local px). */
    private var boxGrab = 0f
    /** Path handle drag: handle index and finger offset from it. */
    private var handleIndex = -1
    private var handleGrab = Vec2.ZERO

    /**
     * "Snap to objects" (the app-wide setting) for drags: a moved text's box (left / center /
     * right, top / center / bottom), the dragged edge of a fixed box and the point handles of a
     * text path align to the canvas, the selection, other layers' content bounds (never the text
     * layer being edited), the lines drawn in layers (Table filter lines...) and shape vertices.
     * Pinching is not snapped.
     */
    private val snap = controller.newSnapSession()

    /** The text's box when the move started (document px), or null. */
    private var startBox: DocBox? = null

    /** Whether a point dragged onto the square grid snaps to it (the snap session leaves the grid to its callers). */
    private val gridSnaps: Boolean
        get() = controller.grid.let { it.enabled && it.snap && it.type == GridType.SQUARE && it.spacingPx > 0f }

    /** What the guide labels keep away from (the moving box or point), or null. */
    private var snapMoving: DocBox? = null

    /** Guides shown right now (document px); empty when nothing is aligned. */
    internal val activeGuides: List<SnapGuide> get() = snap.guides

    /** Starts snapping for a drag; [points] (e.g. the other ends of a text path) are targets too. */
    private fun beginSnap(points: List<Vec2> = emptyList()) {
        snapMoving = null
        snap.begin(exclude = listOfNotNull(editingLayer), includeSelection = true) { pointLines(points, PATH_POINT_LABEL) }
    }

    private fun endSnap() {
        startBox = null
        snapMoving = null
        snap.end()
    }

    /**
     * The box [t] occupies (document px, axis-aligned): its text box (straight text, turned
     * with it) or the bounds of the text along its path; null when it draws nothing.
     */
    private fun snapBox(t: TextItem, prep: PreparedText): DocBox? {
        if (prep.isEmpty) return null
        val block = prep.block
        if (block == null) {
            val b = prep.docBounds(t)
            return if (b.isEmpty) null else DocBox(b.left, b.top, b.right, b.bottom)
        }
        val c = t.corners(block.width, block.height)
        val box = DocBox(c.minOf { it.x }, c.minOf { it.y }, c.maxOf { it.x }, c.maxOf { it.y })
        return box.takeIf { it.left.isFinite() && it.top.isFinite() && it.right.isFinite() && it.bottom.isFinite() }
    }

    /**
     * The text path handles that snap: those that put the path somewhere (line ends and middle,
     * circle center and radius, rectangle center and size corner, curve points and middle), not
     * the angle, corner rounding or text position ones.
     */
    private fun handleSnaps(type: TextPathType, index: Int): Boolean = when (type) {
        TextPathType.NONE -> false
        TextPathType.LINE -> index in 0..2
        TextPathType.CIRCLE, TextPathType.RECT -> index == 0 || index == 1
        TextPathType.CURVE -> index in 0..4
    }

    /**
     * Handles of the same path that stay put while handle [index] moves and that it can line up
     * with (none for the circle's radius and the rectangle's size: only the size changes there).
     */
    private fun fixedHandles(type: TextPathType, index: Int): List<Int> = when (type) {
        TextPathType.LINE -> when (index) { 0 -> listOf(1); 1 -> listOf(0); else -> emptyList() }
        TextPathType.CURVE -> if (index in 0..3) (0..3).filter { it != index } else emptyList()
        else -> emptyList()
    }

    /**
     * Path handle [index] of [path] at [at] (what the finger alone gives) snapped while "Snap to
     * objects" is on. The circle's radius handle and a square's size corner only change the size:
     * it snaps so the outline touches the closest line (the circle's top, bottom, left or right,
     * a square's side or, turned, its corner). Every other handle snaps per axis like a point
     * (then the square grid on axes that didn't snap, when grid snapping is on). Off: [at]
     * unchanged.
     */
    private fun snapPathHandle(path: TextPathSpec, index: Int, at: Vec2): Vec2 {
        if (!controller.snapping.enabled) {
            snap.clearGuides()
            snapMoving = null
            return at
        }
        val c = Vec2(path.cx, path.cy)
        return when {
            path.type == TextPathType.CIRCLE && index == 1 -> {
                val d = at - c
                val r = d.length
                snapSize(c, r, AXIS_DIRS) { nr -> if (r > 1e-3f) c + d * (nr / r) else c + Vec2(nr, 0f) } ?: at
            }
            path.type == TextPathType.RECT && index == 1 && path.keepSquare -> {
                // moveHandle makes the half side the mean of the corner's local offsets.
                val rot = Math.toRadians(path.rotationDeg.toDouble()).toFloat()
                val local = (at - c).rotated(-rot)
                val half = TextPathGeometry.MIN_EXTENT / 2f
                val s = (max(abs(local.x), half) + max(abs(local.y), half)) / 2f
                snapSize(c, s, SQUARE_DIRS.map { it.rotated(rot) }) { ns -> c + Vec2(ns, ns).rotated(rot) } ?: at
            }
            else -> snap.snapPoint(at).also { snapMoving = pointBox(it) }
        }
    }

    /**
     * A size [size] (radius / half side) of a shape centered at [c] snapped so one of its points
     * `c ± dir * size` ([dirs]) lands on the closest line; [place] turns the snapped size into the
     * handle position. Null (guides hidden) when no line is within reach.
     */
    private fun snapSize(c: Vec2, size: Float, dirs: List<Vec2>, place: (Float) -> Vec2): Vec2? {
        val hit = RulerHandleSnap.radius(c, size, dirs, TextPathGeometry.MIN_EXTENT) { v, axis -> snap.snapValue(v, axis) }
        if (hit == null) {
            snap.clearGuides()
            snapMoving = null
            return null
        }
        val (snapped, touching) = hit
        snap.showGuidesFor(pointBox(touching), POINT_GUIDE_EPS)
        snapMoving = pointBox(touching)
        return place(snapped)
    }

    /**
     * The dragged edge of a box resize, snapped: [outer] is how far it is from the corner that
     * stays ([origin] in [start]'s local box coordinates, [dir] the local direction it moves in).
     * Snaps only when the edge is upright or level on the canvas (the text turned by a multiple
     * of 90°). Returns the snapped [outer] (or [outer]) and the edge's guide box.
     */
    private fun snapEdge(start: TextItem, b0: TextBlock, origin: Vec2, dir: Vec2, outer: Float): Pair<Float, DocBox?> {
        if (!controller.snapping.enabled) return outer to null
        val rot = Math.toRadians(start.rotationDeg.toDouble()).toFloat()
        val d = dir.rotated(rot)
        val axis = when {
            abs(d.x) >= AXIS_ALIGNED -> SnapAxis.X
            abs(d.y) >= AXIS_ALIGNED -> SnapAxis.Y
            else -> { snap.clearGuides(); return outer to null }
        }
        val edge = start.localToDoc(origin.x + dir.x * outer, origin.y + dir.y * outer, b0.width, b0.height)
        val v = if (axis == SnapAxis.X) edge.x else edge.y
        val hit = snap.snapValue(v, axis)
        if (hit == null) { snap.clearGuides(); return outer to null }
        val k = if (axis == SnapAxis.X) d.x else d.y
        val snapped = outer + (hit.pos - v) / k
        if (!snapped.isFinite()) { snap.clearGuides(); return outer to null }
        // The edge's extent across (the box's other side), for the guide.
        val c = start.corners(b0.width, b0.height)
        val guide = if (axis == SnapAxis.X) DocBox(hit.pos, c.minOf { it.y }, hit.pos, c.maxOf { it.y })
        else DocBox(c.minOf { it.x }, hit.pos, c.maxOf { it.x }, hit.pos)
        return snapped to guide
    }

    override fun onDown(p: ToolPoint) {
        val t = controller.viewTransform
        downDoc = Vec2(p.x, p.y)
        moved = false
        endSnap()
        val cur = item
        if (cur == null) {
            mode = Mode.CREATE
            gestureStart = null
            return
        }
        // Also while the editor is open (its sheet may be minimized): drags move and resize the text.
        gestureStart = cur
        val prep = preparedFor(cur)
        gesturePrepared = prep
        downInside = prep.contains(cur, downDoc, t.screenToDocLength(t.dp(BOX_PAD_DP + 8f)))
        val s = t.docToScreen(downDoc)
        val hit = t.dp(HANDLE_HIT_DP)
        val block = prep.block
        if (block == null) {
            // Text on a path: the path's handles, else move.
            val hs = TextOnPath.handles(cur.path)
            var best = -1
            var bestD = Float.MAX_VALUE
            for (i in hs.indices) {
                val d = s.distanceTo(t.docToScreen(hs[i]))
                if (d <= hit && d < bestD) { best = i; bestD = d }
            }
            if (best >= 0) {
                mode = Mode.PATH_HANDLE
                handleIndex = best
                handleGrab = hs[best] - downDoc
                // Its other ends are targets too (a level / upright line, aligned curve points).
                if (handleSnaps(cur.path.type, best)) beginSnap(fixedHandles(cur.path.type, best).mapNotNull { hs.getOrNull(it) })
            } else {
                mode = Mode.MOVE
                startBox = snapBox(cur, prep)
                beginSnap()
            }
            return
        }
        val h = handles(cur, block, t)
        // On small text the handle hit areas overlap the box: a handle wins only when the finger
        // is closer to it than to the box center.
        val toCenter = s.distanceTo((h.corners[0] + h.corners[2]) / 2f)
        val toRotate = s.distanceTo(h.rotate)
        val toScale = s.distanceTo(h.scale)
        val toBox = s.distanceTo(h.box)
        val toDepth = h.depth?.let { s.distanceTo(it) } ?: Float.MAX_VALUE
        mode = when {
            toRotate <= hit && toRotate < toCenter && toRotate <= toScale && toRotate <= toBox -> Mode.ROTATE
            toScale <= hit && toScale < toCenter && toScale <= toBox -> Mode.SCALE
            toBox <= hit && toBox < toCenter && toBox <= toDepth -> Mode.BOX
            toDepth <= hit && toDepth < toCenter -> Mode.DEPTH
            else -> Mode.MOVE
        }
        val l = cur.docToLocal(downDoc, block.width, block.height)
        if (mode == Mode.BOX) boxGrab = if (cur.spec.vertical) l.y - block.height else l.x - block.width
        if (mode == Mode.DEPTH) {
            boxGrab = when {
                !cur.spec.vertical -> l.y - block.height
                cur.spec.columnsLeftToRight -> l.x - block.width
                else -> l.x
            }
        }
        when (mode) {
            Mode.MOVE -> {
                startBox = snapBox(cur, prep)
                beginSnap()
            }
            Mode.BOX, Mode.DEPTH -> beginSnap()
            else -> {}
        }
    }

    override fun onMove(p: ToolPoint) {
        val t = controller.viewTransform
        val q = Vec2(p.x, p.y)
        if (!moved) {
            if (t.docToScreen(q).distanceTo(t.docToScreen(downDoc)) < t.dp(TOUCH_SLOP_DP)) return
            moved = true
        }
        val start = gestureStart ?: return
        val c = Vec2(start.cx, start.cy)
        when (mode) {
            Mode.MOVE -> {
                val rawDx = q.x - downDoc.x
                val rawDy = q.y - downDoc.y
                var dx = rawDx; var dy = rawDy
                var snappedX = false
                var snappedY = false
                // The box the finger alone gives snaps (never the last snapped one), so moving
                // farther than the snap distance lets go of a guide.
                startBox?.let { b ->
                    val r = snap.snapMove(b.offset(dx, dy))
                    if (r.snappedX) dx += r.dx
                    if (r.snappedY) dy += r.dy
                    snappedX = r.snappedX
                    snappedY = r.snappedY
                    snapMoving = if (r.snappedX || r.snappedY) b.offset(dx, dy) else null
                }
                // v1.6 increments: an axis no guide holds puts the box's top-left corner on the
                // grid when grid snapping is on, else moves in Length steps from the start (§3.4:
                // guide, then grid, then increment; as a text frame's move). With increments off
                // the move stays v1.5's (guides only: I8).
                if (increments.step(IncrementKind.LENGTH) != null) {
                    val stepped = increments.lengthDelta(Vec2(dx, dy))
                    val grid = startBox?.takeIf { gridSnaps }?.let { b ->
                        controller.snapping.gridPoint(Vec2(b.left + rawDx, b.top + rawDy)).let { g -> Vec2(g.x - b.left, g.y - b.top) }
                    }
                    if (!snappedX) dx = grid?.x ?: stepped.x
                    if (!snappedY) dy = grid?.y ?: stepped.y
                    showReadout(signedLength(dx) + ", " + signedLength(dy))
                }
                val next = translated(start, dx, dy)
                // Text on a path: offset the measured bounds instead of measuring every frame.
                gesturePrepared?.let { if (it.onPath && it.matches(start)) prepared = it.translatedTo(next, dx, dy) }
                item = next
            }
            Mode.ROTATE -> {
                val delta = Math.toDegrees(((q - c).angle - (downDoc - c).angle).toDouble()).toFloat()
                // v1.6 increments: the Angle step replaces the soft 45° detents.
                item = if (increments.step(IncrementKind.ANGLE) != null) {
                    val deg = increments.angle(start.rotationDeg + delta)
                    showReadout(Units.formatNumber(deg.toDouble(), 1) + "°")
                    start.copy(rotationDeg = deg)
                } else {
                    start.copy(rotationDeg = TextItem.snapDegrees(start.rotationDeg + delta))
                }
            }
            Mode.SCALE -> {
                val d0 = (downDoc - c).length
                if (d0 < 1e-3f) return
                val size = if (increments.step(IncrementKind.SCALE) != null) {
                    // v1.6 increments: the size changes by Scale steps of the size at the start.
                    val k = increments.factor((q - c).length / d0)
                    showReadout(Units.formatNumber(k * 100.0, 0) + " %")
                    (start.spec.sizePx * k).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)
                } else {
                    (start.spec.sizePx * (q - c).length / d0).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)
                }
                val k = size / start.spec.sizePx
                item = start.copy(spec = start.spec.scaled(k).copy(sizePx = size))
            }
            Mode.BOX -> item = boxResized(start, q)
            Mode.DEPTH -> item = depthResized(start, q)
            Mode.PATH_HANDLE -> {
                val raw = q + handleGrab
                var at = raw
                if (handleSnaps(start.path.type, handleIndex)) at = snapPathHandle(start.path, handleIndex, raw)
                // v1.6 increments: a point handle moves in Length steps on the axes no guide holds.
                if (increments.step(IncrementKind.LENGTH) != null && stepsAsPoint(start.path.type, handleIndex)) {
                    val d = increments.lengthDelta(q - downDoc)
                    val stepped = downDoc + handleGrab + d
                    // Which axes a guide or the grid placed (asked, not told from the snapped
                    // value: a finger exactly on a guide or grid line is placed too and keeps it).
                    val grid = controller.snapping.enabled && gridSnaps
                    val placedX = grid || snap.snapValue(raw.x, SnapAxis.X) != null
                    val placedY = grid || snap.snapValue(raw.y, SnapAxis.Y) != null
                    at = Vec2(if (placedX) at.x else stepped.x, if (placedY) at.y else stepped.y)
                    showReadout(signedLength(d.x) + ", " + signedLength(d.y))
                }
                item = start.copy(path = TextOnPath.moveHandle(start.path, handleIndex, at))
            }
            Mode.NONE, Mode.CREATE -> return
        }
        controller.invalidateOverlay()
    }

    /**
     * [start] with its box edge dragged to [q]: the width (horizontal text; lines re-wrap) or
     * height (vertical text; columns re-wrap) follows the finger, at least one em, and the
     * opposite corner stays in place.
     */
    private fun boxResized(start: TextItem, q: Vec2): TextItem {
        val b0 = gesturePrepared?.block ?: return start
        val spec = start.spec
        val l = start.docToLocal(q, b0.width, b0.height)
        val inset = spec.box.inset
        val min = spec.sizePx.coerceAtMost(maxBoxPx)
        val vertical = spec.vertical
        val raw = if (vertical) l.y - boxGrab else l.x - boxGrab
        // The dragged edge (right of horizontal text, bottom of vertical text) snaps.
        val outer = snappedOuter(start, b0, Vec2.ZERO, if (vertical) Vec2(0f, 1f) else Vec2(1f, 0f), raw, inset, min)
        val content = steppedBoxLength(outer - 2f * inset).coerceIn(min, maxBoxPx)
        val ns = if (vertical) spec.copy(box = spec.box.copy(height = content)) else spec.copy(box = spec.box.copy(width = content))
        return anchored(start, b0, start.copy(spec = ns))
    }

    /**
     * [raw] (the dragged box edge's distance from the corner that stays) snapped to objects (see
     * [snapEdge]); the guide shows only when the box really reaches the line (not held back by
     * its smallest / largest size).
     */
    private fun snappedOuter(start: TextItem, b0: TextBlock, origin: Vec2, dir: Vec2, raw: Float, inset: Float, min: Float): Float {
        val (outer, guide) = snapEdge(start, b0, origin, dir, raw)
        val content = outer - 2f * inset
        if (guide == null || content < min || content > maxBoxPx) {
            snap.clearGuides()
            snapMoving = null
            edgeSnapped = false
            return raw
        }
        snap.showGuidesFor(guide, POINT_GUIDE_EPS)
        snapMoving = guide
        edgeSnapped = true
        return outer
    }

    /**
     * [start] with the box's other side dragged to [q] (the bottom edge of horizontal text, the
     * left edge of right-to-left columns, else the right edge): a fixed-size area to fill, at
     * least one em; the opposite edge stays in place.
     */
    private fun depthResized(start: TextItem, q: Vec2): TextItem {
        val b0 = gesturePrepared?.block ?: return start
        val spec = start.spec
        val l = start.docToLocal(q, b0.width, b0.height)
        val inset = spec.box.inset
        val min = spec.sizePx.coerceAtMost(maxBoxPx)
        val raw = when {
            !spec.vertical -> l.y - boxGrab
            spec.columnsLeftToRight -> l.x - boxGrab
            else -> b0.width - (l.x - boxGrab)
        }
        // The dragged edge (bottom of horizontal text, else the right or left side) snaps.
        val outer = when {
            !spec.vertical -> snappedOuter(start, b0, Vec2.ZERO, Vec2(0f, 1f), raw, inset, min)
            spec.columnsLeftToRight -> snappedOuter(start, b0, Vec2.ZERO, Vec2(1f, 0f), raw, inset, min)
            else -> snappedOuter(start, b0, Vec2(b0.width, 0f), Vec2(-1f, 0f), raw, inset, min)
        }
        val content = steppedBoxLength(outer - 2f * inset).coerceIn(min, maxBoxPx)
        val ns = if (spec.vertical) spec.copy(box = spec.box.copy(minWidth = content)) else spec.copy(box = spec.box.copy(minHeight = content))
        return anchored(start, b0, start.copy(spec = ns))
    }

    // ------------------------------------------------------------------ increments (v1.6 §3.4)

    /**
     * The app's increments (v1.6 §3.4c, hooks of area C): a move steps by the Length step from
     * the gesture start (per axis, where no guide holds it), the size handle and the pinch by the
     * Scale step of the size at the start, every turn to a multiple of the Angle step (replacing
     * the soft 45° detents), a box side to a multiple of the Length step. Object snapping wins
     * over the step. Off (the default): every gesture is exactly v1.5's. Typed values (the
     * editor, the numbers sheet) are never stepped.
     */
    private val increments get() = controller.increments

    /** Whether the box side being dragged is held by a guide (it is not stepped then). */
    private var edgeSnapped = false

    /** True while this tool's gesture shows the increments readout. */
    private var readoutShown = false

    /** A box length (document px) on the Length step, unless a guide holds the dragged side. */
    private fun steppedBoxLength(v: Float): Float {
        if (edgeSnapped || increments.step(IncrementKind.LENGTH) == null) return v
        val stepped = increments.lengthAbs(v)
        showReadout(Units.format(stepped.toDouble(), positionUnit, doc.dpi.toDouble()))
        return stepped
    }

    /** Text path handles that are points (moved by Length steps); not sizes, angles or the text position. */
    private fun stepsAsPoint(type: TextPathType, index: Int): Boolean = when (type) {
        TextPathType.NONE -> false
        TextPathType.LINE -> index in 0..2
        TextPathType.CIRCLE, TextPathType.RECT -> index == 0
        TextPathType.CURVE -> index in 0..4
    }

    /** "+30 px" in the position unit. */
    private fun signedLength(px: Float): String {
        val s = Units.format(px.toDouble(), positionUnit, doc.dpi.toDouble())
        return if (px > 0f) "+$s" else s
    }

    private fun showReadout(text: String) {
        increments.readout = text
        readoutShown = true
    }

    private fun clearReadout() {
        if (!readoutShown) return
        readoutShown = false
        increments.readout = null
    }

    /**
     * [moved] (a resized [start] whose block was [b0]) placed so the box keeps its top-left
     * corner (horizontal text, or columns left to right), else its top-right corner.
     */
    private fun anchored(start: TextItem, b0: TextBlock, moved: TextItem): TextItem {
        val nb = preparedFor(moved).block ?: return moved
        val rot = Math.toRadians(start.rotationDeg.toDouble()).toFloat()
        val anchorRight = start.spec.vertical && !start.spec.columnsLeftToRight
        val anchor = start.localToDoc(if (anchorRight) b0.width else 0f, 0f, b0.width, b0.height)
        fun place(b: TextBlock): TextItem {
            val half = Vec2(if (anchorRight) -b.width / 2f else b.width / 2f, b.height / 2f).rotated(rot)
            return moved.copy(cx = anchor.x + half.x, cy = anchor.y + half.y)
        }
        var placed = place(nb)
        // Wrapped text: its height depends on where it is (the picture), so the corner is kept
        // for the block it has where it lands.
        if (moved.wrapActive) {
            var last = nb
            repeat(ANCHOR_REFINE_PASSES) {
                val b = preparedFor(placed).block ?: return placed
                if (b.width == last.width && b.height == last.height) return placed
                last = b
                placed = place(b)
            }
        }
        return placed
    }

    override fun onUp(p: ToolPoint) {
        val m = mode
        mode = Mode.NONE
        gestureStart = null
        gesturePrepared = null
        endSnap()
        clearReadout()
        when {
            moved -> {}
            // The editor is open (minimized to its pill while the canvas is used): the text can
            // be dragged and pinched, but a tap neither places it nor restarts the editor.
            editorOpen -> {}
            m == Mode.CREATE -> tapAt(p.x, p.y)
            // While the editor is open a tap neither applies the text nor reopens the editor.
            editorOpen -> {}
            m == Mode.MOVE && downInside -> openEditor()
            // Tap away from the text: place it (like ✓, back to the vector layer it came from),
            // then edit the text tapped or start a new one there.
            m == Mode.MOVE -> if (commitItem()) {
                returnToVectorLayer()
                tapAt(p.x, p.y)
            }
        }
        controller.invalidateOverlay()
    }

    /** A tap without pending text: edit the text layer's text under the finger, else add text. */
    private fun tapAt(x: Float, y: Float) {
        val layer = textLayerAt(Vec2(x, y))
        if (layer == null || !editLayer(layer)) startTextAt(x, y)
    }

    /**
     * A finger resting on the pending text or on one of its handles is about to drag it: the
     * gesture stays a move/rotate/resize instead of turning into the long-press color pick (which
     * still happens away from the text).
     */
    override fun onLongPress(p: ToolPoint): Boolean {
        if (item == null || gestureStart == null) return false
        return mode == Mode.ROTATE || mode == Mode.SCALE || mode == Mode.BOX || mode == Mode.DEPTH || mode == Mode.PATH_HANDLE ||
            (mode == Mode.MOVE && downInside)
    }

    override fun onCancel() {
        if (mode != Mode.NONE && mode != Mode.CREATE) gestureStart?.let { item = it }
        mode = Mode.NONE
        gestureStart = null
        gesturePrepared = null
        endSnap()
        clearReadout()
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ two-finger pinch

    /** The text when a two-finger pinch on it started (null = no pinch). */
    private var pinchStart: TextItem? = null
    private var pinchFocus = Vec2.ZERO

    /**
     * Two fingers on the pending text (the midpoint or either finger on it): pinching scales it,
     * turning rotates it and moving drags it (a text path is transformed along). Elsewhere the
     * view zooms.
     */
    override fun onTwoFingerStart(focus: Vec2, a: Vec2, b: Vec2): Boolean {
        pinchStart = null
        // Pinching isn't snapped (and no guide of a cancelled drag stays).
        endSnap()
        val cur = item ?: return false
        val t = controller.viewTransform
        val prep = preparedFor(cur)
        // A finger (not just the midpoint) must be on the text box as drawn (v1.5, §4.7).
        val block = prep.block
        val accepted = if (block != null) {
            val pad = t.screenToDocLength(t.dp(BOX_PAD_DP))
            PinchTargeting.acceptsQuad(a, b, cur.corners(block.width, block.height, pad), t)
        } else {
            val r = prep.docBounds(cur)
            !r.isEmpty && PinchTargeting.acceptsRect(a, b, r, t)
        }
        if (!accepted) return false
        mode = Mode.NONE
        gestureStart = null
        pinchStart = cur
        pinchFocus = focus
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        val start = pinchStart ?: return
        // Placed or removed meanwhile (a chrome button): nothing to pinch any more.
        if (item == null) { pinchStart = null; return }
        var delta = TransformHandles.pinchRotation(start.rotationDeg, rotationDeg).takeIf { it.isFinite() } ?: 0f
        var scaleK = scale
        var move = translation
        // v1.6 increments: the size by Scale steps, the angle to Angle steps, the move by Length steps.
        if (increments.enabled) {
            val parts = ArrayList<String>(3)
            if (increments.step(IncrementKind.SCALE) != null && scale.isFinite() && scale > 0f) {
                scaleK = increments.factor(scale)
                parts += Units.formatNumber(scaleK * 100.0, 0) + " %"
            }
            if (increments.step(IncrementKind.ANGLE) != null && rotationDeg.isFinite()) {
                // The Angle step replaces the pinch's 45° detents (a small turn rounds back anyway).
                val deg = increments.angle(start.rotationDeg + TextItem.normalizeDegrees(rotationDeg))
                delta = TextItem.normalizeDegrees(deg - start.rotationDeg)
                parts += Units.formatNumber(deg.toDouble(), 1) + "°"
            }
            if (increments.step(IncrementKind.LENGTH) != null && translation.x.isFinite() && translation.y.isFinite()) {
                move = increments.lengthDelta(translation)
            }
            if (parts.isNotEmpty()) showReadout(parts.joinToString("  "))
        }
        val next = start.pinched(pinchFocus, move, scaleK, delta, maxSizePx)
        item = if (start.path.isActive) {
            // The same motion as the text: scaled (like its size) and turned about the start
            // focus, then moved with the fingers. Two calls, so the order is unambiguous.
            val k = if (start.spec.sizePx > 0f) next.spec.sizePx / start.spec.sizePx else 1f
            val tr = Vec2(if (move.x.isFinite()) move.x else 0f, if (move.y.isFinite()) move.y else 0f)
            val turned = TextOnPath.transformed(start.path, Vec2.ZERO, k, delta, pinchFocus)
            next.copy(path = if (tr == Vec2.ZERO) turned else TextOnPath.transformed(turned, tr, 1f, 0f, pinchFocus + tr))
        } else next
        controller.invalidateOverlay()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        val start = pinchStart ?: return
        pinchStart = null
        clearReadout()
        if (cancelled && item != null) item = start
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ overlay

    private class Handles(
        val corners: List<Vec2>, val rotateBase: Vec2, val rotate: Vec2, val scale: Vec2, val box: Vec2, val boxDir: Vec2,
        /** Handle of the box's other side (null without a fixed box). */
        val depth: Vec2?, val depthDir: Vec2,
    )

    private fun handles(t0: TextItem, block: TextBlock, t: ViewTransform): Handles {
        val pad = t.screenToDocLength(t.dp(BOX_PAD_DP))
        val sc = t0.corners(block.width, block.height, pad).map { t.docToScreen(it) }
        val topMid = (sc[0] + sc[1]) / 2f
        val center = (sc[0] + sc[2]) / 2f
        var dir = (topMid - center).normalized()
        if (dir.lengthSq < 0.5f) dir = Vec2(0f, -1f)
        // Box handle: middle of the right edge (horizontal text) or of the bottom edge (vertical).
        val vertical = t0.spec.vertical
        val box = if (vertical) (sc[2] + sc[3]) / 2f else (sc[1] + sc[2]) / 2f
        var boxDir = (box - center).normalized()
        if (boxDir.lengthSq < 0.5f) boxDir = Vec2(1f, 0f)
        // A fixed box also has a handle for its other side (the area "Fill the box" fills): the
        // bottom edge of horizontal text, the left (right-to-left columns) or right edge of vertical.
        val depth = if (t0.spec.box.wrapFor(vertical) <= 0f) null else when {
            !vertical -> (sc[2] + sc[3]) / 2f
            t0.spec.columnsLeftToRight -> (sc[1] + sc[2]) / 2f
            else -> (sc[0] + sc[3]) / 2f
        }
        var depthDir = depth?.let { (it - center).normalized() } ?: Vec2(0f, 1f)
        if (depthDir.lengthSq < 0.5f) depthDir = Vec2(0f, 1f)
        return Handles(sc, topMid, topMid + dir * t.dp(ROTATE_STEM_DP), sc[2], box, boxDir, depth, depthDir)
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val dashPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private var dashDensity = 0f
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boxPath = Path()
    private val guideScreen = Path()
    private var guideSpec: TextPathSpec? = null
    private var guideDoc: Path? = null
    private val tmpRect = RectF()

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val cur = item ?: return
        val prep = preparedFor(cur)
        // An edited text layer shows the text in place (LayerPreview). A new text (or an edit
        // whose layer preview was replaced) is previewed here at document scale, clipped to the
        // canvas (and the selection for new text).
        if (!previewInLayer) {
            val save = canvas.save()
            canvas.concat(t.matrix)
            canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
            TextRenderer.drawItem(canvas, cur, prep, if (editingLayer != null) null else controller.selection, doc.colorMode)
            canvas.restoreToCount(save)
        }

        if (cur.wrapActive && showWrapOutline) drawWrapOutline(canvas, t, cur.wrap.polygons)

        haloPaint.strokeWidth = t.dp(3f)
        linePaint.strokeWidth = t.dp(1.5f)
        val block = prep.block
        if (block == null) {
            drawPathOverlay(canvas, t, cur, prep)
            snap.draw(canvas, t, snapMoving)
            return
        }
        val h = handles(cur, block, t)
        boxPath.rewind()
        boxPath.moveTo(h.corners[0].x, h.corners[0].y)
        for (i in 1..3) boxPath.lineTo(h.corners[i].x, h.corners[i].y)
        boxPath.close()
        canvas.drawPath(boxPath, haloPaint)
        canvas.drawPath(boxPath, linePaint)
        canvas.drawLine(h.rotateBase.x, h.rotateBase.y, h.rotate.x, h.rotate.y, haloPaint)
        canvas.drawLine(h.rotateBase.x, h.rotateBase.y, h.rotate.x, h.rotate.y, linePaint)
        drawBoxHandle(canvas, t, h.box, h.boxDir, fixed = if (cur.spec.vertical) cur.spec.box.height > 0f else cur.spec.box.width > 0f)
        h.depth?.let { drawBoxHandle(canvas, t, it, h.depthDir, fixed = cur.spec.box.depthFor(cur.spec.vertical) > 0f) }
        drawHandle(canvas, t, h.rotate, filled = false)
        drawHandle(canvas, t, h.scale, filled = true)
        // Smart guides of a drag (labels away from the moving box).
        snap.draw(canvas, t, snapMoving)
    }

    /** Guide of the path (dashed), the text's bounds and the path's handles. */
    private fun drawPathOverlay(canvas: Canvas, t: ViewTransform, cur: TextItem, prep: PreparedText) {
        val b = prep.docBounds(cur)
        if (!b.isEmpty) {
            tmpRect.set(b)
            val pad = t.screenToDocLength(t.dp(BOX_PAD_DP))
            tmpRect.inset(-pad, -pad)
            val c = listOf(Vec2(tmpRect.left, tmpRect.top), Vec2(tmpRect.right, tmpRect.top), Vec2(tmpRect.right, tmpRect.bottom), Vec2(tmpRect.left, tmpRect.bottom))
                .map { t.docToScreen(it) }
            boxPath.rewind()
            boxPath.moveTo(c[0].x, c[0].y)
            for (i in 1..3) boxPath.lineTo(c[i].x, c[i].y)
            boxPath.close()
            haloPaint.strokeWidth = t.dp(2f)
            linePaint.strokeWidth = t.dp(1f)
            canvas.drawPath(boxPath, haloPaint)
            canvas.drawPath(boxPath, linePaint)
        }
        // The guide only changes with the path: rebuild it then, map it to the screen each frame.
        if (guideSpec != cur.path || guideDoc == null) {
            guideDoc = TextOnPath.guide(cur.path)
            guideSpec = cur.path
        }
        guideScreen.rewind()
        guideDoc?.let { guideScreen.addPath(it) }
        guideScreen.transform(t.matrix)
        haloPaint.strokeWidth = t.dp(3f)
        dashPaint.strokeWidth = t.dp(1.5f)
        if (dashDensity != t.density) {
            dashDensity = t.density
            dashPaint.pathEffect = DashPathEffect(floatArrayOf(t.dp(6f), t.dp(4f)), 0f)
        }
        canvas.drawPath(guideScreen, haloPaint)
        canvas.drawPath(guideScreen, dashPaint)
        for (hp in TextOnPath.handles(cur.path)) drawHandle(canvas, t, t.docToScreen(hp), filled = true)
    }

    private val wrapPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = WRAP_OUTLINE_COLOR }
    private var wrapDashDensity = 0f
    private var wrapOutlineKey: List<WrapPolygon>? = null
    private val wrapOutlineDoc = Path()
    private val wrapOutlineScreen = Path()

    /** The outline the text wraps around: dashed magenta over a dark halo (document polygons mapped to the screen). */
    private fun drawWrapOutline(canvas: Canvas, t: ViewTransform, polygons: List<WrapPolygon>) {
        if (polygons.isEmpty()) return
        if (wrapOutlineKey !== polygons) {
            wrapOutlineDoc.rewind()
            for (p in polygons) {
                if (p.size < 2) continue
                wrapOutlineDoc.moveTo(p.xs[0], p.ys[0])
                for (i in 1 until p.size) wrapOutlineDoc.lineTo(p.xs[i], p.ys[i])
                wrapOutlineDoc.close()
            }
            wrapOutlineKey = polygons
        }
        wrapOutlineScreen.rewind()
        wrapOutlineScreen.addPath(wrapOutlineDoc)
        wrapOutlineScreen.transform(t.matrix)
        if (wrapDashDensity != t.density) {
            wrapDashDensity = t.density
            wrapPaint.pathEffect = DashPathEffect(floatArrayOf(t.dp(5f), t.dp(4f)), 0f)
        }
        haloPaint.strokeWidth = t.dp(2.5f)
        canvas.drawPath(wrapOutlineScreen, haloPaint)
        wrapPaint.strokeWidth = t.dp(1.5f)
        canvas.drawPath(wrapOutlineScreen, wrapPaint)
    }

    private fun drawHandle(canvas: Canvas, t: ViewTransform, at: Vec2, filled: Boolean) {
        val r = t.dp(HANDLE_RADIUS_DP)
        fillPaint.color = 0x99000000.toInt()
        canvas.drawCircle(at.x, at.y, r + t.dp(1.5f), fillPaint)
        fillPaint.color = if (filled) ACCENT else 0xFFFFFFFF.toInt()
        canvas.drawCircle(at.x, at.y, r, fillPaint)
        if (!filled) {
            // Small circular arrow to mark the rotation handle.
            linePaint.strokeWidth = t.dp(1.5f)
            val ir = r * 0.55f
            canvas.drawArc(at.x - ir, at.y - ir, at.x + ir, at.y + ir, -60f, 270f, false, linePaint)
        }
    }

    /** Pill-shaped handle across the box edge (filled when the box has a fixed size). */
    private fun drawBoxHandle(canvas: Canvas, t: ViewTransform, at: Vec2, dir: Vec2, fixed: Boolean) {
        val along = dir.perpendicular() * t.dp(BOX_HANDLE_HALF_DP)
        val a = at - along; val b = at + along
        fillPaint.color = 0x99000000.toInt()
        val w = t.dp(BOX_HANDLE_WIDTH_DP)
        linePaint.strokeCap = Paint.Cap.ROUND
        haloPaint.strokeCap = Paint.Cap.ROUND
        val halo = haloPaint.strokeWidth
        haloPaint.strokeWidth = w + t.dp(3f)
        canvas.drawLine(a.x, a.y, b.x, b.y, haloPaint)
        haloPaint.strokeWidth = halo
        val lw = linePaint.strokeWidth
        linePaint.strokeWidth = w
        linePaint.color = if (fixed) ACCENT else 0xFFFFFFFF.toInt()
        canvas.drawLine(a.x, a.y, b.x, b.y, linePaint)
        linePaint.color = ACCENT
        linePaint.strokeWidth = lw
        linePaint.strokeCap = Paint.Cap.BUTT
        haloPaint.strokeCap = Paint.Cap.BUTT
    }

    companion object {
        /** Shown when vertical text or text on a path is asked to wrap. */
        const val WRAP_HORIZONTAL_ONLY = "Wrap works with horizontal text"

        /** Shown when the "Letters" chip is tapped with no text to scale. */
        const val LETTERS_NEED_TEXT = "Tap the canvas to add a text, then scale its letters"

        /** Shown when the fixed width of a text that wraps around a picture is turned off. */
        const val WRAP_NEEDS_WIDTH = "Text that wraps around a picture needs a fixed width: turn Wrap off first"

        /** A layer whose content covers this much of the text box is never the default picture (a background). */
        private const val WRAP_DEFAULT_MAX_COVER = 0.9f

        /** Distance a text keeps from its picture when wrap is first turned on (em). */
        private const val WRAP_DEFAULT_GAP_EM = 0.3f

        /** Narrowest box (em) a text without a fixed width gets when it starts wrapping. */
        private const val WRAP_MIN_WIDTH_EM = 8f

        private const val WRAP_OUTLINE_COLOR = 0xFFFF3DD8.toInt()

        /** Layouts a resized wrapped text gets to keep its corner (its height depends on its place). */
        private const val ANCHOR_REFINE_PASSES = 2

        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val HANDLE_RADIUS_DP = 9f
        private const val HANDLE_HIT_DP = 24f
        private const val BOX_PAD_DP = 6f
        private const val ROTATE_STEM_DP = 30f
        private const val TOUCH_SLOP_DP = 8f
        private const val BOX_HANDLE_HALF_DP = 9f
        private const val BOX_HANDLE_WIDTH_DP = 6f
        /** Tapping this close to a text layer's box still counts as tapping its text. */
        private const val HIT_TOLERANCE_DP = 8f
        /** Extra document px redrawn around the in-layer preview. */
        private const val PREVIEW_SLACK_PX = 2
        private const val LAYER_CACHE_SIZE = 16
        /** Guide label of a text path's other points. */
        private const val PATH_POINT_LABEL = "Path point"
        /** A box edge counts as upright / level (and snaps) when its direction is this close to an axis (cos ~0.8°). */
        private const val AXIS_ALIGNED = 0.9999f
        /** A circle touches lines with its right / left and bottom / top. */
        private val AXIS_DIRS = listOf(Vec2(1f, 0f), Vec2(0f, 1f))
        /**
         * A square (half side s) touches lines with its corners `c ± (s, ±s)` (turned with it):
         * upright, they lie on its sides' lines; turned, they are the points that stick out.
         */
        private val SQUARE_DIRS = listOf(Vec2(1f, 1f), Vec2(1f, -1f))
    }
}
