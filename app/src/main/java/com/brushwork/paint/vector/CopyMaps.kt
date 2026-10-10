package com.brushwork.paint.vector

import kotlinx.serialization.KSerializer
import kotlinx.serialization.Serializable
import kotlinx.serialization.builtins.FloatArraySerializer
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder

/**
 * v1.8 (I15): the live symmetry copies of a VPath or a ShapeObject. Affine 3x3 maps, row-major,
 * identity first; empty = no copies. JSON: a list of 9-float lists, exactly VStroke.copies.
 *
 * Every instance is sanitized ([of]): 2..[StrokeCopies.MAX] usable affine maps, the first one the
 * exact identity, or none at all ([NONE]). Maps are compared by content, bit for bit as stored, so
 * a decoded object equals the one that was written. The arrays are this object's own copies and
 * are never changed: readers must not write into [maps].
 */
@Serializable(with = CopyMapsSerializer::class)
class CopyMaps private constructor(val maps: List<FloatArray>) {
    val isEmpty: Boolean get() = maps.isEmpty()

    /** Instances drawn: 1 without copies, else maps.size (the identity included). */
    val count: Int get() = if (maps.isEmpty()) 1 else maps.size

    /** The maps to draw with: [identity] alone when empty. */
    fun orIdentity(): List<FloatArray> = if (maps.isEmpty()) listOf(identity()) else maps

    /**
     * After an affine transform [m] of the object: m·C_k·m⁻¹ for each k (StrokeCopies.conjugated),
     * so every copy lies where [m] puts the old one. The first map stays the exact identity (its
     * conjugate is the identity up to rounding). Unchanged without copies, and for an [m] that is
     * not a usable affine map (a homography or a singular map: its caller bakes the copies).
     */
    fun conjugated(m: FloatArray): CopyMaps {
        if (maps.isEmpty() || !isAffine(m) || !StrokeCopies.isUsable(m)) return this
        val rest = StrokeCopies.conjugated(maps.subList(1, maps.size), m)
        return of(listOf(maps[0]) + rest)
    }

    override fun equals(other: Any?): Boolean = other is CopyMaps && StrokeCopies.sameMaps(maps, other.maps)
    override fun hashCode(): Int = maps.fold(1) { h, m -> 31 * h + m.contentHashCode() }
    override fun toString(): String = "CopyMaps(${maps.size})"

    companion object {
        val NONE: CopyMaps = CopyMaps(emptyList())

        /**
         * Sanitized: drops non-finite, singular (StrokeCopies.isUsable) and non-affine maps
         * (m[6] != 0, m[7] != 0 or m[8] != 1); puts the identity first; keeps at most
         * StrokeCopies.MAX (256); NONE below 2 maps.
         *
         * "Identity first": the first exact identity in [maps] moves to the front, and further
         * exact identities are dropped (a second copy on the source draws nothing new); when
         * [maps] holds none, the identity is put in front of them. The order of the other maps
         * is kept. Idempotent: `of(of(x).maps) == of(x)`. The arrays are copied.
         */
        fun of(maps: List<FloatArray>): CopyMaps {
            if (maps.isEmpty()) return NONE
            val others = ArrayList<FloatArray>(minOf(maps.size, StrokeCopies.MAX))
            for (m in maps) {
                if (!isAffine(m) || !StrokeCopies.isUsable(m) || isIdentity(m)) continue
                others += m.copyOf()
                if (others.size >= StrokeCopies.MAX - 1) break
            }
            if (others.isEmpty()) return NONE
            return CopyMaps(listOf(identity()) + others)
        }

        private fun identity(): FloatArray = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

        private fun isAffine(m: FloatArray): Boolean = m.size == 9 && m[6] == 0f && m[7] == 0f && m[8] == 1f

        private fun isIdentity(m: FloatArray): Boolean =
            m[0] == 1f && m[1] == 0f && m[2] == 0f && m[3] == 0f && m[4] == 1f && m[5] == 0f && m[6] == 0f && m[7] == 0f && m[8] == 1f
    }
}

/**
 * The JSON of [CopyMaps]: a list of 9-float lists, as `VStroke.copies` (v1.7) writes its maps.
 * Decoding goes through [CopyMaps.of], so damaged maps are dropped on read.
 */
object CopyMapsSerializer : KSerializer<CopyMaps> {
    private val delegate = ListSerializer(FloatArraySerializer())

    @OptIn(kotlinx.serialization.ExperimentalSerializationApi::class)
    override val descriptor: SerialDescriptor = SerialDescriptor("com.brushwork.paint.vector.CopyMaps", delegate.descriptor)

    override fun serialize(encoder: Encoder, value: CopyMaps) = encoder.encodeSerializableValue(delegate, value.maps)

    override fun deserialize(decoder: Decoder): CopyMaps = CopyMaps.of(decoder.decodeSerializableValue(delegate))
}
