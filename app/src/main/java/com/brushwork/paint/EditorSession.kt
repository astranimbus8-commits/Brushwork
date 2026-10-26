package com.brushwork.paint

import android.os.Build
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.check.CheckStore
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.job
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

    // region v110-A10 session
    // v1.10 (A10): the session's swap store is installed in the second region of this name (after
    // the engine session below is open); NativeWatch and the editor-open marker run with the
    // editor's lifecycle in `EngineEditorChrome`.
    // endregion

    private var controller: EditorController? = null
    private val saveMutex = Mutex()
    private var lastSavedEdit = 0
    var closed = false
        private set

    /** v1.10 (F1): this session's place among the sessions opened in this process ([engineSessions]). */
    private val engineSession = ++engineSessions

    // v1.10 (F1, J32, I31): the engine's level, budgets and residency for this session, fixed before
    // the controller exists (A10 fills CheckStore and the probes; in F1 there is no check and memory
    // is unknown, so the session runs at CLASSIC with v1.9's numbers). The engine session ends with
    // this session's scope, after close() has saved and released the controller; a session opened
    // meanwhile (another artwork while this one still saves) keeps its own.
    init {
        val check = CheckStore.read(app)
        val budgets = Budgets.compute(EngineConfig.memoryProbe, check?.stepDown ?: 0)
        val totalMem = runCatching { EngineConfig.memoryProbe.snapshot().totalMem }.getOrDefault(0L)
        val freeBytes = runCatching { EngineConfig.storageProbe.freeBytes(app.filesDir) }.getOrDefault(0L)
        val deviceKey = EngineConfig.deviceKey(Build.MODEL.orEmpty(), totalMem)
        EngineConfig.openSession(
            EngineConfig.resolve(app.settings.layerStorageMode, check, deviceKey, freeBytes, memoryKnown = budgets.known),
            budgets,
            check,
        )
        scope.coroutineContext.job.invokeOnCompletion { if (engineSession == engineSessions) EngineConfig.closeSession() }
    }

    // region v110-A11 open
    /**
     * v1.10 (A11, J16, UQ16): true while [state] is Classic's refusal of an artwork that does not
     * fit Classic layer storage and Classic's reason is not memory (the user chose Classic, or the
     * check chose it for slow drawing or slow storage): the editor then offers
     * `EngineLabels110.OPEN_WITH_FULL`, which calls [openWithFullStorage].
     */
    var offersFullStorage by mutableStateOf(false)
        private set

    init {
        open()
    }

    /**
     * v1.10 (A11, J16): opens this artwork once at TILES or FULL (FULL with 2 GiB of free storage)
     * after Classic refused it, without changing the setting: the next artwork opens at the
     * setting's level again. Does nothing unless [offersFullStorage], or when a newer session owns
     * the engine. Main thread.
     */
    fun openWithFullStorage() {
        if (!offersFullStorage || closed || engineSession != engineSessions) return
        offersFullStorage = false
        val check = CheckStore.read(app)
        val freeBytes = runCatching { EngineConfig.storageProbe.freeBytes(app.filesDir) }.getOrDefault(0L)
        EngineConfig.openSession(
            com.brushwork.paint.storage.ClassicOpen.onceLevel(freeBytes),
            Budgets.compute(EngineConfig.memoryProbe, check?.stepDown ?: 0),
            check,
        )
        state = State.Loading
        open()
    }

    private fun open() {
        scope.launch {
            try {
                // v1.9 (design 2.4.4): opening (loading the files, setting up the editor) as PerfTrace.OPEN.
                // v1.10 (A11, design 2.9): the load streams each layer into its store (LayerEntries);
                // a refused admission is an OutOfMemoryError, caught below. The open's ms go to EngineStats.
                val started = System.nanoTime()
                val c = PerfTrace.span(PerfTrace.OPEN) {
                    val doc = app.repository.load(projectId)
                    EditorController(app, doc, scope, app.settings)
                }
                com.brushwork.paint.engine.EngineStats.record(com.brushwork.paint.engine.EngineStat.OPEN, (System.nanoTime() - started) / 1_000_000f)
                controller = c
                // v1.9 (design 2.4.13): sizes and counts only.
                Breadcrumbs.add("open:${c.doc.width}x${c.doc.height}/${c.doc.layers.size}")
                state = State.Ready(c)
                autosaveLoop(c)
            } catch (e: com.brushwork.paint.storage.ClassicCapacityException) {
                // v1.10 (A11, J16, UQ16): not a failure to report; the text says why, and the
                // offer stands unless Classic's reason is memory.
                Breadcrumbs.add("open:classic-refused:${e.painted}/${e.capacity}")
                val totalMem = runCatching { EngineConfig.memoryProbe.snapshot().totalMem }.getOrDefault(0L)
                val forMemory = com.brushwork.paint.storage.ClassicOpen.refusedForMemory(
                    CheckStore.read(app),
                    app.settings.layerStorageMode,
                    EngineConfig.deviceKey(Build.MODEL.orEmpty(), totalMem),
                )
                offersFullStorage = !forMemory
                state = State.Failed(com.brushwork.paint.storage.ClassicOpen.refusalText(forMemory))
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
    // endregion

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
     *
     * v1.9 (A7b step 5, R9, design 3.2 A7b): on the app scope, without holding the main thread
     * (v1.8 waited on it for good, and Android offers to close an app that does that on the way
     * out): it waits up to 1.5 s for the renders still running and until the saved selections are
     * compressed (`EditorController.awaitDeferredWork`), then lands what is still pending on the
     * spot, through the synchronous path ([EditorController.leavingDeferred]: never cancelled),
     * and saves. The user's work is never dropped on leaving.
     */
    fun saveOnLeaving(): Job {
        val c = controller ?: return saveNow()
        return app.appScope.launch {
            // From the start: a step recorded meanwhile (a saved selection landing) lands the
            // render too, on the spot, instead of cancelling it.
            leavingSaves++
            c.leavingDeferred = true
            try {
                try {
                    c.awaitDeferredWork()
                } catch (e: kotlinx.coroutines.CancellationException) {
                    throw e
                } catch (e: Throwable) {
                    kept(e, "leaving: waiting for work")
                }
                runCatching { c.settleVectorWork() }.onFailure { kept(it, "leaving: vector work") }
                runCatching { c.landSavedSelections() }.onFailure { kept(it, "leaving: saved selections") }
            } finally {
                if (--leavingSaves == 0) c.leavingDeferred = false
            }
            save()
        }
    }

    /** The [saveOnLeaving] calls still landing work (Home pressed twice quickly). Main thread. */
    private var leavingSaves = 0
    // endregion

    // ------------------------------------------------------------------ saving

    // region v110-A11 save
    /** Saves the document and its thumbnail if there are unsaved edits, one save at a time. */
    suspend fun save() {
        saveMutex.withLock {
            val c = controller ?: return
            val edits = c.editCount
            if (edits == lastSavedEdit) return
            try {
                Breadcrumbs.add("save:start")
                val thumb = thumbnailForSave(c)
                try {
                    app.repository.save(c.doc, thumb)
                } finally {
                    thumb.recycle()
                }
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

    /**
     * v1.10 (A11, design 2.9, finding 54): the 512 px thumbnail of a save. Main takes only the
     * snapshots (`SaveThumbnail.take`); the picture is rendered on the save writer (IO) and the
     * snapshots are let go of back on main. At Classic it is rendered here, as in v1.9.
     */
    private suspend fun thumbnailForSave(c: EditorController): android.graphics.Bitmap {
        val snap = com.brushwork.paint.storage.SaveThumbnail.take(c.doc) ?: return c.compositor.renderThumbnail(512)
        try {
            return kotlinx.coroutines.withContext(Dispatchers.IO) { snap.render(512) }
        } finally {
            snap.release()
        }
    }
    // endregion

    // ------------------------------------------------------------------ closing

    // region v110-A10 session
    init {
        // v1.10 (A10, design 2.8): at FULL the session's swap store (noBackupFilesDir/swap/<uuid>)
        // is installed once the engine session is open (the init above; initializers run in
        // source order), while open() still waits for the load, so before the editor exists and
        // before any tile can be written out (only TileWorker jobs write, posted by the residency
        // policy the editor attaches); the session's end (EngineConfig.closeSession) closes it
        // and deletes its directory.
        // openWithFullStorage opens the engine session again without a store; EngineEditorChrome
        // installs one then.
        com.brushwork.paint.engine.tiles.SlabSwapStore.installIfFull(app)
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
    // endregion

    /** v1.9 (A7a): what closing could not finish is kept as a handled report (a cancellation is none). */
    private fun kept(e: Throwable, where: String) {
        if (e !is kotlinx.coroutines.CancellationException) CrashLog.record(e, where, fatal = false)
    }

    private companion object {
        /** v1.10 (F1): the sessions opened in this process; only the newest closes the engine session. */
        @Volatile
        var engineSessions = 0
    }
}
