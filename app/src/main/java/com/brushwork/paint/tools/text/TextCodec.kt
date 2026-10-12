package com.brushwork.paint.tools.text

import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/**
 * What a text layer stores in `Layer.textData`: the editable text object plus a format
 * [version] for future migrations. Unknown fields are ignored and missing ones take their
 * defaults, so older and newer app versions can read each other's text layers.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class TextLayerData(
    val version: Int = TextCodec.VERSION,
    /** The bottom text of the layer (the only one in v1.8). */
    val item: TextItem = TextItem(),
    /** R14: the second and later texts, in picture order (bottom first). Never written when empty (I13). */
    @EncodeDefault(EncodeDefault.Mode.NEVER) val more: List<TextItem> = emptyList(),
)

/** JSON encoding of [TextItem]s for text layers (pure Kotlin). */
object TextCodec {
    /**
     * Current format version (1 = v1.2: box, vertical style, text path; 2 = v1.3: imported fonts
     * `fontId` / `fontName`, box `minHeight` / `minWidth`; 3 = v1.5: `wrap`, text flowing around a
     * picture; 4 = v1.6: `spec.letterScale` (progressive letter scaling) and `thread` (a frame of
     * a linked story); 5 = v1.7: `kerns` and `spec.fontKerning`, written only when used, so other
     * text encodes as in v1.6 but for this number). Older data reads as is (the new fields take
     * their defaults: version 3 text is unscaled and unthreaded, version 4 text unkerned); older
     * apps ignore the new fields and show the committed pixels until the text is edited there
     * (editing a frame in v1.5 drops its thread, for that frame only; editing kerned text in v1.6
     * lays it out without kerns). v1.9 adds fields that are never written at their defaults
     * (`more`, `id`, `justify`, `kerningMode`, `list`, `nodes`) and keeps this number (I13).
     */
    const val VERSION = 5

    /** R14: at most 64 texts per layer; the 65th starts a new layer (A3). */
    const val MAX_ITEMS = 64

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        // An enum value written by a newer version falls back to the field's default.
        coerceInputValues = true
        // Never fail to save because of a stray NaN (items are sanitized anyway).
        allowSpecialFloatingPointValues = true
    }

    /**
     * The layer data of the one text [item]: the bytes v1.8 wrote (`more` is never written
     * empty). After F2 only TextCodec.kt and tests call it (rule T).
     */
    fun encode(item: TextItem): String = json.encodeToString(TextLayerData.serializer(), TextLayerData(VERSION, item.sanitized()))

    /**
     * The text object stored in [data] (the first, bottom one of a layer with several), or null
     * when there is none or it can't be read. After F2 only TextCodec.kt and tests call it (rule T).
     */
    fun decode(data: String?): TextItem? {
        if (data.isNullOrBlank()) return null
        return try {
            json.decodeFromString(TextLayerData.serializer(), data).item.sanitized()
        } catch (e: Exception) {
            null
        }
    }

    /**
     * v1.9 (R14): the layer data of [items], bottom first. [items] must not be empty and hold at
     * most [MAX_ITEMS]. One item: byte-identical to `encode(items[0])`. Several: items[0] becomes
     * `item`, the rest `more`; each positive id that is not yet taken is kept, every other item
     * gets the next free id (max + 1, ...), so ids are unique and stable.
     */
    fun encodeAll(items: List<TextItem>): String {
        require(items.isNotEmpty()) { "a text layer holds at least one text" }
        require(items.size <= MAX_ITEMS) { "a text layer holds at most $MAX_ITEMS texts, not ${items.size}" }
        if (items.size == 1) return encode(items[0])
        val clean = items.map { it.sanitized() }
        val taken = HashSet<Int>()
        val keep = BooleanArray(clean.size) { i -> clean[i].id > 0 && taken.add(clean[i].id) }
        var next = taken.maxOrNull() ?: 0
        val withIds = clean.mapIndexed { i, it -> if (keep[i]) it else it.copy(id = ++next) }
        return json.encodeToString(TextLayerData.serializer(), TextLayerData(VERSION, withIds[0], withIds.subList(1, withIds.size)))
    }

    /**
     * v1.9 (R14): every text of [data], bottom first, sanitized, at most [MAX_ITEMS] (the rest
     * dropped); empty for null or blank data and when it does not decode. A v1.8 layer gives its
     * one text. The ids are read as stored ([encodeAll] makes them unique).
     */
    fun decodeAll(data: String?): List<TextItem> {
        if (data.isNullOrBlank()) return emptyList()
        val d = try {
            json.decodeFromString(TextLayerData.serializer(), data)
        } catch (e: Exception) {
            return emptyList()
        }
        if (d.more.isEmpty()) return listOf(d.item.sanitized())
        val out = ArrayList<TextItem>(minOf(d.more.size + 1, MAX_ITEMS))
        out += d.item.sanitized()
        for (m in d.more) {
            if (out.size >= MAX_ITEMS) break
            out += m.sanitized()
        }
        return out
    }

    /**
     * Id of the picture layer a text in [data] wraps around (v1.5), also when that layer was
     * deleted (the text keeps its outline); 0 when none wraps or it can't be read. Layer ids a
     * document hands out must stay clear of it, or the text would follow an unrelated layer.
     * v1.9 (F1a): every text of the layer is read ([decodeAll]); the first wrapping one counts.
     */
    fun wrapSourceId(data: String?): Long {
        if (data.isNullOrBlank() || !data.contains("sourceLayerId")) return 0L
        return decodeAll(data).firstOrNull { it.wrap.isOn }?.wrap?.sourceLayerId ?: 0L
    }

    /**
     * [data] wrapping around layer [sourceLayerId] instead (its outline and layout unchanged: the
     * renderer uses only the stored outline). [data] itself when no text of it wraps, when every
     * wrapping text already wraps around that layer, when [sourceLayerId] is not positive, or when
     * it can't be read. v1.9 (F1a): every wrapping text is mapped and the layer is written with
     * [encodeAll], so `more` is never dropped; a one-text layer gives the bytes v1.8 gave.
     */
    fun withWrapSource(data: String, sourceLayerId: Long): String {
        if (sourceLayerId <= 0L) return data
        val items = decodeAll(data)
        if (items.none { it.wrap.isOn && it.wrap.sourceLayerId != sourceLayerId }) return data
        return encodeAll(items.map { if (it.wrap.isOn) it.copy(wrap = it.wrap.copy(sourceLayerId = sourceLayerId)) else it })
    }
}
