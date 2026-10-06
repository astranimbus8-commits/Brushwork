package com.brushwork.paint.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeObject
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable

/*
 * The editable content of a VECTOR LAYER (frozen model, v1.5): an immutable list of objects in
 * z-order (first = bottom). `Layer.bitmap` of a vector layer is a render cache of this content;
 * every edit replaces the whole (immutable) [VectorContent] and the cache together (see
 * `EditorController.updateLayerData` and `vector/VectorLayers`). Lengths are document pixels.
 * Changes to these classes are additive only and made by the lead.
 */

/** All objects of one vector layer, bottom first. [nextId] is the id the next new object gets. */
@Serializable
data class VectorContent(
    val version: Int = 1,
    val objects: List<VObject> = emptyList(),
    val nextId: Long = 1,
) {
    /** The object with [id], or null. */
    fun byId(id: Long): VObject? = objects.firstOrNull { it.id == id }

    /** Z-index of the object with [id], or -1. */
    fun indexOf(id: Long): Int = objects.indexOfFirst { it.id == id }

    /** Appends [objs] on top, assigning ids from [nextId] (their own ids are ignored). Returns the new content and the ids. */
    fun plus(objs: List<VObject>): Pair<VectorContent, List<Long>> {
        if (objs.isEmpty()) return this to emptyList()
        val ids = List(objs.size) { nextId + it }
        val added = objs.mapIndexed { i, o -> o.withId(ids[i]) }
        return copy(objects = objects + added, nextId = nextId + objs.size) to ids
    }

    /** Without the objects whose ids are in [ids]. */
    fun without(ids: Set<Long>): VectorContent =
        if (ids.isEmpty() || objects.none { it.id in ids }) this else copy(objects = objects.filter { it.id !in ids })

    /**
     * Each id of [map] has its object replaced in place (same z position) by 0..n objects: the
     * first piece keeps the replaced object's id, extra pieces get new ids from [nextId]. Ids
     * not in this content are ignored.
     */
    fun replaced(map: Map<Long, List<VObject>>): VectorContent {
        if (map.isEmpty()) return this
        var next = nextId
        val out = ArrayList<VObject>(objects.size + 4)
        for (o in objects) {
            val pieces = map[o.id]
            if (pieces == null) { out += o; continue }
            pieces.forEachIndexed { i, piece -> out += if (i == 0) piece.withId(o.id) else piece.withId(next++) }
        }
        return copy(objects = out, nextId = next)
    }

    /** Approximate retained bytes (points dominate), for undo history bounds. */
    fun approxBytes(): Long {
        var b = 64L
        for (o in objects) {
            b += 96L
            when (o) {
                is VStroke -> b += o.points.size * 12L + o.copies.size * 52L
                is VPath -> {
                    for (s in o.subpaths) b += s.anchors.size * 48L
                    o.spline?.let { b += it.points.size * 32L }
                }
                is VShape -> b += (o.shape.points?.size ?: 0) * 40L
            }
        }
        return b
    }

    companion object {
        val EMPTY = VectorContent()
    }
}

/** One object of a vector layer. */
@Serializable
sealed class VObject {
    abstract val id: Long
    /** Object opacity 0..1 (applied as a whole, not per dab or per sub-path). */
    abstract val opacity: Float

    /** This object with another [id]. */
    abstract fun withId(id: Long): VObject
}

