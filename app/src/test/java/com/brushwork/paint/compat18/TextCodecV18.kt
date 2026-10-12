package com.brushwork.paint.compat18

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/*
 * v1.9 F0: a FROZEN copy of v1.8's text-layer codec (`tools/text/TextCodec.kt` at 600582d9), in
 * test sources only: `Layer.textData` as v1.8 writes and reads it, with v1.8's JSON settings
 * (unknown keys ignored, so v1.8 drops v1.9's `more` and every new field; an unknown enum value
 * takes the field's default) and v1.8's sanitizing on read. Never change it.
 */

/** v1.8's `TextLayerData` (one text per layer: no `more`). */
@Serializable
data class TextLayerDataV18(
    val version: Int = TextCodecV18.VERSION,
    val item: TextItemV18 = TextItemV18(),
)

/** v1.8's `TextCodec`. */
object TextCodecV18 {
    /** v1.8 writes 5 (v1.7's kerns and `fontKerning`). */
    const val VERSION = 5

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    /** v1.8's `TextCodec.encode`: the sanitized item with version 5. */
    fun encode(item: TextItemV18): String = json.encodeToString(TextLayerDataV18.serializer(), TextLayerDataV18(VERSION, item.sanitized()))

    /** The text object stored in [data] as v1.8 reads it, or null when there is none or v1.8 can't read it. */
    fun decode(data: String?): TextItemV18? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(TextLayerDataV18.serializer(), data).item.sanitized()
        } catch (e: Exception) {
            null
        }
    }
}
