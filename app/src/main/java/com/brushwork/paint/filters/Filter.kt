package com.brushwork.paint.filters

import com.brushwork.paint.core.PixelBuffer
import java.util.concurrent.CancellationException

/**
 * Filter framework. Filters are PURE KOTLIN (no android.* imports) and operate on [PixelBuffer]s
 * so every filter can be unit-tested on the JVM.
 *
 * Contract for [Filter.apply]:
 *  - `src` is read-only. Return a NEW buffer of the same width/height (never mutate `src`).
 *  - Pixels are NON-premultiplied ARGB. Preserve alpha unless the effect is meant to change it.
 *  - Any parameter that is a distance in pixels must be multiplied by [FilterContext.scale]
 *    (the preview runs on a downscaled copy; scale = previewWidth / fullWidth). Use [FilterContext.px].
 *  - Call [FilterContext.checkCancelled] periodically (e.g. once per row chunk) in long loops.
 *  - Selection masking is done by the framework AFTER apply(); filters process the whole buffer.
 *  - Must be deterministic for the same inputs (use [FilterValues.seed] for randomness).
 */
abstract class Filter(
    val id: String,
    val name: String,
    val category: FilterCategory,
) {
    abstract val params: List<FilterParam>

    /** False for very slow filters (preview is then only rendered on demand). */
    open val livePreview: Boolean = true

    /** True if the filter replaces/draws content and works on an empty (transparent) layer. */
    open val generatesContent: Boolean = false

    abstract fun apply(src: PixelBuffer, values: FilterValues, ctx: FilterContext): PixelBuffer

    /**
     * Non-null for pointwise filters (each output pixel depends only on the same input pixel and
     * [values]): maps pixels exactly like [apply] does, per pixel (contract-tested). Adjustment
     * layers use it to apply the effect live (v1.5). Content-dependent filters return null.
     */
    open fun pixelMapper(values: FilterValues): PixelMapper? = null

    /** True when this filter can be the live effect of an adjustment layer. */
    val isAdjustmentCapable: Boolean get() = !generatesContent && pixelMapper(defaultValues()) != null

    fun defaultValues(): FilterValues = FilterValues(params.associate { it.key to it.defaultValue() })

    override fun toString(): String = "Filter($id)"
}

/**
 * A pointwise color mapping (v1.5): maps the NON-premultiplied ARGB pixels [from] until [until]
 * of [px] in place. Must be thread-safe (rows are mapped in parallel).
 *
 * Alpha is preserved, with one deliberate exception: a mapper may LOWER alpha where its filter's
 * [Filter.apply] does (it must equal apply(), per pixel). Today only Gradation Map does, with
 * semi-transparent gradient stops (stop alpha scales the pixel's alpha). On an adjustment layer a
 * lowered alpha means the mapped color is drawn that much weaker, so a transparent stop shows the
 * image below (Photoshop's gradient map; `AdjustmentStage`). No mapper ever raises alpha, so
 * fully transparent pixels stay fully transparent.
 */
fun interface PixelMapper {
    fun map(px: IntArray, from: Int, until: Int)
}

enum class FilterCategory(val title: String) {
    ADJUST("Color Adjustment"),
    BLUR("Blur"),
    STYLE("Style"),
    DRAW("Draw"),
    ART("Art"),
    PIXELATE("Pixelate"),
    DISTORT("Distortion"),
    FRAME("Frame & Weather"),
    AI("Smart"),
}

data class CurvePoint(val x: Float, val y: Float)
data class GradientStop(val position: Float, val color: Int)

/** Parameter descriptors; the UI builds controls from these automatically. */
sealed class FilterParam {
    abstract val key: String
    abstract val label: String
    abstract fun defaultValue(): Any

    /**
     * Numeric slider. If [step] >= 1 the value is treated as an integer.
     * [pixels] = true means the value is a distance in image pixels (the framework shows it with
     * a "px" suffix; the filter must still multiply by ctx.scale via ctx.px()).
     */
    data class Slider(
        override val key: String,
        override val label: String,
        val min: Float,
        val max: Float,
        val default: Float,
        val step: Float = 0f,
        val suffix: String = "",
        val pixels: Boolean = false,
    ) : FilterParam() { override fun defaultValue(): Any = default }

