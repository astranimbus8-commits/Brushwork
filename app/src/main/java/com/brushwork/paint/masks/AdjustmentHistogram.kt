package com.brushwork.paint.masks

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.filters.FilterSessionMath
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer

/** The luminance histogram an adjustment layer's effect works on (the Adjust sheet, §4.3a). */
object AdjustmentHistogram {
    /** Long side of the composite the histogram is measured on. */
    private const val SIZE = 256

    /**
     * Luminance histogram (256 bins) of the composite below [layer], weighted by its mask (where
     * the effect shows) — null when there is nothing below. Main thread (it reads the layers);
     * about 10–30 ms on a phone.
     */
    fun below(c: EditorController, layer: Layer): IntArray? {
        val layers = c.doc.layers
        val idx = c.doc.indexOf(layer)
        if (idx <= 0) return null
        val tmp = Document("histogram", "histogram", c.doc.width, c.doc.height)
        // v1.7 (§3.8, rule P, site 24): what the effect works on. Inside an isolated folder that
        // is the folder's layers below it, else everything below. The tree is copied, folders
        // included; a layer whose folder is not copied (a pass-through folder the adjustment is
        // in, which draws as if it were not there) moves to the top level, unclipped when it was
        // the bottom of its folder.
        val start = FolderComposite.effectStart(layers, idx)
        val views = HashMap<Long, Long>()
        for (i in start until idx) if (layers[i].isFolder) views[layers[i].id] = viewId(i)
        for (i in start until idx) {
            val l = layers[i]
            tmp.layers += Layer(viewId(i), l.name, l.bitmap).also { v ->
                v.copyPropsFrom(l.props())
                v.mask = l.mask
                v.maskSpec = l.maskSpec
                v.adjustment = l.adjustment
                v.folder = l.folder
                val parent = views[l.parentId]
                if (parent != null) {
                    v.parentId = parent
                } else if (l.parentId != Layer.ROOT_ID) {
                    v.clipping = l.clipping && FolderComposite.isClipped(layers, i)
                }
            }
        }
        if (tmp.layers.isEmpty()) return null
        var thumb: Bitmap? = null
        var mask: Bitmap? = null
        return try {
            val t = Compositor(tmp) { null }.renderThumbnail(SIZE)
            thumb = t
            val buf = BitmapUtils.toPixelBuffer(t)
            val m = if (layer.maskEnabled) layer.mask else null
            val sel = m?.let { src ->
                val scaled = Bitmap.createScaledBitmap(src, t.width, t.height, true)
                mask = scaled.takeIf { it !== src }
                BitmapUtils.maskToBytes(scaled)
            }
            FilterSessionMath.luminanceHistogram(buf, sel)
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            thumb?.recycle()
            mask?.recycle()
        }
    }

    /** The id of the view of the layer at flat index [i] (negative: never a document id). */
    private fun viewId(i: Int): Long = -1L - i
}
