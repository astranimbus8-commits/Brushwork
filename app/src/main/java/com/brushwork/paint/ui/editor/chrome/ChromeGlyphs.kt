package com.brushwork.paint.ui.editor.chrome

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathFillType
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

/**
 * The ibisPaint silhouettes of the main screen (and of its layer window) that Material has no
 * glyph for (v1.6 §3.7.3, §3.7.5, §3.7.7), drawn on a 24-unit grid like Material icons (tinted by
 * the caller).
 */
internal object ChromeGlyphs {

    private fun glyph(name: String, block: ImageVector.Builder.() -> ImageVector.Builder): ImageVector =
        ImageVector.Builder(name = name, defaultWidth = 24.dp, defaultHeight = 24.dp, viewportWidth = 24f, viewportHeight = 24f)
            .block()
            .build()

    private fun ImageVector.Builder.stroke(width: Float = 1.8f, pathBuilder: PathBuilder.() -> Unit): ImageVector.Builder =
        path(fill = null, stroke = SolidColor(Color.Black), strokeLineWidth = width, strokeLineCap = StrokeCap.Round, strokeLineJoin = StrokeJoin.Round, pathBuilder = pathBuilder)

    private fun ImageVector.Builder.solid(pathBuilder: PathBuilder.() -> Unit): ImageVector.Builder =
        path(fill = SolidColor(Color.Black), pathFillType = PathFillType.EvenOdd, pathBuilder = pathBuilder)

    /** Slot 3, Vector: ibisPaint's toggle switch (a pill track with its knob on the right: a mode switch). */
    val ToggleSwitch: ImageVector by lazy {
        glyph("ToggleSwitch") {
            stroke {
                // Upper switch, knob right.
                moveTo(7f, 4.5f); lineTo(17f, 4.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 11.5f)
                lineTo(7f, 11.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 4.5f)
                close()
                // Lower switch, knob left.
                moveTo(7f, 12.5f); lineTo(17f, 12.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 17f, 19.5f)
                lineTo(7f, 19.5f)
                arcTo(3.5f, 3.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 7f, 12.5f)
                close()
            }
            solid {
                circle(17f, 8f, 2.2f)
                circle(7f, 16f, 2.2f)
            }
        }
    }

    /** Slot 4, Selection: a dashed rectangle (dashes drawn as short strokes). */
    val DashedRect: ImageVector by lazy {
        glyph("DashedRect") {
            stroke(1.8f) {
                // Corners.
                moveTo(4f, 7f); lineTo(4f, 4f); lineTo(7f, 4f)
                moveTo(17f, 4f); lineTo(20f, 4f); lineTo(20f, 7f)
                moveTo(20f, 17f); lineTo(20f, 20f); lineTo(17f, 20f)
                moveTo(7f, 20f); lineTo(4f, 20f); lineTo(4f, 17f)
                // Middle dashes.
                moveTo(10.5f, 4f); lineTo(13.5f, 4f)
                moveTo(10.5f, 20f); lineTo(13.5f, 20f)
                moveTo(4f, 10.5f); lineTo(4f, 13.5f)
                moveTo(20f, 10.5f); lineTo(20f, 13.5f)
            }
        }
    }

    /**
     * Slot 5, Stabilizer: ibisPaint's pointing hand (the index finger up, the other fingers
     * folded) with a tap mark at each side of the fingertip.
     */
    val TapHand: ImageVector by lazy {
        glyph("TapHand") {
            stroke(1.5f) {
                moveTo(6.5f, 4.5f)
                arcTo(1.75f, 1.75f, 0f, isMoreThanHalf = false, isPositiveArc = true, 10f, 4.5f)
                lineTo(10f, 11f)
                // The folded fingers: two knuckles stepping down to the back of the hand.
                curveTo(10f, 8.6f, 14.6f, 8.6f, 14.6f, 11f)
                curveTo(14.6f, 9.2f, 20.5f, 9.2f, 20.5f, 12.8f)
                lineTo(20.5f, 18f)
                quadTo(20.5f, 21.5f, 17f, 21.5f)
                lineTo(9.5f, 21.5f)
                quadTo(6.5f, 21.5f, 6.5f, 18.5f)
                close()
                // Tap marks.
                moveTo(2.8f, 4.5f); lineTo(4.6f, 4.5f)
                moveTo(11.9f, 4.5f); lineTo(13.7f, 4.5f)
            }
            stroke(1.1f) {
                circle(1.6f, 4.5f, 0.6f)
                circle(14.9f, 4.5f, 0.6f)
            }
        }
    }

