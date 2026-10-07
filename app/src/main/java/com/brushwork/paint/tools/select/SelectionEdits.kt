package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Shader
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeAction
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.segmentation.SegmentationService
import com.brushwork.paint.segmentation.SmartTarget
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.util.concurrent.atomic.AtomicBoolean

/**
 * Operations of the selection menu. Entry points taking an [EditorController] must be called on
 * the main thread; the `…ed` functions are the blocking cores (any thread) used by them.
 */
object SelectionEdits {

    // ------------------------------------------------------------------ blocking cores

    /** [sel] grown by [radius] px (null if empty or cancelled). */
    fun grown(sel: Selection, radius: Int, cancelled: () -> Boolean = { false }): Selection? =
        morph(sel, MaskMath.growMargin(radius), cancelled) { m, w, h -> MaskMath.grow(m, w, h, radius, cancelled) }

    /** [sel] shrunk by [radius] px; canvas edges are not treated as selection edges. */
    fun shrunk(sel: Selection, radius: Int, cancelled: () -> Boolean = { false }): Selection? =
        // A 1 px ring around the bounds provides the unselected pixels the distance is measured to.
        morph(sel, 1, cancelled) { m, w, h -> MaskMath.shrink(m, w, h, radius, cancelled) }

    /** [sel] with its edge softened by a gaussian of [radius] px. */
    fun feathered(sel: Selection, radius: Float, cancelled: () -> Boolean = { false }): Selection? =
        morph(sel, MaskMath.featherMargin(radius), cancelled) { m, w, h -> MaskMath.feather(m, w, h, radius, cancelled) }

    private inline fun morph(sel: Selection, margin: Int, noinline cancelled: () -> Boolean, op: (ByteArray, Int, Int) -> ByteArray): Selection? {
        if (sel.isEmpty) return null
        val win = SelectionMasks.window(sel.bounds, margin, sel.width, sel.height)
        val bytes = SelectionMasks.crop(sel.mask, win)
        val out = op(bytes, win.width(), win.height())
        if (cancelled()) return null
        return SelectionMasks.fromWindow(out, win.left, win.top, win.width(), win.height(), sel.width, sel.height)
    }

    /** A new ALPHA_8 bitmap holding [bitmap]'s alpha channel (call where [bitmap] may be read). */
    fun alphaMask(bitmap: Bitmap): Bitmap {
        val alpha = bitmap.extractAlpha()
        if (alpha.config == Bitmap.Config.ALPHA_8 && alpha.width == bitmap.width && alpha.height == bitmap.height) return alpha
        alpha.recycle()
        val m = Bitmap.createBitmap(bitmap.width, bitmap.height, Bitmap.Config.ALPHA_8)
        Canvas(m).drawBitmap(bitmap, 0f, 0f, Paint())
        return m
    }

    // ------------------------------------------------------------------ selection edits (undoable)

    /** Grows (positive) or shrinks (negative) the selection by [px]. */
    fun growOrShrink(controller: EditorController, px: Int) {
        val sel = controller.selection ?: return
        val r = kotlin.math.abs(px).coerceIn(1, MaskMath.MAX_RADIUS)
        val grow = px > 0
        replaceAsync(controller, sel, if (grow) "Grow selection" else "Shrink selection", if (grow) "Growing selection…" else "Shrinking selection…") { cancelled ->
            if (grow) grown(sel, r, cancelled) else shrunk(sel, r, cancelled)
        }
    }

    /** Softens the selection edge with a gaussian of [px]. */
    fun feather(controller: EditorController, px: Float) {
        val sel = controller.selection ?: return
        val r = px.coerceIn(0.5f, MaskMath.MAX_RADIUS.toFloat())
        replaceAsync(controller, sel, "Feather selection", "Feathering selection…") { cancelled -> feathered(sel, r, cancelled) }
    }

    /** Runs [op] in the background with a stoppable busy overlay and publishes its result. */
    private fun replaceAsync(controller: EditorController, sel: Selection, label: String, busy: String, op: (cancelled: () -> Boolean) -> Selection?) {
        val stopped = AtomicBoolean(false)
        controller.runBusy(busy, onCancel = { stopped.set(true) }) {
            val result = withContext(Dispatchers.Default) { op { stopped.get() } }
            // Only apply if nobody changed the selection meanwhile (e.g. undo).
            if (result != null && !stopped.get() && controller.selection === sel) controller.setSelection(result, label = label)
        }
    }

