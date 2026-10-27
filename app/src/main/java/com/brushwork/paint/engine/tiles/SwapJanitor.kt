package com.brushwork.paint.engine.tiles

import android.content.Context
import android.util.Log
import java.io.File
import java.util.concurrent.ConcurrentHashMap

/**
 * v1.10 (A10, design 2.8, J9, I34): swap is a cache, deleted at editor close and at app start.
 *
 * Every [SlabSwapStore] lives in its own directory `noBackupFilesDir/swap/<session UUID>/`
 * ([root]) and joins [live] **before** it makes that directory; it leaves after its
 * [SlabSwapStore.close] deleted it. [purge] (posted to a background thread by `EngineStartup.onAppCreate`) deletes
 * everything under the root that is not a live session's directory: the leftovers of an earlier
 * process (a crash, a kill, a force stop), which are never resumed. An editor may already be opening
 * while it runs (after a process death `MainActivity` reopens the artwork in its first
 * composition), so it never assumes an empty root, and it never deletes the root itself.
 */
object SwapJanitor {
    /** The swap root's name in `noBackupFilesDir` (never `cacheDir`: the system may clear it while the editor runs). */
    const val ROOT = "swap"

    /** The session directories in use by this process (their names: one UUID each). */
    val live: MutableSet<String> = ConcurrentHashMap.newKeySet()

    /** `noBackupFilesDir/swap`. */
    fun root(context: Context): File = File(context.noBackupFilesDir, ROOT)

    /**
     * Deletes every directory and file under [root] that is not a [live] session's directory;
     * [root] itself stays. Returns how many entries went. Blocking (deletes files): never on main.
     */
    fun purge(root: File): Int {
        val entries = root.listFiles() ?: return 0
        var gone = 0
        for (e in entries) {
            // A session joins `live` before it makes its directory, so a directory not in `live`
            // now was left by an earlier process (or by a session already closed).
            if (e.isDirectory && e.name in live) continue
            if (e.deleteRecursively()) gone++ else Log.w(TAG, "swap leftover not deleted: ${e.name}")
        }
        return gone
    }

    private const val TAG = "BrushworkEngine"
}
