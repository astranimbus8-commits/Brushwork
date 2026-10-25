package com.brushwork.paint.engine.tiles

import com.brushwork.paint.model.Layer

/**
 * v1.10 (A8 step 2, design 2.0 "Layer states" and 2.7): parks a FLAT layer. Main takes a pin on
 * the layer and reads its flat content and mask; the `tile-swapper` cuts them into pieces
 * (`TileOps.cutPieces`: a transparent tile is nothing, a uniform opaque one a [SolidTiles] color);
 * the posted task, on main, turns the pieces into stores and puts them in with `Layer.rehouse`
 * (the same pixels: `pixelGeneration` unchanged, J5) only when nothing moved meanwhile: the layer
 * still holds the stores that were read, its [Layer.contentVersion] and [Layer.pixelGeneration]
 * are the same, no other pin was taken and the caller still wants it ([park]'s `mayCommit`, which
 * says no once the layer was activated or a gesture began). Otherwise the pieces are recycled and
 * the layer stays FLAT.
 *
 * The flat bitmap is not recycled by a park (F1a gap 4): v1.9 code (undo steps, tools) may still
 * hold it, and the garbage collector frees it once nothing does. The pin is what forbids the
 * engine to change the layer's pixels while they are cut; v1.9 code that replaces and recycles a
 * bitmap does so under a gesture or a busy job, which `mayCommit` and the policy rule out.
 *
 * Main thread, but the cut.
 */
internal object ParkCutter {
    /** What the worker cut (or failed to cut) from a layer's content and mask. */
    private class Cut(val content: TileOps.CutPieces?, val mask: TileOps.CutPieces?, val error: Throwable?) {
        fun recycle() {
            content?.recycle()
            mask?.recycle()
        }
    }

    /** Main only: layers with a cut in flight (one at a time per layer). */
    private val cutting = HashSet<Long>()

    /** Main only: true while [layer] is being cut. */
    fun isCutting(layer: Layer): Boolean = layer.id in cutting

    /** True when [p] is a flat store with pixels a park can cut. */
    fun parkable(p: PixelStore?): Boolean = p is FlatPixels && !p.bitmap.isRecycled && p.bitmap.width > 0 && p.bitmap.height > 0

    /**
     * Main only: starts parking [layer] (its flat content, its flat mask, or both). False when
     * there is nothing to park (a folder, a pinned layer, nothing flat, a cut in flight); else
     * [done] runs on main with whether the layer was parked. [mayCommit] is asked on main just
     * before the new stores go in.
     */
    fun park(layer: Layer, mayCommit: () -> Boolean, done: (Boolean) -> Unit = {}): Boolean {
        if (layer.isFolder || layer.pins > 0 || layer.id in cutting) return false
        val readContent = layer.pixels
        val readMask = layer.maskPixels
        val content = (readContent as? FlatPixels)?.takeIf { parkable(it) }
        val mask = (readMask as? FlatPixels)?.takeIf { parkable(it) }
        if (content == null && mask == null) return false
        val version = layer.contentVersion
        val generation = layer.pixelGeneration
        val pin = layer.pin()
        cutting += layer.id
        TileWorker.submit(TileWorker.Priority.PARK_CUT, { cut(content, mask) }) { r ->
            cutting -= layer.id
            pin.close()
            val cut = r.getOrNull()
            val ok = cut != null && cut.error == null && mayCommit() &&
                layer.pins == 0 && layer.contentVersion == version && layer.pixelGeneration == generation &&
                // Out of memory adopting the pieces: the layer stays FLAT (what was adopted is released).
                runCatching { commit(layer, readContent, readMask, cut) }.getOrDefault(false)
            if (!ok) cut?.recycle()
            done(ok)
        }
        return true
    }

    /** Worker: the pieces of [content] and [mask] (either may be null); never throws. */
    private fun cut(content: FlatPixels?, mask: FlatPixels?): Cut {
        var c: TileOps.CutPieces? = null
        return try {
            c = content?.let { TileOps.cutPieces(it.bitmap, detectBlank = true) }
            val m = mask?.let { TileOps.cutPieces(it.bitmap, detectBlank = true) }
            Cut(c, m, null)
        } catch (e: Throwable) {
            // What was cut goes back to main, which recycles it (rule 3).
            Cut(c, null, e)
        }
    }

    /**
     * Main: [cut] as [layer]'s stores when it still holds [readContent] and [readMask] (checked
     * under its monitor, atomic with the store setters). The pieces are spent either way: adopted
     * as tiles, which are released again when the layer moved on.
     */
    private fun commit(layer: Layer, readContent: PixelStore, readMask: PixelStore?, cut: Cut): Boolean {
        val c = cut.content?.let { TileOps.adoptPieces(it) }
        val m = try {
            cut.mask?.let { TileOps.adoptPieces(it) }
        } catch (e: Throwable) {
            c?.let { TileOps.releaseStore(it) }
            throw e
        }
        val same = synchronized(layer) {
            val same = layer.pixels === readContent && layer.maskPixels === readMask
            if (same) layer.rehouse(c ?: readContent, m ?: readMask)
            same
        }
        if (!same) {
            c?.let { TileOps.releaseStore(it) }
            m?.let { TileOps.releaseStore(it) }
        }
        return same
    }
}
