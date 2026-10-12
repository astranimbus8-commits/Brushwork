package com.brushwork.paint.compat18

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable

/*
 * v1.9 F0: a FROZEN copy of the v1.8 text model (`tools/text/TextModel.kt`, `TextPathSpec.kt`
 * and the part of `fonts/FontIds.kt` that `TextSpec.sanitized` uses, at 600582d9), in test
 * sources only, so v1.9 tests can read new text layers the way v1.8 reads them
 * (`Qa19CompatBackFromV18RobolectricTest`). Every serialized property, its order, default and
 * `@EncodeDefault` is v1.8's, and so is every `sanitized`; what the codec does not use (the box
 * presets, layout and pinch helpers, labels) is left out. With v1.8's JSON settings a v1.9 field
 * is dropped on read and an enum value v1.8 does not know takes the field's default. Never change
 * it: it is what v1.8 is.
 */

/** v1.8's `TextFont`. */
@Serializable
enum class TextFontV18(val label: String, val family: String) {
    SANS("Sans", "sans-serif"),
    SERIF("Serif", "serif"),
    MONOSPACE("Monospace", "monospace"),
    CONDENSED("Condensed", "sans-serif-condensed"),
    CASUAL("Casual", "casual"),
    CURSIVE("Cursive", "cursive"),
}

/** v1.8's `TextAlign` (no JUSTIFY). */
@Serializable
enum class TextAlignV18(val horizontalLabel: String, val verticalLabel: String) {
    START("Left", "Top"),
    CENTER("Center", "Center"),
    END("Right", "Bottom"),
}

/** v1.8's `VerticalStyle`. */
@Serializable
enum class VerticalStyleV18(val label: String) {
    UPRIGHT("Upright letters"),
    MIXED("Sideways Latin (manga)"),
}

/** v1.8's `TextBoxSpec`. */
@Serializable
data class TextBoxSpecV18(
    val width: Float = 0f,
    val height: Float = 0f,
    val padding: Float = 0f,
    val fill: Boolean = false,
    val fillColor: Int = 0xFFFFFFFF.toInt(),
    val borderWidth: Float = 0f,
    val borderColor: Int = 0xFF000000.toInt(),
    val roundness: Float = 0f,
    val minHeight: Float = 0f,
    val minWidth: Float = 0f,
) {
    /** v1.8's `TextBoxSpec.scaled`: every length multiplied by [k]. */
    fun scaled(k: Float): TextBoxSpecV18 = copy(
        width = width * k, height = height * k, padding = padding * k, borderWidth = borderWidth * k,
        minHeight = minHeight * k, minWidth = minWidth * k,
    )

    companion object {
        const val MIN_ROUNDNESS = 0f
        const val MAX_ROUNDNESS = 1f
    }
}

/** v1.8's `LetterScaleDirection`. */
@Serializable
enum class LetterScaleDirectionV18(val label: String) {
    START_TO_END("Beginning → end"),
    END_TO_START("End → beginning"),
}

/** v1.8's `LetterScaleAlign`. */
@Serializable
enum class LetterScaleAlignV18(val label: String) {
    CENTER("Center"),
    BASELINE("Baseline"),
    TOP("Top"),
}

/** v1.8's `LetterScaleCurve`. */
@Serializable
enum class LetterScaleCurveV18(val label: String) {
    EVEN("Even steps"),
    RATIO("Same ratio"),
}

/** v1.8's `LetterScaleScope`. */
@Serializable
enum class LetterScaleScopeV18(val label: String) {
    WHOLE_TEXT("Whole text"),
    EACH_PARAGRAPH("Each paragraph"),
}

/** v1.8's `LetterScaleSpec`, with v1.8's [sanitized]. */
@Serializable
data class LetterScaleSpecV18(
    val smallestPercent: Float = 100f,
    val direction: LetterScaleDirectionV18 = LetterScaleDirectionV18.START_TO_END,
    val align: LetterScaleAlignV18 = LetterScaleAlignV18.CENTER,
    val curve: LetterScaleCurveV18 = LetterScaleCurveV18.EVEN,
    val scope: LetterScaleScopeV18 = LetterScaleScopeV18.WHOLE_TEXT,
) {
    /** v1.8's `LetterScaleSpec.sanitized`: [smallestPercent] finite and in [MIN_PERCENT]..100. */
    fun sanitized(): LetterScaleSpecV18 {
        val p = if (smallestPercent.isFinite()) smallestPercent.coerceIn(MIN_PERCENT, 100f) else 100f
        return if (p == smallestPercent) this else copy(smallestPercent = p)
    }

    companion object {
        const val MIN_PERCENT = 5f
    }
}

