package com.brushwork.paint.tools.text

import kotlinx.serialization.Serializable

/** Shape the text follows. */
@Serializable
enum class TextPathType(val label: String) {
    /** Normal text (no path). */
    NONE("Straight"),
    /** Along a straight line from (x1, y1) to (x2, y2). */
    LINE("Line"),
    /** Around a circle at (cx, cy) with [TextPathSpec.radius]. */
    CIRCLE("Circle"),
    /** Around a rectangle (a square when width == height) centered at (cx, cy). */
    RECT("Square / rectangle"),
    /** Along a cubic Bezier curve p0 = (x1, y1), c1 = (cx1, cy1), c2 = (cx2, cy2), p3 = (x2, y2). */
    CURVE("Curve"),
}

/** How letters follow the path. */
@Serializable
enum class TextPathMode(val label: String) {
    /** Letter outlines are deformed to follow the path (they bend with curves). */
    BEND("Bend letters"),
    /** Letters keep their shape and are rotated to the path direction. */
    ROTATE("Rotate letters"),
}

/** For closed paths (circle, rectangle): which side of the path the letters stand on. */
@Serializable
enum class TextPathSide(val label: String) {
    /** Letters stand on the outside (like text around the top of a badge). */
    OUTSIDE("Outside"),
    /** Letters hang on the inside of the shape. */
    INSIDE("Inside"),
}

@Serializable
enum class TextPathAlign(val label: String) { START("Start"), CENTER("Center"), END("End") }

/**
 * Text-on-a-path settings of a text object. All positions/lengths are DOCUMENT pixels.
 * Only the fields of the current [type] matter; the others keep their values so switching
 * types back and forth doesn't lose the user's shape.
 */
@Serializable
data class TextPathSpec(
    val type: TextPathType = TextPathType.NONE,
    val mode: TextPathMode = TextPathMode.BEND,
    // LINE: (x1, y1) -> (x2, y2). CURVE: p0 = (x1, y1), c1 = (cx1, cy1), c2 = (cx2, cy2), p3 = (x2, y2).
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val cx1: Float = 0f,
    val cy1: Float = 0f,
    val cx2: Float = 0f,
    val cy2: Float = 0f,
    // CIRCLE / RECT center.
    val cx: Float = 0f,
    val cy: Float = 0f,
    /** CIRCLE radius. */
    val radius: Float = 200f,
    /** CIRCLE: where the text is anchored (degrees, 0 = right, -90 = top, clockwise positive on screen). */
    val startAngleDeg: Float = -90f,
    /** RECT size and corner rounding; RECT rotation (degrees). */
    val width: Float = 400f,
    val height: Float = 400f,
    val cornerRadius: Float = 0f,
    val rotationDeg: Float = 0f,
    /** RECT: keep width == height while editing. */
    val keepSquare: Boolean = false,
    /** Closed paths: direction the text runs. */
    val clockwise: Boolean = true,
    val side: TextPathSide = TextPathSide.OUTSIDE,
    /** Where along the path the text sits, and an extra shift along the path (px). */
    val align: TextPathAlign = TextPathAlign.CENTER,
    val offset: Float = 0f,
    /** Moves the text off the path along its normal (px, positive = away from the baseline side). */
    val baselineShift: Float = 0f,
) {
    val isActive: Boolean get() = type != TextPathType.NONE
}
