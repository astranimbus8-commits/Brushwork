package com.brushwork.paint.compat18

import com.brushwork.paint.core.PackedPoints
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/*
 * v1.9 F0: a FROZEN copy of v1.8's exchange payload (`exchange/export/Payload.kt` with the mask
 * and adjustment specs of `masks/MaskModel.kt` it holds, at 600582d9), in test sources only: the
 * Brushwork data of an SVG or PDF export as v1.8 writes and reads it (unknown keys ignored, so
 * v1.8 drops v1.9's `paper`; an unknown enum value takes the field's default). A vector layer is
 * written with [VectorCodecV18.forWriting], as v1.8 does. Never change it: it is what v1.8 is.
 */

/** v1.8's `BrushworkPayload` (no `paper`). */
@Serializable
data class BrushworkPayloadV18(
    val version: Int = 1,
    val width: Int,
    val height: Int,
    val dpi: Float = 350f,
    val colorMode: ColorModeV18 = ColorModeV18.RGB,
    val activeLayer: Int = 0,
    val layers: List<PayloadLayerV18> = emptyList(),
)

/** v1.8's `PayloadKind`. */
@Serializable
enum class PayloadKindV18 { RASTER, VECTOR, TEXT, SHAPE, ADJUSTMENT, FOLDER }

/** v1.8's `PayloadRect`. */
@Serializable
data class PayloadRectV18(val left: Int, val top: Int, val width: Int, val height: Int)

/** v1.8's `PayloadLayer`. */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class PayloadLayerV18(
    val id: Long,
    val props: LayerPropsV18,
    val kind: PayloadKindV18 = PayloadKindV18.RASTER,
    val imageRef: String? = null,
    val imageRect: PayloadRectV18? = null,
    val hasMask: Boolean = false,
    val maskRef: String? = null,
    val maskRect: PayloadRectV18? = null,
    val maskFill: Int = -1,
    val vector: VectorContentV18? = null,
    val textData: String? = null,
    val shapeData: String? = null,
    val maskSpec: MaskSpecV18? = null,
    val adjustment: AdjustmentSpecV18? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val parentId: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folder: FolderSpecV18? = null,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val folderOpen: Boolean = true,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val array: PayloadArrayV18? = null,
)

/** v1.8's `PayloadArray`. */
@Serializable
data class PayloadArrayV18(val spec: String, val source: String)

/** v1.8's `MaskSpec` (unchanged since v1.7). */
@Serializable
data class MaskSpecV18(
    val version: Int = 1,
    val startFull: Boolean = false,
    val components: List<MaskComponentV18> = emptyList(),
    val invert: Boolean = false,
    val density: Float = 1f,
    val nextId: Long = 1,
)

/** v1.8's `MaskComponent`. */
@Serializable
sealed class MaskComponentV18 {
    abstract val id: Long
    abstract val mode: MaskModeV18
    abstract val invert: Boolean
    abstract val amount: Float
    abstract val visible: Boolean
}

/** v1.8's `LinearMask`. */
@Serializable
@SerialName("linear")
data class LinearMaskV18(
    override val id: Long,
    override val mode: MaskModeV18 = MaskModeV18.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val x0: Float,
    val y0: Float,
    val x1: Float,
    val y1: Float,
) : MaskComponentV18()

/** v1.8's `RadialMask`. */
@Serializable
@SerialName("radial")
data class RadialMaskV18(
    override val id: Long,
    override val mode: MaskModeV18 = MaskModeV18.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val cx: Float,
    val cy: Float,
    val rx: Float,
    val ry: Float,
    val rotationDeg: Float = 0f,
    val feather: Float = 0.5f,
) : MaskComponentV18()

/** v1.8's `BrushMask`. */
@Serializable
@SerialName("brush")
data class BrushMaskV18(
    override val id: Long,
    override val mode: MaskModeV18 = MaskModeV18.ADD,
    override val invert: Boolean = false,
    override val amount: Float = 1f,
    override val visible: Boolean = true,
    val strokes: List<MaskStrokeV18> = emptyList(),
) : MaskComponentV18()

/** v1.8's `MaskStroke`. */
@Serializable
data class MaskStrokeV18(val erase: Boolean, val size: Float, val hardness: Float, val flow: Float, val points: PackedPoints)

/** v1.8's `MaskMode`. */
@Serializable
enum class MaskModeV18 { ADD, SUBTRACT, INTERSECT }

