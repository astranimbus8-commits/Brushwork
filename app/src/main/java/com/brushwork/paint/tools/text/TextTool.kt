package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.DashPathEffect
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableFloatStateOf
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.RulerHandleSnap
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.LayerStructure
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.model.GridType
import com.brushwork.paint.model.IncrementKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectDeletion
import com.brushwork.paint.tools.ObjectPosition
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.PinchTargeting
import com.brushwork.paint.tools.ScaledTool
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
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.select.ArrangeOrder
import com.brushwork.paint.vector.select.ObjectEdits
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
 * v1.9 (R14): a text layer holds up to [TextCodec.MAX_ITEMS] texts. With a text layer active, a
 * tap on empty canvas places the new text IN that layer (its ✓ is v1.8's "Add text" step, the
 * layer keeps its name), unless the More row's “New layer each time” is on ([newLayerEachTime],
 * v1.8's own layer per text), a selection is active (v1.8's new layer, clipped to it), or the
 * layer can take no other text: a frame of a linked story, a text that wraps around a picture,
 * a live array, a full layer (a message says so) (I24). A tap on a text edits that text alone
 * ([editLayer] with its index); a second tap on it opens the text of the same layer under it
 * ([cycleBelow]), round to the top one. The open text's neighbours are kept as they are and
 * drawn with it in picture order; ✓ re-renders only where the open text was and is.
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
class TextTool(controller: EditorController) : Tool(controller), TextEditorHost, KerningEditor, PillPositionTool, ScaledTool, DeletingTool {
    override val id = ToolId.TEXT

    // v1.6 TextEditorHost capability flags: the Text tool offers everything.
    override val supportsPath: Boolean get() = true
    override val supportsVertical: Boolean get() = true
    override val supportsWrap: Boolean get() = true
    override val boxSizeEditable: Boolean get() = true

    /** The app's imported fonts (also makes text rendering resolve them). */
    override val fontStore: FontStore = FontStore.get(controller.appContext)

    private var itemState by mutableStateOf<TextItem?>(null)

    /** Font size of the open text when it was opened or placed: the pill's 100 % (0 while none is open). */
    private var scaleRefPx by mutableFloatStateOf(0f)

    /**
     * The text object being placed or edited, null when there is none. While a text layer is
     * edited, every change also redraws that layer's in-place preview (see [LayerPreview]).
     */
    override var item: TextItem?
        get() = itemState
        private set(value) {
            // v1.7 (item 9): the pill's Scale is relative to the text as it was opened.
            if (value == null) scaleRefPx = 0f else if (itemState == null) scaleRefPx = value.spec.sizePx
            itemState = value
            // v1.9 (R12): the node editing of a path ends with its text (placed, dropped).
            if (value == null) endNodes()
            if (layerPreview != null) refreshLayerPreview()
        }

    /**
     * v1.9 (R12, D31): the node editing of the open text's path. The path sheet's three entries
     * ([TextPathNodeEntry]) start it ([startNodes]); ✓ (TextLabels19.DONE_POINTS, [doneNodes])
     * ends it. While it runs it is the controller's `inToolHistory` (Undo and Redo
     * go through its steps first), fingers pick and drag the guide's nodes instead of moving the
     * text, and the node bar shows.
     */
    val nodeEditor: TextPathNodeEditor = TextPathNodeEditor(object : TextPathNodeEditor.Host {
        override val path: TextPathSpec? get() = item?.path
        override fun setPath(spec: TextPathSpec) = update { it.copy(path = spec) }
        override fun toast(message: String) = controller.toast(message)
    })

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

    private var newLayerState by mutableStateOf(controller.settings.textNewLayerEachTime)

    /**
     * v1.9 (R14): the More row's “New layer each time” ([AppSettings.textNewLayerEachTime], off
     * by default): on, every new text gets a layer of its own as in v1.8; off, a new text joins
     * the active text layer. The tap that places a text reads the setting itself ([joinTarget]).
     */
    var newLayerEachTime: Boolean
        get() = newLayerState
        set(v) {
            newLayerState = v
            controller.settings.textNewLayerEachTime = v
        }

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

    /** The text as loaded from [editingLayer] (null while a new text joins it, [joining]). */
    private var loadedItem: TextItem? = null

    /**
     * v1.9 (R14): the texts of [editingLayer] under the open one (bottom first), kept as they
     * are: the preview and ✓ draw them with the open text, and ✓ stores them before it. While a
     * new text joins the layer ([joining]) these are all its texts.
     */
    private var loadedBelow: List<TextItem> = emptyList()

    /** [loadedBelow] laid out, in the same order. */
    private var belowPrepared: List<PreparedText> = emptyList()

    /**
     * v1.9 (F2, R14): the texts of [editingLayer] above the open one (bottom first), kept as
     * they are: the preview and ✓ draw them with the open text, and ✓ stores them after it.
     */
    private var loadedAbove: List<TextItem> = emptyList()

    /** [loadedAbove] laid out, in the same order. */
    private var abovePrepared: List<PreparedText> = emptyList()

    /**
     * The sizes of [loadedBelow] and [loadedAbove] as Compose state, set with them ([placed]), so
     * the strip's arrange items follow them ([arrangeShown], [canBringForward], [canSendBackward]).
     */
    private var belowCount by mutableIntStateOf(0)
    private var aboveCount by mutableIntStateOf(0)

    /**
     * Where [loadedBelow] and [loadedAbove] draw ([TextLayerDraw.inkRect], in the same order),
     * set with them ([placed]): the in-place preview draws only the texts that meet the tile it
     * redraws, the rule ✓ redraws them by ([TextLayerDraw.drawMeeting]).
     */
    private var belowInk: List<Rect> = emptyList()
    private var aboveInk: List<Rect> = emptyList()

    /** The clip of the tile [LayerPreview] draws (main thread, no allocation per tile). */
    private val previewClip = Rect()

    /**
     * Publishes the open text's place in its layer ([belowCount], [aboveCount]) after a change,
     * and where its other texts draw ([belowInk], [aboveInk]).
     */
    private fun placed() {
        belowCount = loadedBelow.size
        aboveCount = loadedAbove.size
        belowInk = inkRects(loadedBelow, belowPrepared)
        aboveInk = inkRects(loadedAbove, abovePrepared)
    }

    private fun inkRects(items: List<TextItem>, preps: List<PreparedText>): List<Rect> =
        items.indices.map { k -> TextLayerDraw.inkRect(items[k], preps[k]) }

