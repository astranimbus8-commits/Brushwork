package com.brushwork.paint.storage.backup

import android.content.Context
import android.util.Log
import com.brushwork.paint.storage.ProjectFormat
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v1.9 A8 (R16 (b)): what the backup remembers between runs, in
 * `noBackupFilesDir/backup-state.json` ([BackupStateStore]). Android's Auto Backup never includes
 * `noBackupFilesDir`, so a reinstalled app never gets back a folder grant it no longer holds (it
 * starts with no folder, and the restore offer finds the old backup instead).
 */
@Serializable
internal data class BackupStateData(
    /** This file's format. */
    val format: Int = BackupStateStore.FORMAT,
    /** The picked folder's tree Uri (Storage Access Framework, persistable grant); null = no folder. */
    val tree: String? = null,
    /**
     * True when the picked folder is the backup's root itself (it held the index, or it is named
     * [BackupLayout.ROOT_NAME]); else the root is its [BackupLayout.ROOT_NAME] child, made on the
     * first write.
     */
    val pickedIsRoot: Boolean = false,
    /** The picked folder as the Settings row shows it ("Documents/Brushwork"). */
    val folderName: String = "",
    /** When the no-folder notice was put off ([com.brushwork.paint.ui.common.BackupLabels19.LATER]); 0 = never. */
    val notSetLaterAt: Long = 0L,
    /** When the last backup run finished (0 = never). */
    val lastBackupAt: Long = 0L,
    /** True once the first full backup into [tree] finished; a run cut short starts again at the next start. */
    val fullDone: Boolean = false,
    /** The artworks of the backup's index by id: the mirrored ones and those another install left there. */
    val artworks: Map<String, MirroredArtwork> = emptyMap(),
    /** The deleted artworks kept under `deleted/<id>/`, by id. */
    val deleted: Map<String, BackupDeletedEntry> = emptyMap(),
    /** The hash of the last `settings.json` written (empty = none yet). */
    val settingsHash: String = "",
) {
    /** The backup's total size: the artworks' sizes ([BackupStatus.Ok.bytes]). */
    val bytes: Long get() = artworks.values.sumOf { it.entry.size }
}

/**
 * One artwork of the backup: its index entry plus [hash], the SHA-1 of the `project.json` this
 * install last mirrored ("" = an artwork another install mirrored, which this one never touches
 * unless the same id is saved here).
 */
@Serializable
internal data class MirroredArtwork(
    val entry: BackupIndexEntry,
    val hash: String = "",
)

/** v1.9 A8: reads and writes [BackupStateData] (blocking; call off the main thread except at app start, where it is one small file). */
internal object BackupStateStore {
    const val FORMAT = 1
    const val FILE_NAME = "backup-state.json"

    private val json = Json {
        ignoreUnknownKeys = true
        encodeDefaults = true
        coerceInputValues = true
    }

    /** The state file: always under [Context.getNoBackupFilesDir]. */
    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** The stored state, or the empty one (no folder) when missing or damaged. */
    fun read(context: Context): BackupStateData {
        val f = file(context)
        if (!f.isFile) return BackupStateData()
        return try {
            json.decodeFromString(BackupStateData.serializer(), f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "backup state unreadable; starting without a folder", e)
            BackupStateData()
        }
    }

    /** Writes [data] atomically. */
    fun write(context: Context, data: BackupStateData) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val bytes = json.encodeToString(BackupStateData.serializer(), data).toByteArray(Charsets.UTF_8)
        ProjectFormat.writeAtomically(f) { it.write(bytes) }
    }

    private const val TAG = "BrushworkBackup"
}
