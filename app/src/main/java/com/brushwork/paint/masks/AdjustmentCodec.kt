package com.brushwork.paint.masks

/**
 * Stores an [AdjustmentSpec] as a string inside `project.json` (`LayerEntryDto.adjustment`), so
 * an effect that can't be read affects only its own layer (v1.5, owned by A5 after F1).
 */
object AdjustmentCodec {
    fun encode(s: AdjustmentSpec): String = maskJson.encodeToString(AdjustmentSpec.serializer(), s)

    /** The spec in [s], or null when it can't be read. */
    fun decode(s: String): AdjustmentSpec? = try {
        maskJson.decodeFromString(AdjustmentSpec.serializer(), s)
    } catch (e: Exception) {
        null
    }
}
