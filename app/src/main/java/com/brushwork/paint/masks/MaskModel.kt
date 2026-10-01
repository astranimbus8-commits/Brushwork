package com.brushwork.paint.masks

import com.brushwork.paint.core.PackedPoints
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.JsonObject

/*
 * Editable (parametric) masks and adjustment layers (frozen model, v1.5; §4.3). A [MaskSpec] is
 * rendered into the existing `Layer.mask` bitmap (white = visible), so everything that reads
 * masks keeps working; the spec stays editable. Coordinates are document pixels. Changes to these
 * classes are additive only and made by the lead.
 *
 * Combination per pixel: m = startFull ? 1 : 0; then per visible component with r = invert ?
 * 1 - raw : raw and amount a: ADD m = max(m, a*r); SUBTRACT m = m*(1 - a*r); INTERSECT
 * m = m*(1 - a + a*r); final (spec.invert ? 1 - m : m) * density.
 */

@Serializable
data class MaskSpec(
    val version: Int = 1,
    /** Start from a fully visible mask (so a first "Subtract" component makes sense). */
    val startFull: Boolean = false,
    val components: List<MaskComponent> = emptyList(),
    val invert: Boolean = false,
    /** 0..1 multiplier of the whole mask. */
    val density: Float = 1f,
    /** The id the next new component gets. */
    val nextId: Long = 1,
) {
    /** Approximate retained bytes (brush points dominate), for undo history bounds. */
    fun approxBytes(): Long {
        var b = 64L
        for (c in components) {
            b += 64L
            if (c is BrushMask) for (s in c.strokes) b += 32L + s.points.size * 12L
        }
        return b
    }
}

/** One component of a [MaskSpec]. */
@Serializable
sealed class MaskComponent {
    abstract val id: Long
    abstract val mode: MaskMode
    abstract val invert: Boolean
    /** 0..1 strength of this component. */
    abstract val amount: Float
    abstract val visible: Boolean
}

/** 100 % on the line through p0, 0 % on the line through p1 (both perpendicular to p0p1), smoothstep between. */
@Serializable
@SerialName("linear")
data class LinearMask(
    override val id: Long,
    override val mode: MaskMode = MaskMode.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) : MaskComponent()

/** 100 % inside the ellipse shrunk by [feather] (0..1), 0 % outside the ellipse. */
@Serializable
@SerialName("radial")
data class RadialMask(
    override val id: Long,
    override val mode: MaskMode = MaskMode.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rotationDeg: Float = 0f,
    val feather: Float = 0.5f,
) : MaskComponent()

/** Painted soft discs (accumulated; erase strokes take coverage away). */
@Serializable
@SerialName("brush")
data class BrushMask(
    override val id: Long,
    override val mode: MaskMode = MaskMode.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val strokes: List<MaskStroke> = emptyList(),
) : MaskComponent()

/** One brush stroke of a [BrushMask]: disc [size] (diameter, px), [hardness] and [flow] 0..1. */
@Serializable
data class MaskStroke(val erase: Boolean, val size: Float, val hardness: Float, val flow: Float, val points: PackedPoints)

@Serializable
enum class MaskMode { ADD, SUBTRACT, INTERSECT }

/** Effect of an adjustment layer: any filter whose pixelMapper() is non-null, with its values as JSON (FilterValuesCodec). */
@Serializable
data class AdjustmentSpec(
    val version: Int = 1,
    val filterId: String = "adjust.tone",
    val values: JsonObject = JsonObject(emptyMap()),
)
