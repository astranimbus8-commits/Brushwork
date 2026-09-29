package com.brushwork.paint.ui.color

import android.content.Context
import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.core.ColorUtils
import kotlinx.serialization.Serializable
import kotlinx.serialization.Transient
import kotlinx.serialization.json.Json
import java.util.UUID

/** A named list of colors. Built-in palettes are read-only and never persisted. */
@Serializable
data class Palette(
    val id: String,
    val name: String,
    val colors: List<Int> = emptyList(),
    @Transient val builtIn: Boolean = false,
)

/**
 * Everything the color picker persists: the user's palettes, the active palette, recent colors
 * and the last picker mode. Immutable; every `with...` operation returns an updated copy (or
 * `this` when the operation doesn't apply, e.g. editing a built-in palette).
 */
@Serializable
data class PaletteData(
    /** User palettes only (built-ins come from [BuiltInPalettes]). */
    val palettes: List<Palette> = listOf(defaultUserPalette()),
    val activeId: String = MY_PALETTE_ID,
    /** Most recent first, distinct, at most [MAX_RECENT]. */
    val recent: List<Int> = emptyList(),
    /** Last picker tab (see [PickerMode]). */
    val pickerMode: Int = 0,
) {
    /** User palettes followed by the built-in ones (the order of the palette chips). */
    val all: List<Palette> get() = palettes + BuiltInPalettes.all

    fun palette(id: String): Palette? = palettes.firstOrNull { it.id == id } ?: BuiltInPalettes.byId(id)

    val active: Palette get() = palette(activeId) ?: palettes.firstOrNull() ?: BuiltInPalettes.all.first()

    fun isEditable(id: String): Boolean = palettes.any { it.id == id }

    fun withActive(id: String): PaletteData = if (palette(id) == null) this else copy(activeId = id)

    fun withPickerMode(mode: Int): PaletteData = copy(pickerMode = mode)

    fun withColorAdded(id: String, color: Int): PaletteData =
        editColors(id) { if (it.size >= MAX_COLORS) it else it + color }

    fun withColorReplaced(id: String, index: Int, color: Int): PaletteData =
        editColors(id) { if (index !in it.indices) it else it.toMutableList().apply { set(index, color) } }

    fun withColorRemoved(id: String, index: Int): PaletteData =
        editColors(id) { if (index !in it.indices) it else it.toMutableList().apply { removeAt(index) } }

    /** Swaps the color at [index] with its neighbor [delta] positions away (-1 = left, 1 = right). */
    fun withColorMoved(id: String, index: Int, delta: Int): PaletteData = editColors(id) {
        val to = index + delta
        if (index !in it.indices || to !in it.indices) it
        else it.toMutableList().apply { val tmp = this[index]; this[index] = this[to]; this[to] = tmp }
    }

    /** Adds a user palette (name made unique) and makes it active. */
    fun withNewPalette(name: String, newId: String, colors: List<Int> = emptyList()): PaletteData {
        val clean = cleanName(name) ?: "Palette"
        val p = Palette(newId, uniqueName(clean), colors.take(MAX_COLORS))
        return copy(palettes = palettes + p, activeId = p.id)
    }

    /** Copies any palette (built-ins included) into a new editable one and makes it active. */
    fun withDuplicate(id: String, newId: String): PaletteData {
        val src = palette(id) ?: return this
        return withNewPalette("${src.name} copy", newId, src.colors)
    }

    fun withRenamed(id: String, name: String): PaletteData {
        val clean = cleanName(name) ?: return this
        if (!isEditable(id)) return this
        val others = palettes.filter { it.id != id }.map { it.name }.toSet()
        val finalName = if (clean in others) uniqueName(clean) else clean
        return copy(palettes = palettes.map { if (it.id == id) it.copy(name = finalName) else it })
    }

    /** Deletes a user palette. The last remaining user palette can't be deleted. */
    fun withDeleted(id: String): PaletteData {
        if (!isEditable(id) || palettes.size <= 1) return this
        val idx = palettes.indexOfFirst { it.id == id }
        val left = palettes.filter { it.id != id }
        val active = if (activeId == id) left[(idx - 1).coerceIn(0, left.lastIndex)].id else activeId
        return copy(palettes = left, activeId = active)
    }

    /** Records a used color: moved to the front, duplicates removed, capped at [MAX_RECENT]. */
    fun withRecent(color: Int): PaletteData {
        val next = (listOf(color) + recent.filter { it != color }).take(MAX_RECENT)
        return if (next == recent) this else copy(recent = next)
    }

    fun withRecentCleared(): PaletteData = if (recent.isEmpty()) this else copy(recent = emptyList())

    /** Repairs data read from storage (see [decode]). */
    fun sanitized(): PaletteData {
        val seen = HashSet<String>()
        val users = palettes
            .filter { it.id.isNotBlank() && BuiltInPalettes.byId(it.id) == null && seen.add(it.id) }
            .map { it.copy(name = cleanName(it.name) ?: "Palette", colors = it.colors.take(MAX_COLORS), builtIn = false) }
            .ifEmpty { listOf(defaultUserPalette()) }
        val fixed = copy(
            palettes = users,
            recent = recent.distinct().take(MAX_RECENT),
            pickerMode = pickerMode.coerceIn(0, PickerMode.entries.lastIndex),
        )
        return if (fixed.palette(fixed.activeId) == null) fixed.copy(activeId = users.first().id) else fixed
    }

    private fun editColors(id: String, block: (List<Int>) -> List<Int>): PaletteData {
        val target = palettes.firstOrNull { it.id == id } ?: return this
        val colors = block(target.colors)
        if (colors == target.colors) return this
        return copy(palettes = palettes.map { if (it.id == id) it.copy(colors = colors) else it })
    }

    private fun uniqueName(base: String): String {
        val names = palettes.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    companion object {
        const val MY_PALETTE_ID = "my"
        const val MAX_RECENT = 16
        const val MAX_COLORS = 256
        const val MAX_NAME_LENGTH = 40

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun defaultUserPalette() = Palette(
            MY_PALETTE_ID, "My palette",
            hexColors("#000000", "#FFFFFF", "#808080", "#E53935", "#FB8C00", "#FDD835", "#43A047", "#1E88E5", "#8E24AA", "#6D4C41"),
        )

        fun cleanName(name: String): String? = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME_LENGTH).ifEmpty { null }

        fun encode(data: PaletteData): String = json.encodeToString(serializer(), data)

        /** Parses stored JSON; missing, corrupt or inconsistent data falls back to defaults. */
        fun decode(text: String?): PaletteData {
            if (text.isNullOrBlank()) return PaletteData()
            return runCatching { json.decodeFromString(serializer(), text).sanitized() }.getOrElse { PaletteData() }
        }
    }
}

