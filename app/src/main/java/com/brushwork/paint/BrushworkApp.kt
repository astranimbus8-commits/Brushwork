package com.brushwork.paint

import android.app.Application
import android.content.ComponentCallbacks2
import android.content.res.Configuration
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import com.brushwork.paint.diagnostics.Breadcrumbs
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.engine.GovernorPolicies
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.PerfTrace
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.storage.backup.BackupService
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob

class BrushworkApp : Application() {
    /**
     * Outlives screens; used for saves that must finish even when the UI goes away. v1.9 (design
     * 2.4.13): with [ErrorReporter.handler] (F1b: v1.8's outcome).
     */
    val appScope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ErrorReporter.handler)

    lateinit var repository: ProjectRepository
        private set
    lateinit var settings: AppSettings
        private set

    /** The currently open document, if any. */
    var editorSession by mutableStateOf<EditorSession?>(null)
        private set

    override fun onCreate() {
        super.onCreate()
        // v1.9 (design 2.4.13): the crash log first, so it sees what follows (F1b: no-op).
        CrashLog.install(this)
        repository = ProjectRepository(this)
        settings = AppSettings(this)
        // v1.9 (design 2.6 step 8): "Show frame times" records the timings from the start.
        PerfTrace.enabled = settings.showFrameTimes
        // v1.9 (V28, design 2.4.3): one memory policy for the whole app (v1.8's until A7b).
        MemoryGovernor.policy = GovernorPolicies.current()
        registerComponentCallbacks(memoryCallbacks)
        // v1.9 (design 2.4.13): the automatic backup (A8; F1b: no-op).
        BackupService.start(this)
    }

    /** Hands the system's memory requests to [MemoryGovernor]. */
    private val memoryCallbacks = object : ComponentCallbacks2 {
        override fun onTrimMemory(level: Int) {
            Breadcrumbs.add("trim:$level")
            MemoryGovernor.onTrimMemory(level)
        }

        override fun onConfigurationChanged(newConfig: Configuration) = Unit

        @Deprecated("Deprecated in Java")
        @Suppress("DEPRECATION")
        override fun onLowMemory() = MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_COMPLETE)
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
