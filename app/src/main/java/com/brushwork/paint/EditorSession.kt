package com.brushwork.paint

import android.util.Log
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
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

    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
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
                val doc = app.repository.load(projectId)
                val c = EditorController(app, doc, scope, app.settings)
                controller = c
                state = State.Ready(c)
                autosaveLoop(c)
            } catch (e: OutOfMemoryError) {
                state = State.Failed("Not enough memory to open this artwork")
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Exception) {
                Log.e("Brushwork", "load failed", e)
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

    suspend fun save() {
        saveMutex.withLock {
            val c = controller ?: return
            val edits = c.editCount
            if (edits == lastSavedEdit) return
            try {
                val thumb = c.compositor.renderThumbnail(512)
                app.repository.save(c.doc, thumb)
                lastSavedEdit = edits
            } catch (e: kotlinx.coroutines.CancellationException) {
                throw e
            } catch (e: Throwable) {
                Log.e("Brushwork", "save failed", e)
                c.toast("Autosave failed: ${e.message ?: e.javaClass.simpleName}")
            }
        }
    }

    /** Commits pending tool work, saves, releases memory, then calls [onClosed]. */
    fun close(onClosed: () -> Unit) {
        if (closed) return
        closed = true
        app.appScope.launch {
            controller?.let { c -> runCatching { c.currentTool.onDeactivate() } }
            save()
            controller?.dispose()
            controller = null
            scope.cancel()
            onClosed()
        }
    }
}
