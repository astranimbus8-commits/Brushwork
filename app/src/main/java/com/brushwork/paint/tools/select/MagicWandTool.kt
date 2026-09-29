package com.brushwork.paint.tools.select

import android.graphics.Bitmap
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlin.math.floor
import kotlin.math.hypot

/**
 * Magic wand: tap to select the area of similar color (contiguous or everywhere), from the
 * active layer or the whole canvas, combined with the current selection by [mode].
 */
class MagicWandTool(controller: EditorController) : Tool(controller) {
    override val id = ToolId.MAGIC_WAND

    var settings: WandSettings by PersistedOption(controller.settings, "select.wand", WandSettings.serializer(), WandSettings())
    var mode by mutableStateOf(SelectionMode.REPLACE)

    /** True while a selection is being computed (taps are ignored meanwhile). */
    var busy by mutableStateOf(false)
        private set

    private var down: ToolPoint? = null

    override fun onDown(p: ToolPoint) { down = p }

    override fun onUp(p: ToolPoint) {
        val d = down ?: return
        down = null
        val t = controller.viewTransform
        // A drag is not a tap: ignore it rather than select at a surprising spot.
        if (hypot(p.x - d.x, p.y - d.y) > t.screenToDocLength(t.dp(24f))) return
        selectAt(d.x, d.y)
    }

    override fun onCancel() { down = null }

    /** Runs the wand at document position (x, y). */
    fun selectAt(x: Float, y: Float) {
        if (busy) return
        val doc = controller.doc
        val ix = floor(x).toInt(); val iy = floor(y).toInt()
        if (ix !in 0 until doc.width || iy !in 0 until doc.height) return
        val s = settings
        val snapshot: Bitmap = try {
            PixelSnapshot.take(controller, s.source, controller.activeLayer.bitmap)
        } catch (e: OutOfMemoryError) {
            controller.toast("Not enough memory for the magic wand")
            return
        }
        val params = RegionParams(tolerance = s.tolerance, contiguous = s.contiguous, antiAlias = s.antiAlias)
        busy = true
        SelectionJobs.applyAsync(controller, "Magic wand", mode, "Selecting…", onFinished = { busy = false }) { cancelled ->
            try {
                computeSelection(snapshot, ix, iy, params, cancelled)
            } finally {
                snapshot.recycle()
            }
        }
    }

    companion object {
        /** The wand's selection for a seed in [snapshot] (blocking; any thread). */
        internal fun computeSelection(snapshot: Bitmap, x: Int, y: Int, params: RegionParams, cancelled: () -> Boolean = { false }): Selection? {
            val w = snapshot.width; val h = snapshot.height
            val map = PixelSnapshot.similarityMap(snapshot, x, y, params.tolerance, null, cancelled) ?: return null
            val region = RegionFill.compute(map, w, h, x, y, params, null, cancelled) ?: return null
            if (cancelled()) return null
            return SelectionMasks.fromWindow(region.coverage, region.x0, region.y0, region.width, region.height, w, h)
        }
    }
}
