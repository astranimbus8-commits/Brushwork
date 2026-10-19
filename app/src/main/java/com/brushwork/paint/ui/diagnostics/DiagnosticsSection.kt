package com.brushwork.paint.ui.diagnostics

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import com.brushwork.paint.AppSettings
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.diagnostics.rememberSupervisedScope
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.ui.common.SectionHeader
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.ToggleRow
import com.brushwork.paint.ui.theme.BrushworkColors
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext

/**
 * v1.9 (R9, design 2.4.14 and 3.2 A7a step 5; owner A7a): the Diagnostics section of the editor's
 * settings, after [com.brushwork.paint.ui.backup.BackupSection].
 *
 * - F3: the frame times switch ([StabilityLabels19.SHOW_FRAME_TIMES], `AppSettings.showFrameTimes`):
 *   on, the timings are recorded ([PerfTrace.enabled], the old samples forgotten) and the canvas
 *   draws the frame-times overlay at its top left (`CanvasView`, tag `V19Tags.FRAME_TIMES`); off,
 *   both stop.
 * - A7a, under it: [StabilityLabels19.CRASH_REPORTS] with the number of reports kept (read off the
 *   main thread), then each report, newest first: its summary ("Crash: IllegalStateException") and
 *   its own [StabilityLabels19.COPY_REPORT] and [StabilityLabels19.SHARE_REPORT] buttons, which name
 *   its time ([ReportSharing.rowTimes]) so no two buttons read alike. With reports,
 *   [StabilityLabels19.SHARE_ALL] (one share sheet for all) and [StabilityLabels19.DELETE_REPORTS]
 *   (at once: reports hold nothing of the artwork).
 */
@OptIn(ExperimentalLayoutApi::class)
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

    // A7a: the crash reports, read off the main thread (up to 20 files of 64 KB).
    val scope = rememberSupervisedScope()
    var loads by remember { mutableIntStateOf(0) }
    var reports by remember { mutableStateOf<List<CrashReport>?>(null) }
    LaunchedEffect(context, loads) {
        reports = try {
            withContext(Dispatchers.IO) { CrashLog.reports(context) }
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            emptyList()
        }
    }
    val list = reports ?: return
    Text(
        StabilityLabels19.CRASH_REPORTS.format(list.size),
        style = MaterialTheme.typography.bodyMedium,
        modifier = Modifier.padding(top = 8.dp, bottom = 2.dp),
    )
    val times = remember(list) { ReportSharing.rowTimes(list.map { it.timeMillis }) }
    list.forEachIndexed { i, r ->
        Column(Modifier.fillMaxWidth().padding(top = 6.dp)) {
            Text(r.summary, style = MaterialTheme.typography.bodySmall, color = BrushworkColors.OnChromeDim)
            FlowRow(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
                CardButton(StabilityLabels19.COPY_REPORT.format(times[i])) { ReportSharing.copy(context, r) }
                CardButton(StabilityLabels19.SHARE_REPORT.format(times[i])) { ReportSharing.share(context, r) }
            }
        }
    }
    if (list.isEmpty()) return
    FlowRow(Modifier.fillMaxWidth().padding(top = 6.dp), horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        CardButton(StabilityLabels19.SHARE_ALL) {
            scope.launch {
                // Past 100 KB the reports are written to a file first: off the main thread.
                val intent = withContext(Dispatchers.IO) { ReportSharing.shareAllIntent(context, list) }
                ReportSharing.shareAll(context, intent)
            }
        }
        CardButton(StabilityLabels19.DELETE_REPORTS) {
            scope.launch {
                withContext(Dispatchers.IO) { CrashLog.deleteAll(context) }
                loads++
            }
        }
    }
}
