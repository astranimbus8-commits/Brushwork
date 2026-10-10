package com.brushwork.paint.compat17

import com.brushwork.paint.core.PackedPoints
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * v1.8 F0: a FROZEN copy of the v1.7 vector model (`vector/VectorModel.kt` at 885793b4), in test
 * sources only, so v1.8 tests can read new files the way v1.7 reads them (`CompatV17DecodeTest`)
 * and check that v1.7 files still encode byte for byte (I13 / I13b). Every serialized property,
 * its order, default, serial name and `@EncodeDefault` is v1.7's; what the codec does not use
 * (editing helpers, approximate sizes) is left out. Never change it: it is what v1.7 is.
 * [PackedPoints] (and its serializer) is shared with the app, which keeps its format (I13).
 */

/** v1.7's `VectorContent`: all objects of one vector layer, bottom first. */
@Serializable
data class VectorContentV17(
    val version: Int = 1,
    val objects: List<VObjectV17> = emptyList(),
    val nextId: Long = 1,
) {
    /** The object with [id], or null. */
    fun byId(id: Long): VObjectV17? = objects.firstOrNull { it.id == id }
}

/** v1.7's `VObject`. */
@Serializable
sealed class VObjectV17 {
    abstract val id: Long
    abstract val opacity: Float

    /** This object with another [id]. */
    abstract fun withId(id: Long): VObjectV17
}

/** v1.7's `VStroke` ([copies] compared by content, as v1.7 does). */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("stroke")
data class VStrokeV17(
    override val id: Long,
    override val opacity: Float = 1f,
    val preset: BrushPresetV17,
    val color: Int,
    val seed: Long,
    val stylus: Boolean,
    val points: PackedPoints,
    val sizeScale: Float = 1f,
    val taperIn: Boolean = true,
    val taperOut: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val copies: List<FloatArray> = emptyList(),
) : VObjectV17() {
    override fun withId(id: Long): VObjectV17 = copy(id = id)

    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VStrokeV17) return false
        return id == other.id && opacity.compareTo(other.opacity) == 0 && preset == other.preset && color == other.color &&
            seed == other.seed && stylus == other.stylus && points == other.points && sizeScale.compareTo(other.sizeScale) == 0 &&
            taperIn == other.taperIn && taperOut == other.taperOut && StrokeCopiesV17.sameMaps(copies, other.copies)
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

/** v1.7's `VPath`: v1.7 has no `copies` and no `generator`, so it drops them on read. */
@Serializable
@SerialName("path")
data class VPathV17(
    override val id: Long,
    override val opacity: Float = 1f,
    val subpaths: List<VSubpathV17>,
    val tension: Float = 0f,
    val polyline: Boolean = false,
    val fillRule: VFillRuleV17 = VFillRuleV17.NONZERO,
    val fill: VPaintV17? = null,
    val stroke: VStrokeStyleV17? = null,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val spline: VSplineV17? = null,
) : VObjectV17() {
    override fun withId(id: Long): VObjectV17 = copy(id = id)
}

/** v1.7's `VSubpath`. */
@Serializable
data class VSubpathV17(val anchors: List<VAnchorV17>, val closed: Boolean = false)

/** v1.7's `VAnchor`. */
@Serializable
data class VAnchorV17(
    val x: Float,
    val y: Float,
    val sharp: Boolean = false,
    val inX: Float? = null,
    val inY: Float? = null,
    val outX: Float? = null,
    val outY: Float? = null,
    val width: Float = 1f,
)

/** v1.7's `VSplinePoint`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VSplinePointV17(
    val x: Float,
    val y: Float,
    val weight: Float = 1f,
    val width: Float = 1f,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sharp: Boolean = false,
)

/** v1.7's `VSpline`, with v1.7's [sanitized]. */
@Serializable
data class VSplineV17(
    val points: List<VSplinePointV17>,
    val order: Int = 4,
    val endpoint: Boolean = true,
    val cyclic: Boolean = false,
) {
    /** v1.7's `VSpline.sanitized`: usable numbers only; this instance when nothing changes. */
    fun sanitized(): VSplineV17 {
        val o = order.coerceIn(MIN_ORDER, MAX_ORDER)
        var changed = o != order || points.size > MAX_POINTS
        val out = ArrayList<VSplinePointV17>(minOf(points.size, MAX_POINTS))
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
        return if (!changed) this else VSplineV17(out, o, endpoint, cyclic)
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

/** v1.7's `VPaint`. */
@Serializable
sealed class VPaintV17 {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: Int) : VPaintV17()

    @Serializable
    @SerialName("linear")
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val stops: List<VStopV17>) : VPaintV17()

    @Serializable
    @SerialName("radial")
    data class Radial(val cx: Float, val cy: Float, val r: Float, val stops: List<VStopV17>, val matrix: List<Float>? = null) : VPaintV17()
}

/** v1.7's `VStop`. */
@Serializable
data class VStopV17(val offset: Float, val color: Int)

/** v1.7's `VFillRule`. */
@Serializable
enum class VFillRuleV17 { NONZERO, EVENODD }

/** v1.7's `VStrokeKind`. */
@Serializable
enum class VStrokeKindV17 { PLAIN, BRUSH }

/** v1.7's `VStrokeStyle`. */
@Serializable
data class VStrokeStyleV17(
    val kind: VStrokeKindV17 = VStrokeKindV17.PLAIN,
    val color: Int,
    val width: Float,
    val cap: LineCapStyleV17 = LineCapStyleV17.ROUND,
    val join: JoinStyleV17 = JoinStyleV17.ROUND,
    val miter: Float = 4f,
    val brushTool: ToolIdV17? = null,
    val brush: BrushPresetV17? = null,
    val seed: Long = 0,
    val taperPercent: Float = 0f,
)

/** v1.7's `VShape`. */
@Serializable
@SerialName("shape")
data class VShapeV17(
    override val id: Long,
    override val opacity: Float = 1f,
    val shape: ShapeObjectV17,
    val seed: Long = 0,
) : VObjectV17() {
    override fun withId(id: Long): VObjectV17 = copy(id = id)
}
