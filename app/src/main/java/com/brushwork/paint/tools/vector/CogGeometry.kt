package com.brushwork.paint.tools.vector

/**
 * v1.8 (item 4, design §4.7): the cog's own geometry, its teeth and its hole as closed contours in
 * document pixels.
 *
 * F2 stub: [contours] is the interim outline ([ShapeGeometry.outline] of [ShapeType.COG], the star
 * zigzag of [CogParams.teeth] teeth) without a hole, so a cog draws, hits and exports exactly as in
 * F1 until the cog area replaces it.
 */
object CogGeometry {
    /**
     * The cog's contours in [box] (document px) for [p] ([OutlineParams.cog] sets the teeth): the
     * toothed outline, then the hole (when [CogParams.hole] > 0) as a second contour. F2: the
     * interim zigzag only, no hole.
     */
    fun contours(box: ShapeBox, p: OutlineParams): VectorPath = ShapeGeometry.outline(ShapeType.COG, box, p)
}
