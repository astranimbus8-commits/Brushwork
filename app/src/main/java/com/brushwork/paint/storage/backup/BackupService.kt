package com.brushwork.paint.storage.backup

import android.app.Application
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Environment
import android.os.Process
import android.os.StatFs
import android.provider.DocumentsContract
import android.system.Os
import android.util.Log
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.util.concurrent.CountDownLatch
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/** v1.9 (R16, design 2.4.14; owner A8): where the backup stands. */
sealed interface BackupStatus {
    data object NotSet : BackupStatus
    data object Unavailable : BackupStatus
    data class Running(val done: Int, val total: Int) : BackupStatus
    data class Ok(val lastMillis: Long, val folderName: String, val bytes: Long) : BackupStatus
}

/**
 * v1.9 (R16, design 2.4.14; owner A8): the automatic backup to a folder the user picks, so the
 * artworks survive uninstalling the app.
 *
 * - [start] (app start) reads `noBackupFilesDir/backup-state.json` ([BackupStateStore]): no folder
 *   is [BackupStatus.NotSet]; a folder whose grant is gone is [BackupStatus.Unavailable]; else a
 *   catch-up mirrors what changed while the app was away (and a first full backup cut short runs
 *   again).
 * - [onSaved] and [onDeleted] (from [ProjectRepository]) only queue the artwork's id, conflated: a
 *   save never waits for the backup. One low-priority worker thread mirrors the queue
 *   ([BackupMirror]), then rewrites the index and, when they changed, the settings.
 * - Deleted artworks move to `deleted/<id>/`, purged after 30 days, and oldest first while the
 *   folder's volume has less than 500 MB free.
 */
object BackupService {
    private const val TAG = "BrushworkBackup"
    private const val EXTERNAL_STORAGE = "com.android.externalstorage.documents"

    private val state = MutableStateFlow<BackupStatus>(BackupStatus.Ok(0L, "", 0L))

    /** Where the backup stands (Ok(0, "", 0) until [start]: no notice shows). */
    val status: StateFlow<BackupStatus> = state.asStateFlow()

    private val artworks = MutableStateFlow(false)

    /** True while the gallery has an artwork: the no-folder notice waits for the first one (nothing to lose before). */
    internal val hasArtworks: StateFlow<Boolean> = artworks.asStateFlow()

    private val later = MutableStateFlow(0L)

    /** When [com.brushwork.paint.ui.common.BackupLabels19.LATER] last put off the no-folder notice (0 = never); kept in the state file. */
    internal val notSetLaterAt: StateFlow<Long> = later.asStateFlow()

    private val lock = Any()
    private var context: Context? = null
    private var data = BackupStateData()
    /** Bumped by [start]: work of an earlier start stops at its next step. */
    private var generation = 0
    private val pendingSaves = LinkedHashSet<String>()
    private val pendingDeletes = LinkedHashSet<String>()
    private var pendingCatchUp = false
    private var pendingFull = false
    private var scheduled = false

    private val worker: ExecutorService = Executors.newSingleThreadExecutor { r ->
        Thread({
            Process.setThreadPriority(Process.THREAD_PRIORITY_BACKGROUND)
            r.run()
        }, "brushwork-backup").apply { isDaemon = true }
    }

    /** The clock (tests move it). */
    @Volatile internal var clock: () -> Long = { System.currentTimeMillis() }

    /** Free bytes on the volume of a file of the folder, or null when unknown (tests replace it). */
    @Volatile internal var freeBytes: (Context, Uri) -> Long? = ::volumeFreeBytes

    /** Called once from `BrushworkApp.onCreate`: reads the state, sets [status] and starts the catch-up. */
    fun start(app: Application) = startWith(app)

    /** [start] for any context (tests start again to stand for the next app start). */
    internal fun startWith(app: Context) {
        val c = app.applicationContext ?: app
        val stored = BackupStateStore.read(c)
        val hasAny = hasArtworks(c)
        synchronized(lock) {
            generation++
            context = c
            data = stored
            pendingSaves.clear()
            pendingDeletes.clear()
            pendingCatchUp = false
            pendingFull = false
            scheduled = false
            later.value = stored.notSetLaterAt
            artworks.value = hasAny
            val s = statusOf(c, stored)
            state.value = s
            if (s is BackupStatus.Ok) {
                pendingCatchUp = true
                pendingFull = !stored.fullDone
                schedule()
            }
        }
    }