    /**
     * v1.9 (R14): the pending text is a NEW text that goes into [editingLayer] on ✓ (on top of
     * its texts, as v1.8's "Add text" step) instead of into a layer of its own.
     */
    val joining: Boolean get() = editingLayer != null && loadedItem == null

    /** v1.9 (R14): the open text shares its layer with other texts (or is about to join one). */
    val sharesLayer: Boolean get() = editingLayer != null && (joining || loadedBelow.isNotEmpty() || loadedAbove.isNotEmpty())

    /** v1.9 (R14): where the open text is in its layer's picture order (0 = the bottom text). */
    val openIndex: Int get() = loadedBelow.size

    /** v1.9 (R14): the id of the open text in its layer ([TextItem.id]; 0 for a layer's only text or a new text). */
    val openId: Int get() = if (editingLayer == null || joining) 0 else loadedItem?.id ?: 0

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
    fun blockFor(t: TextItem): TextBlock = preparedFor(t).block ?: TextRenderer.layout(t.text, t.spec, kerns = t.kerns)

    // ------------------------------------------------------------------ editing API (UI)

    /**
     * Creates a new, empty text centered at ([x], [y]) and opens the editor. Refused (with a
     * message) at the layer limit, so nothing is typed that could not be committed. v1.9 (R14):
     * with a text layer active that can take it ([joinTarget]) the text joins that layer: it is
     * drawn in it (in place, on top of its texts) and ✓ adds it there; no layer is added.
     */
    fun startTextAt(x: Float, y: Float) {
        endNodes()
        val join = joinTarget()
        if (join == null && !controller.canAddLayer) {
            controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size: delete or merge a layer to add text")
            return
        }
        rememberVectorLayer()
        endLayerEdit()
        if (join != null) beginJoin(join.first, join.second)
        item = TextItem("", specForNewText(), x.coerceIn(0f, doc.width.toFloat()), y.coerceIn(0f, doc.height.toFloat()))
        scaleRefPx = item!!.spec.sizePx
        editorBackup = null
        editingNew = true
        editorOpen = true
        controller.invalidateOverlay()
    }

    /**
     * v1.9 (R14): the active text layer a new text joins, with its texts, or null for a layer of
     * its own (v1.8): “New layer each time” is on (read here, at the tap); a selection is active
     * (the new text is clipped to it, which only a layer of its own keeps); the layer is hidden,
     * locked or shows its mask; it is a frame of a linked story or holds a text that wraps around
     * a picture or is linked (those stay alone, I24); it has a live array; or it is full
     * ([TextCodec.MAX_ITEMS] texts: a message says this text starts a new layer).
     */
    private fun joinTarget(): Pair<Layer, LayerTexts>? {
        if (controller.settings.textNewLayerEachTime) return null
        val layer = controller.activeLayer
        if (!layer.isTextLayer || controller.selection != null) return null
        if (!doc.effectiveVisible(layer) || doc.effectiveLocked(layer) || layer.editingMask || layer.array != null) return null
        if (controller.textThreads.isFrame(layer)) return null
        val texts = layerTexts(layer) ?: return null
        if (texts.items.any { it.wrap.isOn || it.threaded }) return null
        if (texts.items.size >= TextCodec.MAX_ITEMS) {
            controller.toast(TextLabels19.LAYER_FULL)
            return null
        }
        return layer to texts
    }

    /**
     * v1.9 (R14): a new text is to join [layer] (holding [texts]): the layer is drawn from its
     * texts with the new one on top ([LayerPreview]); nothing is stored until ✓.
     */
    private fun beginJoin(layer: Layer, texts: LayerTexts) {
        editingLayer = layer
        loadedItem = null
        loadedBelow = texts.items
        belowPrepared = texts.preps
        loadedAbove = emptyList()
        abovePrepared = emptyList()
        placed()
        loadedInk = null
        layerPreview = LayerPreview(layer).also { controller.renderOverride = it }
    }