/** v1.8's `TextSpec` (no justify, kerning mode or list), with v1.8's [scaled] and [sanitized]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TextSpecV18(
    val font: TextFontV18 = TextFontV18.SANS,
    val bold: Boolean = false,
    val italic: Boolean = false,
    val sizePx: Float = 48f,
    val color: Int = 0xFF000000.toInt(),
    val align: TextAlignV18 = TextAlignV18.START,
    val letterSpacing: Float = 0f,
    val lineSpacing: Float = 1.2f,
    val vertical: Boolean = false,
    val strokeWidthPx: Float = 0f,
    val strokeColor: Int = 0xFFFFFFFF.toInt(),
    val antiAlias: Boolean = true,
    val verticalStyle: VerticalStyleV18 = VerticalStyleV18.UPRIGHT,
    val columnsLeftToRight: Boolean = false,
    val box: TextBoxSpecV18 = TextBoxSpecV18(),
    val fontId: String? = null,
    val fontName: String? = null,
    val letterScale: LetterScaleSpecV18 = LetterScaleSpecV18(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val fontKerning: Boolean = true,
) {
    /** v1.8's `TextSpec.scaled`: font size, outline and box resized by [k]. */
    fun scaled(k: Float): TextSpecV18 = copy(sizePx = sizePx * k, strokeWidthPx = strokeWidthPx * k, box = box.scaled(k))

    /** v1.8's `TextSpec.sanitized`. */
    fun sanitized(): TextSpecV18 {
        fun f(v: Float, default: Float) = if (v.isFinite()) v else default
        fun len(v: Float) = f(v, 0f).coerceIn(0f, MAX_LENGTH_PX)
        val b = box
        val id = fontId?.takeIf { FontIdsV18.isValid(it) }
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
                roundness = f(b.roundness, 0f).coerceIn(TextBoxSpecV18.MIN_ROUNDNESS, TextBoxSpecV18.MAX_ROUNDNESS),
                minHeight = len(b.minHeight),
                minWidth = len(b.minWidth),
            ),
            fontId = id,
            fontName = if (id == null) null else fontName?.let { FontIdsV18.cleanName(it) },
            letterScale = letterScale.sanitized(),
        )
    }

    companion object {
        const val MIN_SIZE_PX = 2f
        const val MAX_SIZE_PX = 100_000f
        const val MAX_LENGTH_PX = 1_000_000f
        const val MIN_LETTER_SPACING = -0.3f
        const val MAX_LETTER_SPACING = 1f
        const val MIN_LINE_SPACING = 0.5f
        const val MAX_LINE_SPACING = 3f
    }
}

/** v1.8's `WrapContour`. */
@Serializable
enum class WrapContourV18(val label: String) {
    SHAPE("Shape"),
    BOX("Box"),
}

/** v1.8's `WrapSides`. */
@Serializable
enum class WrapSidesV18(val label: String) {
    LARGEST("Largest side"),
    BOTH("Both sides"),
    LEFT("Left only"),
    RIGHT("Right only"),
}

/** v1.8's `WrapPolygon`. */
@Serializable
data class WrapPolygonV18(val xs: List<Float>, val ys: List<Float>)

