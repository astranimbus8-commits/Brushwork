package com.brushwork.paint.ui.common

import androidx.compose.runtime.staticCompositionLocalOf
import com.brushwork.paint.snap.Increments

/**
 * The editor's increments service for the shared controls (v1.6 §3.4; frozen): provided by
 * `EditorScreen` (`controller.increments`); null outside the editor (gallery, dialogs of other
 * screens), where controls never step.
 */
val LocalIncrements = staticCompositionLocalOf<Increments?> { null }