    /**
     * Slot 6, Grid: ibisPaint's square (upper left) overlapped by a circle (lower right, in
     * front: the square's corner is hidden behind it).
     */
    val SquareCircle: ImageVector by lazy {
        glyph("SquareCircle") {
            stroke(1.5f) {
                // The circle (15, 15) r 5.5 meets the square's right edge x 14.5 and bottom y 14.5
                // at 15 − √30 ≈ 9.52.
                moveTo(14.5f, 9.52f); lineTo(14.5f, 3.5f); lineTo(3.5f, 3.5f); lineTo(3.5f, 14.5f); lineTo(9.52f, 14.5f)
                circle(15f, 15f, 5.5f)
            }
        }
    }

    /** Slot 7, Ruler: ibisPaint's single diagonal ruler (a long rounded bar with a slot between two holes). */
    val DiagonalRuler: ImageVector by lazy {
        glyph("DiagonalRuler") {
            stroke(1.5f) {
                // The bar from (5.5, 18.5) to (18.5, 5.5), 5.2 wide, round ends.
                val h = 2.6f * 0.7071f
                moveTo(5.5f + h, 18.5f + h); lineTo(18.5f + h, 5.5f + h)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 18.5f - h, 5.5f - h)
                lineTo(5.5f - h, 18.5f - h)
                arcTo(2.6f, 2.6f, 0f, isMoreThanHalf = false, isPositiveArc = false, 5.5f + h, 18.5f + h)
                close()
                moveTo(8.6f, 15.4f); lineTo(15.4f, 8.6f)
            }
            stroke(1.2f) {
                circle(6.6f, 17.4f, 0.9f)
                circle(17.4f, 6.6f, 0.9f)
            }
        }
    }

    /** Slot 8, More options: ibisPaint's picture (a frame with a white sun and white hills). */
    val Picture: ImageVector by lazy {
        glyph("Picture") {
            stroke(1.5f) {
                moveTo(3.5f, 3.5f); lineTo(20.5f, 3.5f); lineTo(20.5f, 20.5f); lineTo(3.5f, 20.5f); close()
            }
            solid {
                circle(8.6f, 8.6f, 2.1f)
                moveTo(5.4f, 12.6f); lineTo(12f, 16.6f); lineTo(18.6f, 11.8f); lineTo(18.6f, 18.6f); lineTo(5.4f, 18.6f); close()
            }
        }
    }

    /**
     * Bottom slot 6: two stacked layer squares behind the number tile, peeking out at its lower
     * right as in ibisPaint (the tile, upper left, is drawn by the caller).
     */
    val StackedSquares: ImageVector by lazy {
        glyph("StackedSquares") {
            stroke(1.6f) {
                moveTo(18.5f, 5.5f); lineTo(18.5f, 18.5f); lineTo(5.5f, 18.5f)
                moveTo(21f, 8f); lineTo(21f, 21f); lineTo(8f, 21f)
            }
        }
    }

    /** Bottom slot 5: ibisPaint's thin, long ↓ (hide interface). */
    val ThinArrowDown: ImageVector by lazy {
        glyph("ThinArrowDown") {
            stroke(1.4f) {
                moveTo(12f, 2.5f); lineTo(12f, 21.5f)
                moveTo(5.5f, 15f); lineTo(12f, 21.5f); lineTo(18.5f, 15f)
            }
        }
    }

    /** Bottom slot 5 while the interface is hidden: ↑ (show it). */
    val ThinArrowUp: ImageVector by lazy {
        glyph("ThinArrowUp") {
            stroke(1.4f) {
                moveTo(12f, 21.5f); lineTo(12f, 2.5f)
                moveTo(5.5f, 9f); lineTo(12f, 2.5f); lineTo(18.5f, 9f)
            }
        }
    }

    /** Bottom slot 7: ibisPaint's thin, long ← (back to the gallery). */
    val ThinArrowBack: ImageVector by lazy {
        glyph("ThinArrowBack") {
            stroke(1.4f) {
                moveTo(21.5f, 12f); lineTo(2.5f, 12f)
                moveTo(9f, 5.5f); lineTo(2.5f, 12f); lineTo(9f, 18.5f)
            }
        }
    }

    /** Bottom slot 6 while the layer window is open: ibisPaint's double chevron ⌄⌄. */
    val DoubleChevronDown: ImageVector by lazy {
        glyph("DoubleChevronDown") {
            stroke(1.8f) {
                moveTo(6f, 6f); lineTo(12f, 12f); lineTo(18f, 6f)
                moveTo(6f, 12f); lineTo(12f, 18f); lineTo(18f, 12f)
            }
        }
    }

    /** Bottom slot 1: the swap arrows drawn under the brush / eraser glyph. */
    val SwapArrows: ImageVector by lazy {
        glyph("SwapArrows") {
            stroke(1.8f) {
                // An arc from the bottom-left up, arrow head at its end; and back.
                moveTo(4f, 15f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 11f, 8f)
                moveTo(8.5f, 6f); lineTo(11f, 8f); lineTo(8.5f, 10f)
                moveTo(20f, 9f)
                arcTo(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 13f, 16f)
                moveTo(15.5f, 14f); lineTo(13f, 16f); lineTo(15.5f, 18f)
            }
        }
    }

    /** The tool menu's Filters cell: ibisPaint's "FX" in a ring. */
    val FxDisc: ImageVector by lazy {
        glyph("FxDisc") {
            stroke(1.6f) {
                circle(12f, 12f, 9.6f)
            }
            stroke(1.7f) {
                // F
                moveTo(7.6f, 16.2f); lineTo(7.6f, 7.8f); lineTo(11f, 7.8f)
                moveTo(7.6f, 11.8f); lineTo(10.4f, 11.8f)
                // X
                moveTo(12.6f, 7.8f); lineTo(16.8f, 16.2f)
                moveTo(16.8f, 7.8f); lineTo(12.6f, 16.2f)
            }
        }
    }

    /** Path tool (v1.6): a smooth arc over a dashed control polygon with its control points. */
    val PathTool: ImageVector by lazy {
        glyph("PathTool") {
            stroke(1.2f) {
                // Dashed control polygon (3,20) → (6,5) → (18,5) → (21,20).
                dashed(3f, 20f, 6f, 5f)
                dashed(6f, 5f, 18f, 5f)
                dashed(18f, 5f, 21f, 20f)
            }
            stroke(2f) {
                // The curve the control points pull (a clamped cubic B-spline = this Bézier).
                moveTo(3f, 20f)
                curveTo(6f, 5f, 18f, 5f, 21f, 20f)
            }
            solid {
                square(3f, 20f, 1.7f)
                circle(6f, 5f, 1.8f)
                circle(18f, 5f, 1.8f)
                square(21f, 20f, 1.7f)
            }
        }
    }

    /** Text frames tool (v1.6): two text boxes threaded together (InDesign's linked frames). */
    val TextFramesTool: ImageVector by lazy {
        glyph("TextFramesTool") {
            stroke(1.6f) {
                // First frame with text lines.
                moveTo(2f, 2.5f); lineTo(12f, 2.5f); lineTo(12f, 11.5f); lineTo(2f, 11.5f); close()
                moveTo(4.5f, 5.5f); lineTo(9.5f, 5.5f)
                moveTo(4.5f, 8.5f); lineTo(9.5f, 8.5f)
                // Second frame with text lines.
                moveTo(12f, 12.5f); lineTo(22f, 12.5f); lineTo(22f, 21.5f); lineTo(12f, 21.5f); close()
                moveTo(14.5f, 15.5f); lineTo(19.5f, 15.5f)
                moveTo(14.5f, 18.5f); lineTo(17.5f, 18.5f)
            }
            stroke(1.2f) {
                // The thread from the first frame's out-port to the second's in-port.
                dashed(9f, 13.5f, 9f, 17f)
                dashed(9f, 17f, 11f, 17f)
            }
            solid {
                // The port where the thread leaves the first frame (InDesign's out-port).
                moveTo(10f, 9.5f); lineTo(14f, 9.5f); lineTo(14f, 13.5f); lineTo(10f, 13.5f); close()
            }
        }
    }

    /**
     * Lasso (tool menu): ibisPaint's rope lasso — an open loop wider than tall, the knot on its
     * lower left, the rope's end hanging from it and hooking right (Material's "Gesture" squiggle
     * it replaces reads as a stroke, not a selection).
     */
    val Lasso: ImageVector by lazy {
        glyph("Lasso") {
            stroke(1.7f) {
                // The loop: an ellipse centred (12.5, 9), 17 × 12.
                moveTo(4f, 9f)
                arcTo(8.5f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 21f, 9f)
                arcTo(8.5f, 6f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 9f)
                close()
                // The rope's end: from the knot down to the lower left, then hooking right.
                moveTo(9.4f, 15.8f)
                curveTo(8.4f, 18f, 6.2f, 19f, 6.6f, 20.8f)
                curveTo(7f, 22.2f, 10.5f, 21.4f, 13f, 20f)
            }
            solid {
                // The knot, on the loop's lower edge.
                circle(10.2f, 14.6f, 2.3f)
            }
        }
    }

    /**
     * The layer window's horizontal flips (canvas and layer): ibisPaint's ▸|◂, two solid
     * triangles pointing at a thin upright bar from either side.
     */
    val FlipHorizontal: ImageVector by lazy {
        glyph("FlipHorizontal") {
            solid {
                // The bar, the glyph's full height.
                moveTo(11.1f, 2f); lineTo(12.9f, 2f); lineTo(12.9f, 22f); lineTo(11.1f, 22f); close()
                // ▸ at the left, its tip 1.6 short of the bar.
                moveTo(3.5f, 6.5f); lineTo(9.5f, 12f); lineTo(3.5f, 17.5f); close()
                // ◂ at the right.
                moveTo(20.5f, 6.5f); lineTo(14.5f, 12f); lineTo(20.5f, 17.5f); close()
            }
        }
    }

    /** The vertical flips: [FlipHorizontal] turned 90° (▾ over a level bar over ▴). */
    val FlipVertical: ImageVector by lazy {
        glyph("FlipVertical") {
            solid {
                moveTo(2f, 11.1f); lineTo(22f, 11.1f); lineTo(22f, 12.9f); lineTo(2f, 12.9f); close()
                moveTo(6.5f, 3.5f); lineTo(17.5f, 3.5f); lineTo(12f, 9.5f); close()
                moveTo(6.5f, 20.5f); lineTo(12f, 14.5f); lineTo(17.5f, 20.5f); close()
            }
        }
    }

    private fun PathBuilder.circle(cx: Float, cy: Float, r: Float) {
        moveTo(cx - r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx + r, cy)
        arcTo(r, r, 0f, isMoreThanHalf = true, isPositiveArc = true, cx - r, cy)
        close()
    }

    private fun PathBuilder.square(cx: Float, cy: Float, half: Float) {
        moveTo(cx - half, cy - half); lineTo(cx + half, cy - half); lineTo(cx + half, cy + half); lineTo(cx - half, cy + half); close()
    }

    /** A dashed line: 1.6-unit dashes with 1.2-unit gaps (ImageVector paths have no dash effect). */
    private fun PathBuilder.dashed(x0: Float, y0: Float, x1: Float, y1: Float) {
        val dx = x1 - x0
        val dy = y1 - y0
        val len = kotlin.math.sqrt(dx * dx + dy * dy)
        if (len <= 0f) return
        val dash = 1.6f
        val gap = 1.2f
        var t = 0f
        while (t < len) {
            val e = minOf(len, t + dash)
            moveTo(x0 + dx * t / len, y0 + dy * t / len)
            lineTo(x0 + dx * e / len, y0 + dy * e / len)
            t += dash + gap
        }
    }
}
