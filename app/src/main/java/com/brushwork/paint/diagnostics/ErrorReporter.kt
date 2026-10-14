package com.brushwork.paint.diagnostics

import android.os.Handler
import android.os.Looper
import android.widget.Toast
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineName
import kotlin.coroutines.CoroutineContext

/**
 * v1.9 (R9, design 2.4.13 and 3.2 A7a step 4; owner A7a): the exception handler of every coroutine
 * scope the app creates (the app, editor and font scopes, the composables' scopes), so what an
 * uncaught error does is decided in one place. `UnguardedLaunchGateTest` fails a new scope without it.
 *
 * - Release builds: the error is kept as a handled report ([CrashLog.record]), then on the main
 *   thread the open editor's tool is reset (its touch cancelled: `Tool.onCancel`) and
 *   [StabilityLabels19.ERROR_TOAST] is shown with the tool's name (outside the editor, the error's
 *   class), and the app goes on: the scopes are `SupervisorJob`s, so only the failed job ends.
 * - Debug builds, tests (Robolectric) and a JVM with no app: the error goes to the thread's
 *   uncaught-exception handler as in v1.8 (and so to [CrashLog]'s handler: the app stops with a
 *   report), so a bug is never hidden while the app is built.
 *
 * Never throws and never blocks: what fails while handling an error is dropped.
 */
object ErrorReporter {
    /** The handler: see [ErrorReporter]. */
    val handler: CoroutineExceptionHandler = CoroutineExceptionHandler { context, e -> handle(context, e) }

    /** Tests: true acts as a release build, false as a debug one; null is the build's own. */
    @Volatile
    internal var releaseOverride: Boolean? = null

    /** Tests: the open editor (null: the app's editor session's, when it is ready). */
    @Volatile
    internal var editorOverride: (() -> EditorController?)? = null

    /** True when an error is recovered from (a release build), false when it is handed on. */
    internal val recovers: Boolean get() = releaseOverride ?: (CrashLog.app != null && !CrashLog.isDebuggable)

    private val main by lazy { Handler(Looper.getMainLooper()) }

    internal fun handle(context: CoroutineContext, e: Throwable) {
        if (!recovers) {
            val thread = Thread.currentThread()
            thread.uncaughtExceptionHandler?.uncaughtException(thread, e) ?: throw e
            return
        }
        try {
            val name = context[CoroutineName]?.name
            CrashLog.record(e, if (name.isNullOrBlank()) "coroutine" else "coroutine $name", fatal = false)
        } catch (_: Throwable) {
        }
        try {
            main.post { recover(e) }
        } catch (_: Throwable) {
        }
    }

    /** The editor's tool reset and the toast (main thread). */
    private fun recover(e: Throwable) {
        try {
            val c = editor()
            val tool = c?.let { runCatching { it.currentTool }.getOrNull() }
            val label = tool?.id?.label ?: e.javaClass.simpleName
            val text = StabilityLabels19.ERROR_TOAST.format(label)
            if (c != null) {
                runCatching { tool?.onCancel() }
                runCatching { c.invalidateOverlay() }
                c.toast(text)
            } else {
                CrashLog.app?.let { Toast.makeText(it, text, Toast.LENGTH_LONG).show() }
            }
        } catch (_: Throwable) {
        }
    }

    private fun editor(): EditorController? {
        editorOverride?.let { return it() }
        val session = (CrashLog.app as? BrushworkApp)?.editorSession ?: return null
        return (session.state as? EditorSession.State.Ready)?.controller
    }
}
