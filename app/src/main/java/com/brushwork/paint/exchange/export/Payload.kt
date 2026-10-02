package com.brushwork.paint.exchange.export

import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.vector.VectorContent
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.zip.DataFormatException
import java.util.zip.Deflater
import java.util.zip.Inflater

/**
 * The Brushwork data embedded in exported SVG and PDF files (v1.5 §4.10): everything needed to
 * restore the artwork's layers exactly when the file is opened in Brushwork again. Pixels are
 * not stored here: [PayloadLayer.imageRef] / [PayloadLayer.maskRef] point at images the file
 * already holds (the SVG element id of an image, the PDF object number of an image XObject), and
 * vector layers are rendered again from their objects. Pure Kotlin.
 */
@Serializable
data class BrushworkPayload(
    val version: Int = Payload.VERSION,
    val width: Int,
    val height: Int,
    val dpi: Float = 350f,
    val colorMode: ColorMode = ColorMode.RGB,
    /** Index (bottom = 0) of the active layer when exported. */
    val activeLayer: Int = 0,
    /** Bottom first, hidden layers included. */
    val layers: List<PayloadLayer> = emptyList(),
)

/** What kind of layer a [PayloadLayer] restores. */
@Serializable
enum class PayloadKind { RASTER, VECTOR, TEXT, SHAPE, ADJUSTMENT }

/** An integer rectangle (document px). */
@Serializable
data class PayloadRect(val left: Int, val top: Int, val width: Int, val height: Int) {
    val right: Int get() = left + width
    val bottom: Int get() = top + height
}

/**
 * One layer of a [BrushworkPayload]: its properties and editable data, and where its pixels and
 * mask are. A layer without [imageRef] is empty (or, for vector layers, rendered from [vector]).
 * A mask is [maskFill] (opaque gray ARGB) everywhere outside [maskRect].
 */
@Serializable
data class PayloadLayer(
    val id: Long,
    val props: LayerProps,
    val kind: PayloadKind = PayloadKind.RASTER,
    val imageRef: String? = null,
    val imageRect: PayloadRect? = null,
    val hasMask: Boolean = false,
    val maskRef: String? = null,
    val maskRect: PayloadRect? = null,
    val maskFill: Int = -1,
    val vector: VectorContent? = null,
    val textData: String? = null,
    val shapeData: String? = null,
    val maskSpec: MaskSpec? = null,
    val adjustment: AdjustmentSpec? = null,
)

/** JSON / deflate / base64 forms of a [BrushworkPayload]. Thread-safe. */
object Payload {
    const val VERSION = 1

    /** XML namespace of the payload elements in SVG files. */
    const val SVG_NAMESPACE = "https://brushwork.app/ns/exchange/1"

    /** Name of the embedded file in PDF files. */
    const val PDF_FILE_NAME = "brushwork.json"

    /** Largest inflated payload accepted (a damaged or hostile file must not exhaust memory). */
    private const val MAX_JSON_BYTES = 256L shl 20

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** UTF-8 JSON of [p]. */
    fun toJson(p: BrushworkPayload): ByteArray = json.encodeToString(BrushworkPayload.serializer(), p).toByteArray(Charsets.UTF_8)

    /** The payload in [bytes] (UTF-8 JSON); throws [IOException] when it can't be read. */
    fun fromJson(bytes: ByteArray): BrushworkPayload = try {
        json.decodeFromString(BrushworkPayload.serializer(), String(bytes, Charsets.UTF_8)).also { check(it) }
    } catch (e: IOException) {
        throw e
    } catch (e: Exception) {
        throw IOException("The Brushwork data can't be read (${e.message ?: e.javaClass.simpleName})", e)
    }

    /** zlib (deflate) of the JSON of [p]. */
    fun deflated(p: BrushworkPayload): ByteArray = deflate(toJson(p))

    /** The payload stored as zlib data in [bytes]. */
    fun fromDeflated(bytes: ByteArray): BrushworkPayload = fromJson(inflate(bytes, MAX_JSON_BYTES))

    /** Base64 of [deflated] (the SVG `<bw:payload>` text). */
    fun toBase64(p: BrushworkPayload): String = Base64.getEncoder().encodeToString(deflated(p))

    /** The payload of a `<bw:payload>` element's text (whitespace allowed). */
    fun fromBase64(text: String): BrushworkPayload {
        val clean = text.filterNot { it.isWhitespace() }
        val bytes = try {
            Base64.getDecoder().decode(clean)
        } catch (e: IllegalArgumentException) {
            throw IOException("The Brushwork data is damaged", e)
        }
        return fromDeflated(bytes)
    }

    /** Sanity limits of a decoded payload (a damaged file must not create huge documents). */
    private fun check(p: BrushworkPayload) {
        if (p.width !in 1..MAX_SIDE || p.height !in 1..MAX_SIDE) throw IOException("The Brushwork data has an invalid canvas size")
        if (p.layers.size > MAX_LAYERS) throw IOException("The Brushwork data has too many layers")
    }

    private const val MAX_SIDE = 100_000
    private const val MAX_LAYERS = 1000

    /** zlib-compresses [data] (level 6). */
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

    /** Inflates zlib [data]; at most [limit] bytes (else [IOException]). */
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
