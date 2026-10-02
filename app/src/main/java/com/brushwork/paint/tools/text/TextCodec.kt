package com.brushwork.paint.tools.text

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a text layer stores in `Layer.textData`: the editable text object plus a format
 * [version] for future migrations. Unknown fields are ignored and missing ones take their
 * defaults, so older and newer app versions can read each other's text layers.
 */
@Serializable
data class TextLayerData(
    val version: Int = TextCodec.VERSION,
    val item: TextItem = TextItem(),
)

/** JSON encoding of [TextItem]s for text layers (pure Kotlin). */
object TextCodec {
    /**
     * Current format version (1 = v1.2: box, vertical style, text path; 2 = v1.3: imported fonts
     * `fontId` / `fontName`, box `minHeight` / `minWidth`; 3 = v1.5: `wrap`, text flowing around a
     * picture; 4 = v1.6: `spec.letterScale` (progressive letter scaling) and `thread` (a frame of
     * a linked story)). Older data reads as is (the new fields take their defaults: version 3
     * text is unscaled and unthreaded); older apps ignore the new fields and show the committed
     * pixels until the text is edited there (editing a frame in v1.5 drops its thread, for that
     * frame only).
     */
    const val VERSION = 4

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An enum value written by a newer version falls back to the field's default.
        coerceInputValues = true
        // Never fail to save because of a stray NaN (items are sanitized anyway).
        allowSpecialFloatingPointValues = true
    }

    fun encode(item: TextItem): String = json.encodeToString(TextLayerData.serializer(), TextLayerData(VERSION, item.sanitized()))

    /** The text object stored in [data], or null when there is none or it can't be read. */
    fun decode(data: String?): TextItem? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(TextLayerData.serializer(), data).item.sanitized()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * Id of the picture layer the text in [data] wraps around (v1.5), also when that layer was
     * deleted (the text keeps its outline); 0 when it doesn't wrap or can't be read. Layer ids a
     * document hands out must stay clear of it, or the text would follow an unrelated layer.
     */
    fun wrapSourceId(data: String?): Long {
        if (data.isNullOrBlank() || !data.contains("sourceLayerId")) return 0L
        return decode(data)?.wrap?.takeIf { it.isOn }?.sourceLayerId ?: 0L
    }

    /**
     * [data] wrapping around layer [sourceLayerId] instead (its outline and layout unchanged: the
     * renderer uses only the stored outline). [data] itself when it doesn't wrap or can't be read.
     */
    fun withWrapSource(data: String, sourceLayerId: Long): String {
        val item = decode(data) ?: return data
        if (!item.wrap.isOn || item.wrap.sourceLayerId == sourceLayerId || sourceLayerId <= 0L) return data
        return encode(item.copy(wrap = item.wrap.copy(sourceLayerId = sourceLayerId)))
    }
}
