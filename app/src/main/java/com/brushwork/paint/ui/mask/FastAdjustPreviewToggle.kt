package com.brushwork.paint.ui.mask

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
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
 * is exact). It shows the saved setting (read once when the sheet composes), not the live
 * adjustment's cached copy, which only catches up with a change made in Settings at the next drag.
 */
@Composable
fun FastAdjustPreviewToggle(controller: EditorController) {
    var on by remember(controller) { mutableStateOf(controller.settings.fastAdjustPreview) }
    ToggleRow(
        FAST_ADJUST_PREVIEW_LABEL,
        on,
        { v ->
            controller.liveAdjust.fastPreview = v
            on = v
        },
        description = FAST_ADJUST_PREVIEW_DESCRIPTION,
    )
}

/**
 * The same switch for the editor's Settings (E's `EditorSettingsDialog`, which has the
 * preferences but no controller): it saves `AppSettings.fastAdjustPreview`, and the editor's live
 * adjustment takes it over from the next drag.
 */
@Composable
fun FastAdjustPreviewToggle(settings: AppSettings) {
    var on by remember(settings) { mutableStateOf(settings.fastAdjustPreview) }
    ToggleRow(
        FAST_ADJUST_PREVIEW_LABEL,
        on,
        { v ->
            settings.fastAdjustPreview = v
            on = v
        },
        description = FAST_ADJUST_PREVIEW_DESCRIPTION,
    )
}
