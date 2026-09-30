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
    /** Current format version (1 = v1.2: box, vertical style, text path). */
    const val VERSION = 1

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
}
