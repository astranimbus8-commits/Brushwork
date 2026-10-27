package com.brushwork.paint.engine.check

import android.content.Context
import android.util.Log
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.storage.engine.EngineCheckResult
import com.brushwork.paint.storage.engine.EngineStateStore
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v1.10 (F1, J32, design 2.10; signature frozen, A10 body): the automatic check's stored result
 * (`noBackupFilesDir/engine-check.json`, A10), read by `EditorSession` before the controller
 * exists. F1: no result, so every session runs at CLASSIC with v1.9's numbers.
 *
 * A10: [read] is the result a session and the gallery run with; [readStored] the file as the
 * check wrote it. An unreadable file, or one of a newer [EngineCheckResult.schema], is no result
 * (the check runs again). Blocking (one small file): off the main thread, except at app start and
 * at open.
 */
object CheckStore {
    /** The result file's name in `noBackupFilesDir` (never backed up, so never on another phone). */
    const val FILE_NAME = "engine-check.json"

    /** The schema this build writes and reads. */
    private const val SCHEMA = 1

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /**
     * The result sessions and the gallery run with: the stored one ([readStored]), except that a
     * FULL result runs at TILES with [CheckReasons.SLOW_STORAGE] while `engine-state.json` records
     * slow swap reads in use (`slowSwapReads`, design 2.10), which the next check clears.
     */
    fun read(context: Context): EngineCheckResult? {
        val stored = readStored(context) ?: return null
        if (stored.level != EngineLevel.FULL || !EngineStateStore.read(context).slowSwapReads) return stored
        return stored.copy(level = EngineLevel.TILES, reason = CheckReasons.SLOW_STORAGE)
    }

    /** The result file. */
    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** The stored result, or null when there is none, it is unreadable, or its schema is newer than this build's. */
    fun readStored(context: Context): EngineCheckResult? {
        val f = file(context)
        if (!f.isFile) return null
        return try {
            json.decodeFromString(EngineCheckResult.serializer(), f.readText()).takeIf { it.schema <= SCHEMA }
        } catch (e: Exception) {
            Log.w(TAG, "engine check result unreadable; the check runs again", e)
            null
        }
    }

    /** Writes [result] atomically. */
    fun write(context: Context, result: EngineCheckResult) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val bytes = json.encodeToString(EngineCheckResult.serializer(), result).toByteArray(Charsets.UTF_8)
        ProjectFormat.writeAtomically(f) { it.write(bytes) }
    }

    private const val TAG = "BrushworkEngine"
}
