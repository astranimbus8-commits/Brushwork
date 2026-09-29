package com.brushwork.paint

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brushwork.paint.model.StabilizerSettings
import kotlinx.serialization.KSerializer
import kotlinx.serialization.json.Json

/** Small app-wide preferences store (SharedPreferences + JSON for structured values). */
class AppSettings(context: Context) {
    val prefs: SharedPreferences = context.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE)

    val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }

    fun <T> getObject(key: String, serializer: KSerializer<T>): T? =
        prefs.getString(key, null)?.let { runCatching { json.decodeFromString(serializer, it) }.getOrNull() }

    fun <T> putObject(key: String, serializer: KSerializer<T>, value: T) =
        prefs.edit { putString(key, json.encodeToString(serializer, value)) }

    var stabilizer: StabilizerSettings
        get() = getObject("stabilizer", StabilizerSettings.serializer()) ?: StabilizerSettings()
        set(v) = putObject("stabilizer", StabilizerSettings.serializer(), v)

    /** Two-finger tap = undo. */
    var twoFingerUndo: Boolean
        get() = prefs.getBoolean("twoFingerUndo", true)
        set(v) = prefs.edit { putBoolean("twoFingerUndo", v) }

    /** Three-finger tap = redo. */
    var threeFingerRedo: Boolean
        get() = prefs.getBoolean("threeFingerRedo", true)
        set(v) = prefs.edit { putBoolean("threeFingerRedo", v) }

    /** Autosave interval while editing, in seconds (the project is also saved on exit). */
    var autosaveSeconds: Int
        get() = prefs.getInt("autosaveSeconds", 45)
        set(v) = prefs.edit { putInt("autosaveSeconds", v) }

    /** Ignore finger input for painting when a stylus has been used (palm rejection). */
    var stylusOnlyDrawing: Boolean
        get() = prefs.getBoolean("stylusOnly", false)
        set(v) = prefs.edit { putBoolean("stylusOnly", v) }

    /** Put the side sliders on the right (left-handed layout). */
    var leftHanded: Boolean
        get() = prefs.getBoolean("leftHanded", false)
        set(v) = prefs.edit { putBoolean("leftHanded", v) }
}
