package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import kotlin.math.cos
import kotlin.math.sin

/*
 * Pure-Kotlin description of a text object (no android imports, unit-tested on the JVM).
 */

/** Font families available to the text tool ([family] is the Android system family name). */
enum class TextFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONOSPACE("Monospace", "monospace"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    CASUAL("Casual", "casual"),
    CURSIVE("Cursive", "cursive"),
}

/** Alignment of lines (horizontal text) or columns (vertical text). */
enum class TextAlign(val horizontalLabel: String, val verticalLabel: String) {
    START("Left", "Top"),
    CENTER("Center", "Center"),
    END("Right", "Bottom"),
}

/**
 * Appearance of a text object. Sizes are DOCUMENT pixels; [letterSpacing] is in em (fraction of
 * the font size) and [lineSpacing] a multiplier of the font's line height (vertical text: of the
 * column pitch).
 */
data class TextSpec(
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizePx: Float = 48f,
    val color: Int = 0xFF000000.toInt(),
    val align: TextAlign = TextAlign.START,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.2f,
    /** Manga style: characters top-to-bottom, columns right-to-left. */
    val vertical: Boolean = false,
    /** Outline drawn behind the fill, 0 = none. */
    val strokeWidthPx: Float = 0f,
    val strokeColor: Int = 0xFFFFFFFF.toInt(),
    val antiAlias: Boolean = true,
) {
    companion object {
        const val MIN_SIZE_PX = 2f
        const val MIN_LETTER_SPACING = -0.3f
        const val MAX_LETTER_SPACING = 1f
        const val MIN_LINE_SPACING = 0.5f
        const val MAX_LINE_SPACING = 3f
    }
}

/**
 * A placed text object: the block of laid-out text is centered on ([cx], [cy]) in document
 * pixels and rotated by [rotationDeg] (clockwise on screen) around that center.
 */
data class TextItem(
    val text: String,
    val spec: TextSpec,
    val cx: Float,
    val cy: Float,
    val rotationDeg: Float = 0f,
) {
    /** Name for the layer the text is committed into: "Text: " + the first 12 characters. */
    fun layerName(): String {
        val flat = text.trim().replace(Regex("\\s+"), " ")
        val cps = flat.codePoints().limit(12).toArray()
        return "Text: " + String(cps, 0, cps.size).trimEnd()
    }

    /** Document position of the local block point ([lx], [ly]) for a block of [w] x [h]. */
    fun localToDoc(lx: Float, ly: Float, w: Float, h: Float): Vec2 {
        val r = Math.toRadians(rotationDeg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val x = lx - w / 2f; val y = ly - h / 2f
        return Vec2(cx + x * c - y * s, cy + x * s + y * c)
    }

    /** Inverse of [localToDoc]. */
    fun docToLocal(p: Vec2, w: Float, h: Float): Vec2 {
        val r = Math.toRadians(-rotationDeg.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val x = p.x - cx; val y = p.y - cy
        return Vec2(x * c - y * s + w / 2f, x * s + y * c + h / 2f)
    }

    /**
     * This text after a two-finger pinch that started with the fingers' midpoint at [focus]
     * (document px): the font size and outline scale by [scale] (the size clamped to
     * [TextSpec.MIN_SIZE_PX]..[maxSizePx]), the text turns by [deltaDeg] about [focus] and moves
     * by [translation], so it follows the fingers.
     */
    fun pinched(focus: Vec2, translation: Vec2, scale: Float, deltaDeg: Float, maxSizePx: Float): TextItem {
        val k0 = if (scale.isFinite() && scale > 0f) scale else 1f
        val size = (spec.sizePx * k0).coerceIn(TextSpec.MIN_SIZE_PX, maxOf(TextSpec.MIN_SIZE_PX, maxSizePx))
        val k = if (spec.sizePx > 0f) size / spec.sizePx else 1f
        val d = if (deltaDeg.isFinite()) deltaDeg else 0f
        val r = Math.toRadians(d.toDouble())
        val c = cos(r).toFloat(); val s = sin(r).toFloat()
        val dx = (cx - focus.x) * k; val dy = (cy - focus.y) * k
        val tx = if (translation.x.isFinite()) translation.x else 0f
        val ty = if (translation.y.isFinite()) translation.y else 0f
        return copy(
            cx = focus.x + tx + dx * c - dy * s,
            cy = focus.y + ty + dx * s + dy * c,
            rotationDeg = normalizeDegrees(rotationDeg + d),
            spec = spec.copy(sizePx = size, strokeWidthPx = spec.strokeWidthPx * k),
        )
    }

    /** Corners of the (optionally [pad]-expanded) block in document space: TL, TR, BR, BL. */
    fun corners(w: Float, h: Float, pad: Float = 0f): List<Vec2> = listOf(
        localToDoc(-pad, -pad, w, h),
        localToDoc(w + pad, -pad, w, h),
        localToDoc(w + pad, h + pad, w, h),
        localToDoc(-pad, h + pad, w, h),
    )

    companion object {
        /** Normalizes an angle to (-180, 180]. */
        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d <= -180f) d += 360f
            if (d > 180f) d -= 360f
            return d
        }

        /** Snaps [deg] to the nearest multiple of [step] when within [tolerance] degrees. */
        fun snapDegrees(deg: Float, step: Float = 45f, tolerance: Float = 3f): Float {
            val nearest = Math.round(deg / step) * step
            return if (kotlin.math.abs(deg - nearest) <= tolerance) normalizeDegrees(nearest) else normalizeDegrees(deg)
        }
    }
}
