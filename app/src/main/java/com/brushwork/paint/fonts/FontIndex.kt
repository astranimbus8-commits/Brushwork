package com.brushwork.paint.fonts

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json

/** One imported font file, stored as `fonts/<id>.<ext>` in app-private storage. */
@Serializable
data class ImportedFont(
    /** Content hash (see [FontIds]); stable across devices for the same file. */
    val id: String,
    /** Display name (from the font's `name` table, else its file name). */
    val name: String,
    val family: String = "",
    val style: String = "",
    /** Stored file name (`<id>.<ext>`). */
    val file: String,
    /** Name of the file (or zip entry) it came from. */
    val source: String = "",
    val bytes: Long = 0L,
    val addedAt: Long = 0L,
)

/**
 * What the font store persists in `fonts/index.json`: the imported fonts, the favorites and the
 * recently used fonts (keys from [FontIds.keyOf], built-in families included).
 */
@Serializable
data class FontIndexData(
    val version: Int = 1,
    val fonts: List<ImportedFont> = emptyList(),
    val favorites: List<String> = emptyList(),
    /** Most recent first. */
    val recent: List<String> = emptyList(),
) {
    fun withFavorite(key: String, favorite: Boolean): FontIndexData {
        val has = key in favorites
        return when {
            favorite && !has -> copy(favorites = favorites + key)
            !favorite && has -> copy(favorites = favorites - key)
            else -> this
        }
    }

    fun withRecent(key: String): FontIndexData {
        val next = (listOf(key) + recent.filter { it != key }).take(MAX_RECENT)
        return if (next == recent) this else copy(recent = next)
    }

    /** Without the imported font [id] (its favorite and recent entries too). */
    fun without(id: String): FontIndexData {
        val key = FontIds.keyOf(id)
        return copy(fonts = fonts.filter { it.id != id }, favorites = favorites - key, recent = recent - key)
    }

    /**
     * Repairs data read from storage: bad ids and duplicates dropped, favorites / recent limited
     * to fonts that exist (built-in families always do), fonts sorted by name.
     */
    fun sanitized(): FontIndexData {
        val seen = HashSet<String>()
        val clean = fonts
            .filter { FontIds.isValid(it.id) && seen.add(it.id) }
            .map { it.copy(name = FontIds.cleanName(it.name) ?: "Font") }
            .sortedWith(compareBy(String.CASE_INSENSITIVE_ORDER) { it.name })
        val ids = clean.mapTo(HashSet()) { it.id }
        fun exists(key: String) = FontIds.builtInOf(key) != null || FontIds.importedOf(key)?.let { it in ids } == true
        return copy(
            fonts = clean,
            favorites = favorites.filter(::exists).distinct(),
            recent = recent.filter(::exists).distinct().take(MAX_RECENT),
        )
    }

    companion object {
        const val MAX_RECENT = 8

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

        fun encode(data: FontIndexData): String = json.encodeToString(serializer(), data)

        /** Stored JSON, or an empty index when it is missing or unreadable. */
        fun decode(text: String?): FontIndexData {
            if (text.isNullOrBlank()) return FontIndexData()
            return runCatching { json.decodeFromString(serializer(), text) }.getOrElse { FontIndexData() }
        }
    }
}

/** What an import did, with a short message for a toast or the font picker. */
data class FontImportReport(
    val added: List<ImportedFont> = emptyList(),
    /** Names of fonts that were already imported. */
    val duplicates: List<String> = emptyList(),
    /** Files that look like fonts but can't be used (damaged, or a format Android can't load). */
    val invalid: List<String> = emptyList(),
    /** Web fonts (.woff / .woff2), which Android can't load. */
    val unsupported: List<String> = emptyList(),
    val tooBig: List<String> = emptyList(),
    /** Files that were not fonts at all (read-me files, pictures...), when nothing else was found. */
    val notFonts: List<String> = emptyList(),
    /** Files that couldn't be read at all. */
    val unreadable: List<String> = emptyList(),
    /** The archive was larger than the import allows; the rest was skipped. */
    val limitReached: Boolean = false,
) {
    val message: String
        get() {
            val parts = ArrayList<String>()
            if (added.isNotEmpty()) {
                val names = added.take(3).joinToString(", ") { it.name } + if (added.size > 3) "…" else ""
                parts += if (added.size == 1) "Added the font $names" else "Added ${added.size} fonts: $names"
            }
            if (duplicates.isNotEmpty()) parts += if (duplicates.size == 1 && added.isEmpty()) "${duplicates[0]} is already imported" else "${duplicates.size} already imported"
            if (invalid.isNotEmpty()) parts += "${invalid.size} damaged or unusable font file${if (invalid.size == 1) "" else "s"} skipped"
            if (unsupported.isNotEmpty()) parts += "${unsupported.size} web font${if (unsupported.size == 1) "" else "s"} (.woff) skipped: use the .ttf / .otf"
            if (tooBig.isNotEmpty()) parts += "${tooBig.size} file${if (tooBig.size == 1) "" else "s"} too large"
            if (unreadable.isNotEmpty()) parts += "${unreadable.size} file${if (unreadable.size == 1) "" else "s"} couldn't be read"
            if (limitReached) parts += "the archive is too large, the rest was skipped"
            if (parts.isEmpty()) {
                return if (notFonts.isNotEmpty()) "No fonts found (pick a .ttf, .otf or a .zip from dafont)" else "Nothing imported"
            }
            return parts.joinToString("; ")
        }

    operator fun plus(o: FontImportReport) = FontImportReport(
        added + o.added, duplicates + o.duplicates, invalid + o.invalid, unsupported + o.unsupported,
        tooBig + o.tooBig, notFonts + o.notFonts, unreadable + o.unreadable, limitReached || o.limitReached,
    )
}