    /** Called by [ProjectRepository] after each save, autosave, new artwork, rename and duplicate: queues [projectId]. */
    fun onSaved(projectId: String) {
        artworks.value = true
        synchronized(lock) {
            if (!backingUp()) return
            pendingDeletes.remove(projectId)
            pendingSaves += projectId
            schedule()
        }
    }

    /** Called by [ProjectRepository] after a delete: the backup's copy moves to `deleted/<id>/`. */
    fun onDeleted(projectId: String) {
        synchronized(lock) {
            val c = context ?: return
            val gen = generation
            worker.execute { if (gen == generation) artworks.value = hasArtworks(c) }
            if (!backingUp()) return
            pendingSaves.remove(projectId)
            pendingDeletes += projectId
            schedule()
        }
    }

    /**
     * [com.brushwork.paint.ui.common.BackupLabels19.NOW]: every artwork again, with the progress
     * (the copy-once rule still skips the unchanged data files).
     */
    internal fun backUpNow() {
        synchronized(lock) {
            if (!backingUp()) return
            pendingFull = true
            pendingCatchUp = true
            schedule()
        }
    }

    /** Puts off the no-folder notice for 7 days (kept in the state file). */
    internal fun putOffNotice() {
        val at = clock()
        later.value = at
        update { it.copy(notSetLaterAt = at) }
    }

    /** True when a folder is set and usable (the lock is held). */
    private fun backingUp(): Boolean = data.tree != null && state.value !is BackupStatus.Unavailable && state.value !is BackupStatus.NotSet

    /** The state with [change] applied, written on the worker (the lock is taken here). */
    private fun update(change: (BackupStateData) -> BackupStateData) {
        val c: Context
        val gen: Int
        synchronized(lock) {
            c = context ?: return
            gen = generation
            data = change(data)
        }
        worker.execute { if (gen == generation) persist(c) }
    }

    private fun persist(c: Context) {
        val snapshot = synchronized(lock) { data }
        try {
            BackupStateStore.write(c, snapshot)
        } catch (e: Exception) {
            Log.w(TAG, "could not write the backup state", e)
        }
    }

    // ------------------------------------------------------------------ the folder

    /** What a picked folder is. */
    internal sealed interface Pick {
        /** No folder came back, or one the backup refuses (a storage root or Download). */
        data object Refused : Pick

        /** A usable folder; [source] says whether it already holds a backup ([BackupSource.count] artworks). */
        class Usable(val tree: Uri, val folderName: String, val source: BackupSource) : Pick
    }

    /**
     * Looks at the folder [tree] the picker returned without changing anything in it: refused
     * (null, a storage root or Download), or usable with the backup it holds, if any.
     */
    internal suspend fun inspect(app: Context, tree: Uri?): Pick = withContext(Dispatchers.IO) {
        if (tree == null || refused(tree)) return@withContext Pick.Refused
        try {
            val safTree = SafTree(app.contentResolver, tree)
            val source = BackupRestore.find(safTree)
            Pick.Usable(tree, folderNameOf(tree, safTree.picked), source)
        } catch (e: Exception) {
            Log.w(TAG, "the picked folder cannot be read", e)
            Pick.Refused
        }
    }

    /** A storage root or the Download folder (API 30+'s picker refuses them; older ones do not). */
    internal fun refused(tree: Uri): Boolean {
        val id = try { DocumentsContract.getTreeDocumentId(tree) } catch (e: IllegalArgumentException) { return true }
        val colon = id.indexOf(':')
        if (colon < 0) return false
        val path = id.substring(colon + 1).trim('/')
        return path.isEmpty() || path.equals(Environment.DIRECTORY_DOWNLOADS, ignoreCase = true)
    }

    /** "Documents/Brushwork" for a storage folder, else the folder's name. */
    private fun folderNameOf(tree: Uri, picked: SafDoc): String {
        val id = DocumentsContract.getTreeDocumentId(tree)
        val path = id.substringAfter(':', "").trim('/')
        return path.ifEmpty { picked.name }
    }

