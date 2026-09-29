package com.brushwork.paint.tools.text

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ViewTransform
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.max

/**
 * Text tool: tap the canvas to place a text object, type in the editor dialog, then move it
 * (drag), rotate it (top handle) or resize it (corner handle) before committing it with ✓ into a
 * new layer. Supports horizontal and vertical (manga) text, outline, spacing and alignment.
 *
 * The editor dialog and the "Numbers" sheet are hosted by `TextToolOptions` and driven by the
 * Compose state here ([item], [editorOpen], [numbersOpen]).
 */
class TextTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.TEXT

    /** The text object being placed or edited, null when there is none. */
    var item by mutableStateOf<TextItem?>(null)
        private set

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

    override val hasPendingWork: Boolean get() = item != null

    private val doc get() = controller.doc
    private var editorBackup: TextItem? = null

    /** Largest font size allowed (twice the canvas' longer side). */
    val maxSizePx: Float get() = 2f * max(doc.width, doc.height)

    /** Default font size for new text: 5 % of the canvas height. */
    val defaultSizePx: Float get() = (doc.height * 0.05f).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)

    /** The spec a new text starts with. */
    fun specForNewText(): TextSpec = (nextSpec ?: TextSpec(sizePx = defaultSizePx)).copy(color = controller.color)

    // ------------------------------------------------------------------ layout cache

    private var cachedBlock: TextBlock? = null
    private var cachedText: String? = null
    private var cachedSpec: TextSpec? = null

    /** Laid-out block of [t] (cached while text and spec are unchanged). */
    fun blockFor(t: TextItem): TextBlock {
        val b = cachedBlock
        if (b != null && cachedText == t.text && cachedSpec == t.spec) return b
        val nb = TextRenderer.layout(t.text, t.spec)
        cachedBlock = nb; cachedText = t.text; cachedSpec = t.spec
        return nb
    }

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
        item = TextItem("", specForNewText(), x.coerceIn(0f, doc.width.toFloat()), y.coerceIn(0f, doc.height.toFloat()))
        editorBackup = null
        editingNew = true
        editorOpen = true
        controller.invalidateOverlay()
    }

    /** Reopens the editor for the current text. */
    fun openEditor() {
        val cur = item ?: return
        editorBackup = cur
        editingNew = false
        editorOpen = true
    }

    /** Closes the editor keeping the changes; an empty text is removed. */
    fun confirmEditor() {
        editorOpen = false
        editorBackup = null
        val cur = item
        if (cur == null || cur.text.isBlank()) item = null else nextSpec = cur.spec
        controller.invalidateOverlay()
    }

    /** Closes the editor reverting its changes (a new text is removed). */
    fun cancelEditor() {
        editorOpen = false
        item = if (editingNew) null else editorBackup ?: item
        editorBackup = null
        controller.invalidateOverlay()
    }

    fun setText(text: String) = update { it.copy(text = text) }

    fun updateSpec(transform: (TextSpec) -> TextSpec) = update { it.copy(spec = transform(it.spec)) }

    fun setCenter(x: Float, y: Float) = update { it.copy(cx = x, cy = y) }

    fun setCenterX(x: Float) = update { it.copy(cx = x) }

    fun setCenterY(y: Float) = update { it.copy(cy = y) }

    fun nudge(dx: Float, dy: Float) = update { it.copy(cx = it.cx + dx, cy = it.cy + dy) }

    fun setRotation(deg: Float) = update { it.copy(rotationDeg = TextItem.normalizeDegrees(deg)) }

    fun setSizePx(px: Float) = updateSpec { it.copy(sizePx = px.coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)) }

    /** Toggles vertical text on the current text, or for the next one when there is none. */
    fun toggleVertical() {
        val cur = item
        if (cur != null) updateSpec { it.copy(vertical = !it.vertical) }
        else nextSpec = specForNewText().let { it.copy(vertical = !it.vertical) }
    }

    /** Whether the current (or next) text is vertical. */
    val isVertical: Boolean get() = (item?.spec ?: nextSpec)?.vertical ?: false

    private fun update(transform: (TextItem) -> TextItem) {
        val cur = item ?: return
        item = transform(cur)
        controller.invalidateOverlay()
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
        controller.invalidateOverlay()
    }

    override fun onDeactivate() {
        if (hasPendingWork && !commitItem()) discard()
    }

    /**
     * Bakes the text into a new layer above the active one (clipped to the selection) with undo.
     * Returns false, keeping the text editable, if nothing could be placed.
     */
    fun commitItem(): Boolean {
        val cur = item ?: return true
        if (cur.text.isBlank()) { discard(); return true }
        val block = blockFor(cur)
        val rect = Rect()
        TextRenderer.docBounds(cur, block).roundOut(rect)
        if (!rect.intersect(0, 0, doc.width, doc.height)) {
            controller.toast("The text is outside the canvas")
            return false
        }
        val sel = controller.selection
        if (sel != null && !rect.intersect(sel.bounds)) {
            controller.toast("The text is outside the selection")
            return false
        }
        // Clear the pending state first: addLayerWithContent deactivates the current tool (this one).
        item = null
        editorOpen = false
        numbersOpen = false
        editorBackup = null
        // addLayerWithContent applies the color mode and handles a failed layer allocation itself;
        // the catch covers its grayscale/1-bit conversion, which allocates a canvas-sized buffer.
        val layer = try {
            controller.addLayerWithContent(cur.layerName(), "Add text") { c ->
                c.clipRect(rect)
                TextRenderer.drawItem(c, cur, block, sel)
            }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            null
        }
        if (layer == null) {
            item = cur
            return false
        }
        nextSpec = cur.spec
        controller.invalidateOverlay()
        return true
    }

    // ------------------------------------------------------------------ gestures

    private enum class Mode { NONE, CREATE, MOVE, ROTATE, SCALE }

    private var mode = Mode.NONE
    private var downDoc = Vec2.ZERO
    private var downInside = false
    private var moved = false
    private var gestureStart: TextItem? = null

    override fun onDown(p: ToolPoint) {
        val t = controller.viewTransform
        downDoc = Vec2(p.x, p.y)
        moved = false
        val cur = item
        if (cur == null || editorOpen) {
            mode = if (cur == null) Mode.CREATE else Mode.NONE
            gestureStart = null
            return
        }
        gestureStart = cur
        val block = blockFor(cur)
        val h = handles(cur, block, t)
        val s = t.docToScreen(downDoc)
        val hit = t.dp(HANDLE_HIT_DP)
        // On small text the handle hit areas overlap the box: a handle wins only when the finger
        // is closer to it than to the box center.
        val toCenter = s.distanceTo((h.corners[0] + h.corners[2]) / 2f)
        val toRotate = s.distanceTo(h.rotate)
        val toScale = s.distanceTo(h.scale)
        mode = when {
            toRotate <= hit && toRotate < toCenter && toRotate <= toScale -> Mode.ROTATE
            toScale <= hit && toScale < toCenter -> Mode.SCALE
            else -> Mode.MOVE
        }
        downInside = isInside(cur, block, downDoc, t)
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
            Mode.MOVE -> item = start.copy(cx = start.cx + q.x - downDoc.x, cy = start.cy + q.y - downDoc.y)
            Mode.ROTATE -> {
                val delta = Math.toDegrees(((q - c).angle - (downDoc - c).angle).toDouble()).toFloat()
                item = start.copy(rotationDeg = TextItem.snapDegrees(start.rotationDeg + delta))
            }
            Mode.SCALE -> {
                val d0 = (downDoc - c).length
                if (d0 < 1e-3f) return
                val size = (start.spec.sizePx * (q - c).length / d0).coerceIn(TextSpec.MIN_SIZE_PX, maxSizePx)
                val k = size / start.spec.sizePx
                item = start.copy(spec = start.spec.copy(sizePx = size, strokeWidthPx = start.spec.strokeWidthPx * k))
            }
            Mode.NONE, Mode.CREATE -> return
        }
        controller.invalidateOverlay()
    }

    override fun onUp(p: ToolPoint) {
        val m = mode
        mode = Mode.NONE
        gestureStart = null
        when {
            moved -> {}
            m == Mode.CREATE -> startTextAt(p.x, p.y)
            m == Mode.MOVE && downInside -> openEditor()
            // Tap away from the text: place it and start a new one there.
            m == Mode.MOVE -> if (commitItem()) startTextAt(p.x, p.y)
        }
        controller.invalidateOverlay()
    }

    override fun onCancel() {
        if (mode == Mode.MOVE || mode == Mode.ROTATE || mode == Mode.SCALE) gestureStart?.let { item = it }
        mode = Mode.NONE
        gestureStart = null
        controller.invalidateOverlay()
    }

    // ------------------------------------------------------------------ overlay

    private class Handles(val corners: List<Vec2>, val rotateBase: Vec2, val rotate: Vec2, val scale: Vec2)

    private fun handles(t0: TextItem, block: TextBlock, t: ViewTransform): Handles {
        val pad = t.screenToDocLength(t.dp(BOX_PAD_DP))
        val sc = t0.corners(block.width, block.height, pad).map { t.docToScreen(it) }
        val topMid = (sc[0] + sc[1]) / 2f
        val center = (sc[0] + sc[2]) / 2f
        var dir = (topMid - center).normalized()
        if (dir.lengthSq < 0.5f) dir = Vec2(0f, -1f)
        return Handles(sc, topMid, topMid + dir * t.dp(ROTATE_STEM_DP), sc[2])
    }

    private fun isInside(t0: TextItem, block: TextBlock, p: Vec2, t: ViewTransform): Boolean {
        val pad = t.screenToDocLength(t.dp(BOX_PAD_DP + 8f))
        val l = t0.docToLocal(p, block.width, block.height)
        return l.x >= -pad && l.y >= -pad && l.x <= block.width + pad && l.y <= block.height + pad
    }

    private val haloPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = 0x99000000.toInt() }
    private val linePaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.STROKE; color = ACCENT }
    private val fillPaint = Paint(Paint.ANTI_ALIAS_FLAG).apply { style = Paint.Style.FILL }
    private val boxPath = Path()

    override fun drawOverlay(canvas: Canvas, t: ViewTransform) {
        val cur = item ?: return
        val block = blockFor(cur)
        // Live preview at document scale, clipped to the canvas and the selection.
        val save = canvas.save()
        canvas.concat(t.matrix)
        canvas.clipRect(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        TextRenderer.drawItem(canvas, cur, block, controller.selection, doc.colorMode)
        canvas.restoreToCount(save)

        val h = handles(cur, block, t)
        boxPath.rewind()
        boxPath.moveTo(h.corners[0].x, h.corners[0].y)
        for (i in 1..3) boxPath.lineTo(h.corners[i].x, h.corners[i].y)
        boxPath.close()
        haloPaint.strokeWidth = t.dp(3f)
        linePaint.strokeWidth = t.dp(1.5f)
        canvas.drawPath(boxPath, haloPaint)
        canvas.drawPath(boxPath, linePaint)
        canvas.drawLine(h.rotateBase.x, h.rotateBase.y, h.rotate.x, h.rotate.y, haloPaint)
        canvas.drawLine(h.rotateBase.x, h.rotateBase.y, h.rotate.x, h.rotate.y, linePaint)
        drawHandle(canvas, t, h.rotate, filled = false)
        drawHandle(canvas, t, h.scale, filled = true)
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

    companion object {
        private const val ACCENT = 0xFF4DA3FF.toInt()
        private const val HANDLE_RADIUS_DP = 9f
        private const val HANDLE_HIT_DP = 24f
        private const val BOX_PAD_DP = 6f
        private const val ROTATE_STEM_DP = 30f
        private const val TOUCH_SLOP_DP = 8f
    }
}
