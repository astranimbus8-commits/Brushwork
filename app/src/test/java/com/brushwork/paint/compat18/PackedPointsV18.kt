package com.brushwork.paint.compat18

import kotlinx.serialization.KSerializer
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.SerializationException
import kotlinx.serialization.descriptors.SerialDescriptor
import kotlinx.serialization.encoding.Decoder
import kotlinx.serialization.encoding.Encoder
import java.nio.ByteBuffer
import java.nio.ByteOrder
import java.util.Base64

/*
 * v1.9 F0: a FROZEN copy of v1.8's stroke points (`core/PackedPoints.kt` at 600582d9), in test
 * sources only, so the frozen vector and payload models ([VStrokeV18], [MaskStrokeV18]) read
 * points as v1.8 does even if the app's class changes. Every serialized detail is v1.8's: the
 * JSON `{"n": N, "d": base64(little-endian float32 x, then y, then p)}`, the size check and the
 * errors; what the codecs do not use (bounds, mapping, slicing) is left out. Never change it.
 */

/** v1.8's `PackedPoints`: input points with their raw pressures, in three parallel arrays. */
@Serializable(with = PackedPointsV18Serializer::class)
class PackedPointsV18(val x: FloatArray, val y: FloatArray, val p: FloatArray) {
    init {
        require(x.size == y.size && y.size == p.size) { "PackedPoints arrays differ in size (${x.size}, ${y.size}, ${p.size})" }
    }

    val size: Int get() = x.size

    /** Content equality (bit-exact floats, as stored). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackedPointsV18) return false
        return x.contentEquals(other.x) && y.contentEquals(other.y) && p.contentEquals(other.p)
    }

    override fun hashCode(): Int = (x.contentHashCode() * 31 + y.contentHashCode()) * 31 + p.contentHashCode()

    override fun toString(): String = "PackedPointsV18(size=$size)"

    companion object {
        /** v1.8's `PackedPoints.toBytes`: little-endian float32 bytes of x, then y, then p. */
        fun toBytes(v: PackedPointsV18): ByteArray {
            val n = v.size
            val buf = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) buf.putFloat(v.x[i])
            for (i in 0 until n) buf.putFloat(v.y[i])
            for (i in 0 until n) buf.putFloat(v.p[i])
            return buf.array()
        }

        /** v1.8's `PackedPoints.fromBytes`; throws [IllegalArgumentException] when [bytes] doesn't hold [n] points. */
        fun fromBytes(n: Int, bytes: ByteArray): PackedPointsV18 {
            require(n >= 0 && bytes.size.toLong() == n.toLong() * 12L) { "PackedPoints data holds ${bytes.size} bytes, not $n points" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val x = FloatArray(n) { buf.float }
            val y = FloatArray(n) { buf.float }
            val p = FloatArray(n) { buf.float }
            return PackedPointsV18(x, y, p)
        }
    }
}

/** v1.8's `PackedPointsSerializer`: floats as raw bits, so the round trip is exact (NaN payloads too). */
object PackedPointsV18Serializer : KSerializer<PackedPointsV18> {
    @Serializable
    @SerialName("PackedPoints")
    private class Surrogate(val n: Int, val d: String)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PackedPointsV18) {
        val d = Base64.getEncoder().encodeToString(PackedPointsV18.toBytes(value))
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.size, d))
    }

    override fun deserialize(decoder: Decoder): PackedPointsV18 {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        val bytes = try {
            Base64.getDecoder().decode(s.d)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("PackedPoints data is not base64", e)
        }
        return try {
            PackedPointsV18.fromBytes(s.n, bytes)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message ?: "PackedPoints data is damaged", e)
        }
    }
}
