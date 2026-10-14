package com.brushwork.paint.storage.backup

import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import java.io.File
import java.io.FileInputStream
import java.io.FileNotFoundException
import java.io.IOException
import java.security.MessageDigest

/**
 * v1.9 A8 (R16 (b)): one mirror run into the backup's root folder [root] of [tree] (a
 * [SafTree], so each folder is listed once per run). Blocking; the backup's worker thread.
 */
internal class BackupMirror(
    private val tree: SafTree,
    val root: SafDoc,
    private val projectsDir: File,
) {
    /** What [mirror] did. */
    sealed interface Result {
        /** The artwork is in the backup as of the `project.json` whose SHA-1 is [hash]. */
        data class Done(val entry: BackupIndexEntry, val hash: String) : Result
        /** The artwork changed (or a file of it went) while it was copied: run it again. */
        data object Again : Result
        /** The artwork is not (or no longer) in the gallery. */
        data object Gone : Result
    }

    /**
     * Mirrors the artwork [id] into `<root>/<id>/`:
     * 1. a snapshot of the local `project.json`'s bytes;
     * 2. each data file it references, copied when the backup lacks it or has another size (the
     *    files are immutable: `*_r<rev>` names change with every new content);
     * 3. `thumb.png` ("wt");
     * 4. `project.json` from the snapshot, in the R16 (b) order: create `project.json.new`, write
     *    it ("wt"), delete `project.json`, rename `project.json.new` to `project.json`. A provider
     *    without `FLAG_SUPPORTS_RENAME`: write `project.json.new`, rewrite `project.json` ("wt"),
     *    delete `project.json.new`;
     * 5. every other file in the folder deleted (older revisions, a leftover `.new`).
     *
     * A local file that vanished (a newer save replaced it) or a `project.json` that changed
     * meanwhile gives [Result.Again]: the copy is a consistent older snapshot, and the next run
     * takes the newer one.
     */
    fun mirror(id: String): Result {
        if (!ProjectFormat.isValidId(id)) return Result.Gone
        val dir = File(projectsDir, id)
        val jsonFile = File(dir, ProjectFormat.PROJECT_FILE)
        val snapshot = try {
            jsonFile.readBytes()
        } catch (e: FileNotFoundException) {
            return if (dir.isDirectory) Result.Again else Result.Gone
        }
        val dto = decode(snapshot) ?: return Result.Gone // a damaged project: nothing safe to back up
        val referenced = ProjectFormat.referencedFiles(dto)
        if (referenced.any { !ProjectFormat.isDataFile(it) }) return Result.Gone
        val folder = tree.dir(root, id, create = true)!!
        val existing = tree.list(folder)
        var size = snapshot.size.toLong()
        for (name in referenced) {
            val local = File(dir, name)
            val length = local.length()
            if (length == 0L && !local.isFile) return Result.Again
            size += length
            val remote = existing[name]
            if (remote != null && !remote.isDir && remote.size == length) continue
            try {
                tree.copyIn(folder, name, BackupLayout.MIME_DATA, FileInputStream(local))
            } catch (e: FileNotFoundException) {
                if (!local.isFile) return Result.Again
                throw e
            }
        }
        val thumb = File(dir, ProjectFormat.THUMB_FILE)
        if (thumb.isFile) {
            try {
                size += tree.copyIn(folder, ProjectFormat.THUMB_FILE, BackupLayout.MIME_PNG, FileInputStream(thumb)).size
            } catch (e: FileNotFoundException) {
                // A save replacing the thumbnail at this moment: the next run copies it.
            }
        }
        writeProjectFile(folder, snapshot)
        val keep = referenced + ProjectFormat.PROJECT_FILE + ProjectFormat.THUMB_FILE
        for (doc in tree.list(folder).values.toList()) {
            if (doc.name !in keep) tree.delete(folder, doc)
        }
        val hash = sha1(snapshot)
        val now = try { jsonFile.readBytes() } catch (e: IOException) { null }
        if (now == null || sha1(now) != hash) return Result.Again
        return Result.Done(BackupIndexEntry(id, dto.name, dto.modifiedAt, dto.revision, size), hash)
    }

    /** Step 4 of [mirror]. */
    private fun writeProjectFile(folder: SafDoc, bytes: ByteArray) {
        val name = ProjectFormat.PROJECT_FILE
        val newName = name + BackupLayout.NEW_SUFFIX
        val current = tree.child(folder, name)
        if (current == null) {
            // The first copy, or a crash between the delete and the rename: [newName] is deleted
            // by step 5.
            tree.writeBytes(folder, name, BackupLayout.MIME_JSON, bytes)
            return
        }
        if (current.supportsRename) {
            val fresh = tree.writeBytes(folder, newName, BackupLayout.MIME_JSON, bytes)
            tree.delete(folder, current)
            tree.rename(folder, fresh, name)
        } else {
            tree.writeBytes(folder, newName, BackupLayout.MIME_JSON, bytes)
            tree.writeBytes(folder, name, BackupLayout.MIME_JSON, bytes)
            tree.child(folder, newName)?.let { tree.delete(folder, it) }
        }
    }

    /**
     * Moves the artwork [id]'s folder to `deleted/<id>/` (an older one there is replaced). False
     * when the backup has no folder for it.
     */
    fun moveToDeleted(id: String): Boolean {
        if (!ProjectFormat.isValidId(id)) return false
        val folder = tree.dir(root, id, create = false) ?: return false
        val deleted = tree.dir(root, BackupLayout.DELETED, create = true)!!
        tree.child(deleted, id)?.let { tree.delete(deleted, it) }
        tree.move(folder, root, deleted)
        return true
    }

    /** Deletes `deleted/<id>/` (the 30-day and 500 MB rules). */
    fun purgeDeleted(id: String) {
        val deleted = tree.dir(root, BackupLayout.DELETED, create = false) ?: return
        tree.child(deleted, id)?.let { tree.delete(deleted, it) }
    }

    /**
     * The artwork [id] is in the gallery again (restored from `deleted/` with its id): its
     * `deleted/<id>/` folder moves back to `<id>/` when the root has none, so [mirror] copies only
     * what changed; else it is deleted (the root's copy is the one [mirror] keeps). Without this the
     * folder would stay in `deleted/` for good: the purge only knows the artworks still deleted.
     */
    fun reviveDeleted(id: String) {
        if (!ProjectFormat.isValidId(id)) return
        val deleted = tree.dir(root, BackupLayout.DELETED, create = false) ?: return
        val doc = tree.child(deleted, id) ?: return
        if (doc.isDir && tree.child(root, id) == null) tree.move(doc, deleted, root) else tree.delete(deleted, doc)
    }

    /** The artwork folders the root holds (by id), for the catch-up. */
    fun artworkFolders(): Set<String> =
        tree.list(root).values.filter { it.isDir && it.name !in BackupLayout.RESERVED && ProjectFormat.isValidId(it.name) }.mapTo(HashSet()) { it.name }

    /** Writes the index ("wt"). */
    fun writeIndex(index: BackupIndexData) {
        tree.writeBytes(root, BackupLayout.INDEX, BackupLayout.MIME_JSON, BackupIndex.encode(index))
    }

    /** Writes `settings.json` ("wt") and copies the font files the backup lacks into `fonts/` (others there are deleted). */
    fun writeSettings(settings: BackupSettings.Snapshot, fontsDir: File) {
        val fonts = tree.dir(root, BackupLayout.FONTS, create = settings.fonts.isNotEmpty())
        if (fonts != null) {
            val wanted = settings.fonts.map { it.file }.toSet()
            val have = tree.list(fonts)
            for (f in settings.fonts) {
                val local = File(fontsDir, f.file)
                if (!local.isFile) continue
                val remote = have[f.file]
                if (remote != null && remote.size == local.length()) continue
                tree.copyIn(fonts, f.file, BackupLayout.MIME_DATA, FileInputStream(local))
            }
            for (doc in tree.list(fonts).values.toList()) if (doc.name !in wanted) tree.delete(fonts, doc)
        }
        tree.writeBytes(root, BackupLayout.SETTINGS, BackupLayout.MIME_JSON, BackupSettings.encode(settings))
    }

    companion object {
        fun decode(bytes: ByteArray): ProjectFileDto? = try {
            ProjectFormat.json.decodeFromString(ProjectFileDto.serializer(), String(bytes, Charsets.UTF_8))
        } catch (e: Exception) {
            null
        }

        fun sha1(bytes: ByteArray): String =
            MessageDigest.getInstance("SHA-1").digest(bytes).joinToString("") { "%02x".format(it) }
    }
}
