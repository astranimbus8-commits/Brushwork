package com.brushwork.paint.exchange

import android.net.Uri

/**
 * Hand-off of a file to import into a project that is about to open (v1.5 §4.11; owned by A8):
 * the gallery's "New from SVG or PDF" creates the project, [put]s the file's Uri and opens the
 * editor, which [take]s it once and imports it (one undo step). In memory only; thread-safe.
 */
object PendingImports {
    private val pending = HashMap<String, Uri>()

    /** Remembers [uri] to import when project [projectId] opens (replaces an earlier one). */
    @Synchronized
    fun put(projectId: String, uri: Uri) {
        pending[projectId] = uri
    }

    /** The Uri waiting for [projectId] (removed), or null. */
    @Synchronized
    fun take(projectId: String): Uri? = pending.remove(projectId)
}