/** v1.8's `TextWrapSpec`, with v1.8's [sanitized]. */
@Serializable
data class TextWrapSpecV18(
    val sourceLayerId: Long = 0,
    val contour: WrapContourV18 = WrapContourV18.SHAPE,
    val gapPx: Float = 0f,
    val sides: WrapSidesV18 = WrapSidesV18.LARGEST,
    val polygons: List<WrapPolygonV18> = emptyList(),
    val minRunEm: Float = 1.5f,
) {
    val isOn: Boolean get() = sourceLayerId != 0L

    /** v1.8's `TextWrapSpec.sanitized`. */
    fun sanitized(): TextWrapSpecV18 {
        val g = if (gapPx.isFinite()) gapPx.coerceIn(0f, MAX_GAP_PX) else 0f
        val run = if (minRunEm.isFinite()) minRunEm.coerceIn(MIN_RUN_EM, MAX_RUN_EM) else 1.5f
        var budget = MAX_STORED_POINTS
        val polys = ArrayList<WrapPolygonV18>(polygons.size)
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
        const val MAX_GAP_PX = 200f
        const val MIN_RUN_EM = 0.5f
        const val MAX_RUN_EM = 20f
        const val MAX_STORED_POINTS = 4000
        const val MAX_COORD = 1_000_000f
    }
}

/** v1.8's `TextThreadSpec`, with v1.8's [sanitized]. */
@Serializable
data class TextThreadSpecV18(
    val storyId: Long = 0,
    val index: Int = 0,
    val story: String = "",
    val start: Int = 0,
    val end: Int = 0,
    val overset: Boolean = false,
    val rev: Long = 0,
) {
    val isOn: Boolean get() = storyId != 0L

    /** v1.8's `TextThreadSpec.sanitized`. */
    fun sanitized(): TextThreadSpecV18 {
        if (storyId <= 0L) return if (this == NONE) this else NONE
        var s = story
        if (s.length > MAX_STORY) {
            var cut = MAX_STORY
            if (Character.isHighSurrogate(s[cut - 1])) cut--
            s = s.substring(0, cut)
        }
        val a = start.coerceIn(0, s.length)
        val b = end.coerceIn(a, s.length)
        val i = index.coerceAtLeast(0)
        val r = rev.coerceAtLeast(0L)
        return if (s === story && a == start && b == end && i == index && r == rev) this
        else copy(story = s, start = a, end = b, index = i, rev = r)
    }

    companion object {
        const val MAX_STORY = 50_000

        private val NONE = TextThreadSpecV18()
    }
}

/** v1.8's `TextKern`, with v1.8's [sanitized]. */
@Serializable
data class TextKernV18(val index: Int, val value: Int) {
    companion object {
        const val MIN_VALUE = -1000
        const val MAX_VALUE = 1000
        const val MAX_COUNT = 10_000

        /** v1.8's `TextKern.sanitized`. */
        fun sanitized(kerns: List<TextKernV18>, length: Int): List<TextKernV18> {
            if (kerns.isEmpty()) return kerns
            var ok = kerns.size <= MAX_COUNT
            var last = -1
            if (ok) for (k in kerns) {
                if (k.index <= last || k.index >= length - 1 || k.value == 0 || k.value < MIN_VALUE || k.value > MAX_VALUE) { ok = false; break }
                last = k.index
            }
            if (ok) return kerns
            val seen = HashSet<Int>()
            val out = ArrayList<TextKernV18>()
            for (k in kerns) {
                if (k.index < 0 || k.index >= length - 1) continue
                val v = k.value.coerceIn(MIN_VALUE, MAX_VALUE)
                if (v == 0 || !seen.add(k.index)) continue
                out += if (v == k.value) k else k.copy(value = v)
            }
            out.sortBy { it.index }
            return if (out.size > MAX_COUNT) out.subList(0, MAX_COUNT).toList() else out
        }
    }
}

