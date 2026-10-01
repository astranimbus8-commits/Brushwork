package com.brushwork.paint.core

import android.graphics.RectF
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

/**
 * Input points of a stroke (document px) with their RAW pressures, in three parallel arrays: a
 * long stroke costs no object per point (about 12 bytes per point in memory). Immutable by
 * convention: never write into the arrays after construction (vector content shares them).
 *
 * Stored as JSON `{"n": N, "d": base64(little-endian float32 x[0..N), y[0..N), p[0..N))}` by
 * [PackedPointsSerializer]: the round trip is exact, so a reloaded stroke replays identically.
 */
@Serializable(with = PackedPointsSerializer::class)
class PackedPoints(val x: FloatArray, val y: FloatArray, val p: FloatArray) {
    init {
        require(x.size == y.size && y.size == p.size) { "PackedPoints arrays differ in size (${x.size}, ${y.size}, ${p.size})" }
    }

    val size: Int get() = x.size

    /** Bounds of the points (no radius); empty at the first point when there is one, else (0,0,0,0). */
    fun bounds(): RectF {
        if (size == 0) return RectF()
        var l = x[0]; var t = y[0]; var r = l; var b = t
        for (i in 1 until size) {
            val px = x[i]; val py = y[i]
            if (px < l) l = px
            if (px > r) r = px
            if (py < t) t = py
            if (py > b) b = py
        }
        return RectF(l, t, r, b)
    }

    /**
     * The points mapped by [m], a 3x3 row-major matrix (affine or a homography: x and y are
     * divided by the third row). Pressures are kept.
     */
    fun mapped(m: FloatArray): PackedPoints {
        require(m.size >= 9) { "A 3x3 matrix needs 9 values" }
        val n = size
        val nx = FloatArray(n)
        val ny = FloatArray(n)
        val affine = m[6] == 0f && m[7] == 0f && m[8] == 1f
        for (i in 0 until n) {
            val px = x[i]; val py = y[i]
            val qx = m[0] * px + m[1] * py + m[2]
            val qy = m[3] * px + m[4] * py + m[5]
            if (affine) {
                nx[i] = qx; ny[i] = qy
            } else {
                val w = m[6] * px + m[7] * py + m[8]
                val iw = if (w != 0f) 1f / w else 0f
                nx[i] = qx * iw; ny[i] = qy * iw
            }
        }
        return PackedPoints(nx, ny, p.copyOf())
    }

    /** Points [from] until [until] (clamped to the valid range). */
    fun slice(from: Int, until: Int): PackedPoints {
        val a = from.coerceIn(0, size)
        val b = until.coerceIn(a, size)
        return PackedPoints(x.copyOfRange(a, b), y.copyOfRange(a, b), p.copyOfRange(a, b))
    }

    /** Content equality (bit-exact floats, as stored). */
    override fun equals(other: Any?): Boolean {
        if (this === other) return true
        if (other !is PackedPoints) return false
        return x.contentEquals(other.x) && y.contentEquals(other.y) && p.contentEquals(other.p)
    }

    override fun hashCode(): Int = (x.contentHashCode() * 31 + y.contentHashCode()) * 31 + p.contentHashCode()

    override fun toString(): String = "PackedPoints(size=$size)"

    companion object {
        val EMPTY = PackedPoints(FloatArray(0), FloatArray(0), FloatArray(0))

        /** Little-endian float32 bytes of x, then y, then p (what the JSON field `d` holds, decoded). */
        fun toBytes(v: PackedPoints): ByteArray {
            val n = v.size
            val buf = ByteBuffer.allocate(n * 12).order(ByteOrder.LITTLE_ENDIAN)
            for (i in 0 until n) buf.putFloat(v.x[i])
            for (i in 0 until n) buf.putFloat(v.y[i])
            for (i in 0 until n) buf.putFloat(v.p[i])
            return buf.array()
        }

        /** Inverse of [toBytes]; throws [IllegalArgumentException] when [bytes] doesn't hold [n] points. */
        fun fromBytes(n: Int, bytes: ByteArray): PackedPoints {
            require(n >= 0 && bytes.size.toLong() == n.toLong() * 12L) { "PackedPoints data holds ${bytes.size} bytes, not $n points" }
            val buf = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN)
            val x = FloatArray(n) { buf.float }
            val y = FloatArray(n) { buf.float }
            val p = FloatArray(n) { buf.float }
            return PackedPoints(x, y, p)
        }
    }
}

/**
 * JSON form of [PackedPoints]: `{"n": N, "d": base64(little-endian float32 x[0..N), y[0..N),
 * p[0..N))}`. Floats are written as raw bits, so the round trip is exact (NaN payloads too).
 */
object PackedPointsSerializer : KSerializer<PackedPoints> {
    @Serializable
    @SerialName("PackedPoints")
    private class Surrogate(val n: Int, val d: String)

    override val descriptor: SerialDescriptor = Surrogate.serializer().descriptor

    override fun serialize(encoder: Encoder, value: PackedPoints) {
        val d = Base64.getEncoder().encodeToString(PackedPoints.toBytes(value))
        encoder.encodeSerializableValue(Surrogate.serializer(), Surrogate(value.size, d))
    }

    override fun deserialize(decoder: Decoder): PackedPoints {
        val s = decoder.decodeSerializableValue(Surrogate.serializer())
        val bytes = try {
            Base64.getDecoder().decode(s.d)
        } catch (e: IllegalArgumentException) {
            throw SerializationException("PackedPoints data is not base64", e)
        }
        return try {
            PackedPoints.fromBytes(s.n, bytes)
        } catch (e: IllegalArgumentException) {
            throw SerializationException(e.message ?: "PackedPoints data is damaged", e)
        }
    }
}
