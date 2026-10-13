package com.brushwork.paint.storage.backup

import android.app.Application
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

/** v1.9 (R16, design 2.4.14; owner A8): where the backup stands. */
sealed interface BackupStatus {
    data object NotSet : BackupStatus
    data object Unavailable : BackupStatus
    data class Running(val done: Int, val total: Int) : BackupStatus
    data class Ok(val lastMillis: Long, val folderName: String, val bytes: Long) : BackupStatus
}

/**
 * v1.9 (R16, design 2.4.14; owner A8): the automatic backup to a folder the user picks. F1b: the
 * seam; the status is Ok, so no gallery notice shows, and nothing is backed up.
 */
@Suppress("UNUSED_PARAMETER")
object BackupService {
    private val state = MutableStateFlow<BackupStatus>(BackupStatus.Ok(0L, "", 0L))

    /** F1b: Ok(0, "", 0), so no notice shows. */
    val status: StateFlow<BackupStatus> = state.asStateFlow()

    /** Called once from `BrushworkApp.onCreate`. F1b: no-op. */
    fun start(app: Application) {}

    /** Called by ProjectRepository after each save, autosave and delete (A8 wires the calls). F1b: no-op. */
    fun onSaved(projectId: String) {}

    fun onDeleted(projectId: String) {}
}
