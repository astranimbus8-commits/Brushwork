package com.brushwork.paint.ui.mask

import android.content.SharedPreferences
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.MutableState
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.ui.common.ToggleRow

/** Label of the "Fast adjustment preview" switch (v1.6 §3.1a; the same in the Masks tool's sheet and in Settings). */
const val FAST_ADJUST_PREVIEW_LABEL = "Fast adjustment preview"

/** What the switch does, under its label. */
const val FAST_ADJUST_PREVIEW_DESCRIPTION =
    "Adjustment sliders and mask handles show a quick preview while you drag and sharpen it as soon as you stop. Turn off to always draw the exact image."

/**
 * The "Fast adjustment preview" switch of the Masks tool's sheet (v1.6 §3.1a): live adjustment
 * sessions on or off ([com.brushwork.paint.engine.live.LiveAdjust.fastPreview], saved in
 * `AppSettings.fastAdjustPreview`). Turning it off mid-session ends the session (the next frame
 * is exact). It shows the saved setting ([rememberSavedFastPreview]), not the live adjustment's
 * cached copy, which only catches up with a change made in Settings at the next drag.
 */
@Composable
fun FastAdjustPreviewToggle(controller: EditorController) {
    val on = rememberSavedFastPreview(controller.settings)
    ToggleRow(
        FAST_ADJUST_PREVIEW_LABEL,
        on.value,
        { v ->
            controller.liveAdjust.fastPreview = v
            on.value = v
        },
        description = FAST_ADJUST_PREVIEW_DESCRIPTION,
    )
}

/**
 * The same switch in the editor's Settings (`EditorSettingsDialog`, above "Safe compositing"; the
 * dialog works from the preferences and may have no controller): it saves
 * `AppSettings.fastAdjustPreview`, and the editor's live adjustment takes it over from the next
 * drag. The only "Fast adjustment preview" switch besides the Masks tool's.
 */
@Composable
fun FastAdjustPreviewToggle(settings: AppSettings) {
    val on = rememberSavedFastPreview(settings)
    ToggleRow(
        FAST_ADJUST_PREVIEW_LABEL,
        on.value,
        { v ->
            settings.fastAdjustPreview = v
            on.value = v
        },
        description = FAST_ADJUST_PREVIEW_DESCRIPTION,
    )
}

/**
 * The saved `AppSettings.fastAdjustPreview`, kept current while the switch is composed: the two
 * switches are one setting, and the Masks tool's sheet stays composed (open or minimized to its
 * pill) while Settings changes it over the sheet, so each follows a change the other saves
 * instead of showing a stale "on". Any preference change re-reads the one value (a boolean read;
 * the key itself is [AppSettings]'s business, and `clear()` reports no key).
 */
@Composable
private fun rememberSavedFastPreview(settings: AppSettings): MutableState<Boolean> {
    val on = remember(settings) { mutableStateOf(settings.fastAdjustPreview) }
    DisposableEffect(settings) {
        // SharedPreferences holds its listeners weakly: this one lives as long as onDispose below.
        val listener = SharedPreferences.OnSharedPreferenceChangeListener { _, _ ->
            val saved = settings.fastAdjustPreview
            if (on.value != saved) on.value = saved
        }
        settings.prefs.registerOnSharedPreferenceChangeListener(listener)
        // A change saved between the first composition and now.
        settings.fastAdjustPreview.let { if (on.value != it) on.value = it }
        onDispose { settings.prefs.unregisterOnSharedPreferenceChangeListener(listener) }
    }
    return on
}
