package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.fonts.FontIds
import kotlinx.serialization.Serializable
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin

/*
 * Pure-Kotlin description of a text object (no android imports, unit-tested on the JVM). The
 * whole object is @Serializable: a text layer stores it as JSON (see TextCodec) so the text can
 * be edited again later.
 */

/** Font families available to the text tool ([family] is the Android system family name). */
@Serializable
enum class TextFont(val label: String, val family: String) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONOSPACE("Monospace", "monospace"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    CASUAL("Casual", "casual"),
    CURSIVE("Cursive", "cursive"),
}

/** Alignment of lines (horizontal text) or columns (vertical text). */
@Serializable
enum class TextAlign(val horizontalLabel: String, val verticalLabel: String) {
    START("Left", "Top"),
    CENTER("Center", "Center"),
    END("Right", "Bottom"),
}

/** How the characters of vertical text are oriented. */
@Serializable
enum class VerticalStyle(val label: String) {
    /**
     * Every character stands upright (normal orientation) and characters are stacked top to
     * bottom, centered in their column: Latin letters, digits and punctuation included. Only the
     * Japanese vertical forms that have no upright meaning (long vowel mark ー, wave dash, CJK
     * brackets, ellipsis and dashes) keep their vertical form.
     */
    UPRIGHT("Upright letters"),

    /** Japanese typesetting: CJK upright, Latin runs turned sideways, "12" / "!?" in one cell. */
    MIXED("Sideways Latin (manga)"),
}

/**
 * The box around a text object (like ibisPaint's text frame). Lengths are DOCUMENT pixels.
 * The box is part of the committed pixels and of hit testing.
 */
@Serializable
data class TextBoxSpec(
    /** Horizontal text: width of the text area, lines wrap inside it; 0 = as wide as the longest line. */
    val width: Float = 0f,
    /** Vertical text: height of the text area, columns wrap; 0 = as tall as the longest column. */
    val height: Float = 0f,
    /** Space between the text and the border. */
    val padding: Float = 0f,
    /** Background fill. */
    val fill: Boolean = false,
    val fillColor: Int = 0xFFFFFFFF.toInt(),
    /** Border drawn inside the box edge, 0 = none. */
    val borderWidth: Float = 0f,
    val borderColor: Int = 0xFF000000.toInt(),
    /** 0 = square corners .. 1 = fully rounded ends (a pill; a circle-ish bubble for short text). */
    val roundness: Float = 0f,
    /**
     * Horizontal text with a fixed [width]: the text area is at least this tall, so the box is a
     * real area of [width] x [minHeight] (what "Fill the box" fills); it still grows when the text
     * is taller. 0 = the box fits the text. Ignored without a fixed width and for vertical text.
     */
    val minHeight: Float = 0f,
    /** Vertical text with a fixed [height]: the text area is at least this wide (see [minHeight]). */
    val minWidth: Float = 0f,
) {
    /** True when something of the box itself is drawn. */
    val hasFrame: Boolean get() = fill || borderWidth > 0f

    /** Distance from the box edge to the text area. */
    val inset: Float get() = max(0f, padding) + max(0f, borderWidth)

    /** Every length multiplied by [k] (resizing the text object). */
    fun scaled(k: Float): TextBoxSpec = copy(
        width = width * k, height = height * k, padding = padding * k, borderWidth = borderWidth * k,
        minHeight = minHeight * k, minWidth = minWidth * k,
    )

    /** Wrap length of [vertical] or horizontal text (0 = the box fits the text). */
    fun wrapFor(vertical: Boolean): Float = if (vertical) height else width

    /** The box's other side (across the lines / columns), 0 = fits the text; only used with a wrap. */
    fun depthFor(vertical: Boolean): Float = if (vertical) minWidth else minHeight

    companion object {
        const val MIN_ROUNDNESS = 0f
        const val MAX_ROUNDNESS = 1f
    }
}

/** Quick box styles; lengths are relative to the font size so they look right at any size. */
enum class TextBoxPreset(val label: String) {
    PLAIN("Plain"),
    CAPTION("Caption box"),
    BUBBLE("Rounded bubble");

