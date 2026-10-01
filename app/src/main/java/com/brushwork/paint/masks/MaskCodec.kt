package com.brushwork.paint.masks

import kotlinx.serialization.json.Json

/** JSON form of editable masks and adjustment effects (pure Kotlin; v1.5, owned by A5 after F1). */
internal val maskJson = Json {
    ignoreUnknownKeys = true
    encodeDefaults = true
    // An enum value written by a newer version falls back to the field's default.
    coerceInputValues = true
    // A NaN that slipped into a value must not make a save fail (renderers clamp).
    allowSpecialFloatingPointValues = true
}

/**
 * Stores a [MaskSpec] as a string inside `project.json` (`LayerEntryDto.maskSpec`), so a spec
 * that can't be read affects only its own layer.
 */
object MaskCodec {
    fun encode(s: MaskSpec): String = maskJson.encodeToString(MaskSpec.serializer(), s)

    /** The spec in [s], or null when it can't be read. */
    fun decode(s: String): MaskSpec? = try {
        maskJson.decodeFromString(MaskSpec.serializer(), s)
    } catch (e: Exception) {
        null
    }
}
