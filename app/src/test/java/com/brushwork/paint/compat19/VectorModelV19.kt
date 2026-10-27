package com.brushwork.paint.compat19

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v1.10 G2 (compat19 kit): a FROZEN copy of the v1.9 vector model (`vector/VectorModel.kt` and the fields of
 * `vector/CurveGenerator.kt` at tag v1.9.0), in test sources only, so v1.10 tests can read new
 * files the way v1.9 reads them ([VectorCodecV19]). Every serialized property, its order,
 * default, serial name and `@EncodeDefault` is v1.9's; what the codec does not use (editing
 * helpers, approximate sizes, the generator's geometry) is left out. Never change it: it is what
 * v1.9 is. A stroke's points are the frozen [PackedPointsV19], not the app's class.
 */

/** v1.9's `VectorContent`: all objects of one vector layer, bottom first. */
@Serializable
data class VectorContentV19(
    val version: Int = 1,
    val objects: List<VObjectV19> = emptyList(),
    val nextId: Long = 1,
)

/** v1.9's `VObject`. */
@Serializable
sealed class VObjectV19 {
    abstract val id: Long
    abstract val opacity: Float

    /** This object with another [id]. */
    abstract fun withId(id: Long): VObjectV19
}

/** v1.9's `VStroke` ([copies] compared by content, as v1.9 does). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("stroke")
data class VStrokeV19(
    override val id: Long,
    override val opacity: Float = 1f,
    val preset: BrushPresetV19,
    val color: Int,
    val seed: Long,
    val stylus: Boolean,
    val points: PackedPointsV19,
    val sizeScale: Float = 1f,
    val taperIn: Boolean = true,
    val taperOut: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val copies: List<FloatArray> = emptyList(),
) : VObjectV19() {
    override fun withId(id: Long): VObjectV19 = copy(id = id)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VStrokeV19) return false
        return id == other.id && opacity.compareTo(other.opacity) == 0 && preset == other.preset && color == other.color &&
            seed == other.seed && stylus == other.stylus && points == other.points && sizeScale.compareTo(other.sizeScale) == 0 &&
            taperIn == other.taperIn && taperOut == other.taperOut && StrokeCopiesV19.sameMaps(copies, other.copies)
    }

    override fun hashCode(): Int {
        var h = id.hashCode()
        h = 31 * h + opacity.hashCode()
        h = 31 * h + preset.hashCode()
        h = 31 * h + color
        h = 31 * h + seed.hashCode()
        h = 31 * h + stylus.hashCode()
        h = 31 * h + points.hashCode()
        h = 31 * h + sizeScale.hashCode()
        h = 31 * h + taperIn.hashCode()
        h = 31 * h + taperOut.hashCode()
        for (m in copies) h = 31 * h + m.contentHashCode()
        return h
    }
}

/** v1.9's `VPath`, with v1.9's live copies and generator. */
@Serializable
@SerialName("path")
data class VPathV19(
    override val id: Long,
    override val opacity: Float = 1f,
    val subpaths: List<VSubpathV19>,
    val tension: Float = 0f,
    val polyline: Boolean = false,
    val fillRule: VFillRuleV19 = VFillRuleV19.NONZERO,
    val fill: VPaintV19? = null,
    val stroke: VStrokeStyleV19? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val spline: VSplineV19? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val copies: CopyMapsV19 = CopyMapsV19.NONE,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val generator: CurveGeneratorV19? = null,
) : VObjectV19() {
    override fun withId(id: Long): VObjectV19 = copy(id = id)
}

/** v1.9's `VSubpath`. */
@Serializable
data class VSubpathV19(val anchors: List<VAnchorV19>, val closed: Boolean = false)

/** v1.9's `VAnchor`. */
@Serializable
data class VAnchorV19(
    val x: Float,
    val y: Float,
    val sharp: Boolean = false,
    val inX: Float? = null,
    val inY: Float? = null,
    val outX: Float? = null,
    val outY: Float? = null,
    val width: Float = 1f,
)