    /** Selects the active layer's opaque pixels (alpha = selection strength), combined by [mode]. */
    fun selectLayerOpacity(controller: EditorController, mode: SelectionMode) {
        val alpha = try {
            // v1.7 (rule C): a folder selects the opacity of its layers' composite.
            withPixelsOf(controller, controller.activeLayer) { alphaMask(it) }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory to select the layer")
            return
        }
        val name = controller.activeLayer.name
        SelectionJobs.applyAsync(controller, "Select layer opacity", mode, "Selecting…", emptyMessage = "\"$name\" has no painted pixels to select") {
            Selection.wrap(alpha)
        }
    }

    /**
     * Smart select: segments the flattened canvas on-device and turns the confidences into a
     * selection combined by [mode].
     */
    fun smartSelect(controller: EditorController, target: SmartTarget, mode: SelectionMode) {
        val name = shortName(target)
        controller.runBusy("Selecting $name…") {
            val doc = controller.doc
            val w = doc.width; val h = doc.height
            val base = controller.selection
            val flat = controller.compositor.renderFlattened(background = 0xFFFFFFFF.toInt())
            val (fresh, combined) = withContext(Dispatchers.Default) {
                val buffer = try { BitmapUtils.toPixelBuffer(flat) } finally { flat.recycle() }
                val conf = SegmentationService.get(controller.appContext).segment(buffer, target)
                if (conf == null || conf.size != w * h) return@withContext null to null
                val s = Selection.fromFloats(conf, w, h)
                s to (if (s.isEmpty) null else SelectionMasks.combine(base, s, mode))
            }
            when {
                fresh == null -> controller.toast("Smart select ($name) isn't available on this device")
                fresh.isEmpty -> controller.toast("No $name found in this picture")
                doc.width != w || doc.height != h -> {}
                else -> {
                    val current = controller.selection
                    val result = if (current === base) combined else SelectionMasks.combine(current, fresh, mode)
                    controller.setSelection(result, label = "Select $name")
                }
            }
        }
    }

    /** Lower-case name used in messages ("Selecting sky…"). */
    fun shortName(target: SmartTarget): String = when (target) {
        SmartTarget.SUBJECT -> "subject"
        SmartTarget.BACKGROUND -> "background"
        SmartTarget.SKY -> "sky"
        SmartTarget.NATURE -> "nature"
        SmartTarget.BUILDINGS -> "buildings"
        SmartTarget.PEOPLE -> "people"
        SmartTarget.WATER -> "water"
    }

    // ------------------------------------------------------------------ pixel edits (undoable)

    /**
     * Fills the selected area of the active layer with [color] (alpha lock respected; on a mask,
     * the color's luminance). Returns false if nothing was done.
     */
    fun fillSelection(controller: EditorController, color: Int): Boolean {
        val sel = controller.selection ?: return false
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return false
        val target = controller.editTargetOf(layer)
        // A vector layer gets a filled object, as with the layer menu's Fill (v1.5 §4.9): painting
        // pixels would turn it into a raster layer.
        if (target == EditTarget.CONTENT && layer.isVectorLayer) {
            controller.fillLayer(layer, color or 0xFF000000.toInt())
            return true
        }
        val bmp = (if (target == EditTarget.MASK) layer.mask else layer.bitmap) ?: return false
        val rec = controller.beginEdit(layer, target)
        rec.touch(sel.bounds)
        val paint = Paint().apply {
            this.color = if (target == EditTarget.MASK) ColorUtils.gray(ColorUtils.luminance(color)) else color or 0xFF000000.toInt()
            if (target == EditTarget.CONTENT && layer.alphaLocked) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        Canvas(bmp).apply { clipRect(sel.bounds); drawBitmap(sel.mask, 0f, 0f, paint) }
        return controller.commitEdit(rec, "Fill selection")
    }

    /**
     * Erases the selected area of the active layer (on a mask: hides it by painting black).
     * Refused on an alpha-locked layer, whose transparency must not change.
     */
    fun clearSelection(controller: EditorController): Boolean {
        val sel = controller.selection ?: return false
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return false
        val target = controller.editTargetOf(layer)
        // A vector layer loses the objects the selection touches, as with the selection bar's
        // Clear (v1.5 §4.9; objects ignore alpha lock): erasing pixels would rasterize it.
        if (target == EditTarget.CONTENT && layer.isVectorLayer) {
            controller.clearLayer(layer, label = "Clear selection")
            return true
        }
        if (target == EditTarget.CONTENT && layer.alphaLocked) {
            controller.toast("Can't clear: transparency is locked on \"${layer.name}\"")
            return false
        }
        val bmp = (if (target == EditTarget.MASK) layer.mask else layer.bitmap) ?: return false
        val rec = controller.beginEdit(layer, target)
        rec.touch(sel.bounds)
        val paint = Paint().apply {
            if (target == EditTarget.MASK) color = 0xFF000000.toInt() else xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT)
        }
        Canvas(bmp).apply { clipRect(sel.bounds); drawBitmap(sel.mask, 0f, 0f, paint) }
        return controller.commitEdit(rec, "Clear selection")
    }