/** Tabs of the picker. The ordinal is persisted in [PaletteData.pickerMode]. */
enum class PickerMode(val label: String) { WHEEL("Wheel"), RGB("RGB"), HSB("HSB") }

/** Read-only palettes that ship with the app. */
object BuiltInPalettes {
    val all: List<Palette> = listOf(
        builtIn("basic", "Basic",
            "#000000", "#404040", "#808080", "#C0C0C0", "#FFFFFF", "#E53935", "#FF7043", "#FB8C00",
            "#FDD835", "#C0CA33", "#43A047", "#00897B", "#00ACC1", "#1E88E5", "#3949AB", "#8E24AA",
            "#D81B60", "#F48FB1", "#795548", "#5D4037"),
        builtIn("skin", "Skin tones",
            "#FFF0E1", "#FFE0C4", "#F8D2B4", "#EEC1A0", "#E0AC84", "#D09A70", "#C68642", "#A86B3D",
            "#8D5524", "#6B3E1E", "#4A2912", "#3B2219", "#F2B8A2", "#D98E73", "#B5654D", "#E8A598"),
        builtIn("pastel", "Pastel",
            "#FFB3BA", "#FFDFBA", "#FFFFBA", "#BAFFC9", "#BAE1FF", "#D7BAFF", "#FFC8DD", "#BDE0FE",
            "#A2D2FF", "#CDB4DB", "#FFAFCC", "#E2F0CB", "#B5EAD7", "#C7CEEA", "#FFDAC1", "#F1E3D3"),
        Palette(BUILT_IN_PREFIX + "gray", "Grayscale", (0..15).map { ColorUtils.gray(it * 17) }, builtIn = true),
        builtIn("earth", "Earth",
            "#3B2F2F", "#5C4033", "#6F4E37", "#8B5A2B", "#A0522D", "#C19A6B", "#D2B48C", "#E6D3A3",
            "#556B2F", "#6B8E23", "#808000", "#8F9779", "#2F4F4F", "#708090", "#B7410E", "#CC7722"),
        builtIn("neon", "Neon",
            "#FF073A", "#FF3131", "#FF6EC7", "#FF00FF", "#BC13FE", "#8A2BE2", "#1F51FF", "#00FFFF",
            "#0FF0FC", "#39FF14", "#CCFF00", "#DFFF00", "#FFFF33", "#FFAA1D", "#FF5F1F", "#FE347E"),
    )

