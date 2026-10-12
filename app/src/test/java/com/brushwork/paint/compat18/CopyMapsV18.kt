package com.brushwork.paint.compat18

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import kotlin.math.abs

/*
 * v1.9 F0: FROZEN copies of v1.8's live symmetry copies (`vector/CopyMaps.kt`) and of the part of
 * `vector/StrokeCopies.kt` the codecs use (at 600582d9), in test sources only: which maps v1.8
 * keeps on read and how it compares them. Never change them.
 */

/** v1.8's `StrokeCopies` (what the codecs use). */
object StrokeCopiesV18 {
    /** Most maps a stroke keeps. */
    const val MAX = 256

    private const val MIN_DET = 1e-12

    private const val MAX_COEF = 1e7f

    /** v1.8's `StrokeCopies.isUsable`: 9 finite values of moderate size, invertible. */
    fun isUsable(m: FloatArray): Boolean {
        if (m.size != 9) return false
        for (v in m) if (!v.isFinite() || abs(v) > MAX_COEF) return false
        val d = det(m)
        return d.isFinite() && abs(d) >= MIN_DET
    }

    /** v1.8's `StrokeCopies.sanitized`: unusable maps dropped, at most [MAX] kept; the same instance when nothing changes. */
    fun sanitized(copies: List<FloatArray>): List<FloatArray> {
        if (copies.isEmpty()) return copies
        if (copies.size <= MAX && copies.all { isUsable(it) }) return copies
        return copies.filter { isUsable(it) }.take(MAX)
    }

    /** v1.8's `StrokeCopies.sameMaps`: the same maps, bit-exact as stored. */
    fun sameMaps(a: List<FloatArray>, b: List<FloatArray>): Boolean {
        if (a === b) return true
        if (a.size != b.size) return false
        for (i in a.indices) if (!a[i].contentEquals(b[i])) return false
        return true
    }

    private fun det(m: FloatArray): Double {
        val a = m.map { it.toDouble() }
        return a[0] * (a[4] * a[8] - a[5] * a[7]) - a[1] * (a[3] * a[8] - a[5] * a[6]) + a[2] * (a[3] * a[7] - a[4] * a[6])
    }
}

/** v1.8's `CopyMaps`: identity first, 2..256 usable affine maps, or none ([NONE]); sanitized by [of]. */
@Serializable(with = CopyMapsV18Serializer::class)
class CopyMapsV18 private constructor(val maps: List<FloatArray>) {
    override fun equals(other: Any?): Boolean = other is CopyMapsV18 && StrokeCopiesV18.sameMaps(maps, other.maps)
    override fun hashCode(): Int = maps.fold(1) { h, m -> 31 * h + m.contentHashCode() }
    override fun toString(): String = "CopyMapsV18(${maps.size})"

    companion object {
        val NONE: CopyMapsV18 = CopyMapsV18(emptyList())

        /** v1.8's `CopyMaps.of`. */
        fun of(maps: List<FloatArray>): CopyMapsV18 {
            if (maps.isEmpty()) return NONE
            val others = ArrayList<FloatArray>(minOf(maps.size, StrokeCopiesV18.MAX))
            for (m in maps) {
                if (!isAffine(m) || !StrokeCopiesV18.isUsable(m) || isIdentity(m)) continue
                others += m.copyOf()
                if (others.size >= StrokeCopiesV18.MAX - 1) break
            }
            if (others.isEmpty()) return NONE
            return CopyMapsV18(listOf(identity()) + others)
        }

        private fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        private fun isAffine(m: FloatArray): Boolean = m.size == 9 && m[6] == 0f && m[7] == 0f && m[8] == 1f

        private fun isIdentity(m: FloatArray): Boolean =
            m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f && m[6] == 0f && m[7] == 0f && m[8] == 1f
    }
}

/** v1.8's `CopyMapsSerializer`: a list of 9-float lists; decoding goes through [CopyMapsV18.of]. */
object CopyMapsV18Serializer : KSerializer<CopyMapsV18> {
    private val delegate = ListSerializer(FloatArraySerializer())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("com.brushwork.paint.compat18.CopyMapsV18", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: CopyMapsV18) = encoder.encodeSerializableValue(delegate, value.maps)

    override fun deserialize(decoder: Decoder): CopyMapsV18 = CopyMapsV18.of(decoder.decodeSerializableValue(delegate))
}
