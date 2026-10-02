package com.brushwork.paint.ui.editor

import androidx.compose.runtime.Composable
import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.ChoiceChips
import com.brushwork.paint.ui.common.IncrementsSettingsSection
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

    /**
     * "Fast adjustment preview" (v1.6 §3.1): live adjustment drags draw a proxy and refine. Read
     * straight from [AppSettings] (the Masks tool's sheet changes it too); the Settings dialog
     * keeps its own state while it is open.
     */
    var fastAdjustPreview: Boolean
        get() = settings.fastAdjustPreview
        set(v) { settings.fastAdjustPreview = v }

    /** Bumped when [safeCompositing] is set here (the value itself also changes from the Masks tool). */
    private var safeCompositingSets by mutableIntStateOf(0)

    /**
     * "Safe compositing" (v1.5, I5's kill switch): adjustment layers show on the canvas without
     * their effect. Read from the compositor's live switch (the Masks tool's sheet changes it too),
     * saved in [AppSettings.safeCompositing].
     */
    var safeCompositing: Boolean
        get() {
            safeCompositingSets
            return AdjustmentStage.safeCompositing
        }
        set(v) {
            settings.safeCompositing = v
            AdjustmentStage.safeCompositing = v
            safeCompositingSets++
        }

    companion object {
        val AUTOSAVE_CHOICES = listOf(15, 30, 45, 60, 120)
    }
}

/**
 * Gesture, layout, display, increments and autosave preferences of the editor. [onCanvasChanged]
 * redraws the canvas after a display setting changed. With the editor's [controller] (v1.6) the
 * dialog also offers the Increments section (area G's [IncrementsSettingsSection]).
 */
@Composable
fun EditorSettingsDialog(prefs: EditorPrefs, onDismiss: () -> Unit, onCanvasChanged: () -> Unit = {}, controller: EditorController? = null) {
    // Read fresh when the dialog opens: the Masks tool's sheet changes the same setting.
    var fastPreview by remember(prefs) { mutableStateOf(prefs.fastAdjustPreview) }
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
            description = "Mirrors the brush size and opacity rows: their values on the right",
        )
        SectionHeader("Display")
        ToggleRow(
            "Fast adjustment preview",
            fastPreview,
            { fastPreview = it; prefs.fastAdjustPreview = it; onCanvasChanged() },
            description = "While you drag an adjustment or its mask, the canvas shows a quick preview and sharpens when you stop",
        )
        ToggleRow(
            "Safe compositing",
            prefs.safeCompositing,
            { prefs.safeCompositing = it; onCanvasChanged() },
            description = "Show adjustment layers on the canvas without their effect (if the canvas misbehaves). Exports, merging and the eyedropper still use the effect.",
        )
        if (controller != null) {
            SectionHeader("Increments")
            IncrementsSettingsSection(controller)
        }
        SectionHeader("Autosave every")
        val choices = EditorPrefs.AUTOSAVE_CHOICES
        ChoiceChips(
            options = choices.map { "$it s" },
            selected = choices.indexOf(prefs.autosaveSeconds),
            onSelect = { prefs.autosaveSeconds = choices[it] },
        )
    }
}
