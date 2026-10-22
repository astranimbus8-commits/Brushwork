package com.brushwork.paint.engine.tiles

import com.brushwork.paint.model.Layer

/**
 * v1.10 (F1, design 2.2 "Pins"): the store and its tiles are neither parked, swapped out nor
 * recycled while open. A non-resident pin ([Layer.pin]) never swaps in; a resident pin
 * ([Layer.pinResident]) also made every tile resident when taken. Taken and closed on main (an
 * off-main holder closes it with a post). It holds the tiles the layer had when it was taken;
 * [close] is idempotent.
 */
class PixelPin internal constructor(val layer: Layer, val resident: Boolean) : AutoCloseable {
    private val held: List<Tile> = TileOps.pin(layer, resident)
    private var open = true

    override fun close() {
        if (!open) return
        open = false
        TileOps.unpin(layer, held)
    }
}
