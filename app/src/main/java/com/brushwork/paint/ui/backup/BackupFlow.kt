package com.brushwork.paint.ui.backup

import android.content.ActivityNotFoundException
import android.content.Context
import android.net.Uri
import android.provider.DocumentsContract
import android.widget.Toast
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.runtime.Composable
import androidx.compose.runtime.DisposableEffect
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateListOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.platform.LocalContext
import com.brushwork.paint.diagnostics.ErrorReporter
import com.brushwork.paint.storage.backup.BackupService
import com.brushwork.paint.storage.backup.BackupSource
import com.brushwork.paint.storage.backup.BackupStatus
import com.brushwork.paint.ui.common.BackupLabels19
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.launch

/**
 * v1.9 A8 (R16 (a)): the backup's folder pick, restore offer and restore list, shared by the
 * gallery (its notice, ⋮ menu and empty state) and the editor's Settings. The entries only ask
 * ([chooseFolder], [restore]); the screen's [BackupHost] runs the folder picker and shows the
 * dialogs (the last host composed does, so one screen never shows them twice).
 */
internal object BackupFlow {
    enum class Purpose { FOLDER, RESTORE }

    sealed interface Dialog {
        /** The picked folder holds a backup of [BackupSource.count] artworks: restore them, or back up there without restoring. */
        class Offer(val pick: BackupService.Pick.Usable) : Dialog

        /** The restore list of [source]; [commitAfter] becomes the backup folder when the list closes. */
        class Restore(val source: BackupSource, val commitAfter: BackupService.Pick.Usable?) : Dialog
    }

    /** Where the folder picker opens: Documents (R16 (a)). */
    val INITIAL_URI: Uri = DocumentsContract.buildDocumentUri("com.android.externalstorage.documents", "primary:Documents")

    var dialog: Dialog? by mutableStateOf(null)

    /**
     * The last pick returned nothing or a refused folder: the hint shows again and the choose
     * buttons read [BackupLabels19.CHOOSE_AGAIN].
     */
    var pickFailed: Boolean by mutableStateOf(false)

    /** A folder pick to start, taken by the top [BackupHost]. */
    var request: Purpose? by mutableStateOf(null)
        private set

    /** What the open picker is for. */
    private var picking: Purpose = Purpose.FOLDER

    /** The composed hosts, the last on top. */
    val hosts = mutableStateListOf<Any>()

    /** Outlives screens, so a restore or the start of a backup finishes when its dialog goes away. */
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ErrorReporter.handler)

    /** Opens the folder picker for the backup folder. */
    fun chooseFolder() {
        request = Purpose.FOLDER
    }

    /**
     * [BackupLabels19.RESTORE]: the restore list of the backup folder when one is set and readable;
     * else the folder picker, then the list of what the picked folder holds (it becomes the backup
     * folder when the list closes, if it held a backup and none was set).
     */
    fun restore(context: Context) {
        val app = context.applicationContext
        if (!BackupService.folderSet || BackupService.status.value is BackupStatus.Unavailable) {
            request = Purpose.RESTORE
            return
        }
        scope.launch {
            val source = BackupService.currentSource(app)
            if (source != null) dialog = Dialog.Restore(source, commitAfter = null) else request = Purpose.RESTORE
        }
    }

    internal fun launched(purpose: Purpose) {
        request = null
        picking = purpose
    }

    /** The picker's answer: refused (hint and [BackupLabels19.CHOOSE_AGAIN]), or the offer, the list or the folder set. */
    fun onPicked(context: Context, tree: Uri?) {
        val app = context.applicationContext
        val purpose = picking
        scope.launch {
            val pick = BackupService.inspect(app, tree)
            if (pick !is BackupService.Pick.Usable) {
                pickFailed = true
                return@launch
            }
            pickFailed = false
            val found = pick.source.count > 0
            when {
                purpose == Purpose.RESTORE ->
                    dialog = Dialog.Restore(pick.source, commitAfter = pick.takeIf { found && !BackupService.folderSet })
                // Picked again after the grant was lost: the same backup, nothing to offer.
                found && !BackupService.isCurrentFolder(pick.tree) -> dialog = Dialog.Offer(pick)
                else -> BackupService.commit(app, pick)
            }
        }
    }

    /** [BackupLabels19.BACK_UP_HERE]: the folder becomes the backup folder, nothing is restored. */
    fun backUpHere(context: Context, offer: Dialog.Offer) {
        dialog = null
        val app = context.applicationContext
        scope.launch { BackupService.commit(app, offer.pick) }
    }

    /** [BackupLabels19.RESTORE_THEM]: the restore list; the folder becomes the backup folder when it closes. */
    fun restoreOffered(offer: Dialog.Offer) {
        dialog = Dialog.Restore(offer.pick.source, commitAfter = offer.pick)
    }

    /** The restore list closed: restores [ids] (none when cancelled), then sets the folder it came with. */
    fun closeRestore(context: Context, list: Dialog.Restore, ids: List<String>, alsoSettings: Boolean) {
        if (dialog === list) dialog = null
        val app = context.applicationContext
        scope.launch {
            if (ids.isNotEmpty()) {
                val n = BackupService.restore(app, list.source, ids, alsoSettings)
                Toast.makeText(app, String.format(BackupLabels19.RESTORED_COUNT, n), Toast.LENGTH_SHORT).show()
            }
            list.commitAfter?.let { BackupService.commit(app, it) }
        }
    }

    /** Forgets the dialogs and a failed pick (tests start each case clean). */
    internal fun reset() {
        dialog = null
        pickFailed = false
        request = null
    }
}

/**
 * The folder picker and the backup's dialogs for one screen (the gallery's notice slot, the
 * Settings' Backup section). Only the last host composed acts.
 */
@Composable
internal fun BackupHost() {
    val context = LocalContext.current
    val token = remember { Any() }
    DisposableEffect(token) {
        BackupFlow.hosts += token
        onDispose { BackupFlow.hosts -= token }
    }
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.OpenDocumentTree()) { uri ->
        BackupFlow.onPicked(context, uri)
    }
    if (BackupFlow.hosts.lastOrNull() !== token) return
    val request = BackupFlow.request
    LaunchedEffect(request) {
        if (request == null) return@LaunchedEffect
        BackupFlow.launched(request)
        try {
            launcher.launch(BackupFlow.INITIAL_URI)
        } catch (e: ActivityNotFoundException) {
            BackupFlow.pickFailed = true
        }
    }
    when (val d = BackupFlow.dialog) {
        is BackupFlow.Dialog.Offer -> RestoreOfferDialog(d)
        is BackupFlow.Dialog.Restore -> RestoreListDialog(d)
        null -> Unit
    }
}
