package com.brushwork.paint.model

import android.graphics.Bitmap
import android.graphics.RectF
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VSubpath
import kotlinx.serialization.Serializable
import kotlin.math.abs

/** v1.7 (item 3): how an array places its copies (Blender's Array modifier, plus Circle). */
@Serializable
enum class ArrayMode { LINE, CIRCLE, CURVE, TRANSFORM }

/**
 * v1.7 (item 3, I14): a live array's settings. Lengths and positions are DOCUMENT PIXELS; the
 * placement itself is [ArrayLayout]. Stored as the `array` JSON of a `project.json` layer entry
 * ([ArrayCodec.encodeSpec]) and carried in `LayerData` through [LayerArray].
 */
@Serializable
data class ArraySpec(
    val mode: ArrayMode = ArrayMode.LINE,
    /** Instances, the source included (Blender "Count"): 1..[MAX_COUNT]. */
    val count: Int = 3,
    /** LINE: Blender's relative offset per copy, × the source's CURRENT width / height. */
    val relativeX: Float = 1f,
    val relativeY: Float = 0f,
    /** LINE: constant offset per copy, px (added to the relative one). */
    val constantX: Float = 0f,
    val constantY: Float = 0f,
    /** CIRCLE: the centre; null = 1.2 × the source's larger side below its centre (NaN is not JSON, so nullable). */
    val centerX: Float? = null,
    val centerY: Float? = null,
    /** CIRCLE: 360° spreads the copies evenly round the circle; less spreads them from 0 to the sweep inclusive. */
    val sweepDeg: Float = 360f,
    /** CIRCLE: the copies turn with their position (off: only the position turns). */
    val rotateCopies: Boolean = true,
    /** CURVE: the guide, a snapshot in document px (never a link to a path). */
    val guide: VSubpath? = null,
    /** CURVE: arc length between copies, px; 0 = spread evenly over the guide. */
    val spacing: Float = 0f,
    /** CURVE: the copies turn with the guide's direction. */
    val alignToCurve: Boolean = true,
    /** TRANSFORM (Blender object offset): copy k = M^k, M = T(pivot)·T(move)·R(turn)·S(scale)·T(−pivot). */
    val moveX: Float = 100f,
    val moveY: Float = 0f,
    val turnDeg: Float = 30f,
    val scale: Float = 1f,
    /** TRANSFORM: null = the source's centre. */
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    /** Raster sources only: "Edit source pixels" mode (§3.3); the cache is the source alone, painting does not bake. */
    val editingSource: Boolean = false,
) {
    /**
     * Usable values only (damaged or crafted data): count 1..[MAX_COUNT]; every number finite
     * (a non-finite one takes its default, a non-finite centre or pivot coordinate becomes
     * null) and within [MAX_COORD]; sweep -360..360; spacing ≥ 0; scale 0.05..20; the guide at
     * most [MAX_GUIDE_ANCHORS] anchors with finite coordinates (handles that are not finite are
     * automatic again). This instance when nothing changes.
     *
     * [editingSource] is cleared for non-raster sources by the loader (`ArraySourceBlob` is not
     * [ArraySourceBlob.Pixels]), which knows the source kind; the spec alone does not.
     */
    fun sanitized(): ArraySpec {
        fun num(v: Float, default: Float) = if (v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else default
        fun opt(v: Float?) = if (v != null && v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else null
        val n = ArraySpec(
            mode = mode,
            count = count.coerceIn(1, MAX_COUNT),
            relativeX = num(relativeX, DEFAULT.relativeX),
            relativeY = num(relativeY, DEFAULT.relativeY),
            constantX = num(constantX, DEFAULT.constantX),
            constantY = num(constantY, DEFAULT.constantY),
            centerX = opt(centerX),
            centerY = opt(centerY),
            sweepDeg = if (sweepDeg.isFinite()) sweepDeg.coerceIn(-360f, 360f) else DEFAULT.sweepDeg,
            rotateCopies = rotateCopies,
            guide = guide?.let { sanitizedGuide(it) },
            spacing = if (spacing.isFinite()) spacing.coerceIn(0f, MAX_COORD) else 0f,
            alignToCurve = alignToCurve,
            moveX = num(moveX, DEFAULT.moveX),
            moveY = num(moveY, DEFAULT.moveY),
            turnDeg = if (turnDeg.isFinite()) turnDeg.coerceIn(-MAX_TURN, MAX_TURN) else DEFAULT.turnDeg,
            scale = if (scale.isFinite()) scale.coerceIn(MIN_SCALE, MAX_SCALE) else DEFAULT.scale,
            pivotX = opt(pivotX),
            pivotY = opt(pivotY),
            editingSource = editingSource,
        )
        return if (n == this) this else n
    }

    /**
     * The spec under a document map [m] (row-major 3×3, affine; canvas operations, the Transform
     * tool): the centre, pivot and guide are mapped as points, the constant and move offsets as
     * vectors, and a reflection reverses the sweep and the turn. Exact for similarities (moves,
     * flips, turns, uniform scales). Relative offsets are fractions of the source's size, so they
     * follow the axes: exact for maps that keep the axes (flips, quarter turns, scales), otherwise
     * as for a square source. A null centre or pivot stays null (it follows the source's new
     * bounds; exact for maps that keep the axes): a caller that needs the old placement exactly
     * under other maps fills them first ([resolved]). A non-finite or singular map returns this.
     */
    fun mapped(m: FloatArray): ArraySpec {
        if (m.size < 9 || m.any { !it.isFinite() } || m[6] != 0f || m[7] != 0f || m[8] == 0f) return this
        val w = m[8]
        val a = m[0] / w; val b = m[1] / w; val tx = m[2] / w
        val c = m[3] / w; val d = m[4] / w; val ty = m[5] / w
        val det = a * d - b * c
        if (det == 0f || !det.isFinite()) return this
        val flip = if (det < 0f) -1f else 1f
        fun px(x: Float, y: Float) = a * x + b * y + tx
        fun py(x: Float, y: Float) = c * x + d * y + ty
        fun vx(x: Float, y: Float) = a * x + b * y
        fun vy(x: Float, y: Float) = c * x + d * y
        val rowX = abs(a) + abs(b)
        val rowY = abs(c) + abs(d)
        val cx = centerX; val cy = centerY
        val qx = pivotX; val qy = pivotY
        return copy(
            relativeX = if (rowX > 0f) (a * relativeX + b * relativeY) / rowX else relativeX,
            relativeY = if (rowY > 0f) (c * relativeX + d * relativeY) / rowY else relativeY,
            constantX = vx(constantX, constantY),
            constantY = vy(constantX, constantY),
            centerX = if (cx != null && cy != null) px(cx, cy) else cx,
            centerY = if (cx != null && cy != null) py(cx, cy) else cy,
            sweepDeg = sweepDeg * flip,
            guide = guide?.let { g ->
                g.copy(anchors = g.anchors.map { an ->
                    val hin = if (an.inX != null && an.inY != null) vx(an.inX, an.inY) to vy(an.inX, an.inY) else null
                    val hout = if (an.outX != null && an.outY != null) vx(an.outX, an.outY) to vy(an.outX, an.outY) else null
                    an.copy(x = px(an.x, an.y), y = py(an.x, an.y), inX = hin?.first, inY = hin?.second, outX = hout?.first, outY = hout?.second)
                })
            },
            spacing = spacing * kotlin.math.sqrt(abs(det)),
            moveX = vx(moveX, moveY),
            moveY = vy(moveX, moveY),
            turnDeg = turnDeg * flip,
            pivotX = if (qx != null && qy != null) px(qx, qy) else qx,
            pivotY = if (qx != null && qy != null) py(qx, qy) else qy,
        )
    }

    /** This spec with a null CIRCLE centre and TRANSFORM pivot filled in from [source] (the source's current bounds). */
    fun resolved(source: RectF): ArraySpec {
        val c = ArrayLayout.center(this, source)
        val p = ArrayLayout.pivot(this, source)
        return copy(centerX = c.first, centerY = c.second, pivotX = p.first, pivotY = p.second)
    }

    companion object {
        const val MAX_COUNT = 200
        /** count × objects, vector sources. */
        const val MAX_INSTANCES = 20_000
        /** The most anchors a CURVE guide keeps. */
        const val MAX_GUIDE_ANCHORS = 64
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
        /** Coordinates and offsets far beyond any canvas are damaged data. */
        const val MAX_COORD = 1_000_000f
        private const val MAX_TURN = 3600f
        private val DEFAULT = ArraySpec()

        private fun sanitizedGuide(g: VSubpath): VSubpath {
            fun ok(v: Float?) = v == null || (v.isFinite() && abs(v) <= MAX_COORD)
            var changed = g.anchors.size > MAX_GUIDE_ANCHORS
            val out = ArrayList<VAnchor>(minOf(g.anchors.size, MAX_GUIDE_ANCHORS))
            for (a in g.anchors) {
                if (out.size >= MAX_GUIDE_ANCHORS) break
                if (!a.x.isFinite() || !a.y.isFinite() || abs(a.x) > MAX_COORD || abs(a.y) > MAX_COORD) { changed = true; continue }
                if (ok(a.inX) && ok(a.inY) && ok(a.outX) && ok(a.outY)) { out += a; continue }
                changed = true
                out += a.copy(
                    inX = a.inX.takeIf { ok(it) && ok(a.inY) }, inY = a.inY.takeIf { ok(it) && ok(a.inX) },
                    outX = a.outX.takeIf { ok(it) && ok(a.outY) }, outY = a.outY.takeIf { ok(it) && ok(a.outX) },
                )
            }
            return if (changed) g.copy(anchors = out) else g
        }
    }
}

/**
 * v1.7: a raster array's source pixels, cropped to their bounds; [left] / [top] place them in
 * the document. Immutable once published (shared by undo snapshots, never recycled while one
 * holds it).
 */
class ArrayPixels(val bitmap: Bitmap, val left: Int, val top: Int) {
    val bytes: Long get() = bitmap.width.toLong() * bitmap.height * 4
}

/**
 * v1.7 (I14): the array on a layer. Text, shape and vector sources are the layer's own data
 * (`textData`, `shapeData`, `vector`); [pixels] only for raster sources.
 */
data class LayerArray(val spec: ArraySpec, val pixels: ArrayPixels? = null) {
    fun approxBytes(): Long = pixels?.bytes ?: 0L
}