    /**
     * Loads the text of the text layer [layer] as pending work to edit it again: the layer
     * becomes active, its old pixels are hidden while the text is edited, and ✓ re-renders it
     * (one undo step). [openEditor] opens the editor dialog at once. Returns false (with a
     * message) when the layer can't be edited. v1.9 (R14): [index] picks the text of a layer of
     * several (picture order, 0 = the bottom one); null keeps the text open in [layer] if one
     * is, and opens the bottom one otherwise (as v1.8, for "Edit text" in the layers window).
     */
    fun editLayer(layer: Layer, openEditor: Boolean = false, index: Int? = null): Boolean {
        // v1.6 seam: a frame of a linked story is edited by the Text frames tool (it switches
        // tools and selects the frame; false for anything else).
        if (controller.textThreads.openForEditing(layer, openEditor)) return true
        if (editingLayer === layer && item != null && (index == null || (!joining && index == openIndex))) {
            if (openEditor) openEditor()
            return true
        }
        if (doc.indexOf(layer) < 0) return false
        if (layerTexts(layer) == null) {
            controller.toast("\"${layer.name}\" is not an editable text layer")
            return false
        }
        if (!controller.checkEditable(layer)) return false
        // The vector layer to go back to (v1.5): the active one, or the one an earlier text came from.
        val back = controller.activeLayer.takeIf { it.isVectorLayer } ?: vectorReturn
        // Finish another pending text first (placing it adds a layer).
        if (item != null && !commitItem()) discardItem()
        if (doc.indexOf(layer) < 0) return false
        // Read again: the text just finished may have been in this layer.
        val texts = layerTexts(layer) ?: return false
        val all = texts.items
        val k = (index ?: 0).coerceIn(0, all.lastIndex)
        val loaded = all[k]
        // Selecting pauses this tool (onDeactivate/onActivate); nothing is pending at this point.
        controller.selectLayer(layer)
        vectorReturn = back
        editingLayer = layer
        loadedItem = loaded
        loadedBelow = all.subList(0, k).toList()
        belowPrepared = texts.preps.subList(0, k).toList()
        loadedAbove = all.subList(k + 1, all.size).toList()
        abovePrepared = texts.preps.subList(k + 1, all.size).toList()
        placed()
        // A text among others: where it was drawn is its own rect (a scan would meet its
        // neighbours' pixels and take the whole layer); scanned when its font is missing here,
        // so pixels drawn with the real font are cleared too.
        loadedInk = if (all.size > 1 && !TextRenderer.isFontMissing(loaded.spec)) {
            textRectOf(loaded, texts.preps[k])?.apply { inset(-PREVIEW_SLACK_PX, -PREVIEW_SLACK_PX) }
        } else {
            inkOf(layer, loaded)
        }
        editingNew = false
        editorBackup = null
        // The old pixels are hidden and the pending text is drawn in their place (see LayerPreview).
        layerPreview = LayerPreview(layer).also { controller.renderOverride = it }
        loadedInk?.let { controller.tiles.invalidate(it) }
        item = loaded
        scaleRefPx = loaded.spec.sizePx
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
     * v1.9 (F2, R14): every text of the layer is drawn, in picture order, the open one in its
     * place ([loadedBelow] under it, [loadedAbove] over it, as laid out when loaded); a new text
     * that joins the layer is drawn on top of its texts. The other texts are drawn only where they
     * meet the tile being redrawn ([belowInk], [aboveInk]): a drag redraws the tiles around the
     * open text every frame, and a layer may hold [TextCodec.MAX_ITEMS] texts (T606).
     */
    private inner class LayerPreview(override val layer: Layer) : LayerRenderOverride {
        override fun drawContent(canvas: Canvas): Boolean {
            // A live array's copies are drawn from the layer over a larger area: every text then.
            // An empty clip (getClipBounds false) leaves previewClip empty: nothing else is drawn.
            val clip = if (layer.array == null) previewClip.also { canvas.getClipBounds(it) } else null
            drawOthers(canvas, loadedBelow, belowPrepared, belowInk, clip)
            item?.let { cur -> TextRenderer.drawItem(canvas, cur, preparedFor(cur), null, doc.colorMode) }
            drawOthers(canvas, loadedAbove, abovePrepared, aboveInk, clip)
            return true
        }

        /** [texts] in order, those whose [ink] meets [clip] (all of them without one). */
        private fun drawOthers(canvas: Canvas, texts: List<TextItem>, preps: List<PreparedText>, ink: List<Rect>, clip: Rect?) {
            for (k in texts.indices) {
                if (clip != null && k < ink.size && !Rect.intersects(ink[k], clip)) continue
                TextRenderer.drawItem(canvas, texts[k], preps[k], null, doc.colorMode)
            }
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
        loadedBelow = emptyList()
        belowPrepared = emptyList()
        loadedAbove = emptyList()
        abovePrepared = emptyList()
        placed()
        editingLayer = null
        controller.invalidateOverlay()
    }

    /** v1.9 (R14): a text of a text layer: the layer and the text's index in it (picture order). */
    data class TextHit(val layer: Layer, val index: Int)

    /**
     * Topmost visible, unlocked text layer whose text is at [p] (the active layer first), or null.
     * v1.6: a frame of a linked story is hit anywhere in its box, also when it shows no text (an
     * empty frame after "Unlink here", a frame past its story's end), so a tap hands it to the
     * Text frames tool ([editLayer]) instead of starting a new text on it; its box is known
     * without laying the frame out.
     */
    fun textLayerAt(p: Vec2): Layer? = textAt(p)?.layer

    /**
     * v1.9 (R14): the text at [p]: in the layer [textLayerAt] finds, the topmost of its texts
     * there. The texts are laid out once per stored text ([layerTexts]), so a tap over a layer of
     * [TextCodec.MAX_ITEMS] texts only tests boxes.
     */
    fun textAt(p: Vec2): TextHit? {
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        val active = doc.activeLayer
        val box = RectF()
        fun hit(l: Layer): Int {
            if (!l.isTextLayer || !doc.effectiveVisible(l) || doc.effectiveLocked(l)) return -1
            controller.textThreads.frameOf(l)?.let { frame ->
                FrameGeometry.outerRect(frame, box)
                val inside = p.x >= box.left - tol && p.x <= box.right + tol && p.y >= box.top - tol && p.y <= box.bottom + tol
                return if (inside) 0 else -1
            }
            val texts = layerTexts(l) ?: return -1
            for (k in texts.items.indices.reversed()) {
                val it = texts.items[k]
                if (it.text.isNotBlank() && texts.preps[k].contains(it, p, tol)) return k
            }
            return -1
        }
        hit(active).let { if (it >= 0) return TextHit(active, it) }
        for (i in doc.layers.indices.reversed()) {
            val l = doc.layers[i]
            if (l === active) continue
            hit(l).let { if (it >= 0) return TextHit(l, it) }
        }
        return null
    }

    /** v1.9 (R14): every text of a text layer as stored in [data], each laid out ([preps], same order). */
    private class LayerTexts(val data: String, val items: List<TextItem>, val preps: List<PreparedText>)

    /**
     * Decoded texts of text layers, keyed by layer id (dropped when the stored text changes). At
     * most [LAYER_CACHE_SIZE] layers and [LAYER_CACHE_TEXTS] laid-out texts: v1.9 layers hold up
     * to [TextCodec.MAX_ITEMS] texts each, so the layer count alone no longer bounds it (T606).
     */
    private val layerTextCache = HashMap<Long, LayerTexts>()

    /** The texts of [layer] (null when it holds none), laid out; cached while its data and the fonts are unchanged. */
    private fun layerTexts(layer: Layer): LayerTexts? {
        val data = layer.textData ?: return null
        layerTextCache[layer.id]?.let { c -> if (c.data == data && c.preps.all { it.fontsCurrent }) return c }
        val items = TextCodec.decodeAll(data)
        if (items.isEmpty()) return null
        val texts = LayerTexts(data, items, items.map { TextRenderer.prepare(it) })
        layerTextCache.remove(layer.id)
        if (layerTextCache.size >= LAYER_CACHE_SIZE || layerTextCache.values.sumOf { it.items.size } + items.size > LAYER_CACHE_TEXTS) {
            layerTextCache.clear()
        }
        layerTextCache[layer.id] = texts
        return texts
    }

    /**
     * Reopens the editor for the current text. Already open (its sheet minimized, and "Edit text"
     * tapped): nothing changes, so Cancel still takes back everything since it opened and still
     * removes a new text.
     */
    fun openEditor() {
        val cur = item ?: return
        // v1.9 (R12): "Edit text" while the path's points are edited ends that first.
        endNodes()
        if (editorOpen) return
        editorBackup = cur
        editingNew = false
        editorOpen = true
    }

    /**
     * Closes the editor keeping the changes; an empty new text is removed. An edited text layer
     * whose text was emptied is deleted right away as one undo step (see [deleteEmptiedLayer]);
     * v1.9 (F2): one that holds other texts keeps them ([removeEmptiedText]).
     */
    override fun confirmEditor() {
        val cur = item
        if (cur != null && cur.text.isBlank() && editingLayer != null && !joining) {
            // Refused (the layer was locked or hidden meanwhile; a message says so): the editor
            // stays open, so the text can be typed again or the edit cancelled.
            if (emptiedText()) {
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
            // v1.9 (R14): an empty text that was to join a layer: the layer shows its pixels again.
            if (joining) endLayerEdit()
            // Nothing was placed: the active layer never changed.
            vectorReturn = null
            wrapSheetOpen = false
            lettersSheetOpen = false
        } else {
            nextSpec = styleToRemember(cur.spec)
            // v1.7 (item 9): a new text is placed as the editor leaves it (the pill's 100 %).
            if (editingLayer == null || joining) scaleRefPx = cur.spec.sizePx
        }
        controller.invalidateOverlay()
    }

    /** Closes the editor reverting its changes (a new text is removed). */
    override fun cancelEditor() {
        editorOpen = false
        item = if (editingNew) null else editorBackup ?: item
        editorBackup = null
        if (item == null) {
            // v1.9 (R14): a new text that was to join a layer: the layer shows its pixels again.
            if (joining) endLayerEdit()
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

    /**
     * v1.9 (F2): the edited text was emptied. A layer that holds other texts keeps them
     * ([removeEmptiedText]); a layer of one text is deleted as in v1.8 ([deleteEmptiedLayer]).
     * Returns false (keep editing) when the layer got locked or hidden meanwhile. v1.9 (R14): an
     * empty new text that was to join a layer is just dropped (nothing was stored).
     */
    private fun emptiedText(): Boolean {
        if (joining) {
            discardItem()
            return true
        }
        val layer = editingLayer
        if (layer != null && (loadedBelow.isNotEmpty() || loadedAbove.isNotEmpty()) && doc.indexOf(layer) >= 0) return removeEmptiedText(layer)
        return deleteEmptiedLayer()
    }

    /**
     * v1.9 (F2, R14): the open text of [layer] was emptied (or deleted with the pill's trash,
     * [deleteObject]) while the layer holds other texts: only it goes, as ONE step named [label]
     * ("Edit text" by default) whose data is the other texts and whose pixels are a fresh
     * rendering of them where the open text was (I1, I2); the layer and its name stay. Returns
     * false (keep editing) when the layer got locked or hidden meanwhile, or memory ran out.
     */
    private fun removeEmptiedText(layer: Layer, label: String = "Edit text"): Boolean {
        if (!controller.checkEditable(layer)) return false
        val loaded = loadedItem
        val rest = loadedBelow + loadedAbove
        val preps = belowPrepared + abovePrepared
        // Everything the old text covered (its real pixels and its computed bounds); the others
        // that meet it are drawn again there.
        val dirty = Rect()
        loadedInk?.let { dirty.union(it) }
        loaded?.let { textRectOf(it) }?.let { dirty.union(it) }
        dirty.inset(-1, -1)
        val json = TextCodec.encodeAll(rest)
        var done = false
        try {
            done = controller.updateTextLayer(layer, json, label, dirty) { c -> TextLayerDraw.drawMeeting(c, layer, dirty, rest, preps) }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to update the text")
        }
        if (!done) return false
        discardItem()
        return true
    }

    override fun setText(text: String) = setText(text, -1)

    /** v1.7: the text with its kerns following their characters ([TextKerns.edited]). */
    override fun setText(text: String, cursor: Int) = update { it.copy(text = text, kerns = TextKerns.edited(it.kerns, it.text, text, cursor)) }

    /**
     * v1.7: kerns of [gaps] in the current text (horizontal text and text on a path, which is
     * never set vertically; see [KerningEditor.setKerns]).
     */
    override fun setKerns(gaps: Iterable<Int>, value: Int) = update {
        if (it.spec.vertical && !it.path.isActive) it else it.copy(kerns = TextKerns.withValue(it.kerns, gaps, value, it.text.length))
    }

    override fun nudgeKerns(gaps: Iterable<Int>, delta: Int) = update {
        if (it.spec.vertical && !it.path.isActive) it else it.copy(kerns = TextKerns.nudged(it.kerns, gaps, delta, it.text.length))
    }

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
     * fallback font or the font that is back). v1.9: also for an imported list marker font
     * (R7), and the other texts of its layer (R14) that use an imported font are laid out again
     * for the in-place preview.
     */
    override fun onFontsChanged() {
        val cur = item ?: return
        val stale = belowPrepared.any { !it.fontsCurrent } || abovePrepared.any { !it.fontsCurrent }
        if (stale) {
            belowPrepared = loadedBelow.mapIndexed { k, t -> belowPrepared[k].takeIf { it.fontsCurrent } ?: TextRenderer.prepare(t) }
            abovePrepared = loadedAbove.mapIndexed { k, t -> abovePrepared[k].takeIf { it.fontsCurrent } ?: TextRenderer.prepare(t) }
            placed()
            if (previewInLayer) editingLayer?.let { controller.tiles.invalidate(Rect(0, 0, doc.width, doc.height)) }
        }
        if (cur.spec.fontId == null && cur.spec.list.markerFontId == null) {
            if (stale) controller.invalidateOverlay()
            return
        }
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
        update { it.copy(text = edit.text, spec = edit.spec, kerns = TextKerns.edited(it.kerns, it.text, edit.text)) }
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
        val natural = TextRenderer.layout(item?.text ?: "", s.copy(box = box.copy(width = 0f, height = 0f)), measureInk = false, kerns = item?.kerns ?: emptyList())
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
            val block = TextRenderer.layout(item?.text ?: "", s, measureInk = false, kerns = item?.kerns ?: emptyList())
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
     * around the text ([TextOnPath.defaultFor]); straight keeps the shape for later. v1.9 (R12):
     * the edited points of a path ([TextPathSpec.nodes]) are kept for straight text too: going
     * back to the shape they stand for ([TextPathNodes.fallbackType]) brings them back, moved so
     * the text stays where it is; another shape drops them.
     */
    override fun setPath(spec: TextPathSpec) = update { cur ->
        if (spec.type == cur.path.type) return@update cur.copy(path = spec)
        // Where the text is now (along its current path, or straight): the new shape goes there.
        val at = anchorOf(cur)
        if (!spec.isActive) {
            // Back to straight text: it appears where the text along the path was.
            cur.copy(path = spec, cx = at.x, cy = at.y)
        } else {
            val kept = spec.nodes
            if (!cur.path.isActive && kept != null && spec.type == TextPathNodes.fallbackType(kept)) {
                restoredNodes(cur, spec, at)?.let { return@update it }
            }
            val next = TextOnPath.defaultFor(spec.type, at, TextRenderer.lineWidth(cur.text, cur.spec), cur.spec.sizePx, spec.copy(nodes = null))
            cur.copy(path = next)
        }
    }

    /** [cur] (straight) along [spec]'s kept nodes again, moved so its center is at [at]; null when they can't be its guide. */
    private fun restoredNodes(cur: TextItem, spec: TextPathSpec, at: Vec2): TextItem? {
        val nodes = TextPathNodes.of(spec) ?: return null
        val back = cur.copy(path = TextPathNodes.withNodes(spec, nodes))
        val a = anchorOf(back)
        return back.copy(path = TextOnPath.transformed(back.path, at - a, 1f, 0f, at))
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

    /**
     * Whether the current text can wrap around a picture (horizontal straight text). v1.9 (R14,
     * I24): not while it shares its layer with other texts (a text that wraps stays alone).
     */
    val canWrap: Boolean get() = item?.canWrap == true && !sharesLayer

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
        // v1.9 (R14, I24): a text among others can only turn an existing wrap off.
        if (sharesLayer && !cur.wrap.isOn) {
            controller.toast(WRAP_ALONE_ONLY)
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
        if (!cur.canWrap || (sharesLayer && !cur.wrap.isOn) || doc.indexOf(layer) < 0 || layer.isTextLayer || layer.isAdjustmentLayer || layer === editingLayer) return
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
        val natural = TextRenderer.layout(t.text, spec, measureInk = false, kerns = t.kerns).contentWidth
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

    /**
     * ✓: bakes the text; a text placed or opened while a vector layer was active goes back to it
     * (v1.5). v1.9 (R12): while the path's points are edited, ✓ is TextLabels19.DONE_POINTS
     * ([doneNodes]); the next ✓ bakes the text.
     */
    override fun commit() {
        if (nodeEditor.active) {
            doneNodes()
            return
        }
        if (commitItem()) returnToVectorLayer()
    }

    // ------------------------------------------------------------------ node editing (v1.9 R12)

    /**
     * The path sheet's [entry] (TextLabels19.ADD_POINTS, EDIT_AS_PATH or EDIT_AS_POLYLINE): the
     * sheet closes keeping its changes (as OK does), the guide becomes nodes and node editing starts
     * with the toast TextLabels19.NODES_STARTED. False (nothing changes) for straight or empty
     * text.
     */
    fun startNodes(entry: TextPathNodeEntry): Boolean {
        val cur = item ?: return false
        if (!cur.path.isActive || cur.text.isBlank()) return false
        if (editorOpen) {
            confirmEditor()
            if (item == null || editorOpen) return false
        }
        numbersOpen = false
        wrapSheetOpen = false
        lettersSheetOpen = false
        if (!nodeEditor.start(entry)) return false
        controller.inToolHistory = nodeEditor
        controller.invalidateOverlay()
        return true
    }

    /**
     * ✓ while the path's points are edited (TextLabels19.DONE_POINTS, the node bar's and the
     * floating one): the editing ends with the nodes as they are, and the editor opens again at
     * its path settings. The text is baked by the next ✓, as one step.
     */
    fun doneNodes() {
        if (!nodeEditor.active) return
        endNodes()
        openEditor()
        controller.invalidateOverlay()
    }

    /** Ends node editing (its nodes stay): Undo and Redo are v1.8's again. */
    private fun endNodes() {
        if (controller.inToolHistory === nodeEditor) controller.inToolHistory = null
        if (nodeEditor.active) nodeEditor.end()
        if (mode == Mode.NODE) mode = Mode.NONE
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

    // ------------------------------------------------------------------ v1.7: the pill and history taps

    /**
     * v1.7 (items 1, 9): the X / Y pill's one source, the open text's centre ([anchorOf], as the
     * v1.6 strip showed it); hidden while no text is open. Moves are part of the pending text.
     */
    override val pillPosition: ObjectPosition = object : ObjectPosition {
        override val position: Vec2? get() = item?.let { anchorOf(it) }
        override val label: String get() = CENTER_LABEL
        override fun setPosition(x: Float?, y: Float?) {
            if (item == null) return
            x?.takeIf { it.isFinite() }?.let { setCenterX(it) }
            y?.takeIf { it.isFinite() }?.let { setCenterY(it) }
        }
    }

    override val pillUnit: LengthUnit get() = positionUnit

    private val textScale = object : ObjectScale {
        override val scalePercent: Vec2?
            get() {
                val cur = item ?: return null
                val ref = scaleRefPx
                if (!(ref > 0f)) return null
                val p = 100f * cur.spec.sizePx / ref
                return Vec2(p, p)
            }

        /** Text scales as a whole: no Scale Y, proportions kept. */
        override val uniformOnly: Boolean get() = true

        override fun setScale(xPercent: Float?, yPercent: Float?) {
            val p = (xPercent ?: yPercent)?.takeIf { it.isFinite() && it > 0f } ?: return
            scaleTo(p)
        }
    }

    /**
     * v1.7 (item 9): the pill's Scale row while a text is open: uniform, in % of the text as it
     * was opened or placed (100 % = its font size then), about its centre, part of the pending
     * text like a pinch.
     */
    override val objectScale: ObjectScale? get() = if (item != null) textScale else null

    /**
     * Scales the open text to [percent] % of its size when opened ([scaleRefPx]) about its centre
     * ([anchorOf]): the whole look (size, outline, box) as the pinch does, and its path.
     */
    fun scaleTo(percent: Float) = update { cur ->
        val ref = scaleRefPx
        if (!(ref > 0f) || !(cur.spec.sizePx > 0f) || !percent.isFinite() || percent <= 0f) return@update cur
        val k = ref * percent / 100f / cur.spec.sizePx
        if (!k.isFinite() || k <= 0f || k == 1f) return@update cur
        val focus = anchorOf(cur)
        val next = cur.pinched(focus, Vec2.ZERO, k, 0f, maxSizePx)
        if (!cur.path.isActive) return@update next
        val applied = next.spec.sizePx / cur.spec.sizePx
        next.copy(path = TextOnPath.transformed(cur.path, Vec2.ZERO, applied, 0f, focus))
    }

    private val textDeletion = object : ObjectDeletion {
        override val deleteLabel: String? get() = if (item != null) PillLabels.deleteObject(DELETE_KIND) else null
        override fun delete() {
            deleteObject()
        }
    }

    /** v1.7 (item 13): the pill's trash cell, "Delete text", while a text is open. */
    override val objectDeletion: ObjectDeletion? get() = if (item != null) textDeletion else null

    /**
     * The pill's trash cell (item 13), without a confirmation: a text never committed is dropped
     * (as ✕); an opened text layer is deleted with its pending changes as ONE step ("Delete
     * text", undo brings the layer back as it was) and the session ends. The last layer of the
     * drawing stays (the layer window's message). True when something was deleted. v1.9 (F2): in
     * a layer that holds other texts only the open text goes, as ONE "Delete text" step; the
     * layer keeps the others ([removeEmptiedText]). v1.9 (R14): a new text that was to join a
     * layer is dropped (as ✕).
     */
    fun deleteObject(): Boolean {
        if (item == null) return false
        val layer = editingLayer?.takeIf { doc.indexOf(it) >= 0 }
        if (layer == null || joining) {
            discard()
            return true
        }
        if (loadedBelow.isNotEmpty() || loadedAbove.isNotEmpty()) {
            if (!removeEmptiedText(layer, HistoryLabels.DELETE_TEXT)) return false
            returnToVectorLayer()
            controller.invalidateOverlay()
            return true
        }
        if (doc.pixelLayerCount <= 1) {
            controller.toast(LayerStructure.LAST_LAYER)
            return false
        }
        if (!controller.checkEditable(layer)) return false
        discardItem()
        val deleted = controller.structure.delete(layer, keepChildren = false, label = HistoryLabels.DELETE_TEXT)
        returnToVectorLayer()
        controller.invalidateOverlay()
        return deleted
    }

    // ------------------------------------------------------------------ v1.9 (R14, R19): the open text as an item

    /**
     * v1.9 (R14): the More row's Duplicate is enabled: a text with words is open. A tap on a text
     * that cannot be copied (it wraps around a picture, its layer is full) says why ([duplicateOpen]).
     */
    val canDuplicate: Boolean get() = item?.text?.isNotBlank() == true

    /**
     * v1.9 (R19): the More row shows Bring forward and Send backward: the open text is stored in
     * a layer of two or more texts (not a new text that is to join one).
     */
    val arrangeShown: Boolean get() = item != null && !joining && editingLayer != null && belowCount + aboveCount > 0

    /** v1.9 (R19): Bring forward applies: the open text is stored in a layer with texts above it. */
    val canBringForward: Boolean get() = item != null && !joining && editingLayer != null && aboveCount > 0

    /** v1.9 (R19): Send backward applies: the open text is stored in a layer with texts below it. */
    val canSendBackward: Boolean get() = item != null && !joining && editingLayer != null && belowCount > 0

    /** The texts the open text's layer holds once it is stored (1 for a new text of its own). */
    private fun textsInOpenLayer(): Int = if (editingLayer == null) 1 else loadedBelow.size + loadedAbove.size + 1

    /**
     * v1.9 (R14, D24): the More row's Duplicate. The open text's pending changes are committed
     * first, as ✓ does (their own step; a new text is placed, in its own layer or the one it
     * joins); then [TextItemOps.duplicateItems] copies it with the object bar's offset as ONE step
     * [LayerLabels19.DUPLICATE_STEP], right above it in picture order, and the copy opens. Refused
     * with a message, before anything is committed, for a text that wraps around a picture
     * ([WRAP_ALONE_ONLY]) and in a full layer (the first sentence of [TextLabels19.LAYER_FULL]).
     * True when a copy was made.
     */
    fun duplicateOpen(): Boolean {
        val cur = item ?: return false
        if (cur.text.isBlank()) return false
        if (cur.wrap.isOn) {
            controller.toast(WRAP_ALONE_ONLY)
            return false
        }
        if (textsInOpenLayer() >= TextCodec.MAX_ITEMS) {
            controller.toast(LAYER_FULL_ONLY)
            return false
        }
        val before = editingLayer?.takeIf { doc.indexOf(it) >= 0 }
        if (before != null && !controller.checkEditable(before)) return false
        // Where the text is once stored: its place (a new text that joins: on top of the others).
        val k = openIndex
        if (!commitItem()) return false
        // A new text of its own: commitItem added its layer and made it the active one.
        val layer = before ?: controller.activeLayer.takeIf { it.isTextLayer } ?: return false
        val texts = layerTexts(layer) ?: return false
        val index = if (before == null) texts.items.lastIndex else k
        val id = texts.items.getOrNull(index)?.id ?: return false
        val action = TextItemOps.duplicateItems(controller, layer, null, setOf(id))
        if (action == null) {
            // Committed, but nothing copied (the layer got locked or hidden): the text stays open.
            editLayer(layer, index = index)
            return false
        }
        controller.pushUndo(action)
        editLayer(layer, index = index + 1)
        return true
    }

    /**
     * v1.9 (R19 (a), Arrange): the More row's Bring forward ([dir] +1) and Send backward
     * (-1) for the open text of a layer of several. Its pending changes are committed first (their
     * own step, as ✓); then the layer's picture order changes by [ArrangeOrder.step] (past the
     * next text it overlaps, or one plain step when none does) as ONE step named after the item
     * ([ObjectEdits.Arrange.label]), and the text stays open in its new place. Only the moved
     * text's area is drawn again: elsewhere no two texts change places. False when nothing moved.
     */
    fun arrangeOpen(dir: Int): Boolean {
        val cur = item ?: return false
        val layer = editingLayer?.takeIf { doc.indexOf(it) >= 0 } ?: return false
        if (joining || cur.text.isBlank() || (dir != 1 && dir != -1)) return false
        if (if (dir > 0) loadedAbove.isEmpty() else loadedBelow.isEmpty()) return false
        if (!controller.checkEditable(layer)) return false
        val k = openIndex
        if (!commitItem()) return false
        val texts = layerTexts(layer) ?: return false
        if (k !in texts.items.indices) return false
        val bounds = texts.items.indices.map { i -> texts.preps[i].docBounds(texts.items[i]) }
        val perm = ArrangeOrder.step(bounds, setOf(k), dir)
        if (perm == null) {
            editLayer(layer, index = k)
            return false
        }
        val items = perm.map { texts.items[it] }
        val preps = perm.map { texts.preps[it] }
        val moved = texts.items[k]
        val dirty = TextLayerDraw.inkRect(moved, texts.preps[k])
        // Drawn with another font on another device: its real pixels may reach further.
        if (TextRenderer.isFontMissing(moved.spec)) inkOf(layer, moved)?.let { dirty.union(it) }
        val label = (if (dir > 0) ObjectEdits.Arrange.FORWARD else ObjectEdits.Arrange.BACKWARD).label
        var done = false
        try {
            done = controller.updateTextLayer(layer, TextCodec.encodeAll(items), label, dirty.takeUnless { it.isEmpty }) { c ->
                TextLayerDraw.drawMeeting(c, layer, dirty, items, preps)
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to update the text")
        }
        editLayer(layer, index = if (done) perm.indexOf(k) else k)
        return done
    }

    /** The open text as it is now: its pending changes are this tool's in-tool history (item 10). */
    private data class TextMark(val item: TextItem, val layer: Layer?)

    /** v1.7 (item 10): the open text and its layer, null while none is open. */
    override fun historyMark(): Any? = item?.let { TextMark(it, editingLayer) }

    /**
     * v1.7 (item 10): puts the open text back as it was at [mark] (a history tap over the UI takes
     * back what its first finger changed: a slider, a chip). Nothing when another text (or none)
     * is open now.
     */
    override fun rollbackHistory(mark: Any?) {
        val m = mark as? TextMark ?: return
        val cur = item ?: return
        if (editingLayer !== m.layer || cur == m.item) return
        item = m.item
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
        layerTextCache.clear()
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
        val json = TextCodec.encodeAll(listOf(cur))
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

    /**
     * Re-renders the edited text layer [layer] with [cur] (see [commitItem]). v1.9 (F2, R14):
     * [cur] replaces the text it was loaded from, between the layer's other texts
     * ([loadedBelow], [loadedAbove]), which are stored as they were; a new text that joins the
     * layer ([joining]) is stored on top of them as v1.8's "Add text" step. Only where the open
     * text was and is gets cleared, and the other texts that meet it are drawn again there
     * ([TextLayerDraw.drawMeeting]), in the same step. The layer's name follows the text only while it holds
     * that text alone (v1.8).
     */
    private fun commitLayerEdit(layer: Layer, cur: TextItem): Boolean {
        val loaded = loadedItem
        val join = joining
        val below = loadedBelow
        val above = loadedAbove
        // An emptied text deletes its layer (undoable, see deleteEmptiedLayer), or only itself
        // when the layer holds other texts (removeEmptiedText); an empty new text is dropped.
        if (cur.text.isBlank()) return emptiedText()
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
        val items = below + cur + above
        val preps = belowPrepared + prep + abovePrepared
        val json = TextCodec.encodeAll(items)
        val label = if (join) "Add text" else "Edit text"
        val alone = below.isEmpty() && above.isEmpty()
        val oldName = layer.name
        val oldAuto = loaded?.layerName()?.takeIf { alone }
        var done = false
        try {
            controller.groupUndo(label) {
                done = controller.updateTextLayer(layer, json, label, dirty) { c -> TextLayerDraw.drawMeeting(c, layer, dirty, items, preps) }
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
    /** NODE (v1.9 R12): a finger on the canvas while the path's points are edited ([nodeEditor]). */
    private enum class Mode { NONE, CREATE, MOVE, ROTATE, SCALE, BOX, DEPTH, PATH_HANDLE, NODE }

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
     * circle center and radius, rectangle center and size corner, curve points and middle, every
     * node of edited points), not the angle, corner rounding or text position ones.
     */
    private fun handleSnaps(path: TextPathSpec, index: Int): Boolean {
        TextPathNodes.of(path)?.let { return index in it.points.indices }
        return flatHandleSnaps(path.type, index)
    }

    private fun flatHandleSnaps(type: TextPathType, index: Int): Boolean = when (type) {
        TextPathType.NONE -> false
        TextPathType.LINE -> index in 0..2
        TextPathType.CIRCLE, TextPathType.RECT -> index == 0 || index == 1
        TextPathType.CURVE -> index in 0..4
    }

    /**
     * Handles of the same path that stay put while handle [index] moves and that it can line up
     * with (none for the circle's radius and the rectangle's size: only the size changes there;
     * a node's neighbours for edited points).
     */
    private fun fixedHandles(path: TextPathSpec, index: Int): List<Int> {
        val nodes = TextPathNodes.of(path) ?: return flatFixedHandles(path.type, index)
        val n = nodes.points.size
        val closed = TextPathNodes.isClosed(nodes)
        return listOf(index - 1, index + 1)
            .map { if (closed) (it + n) % n else it }
            .filter { it in 0 until n && it != index }
            .distinct()
    }

    private fun flatFixedHandles(type: TextPathType, index: Int): List<Int> = when (type) {
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
        if (nodeEditor.active) {
            // v1.9 (R12): the nearest node within 24 dp is selected and follows the finger;
            // elsewhere nothing is selected (the text stays put).
            mode = Mode.NODE
            nodeEditor.down(downDoc, t.screenToDocLength(t.dp(NODE_PICK_DP)))
            controller.invalidateOverlay()
            return
        }
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
                if (handleSnaps(cur.path, best)) beginSnap(fixedHandles(cur.path, best).mapNotNull { hs.getOrNull(it) })
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
                if (handleSnaps(start.path, handleIndex)) at = snapPathHandle(start.path, handleIndex, raw)
                // v1.6 increments: a point handle moves in Length steps on the axes no guide holds.
                if (increments.step(IncrementKind.LENGTH) != null && stepsAsPoint(start.path, handleIndex)) {
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
            // v1.9 (R12): the picked node follows the finger, the text re-flows (one step on lift).
            Mode.NODE -> nodeEditor.move(q)
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

    /** Text path handles that are points (moved by Length steps); not sizes, angles or the text position. Every node of edited points. */
    private fun stepsAsPoint(path: TextPathSpec, index: Int): Boolean {
        TextPathNodes.of(path)?.let { return index in it.points.indices }
        return flatStepsAsPoint(path.type, index)
    }

    private fun flatStepsAsPoint(type: TextPathType, index: Int): Boolean = when (type) {
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
            // v1.9 (R12): a node that moved is one in-tool step; a tap only selected (or not).
            m == Mode.NODE -> nodeEditor.up()
            moved -> {}
            // The editor is open (minimized to its pill while the canvas is used): the text can
            // be dragged and pinched, but a tap neither places it nor restarts the editor.
            editorOpen -> {}
            m == Mode.CREATE -> tapAt(p.x, p.y)
            // While the editor is open a tap neither applies the text nor reopens the editor.
            editorOpen -> {}
            // v1.9 (R14): a second tap on a text opens the text of its layer under it, if any.
            m == Mode.MOVE && downInside -> if (!cycleBelow(Vec2(p.x, p.y))) openEditor()
            // Tap away from the text: place it (like ✓, back to the vector layer it came from),
            // then edit the text tapped or start a new one there.
            m == Mode.MOVE -> if (commitItem()) {
                returnToVectorLayer()
                tapAt(p.x, p.y)
            }
        }
        controller.invalidateOverlay()
    }

    /**
     * A tap without pending text: edit the text under the finger (v1.9: the topmost text there
     * of the layer [textAt] finds), else add text.
     */
    private fun tapAt(x: Float, y: Float) {
        val hit = textAt(Vec2(x, y))
        if (hit == null || !editLayer(hit.layer, index = hit.index)) startTextAt(x, y)
    }

    /**
     * v1.9 (R14): a tap on the open text at [p] when other texts of its layer are there too: the
     * open text is finished (✓, a step only when it changed) and the next text down at [p] opens,
     * round to the topmost after the bottom one. False when no other text of the layer is at [p]
     * (or the open text is new): the tap opens the editor as in v1.8.
     */
    private fun cycleBelow(p: Vec2): Boolean {
        val layer = editingLayer ?: return false
        if (joining || item == null) return false
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        val k = openIndex
        val hits = ArrayList<Int>()
        for (i in loadedBelow.indices) if (loadedBelow[i].text.isNotBlank() && belowPrepared[i].contains(loadedBelow[i], p, tol)) hits += i
        for (i in loadedAbove.indices) if (loadedAbove[i].text.isNotBlank() && abovePrepared[i].contains(loadedAbove[i], p, tol)) hits += k + 1 + i
        if (hits.isEmpty()) return false
        val next = hits.filter { it < k }.maxOrNull() ?: hits.max()
        // An emptied open text is removed by ✓: the texts above it move down one place.
        val removed = item?.text?.isBlank() == true
        // Refused (outside the canvas, locked meanwhile; a message says so): the text stays open.
        if (!commitItem()) return true
        if (doc.indexOf(layer) < 0) return true
        editLayer(layer, index = if (removed && next > k) next - 1 else next)
        return true
    }

    /**
     * A finger resting on the pending text or on one of its handles is about to drag it: the
     * gesture stays a move/rotate/resize instead of turning into the long-press color pick (which
     * still happens away from the text).
     */
    override fun onLongPress(p: ToolPoint): Boolean {
        if (item == null || gestureStart == null) return false
        return mode == Mode.ROTATE || mode == Mode.SCALE || mode == Mode.BOX || mode == Mode.DEPTH || mode == Mode.PATH_HANDLE ||
            (mode == Mode.MOVE && downInside) || (mode == Mode.NODE && nodeEditor.dragging)
    }

    override fun onCancel() {
        // v1.9 (R12): a node being dragged goes back (no step).
        if (mode == Mode.NODE) nodeEditor.cancel()
        else if (mode != Mode.NONE && mode != Mode.CREATE) gestureStart?.let { item = it }
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
        // v1.9 (R12): while the path's points are edited two fingers zoom the view (a pinch
        // would move every node outside the node steps).
        if (nodeEditor.active) return false
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
        if (nodeEditor.active) drawNodes(canvas, t) else for (hp in TextOnPath.handles(cur.path)) drawHandle(canvas, t, t.docToScreen(hp), filled = true)
    }

    /**
     * v1.9 (R12): the guide's nodes while they are edited: corners as squares, smooth nodes as
     * dots, the selected one ringed [NODE_RING_DP] wide (where a finger picks it).
     */
    private fun drawNodes(canvas: Canvas, t: ViewTransform) {
        val n = nodeEditor.nodes ?: return
        val r = t.dp(HANDLE_RADIUS_DP)
        val o = r + t.dp(1.5f)
        for ((i, p) in n.points.withIndex()) {
            val s = t.docToScreen(Vec2(p.x, p.y))
            if (p.sharp) {
                fillPaint.color = 0x99000000.toInt()
                canvas.drawRect(s.x - o, s.y - o, s.x + o, s.y + o, fillPaint)
                fillPaint.color = ACCENT
                canvas.drawRect(s.x - r, s.y - r, s.x + r, s.y + r, fillPaint)
            } else {
                drawHandle(canvas, t, s, filled = true)
            }
            if (i == nodeEditor.selected) {
                val ring = t.dp(NODE_RING_DP / 2f)
                haloPaint.strokeWidth = t.dp(4f)
                canvas.drawCircle(s.x, s.y, ring, haloPaint)
                linePaint.strokeWidth = t.dp(2f)
                canvas.drawCircle(s.x, s.y, ring, linePaint)
            }
        }
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

        /**
         * v1.9 (R14, I24): shown when a text that shares its layer is asked to wrap. Stands in for
         * a TextLabels19 entry that LabelsV19 lacks (a LeadRequest).
         */
        const val WRAP_ALONE_ONLY = "Only a text alone in its layer can wrap around a picture"

        /**
         * v1.9 (R14): Duplicate in a layer of [TextCodec.MAX_ITEMS] texts: the first sentence of
         * [TextLabels19.LAYER_FULL] (whose second sentence is about a new text, which starts a new layer).
         */
        val LAYER_FULL_ONLY: String = TextLabels19.LAYER_FULL.substringBefore(". ") + "."

        /** Shown when the "Letters" chip is tapped with no text to scale. */
        const val LETTERS_NEED_TEXT = "Tap the canvas to add a text, then scale its letters"

        /** Shown when the fixed width of a text that wraps around a picture is turned off. */
        const val WRAP_NEEDS_WIDTH = "Text that wraps around a picture needs a fixed width: turn Wrap off first"

        /** What the pill's position is (v1.7; the v1.6 strip's word). */
        const val CENTER_LABEL = "Center"

        /** The pill's trash cell deletes a "text" ([PillLabels.deleteObject]). */
        private const val DELETE_KIND = "text"

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

        /** v1.9 (R12): a finger picks the nearest node within this; the selected node's ring (diameter). */
        private const val NODE_PICK_DP = 24f
        private const val NODE_RING_DP = 40f
        private const val BOX_HANDLE_HALF_DP = 9f
        private const val BOX_HANDLE_WIDTH_DP = 6f
        /** Tapping this close to a text layer's box still counts as tapping its text. */
        private const val HIT_TOLERANCE_DP = 8f
        /** Extra document px redrawn around the in-layer preview. */
        private const val PREVIEW_SLACK_PX = 2
        private const val LAYER_CACHE_SIZE = 16

        /** v1.9 (R14): the most laid-out texts [layerTextCache] keeps, over all its layers (4 full layers). */
        private const val LAYER_CACHE_TEXTS = 4 * TextCodec.MAX_ITEMS
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