    /**
     * Makes [pick] the backup folder: takes its persistable grant, keeps the artworks of a backup
     * already there in the index (this install never touches them unless it saves the same id),
     * and starts the full backup with its progress.
     */
    internal suspend fun commit(app: Context, pick: Pick.Usable) = withContext(Dispatchers.IO) {
        val c = app.applicationContext ?: app
        val flags = Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_GRANT_WRITE_URI_PERMISSION
        try {
            c.contentResolver.takePersistableUriPermission(pick.tree, flags)
        } catch (e: SecurityException) {
            Log.w(TAG, "no persistable grant for ${pick.tree}", e)
        }
        val old = synchronized(lock) { data.tree }?.let { Uri.parse(it) }
        if (old != null && old != pick.tree) {
            try { c.contentResolver.releasePersistableUriPermission(old, flags) } catch (e: SecurityException) { /* already gone */ }
        }
        val index = pick.source.root?.let { BackupRestore.readIndex(pick.source.tree, it) }
        val kept = index?.artworks.orEmpty().associate { it.id to MirroredArtwork(it, hash = "") }
        val deleted = index?.deleted.orEmpty().associateBy { it.id }
        synchronized(lock) {
            context = c
            generation++
            scheduled = false
            pendingSaves.clear()
            pendingDeletes.clear()
            data = data.copy(
                tree = pick.tree.toString(),
                pickedIsRoot = pick.source.pickedIsRoot,
                folderName = pick.folderName,
                fullDone = false,
                lastBackupAt = 0L,
                artworks = kept,
                deleted = deleted,
                settingsHash = "",
            )
            persist(c)
            state.value = BackupStatus.Running(0, localIds(c).size)
            pendingFull = true
            pendingCatchUp = true
            schedule()
        }
    }

    /**
     * The backup of the current folder, for [com.brushwork.paint.ui.common.BackupLabels19.RESTORE]
     * (null when no folder is set or it cannot be read).
     */
    internal suspend fun currentSource(app: Context): BackupSource? = withContext(Dispatchers.IO) {
        val d = synchronized(lock) { data }
        val tree = d.tree?.let { Uri.parse(it) } ?: return@withContext null
        try {
            val safTree = SafTree(app.contentResolver, tree)
            val root = if (d.pickedIsRoot) safTree.picked else safTree.dir(safTree.picked, BackupLayout.ROOT_NAME, create = false)
            BackupSource(safTree, d.pickedIsRoot, root, count = 0)
        } catch (e: Exception) {
            Log.w(TAG, "the backup folder cannot be read", e)
            markUnavailable()
            null
        }
    }

    /** True when a folder is set (usable or not). */
    internal val folderSet: Boolean get() = synchronized(lock) { data.tree != null }

    /** The folder as the Settings row shows it ("" when none). */
    internal val folderName: String get() = synchronized(lock) { data.folderName }

    /** True when [tree] is the folder already set. */
    internal fun isCurrentFolder(tree: Uri): Boolean = synchronized(lock) { data.tree } == tree.toString()

    // ------------------------------------------------------------------ restore

    /** The restore list of [source] (blocking work on IO). */
    internal suspend fun listRestore(app: Context, source: BackupSource): List<RestoreItem> = withContext(Dispatchers.IO) {
        val root = source.root ?: return@withContext emptyList()
        try {
            BackupRestore.list(source.tree, root, ProjectRepository.projectsDir(app))
        } catch (e: Exception) {
            Log.w(TAG, "could not list the backup", e)
            emptyList()
        }
    }

    /**
     * Restores [ids] of [source] (and its settings, brushes, palettes and fonts when
     * [alsoSettings]); returns how many artworks came back. The galleries refresh, and the
     * restored artworks are backed up like saved ones.
     */
    internal suspend fun restore(app: Context, source: BackupSource, ids: List<String>, alsoSettings: Boolean): Int {
        val c = app.applicationContext ?: app
        val root = source.root ?: return 0
        val restored = withContext(Dispatchers.IO) {
            val done = BackupRestore.restore(source.tree, root, ids, ProjectRepository.projectsDir(c))
            if (alsoSettings) {
                try {
                    BackupRestore.restoreSettings(c, source.tree, root)
                } catch (e: Exception) {
                    Log.w(TAG, "could not restore the settings", e)
                }
            }
            done
        }
        if (restored.isNotEmpty()) {
            ProjectRepository.notifyRestored()
            for (id in restored) onSaved(id)
        }
        return restored.size
    }

    // ------------------------------------------------------------------ the worker

    private fun schedule() {
        if (scheduled) return
        scheduled = true
        val gen = generation
        worker.execute { drain(gen) }
    }