    /** [box] with this preset's look (the wrap width / height are kept). */
    fun applyTo(box: TextBoxSpec, sizePx: Float): TextBoxSpec {
        val border = max(1f, sizePx * 0.06f)
        return when (this) {
            PLAIN -> box.copy(padding = 0f, fill = false, borderWidth = 0f, roundness = 0f)
            CAPTION -> box.copy(padding = sizePx * 0.35f, fill = true, fillColor = WHITE, borderWidth = border, borderColor = BLACK, roundness = 0f)
            BUBBLE -> box.copy(padding = sizePx * 0.5f, fill = true, fillColor = WHITE, borderWidth = border, borderColor = BLACK, roundness = 1f)
        }
    }

    /**
     * Whether [box] currently looks like this preset at [sizePx] (lengths compared with a tiny
     * tolerance, so a text resized with the handle or a pinch still shows its preset).
     */
    fun matches(box: TextBoxSpec, sizePx: Float): Boolean {
        val p = applyTo(box, sizePx)
        fun near(a: Float, b: Float) = abs(a - b) <= 1e-3f * max(1f, max(abs(a), abs(b)))
        return p.fill == box.fill && p.fillColor == box.fillColor && p.borderColor == box.borderColor &&
            near(p.padding, box.padding) && near(p.borderWidth, box.borderWidth) && near(p.roundness, box.roundness)
    }

    private companion object {
        const val WHITE = 0xFFFFFFFF.toInt()
        const val BLACK = 0xFF000000.toInt()
    }
}

/**
 * Appearance of a text object. Sizes are DOCUMENT pixels; [letterSpacing] is in em (fraction of
 * the font size) and [lineSpacing] a multiplier of the font's line height (vertical text: of the
 * column pitch).
 */
@Serializable
data class TextSpec(
    /** Built-in family; with [fontId] set it is the fallback drawn while that font is missing. */
    val font: TextFont = TextFont.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizePx: Float = 48f,
    val color: Int = 0xFF000000.toInt(),
    val align: TextAlign = TextAlign.START,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.2f,
    /** Characters top-to-bottom in columns (see [verticalStyle], [columnsLeftToRight]). */
    val vertical: Boolean = false,
    /** Outline drawn behind the fill, 0 = none. */
    val strokeWidthPx: Float = 0f,
    val strokeColor: Int = 0xFFFFFFFF.toInt(),
    val antiAlias: Boolean = true,
    /** Vertical text: upright letters (default) or Japanese mixed orientation. */
    val verticalStyle: VerticalStyle = VerticalStyle.UPRIGHT,
    /** Vertical text: columns run left to right instead of right to left (manga). */
    val columnsLeftToRight: Boolean = false,
    /** Box (wrap width, padding, background, border, rounding). */
    val box: TextBoxSpec = TextBoxSpec(),
    /**
     * Imported font file (see `com.brushwork.paint.fonts.FontStore`): its content hash, or null
     * for the built-in [font]. When the file is missing (deleted, or a project from another
     * device) the text is drawn with [font] and the editor warns.
     */
    val fontId: String? = null,
    /** Display name of [fontId], kept so a missing font can still be named. */
    val fontName: String? = null,
) {
    /** True when the text uses an imported font (which may be missing). */
    val usesImportedFont: Boolean get() = fontId != null

    /** The name of the font the text asks for. */
    val fontLabel: String get() = if (fontId != null) fontName ?: "Imported font" else font.label

    /**
     * The whole look resized by [k]: font size, outline and box (used by the resize handle and
     * the pinch). The caller clamps the size.
     */
    fun scaled(k: Float): TextSpec = copy(sizePx = sizePx * k, strokeWidthPx = strokeWidthPx * k, box = box.scaled(k))

    /**
     * Non-finite or out-of-range numbers replaced (typed garbage, old or damaged data): sizes and
     * lengths are also capped far above anything a canvas needs, so a damaged value can't make
     * the layout allocate for billions of pixels.
     */
    fun sanitized(): TextSpec {
        fun f(v: Float, default: Float) = if (v.isFinite()) v else default
        fun len(v: Float) = f(v, 0f).coerceIn(0f, MAX_LENGTH_PX)
        val b = box
        // The id names a file: anything but a plain hash (damaged or crafted data) is dropped.
        val id = fontId?.takeIf { FontIds.isValid(it) }
        return copy(
            sizePx = f(sizePx, 48f).coerceIn(MIN_SIZE_PX, MAX_SIZE_PX),
            letterSpacing = f(letterSpacing, 0f).coerceIn(MIN_LETTER_SPACING, MAX_LETTER_SPACING),
            lineSpacing = f(lineSpacing, 1.2f).coerceIn(MIN_LINE_SPACING, MAX_LINE_SPACING),
            strokeWidthPx = len(strokeWidthPx),
            box = b.copy(
                width = len(b.width),
                height = len(b.height),
                padding = len(b.padding),
                borderWidth = len(b.borderWidth),
                roundness = f(b.roundness, 0f).coerceIn(TextBoxSpec.MIN_ROUNDNESS, TextBoxSpec.MAX_ROUNDNESS),
                minHeight = len(b.minHeight),
                minWidth = len(b.minWidth),
            ),
            fontId = id,
            fontName = if (id == null) null else fontName?.let { FontIds.cleanName(it) },
        )
    }

    companion object {
        const val MIN_SIZE_PX = 2f
        /** Upper bound for stored font sizes (the tool itself allows 2 x the canvas' longer side). */
        const val MAX_SIZE_PX = 100_000f
        /** Upper bound for stored lengths (box, padding, outline). */
        const val MAX_LENGTH_PX = 1_000_000f
        const val MIN_LETTER_SPACING = -0.3f
        const val MAX_LETTER_SPACING = 1f
        const val MIN_LINE_SPACING = 0.5f
        const val MAX_LINE_SPACING = 3f
    }
}

