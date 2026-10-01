package com.brushwork.paint.exchange

import android.net.Uri

/**
 * What the gallery decided for a file it made a new artwork for: the PDF pages chosen and
 * their background (null pages = ask in the editor).
 */
data class PendingImport(
    val uri: Uri,
    /** The artwork was created for this file: its content fills it, the empty "Layer 1" goes. */
    val newArtwork: Boolean = true,
    /** 0-based PDF pages to import (null = decide in the editor). */
    val pages: List<Int>? = null,
    val transparentPages: Boolean = false,
)

/**
 * Hand-off of a file to import into a project that is about to open (v1.5 §4.11; owned by A8):
 * the gallery's "New from SVG or PDF" creates the project, [put]s the file's Uri (or
 * [putRequest]s it with what it already decided) and opens the editor, which [take]s it once and
 * imports it (one undo step), reading those decisions with [details]. In memory only; thread-safe.
 */
object PendingImports {
    private val pending = HashMap<String, Uri>()
    private val requests = HashMap<String, PendingImport>()

    /** Remembers [uri] to import when project [projectId] opens (replaces an earlier one). */
    @Synchronized
    fun put(projectId: String, uri: Uri) {
        pending[projectId] = uri
        requests[projectId] = PendingImport(uri)
    }

    /** [put] with the gallery's decisions about the file (additive, v1.5 A8). */
    @Synchronized
    fun putRequest(projectId: String, request: PendingImport) {
        pending[projectId] = request.uri
        requests[projectId] = request
    }

    /** The Uri waiting for [projectId] (removed), or null. */
    @Synchronized
    fun take(projectId: String): Uri? = pending.remove(projectId)

    /**
     * The decisions for the Uri [take] returned for [projectId] (removed); null when [uri] was
     * not handed over by the gallery for that project (an import from the editor menu).
     */
    @Synchronized
    fun details(projectId: String, uri: Uri): PendingImport? {
        val r = requests[projectId] ?: return null
        if (r.uri != uri) return null
        requests.remove(projectId)
        return r
    }
}
