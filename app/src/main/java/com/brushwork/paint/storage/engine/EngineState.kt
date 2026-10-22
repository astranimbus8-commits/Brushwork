package com.brushwork.paint.storage.engine

import android.content.Context
import android.util.Log
import com.brushwork.paint.storage.ProjectFormat
import kotlinx.serialization.EncodeDefault
import kotlinx.serialization.ExperimentalSerializationApi
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import java.io.File

/**
 * v1.10 (F1, design 2.10-2.12; rule K: lead, frozen): the engine's state on this phone, kept as
 * `noBackupFilesDir/engine-state.json` ([EngineStateStore]) and never in `AppSettings`: Android's
 * Auto Backup never includes `noBackupFilesDir`, and the backup's settings.json holds only
 * `AppSettings`, so none of it is ever restored onto another phone. [engineCheckPending]: the
 * automatic check runs again at the next gallery (`EngineLabels110.CHECK_AGAIN` in the settings
 * row, or a changed build fingerprint); [lastExitSeen]: the newest process exit already counted;
 * [slowSwapReads]: swap reads were slow in a session, so the next sessions run without swap until
 * the next check.
 * A10 reads and writes it; nothing does in F1.
 */
@OptIn(ExperimentalSerializationApi::class)
@Serializable
data class EngineState(
    @EncodeDefault(EncodeDefault.Mode.NEVER) val engineCheckPending: Boolean = false,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val lastExitSeen: Long = 0L,
    @EncodeDefault(EncodeDefault.Mode.NEVER) val slowSwapReads: Boolean = false,
)

/**
 * v1.10 (F1): reads and writes [EngineState] (blocking: one small file; off the main thread except
 * at app start or at open).
 */
object EngineStateStore {
    const val FILE_NAME = "engine-state.json"

    private val json = Json {
        ignoreUnknownKeys = true
        coerceInputValues = true
    }

    /** The state file: always under [Context.getNoBackupFilesDir]. */
    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** The stored state, or the defaults when the file is missing or unreadable. */
    fun read(context: Context): EngineState {
        val f = file(context)
        if (!f.isFile) return EngineState()
        return try {
            json.decodeFromString(EngineState.serializer(), f.readText())
        } catch (e: Exception) {
            Log.w(TAG, "engine state unreadable; using the defaults", e)
            EngineState()
        }
    }

    /** Writes [state] atomically. */
    fun write(context: Context, state: EngineState) {
        val f = file(context)
        f.parentFile?.mkdirs()
        val bytes = json.encodeToString(EngineState.serializer(), state).toByteArray(Charsets.UTF_8)
        ProjectFormat.writeAtomically(f) { it.write(bytes) }
    }

    private const val TAG = "BrushworkEngine"
}