    /** Waits until the worker has nothing left to do (tests). False on [timeoutMs]. */
    internal fun idle(timeoutMs: Long = 60_000): Boolean {
        val deadline = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < deadline) {
            val latch = CountDownLatch(1)
            worker.execute { latch.countDown() }
            if (!latch.await(deadline - System.currentTimeMillis(), TimeUnit.MILLISECONDS)) return false
            synchronized(lock) { if (!scheduled) return true }
        }
        return false
    }

    private class Work(
        val c: Context,
        val saves: List<String>,
        val deletes: List<String>,
        val full: Boolean,
        val catchUp: Boolean,
    )

    private fun drain(gen: Int) {
        while (true) {
            val work = synchronized(lock) {
                if (gen != generation) return
                val c = context
                if (c == null || data.tree == null || (pendingSaves.isEmpty() && pendingDeletes.isEmpty() && !pendingFull && !pendingCatchUp)) {
                    scheduled = false
                    return
                }
                Work(c, pendingSaves.toList(), pendingDeletes.toList(), pendingFull, pendingCatchUp).also {
                    pendingSaves.clear()
                    pendingDeletes.clear()
                    pendingFull = false
                    pendingCatchUp = false
                }
            }
            try {
                run(gen, work)
            } catch (e: Throwable) {
                Log.w(TAG, "backup run failed", e)
                if (gen == generation) checkAvailable(work.c)
            }
        }
    }

    /** One run: the deletes, the artworks to mirror, the purge, the settings and the index. */
    private fun run(gen: Int, work: Work) {
        val c = work.c
        val start = synchronized(lock) { data }
        val tree = SafTree(c.contentResolver, Uri.parse(start.tree ?: return))
        val picked = try {
            tree.picked
        } catch (e: FileNotFoundException) {
            markUnavailable()
            return
        } catch (e: SecurityException) {
            markUnavailable()
            return
        }
        val root = if (start.pickedIsRoot) picked else tree.dir(picked, BackupLayout.ROOT_NAME, create = true)!!
        val mirror = BackupMirror(tree, root, ProjectRepository.projectsDir(c))
        val local = localIds(c)

        // Deletes, and (catch-up) artworks this install mirrored that are gone from the gallery.
        val gone = LinkedHashSet<String>()
        gone += work.deletes.filter { it !in local }
        if (work.catchUp || work.full) gone += start.artworks.filter { (id, a) -> a.hash.isNotEmpty() && id !in local }.keys
        for (id in gone) {
            if (gen != generation) return
            val name = synchronized(lock) { data.artworks[id]?.entry?.name }
            val moved = mirror.moveToDeleted(id)
            synchronized(lock) {
                data = data.copy(
                    artworks = data.artworks - id,
                    deleted = if (moved) data.deleted + (id to BackupDeletedEntry(id, name ?: id, clock())) else data.deleted,
                )
            }
        }

        // What to mirror.
        val todo = LinkedHashSet<String>()
        if (work.full) {
            todo += local
        } else {
            todo += work.saves.filter { it in local }
            if (work.catchUp) {
                for (id in local) {
                    val known = start.artworks[id]?.hash
                    if (known.isNullOrEmpty() || known != localHash(c, id)) todo += id
                }
            }
        }
        val total = todo.size
        if (work.full) state.value = BackupStatus.Running(0, total)
        for ((i, id) in todo.withIndex()) {
            if (gen != generation) return
            try {
                // Restored from `deleted/` with its id: the deleted copy leaves `deleted/` first.
                if (synchronized(lock) { id in data.deleted }) mirror.reviveDeleted(id)
                when (val r = mirror.mirror(id)) {
                    is BackupMirror.Result.Done -> synchronized(lock) {
                        data = data.copy(artworks = data.artworks + (id to MirroredArtwork(r.entry, r.hash)), deleted = data.deleted - id)
                    }
                    BackupMirror.Result.Again -> synchronized(lock) { pendingSaves += id }
                    BackupMirror.Result.Gone -> Unit
                }
            } catch (e: SecurityException) {
                throw e
            } catch (e: Exception) {
                // One artwork that cannot be copied must not stop the others: the next catch-up retries it.
                Log.w(TAG, "could not back up $id", e)
            }
            if (work.full) state.value = BackupStatus.Running(i + 1, total)
            if (work.full && (i + 1) % 8 == 0) persist(c)
        }
        if (gen != generation) return

        purge(c, tree, mirror)

        val settings = BackupSettings.snapshot(c)
        val settingsHash = BackupMirror.sha1(BackupSettings.encode(settings))
        if (settingsHash != synchronized(lock) { data.settingsHash }) {
            mirror.writeSettings(settings, File(c.filesDir, FontStore.DIR_NAME))
            synchronized(lock) { data = data.copy(settingsHash = settingsHash) }
        }

        val now = clock()
        val d = synchronized(lock) { data }
        mirror.writeIndex(
            BackupIndexData(
                appVersion = appVersion(c),
                updatedAt = now,
                artworks = d.artworks.values.map { it.entry }.sortedBy { it.id },
                deleted = d.deleted.values.sortedBy { it.deletedAt },
            ),
        )
        synchronized(lock) {
            if (gen != generation) return
            data = data.copy(lastBackupAt = now, fullDone = data.fullDone || work.full)
            state.value = BackupStatus.Ok(now, data.folderName, data.bytes)
        }
        persist(c)
    }

    /** The 30-day rule, then the 500 MB rule (oldest first). */
    private fun purge(c: Context, tree: SafTree, mirror: BackupMirror) {
        val now = clock()
        val deleted = synchronized(lock) { data.deleted }
        val expired = deleted.values.filter { now - it.deletedAt >= BackupLayout.DELETED_KEEP_MILLIS }
        for (e in expired) mirror.purgeDeleted(e.id)
        var left = (deleted - expired.map { it.id }.toSet()).values.sortedBy { it.deletedAt }
        val probe = tree.child(mirror.root, BackupLayout.INDEX)?.uri
        if (probe != null && left.isNotEmpty()) {
            while (left.isNotEmpty()) {
                val free = freeBytes(c, probe) ?: break
                if (free >= BackupLayout.MIN_FREE_BYTES) break
                mirror.purgeDeleted(left.first().id)
                left = left.drop(1)
            }
        }
        val keep = left.map { it.id }.toSet()
        synchronized(lock) { data = data.copy(deleted = data.deleted.filterKeys { it in keep || it !in deleted }) }
    }

    private fun statusOf(c: Context, d: BackupStateData): BackupStatus {
        val tree = d.tree?.let { Uri.parse(it) } ?: return BackupStatus.NotSet
        val granted = try {
            c.contentResolver.persistedUriPermissions.any { it.uri == tree && it.isWritePermission }
        } catch (e: Exception) {
            false
        }
        return if (granted) BackupStatus.Ok(d.lastBackupAt, d.folderName, d.bytes) else BackupStatus.Unavailable
    }

    /** After a failed run: [BackupStatus.Unavailable] when the folder itself cannot be read any more. */
    private fun checkAvailable(c: Context) {
        val tree = synchronized(lock) { data.tree }?.let { Uri.parse(it) } ?: return
        val ok = try {
            SafTree(c.contentResolver, tree).picked
            true
        } catch (e: Exception) {
            false
        }
        if (!ok) {
            markUnavailable()
        } else {
            synchronized(lock) {
                if (state.value is BackupStatus.Running) state.value = BackupStatus.Ok(data.lastBackupAt, data.folderName, data.bytes)
            }
        }
    }

    private fun markUnavailable() {
        synchronized(lock) {
            if (data.tree != null) state.value = BackupStatus.Unavailable
        }
    }

    /** The gallery's artwork ids (folders with a project file). */
    private fun localIds(c: Context): Set<String> =
        ProjectRepository.projectsDir(c).listFiles().orEmpty()
            .filter { it.isDirectory && ProjectFormat.isValidId(it.name) && File(it, ProjectFormat.PROJECT_FILE).isFile }
            .mapTo(LinkedHashSet()) { it.name }

    private fun hasArtworks(c: Context): Boolean =
        ProjectRepository.projectsDir(c).listFiles().orEmpty()
            .any { it.isDirectory && ProjectFormat.isValidId(it.name) && File(it, ProjectFormat.PROJECT_FILE).isFile }

    private fun localHash(c: Context, id: String): String? = try {
        BackupMirror.sha1(File(ProjectRepository.projectsDir(c), "$id/${ProjectFormat.PROJECT_FILE}").readBytes())
    } catch (e: Exception) {
        null
    }

    private fun appVersion(c: Context): String = try {
        c.packageManager.getPackageInfo(c.packageName, 0).versionName.orEmpty()
    } catch (e: Exception) {
        ""
    }

    /**
     * The free bytes of the volume holding the document [file]: `fstatvfs` on its descriptor, else
     * the primary storage's for a primary-storage folder, else null (the 500 MB rule is skipped).
     */
    private fun volumeFreeBytes(c: Context, file: Uri): Long? {
        try {
            c.contentResolver.openFileDescriptor(file, "r")?.use { pfd ->
                val st = Os.fstatvfs(pfd.fileDescriptor)
                return st.f_bavail * st.f_frsize
            }
        } catch (e: Throwable) {
            // Not every provider hands out a real descriptor.
        }
        return try {
            val id = DocumentsContract.getDocumentId(file)
            if (file.authority == EXTERNAL_STORAGE && id.startsWith("primary:")) {
                @Suppress("DEPRECATION")
                StatFs(Environment.getExternalStorageDirectory().path).availableBytes
            } else {
                null
            }
        } catch (e: Throwable) {
            null
        }
    }
}