/** What text wraps around: the opaque outline of the picture, or its content bounds. */
@Serializable
enum class WrapContour(val label: String) {
    SHAPE("Shape"),
    BOX("Box"),
}

/** On which side(s) of the picture the lines of wrapped text go. */
@Serializable
enum class WrapSides(val label: String) {
    /** Per line, the side with more room. */
    LARGEST("Largest side"),

    /** A line fills the room left of the picture, then continues right of it. */
    BOTH("Both sides"),
    LEFT("Left only"),
    RIGHT("Right only"),
}

/** One closed outline of a wrap obstacle, in DOCUMENT pixels (the closing edge is implicit). */
@Serializable
data class WrapPolygon(val xs: List<Float>, val ys: List<Float>) {
    val size: Int get() = xs.size
}

/**
 * Text flowing around a picture (v1.5, horizontal straight text only). [sourceLayerId] names the
 * picture layer (0 = off); [polygons] is its outline frozen when the text was last laid out, in
 * DOCUMENT pixels: rendering uses only these polygons, never the source layer, so a text renders
 * the same after a reload, in an export or with its picture deleted. The text keeps [gapPx] away
 * from the outline; runs narrower than [minRunEm] (in em) are left empty.
 */
@Serializable
data class TextWrapSpec(
    val sourceLayerId: Long = 0,
    val contour: WrapContour = WrapContour.SHAPE,
    val gapPx: Float = 0f,
    val sides: WrapSides = WrapSides.LARGEST,
    val polygons: List<WrapPolygon> = emptyList(),
    val minRunEm: Float = 1.5f,
) {
    /** True when a picture is chosen (the layout wraps; see [TextItem.wrapActive]). */
    val isOn: Boolean get() = sourceLayerId != 0L

    /** Total number of outline points. */
    val pointCount: Int get() = polygons.sumOf { it.size }

    /**
     * Usable numbers only: the gap and the shortest run kept in range, outlines with a non-finite
     * point, fewer than three points or mismatched coordinate lists dropped, and at most
     * [MAX_STORED_POINTS] points kept (damaged or crafted data can't make a layout slow).
     */
    fun sanitized(): TextWrapSpec {
        val g = if (gapPx.isFinite()) gapPx.coerceIn(0f, MAX_GAP_PX) else 0f
        val run = if (minRunEm.isFinite()) minRunEm.coerceIn(MIN_RUN_EM, MAX_RUN_EM) else 1.5f
        var budget = MAX_STORED_POINTS
        val polys = ArrayList<WrapPolygon>(polygons.size)
        for (p in polygons) {
            if (p.xs.size != p.ys.size || p.xs.size < 3 || p.xs.size > budget) continue
            if (p.xs.any { !it.isFinite() || kotlin.math.abs(it) > MAX_COORD } || p.ys.any { !it.isFinite() || kotlin.math.abs(it) > MAX_COORD }) continue
            budget -= p.xs.size
            polys += p
        }
        val sameList = polys.size == polygons.size
        return if (g == gapPx && run == minRunEm && sameList && sourceLayerId >= 0L) this
        else copy(gapPx = g, minRunEm = run, polygons = if (sameList) polygons else polys, sourceLayerId = sourceLayerId.coerceAtLeast(0L))
    }

    companion object {
        /** Largest distance between the text and the picture (the Distance field's range). */
        const val MAX_GAP_PX = 200f
        const val MIN_RUN_EM = 0.5f
        const val MAX_RUN_EM = 20f

        /** Outline points a contour is simplified to (design: at most 600). */
        const val MAX_POINTS = 600

        /** Points accepted when reading stored data. */
        const val MAX_STORED_POINTS = 4000

        /** Coordinates far beyond any canvas are damaged data. */
        const val MAX_COORD = 1_000_000f
    }
}

