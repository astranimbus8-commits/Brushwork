package com.brushwork.paint.masks

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
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
        val idx = c.doc.indexOf(layer)
        if (idx <= 0) return null
        val tmp = Document("histogram", "histogram", c.doc.width, c.doc.height)
        for (i in 0 until idx) {
            val l = c.doc.layers[i]
            // v1.7 (rule P, site 24): folders are skipped and their layers measured flat (a
            // child of a hidden folder hidden); inside an isolated folder this approximates
            // what the effect works on.
            if (l.isFolder) continue
            tmp.layers += Layer(-1L - i, l.name, l.bitmap).also { v ->
                v.copyPropsFrom(l.props())
                v.mask = l.mask
                v.maskSpec = l.maskSpec
                v.adjustment = l.adjustment
                if (l.parentId != Layer.ROOT_ID) v.visible = c.doc.effectiveVisible(l)
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
}
