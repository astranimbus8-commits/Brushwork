package com.brushwork.paint.compat19

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlin.math.abs

/*
 * v1.10 G2 (compat19 kit): a FROZEN copy of v1.9.0's array spec, its look and its JSON settings
 * (`model/ArrayModel.kt`, `model/ArrayCodec.kt` at tag v1.9.0), in test sources only: the `array`
 * field of a `project.json` layer entry as v1.9 writes and reads it. A3 froze it in
 * `v110/a3/ArraySpecV19.kt` until this kit landed; it moved here unchanged except that the guide
 * and the tint stops are the kit's [VSubpathV19] and [VStopV19]. Every serialized property, its
 * order, default and `@EncodeDefault` is v1.9's. The source container (unchanged, VERSION 1),
 * `ArraySpec.mapped` and `resolved` (which needs `ArrayLayout`) are not copied. Never change it:
 * it is what v1.9 is.
 */

/** v1.9's `ArrayMode`. */
@Serializable
enum class ArrayModeV19 { LINE, CIRCLE, CURVE, TRANSFORM }

/** v1.9's `ArrayEasing`. */
@Serializable
enum class ArrayEasingV19 { LINEAR, EASE_IN, EASE_OUT, EASE_IN_OUT }

/** v1.9's `ArrayLook` (its serialized properties, [isIdentity] and [sanitized]): no color mode. */
@Serializable
data class ArrayLookV19(
    val opacityStart: Float = 1f,
    val opacityEnd: Float = 1f,
    val sizeStart: Float = 1f,
    val sizeEnd: Float = 1f,
    val turnStart: Float = 0f,
    val turnEnd: Float = 0f,
    val easing: ArrayEasingV19 = ArrayEasingV19.LINEAR,
    val tint: List<VStopV19> = emptyList(),
    val tintStrength: Float = 0f,
) {
    /** v1.9's `ArrayLook.isIdentity`. */
    val isIdentity: Boolean
        get() = opacityStart == 1f && opacityEnd == 1f && sizeStart == 1f && sizeEnd == 1f &&
            turnStart == 0f && turnEnd == 0f && (tint.isEmpty() || tintStrength == 0f)

    /** v1.9's `ArrayLook.sanitized`. */
    fun sanitized(): ArrayLookV19 {
        fun f(v: Float, default: Float, min: Float, max: Float) = if (v.isFinite()) v.coerceIn(min, max) else default
        val stops = tint.filter { it.offset.isFinite() }
            .map { if (it.offset in 0f..1f) it else it.copy(offset = it.offset.coerceIn(0f, 1f)) }
            .sortedBy { it.offset }
            .take(MAX_STOPS)
        val n = ArrayLookV19(
            opacityStart = f(opacityStart, 1f, 0f, 1f),
            opacityEnd = f(opacityEnd, 1f, 0f, 1f),
            sizeStart = f(sizeStart, 1f, ArraySpecV19.MIN_SCALE, ArraySpecV19.MAX_SCALE),
            sizeEnd = f(sizeEnd, 1f, ArraySpecV19.MIN_SCALE, ArraySpecV19.MAX_SCALE),
            turnStart = f(turnStart, 0f, -MAX_TURN, MAX_TURN),
            turnEnd = f(turnEnd, 0f, -MAX_TURN, MAX_TURN),
            easing = easing,
            tint = if (stops.size < 2) emptyList() else stops,
            tintStrength = f(tintStrength, 0f, 0f, 1f),
        )
        return if (n == this) this else n
    }

    companion object {
        const val MAX_STOPS = 8
        private const val MAX_TURN = 3600f
    }
}

/** v1.9's `ArraySpec` (its serialized properties and [sanitized]): no `hideSource`. */
@Serializable
data class ArraySpecV19(
    val mode: ArrayModeV19 = ArrayModeV19.LINE,
    val count: Int = 3,
    val relativeX: Float = 1f,
    val relativeY: Float = 0f,
    val constantX: Float = 0f,
    val constantY: Float = 0f,
    val centerX: Float? = null,
    val centerY: Float? = null,
    val sweepDeg: Float = 360f,
    val rotateCopies: Boolean = true,
    val guide: VSubpathV19? = null,
    val spacing: Float = 0f,
    val alignToCurve: Boolean = true,
    val moveX: Float = 100f,
    val moveY: Float = 0f,
    val turnDeg: Float = 30f,
    val scale: Float = 1f,
    val pivotX: Float? = null,
    val pivotY: Float? = null,
    val editingSource: Boolean = false,
    @OptIn(ExperimentalSerializationApi::class)
    @EncodeDefault(EncodeDefault.Mode.NEVER)
    val look: ArrayLookV19? = null,
) {
    /** v1.9's `ArraySpec.sanitized`: usable values only; an identity look becomes null; this instance when nothing changes. */
    fun sanitized(): ArraySpecV19 {
        fun num(v: Float, default: Float) = if (v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else default
        fun opt(v: Float?) = if (v != null && v.isFinite()) v.coerceIn(-MAX_COORD, MAX_COORD) else null
        val n = ArraySpecV19(
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
            look = look?.sanitized()?.takeUnless { it.isIdentity },
        )
        return if (n == this) this else n
    }

    companion object {
        const val MAX_COUNT = 200
        const val MAX_GUIDE_ANCHORS = 64
        const val MIN_SCALE = 0.05f
        const val MAX_SCALE = 20f
        const val MAX_COORD = 1_000_000f
        private const val MAX_TURN = 3600f
        private val DEFAULT = ArraySpecV19()

        private fun sanitizedGuide(g: VSubpathV19): VSubpathV19 {
            fun ok(v: Float?) = v == null || (v.isFinite() && abs(v) <= MAX_COORD)
            var changed = g.anchors.size > MAX_GUIDE_ANCHORS
            val out = ArrayList<VAnchorV19>(minOf(g.anchors.size, MAX_GUIDE_ANCHORS))
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

/** v1.9's `ArrayCodec` spec JSON (`encodeSpec` / `decodeSpec`); the container is not copied. */
object ArrayCodecV19 {
    /** v1.9's container version (the container itself is not copied). */
    const val VERSION = 1

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** v1.9's `encodeSpec`: a look that draws as none is never written (I13b). */
    fun encodeSpec(s: ArraySpecV19): String =
        json.encodeToString(ArraySpecV19.serializer(), if (s.look?.sanitized()?.isIdentity == true) s.copy(look = null) else s)

    /** The spec in [s] as v1.9 reads it (sanitized), or null when [s] is null or v1.9 can't read it. */
    fun decodeSpec(s: String?): ArraySpecV19? {
        if (s == null) return null
        return try {
            json.decodeFromString(ArraySpecV19.serializer(), s).sanitized()
        } catch (e: Exception) {
            null
        }
    }
}