/** A freehand brush stroke: replayed by StrokeRaster with the exact input the live stroke got. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
@SerialName("stroke")
data class VStroke(
    override val id: Long,
    override val opacity: Float = 1f,
    /** Sanitized copy of the brush at stroke start (PAINT kinds only). */
    val preset: BrushPreset,
    val color: Int,
    val seed: Long,
    val stylus: Boolean,
    /** Every point fed to the stroke after stroke assist (down, moves, up), RAW pressure (Stroke.pressureOf is applied at replay). */
    val points: PackedPoints,
    /** Transforms multiply it by sqrt|det|. */
    val sizeScale: Float = 1f,
    /** False on ends cut by the partial eraser (they lose their finger taper). */
    val taperIn: Boolean = true,
    val taperOut: Boolean = true,
    /**
     * v1.7 (item 18): the symmetry maps at stroke start (row-major 3×3 each, the identity first);
     * empty = no symmetry. The stroke paints every dab through each map (`DabMapping`: position
     * mapped, size × √|det J| at the dab, the tip turned and mirrored) into ONE buffer with
     * [seed]; [StrokeCopies] gives the readers the union over the maps. Sanitized on read
     * (`VectorCodec`): at most [StrokeCopies.MAX] maps, each finite and invertible. Never written
     * when empty (I13), and compared by content ([equals]).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val copies: List<FloatArray> = emptyList(),
) : VObject() {
    override fun withId(id: Long): VObject = copy(id = id)

    /** As the generated one, but [copies] compared by content (v1.6 strokes, without copies, compare as before). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is VStroke) return false
        return id == other.id && opacity.compareTo(other.opacity) == 0 && preset == other.preset && color == other.color &&
            seed == other.seed && stylus == other.stylus && points == other.points && sizeScale.compareTo(other.sizeScale) == 0 &&
            taperIn == other.taperIn && taperOut == other.taperOut && StrokeCopies.sameMaps(copies, other.copies)
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

/**
 * Curves, polylines, imported SVG paths, traced fills. Handles are OFFSETS from the anchor (as CurveAnchor); null = automatic.
 *
 * v1.6: [spline] keeps the control points of a Path-tool curve (a NURBS / B-spline, like a
 * Blender path). Everything that reads a path (renderer, hit tests, eraser, snapping, export,
 * v1.5) keeps reading [subpaths], which then hold EXACTLY ONE subpath: the spline's Bézier form
 * (`SplineBezier.toSubpath(spline)` within 0.01 px, invariant I9). An edit that changes
 * [subpaths] any other way clears [spline] in the same step; the Path tool re-opens a spline
 * only after checking I9 (else the path is a plain Bézier path). Affine transforms map it
 * (`VectorOps.mapPath`), homographies and subdivision drop it.
 */
@Serializable
@SerialName("path")
data class VPath(
    override val id: Long,
    override val opacity: Float = 1f,
    val subpaths: List<VSubpath>,
    val tension: Float = 0f,
    val polyline: Boolean = false,
    val fillRule: VFillRule = VFillRule.NONZERO,
    val fill: VPaint? = null,
    val stroke: VStrokeStyle? = null,
    /**
     * v1.6: the Path tool's control points (see the class docs); null = a plain Bézier path.
     * Null is never written (I8): a plain path encodes exactly as in v1.5, although the codecs
     * write defaults (`.vec` files, the SVG / PDF payload).
     */
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val spline: VSpline? = null,
) : VObject() {
    /** Only single-subpath paths can be reopened in the Curve tool. */
    val isCurveEditable: Boolean get() = subpaths.size == 1

    override fun withId(id: Long): VObject = copy(id = id)
}

@Serializable
data class VSubpath(val anchors: List<VAnchor>, val closed: Boolean = false)

/** One anchor of a [VPath]: handle offsets [inX]/[inY], [outX]/[outY] (null = automatic). */
@Serializable
data class VAnchor(
    val x: Float,
    val y: Float,
    val sharp: Boolean = false,
    val inX: Float? = null,
    val inY: Float? = null,
    val outX: Float? = null,
    val outY: Float? = null,
    /** 0..3 thickness factor (§4.5). */
    val width: Float = 1f,
)

/**
 * One control point of a [VSpline] (v1.6): document px; [weight] pulls the curve towards the
 * point (rational B-spline weight, 0.1..10, 1 = plain B-spline); [width] is the thickness factor
 * 0..3 (like [VAnchor.width], blended along the curve).
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class VSplinePoint(
    val x: Float,
    val y: Float,
    val weight: Float = 1f,
    val width: Float = 1f,
    /**
     * v1.7 (item 4): a corner; it ends one clamped piece and starts the next (still one
     * subpath). Cleared on the two ends of an open spline ([VSpline.sanitized]). Never written
     * when false (I13).
     */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val sharp: Boolean = false,
)

/**
 * The control points of a Path-tool curve (v1.6, §3.2): a NURBS / B-spline of [order] (2..6;
 * the effective order is `min(order, points.size)`, see [effectiveOrder]). An open curve with
 * [endpoint] uses clamped knots, so it touches its first and last points; [cyclic] closes it
 * smoothly (periodic knots; [endpoint] is ignored). The geometry (evaluation, Bézier conversion)
 * lives in `tools/vector/spline`; [VPath.subpaths] holds its Bézier form (I9).
 */