/** v1.9's `VSplinePoint`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VSplinePointV19(
    val x: Float,
    val y: Float,
    val weight: Float = 1f,
    val width: Float = 1f,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sharp: Boolean = false,
)

/** v1.9's `VSpline`, with v1.9's [sanitized]. */
@Serializable
data class VSplineV19(
    val points: List<VSplinePointV19>,
    val order: Int = 4,
    val endpoint: Boolean = true,
    val cyclic: Boolean = false,
) {
    /** v1.9's `VSpline.sanitized`: usable numbers only; this instance when nothing changes. */
    fun sanitized(): VSplineV19 {
        val o = order.coerceIn(MIN_ORDER, MAX_ORDER)
        var changed = o != order || points.size > MAX_POINTS
        val out = ArrayList<VSplinePointV19>(minOf(points.size, MAX_POINTS))
        for (p in points) {
            if (out.size >= MAX_POINTS) break
            if (!p.x.isFinite() || !p.y.isFinite() || kotlin.math.abs(p.x) > MAX_COORD || kotlin.math.abs(p.y) > MAX_COORD) { changed = true; continue }
            val wt = if (p.weight.isFinite()) p.weight.coerceIn(MIN_WEIGHT, MAX_WEIGHT) else 1f
            val wd = if (p.width.isFinite()) p.width.coerceIn(0f, MAX_WIDTH) else 1f
            if (wt != p.weight || wd != p.width) { changed = true; out += p.copy(weight = wt, width = wd) } else out += p
        }
        if (!cyclic && out.isNotEmpty()) {
            if (out[0].sharp) { changed = true; out[0] = out[0].copy(sharp = false) }
            if (out[out.lastIndex].sharp) { changed = true; out[out.lastIndex] = out[out.lastIndex].copy(sharp = false) }
        }
        return if (!changed) this else VSplineV19(out, o, endpoint, cyclic)
    }

    companion object {
        const val MIN_ORDER = 2
        const val MAX_ORDER = 6
        const val MIN_WEIGHT = 0.1f
        const val MAX_WEIGHT = 10f
        const val MAX_WIDTH = 3f
        const val MAX_POINTS = 2000
        const val MAX_COORD = 1_000_000f
    }
}

/** v1.9's `VPaint`. */
@Serializable
sealed class VPaintV19 {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: Int) : VPaintV19()

    @Serializable
    @SerialName("linear")
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val stops: List<VStopV19>) : VPaintV19()

    @Serializable
    @SerialName("radial")
    data class Radial(val cx: Float, val cy: Float, val r: Float, val stops: List<VStopV19>, val matrix: List<Float>? = null) : VPaintV19()
}

/** v1.9's `VStop`. */
@Serializable
data class VStopV19(val offset: Float, val color: Int)

/** v1.9's `VFillRule`. */
@Serializable
enum class VFillRuleV19 { NONZERO, EVENODD }

/** v1.9's `VStrokeKind`. */
@Serializable
enum class VStrokeKindV19 { PLAIN, BRUSH }

/** v1.9's `VStrokeStyle`. */
@Serializable
data class VStrokeStyleV19(
    val kind: VStrokeKindV19 = VStrokeKindV19.PLAIN,
    val color: Int,
    val width: Float,
    val cap: LineCapStyleV19 = LineCapStyleV19.ROUND,
    val join: JoinStyleV19 = JoinStyleV19.ROUND,
    val miter: Float = 4f,
    val brushTool: ToolIdV19? = null,
    val brush: BrushPresetV19? = null,
    val seed: Long = 0,
    val taperPercent: Float = 0f,
)

/** v1.9's `VShape`. */
@Serializable
@SerialName("shape")
data class VShapeV19(
    override val id: Long,
    override val opacity: Float = 1f,
    val shape: ShapeObjectV19,
    val seed: Long = 0,
) : VObjectV19() {
    override fun withId(id: Long): VObjectV19 = copy(id = id)
}

/** v1.9's `CurveGenKind`. */
@Serializable
enum class CurveGenKindV19 { ARCHIMEDEAN, LOGARITHMIC, GOLDEN, FERMAT, SINE, ROSE }

/** v1.9's `CurveGenerator` (its serialized fields; v1.9's codec does not sanitize it). */
@Serializable
data class CurveGeneratorV19(
    val kind: CurveGenKindV19 = CurveGenKindV19.ARCHIMEDEAN,
    val cx: Float = 0f,
    val cy: Float = 0f,
    val size: Float = 200f,
    val angleDeg: Float = 0f,
    val turns: Float = 3f,
    val inner: Float = 0f,
    val growth: Float = 2f,
    val clockwise: Boolean = false,
    val amplitude: Float = 40f,
    val waves: Float = 3f,
    val petalsN: Int = 5,
    val petalsD: Int = 1,
    val density: Int = 0,
)
