package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.text.frames.FrameGeometry
import kotlin.math.abs
import kotlin.math.atan2
import kotlin.math.sqrt

/**
 * v1.7 (item 11, §3.11; area D): the Transform tool's data lift of a text layer. Move, the
 * rotate knob and the corner handles (a proportional scale) map the text's data, so the layer
 * stays a sharp text layer:
 *
 * - its centre is mapped and `rotationDeg` turns by the map's angle;
 * - every length is multiplied by the map's scale s: the font size, the outline width, the box
 *   (text area width and height, padding, border width, minimum width and height), the wrap gap
 *   and, for text on a path, the path (its points map; its sizes, offset and baseline shift scale);
 * - nothing measured in em or percent changes (letter and line spacing, kerns, letter scaling).
 *
 * Every length scaled by the same s keeps every ratio, so the lines break where they did: the
 * layout run again on the mapped data (the caller renders it) gives the same lines, s times larger.
 * A text wrapped around a picture keeps the picture's outline (the picture did not move; when it
 * moves too, as in a folder, its own edit re-flows the text, [TextWrapReflow]).
 *
 * A frame of a linked story is scaled with the look it shows (box and type alike), as the
 * Transform preview shows it, and its look becomes the whole story's: when the map changes the
 * look ([FrameGeometry.storyLook]: a scale) the frame's `thread.rev` goes up by one, so it holds
 * the story's newest copy, and `TextThreads` re-flows the story in that look into every frame
 * (each keeping its own box) inside the same step, when the caller's edit is reported
 * (`EditorController.updateTextLayer`). A move keeps the look and the `rev`: nothing re-flows.
 * Frames are never rotated (§7), so a map that turns one is refused.
 */
object TextTransforms {

    /**
     * True when [m] (a row-major 3 × 3 affine matrix in document px) keeps a text a text: a
     * translation, a rotation and a uniform positive scale (no flip, skew or perspective).
     */
    fun canMap(m: FloatArray): Boolean = similarity(m) != null

    /**
     * The text layer data [textData] (TextCodec JSON) mapped by [m], or null when it can't be:
     * [m] is no similarity ([canMap]), the data is no text, the scaled sizes leave their ranges
     * ([TextSpec.MIN_SIZE_PX] … [TextSpec.MAX_SIZE_PX], lengths up to [TextSpec.MAX_LENGTH_PX]),
     * or [m] turns a frame of a linked story. [textData] itself for the identity.
     */
    fun mapped(textData: String, m: FloatArray): String? {
        val sim = similarity(m) ?: return null
        val item = TextCodec.decode(textData) ?: return null
        if (sim.isIdentity) return textData
        val next = mapped(item, sim) ?: return null
        return TextCodec.encode(next)
    }

    /** [item] mapped by [m] (see [mapped]), or null when it can't be. */
    fun mapped(item: TextItem, m: FloatArray): TextItem? = similarity(m)?.let { mapped(item, it) }

    /** A similarity x' = s R(θ) x + t, read from a row-major 3 × 3 matrix. */
    private class Similarity(
        val a: Double, val b: Double, val c: Double,
        val d: Double, val e: Double, val f: Double,
        val scale: Double, val degrees: Double,
    ) {
        val isIdentity: Boolean get() = a == 1.0 && b == 0.0 && c == 0.0 && d == 0.0 && e == 1.0 && f == 0.0

        fun x(px: Double, py: Double): Double = a * px + b * py + c
        fun y(px: Double, py: Double): Double = d * px + e * py + f
    }

    private fun similarity(m: FloatArray): Similarity? {
        if (m.size != 9 || m.any { !it.isFinite() }) return null
        if (abs(m[6]) > EPS || abs(m[7]) > EPS || abs(m[8] - 1f) > EPS) return null
        val a = m[0].toDouble(); val b = m[1].toDouble(); val c = m[2].toDouble()
        val d = m[3].toDouble(); val e = m[4].toDouble(); val f = m[5].toDouble()
        val sx = sqrt(a * a + d * d)
        val sy = sqrt(b * b + e * e)
        if (!(sx > MIN_SCALE) || !(sy > MIN_SCALE)) return null
        // Columns of the same length, at right angles, turning the same way (no flip).
        if (abs(sx - sy) > TOLERANCE * sx) return null
        if (abs(a * b + d * e) > TOLERANCE * sx * sy) return null
        if (a * e - b * d <= 0.0) return null
        // A scale that is 1 but for rounding (a move read back from a composed matrix) is 1:
        // a moved text keeps its sizes exactly.
        val scale = ((sx + sy) / 2.0).let { if (abs(it - 1.0) <= UNIT_SCALE_EPS) 1.0 else it }
        return Similarity(a, b, c, d, e, f, scale, Math.toDegrees(atan2(d, a)))
    }

    private fun mapped(item: TextItem, m: Similarity): TextItem? {
        val k = m.scale.toFloat()
        val turn = m.degrees.toFloat()
        if (item.thread.isOn && abs(TextItem.normalizeDegrees(turn)) > FRAME_TURN_EPS) return null
        val spec = item.spec.scaled(k)
        // A size or length pushed out of its range would be clamped: the ratios, and so the
        // lines, would no longer hold.
        if (!(spec.sizePx >= TextSpec.MIN_SIZE_PX && spec.sizePx <= TextSpec.MAX_SIZE_PX)) return null
        val b = spec.box
        if (maxOf(spec.strokeWidthPx, b.width, b.height, b.padding, b.borderWidth, maxOf(b.minHeight, b.minWidth)) > TextSpec.MAX_LENGTH_PX) return null
        val cx = m.x(item.cx.toDouble(), item.cy.toDouble()).toFloat()
        val cy = m.y(item.cx.toDouble(), item.cy.toDouble()).toFloat()
        if (!cx.isFinite() || !cy.isFinite()) return null
        // p' = M p for every path point: about the old centre, then onto the new one.
        val path = if (item.path.isActive) {
            TextOnPath.transformed(item.path, Vec2(cx - item.cx, cy - item.cy), k, turn, Vec2(item.cx, item.cy))
        } else item.path
        val wrap = if (item.wrap.gapPx == 0f) item.wrap else item.wrap.copy(gapPx = (item.wrap.gapPx * k).coerceAtMost(TextWrapSpec.MAX_GAP_PX))
        // A frame whose look changes holds the story's newest copy: its look wins story-wide.
        val thread = if (item.thread.isOn && FrameGeometry.storyLook(spec) != FrameGeometry.storyLook(item.spec)) {
            item.thread.copy(rev = item.thread.rev + 1)
        } else item.thread
        return item.copy(
            spec = spec,
            cx = cx,
            cy = cy,
            rotationDeg = if (item.thread.isOn) item.rotationDeg else TextItem.normalizeDegrees(item.rotationDeg + turn),
            path = path,
            wrap = wrap,
            thread = thread,
        )
    }

    /** How far the matrix's last row may be from (0, 0, 1). */
    private const val EPS = 1e-6f

    /** Relative difference of the two scales (and the cosine of their angle) still taken as uniform. */
    private const val TOLERANCE = 1e-4

    /** Smaller scales collapse the text. */
    private const val MIN_SCALE = 1e-6

    /** How far from 1 a scale is still rounding of 1 (float matrices hold about 7 digits). */
    private const val UNIT_SCALE_EPS = 1e-6

    /** Turns of a frame smaller than this (degrees) are rounding. */
    private const val FRAME_TURN_EPS = 0.01f
}