@Serializable
data class VSpline(
    val points: List<VSplinePoint>,
    /** 2..6; effective order = min(order, points.size). */
    val order: Int = 4,
    /** The open curve touches its first and last points (clamped knots). */
    val endpoint: Boolean = true,
    /** Periodic: the curve closes smoothly; [endpoint] is ignored. */
    val cyclic: Boolean = false,
) {
    /** The order actually used: [order] limited by the number of points (at least 1). */
    val effectiveOrder: Int get() = minOf(order, points.size).coerceAtLeast(1)

    /**
     * Image under the affine 3x3 row-major matrix [m] (`m[6] == m[7] == 0`; a non-unit `m[8]`
     * divides as a homogeneous coordinate). Weights and widths are unchanged: NURBS are
     * affine-invariant, so the mapped spline's curve is the mapped curve.
     */
    fun mapped(m: FloatArray): VSpline {
        val w = if (m.size >= 9 && m[8] != 0f) m[8] else 1f
        return copy(points = points.map { p ->
            val x = m[0] * p.x + m[1] * p.y + m[2]
            val y = m[3] * p.x + m[4] * p.y + m[5]
            if (w == 1f) p.copy(x = x, y = y) else p.copy(x = x / w, y = y / w)
        })
    }

    /**
     * Usable numbers only (damaged or crafted data): order in [MIN_ORDER]..[MAX_ORDER], points
     * with a non-finite coordinate dropped, weights in [MIN_WEIGHT]..[MAX_WEIGHT] (non-finite = 1),
     * widths in 0..[MAX_WIDTH] (non-finite = 1), at most [MAX_POINTS] points. v1.7: the two
     * ends of an open spline are never [VSplinePoint.sharp] (a corner joins two pieces). This
     * instance when nothing changes.
     */
    fun sanitized(): VSpline {
        val o = order.coerceIn(MIN_ORDER, MAX_ORDER)
        var changed = o != order || points.size > MAX_POINTS
        val out = ArrayList<VSplinePoint>(minOf(points.size, MAX_POINTS))
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
        return if (!changed) this else VSpline(out, o, endpoint, cyclic)
    }

    companion object {
        const val MIN_ORDER = 2
        const val MAX_ORDER = 6
        const val DEFAULT_ORDER = 4
        const val MIN_WEIGHT = 0.1f
        const val MAX_WEIGHT = 10f
        const val MAX_WIDTH = 3f
        const val MAX_POINTS = 2000
        /** Coordinates far beyond any canvas are damaged data. */
        const val MAX_COORD = 1_000_000f
    }
}

/** How a path is filled or stroked. Colors are ARGB (non-premultiplied). */
@Serializable
sealed class VPaint {
    @Serializable
    @SerialName("solid")
    data class Solid(val color: Int) : VPaint()

    @Serializable
    @SerialName("linear")
    data class Linear(val x0: Float, val y0: Float, val x1: Float, val y1: Float, val stops: List<VStop>) : VPaint()

    /** [matrix] = 6 affine values (gradientTransform: a, b, c, d, e, f as in SVG), null = identity. */
    @Serializable
    @SerialName("radial")
    data class Radial(val cx: Float, val cy: Float, val r: Float, val stops: List<VStop>, val matrix: List<Float>? = null) : VPaint()
}

@Serializable
data class VStop(val offset: Float, val color: Int)

@Serializable
enum class VFillRule { NONZERO, EVENODD }

@Serializable
enum class VStrokeKind { PLAIN, BRUSH }

/** The outline of a [VPath]: a plain line of [width], or a painting tool's brush along it. */
@Serializable
data class VStrokeStyle(
    val kind: VStrokeKind = VStrokeKind.PLAIN,
    val color: Int,
    val width: Float,
    val cap: LineCapStyle = LineCapStyle.ROUND,
    val join: JoinStyle = JoinStyle.ROUND,
    val miter: Float = 4f,
    val brushTool: ToolId? = null,
    val brush: BrushPreset? = null,
    val seed: Long = 0,
    val taperPercent: Float = 0f,
)

/** A shape of the Shape tool as an object of a vector layer. */
@Serializable
@SerialName("shape")
data class VShape(
    override val id: Long,
    override val opacity: Float = 1f,
    val shape: ShapeObject,
    val seed: Long = 0,
) : VObject() {
    override fun withId(id: Long): VObject = copy(id = id)
}
