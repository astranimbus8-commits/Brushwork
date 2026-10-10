package com.brushwork.paint.compat17

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
 * v1.8 F0: a FROZEN copy of v1.7's array spec and its JSON (`model/ArrayModel.kt`,
 * `model/ArrayCodec.kt` at 885793b4), in test sources only: the `array` field of a
 * `project.json` layer entry as v1.7 writes and reads it (v1.7 drops v1.8's `look`). The source
 * container (`array_<id>_r<rev>.bin`, VERSION 1, unchanged in v1.8) and `ArraySpec.resolved`
 * (which needs `ArrayLayout`) are not copied. Never change it.
 */

/** v1.7's `ArrayMode`. */
@Serializable
enum class ArrayModeV17 { LINE, CIRCLE, CURVE, TRANSFORM }

/** v1.7's `ArraySpec` (its serialized properties, [sanitized] and [mapped]). */
@Serializable
data class ArraySpecV17(
    val mode: ArrayModeV17 = ArrayModeV17.LINE,
    val count: Int = 3,
    val relativeX: Float = 1f,
    val relativeY: Float = 0f,
    val constantX: Float = 0f,
    val constantY: Float = 0f,
    val centerX: Float? = null,
    val centerY: Float? = null,
    val sweepDeg: Float = 360f,
    val rotateCopies: Boolean = true,
    val guide: VSubpathV17? = null,
    val spacing: Float = 0f,
    val alignToCurve: Boolean = true,
    val moveX: Float = 100f,
    val moveY: Float = 0f,
    val turnDeg: Float = 30f,
    val scale: Float = 1f,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val editingSource: Boolean = false,
) {
    /** v1.7's `ArraySpec.sanitized`: usable values only; this instance when nothing changes. */
    fun sanitized(): ArraySpecV17 {
        fun num(v: Float, default: Float) = if (v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else default
        fun opt(v: Float?) = if (v != null && v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else null
        val n = ArraySpecV17(
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

    /** v1.7's `ArraySpec.mapped`: the spec under the affine document map [m] (row-major 3×3). */
    fun mapped(m: FloatArray): ArraySpecV17 {
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

    companion object {
        const val MAX_COUNT = 200
        const val MAX_GUIDE_ANCHORS = 64
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
        const val MAX_COORD = 1_000_000f
        private const val MAX_TURN = 3600f
        private val DEFAULT = ArraySpecV17()

        private fun sanitizedGuide(g: VSubpathV17): VSubpathV17 {
            fun ok(v: Float?) = v == null || (v.isFinite() && abs(v) <= MAX_COORD)
            var changed = g.anchors.size > MAX_GUIDE_ANCHORS
            val out = ArrayList<VAnchorV17>(minOf(g.anchors.size, MAX_GUIDE_ANCHORS))
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

/** v1.7's `ArrayCodec` spec JSON (`encodeSpec` / `decodeSpec`). */
object ArrayCodecV17 {
    /** v1.7's container version (the container itself is not copied). */
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encodeSpec(s: ArraySpecV17): String = json.encodeToString(ArraySpecV17.serializer(), s)

    /** The spec in [s] as v1.7 reads it (sanitized), or null when [s] is null or v1.7 can't read it. */
    fun decodeSpec(s: String?): ArraySpecV17? {
        if (s == null) return null
        return try {
            json.decodeFromString(ArraySpecV17.serializer(), s).sanitized()
        } catch (e: Exception) {
            null
        }
    }
}
