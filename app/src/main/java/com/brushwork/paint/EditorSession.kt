package com.brushwork.paint

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.engine.PerfTrace
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/**
 * An open document: loads it, owns the [EditorController], autosaves periodically and on exit.
 * Held by [BrushworkApp] (not a ViewModel) so it survives rotation and is released on close.
 */
class EditorSession(private val app: BrushworkApp, val projectId: String) {
    sealed interface State {
        data object Loading : State
        data class Ready(val controller: EditorController) : State
        data class Failed(val message: String) : State
    }

    /** The editor scope; v1.9 (design 2.4.13): with [ErrorReporter.handler] (F1b: v1.8's outcome). */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ErrorReporter.handler)
    var state by mutableStateOf<State>(State.Loading)
        private set

    private var controller: EditorController? = null
    private val saveMutex = Mutex()
    private var lastSavedEdit = 0
    var closed = false
        private set

    init {
        scope.launch {
            try {
                // v1.9 (design 2.4.4): opening (loading the files, setting up the editor) as PerfTrace.OPEN.
                val c = PerfTrace.span(PerfTrace.OPEN) {
                    val doc = app.repository.load(projectId)
                    EditorController(app, doc, scope, app.settings)
                }
                controller = c
                // v1.9 (design 2.4.13): sizes and counts only.
                Breadcrumbs.add("open:${c.doc.width}x${c.doc.height}/${c.doc.layers.size}")
                state = State.Ready(c)
                autosaveLoop(c)
            } catch (e: OutOfMemoryError) {
                // v1.9 (A7a): kept as a handled report (sizes only: no name, no path).
                CrashLog.record(e, "open", fatal = false)
                state = State.Failed("Not enough memory to open this artwork")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                CrashLog.record(e, "open", fatal = false)
                state = State.Failed("Could not open the artwork: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    private suspend fun autosaveLoop(c: EditorController) {
        while (scope.isActive) {
            delay(app.settings.autosaveSeconds.coerceAtLeast(10) * 1000L)
            if (c.editCount != lastSavedEdit && !c.isInteracting && c.busyMessage == null) save()
        }
    }

    /** Saves if there are unsaved edits. Runs on the app scope so it completes in the background. */
    fun saveNow(): Job = app.appScope.launch { save() }

    // region v19-A7b saveOnLeaving
    /**
     * v1.7 QA: the app leaves the screen (Home, app switch, screen off) and may be killed in the
     * background long before the next autosave. Work the user asked for that is still landing (a
     * vector or array render, a saved selection compressing) lands first, as on [close], so this
     * save holds it; then [saveNow]. Pending tool work is left as it is (it is still pending on
     * return). ("Save now" and the autosave don't wait: they save what has landed, consistently.)
     */
    fun saveOnLeaving(): Job {
        controller?.let { c ->
            runCatching { c.settleVectorWork() }
            runCatching { c.landSavedSelections() }
        }
        return saveNow()
    }
    // endregion

    suspend fun save() {
        saveMutex.withLock {
            val c = controller ?: return
            val edits = c.editCount
            if (edits == lastSavedEdit) return
            try {
                Breadcrumbs.add("save:start")
                val thumb = c.compositor.renderThumbnail(512)
                app.repository.save(c.doc, thumb)
                lastSavedEdit = edits
                Breadcrumbs.add("save:end")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                CrashLog.record(e, "save", fatal = false)
                c.toast("Autosave failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Commits pending tool work, saves, releases memory, then calls [onClosed]. */
    fun close(onClosed: () -> Unit) {
        if (closed) return
        closed = true
        // The editor stays on screen until the save is done, and the save copies the layers one
        // by one between file writes: anything drawn meanwhile would be dropped with the
        // controller. Show "Saving…" instead, which also makes the canvas ignore input.
        val saved = CompletableDeferred<Unit>()
        controller?.runBusy("Saving…") { saved.await() }
        app.appScope.launch {
            try {
                controller?.let { c ->
                    // A vector edit still rendering in the background and the Object bar actions
                    // waiting for it (v1.5) land now, so they are saved (dispose() would drop
                    // them); before the tool lets go, as an action may lift its objects again.
                    runCatching { c.settleVectorWork() }.onFailure { kept(it, "close: vector work") }
                    runCatching { c.currentTool.onDeactivate() }.onFailure { kept(it, "close: tool") }
                    // (What the tool's commit rendered lands too.)
                    runCatching { c.vectors.flushPending() }.onFailure { kept(it, "close: vector renders") }
                    // A saved selection still compressing (v1.7) lands, so it is saved too.
                    runCatching { c.landSavedSelections() }.onFailure { kept(it, "close: saved selections") }
                }
                save()
                controller?.dispose()
                controller = null
            } finally {
                saved.complete(Unit)
                scope.cancel()
            }
            onClosed()
        }
    }

    /** v1.9 (A7a): what closing could not finish is kept as a handled report (a cancellation is none). */
    private fun kept(e: Throwable, where: String) {
        if (e !is kotlinx.coroutines.CancellationException) CrashLog.record(e, where, fatal = false)
    }
}