    data class Toggle(override val key: String, override val label: String, val default: Boolean) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    data class Choice(override val key: String, override val label: String, val options: List<String>, val default: Int = 0) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    /**
     * A color. When [useDrawingColor] is true the filter session starts it at the user's current
     * drawing color instead of [default] (which is still used in tests / without a session).
     */
    data class Color(override val key: String, override val label: String, val default: Int, val useDrawingColor: Boolean = false) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    /** A position on the canvas, normalized 0..1 of width/height. The user drags it on the canvas. */
    data class Point(override val key: String, override val label: String, val defaultX: Float = 0.5f, val defaultY: Float = 0.5f) : FilterParam() {
        override fun defaultValue(): Any = floatArrayOf(defaultX, defaultY)
    }

    /** Tone curve control points in 0..1 (sorted by x, first x=0, last x=1). */
    data class Curve(override val key: String, override val label: String, val default: List<CurvePoint> = listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    data class Gradient(override val key: String, override val label: String, val default: List<GradientStop>) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    data class Text(override val key: String, override val label: String, val default: String, val multiline: Boolean = false) : FilterParam() {
        override fun defaultValue(): Any = default
    }

    /** Random seed with a "shuffle" button in the UI. */
    data class Seed(override val key: String = "seed", override val label: String = "Random seed", val default: Int = 1) : FilterParam() {
        override fun defaultValue(): Any = default
    }
}

/** Current parameter values. Typed getters fall back gracefully if a value is missing. */
class FilterValues(initial: Map<String, Any> = emptyMap()) {
    private val map = HashMap(initial)

    fun set(key: String, value: Any): FilterValues { map[key] = value; return this }
    fun copy(): FilterValues = FilterValues(HashMap(map))
    fun raw(key: String): Any? = map[key]
    fun asMap(): Map<String, Any> = map

    fun float(key: String): Float = (map[key] as? Number)?.toFloat() ?: 0f
    fun int(key: String): Int = (map[key] as? Number)?.let { Math.round(it.toFloat()) } ?: 0
    fun bool(key: String): Boolean = map[key] as? Boolean ?: false
    fun choice(key: String): Int = (map[key] as? Number)?.toInt() ?: 0
    fun color(key: String): Int = (map[key] as? Number)?.toInt() ?: 0xFF000000.toInt()
    fun point(key: String): FloatArray = (map[key] as? FloatArray) ?: floatArrayOf(0.5f, 0.5f)
    @Suppress("UNCHECKED_CAST")
    fun curve(key: String): List<CurvePoint> = (map[key] as? List<CurvePoint>) ?: listOf(CurvePoint(0f, 0f), CurvePoint(1f, 1f))
    @Suppress("UNCHECKED_CAST")
    fun gradient(key: String): List<GradientStop> = (map[key] as? List<GradientStop>) ?: listOf(GradientStop(0f, 0xFF000000.toInt()), GradientStop(1f, -1))
    fun text(key: String): String = map[key] as? String ?: ""
    fun seed(key: String = "seed"): Int = int(key)
}

/**
 * Optional services a filter may need that require Android (e.g. ML segmentation). Filters must
 * handle a null/failed result gracefully (e.g. fall back to a heuristic).
 */
interface FilterServices {
    /**
     * Foreground (subject) confidence 0..1 per pixel for [image], or null if unavailable.
     * BLOCKING — only call from Filter.apply (which runs on a background thread).
     */
    fun subjectMask(image: PixelBuffer): FloatArray?
}

class FilterContext(
    /** previewWidth / fullWidth; 1.0 when applying at full resolution. */
    val scale: Float = 1f,
    /** Document resolution in dots per inch (for filters with physical sizes). */
    val dpi: Float = 350f,
    val services: FilterServices? = null,
    private val cancelled: () -> Boolean = { false },
    private val progressSink: (Float) -> Unit = {},
) {
    /** Converts a full-resolution pixel distance into the current buffer's pixel distance. */
    fun px(fullResPixels: Float): Float = fullResPixels * scale

    fun isCancelled(): Boolean = cancelled()

    fun checkCancelled() { if (cancelled()) throw CancellationException("filter cancelled") }

    fun progress(fraction: Float) = progressSink(fraction.coerceIn(0f, 1f))
}
