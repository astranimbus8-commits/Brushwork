package com.brushwork.paint.model

import com.brushwork.paint.core.LengthUnit
import kotlinx.serialization.Serializable

@Serializable
enum class GridType(val label: String) {
    SQUARE("Square"),
    RULE_OF_THIRDS("Rule of thirds"),
    ISOMETRIC("Isometric"),
    DIAGONAL("Diagonal"),
}

/** Overlay grid (display only; never rendered into the artwork). Distances are document px. */
@Serializable
data class GridSettings(
    val enabled: Boolean = false,
    val type: GridType = GridType.SQUARE,
    val spacingPx: Float = 100f,
    /** Every Nth line is drawn stronger (0 or 1 = none). */
    val majorEvery: Int = 4,
    val offsetXPx: Float = 0f,
    val offsetYPx: Float = 0f,
    val color: Int = 0xFF2F80ED.toInt(),
    val opacity: Float = 0.45f,
    /** Unit used when showing/editing the numbers in the grid panel. */
    val unit: LengthUnit = LengthUnit.PX,
    /** Snap painting / shape points to grid intersections. */
    val snap: Boolean = false,
)

@Serializable
enum class RulerType(val label: String) {
    STRAIGHT("Straight"),
    CIRCLE("Circular"),
    ELLIPSE("Elliptical"),
    RADIAL("Radial"),
}

@Serializable
enum class RulerSnap(val label: String) {
    /** Strokes follow a line parallel to / concentric with the ruler through the stroke start. */
    PARALLEL("Parallel / concentric"),
    /** Strokes are pulled onto the ruler itself. */
    ON_RULER("On the ruler"),
}

/**
 * The drawing ruler. All positions/lengths are in DOCUMENT PIXELS (floats, sub-pixel allowed);
 * [unit] only controls how numbers are displayed/edited. [centerX]/[centerY] < 0 means
 * "not placed yet" (the ruler is centered on the canvas when first enabled).
 *
 *  - STRAIGHT: a line through (centerX, centerY) at [angleDeg].
 *  - CIRCLE:   circle at (centerX, centerY) with [radius].
 *  - ELLIPSE:  ellipse at (centerX, centerY), semi-axes [radiusX]/[radiusY], rotated [angleDeg].
 *  - RADIAL:   lines through (centerX, centerY) — every stroke goes toward/away from the center.
 *              [radialLines] is only used to draw guide lines.
 */
@Serializable
data class RulerSettings(
    val enabled: Boolean = false,
    val type: RulerType = RulerType.STRAIGHT,
    val centerX: Float = -1f,
    val centerY: Float = -1f,
    val angleDeg: Float = 0f,
    val radius: Float = 300f,
    val radiusX: Float = 400f,
    val radiusY: Float = 250f,
    val radialLines: Int = 24,
    val snap: RulerSnap = RulerSnap.PARALLEL,
    val unit: LengthUnit = LengthUnit.PX,
    /** Step for nudge arrow buttons, in [unit]. */
    val nudgeStep: Float = 1f,
)

@Serializable
enum class StabilizerMode(val label: String) {
    OFF("Off"),
    /** Weighted moving average (like ibisPaint's "Stabilization"). */
    SMOOTH("Smooth"),
    /** Blender-style "lazy mouse": a string of fixed length drags the brush behind the finger. */
    ROPE("Rope (lazy mouse)"),
}

/**
 * v1.6 increments ("a number for increments everywhere", §3.4): the five quantity kinds a step
 * applies to. [suffix] is the unit the step is typed in ([LENGTH] shows in the tool's own unit).
 */
@Serializable
enum class IncrementKind(val label: String, val suffix: String) {
    /** Moves (a delta from the gesture start), coordinates, box / shape / frame sizes, radii. */
    LENGTH("Length", "px"),
    /** Brush size, font size, stroke width. */
    SIZE("Size", "px"),
    /** Scale factors (Transform, pinches, handle scaling), in % of the original / gesture start. */
    SCALE("Scale", "%"),
    /** Every rotation (absolute angle), gradient-mask angles. */
    ANGLE("Angle", "°"),
    /** Opacity, flow, hardness, amounts, 0..100 sliders. */
    PERCENT("Percent", "%"),
}

/**
 * The app-wide increment steps (v1.6, §3.4; `AppSettings.increments`, not per document, not an
 * undo step). Off by default (I8: with increments off every gesture and control is bit-identical
 * to v1.5). [custom] holds the steps of controls that have no kind (Exposure in EV, counts...),
 * by control key ("$label|$suffix" unless the control names its own), in that control's shown
 * unit.
 */
