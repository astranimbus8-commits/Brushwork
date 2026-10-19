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
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlin.coroutines.CoroutineContext

/**
 * v1.9 (R9, design 2.4.13 and 3.2 A7a step 4; owner A7a): the exception handler of every coroutine
 * scope the app creates (the app, editor and font scopes, the composables' scopes), so what an
 * uncaught error does is decided in one place. `UnguardedLaunchGateTest` fails a new scope without it.
 *
 * - Release builds: the error is kept as a handled report ([CrashLog.record]), then on the main
 *   thread the open editor's tool is reset (its touch cancelled: `Tool.onCancel`) and
 *   [StabilityLabels19.ERROR_TOAST] is shown with the tool's name (outside the editor, the error's
 *   class), and the app goes on: the app, editor and font scopes and the composables' scopes
 *   ([rememberSupervisedScope], Gate 2) are `SupervisorJob`s, so only the failed job ends.
 * - Release builds, a job whose error ends its scope ([tookItsScopeDown]): the scope is no
 *   supervisor. A plain `rememberCoroutineScope` is one (the composables use
 *   [rememberSupervisedScope] instead): Compose makes it a plain child of the window's
 *   Recomposer, so the error cancels the Recomposer too, and the screen would never draw or take
 *   a touch again. Going on would leave a dead screen, so the error is handed on as in a debug
 *   build: the app stops with a report (v1.8's outcome, now kept).
 *   A supervisor scope closed by its owner (the editor scope as the editor closes) is not ended
 *   by a job that throws as it unwinds: that error is recovered from.
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
        if (!recovers || tookItsScopeDown(context)) {
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

    /**
     * True when the failed job's error ends its scope: the scope ([context]'s job's parent) is no
     * `SupervisorJob` and is no longer active. A job's error reaches this handler only after it has
     * gone to its parent. A parent that is no supervisor is cancelled by it, or was ending already
     * and passes the error on up as it ends; so is every plain job above it. A plain
     * `rememberCoroutineScope` is such a scope, also after the composable has left (its job
     * throwing as it unwinds): the window's Recomposer above it is cancelled too.
     * A supervisor scope never takes its jobs' errors: running, or closed by its owner (the editor
     * scope as the editor closes, a job that throws as it unwinds), it is recovered from.
     * A root job (no parent) is recovered from.
     */
    @OptIn(ExperimentalCoroutinesApi::class)
    internal fun tookItsScopeDown(context: CoroutineContext): Boolean = try {
        val parent = context[Job]?.parent
        parent != null && !parent.isActive && parent.javaClass !== supervisorJobClass
    } catch (_: Throwable) {
        // Not even the job's state can be read: never go on with a scope in doubt.
        true
    }

    /** The class `SupervisorJob()` makes, told by identity (so a shrunk release build tells it too). */
    private val supervisorJobClass: Class<*> by lazy { SupervisorJob().javaClass }

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
