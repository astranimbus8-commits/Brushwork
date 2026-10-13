package com.brushwork.paint.diagnostics

import kotlinx.coroutines.CoroutineExceptionHandler

/**
 * v1.9 (R9, design 2.4.13; owner A7a): the exception handler of every coroutine scope the app
 * creates (the app, editor and font scopes), so A7a changes what an uncaught error does in one
 * place.
 */
object ErrorReporter {
    /**
     * F1b: rethrows (v1.8). A7a: release builds record, toast StabilityLabels19.ERROR_TOAST and continue; debug and tests rethrow.
     *
     * F1b hands the error to the current thread's uncaught-exception handler, as kotlinx does
     * for a scope without a handler (v1.8's outcome: the app stops). The difference: kotlinx's
     * platform pre-handlers (on Android, the one that logs before the thread's handler) are
     * skipped once a scope has a handler.
     */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { _, e ->
        val thread = Thread.currentThread()
        thread.uncaughtExceptionHandler?.uncaughtException(thread, e) ?: throw e
    }
}
