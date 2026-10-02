package com.brushwork.paint

import android.content.Context
import android.content.SharedPreferences
import androidx.core.content.edit
import com.brushwork.paint.model.IncrementSettings
import com.brushwork.paint.model.StabilizerSettings
import com.brushwork.paint.model.TransparencyDisplay
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

    /** Holding a finger still on the canvas with a color tool picks the color under it. */
    var longPressEyedropper: Boolean
        get() = prefs.getBoolean("longPressEyedropper", true)
        set(v) = prefs.edit { putBoolean("longPressEyedropper", v) }

    /** Put the side sliders on the right (left-handed layout). */
    var leftHanded: Boolean
        get() = prefs.getBoolean("leftHanded", false)
        set(v) = prefs.edit { putBoolean("leftHanded", v) }

    // ------------------------------------------------------------------ v1.5 (keys owned by the lead; areas read and write them)

    /** Clone stamp: the offset set by the first stroke is kept for the next strokes (Photoshop's "Aligned"). */
    var cloneAligned: Boolean
        get() = prefs.getBoolean("cloneAligned", true)
        set(v) = prefs.edit { putBoolean("cloneAligned", v) }

    /** Clone stamp samples all visible layers (false: only the active layer). */
    var cloneSampleAllLayers: Boolean
        get() = prefs.getBoolean("cloneSampleAllLayers", false)
        set(v) = prefs.edit { putBoolean("cloneSampleAllLayers", v) }

    /** Clone stamp shows its source crosshair. */
    var cloneShowSource: Boolean
        get() = prefs.getBoolean("cloneShowSource", true)
        set(v) = prefs.edit { putBoolean("cloneShowSource", v) }

    /** The X / Y coordinate strip is folded to one line. */
    var coordinateStripFolded: Boolean
        get() = prefs.getBoolean("coordinateStripFolded", false)
        set(v) = prefs.edit { putBoolean("coordinateStripFolded", v) }

    /** Masks tool: the red coverage overlay stays on (not only briefly after an edit). */
    var maskOverlayAlways: Boolean
        get() = prefs.getBoolean("maskOverlayAlways", false)
        set(v) = prefs.edit { putBoolean("maskOverlayAlways", v) }

    /** Vector eraser mode: "OBJECT", "PARTIAL" or "INTERSECTION". */
    var vectorEraserMode: String
        get() = prefs.getString("vectorEraserMode", "OBJECT") ?: "OBJECT"
        set(v) = prefs.edit { putString("vectorEraserMode", v) }

    /** A vector-mode hint was shown (areas may use it for one-time hints). */
    var vectorHintShown: Boolean
        get() = prefs.getBoolean("vectorHintShown", false)
        set(v) = prefs.edit { putBoolean("vectorHintShown", v) }

    /** Hidden kill switch: adjustment layers are drawn as pass-through (I5). */
    var safeCompositing: Boolean
        get() = prefs.getBoolean("safeCompositing", false)
        set(v) = prefs.edit { putBoolean("safeCompositing", v) }

    // ------------------------------------------------------------------ v1.6 (keys owned by the lead; areas read and write them)

    /**
     * The app-wide increment steps (JSON under "increments"; not per document). Off by default
     * (I8). Read through `EditorController.increments` (Compose state) in the editor; stored
     * sanitized.
     */
    var increments: IncrementSettings
        get() = getObject("increments", IncrementSettings.serializer())?.sanitized() ?: IncrementSettings()
        set(v) = putObject("increments", IncrementSettings.serializer(), v.sanitized())

    /**
     * Live adjustment previews (v1.6 §3.1): an adjustment slider, mask handle or adjustment-layer
     * opacity drag draws from a fast proxy and refines to exact when the finger stops. Off: the
     * v1.5 path. "Safe compositing" stays the kill switch.
     */
    var fastAdjustPreview: Boolean
        get() = prefs.getBoolean("fastAdjustPreview", true)
        set(v) = prefs.edit { putBoolean("fastAdjustPreview", v) }

    /** How the canvas shows transparency (a view preference; exports are unaffected). */
    var transparencyDisplay: TransparencyDisplay
        get() = prefs.getString("transparencyDisplay", null)?.let { n -> TransparencyDisplay.entries.firstOrNull { it.name == n } }
            ?: TransparencyDisplay.LIGHT_CHECKER
        set(v) = prefs.edit { putString("transparencyDisplay", v.name) }

    /**
     * Curve settings › "Handle size" (v1.6 §3.3): scales the drawn radii of curve handles,
     * anchors and Path control points AND their grab radii. [MIN_CURVE_HANDLE_SCALE]..
     * [MAX_CURVE_HANDLE_SCALE], default 1.
     */
    var curveHandleScale: Float
        get() = prefs.getFloat("curveHandleScale", 1f).let { if (it.isFinite()) it.coerceIn(MIN_CURVE_HANDLE_SCALE, MAX_CURVE_HANDLE_SCALE) else 1f }
        set(v) = prefs.edit { putFloat("curveHandleScale", if (v.isFinite()) v.coerceIn(MIN_CURVE_HANDLE_SCALE, MAX_CURVE_HANDLE_SCALE) else 1f) }

    companion object {
        const val MIN_CURVE_HANDLE_SCALE = 0.75f
        const val MAX_CURVE_HANDLE_SCALE = 2f
    }
}
