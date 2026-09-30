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
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.LayerRenderOverride
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.transform.ContentBounds
import com.brushwork.paint.tools.transform.TransformHandles
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
 * never cut away part of the old text); new text is clipped to the selection.
 *
 * The editor dialog, the "Numbers" sheet and the empty-text question are hosted by
 * `TextToolOptions` and driven by the Compose state here.
 */
class TextTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.TEXT

    private var itemState by mutableStateOf<TextItem?>(null)

    /**
     * The text object being placed or edited, null when there is none. While a text layer is
     * edited, every change also redraws that layer's in-place preview (see [LayerPreview]).
     */
    var item: TextItem?
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
    var editingNew by mutableStateOf(false)
        private set

    /** The numeric position/size sheet is showing. */
    var numbersOpen by mutableStateOf(false)

    /** Units used by the size and position fields. */
    var sizeUnit by mutableStateOf(LengthUnit.PT)
    var positionUnit by mutableStateOf(LengthUnit.PX)

    /** The text layer being edited again (null while placing a new text). */
    var editingLayer by mutableStateOf<Layer?>(null)
        private set

    /** Asks whether to delete the edited text layer because its text was emptied. */
    var emptyTextPrompt by mutableStateOf(false)
        private set

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
    val maxSizePx: Float get() = 2f * max(doc.width, doc.height)

    /** Largest box width / height (a few canvases; more is never useful). */
    val maxBoxPx: Float get() = 4f * max(doc.width, doc.height)

    /** Default font size for new text: 5 % of the canvas height. */
    val defaultSizePx: Float get() = (doc.height * 0.05f).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)

    /** The spec a new text starts with. */
    fun specForNewText(): TextSpec = (nextSpec ?: TextSpec(sizePx = defaultSizePx)).copy(color = controller.color)

    /**
     * The look of a placed or edited text, kept for the next new text: everything but the fixed
     * box width / height, which belonged to that text's words (a new text starts fitting its own).
     */
    private fun styleToRemember(spec: TextSpec): TextSpec = spec.copy(box = spec.box.copy(width = 0f, height = 0f))

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
        // Finish another pending text first (placing it adds a layer).
        if (item != null && !commitItem()) discard()
        if (doc.indexOf(layer) < 0) return false
        // Selecting pauses this tool (onDeactivate/onActivate); nothing is pending at this point.
        controller.selectLayer(layer)
        editingLayer = layer
        loadedItem = loaded
        loadedInk = inkOf(layer, loaded)
        editingNew = false
        editorBackup = null
        emptyTextPrompt = false
        // The old pixels are hidden and the pending text is drawn in their place (see LayerPreview).
        layerPreview = LayerPreview(layer).also { controller.renderOverride = it }
        loadedInk?.let { controller.tiles.invalidate(it) }
        item = loaded
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
        emptyTextPrompt = false
        controller.invalidateOverlay()
    }

    /** Topmost visible, unlocked text layer whose text is at [p] (the active layer first), or null. */
    fun textLayerAt(p: Vec2): Layer? {
        val t = controller.viewTransform
        val tol = t.screenToDocLength(t.dp(HIT_TOLERANCE_DP))
        val active = doc.activeLayer
        fun hits(l: Layer): Boolean {
            if (!l.isTextLayer || !l.visible || l.locked) return false
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
        layerTexts[layer.id]?.let { (d, it, p) -> if (d == data) return it to p }
        val decoded = TextCodec.decode(data) ?: return null
        val prep = TextRenderer.prepare(decoded)
        if (layerTexts.size >= LAYER_CACHE_SIZE) layerTexts.clear()
        layerTexts[layer.id] = Triple(data, decoded, prep)
        return decoded to prep
    }

    /** Reopens the editor for the current text. */
    fun openEditor() {
        val cur = item ?: return
        editorBackup = cur
        editingNew = false
        editorOpen = true
    }

    /**
     * Closes the editor keeping the changes; an empty new text is removed. An edited text layer
     * whose text was emptied is never deleted silently: [emptyTextPrompt] asks first.
     */
    fun confirmEditor() {
        val cur = item
        if (cur != null && cur.text.isBlank() && editingLayer != null) {
            editorOpen = false
            editorBackup = null
            emptyTextPrompt = true
            return
        }
        editorOpen = false
        editorBackup = null
        if (cur == null || cur.text.isBlank()) item = null else nextSpec = styleToRemember(cur.spec)
        controller.invalidateOverlay()
    }

    /** Closes the editor reverting its changes (a new text is removed). */
    fun cancelEditor() {
        editorOpen = false
        item = if (editingNew) null else editorBackup ?: item
        editorBackup = null
        controller.invalidateOverlay()
    }

    /** Answer to [emptyTextPrompt]: keep the layer, with its old text back (other changes stay). */
    fun keepOldText() {
        emptyTextPrompt = false
        val old = loadedItem?.text ?: return
        update { it.copy(text = old) }
    }

    /** Answer to [emptyTextPrompt]: delete the edited text layer (undoable). */
    fun deleteEditedLayer() {
        emptyTextPrompt = false
        val layer = editingLayer ?: return
        discard()
        controller.deleteLayer(layer)
    }

    fun setText(text: String) = update { it.copy(text = text) }

    fun updateSpec(transform: (TextSpec) -> TextSpec) = update { it.copy(spec = transform(it.spec)) }

    /**
     * Where the text object is on the canvas (document px): the center of straight text, or the
     * center of the text as drawn along its path (its path handles and settings move it, not
     * [TextItem.cx]/[TextItem.cy]); ([TextItem.cx], [TextItem.cy]) while nothing is measured.
     * Shown as the position, and the pivot for turning.
     */
    fun anchorOf(t: TextItem): Vec2 {
        if (!t.path.isActive) return Vec2(t.cx, t.cy)
        val b = preparedFor(t).docBounds(t)
        return if (b.isEmpty || !b.centerX().isFinite() || !b.centerY().isFinite()) Vec2(t.cx, t.cy) else Vec2(b.centerX(), b.centerY())
    }

    /** Moves the text object (and its path) so its center ([anchorOf]) is at ([x], [y]). */
    fun setCenter(x: Float, y: Float) = update { val a = anchorOf(it); translated(it, x - a.x, y - a.y) }

    fun setCenterX(x: Float) = update { translated(it, x - anchorOf(it).x, 0f) }

    fun setCenterY(y: Float) = update { translated(it, 0f, y - anchorOf(it).y) }

    fun nudge(dx: Float, dy: Float) = update { translated(it, dx, dy) }

    /** Turns the text object (and its path, around the text's center [anchorOf]) to [deg]. */
    fun setRotation(deg: Float) = update {
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

    fun setSizePx(px: Float) {
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
    fun setFixedBox(on: Boolean) = updateSpec { s ->
        val box = s.box
        if (!on) return@updateSpec s.copy(box = if (s.vertical) box.copy(height = 0f) else box.copy(width = 0f))
        val natural = TextRenderer.layout(item?.text ?: "", s.copy(box = box.copy(width = 0f, height = 0f)))
        if (s.vertical) s.copy(box = box.copy(height = natural.contentHeight.coerceIn(s.sizePx, maxBoxPx)))
        else s.copy(box = box.copy(width = natural.contentWidth.coerceIn(s.sizePx, maxBoxPx)))
    }

    /** Sets the fixed box length (width of horizontal text, height of vertical text) in px. */
    fun setBoxLength(px: Float) = updateSpec { s ->
        if (!px.isFinite()) return@updateSpec s
        val v = px.coerceIn(s.sizePx.coerceAtMost(maxBoxPx), maxBoxPx)
        if (s.vertical) s.copy(box = s.box.copy(height = v)) else s.copy(box = s.box.copy(width = v))
    }

    fun updateBox(transform: (TextBoxSpec) -> TextBoxSpec) = updateSpec { it.copy(box = transform(it.box)) }

    fun applyBoxPreset(preset: TextBoxPreset) = updateSpec { it.copy(box = preset.applyTo(it.box, it.sizePx)) }

    /**
     * Sets the text path. Switching to another shape places a sensible default of that shape
     * around the text ([TextOnPath.defaultFor]); straight keeps the shape for later.
     */
    fun setPath(spec: TextPathSpec) = update { cur ->
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

    override fun commit() { commitItem() }

    override fun discard() {
        item = null
        editorOpen = false
        numbersOpen = false
        editingNew = false
        editorBackup = null
        mode = Mode.NONE
        gestureStart = null
        pinchStart = null
        endLayerEdit()
        controller.invalidateOverlay()
    }

    override fun onDeactivate() {
        if (hasPendingWork && !commitItem()) discard()
        // Never leave the old pixels of a text layer hidden.
        if (item == null && layerPreview != null) endLayerEdit()
    }

    override fun onDispose() {
        layerTexts.clear()
        prepared = null
    }

    /**
     * Where the pixels of the text layer [layer] (drawn from [item]) really are: fonts may differ
     * from the device that drew them, so the pixels are scanned. Only the area around the
     * computed bounds is read, unless the ink reaches its edge (then the whole layer is).
     */
    private fun inkOf(layer: Layer, item: TextItem): Rect? {
        val guess = rectOf(item)
        return try {
            if (guess != null) {
                val margin = max(64, max(guess.width(), guess.height()) / 4)
                val region = Rect(guess).apply { inset(-margin, -margin) }
                if (region.intersect(0, 0, layer.width, layer.height)) {
                    val ink = ContentBounds.of(layer.bitmap, region = region)
                    val atEdge = ink != null && (
                        (ink.left <= region.left && region.left > 0) || (ink.top <= region.top && region.top > 0) ||
                            (ink.right >= region.right && region.right < layer.width) || (ink.bottom >= region.bottom && region.bottom < layer.height)
                        )
                    if (ink != null && !atEdge) return ink
                }
            }
            ContentBounds.of(layer.bitmap) ?: guess
        } catch (e: OutOfMemoryError) {
            guess
        }
    }

    /** Pixel rect (rounded out) of everything [t] paints, or null when it paints nothing. */
    private fun rectOf(t: TextItem): Rect? {
        val prep = TextRenderer.prepare(t)
        if (prep.isEmpty) return null
        val r = Rect()
        prep.docBounds(t).roundOut(r)
        return r.takeUnless { it.isEmpty }
    }

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
        if (cur.text.isBlank()) { discard(); return true }
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
        // An emptied text never deletes the layer silently (the editor asks); keep the old text.
        if (cur.text.isBlank() || cur == loaded) { discard(); return true }
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
        loaded?.let { rectOf(it) }?.let { dirty.union(it) }
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
        editorBackup = null
        endLayerEdit()
        nextSpec = styleToRemember(cur.spec)
        controller.invalidateOverlay()
        return true
    }

    private fun isAutoName(name: String, auto: String): Boolean =
        name == auto || (name.startsWith("$auto ") && name.substring(auto.length + 1).all { it.isDigit() })

    // ------------------------------------------------------------------ gestures

    private enum class Mode { NONE, CREATE, MOVE, ROTATE, SCALE, BOX, PATH_HANDLE }

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

    override fun onDown(p: ToolPoint) {
        val t = controller.viewTransform
        downDoc = Vec2(p.x, p.y)
        moved = false
        val cur = item
        if (cur == null || editorOpen || emptyTextPrompt) {
            mode = if (cur == null) Mode.CREATE else Mode.NONE
            gestureStart = null
            return
        }
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
            } else {
                mode = Mode.MOVE
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
        mode = when {
            toRotate <= hit && toRotate < toCenter && toRotate <= toScale && toRotate <= toBox -> Mode.ROTATE
            toScale <= hit && toScale < toCenter && toScale <= toBox -> Mode.SCALE
            toBox <= hit && toBox < toCenter -> Mode.BOX
            else -> Mode.MOVE
        }
        if (mode == Mode.BOX) {
            val l = cur.docToLocal(downDoc, block.width, block.height)
            boxGrab = if (cur.spec.vertical) l.y - block.height else l.x - block.width
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
                val dx = q.x - downDoc.x; val dy = q.y - downDoc.y
                val next = translated(start, dx, dy)
                // Text on a path: offset the measured bounds instead of measuring every frame.
                gesturePrepared?.let { if (it.onPath && it.matches(start)) prepared = it.translatedTo(next, dx, dy) }
                item = next
            }
            Mode.ROTATE -> {
                val delta = Math.toDegrees(((q - c).angle - (downDoc - c).angle).toDouble()).toFloat()
                item = start.copy(rotationDeg = TextItem.snapDegrees(start.rotationDeg + delta))
            }
            Mode.SCALE -> {
                val d0 = (downDoc - c).length
                if (d0 < 1e-3f) return
                val size = (start.spec.sizePx * (q - c).length / d0).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)
                val k = size / start.spec.sizePx
                item = start.copy(spec = start.spec.scaled(k).copy(sizePx = size))
            }
            Mode.BOX -> item = boxResized(start, q)
            Mode.PATH_HANDLE -> item = start.copy(path = TextOnPath.moveHandle(start.path, handleIndex, q + handleGrab))
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
        val outer = if (vertical) l.y - boxGrab else l.x - boxGrab
        val content = (outer - 2f * inset).coerceIn(min, maxBoxPx)
        val ns = if (vertical) spec.copy(box = spec.box.copy(height = content)) else spec.copy(box = spec.box.copy(width = content))
        val moved = start.copy(spec = ns)
        val nb = preparedFor(moved).block ?: return moved
        val rot = Math.toRadians(start.rotationDeg.toDouble()).toFloat()
        // Anchor: top-left (horizontal, or columns left to right), else top-right.
        val anchorRight = vertical && !spec.columnsLeftToRight
        val anchor = start.localToDoc(if (anchorRight) b0.width else 0f, 0f, b0.width, b0.height)
        val half = Vec2(if (anchorRight) -nb.width / 2f else nb.width / 2f, nb.height / 2f).rotated(rot)
        return moved.copy(cx = anchor.x + half.x, cy = anchor.y + half.y)
    }

    override fun onUp(p: ToolPoint) {
        val m = mode
        mode = Mode.NONE
        gestureStart = null
        gesturePrepared = null
        when {
            moved -> {}
            m == Mode.CREATE -> tapAt(p.x, p.y)
            m == Mode.MOVE && downInside -> openEditor()
            // Tap away from the text: place it, then edit the text tapped or start a new one there.
            m == Mode.MOVE -> if (commitItem()) tapAt(p.x, p.y)
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
        if (item == null || editorOpen || gestureStart == null) return false
        return mode == Mode.ROTATE || mode == Mode.SCALE || mode == Mode.BOX || mode == Mode.PATH_HANDLE ||
            (mode == Mode.MOVE && downInside)
    }

    override fun onCancel() {
        if (mode != Mode.NONE && mode != Mode.CREATE) gestureStart?.let { item = it }
        mode = Mode.NONE
        gestureStart = null
        gesturePrepared = null
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
        val cur = item ?: return false
        if (editorOpen || emptyTextPrompt) return false
        val t = controller.viewTransform
        val prep = preparedFor(cur)
        val tol = t.screenToDocLength(t.dp(BOX_PAD_DP + 8f))
        if (listOf(focus, a, b).none { prep.contains(cur, it, tol) }) return false
        mode = Mode.NONE
        gestureStart = null
        pinchStart = cur
        pinchFocus = focus
        return true
    }

    override fun onTwoFingerGesture(translation: Vec2, scale: Float, rotationDeg: Float) {
        val start = pinchStart ?: return
        // Placed or removed meanwhile (a chrome button): nothing to pinch any more.
        if (item == null || editorOpen) { pinchStart = null; return }
        val delta = TransformHandles.pinchRotation(start.rotationDeg, rotationDeg).takeIf { it.isFinite() } ?: 0f
        val next = start.pinched(pinchFocus, translation, scale, delta, maxSizePx)
        item = if (start.path.isActive) {
            // The same motion as the text: scaled (like its size) and turned about the start
            // focus, then moved with the fingers. Two calls, so the order is unambiguous.
            val k = if (start.spec.sizePx > 0f) next.spec.sizePx / start.spec.sizePx else 1f
            val tr = Vec2(if (translation.x.isFinite()) translation.x else 0f, if (translation.y.isFinite()) translation.y else 0f)
            val turned = TextOnPath.transformed(start.path, Vec2.ZERO, k, delta, pinchFocus)
            next.copy(path = if (tr == Vec2.ZERO) turned else TextOnPath.transformed(turned, tr, 1f, 0f, pinchFocus + tr))
        } else next
        controller.invalidateOverlay()
    }

    override fun onTwoFingerEnd(cancelled: Boolean) {
        val start = pinchStart ?: return
        pinchStart = null
        if (cancelled && item != null && !editorOpen) item = start
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ overlay

    private class Handles(val corners: List<Vec2>, val rotateBase: Vec2, val rotate: Vec2, val scale: Vec2, val box: Vec2, val boxDir: Vec2)

    private fun handles(t0: TextItem, block: TextBlock, t: ViewTransform): Handles {
        val pad = t.screenToDocLength(t.dp(BOX_PAD_DP))
        val sc = t0.corners(block.width, block.height, pad).map { t.docToScreen(it) }
        val topMid = (sc[0] + sc[1]) / 2f
        val center = (sc[0] + sc[2]) / 2f
        var dir = (topMid - center).normalized()
        if (dir.lengthSq < 0.5f) dir = Vec2(0f, -1f)
        // Box handle: middle of the right edge (horizontal text) or of the bottom edge (vertical).
        val box = if (t0.spec.vertical) (sc[2] + sc[3]) / 2f else (sc[1] + sc[2]) / 2f
        var boxDir = (box - center).normalized()
        if (boxDir.lengthSq < 0.5f) boxDir = Vec2(1f, 0f)
        return Handles(sc, topMid, topMid + dir * t.dp(ROTATE_STEM_DP), sc[2], box, boxDir)
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

        haloPaint.strokeWidth = t.dp(3f)
        linePaint.strokeWidth = t.dp(1.5f)
        val block = prep.block
        if (block == null) {
            drawPathOverlay(canvas, t, cur, prep)
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
        drawHandle(canvas, t, h.rotate, filled = false)
        drawHandle(canvas, t, h.scale, filled = true)
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
    }
}
