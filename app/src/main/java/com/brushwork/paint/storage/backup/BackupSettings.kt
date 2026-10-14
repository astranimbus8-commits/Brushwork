package com.brushwork.paint.storage.backup

import android.content.Context
import android.content.SharedPreferences
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.fonts.FontIndexData
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.fonts.ImportedFont
import com.brushwork.paint.ui.color.PaletteStore
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v1.9 A8 (R16 (b)): `settings.json`, format 1: the app's settings, the brush presets' edits and
 * the palettes (their three preference files, typed value by value) and the imported fonts' list
 * (the font files go under `fonts/`). The UI state ("brushwork_ui"), the filter recents and the
 * diagnostics are left out: they belong to the device, not to the artworks.
 */
internal object BackupSettings {
    const val FORMAT = 1
    const val SETTINGS_PREFS = "brushwork_settings"

    /** One stored preference value, typed so it is written back as it was read. */
    @Serializable
    sealed interface PrefValue {
        @Serializable @SerialName("bool") data class Bool(val v: Boolean) : PrefValue
        @Serializable @SerialName("int") data class IntV(val v: Int) : PrefValue
        @Serializable @SerialName("long") data class LongV(val v: Long) : PrefValue
        @Serializable @SerialName("float") data class FloatV(val v: Float) : PrefValue
        @Serializable @SerialName("string") data class Str(val v: String) : PrefValue
        @Serializable @SerialName("set") data class StrSet(val v: List<String>) : PrefValue
    }

    @Serializable
    data class Snapshot(
        val format: Int = FORMAT,
        val settings: Map<String, PrefValue> = emptyMap(),
        val brushes: Map<String, PrefValue> = emptyMap(),
        val palettes: Map<String, PrefValue> = emptyMap(),
        val fonts: List<ImportedFont> = emptyList(),
    )

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
        allowSpecialFloatingPointValues = true
    }

    fun encode(s: Snapshot): ByteArray = json.encodeToString(Snapshot.serializer(), s).toByteArray(Charsets.UTF_8)

    fun decode(bytes: ByteArray): Snapshot? = try {
        json.decodeFromString(Snapshot.serializer(), String(bytes, Charsets.UTF_8)).takeIf { it.format <= FORMAT }
    } catch (e: Exception) {
        null
    }

    /** The current settings, brushes, palettes and imported fonts. Thread-safe (preference reads). */
    fun snapshot(context: Context): Snapshot = Snapshot(
        settings = read(context.getSharedPreferences(SETTINGS_PREFS, Context.MODE_PRIVATE)),
        brushes = read(context.getSharedPreferences(BrushPresetStore.PREFS_NAME, Context.MODE_PRIVATE)),
        palettes = read(context.getSharedPreferences(PaletteStore.PREFS_NAME, Context.MODE_PRIVATE)),
        fonts = fontsOf(File(context.filesDir, FontStore.DIR_NAME)),
    )

    /** The imported fonts' list (`fonts/index.json`), only the fonts whose file exists. */
    fun fontsOf(dir: File): List<ImportedFont> {
        val index = File(dir, FontStore.INDEX_FILE)
        if (!index.isFile) return emptyList()
        val data = FontIndexData.decode(runCatching { index.readText() }.getOrNull())
        return data.fonts.filter { File(dir, it.file).isFile }
    }

    private fun read(prefs: SharedPreferences): Map<String, PrefValue> = prefs.all.entries.mapNotNull { (k, v) ->
        val value: PrefValue = when (v) {
            is Boolean -> PrefValue.Bool(v)
            is Int -> PrefValue.IntV(v)
            is Long -> PrefValue.LongV(v)
            is Float -> PrefValue.FloatV(v)
            is String -> PrefValue.Str(v)
            is Set<*> -> PrefValue.StrSet(v.filterIsInstance<String>().sorted())
            else -> return@mapNotNull null
        }
        k to value
    }.sortedBy { it.first }.toMap()

    /** Writes [values] into [editor] (each key as its type). */
    fun put(editor: SharedPreferences.Editor, key: String, value: PrefValue) {
        when (value) {
            is PrefValue.Bool -> editor.putBoolean(key, value.v)
            is PrefValue.IntV -> editor.putInt(key, value.v)
            is PrefValue.LongV -> editor.putLong(key, value.v)
            is PrefValue.FloatV -> editor.putFloat(key, value.v)
            is PrefValue.Str -> editor.putString(key, value.v)
            is PrefValue.StrSet -> editor.putStringSet(key, value.v.toSet())
        }
    }
}