@Serializable
data class IncrementSettings(
    /** Master switch (the "#" cell of the X / Y pill). I8: off. */
    val enabled: Boolean = false,
    val lengthPx: Float = 10f,
    val sizePx: Float = 1f,
    val scalePercent: Float = 10f,
    val angleDeg: Float = 15f,
    val percent: Float = 5f,
    /** Control key -> step in that control's shown unit. */
    val custom: Map<String, Float> = emptyMap(),
) {
    /** The step of [kind] (in its [IncrementKind.suffix] unit), whether or not [enabled]. */
    fun step(kind: IncrementKind): Float = when (kind) {
        IncrementKind.LENGTH -> lengthPx
        IncrementKind.SIZE -> sizePx
        IncrementKind.SCALE -> scalePercent
        IncrementKind.ANGLE -> angleDeg
        IncrementKind.PERCENT -> percent
    }

    /** These settings with the step of [kind] set to [v] (call [sanitized] before storing). */
    fun with(kind: IncrementKind, v: Float): IncrementSettings = when (kind) {
        IncrementKind.LENGTH -> copy(lengthPx = v)
        IncrementKind.SIZE -> copy(sizePx = v)
        IncrementKind.SCALE -> copy(scalePercent = v)
        IncrementKind.ANGLE -> copy(angleDeg = v)
        IncrementKind.PERCENT -> copy(percent = v)
    }

    /** These settings with the custom step of control [key] set to [v] (null removes it). */
    fun withCustom(key: String, v: Float?): IncrementSettings =
        copy(custom = if (v == null) custom - key else custom + (key to v))

    /**
     * Usable steps: each kind finite, > 0 and at most its [MAX_STEPS] bound (anything else takes
     * the default); custom steps finite and > 0 (others dropped), keys non-blank and at most
     * [MAX_KEY_LENGTH] characters, at most [MAX_CUSTOM] of them (the first ones kept). This
     * instance when it already is.
     */
    fun sanitized(): IncrementSettings {
        fun s(kind: IncrementKind, v: Float): Float {
            val max = MAX_STEPS.getValue(kind)
            return if (v.isFinite() && v > 0f && v <= max) v else DEFAULT.step(kind)
        }
        var cleanCustom: Map<String, Float> = custom
        if (custom.size > MAX_CUSTOM || custom.any { (k, v) -> k.isBlank() || k.length > MAX_KEY_LENGTH || !v.isFinite() || v <= 0f || v > MAX_CUSTOM_STEP }) {
            val out = LinkedHashMap<String, Float>()
            for ((k, v) in custom) {
                if (out.size >= MAX_CUSTOM) break
                if (k.isBlank() || k.length > MAX_KEY_LENGTH || !v.isFinite() || v <= 0f || v > MAX_CUSTOM_STEP) continue
                out[k] = v
            }
            cleanCustom = out
        }
        val n = IncrementSettings(
            enabled, s(IncrementKind.LENGTH, lengthPx), s(IncrementKind.SIZE, sizePx), s(IncrementKind.SCALE, scalePercent),
            s(IncrementKind.ANGLE, angleDeg), s(IncrementKind.PERCENT, percent), cleanCustom,
        )
        return if (n == this) this else n
    }

    companion object {
        private val DEFAULT = IncrementSettings()

        /** Largest step per kind (px, px, %, °, %). */
        val MAX_STEPS: Map<IncrementKind, Float> = mapOf(
            IncrementKind.LENGTH to 100_000f,
            IncrementKind.SIZE to 1_000f,
            IncrementKind.SCALE to 1_000f,
            IncrementKind.ANGLE to 360f,
            IncrementKind.PERCENT to 100f,
        )

        /** Most custom steps kept. */
        const val MAX_CUSTOM = 64

        /** Longest custom control key. */
        const val MAX_KEY_LENGTH = 128

        /** Largest custom step (in the control's own unit). */
        const val MAX_CUSTOM_STEP = 1_000_000f
    }
}

/**
 * v1.6: how the canvas shows transparent areas (the layer window's transparency squares,
 * `AppSettings.transparencyDisplay`). A VIEW preference only: exports, flattening and the
 * compositor are unaffected (I5). [label] completes "Transparency: …".
 */
@Serializable
enum class TransparencyDisplay(val label: String) {
    WHITE("white"),
    LIGHT_CHECKER("light checker"),
    DARK_CHECKER("dark checker"),
    /** Nothing: the canvas surround shows through. */
    NONE("none"),
}

@Serializable
data class StabilizerSettings(
    val mode: StabilizerMode = StabilizerMode.OFF,
    /** 0..1 for SMOOTH. */
    val strength: Float = 0.5f,
    /** Rope length in SCREEN dp (like Blender's radius) for ROPE. */
    val ropeLengthDp: Float = 60f,
    /** When lifting the finger, finish the stroke up to the finger position. */
    val catchUp: Boolean = true,
)
