package com.brushwork.paint.ui.diagnostics

import androidx.compose.runtime.Composable
import com.brushwork.paint.diagnostics.CrashReport

/**
 * v1.9 (R9, design 2.4.14; owner A7a): the gallery's card for a crash report not yet seen,
 * tagged [com.brushwork.paint.ui.common.V19Tags.CRASH_CARD]. F1b: empty (no report is kept, so
 * it is never called).
 */
@Suppress("UNUSED_PARAMETER")
@Composable
fun CrashCard(report: CrashReport, onCopy: () -> Unit, onShare: () -> Unit, onDismiss: () -> Unit) {
}
