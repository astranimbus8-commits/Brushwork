package com.brushwork.paint.ui.tools.strip

/**
 * v1.9 (design 2.3.5, C8): a v1.9 strip item: [key], its width in dp, its rank and the [tools]
 * that show it (`ToolId.name`, lowercase).
 * - [widthDp] 0: a row of a menu or cog sheet (rank 99, not in the strip).
 * - [rank] 0: row 1, never leaves it (`StripItem` overflowRank 0).
 * - [rank] 1-49: row 1, moves to the More row's front when row 1 is full (overflowRank = rank).
 * - [rank] 50-98: always in the More row (`StripRow.MORE`), after v1.8's More items, in rank order.
 *
 * A new file rather than an edit of `StripModel.kt` (fact V5). The areas build their strip items
 * from these keys, so the budgets `StripKeysV19Test` checks hold for what they ship.
 */
data class StripKeyV19(val key: String, val widthDp: Int, val rank: Int, val tools: Set<String>)

/** The v1.9 strip keys (2.3.5), frozen for the areas. */
object StripKeysV19 {
    /** R18: the bevel button of Points mode (Shape: in Points mode only). */
    val BEVEL = StripKeyV19("points.bevel", 40, 60, setOf("polyline", "shape"))

    /** R11: v1.8's three eraser mode buttons, one item (equal to `VectorEraserStrip.KEY` and its 120 dp on purpose). */
    val ERASE_MODES = StripKeyV19("eraser.modes", 120, 0, setOf("eraser"))

    /** R11: EraseLabels19.TURN_INTO_PIXELS, the last item of the eraser's More row. */
    val ERASE_PIXELS = StripKeyV19("eraser.pixels", 132, 90, setOf("eraser"))

    /** R11: the row-1 mode cell (EraseLabels19.MODE_CELL) while erasing on a vector or shape layer. */
    val ERASE_MODE_CELL = StripKeyV19("erase.modeCell", 56, 0, setOf("curve", "polyline", "path", "shape"))

    /** R11: EraseLabels19.INSTEAD_OF_PAINT, a row of the stroke-kind menu. */
    val ERASE_KIND = StripKeyV19("stroke.erase", 0, 99, setOf("curve", "polyline", "path", "shape"))

    /** R14: CurveLabels19.OWN_LAYER, a row of the curve tools' settings sheet. */
    val OWN_LAYER = StripKeyV19("curve.ownLayer", 0, 99, setOf("curve", "polyline", "path"))

    /** R14: PlaceLabels19.NEW_LAYER_EACH_TIME in the Text More row, while no text is open. */
    val NEW_LAYER_EACH_TIME_TEXT = StripKeyV19("text.newLayerEachTime", 132, 80, setOf("text"))

    /** R14: PlaceLabels19.NEW_LAYER_EACH_TIME, a row of the Shape cog. */
    val NEW_LAYER_EACH_TIME_SHAPE = StripKeyV19("shape.newLayerEachTime", 0, 99, setOf("shape"))

    /** R14: LayerLabels19.DUPLICATE (shape: not in Points mode; text: an open text). */
    val DUPLICATE = StripKeyV19("object.duplicate", 40, 50, setOf("shape", "text"))

    /** R14: bring the object forward (text: two or more texts). */
    val FORWARD = StripKeyV19("object.forward", 40, 51, setOf("shape", "text"))

    /** R14: send the object backward (text: two or more texts). */
    val BACKWARD = StripKeyV19("object.backward", 40, 52, setOf("shape", "text"))

    /** Every key, in the order of 2.3.5. */
    val ALL: List<StripKeyV19> = listOf(
        BEVEL, ERASE_MODES, ERASE_PIXELS, ERASE_MODE_CELL, ERASE_KIND, OWN_LAYER,
        NEW_LAYER_EACH_TIME_TEXT, NEW_LAYER_EACH_TIME_SHAPE, DUPLICATE, FORWARD, BACKWARD,
    )
}