/**
 * A placed text object: the block of laid-out text is centered on ([cx], [cy]) in document
 * pixels and rotated by [rotationDeg] (clockwise on screen) around that center. When [path] is
 * active the text follows that shape instead (its geometry is in document pixels); [cx]/[cy]/
 * [rotationDeg] then move along with it, so switching back to straight text puts it nearby.
 * [wrap] makes horizontal straight text flow around a picture (see [TextWrapSpec]).
 */
@Serializable
data class TextItem(
    val text: String = "",
    val spec: TextSpec = TextSpec(),
    val cx: Float = 0f,
    val cy: Float = 0f,
    val rotationDeg: Float = 0f,
    val path: TextPathSpec = TextPathSpec(),
    val wrap: TextWrapSpec = TextWrapSpec(),
) {
    /** Whether the text can wrap around a picture: horizontal straight text. */
    val canWrap: Boolean get() = !spec.vertical && !path.isActive

    /** True when the layout flows around [wrap]'s outline (on, horizontal and straight). */
    val wrapActive: Boolean get() = wrap.isOn && canWrap

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
     * (document px): the font size, outline and box scale by [scale] (the size clamped to
     * [TextSpec.MIN_SIZE_PX]..[maxSizePx]), the text turns by [deltaDeg] about [focus] and moves
     * by [translation], so it follows the fingers. (A text path is transformed by the tool.)
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
            spec = spec.scaled(k).copy(sizePx = size),
        )
    }

    /** Corners of the (optionally [pad]-expanded) block in document space: TL, TR, BR, BL. */
    fun corners(w: Float, h: Float, pad: Float = 0f): List<Vec2> = listOf(
        localToDoc(-pad, -pad, w, h),
        localToDoc(w + pad, -pad, w, h),
        localToDoc(w + pad, h + pad, w, h),
        localToDoc(-pad, h + pad, w, h),
    )

    /** Non-finite numbers replaced so the item can always be drawn and stored. */
    fun sanitized(): TextItem {
        fun f(v: Float, default: Float) = if (v.isFinite()) v else default
        val d = TextPathSpec()
        val p = path
        return copy(
            spec = spec.sanitized(),
            cx = f(cx, 0f),
            cy = f(cy, 0f),
            rotationDeg = normalizeDegrees(f(rotationDeg, 0f)),
            // The path's own numbers too (its engine can then rely on finite geometry).
            path = p.copy(
                x1 = f(p.x1, d.x1), y1 = f(p.y1, d.y1), x2 = f(p.x2, d.x2), y2 = f(p.y2, d.y2),
                cx1 = f(p.cx1, d.cx1), cy1 = f(p.cy1, d.cy1), cx2 = f(p.cx2, d.cx2), cy2 = f(p.cy2, d.cy2),
                cx = f(p.cx, d.cx), cy = f(p.cy, d.cy), radius = f(p.radius, d.radius),
                startAngleDeg = f(p.startAngleDeg, d.startAngleDeg), width = f(p.width, d.width),
                height = f(p.height, d.height), cornerRadius = f(p.cornerRadius, d.cornerRadius),
                rotationDeg = f(p.rotationDeg, d.rotationDeg), offset = f(p.offset, d.offset),
                baselineShift = f(p.baselineShift, d.baselineShift),
            ),
            wrap = wrap.sanitized(),
        )
    }

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
