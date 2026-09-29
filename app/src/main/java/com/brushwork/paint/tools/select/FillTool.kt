package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Bucket fill: tap to fill the area of similar color with the drawing color. Supports tolerance,
 * layer / canvas reference, gap closing, expand and anti-aliasing; respects the selection, alpha
 * lock, mask editing (fills the mask with the color's luminance) and the document color mode.
 */
class FillTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.FILL

    var settings: FillSettings by PersistedOption(controller.settings, "select.fill", FillSettings.serializer(), FillSettings())

    /** True while a fill is being computed (taps are ignored meanwhile). */
    var busy by mutableStateOf(false)
        private set

    private var down: ToolPoint? = null

    override fun onDown(p: ToolPoint) { down = p }

    override fun onUp(p: ToolPoint) {
        val d = down ?: return
        down = null
        val t = controller.viewTransform
        if (hypot(p.x - d.x, p.y - d.y) > t.screenToDocLength(t.dp(24f))) return
        fillAt(d.x, d.y)
    }

    override fun onCancel() { down = null }

    /** Fills at document position (x, y). */
    fun fillAt(x: Float, y: Float) {
        if (busy) return
        val doc = controller.doc
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return
        val layer = controller.activeLayer
        if (!controller.checkEditable(layer)) return
        val sel = controller.selection
        if (sel != null && sel.alphaAt(ix, iy) == 0) { controller.toast("Tap inside the selection to fill it"); return }
        val target = controller.editTargetOf(layer)
        val targetBitmap = targetBitmap(layer, target) ?: return
        val s = settings
        var lockAlpha: Bitmap? = null
        val snapshot: Bitmap = try {
            // Under alpha lock only painted pixels can change: remember where they are.
            if (target == EditTarget.CONTENT && layer.alphaLocked) lockAlpha = SelectionEdits.alphaMask(layer.bitmap)
            PixelSnapshot.take(controller, s.source, targetBitmap)
        } catch (e: OutOfMemoryError) {
            lockAlpha?.recycle()
            controller.toast("Not enough memory to fill")
            return
        }
        val keep = lockAlpha
        val version = layer.contentVersion
        val color = controller.color
        val w = doc.width; val h = doc.height
        val params = RegionParams(s.tolerance, contiguous = true, gapClose = s.gapClose, expand = s.expand, antiAlias = s.antiAlias)
        busy = true
        val job = controller.scope.launch {
            val self = coroutineContext[Job]
            try {
                val region = withContext(Dispatchers.Default) {
                    try {
                        computeFill(snapshot, ix, iy, params, sel, keep) { self?.isActive == false }
                    } finally {
                        snapshot.recycle()
                        keep?.recycle()
                    }
                }
                if (region == null) {
                    if (keep != null) controller.toast("Nothing to fill: transparency is locked on \"${layer.name}\" and this area is empty")
                    return@launch
                }
                val stale = doc.indexOf(layer) < 0 || layer.contentVersion != version ||
                    targetBitmap(layer, target) !== targetBitmap || doc.width != w || doc.height != h
                if (stale) {
                    controller.toast("The layer changed while filling. Tap again.")
                    return@launch
                }
                if (!controller.checkEditable(layer)) return@launch
                applyRegion(layer, target, region, color)
            } catch (e: CancellationException) {
                throw e
            } catch (e: OutOfMemoryError) {
                controller.toast("Not enough memory to fill this area")
            } catch (e: Exception) {
                controller.toast("Fill failed: ${e.message ?: e.javaClass.simpleName}")
            } finally {
                busy = false
            }
        }
        SelectionJobs.showBusyIfSlow(controller, job, "Filling…")
    }

    private fun targetBitmap(layer: Layer, target: EditTarget): Bitmap? =
        if (target == EditTarget.MASK) layer.mask else layer.bitmap

    /**
     * Paints [region] with [color] into the layer (main thread) and records undo. Content: the
     * color over the pixels (SRC_ATOP under alpha lock). Mask: the color's luminance.
     */
    internal fun applyRegion(layer: Layer, target: EditTarget, region: Region, color: Int): Boolean {
        val bmp = targetBitmap(layer, target) ?: return false
        if (region.width <= 0 || region.height <= 0) return false
        val rect = Rect(region.x0, region.y0, region.x1, region.y1)
        val rec = controller.beginEdit(layer, target)
        rec.touch(rect)
        val coverage = BitmapUtils.bytesToAlpha8(region.coverage, region.width, region.height)
        val paint = Paint().apply {
            this.color = if (target == EditTarget.MASK) ColorUtils.gray(ColorUtils.luminance(color)) else color or 0xFF000000.toInt()
            if (target == EditTarget.CONTENT && layer.alphaLocked) xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC_ATOP)
        }
        Canvas(bmp).drawBitmap(coverage, rect.left.toFloat(), rect.top.toFloat(), paint)
        coverage.recycle()
        return controller.commitEdit(rec, "Fill")
    }

    companion object {
        /**
         * The fill region for a tap at ([x], [y]) of [snapshot] (blocking; any thread), clipped to
         * [sel] and, when [lockAlpha] (ALPHA_8 of the target layer) is given, to its painted
         * pixels. Cropped to the pixels that actually change; null if none (or [cancelled]).
         */
        internal fun computeFill(
            snapshot: Bitmap, x: Int, y: Int, params: RegionParams, sel: Selection?, lockAlpha: Bitmap?,
            cancelled: () -> Boolean = { false },
        ): Region? {
            val w = snapshot.width; val h = snapshot.height
            val clip = sel?.toBytes()
            val map = PixelSnapshot.similarityMap(snapshot, x, y, params.tolerance, clip, cancelled) ?: return null
            val region = RegionFill.compute(map, w, h, x, y, params, clip, cancelled) ?: return null
            if (cancelled()) return null
            val keep = lockAlpha?.let { SelectionMasks.crop(it, Rect(region.x0, region.y0, region.x1, region.y1)) }
            return RegionFill.trim(region, keep)
        }
    }
}