    private val ids: Map<String, Palette> = all.associateBy { it.id }

    fun byId(id: String): Palette? = ids[id]

    private fun builtIn(id: String, name: String, vararg hex: String) =
        Palette(BUILT_IN_PREFIX + id, name, hexColors(*hex), builtIn = true)
}

/** Id prefix of built-in palettes (user palettes use "u-<uuid>" or [PaletteData.MY_PALETTE_ID]). */
private const val BUILT_IN_PREFIX = "builtin:"

private fun hexColors(vararg hex: String): List<Int> = hex.map { requireNotNull(ColorUtils.parseHex(it)) { "bad color $it" } }

/**
 * App-wide palette + recent-color store, persisted as JSON in the "brushwork_palettes"
 * SharedPreferences. [data] is Compose state, so every picker showing it updates live.
 * Main thread only. Use [get] (or [rememberPaletteStore]) to share one instance.
 */
class PaletteStore internal constructor(private val prefs: SharedPreferences) {

    constructor(context: Context) : this(context.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))

    var data: PaletteData by mutableStateOf(PaletteData.decode(prefs.getString(KEY, null)))
        private set

    val active: Palette get() = data.active

    fun setActive(id: String) = update { it.withActive(id) }
    fun setPickerMode(mode: PickerMode) = update { it.withPickerMode(mode.ordinal) }
    fun addColor(paletteId: String, color: Int) = update { it.withColorAdded(paletteId, color) }
    fun replaceColor(paletteId: String, index: Int, color: Int) = update { it.withColorReplaced(paletteId, index, color) }
    fun removeColor(paletteId: String, index: Int) = update { it.withColorRemoved(paletteId, index) }
    fun moveColor(paletteId: String, index: Int, delta: Int) = update { it.withColorMoved(paletteId, index, delta) }
    fun createPalette(name: String) = update { it.withNewPalette(name, newId()) }
    fun duplicatePalette(id: String) = update { it.withDuplicate(id, newId()) }
    fun renamePalette(id: String, name: String) = update { it.withRenamed(id, name) }
    fun deletePalette(id: String) = update { it.withDeleted(id) }
    fun addRecent(color: Int) = update { it.withRecent(color) }
    fun clearRecent() = update { it.withRecentCleared() }

    private fun update(transform: (PaletteData) -> PaletteData) {
        val next = transform(data)
        if (next == data) return
        data = next
        prefs.edit().putString(KEY, PaletteData.encode(next)).apply()
    }

    private fun newId(): String = "u-" + UUID.randomUUID().toString()

    companion object {
        const val PREFS_NAME = "brushwork_palettes"
        internal const val KEY = "data"

        @Volatile private var instance: PaletteStore? = null

        /** The shared instance (created from the application context on first use). */
        fun get(context: Context): PaletteStore =
            instance ?: synchronized(this) { instance ?: PaletteStore(context.applicationContext).also { instance = it } }
    }
}

/** The shared [PaletteStore] for the current context. */
@Composable
fun rememberPaletteStore(): PaletteStore {
    val context = LocalContext.current
    return remember(context) { PaletteStore.get(context) }
}
