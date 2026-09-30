package com.brushwork.paint.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.ToggleRow

/**
 * Compose-observable mirror of the editor-related [AppSettings] (which are plain
 * SharedPreferences). Setters write through immediately.
 */
@Stable
class EditorPrefs(private val settings: AppSettings) {
    private var _twoFingerUndo by mutableStateOf(settings.twoFingerUndo)
    private var _threeFingerRedo by mutableStateOf(settings.threeFingerRedo)
    private var _stylusOnly by mutableStateOf(settings.stylusOnlyDrawing)
    private var _leftHanded by mutableStateOf(settings.leftHanded)
    private var _holdToPick by mutableStateOf(settings.longPressEyedropper)
    private var _autosave by mutableIntStateOf(settings.autosaveSeconds)

    var twoFingerUndo: Boolean
        get() = _twoFingerUndo
        set(v) { _twoFingerUndo = v; settings.twoFingerUndo = v }

    var threeFingerRedo: Boolean
        get() = _threeFingerRedo
        set(v) { _threeFingerRedo = v; settings.threeFingerRedo = v }

    var stylusOnlyDrawing: Boolean
        get() = _stylusOnly
        set(v) { _stylusOnly = v; settings.stylusOnlyDrawing = v }

    /** Slider values and the eyedropper on the left of the slider bar. */
    var leftHanded: Boolean
        get() = _leftHanded
        set(v) { _leftHanded = v; settings.leftHanded = v }

    /** Holding a finger still on the canvas picks the color under it (color tools). */
    var longPressEyedropper: Boolean
        get() = _holdToPick
        set(v) { _holdToPick = v; settings.longPressEyedropper = v }

    var autosaveSeconds: Int
        get() = _autosave
        set(v) { _autosave = v; settings.autosaveSeconds = v }

    companion object {
        val AUTOSAVE_CHOICES = listOf(15, 30, 45, 60, 120)
    }
}

/** Gesture, layout and autosave preferences of the editor. */
@Composable
fun EditorSettingsDialog(prefs: EditorPrefs, onDismiss: () -> Unit) {
    BwDialog(title = "Editor settings", onDismiss = onDismiss) {
        SectionHeader("Gestures")
        ToggleRow("Two-finger tap to undo", prefs.twoFingerUndo, { prefs.twoFingerUndo = it })
        ToggleRow("Three-finger tap to redo", prefs.threeFingerRedo, { prefs.threeFingerRedo = it })
        ToggleRow(
            "Draw with stylus only",
            prefs.stylusOnlyDrawing,
            { prefs.stylusOnlyDrawing = it },
            description = "Fingers pan, zoom and rotate; only a stylus paints. Resting your palm never draws.",
        )
        ToggleRow(
            "Hold finger to pick color",
            prefs.longPressEyedropper,
            { prefs.longPressEyedropper = it },
            description = "With the brush, bucket, shapes, curves or text, keep a finger still on the canvas to pick the color under it",
        )
        SectionHeader("Layout")
        ToggleRow(
            "Left-handed layout",
            prefs.leftHanded,
            { prefs.leftHanded = it },
            description = "Brush size and opacity values and the eyedropper on the left of the slider bar",
        )
        SectionHeader("Autosave every")
        val choices = EditorPrefs.AUTOSAVE_CHOICES
        ChoiceChips(
            options = choices.map { "$it s" },
            selected = choices.indexOf(prefs.autosaveSeconds),
            onSelect = { prefs.autosaveSeconds = choices[it] },
        )
    }
}
