package com.brushwork.paint.engine

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.RectF
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree

/**
 * v1.7 (item 8, I11): drawing a document that has folders. Area A owns this file and writes the
 * real bodies (pass-through, isolated folders, clipping per level, §3.8 c).
 *
 * The foundation's stub (F2) draws the pixel layers FLAT, in the v1.6 loop: folders are skipped,
 * and so are the descendants of hidden folders. That is exact for visible pass-through folders at
 * 100 % with no clipping across a folder's edge. [Compositor.drawDocument] calls it only when the
 * document has a folder; without one the v1.6 loop runs unchanged (I5).
 */
object FolderComposite {
    /**
     * Draws [doc] (which has folders) into [canvas] like [Compositor.drawDocument]: [bounds] =
     * the region being drawn, [target] the bitmap behind [canvas], [layerRange] (flat indices) only
     * those layers.
     */
    fun draw(
        compositor: Compositor,
        doc: Document,
        canvas: Canvas,
        bounds: RectF,
        override: LayerRenderOverride?,
        target: CompositeTarget?,
        layerRange: IntRange?,
    ) {
        val layers = doc.layers
        val range = layerRange ?: layers.indices
        val flat = range.filter { it in layers.indices && !layers[it].isFolder && LayerTree.shownByAncestors(layers, it) }.map { layers[it] }
        compositor.drawFlat(canvas, flat, 0, flat.size, bounds, override, target)
    }

    /**
     * The composite of [folder]'s children (all levels; the folder's own opacity, blend and eye
     * not applied) as a new document-sized bitmap the caller owns ("Merge folder", "Layer from
     * folder"). Stub: flat, as [draw].
     */
    fun renderBlock(doc: Document, folder: Layer): Bitmap {
        val layers = doc.layers
        val f = doc.indexOf(folder)
        val out = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        if (f < 0) return out
        val block = LayerTree.block(layers, f)
        val flat = (block.first until f).filter { k ->
            !layers[k].isFolder && LayerTree.ancestors(layers, k).takeWhile { it != f }.all { layers[it].visible }
        }.map { layers[it] }
        Compositor(doc) { null }.drawFlat(Canvas(out), flat, 0, flat.size, RectF(doc.bounds), null, CompositeTarget.identity(out))
        return out
    }
}
