package com.brushwork.paint.brush

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brushwork.paint.EditorController
import com.brushwork.paint.tools.ToolId
import kotlinx.serialization.json.Json

/**
 * Remembers the user's edits to each brush preset and the last preset chosen for each painting
 * tool (SharedPreferences "brushwork_brushes", presets stored as JSON). Unedited presets are not
 * stored, so improved library defaults reach users who never changed them.
 */
class BrushPresetStore private constructor(private val prefs: SharedPreferences) {
    private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true; coerceInputValues = true }

    /**
     * The user's edited version of preset [id], or null when it was never changed. The name and
     * tip are not user settings, so they always come from the current library.
     */
    fun edited(id: String): BrushPreset? {
        val text = prefs.getString(KEY_PRESET + id, null) ?: return null
        val stored = runCatching { json.decodeFromString(BrushPreset.serializer(), text) }
            .getOrNull()
            ?.takeIf { it.id == id }
            ?: return null
        val library = BrushLibrary.byId(id)
        return (if (library != null) stored.copy(name = library.name, tip = library.tip) else stored).sanitized()
    }

    /** Preset [id] including the user's edits; null for an unknown id. */
    fun load(id: String): BrushPreset? = edited(id) ?: BrushLibrary.byId(id)

    /** Stores [preset]'s settings (removes the entry when it matches the library default). */
    fun save(preset: BrushPreset) {
        // Clamped like the stroke engine does: JSON can't hold NaN/Infinity, and encoding one
        // would throw at the end of a stroke (persist runs on the main thread).
        @Suppress("NAME_SHADOWING")
        val preset = preset.sanitized()
        val default = BrushLibrary.byId(preset.id)
        val key = KEY_PRESET + preset.id
        if (default != null && default == preset) {
            if (prefs.contains(key)) prefs.edit { remove(key) }
        } else {
            val text = json.encodeToString(BrushPreset.serializer(), preset)
            if (prefs.getString(key, null) != text) prefs.edit { putString(key, text) }
        }
    }

    /** Forgets the edits of preset [id]; returns the library default (null for unknown ids). */
    fun reset(id: String): BrushPreset? {
        prefs.edit { remove(KEY_PRESET + id) }
        return BrushLibrary.byId(id)
    }

    fun lastPresetId(toolId: ToolId): String? = prefs.getString(KEY_LAST + toolId.name, null)

    fun setLastPreset(toolId: ToolId, presetId: String) {
        if (lastPresetId(toolId) != presetId) prefs.edit { putString(KEY_LAST + toolId.name, presetId) }
    }

    /** Preset to restore for [toolId]: the last chosen one with its edits, else the tool default. */
    fun current(toolId: ToolId): BrushPreset {
        val last = lastPresetId(toolId)?.takeIf { BrushLibrary.toolOf(it) == toolId }?.let { load(it) }
        if (last != null) return last
        val default = BrushLibrary.defaultFor(toolId)
        return load(default.id) ?: default
    }

    /** Saves [preset] and marks it as the last preset used by [toolId]. */
    fun remember(toolId: ToolId, preset: BrushPreset) {
        save(preset)
        setLastPreset(toolId, preset.id)
    }

    // ------------------------------------------------------------------ controller helpers

    /** Switches [toolId] to preset [presetId] (with its saved edits), saving the current one first. */
    fun select(controller: EditorController, toolId: ToolId, presetId: String) {
        controller.presetFor(toolId)?.let { save(it) }
        val preset = load(presetId) ?: return
        controller.updatePreset(toolId, preset)
        setLastPreset(toolId, presetId)
    }

    /**
     * Applies [transform] to the current preset of [toolId] (values are clamped). With
     * [persist] the result is saved too (use false while a slider is dragged).
     */
    fun edit(controller: EditorController, toolId: ToolId, persist: Boolean, transform: (BrushPreset) -> BrushPreset) {
        val current = controller.presetFor(toolId) ?: return
        val next = transform(current).sanitized()
        if (next != current) controller.updatePreset(toolId, next)
        if (persist) remember(toolId, next)
    }

    /** Saves the current preset of [toolId] as it is. */
    fun persist(controller: EditorController, toolId: ToolId) {
        controller.presetFor(toolId)?.let { remember(toolId, it) }
    }

    /** Restores the library settings of the current preset of [toolId]. */
    fun resetToDefault(controller: EditorController, toolId: ToolId) {
        val current = controller.presetFor(toolId) ?: return
        val default = reset(current.id) ?: return
        controller.updatePreset(toolId, default)
        setLastPreset(toolId, default.id)
    }

    companion object {
        const val PREFS_NAME = "brushwork_brushes"
        private const val KEY_PRESET = "preset."
        private const val KEY_LAST = "last."

        private var cached: Pair<Context, BrushPresetStore>? = null

        /** The store of the application [context] belongs to. */
        @Synchronized
        fun get(context: Context): BrushPresetStore {
            val app = context.applicationContext ?: context
            cached?.let { (ctx, store) -> if (ctx === app) return store }
            val store = BrushPresetStore(app.getSharedPreferences(PREFS_NAME, Context.MODE_PRIVATE))
            cached = app to store
            return store
        }
    }
}