    /** New layer (above the active one) with the selected pixels of the active layer. */
    fun copyToNewLayer(controller: EditorController): Layer? {
        val sel = controller.selection ?: return null
        val src = controller.activeLayer
        controller.currentTool.onDeactivate()
        val layer = try {
            // v1.7 (rule C): from a folder, the selected part of its layers' composite.
            withPixelsOf(controller, src) { px -> controller.addLayerWithContent("${src.name} copy", "Copy to new layer") { c -> drawSelected(c, px, sel) } }
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            null
        }
        controller.currentTool.onActivate()
        return layer
    }

    /**
     * Moves the selected pixels of the active layer to a new layer above it, as ONE undo step
     * (pixel edit + added layer).
     */
    fun cutToNewLayer(controller: EditorController): Layer? {
        val sel = controller.selection ?: return null
        val src = controller.activeLayer
        if (!controller.checkEditable(src)) return null
        if (!controller.canAddLayer) { controller.toast("Layer limit reached (${controller.maxLayers}) for this canvas size"); return null }
        controller.currentTool.onDeactivate()
        val layer = cut(controller, src, sel)
        controller.currentTool.onActivate()
        return layer
    }

    /**
     * Core of [cutToNewLayer] (no tool callbacks). Refused on an alpha-locked layer. In grayscale
     * / monochrome documents both halves are constrained, so soft selection edges split cleanly.
     */
    internal fun cut(controller: EditorController, src: Layer, sel: Selection): Layer? {
        val doc = controller.doc
        if (src.alphaLocked) {
            controller.toast("Can't cut: transparency is locked on \"${src.name}\". Use Copy to new layer instead.")
            return null
        }
        val bmp = try {
            BitmapUtils.createLayerBitmap(doc.width, doc.height)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for another layer")
            return null
        }
        drawSelected(Canvas(bmp), src.bitmap, sel)
        ColorModeOps.constrain(bmp, sel.bounds, doc.colorMode)
        val rec = controller.beginEdit(src, EditTarget.CONTENT)
        rec.touch(sel.bounds)
        Canvas(src.bitmap).apply {
            clipRect(sel.bounds)
            drawBitmap(sel.mask, 0f, 0f, Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.DST_OUT) })
        }
        ColorModeOps.constrain(src.bitmap, sel.bounds, doc.colorMode)
        val layer = Layer(doc.newLayerId(), uniqueLayerName(controller, "${src.name} cut"), bmp)
        // v1.7 (rule S): directly above the source at its level, through LayerStructure (without
        // folders: the v1.6 AddLayerAction).
        val add = controller.structure.placed(layer, controller.structure.above(src), "Cut to new layer") ?: run {
            rec.abort()
            bmp.recycle()
            return null
        }
        // One step with the new layer; like any pixel edit it turns an editable (text, shape,
        // vector) source into a raster layer in that step (I1).
        if (!controller.commitEdit(rec, "Cut to new layer", listOf(add))) controller.pushUndo(add)
        return layer
    }

    /**
     * [block] on [layer]'s pixels. v1.7 (rule C): a folder has none of its own, so [block] gets
     * the composite of its layers (`FolderComposite.renderBlock`, in the document's color mode),
     * freed afterwards.
     */
    private inline fun <T> withPixelsOf(controller: EditorController, layer: Layer, block: (Bitmap) -> T): T {
        if (!layer.isFolder) return block(layer.bitmap)
        val doc = controller.doc
        val composite = FolderComposite.renderBlock(doc, layer)
        try {
            if (doc.colorMode != ColorMode.RGB) ColorModeOps.constrain(composite, doc.bounds, doc.colorMode)
            return block(composite)
        } finally {
            composite.recycle()
        }
    }

    /**
     * Draws [src] × selection coverage into [c]. Skia draws an ALPHA_8 bitmap as COVERAGE of the
     * paint, so "src then mask with DST_IN" would be a no-op; painting the source (as a shader)
     * through the mask gives the masked pixels instead.
     */
    private fun drawSelected(c: Canvas, src: Bitmap, sel: Selection) {
        val paint = Paint().apply { shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
        c.save()
        c.clipRect(sel.bounds)
        c.drawBitmap(sel.mask, 0f, 0f, paint)
        c.restore()
    }

    private fun uniqueLayerName(controller: EditorController, base: String): String {
        val names = controller.doc.layers.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }
}
