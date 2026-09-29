package com.brushwork.paint

import android.app.Application
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BrushworkApp : Application() {
    /** Outlives screens; used for saves that must finish even when the UI goes away. */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    lateinit var repository: ProjectRepository
        private set
    lateinit var settings: AppSettings
        private set

    /** The currently open document, if any. */
    var editorSession by mutableStateOf<EditorSession?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        repository = ProjectRepository(this)
        settings = AppSettings(this)
    }

    /** Opens (or returns the already-open) editor session for [projectId]. */
    fun openEditor(projectId: String): EditorSession {
        val current = editorSession
        if (current != null && current.projectId == projectId && !current.closed) return current
        current?.close {}
        return EditorSession(this, projectId).also { editorSession = it }
    }

    /** Saves and closes the open editor, then runs [onClosed]. */
    fun closeEditor(onClosed: () -> Unit = {}) {
        val s = editorSession ?: return onClosed()
        editorSession = null
        s.close(onClosed)
    }
}
