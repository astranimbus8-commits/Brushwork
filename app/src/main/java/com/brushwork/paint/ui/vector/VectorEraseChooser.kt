package com.brushwork.paint.ui.vector

import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.tools.strip.StripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.vector.draw.VectorEraseMode

/**
 * v1.9 (R11, design 2.4.12; owner A5): the erase-mode controls of the Eraser's strip and of the
 * four tools while they erase a vector or shape layer. F1b: every item is null (v1.8's strip).
 */
@Suppress("UNUSED_PARAMETER")
object VectorEraseChooser {
    /** The key of the Eraser's v1.8 mode buttons ([VectorEraserStrip.KEY]). */
    const val KEY = VectorEraserStrip.KEY

    /** The Eraser's row-1 chooser: v1.8's three mode buttons (120 dp), or null when [layer] is not a vector or shape layer. F1b: null. */
    fun item(ctx: StripContext, layer: Layer?, mode: VectorEraseMode, onMode: (VectorEraseMode) -> Unit): StripItem? = null

    /**
     * The Eraser's More-row action [com.brushwork.paint.ui.common.EraseLabels19.TURN_INTO_PIXELS]
     * (StripKeysV19.ERASE_PIXELS, last, warning tint). F1b: null.
     */
    fun pixelsItem(ctx: StripContext, layer: Layer?, onPixels: () -> Unit): StripItem? = null

    /**
     * The four tools' row-1 mode cell while erasing a vector or shape layer (StripKeysV19.ERASE_MODE_CELL,
     * 56 dp, overflow rank 0): a dropdown cell described by [com.brushwork.paint.ui.common.EraseLabels19.MODE_CELL],
     * whose menu lists the three modes with their descriptions, a divider and
     * [com.brushwork.paint.ui.common.EraseLabels19.TURN_INTO_PIXELS]. F1b: null.
     */
    fun modeCell(ctx: StripContext, layer: Layer?, mode: VectorEraseMode, onMode: (VectorEraseMode) -> Unit, onPixels: () -> Unit): StripItem? = null
}