/** v1.8's `TextItem` (no id), with v1.8's [sanitized]. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TextItemV18(
    val text: String = "",
    val spec: TextSpecV18 = TextSpecV18(),
    val cx: Float = 0f,
    val cy: Float = 0f,
    val rotationDeg: Float = 0f,
    val path: TextPathSpecV18 = TextPathSpecV18(),
    val wrap: TextWrapSpecV18 = TextWrapSpecV18(),
    val thread: TextThreadSpecV18 = TextThreadSpecV18(),
    @EncodeDefault(EncodeDefault.Mode.NEVER) val kerns: List<TextKernV18> = emptyList(),
) {
    /** v1.8's `TextItem.sanitized`. */
    fun sanitized(): TextItemV18 {
        val base = sanitizedNumbers()
        var th = base.thread.sanitized()
        if (th.isOn && (base.spec.box.width <= 0f || base.spec.box.minHeight <= 0f)) th = TextThreadSpecV18()
        val item = if (!th.isOn) {
            if (th === base.thread) base else base.copy(thread = th)
        } else {
            base.copy(
                text = th.story.substring(th.start, th.end),
                spec = if (base.spec.vertical) base.spec.copy(vertical = false) else base.spec,
                path = if (base.path.isActive) base.path.copy(type = TextPathTypeV18.NONE) else base.path,
                thread = th,
            )
        }
        val kerns = TextKernV18.sanitized(item.kerns, if (th.isOn) th.story.length else item.text.length)
        return if (kerns === item.kerns) item else item.copy(kerns = kerns)
    }

    private fun sanitizedNumbers(): TextItemV18 {
        fun f(v: Float, default: Float) = if (v.isFinite()) v else default
        val d = TextPathSpecV18()
        val p = path
        return copy(
            spec = spec.sanitized(),
            cx = f(cx, 0f),
            cy = f(cy, 0f),
            rotationDeg = normalizeDegrees(f(rotationDeg, 0f)),
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
        /** v1.8's `TextItem.normalizeDegrees`: (-180, 180]. */
        fun normalizeDegrees(deg: Float): Float {
            var d = deg % 360f
            if (d <= -180f) d += 360f
            if (d > 180f) d -= 360f
            return d
        }
    }
}

/** v1.8's `TextPathType` (no nodes). */
@Serializable
enum class TextPathTypeV18(val label: String) {
    NONE("Straight"),
    LINE("Line"),
    CIRCLE("Circle"),
    RECT("Square / rectangle"),
    CURVE("Curve"),
}

/** v1.8's `TextPathMode`. */
@Serializable
enum class TextPathModeV18(val label: String) {
    BEND("Bend letters"),
    ROTATE("Rotate letters"),
}

/** v1.8's `TextPathSide`. */
@Serializable
enum class TextPathSideV18(val label: String) {
    OUTSIDE("Outside"),
    INSIDE("Inside"),
}

/** v1.8's `TextPathAlign`. */
@Serializable
enum class TextPathAlignV18(val label: String) { START("Start"), CENTER("Center"), END("End") }

/** v1.8's `TextPathSpec` (no nodes). */
@Serializable
data class TextPathSpecV18(
    val type: TextPathTypeV18 = TextPathTypeV18.NONE,
    val mode: TextPathModeV18 = TextPathModeV18.BEND,
    val x1: Float = 0f,
    val y1: Float = 0f,
    val x2: Float = 0f,
    val y2: Float = 0f,
    val cx1: Float = 0f,
    val cy1: Float = 0f,
    val cx2: Float = 0f,
    val cy2: Float = 0f,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val radius: Float = 200f,
    val startAngleDeg: Float = -90f,
    val width: Float = 400f,
    val height: Float = 400f,
    val cornerRadius: Float = 0f,
    val rotationDeg: Float = 0f,
    val keepSquare: Boolean = false,
    val clockwise: Boolean = true,
    val side: TextPathSideV18 = TextPathSideV18.OUTSIDE,
    val align: TextPathAlignV18 = TextPathAlignV18.CENTER,
    val offset: Float = 0f,
    val baselineShift: Float = 0f,
) {
    val isActive: Boolean get() = type != TextPathTypeV18.NONE
}

/** v1.8's `FontIds.isValid` and `FontIds.cleanName`, as `TextSpec.sanitized` uses them. */
internal object FontIdsV18 {
    private val ID = Regex("^[0-9a-f]{16,64}$")

    private const val MAX_NAME_LENGTH = 80

    fun isValid(id: String?): Boolean = id != null && ID.matches(id)

    fun cleanName(name: String): String? {
        val s = buildString(name.length) {
            for (ch in name) append(if (ch.isISOControl() || ch == '�' || ch == '\u0000') ' ' else ch)
        }.trim().replace(Regex("\\s+"), " ")
        return s.take(MAX_NAME_LENGTH).trim().ifEmpty { null }
    }
}
