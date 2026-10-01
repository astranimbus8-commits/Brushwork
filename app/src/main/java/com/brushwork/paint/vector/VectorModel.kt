package com.brushwork.paint.vector

import com.brushwork.paint.brush.BrushPreset
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.ShapeObject
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
                is VStroke -> b += o.points.size * 12L
                is VPath -> for (s in o.subpaths) b += s.anchors.size * 48L
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
) : VObject() {
    override fun withId(id: Long): VObject = copy(id = id)
}

/** Curves, polylines, imported SVG paths, traced fills. Handles are OFFSETS from the anchor (as CurveAnchor); null = automatic. */
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
