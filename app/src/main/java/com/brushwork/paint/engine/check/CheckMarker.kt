package com.brushwork.paint.engine.check

import android.content.Context
import java.io.File

/**
 * v1.10 (A10, design 2.10 "Marker"): `noBackupFilesDir/engine-check.running`, which exists only
 * around M2's allocations, the one step that can get the app killed for memory. Plain text: the
 * start time and the attempt count, one per line. It is deleted in M2's `finally`, on cancel, on
 * `ON_STOP` and in `onTrimMemory`; found at the next launch, it means the run was interrupted
 * ([AutoCheckRunner.atLaunch]). Any thread.
 */
object CheckMarker {
    const val FILE_NAME = "engine-check.running"

    /** A marker's contents: when M2 started and which attempt it was (1 for the first). */
    data class Marker(val startMillis: Long, val attempts: Int)

    fun file(context: Context): File = File(context.noBackupFilesDir, FILE_NAME)

    /** Writes the marker (plain, not atomic: a torn marker still exists, which is what counts). */
    fun write(context: Context, marker: Marker) {
        val f = file(context)
        f.parentFile?.mkdirs()
        f.writeText("${marker.startMillis}\n${marker.attempts}\n")
    }

    /** The marker, or null when there is none; an unreadable one counts as a first attempt started at 0. */
    fun read(context: Context): Marker? {
        val f = file(context)
        if (!f.exists()) return null
        val lines = runCatching { f.readLines() }.getOrDefault(emptyList())
        val start = lines.getOrNull(0)?.trim()?.toLongOrNull() ?: 0L
        val attempts = lines.getOrNull(1)?.trim()?.toIntOrNull()?.coerceAtLeast(1) ?: 1
        return Marker(start, attempts)
    }

    fun delete(context: Context) {
        file(context).delete()
    }
}
