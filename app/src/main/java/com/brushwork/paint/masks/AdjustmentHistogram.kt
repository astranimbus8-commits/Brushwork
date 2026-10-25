package com.brushwork.paint.masks

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
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
        // v1.10 A9 (C-REG): the views take `snapshot()`s of the tiled stores (CoW, no copy; a flat,
        // blank or folder store is shared as v1.9 shared the bitmap), never `bitmap`, which would
        // materialize a parked layer; the temporary document composites tile-native.
        val start = FolderComposite.effectStart(layers, idx)
        val views = HashMap<Long, Long>()
        val snapshots = ArrayList<TiledPixels>()
        fun view(p: PixelStore): PixelStore = if (p is TiledPixels) p.snapshot().also { snapshots += it } else p
        for (i in start until idx) if (layers[i].isFolder) views[layers[i].id] = viewId(i)
        for (i in start until idx) {
            val l = layers[i]
            tmp.layers += Layer(viewId(i), l.name, view(l.pixels)).also { v ->
                v.copyPropsFrom(l.props())
                v.maskPixels = l.maskPixels?.let { view(it) }
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
        var thumb: Bitmap? = null
        var mask: Bitmap? = null
        return try {
            if (tmp.layers.isEmpty()) return null
            val t = Compositor(tmp) { null }.renderThumbnail(SIZE)
            thumb = t
            val buf = BitmapUtils.toPixelBuffer(t)
            val m = if (layer.maskEnabled) layer.maskPixels else null
            val sel = m?.let { src ->
                // A flat mask as v1.9 scaled it; a tiled one through the bands (never materialized).
                val scaled = if (src is FlatPixels && src.bitmap.width == t.width && src.bitmap.height == t.height) src.bitmap else BandAssembler.scaled(src, t.width, t.height)
                mask = scaled.takeIf { src !is FlatPixels || it !== src.bitmap }
                BitmapUtils.maskToBytes(scaled)
            }
            FilterSessionMath.luminanceHistogram(buf, sel)
        } catch (e: OutOfMemoryError) {
            null
        } finally {
            thumb?.recycle()
            mask?.recycle()
            snapshots.forEach { TileOps.releaseStore(it) }
        }
    }

    /** The id of the view of the layer at flat index [i] (negative: never a document id). */
    private fun viewId(i: Int): Long = -1L - i
}
