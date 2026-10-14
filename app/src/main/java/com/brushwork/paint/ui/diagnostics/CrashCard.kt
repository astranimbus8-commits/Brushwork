package com.brushwork.paint.ui.diagnostics

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.heightIn
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TextButton
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import com.brushwork.paint.diagnostics.CrashReport
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.theme.BrushworkColors

/**
 * v1.9 (R9, design 2.4.14 and 3.2 A7a step 5; owner A7a): the gallery's card for a crash report
 * not yet seen ([com.brushwork.paint.diagnostics.CrashLog.unseen]), tagged [V19Tags.CRASH_CARD].
 *
 * [StabilityLabels19.CRASH_TITLE] and [StabilityLabels19.CRASH_SAVED]; for a report of memory
 * (out of memory, closed by Android for memory) also [StabilityLabels19.CRASH_OOM]. Then one line of
 * buttons, each at least [BUTTON_MIN] tall, wrapping only at large font scales:
 * [StabilityLabels19.COPY] copies the report ([ReportSharing.copy], then [onCopy]),
 * [StabilityLabels19.SHARE] opens the share sheet with it ([ReportSharing.share], then [onShare]),
 * [StabilityLabels19.DISMISS] calls [onDismiss] (the gallery marks the report seen).
 */
@OptIn(ExperimentalLayoutApi::class)
@Composable
fun CrashCard(report: CrashReport, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
    val context = LocalContext.current
    Surface(
        color = BrushworkColors.ChromeHigh,
        contentColor = BrushworkColors.OnChrome,
        shape = RoundedCornerShape(12.dp),
        border = BorderStroke(1.dp, BrushworkColors.ChromeBorder),
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 16.dp, vertical = 8.dp)
            .testTag(V19Tags.CRASH_CARD),
    ) {
        Column(Modifier.padding(start = 16.dp, end = 8.dp, top = 12.dp, bottom = 4.dp)) {
            Text(StabilityLabels19.CRASH_TITLE, style = MaterialTheme.typography.titleSmall, modifier = Modifier.padding(end = 8.dp))
            Text(
                StabilityLabels19.CRASH_SAVED,
                style = MaterialTheme.typography.bodySmall,
                color = BrushworkColors.OnChromeDim,
                modifier = Modifier.padding(top = 2.dp, end = 8.dp),
            )
            if (isMemory(report.kind)) {
                Text(
                    StabilityLabels19.CRASH_OOM,
                    style = MaterialTheme.typography.bodySmall,
                    color = BrushworkColors.OnChromeDim,
                    modifier = Modifier.padding(top = 2.dp, end = 8.dp),
                )
            }
            FlowRow(
                Modifier.fillMaxWidth().padding(top = 4.dp),
                horizontalArrangement = Arrangement.spacedBy(4.dp, androidx.compose.ui.Alignment.End),
            ) {
                CardButton(StabilityLabels19.COPY) { if (ReportSharing.copy(context, report)) onCopy() }
                CardButton(StabilityLabels19.SHARE) { if (ReportSharing.share(context, report)) onShare() }
                CardButton(StabilityLabels19.DISMISS, onDismiss)
            }
        }
    }
}

/** A report of memory: the card adds [StabilityLabels19.CRASH_OOM]. */
internal fun isMemory(kind: CrashReport.Kind) =
    kind == CrashReport.Kind.OUT_OF_MEMORY || kind == CrashReport.Kind.LOW_MEMORY_KILL

/** The least height of the card's and the Diagnostics list's buttons. */
internal val BUTTON_MIN = 40.dp

/** A text button of the crash card and the Diagnostics list: at least [BUTTON_MIN] tall, its text wrapped, never cut. */
@Composable
internal fun CardButton(label: String, onClick: () -> Unit) {
    TextButton(
        onClick = onClick,
        modifier = Modifier.heightIn(min = BUTTON_MIN),
        contentPadding = PaddingValues(horizontal = 12.dp, vertical = 6.dp),
    ) {
        Text(label, color = BrushworkColors.Accent, textAlign = TextAlign.Center)
    }
}
