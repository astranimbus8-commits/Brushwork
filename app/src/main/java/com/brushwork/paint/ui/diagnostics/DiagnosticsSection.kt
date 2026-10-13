package com.brushwork.paint.ui.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.AppSettings
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.ToggleRow

/**
 * v1.9 (R9, design 2.4.14; owner A7a): the Diagnostics section of the editor's settings, after
 * [com.brushwork.paint.ui.backup.BackupSection]. F3 gives it its first content, the "Show frame
 * times" switch ([StabilityLabels19.SHOW_FRAME_TIMES], `AppSettings.showFrameTimes`): on, the
 * timings are recorded ([PerfTrace.enabled], the old samples forgotten) and the canvas draws the
 * frame-times overlay at its top left (`CanvasView`, tag `V19Tags.FRAME_TIMES`); off, both stop.
 * A7a adds the crash reports under it.
 */
@Composable
fun DiagnosticsSection() {
    val context = LocalContext.current
    // The app's settings (the editor's are the same preferences file), or this dialog's own.
    val settings = remember(context) {
        (context.applicationContext as? BrushworkApp)?.settings ?: AppSettings(context)
    }
    var showFrameTimes by remember(settings) { mutableStateOf(settings.showFrameTimes) }
    SectionHeader(StabilityLabels19.DIAGNOSTICS)
    ToggleRow(
        StabilityLabels19.SHOW_FRAME_TIMES,
        showFrameTimes,
        { on ->
            if (on) PerfTrace.clear()
            PerfTrace.enabled = on
            settings.showFrameTimes = on
            showFrameTimes = on
        },
    )
}
