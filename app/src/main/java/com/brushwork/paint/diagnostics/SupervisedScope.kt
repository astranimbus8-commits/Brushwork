package com.brushwork.paint.diagnostics

import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberCoroutineScope
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob

/**
 * v1.9 Gate 2 (R9, A7a's open item): the coroutine scope of a composable, a supervisor. Its jobs run
 * as with `rememberCoroutineScope` (the composition's dispatcher and frame clock, cancelled when the
 * composable leaves) under [ErrorReporter.handler], but one failed job ends only itself: its
 * siblings, the composable and the window's Recomposer go on, so a release build keeps the error
 * as a report, says it and goes on (a plain `rememberCoroutineScope` is no supervisor: there the
 * error ends the Recomposer and is handed on). `UnguardedLaunchGateTest` fails a
 * `rememberCoroutineScope` anywhere else.
 */
@Composable
fun rememberSupervisedScope(): CoroutineScope {
    // The composition's own scope (the one place it is used): its job ends when the composable leaves.
    val composed = rememberCoroutineScope { ErrorReporter.handler }
    return remember(composed) {
        val context = composed.coroutineContext
        CoroutineScope(context + SupervisorJob(context[Job]) + ErrorReporter.handler)
    }
}
