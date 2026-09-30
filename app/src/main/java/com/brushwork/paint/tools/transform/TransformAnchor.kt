package com.brushwork.paint.tools.transform

import com.brushwork.paint.core.Vec2

/**
 * Reference point of a transform (like Illustrator's / Photoshop's 3 x 3 reference point): the
 * point of the box's axis-aligned bounds that stays in place when a size, scale or rotation is
 * typed, and whose position the X / Y fields show. [u] / [v] are its fractions across the
 * bounds (0 = left / top, 1 = right / bottom). Pure Kotlin.
 */
enum class TransformAnchor(val u: Float, val v: Float, val label: String) {
    TOP_LEFT(0f, 0f, "Top left"),
    TOP(0.5f, 0f, "Top"),
    TOP_RIGHT(1f, 0f, "Top right"),
    LEFT(0f, 0.5f, "Left"),
    CENTER(0.5f, 0.5f, "Center"),
    RIGHT(1f, 0.5f, "Right"),
    BOTTOM_LEFT(0f, 1f, "Bottom left"),
    BOTTOM(0.5f, 1f, "Bottom"),
    BOTTOM_RIGHT(1f, 1f, "Bottom right");

    /** The point of [box] this anchor stands for (document px). */
    fun pointOn(box: DocBox): Vec2 = Vec2(box.left + u * box.width, box.top + v * box.height)

    companion object {
        /** Row-major order of the 3 x 3 picker (top row first). */
        val GRID: List<TransformAnchor> = entries

        /** By [name], or null for an unknown / missing value (saved preferences). */
        fun byName(name: String?): TransformAnchor? = entries.firstOrNull { it.name == name }
    }
}