/** v1.8's `AdjustmentSpec`. */
@Serializable
data class AdjustmentSpecV18(
    val version: Int = 1,
    val filterId: String = "adjust.tone",
    val values: JsonObject = JsonObject(emptyMap()),
)

/** v1.8's `Payload`: JSON / deflate forms of a [BrushworkPayloadV18]. */
object PayloadV18 {
    /** 1 = v1.5; 2 = v1.7 (the layer tree and live arrays, written only when used); v1.8 keeps 2. */
    const val VERSION = 2

    private const val MAX_JSON_BYTES = 256L shl 20

    private const val MAX_SIDE = 100_000
    private const val MAX_LAYERS = 1000

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** v1.8's `Payload.writtenVersion`. */
    fun writtenVersion(p: BrushworkPayloadV18): Int = if (p.layers.any { usesV17(it) }) VERSION else 1

    private fun usesV17(l: PayloadLayerV18): Boolean =
        l.kind == PayloadKindV18.FOLDER || l.folder != null || l.parentId != 0L || !l.folderOpen || l.array != null

    /** v1.8's `Payload.toJson`: UTF-8 JSON with the written version, cogs with their fallback outline. */
    fun toJson(p: BrushworkPayloadV18): ByteArray {
        val v = writtenVersion(p)
        return json.encodeToString(BrushworkPayloadV18.serializer(), forWriting(if (p.version == v) p else p.copy(version = v))).toByteArray(Charsets.UTF_8)
    }

    private fun forWriting(p: BrushworkPayloadV18): BrushworkPayloadV18 {
        var changed = false
        val layers = p.layers.map { l ->
            val c = l.vector ?: return@map l
            val w = VectorCodecV18.forWriting(c)
            if (w === c) l else { changed = true; l.copy(vector = w) }
        }
        return if (changed) p.copy(layers = layers) else p
    }

    /** v1.8's `Payload.fromJson`: the payload in [bytes]; throws [IOException] when v1.8 can't read it. */
    fun fromJson(bytes: ByteArray): BrushworkPayloadV18 = try {
        json.decodeFromString(BrushworkPayloadV18.serializer(), String(bytes, Charsets.UTF_8)).also { check(it) }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("The Brushwork data can't be read (${e.message ?: e.javaClass.simpleName})", e)
    }

    /** v1.8's `Payload.deflated`. */
    fun deflated(p: BrushworkPayloadV18): ByteArray = deflate(toJson(p))

    /** v1.8's `Payload.fromDeflated`. */
    fun fromDeflated(bytes: ByteArray): BrushworkPayloadV18 = fromJson(inflate(bytes, MAX_JSON_BYTES))

    private fun check(p: BrushworkPayloadV18) {
        if (p.width !in 1..MAX_SIDE || p.height !in 1..MAX_SIDE) throw IOException("The Brushwork data has an invalid canvas size")
        if (p.layers.size > MAX_LAYERS) throw IOException("The Brushwork data has too many layers")
    }

    /** v1.8's `Payload.deflate` (zlib, level 6). */
    fun deflate(data: ByteArray, level: Int = 6): ByteArray {
        val d = Deflater(level)
        try {
            d.setInput(data)
            d.finish()
            val out = ByteArrayOutputStream(maxOf(64, data.size / 3))
            val buf = ByteArray(64 * 1024)
            while (!d.finished()) {
                val n = d.deflate(buf)
                out.write(buf, 0, n)
            }
            return out.toByteArray()
        } finally {
            d.end()
        }
    }

    /** v1.8's `Payload.inflate`: at most [limit] bytes (else [IOException]). */
    fun inflate(data: ByteArray, limit: Long = MAX_JSON_BYTES): ByteArray {
        val inf = Inflater()
        try {
            inf.setInput(data)
            val out = ByteArrayOutputStream(maxOf(64, minOf(data.size.toLong() * 4, limit).toInt()))
            val buf = ByteArray(64 * 1024)
            while (!inf.finished()) {
                val n = inf.inflate(buf)
                if (n == 0) {
                    if (inf.needsInput() || inf.needsDictionary()) throw IOException("The compressed data is truncated")
                }
                out.write(buf, 0, n)
                if (out.size() > limit) throw IOException("The compressed data is too large")
            }
            return out.toByteArray()
        } catch (e: DataFormatException) {
            throw IOException("The compressed data is damaged", e)
        } finally {
            inf.end()
        }
    }
}
