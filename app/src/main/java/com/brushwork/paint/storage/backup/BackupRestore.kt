package com.brushwork.paint.storage.backup

import android.content.Context
import android.util.Log
import com.brushwork.paint.brush.BrushPresetStore
import com.brushwork.paint.fonts.FontStore
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.ui.color.PaletteData
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.common.BackupLabels19
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.File
import java.io.IOException
import java.util.UUID

/** v1.9 A8 (R16 (a)): one artwork the restore list offers. */
internal data class RestoreItem(
    val id: String,
    val name: String,
    val modifiedAt: Long,
    val revision: Long,
    /** Under `deleted/` (the collapsed section, unchecked). */
    val deleted: Boolean,
    /** The gallery has this id at this revision ([BackupLabels19.ALREADY], unchecked). */
    val already: Boolean,
) {
    /** Checked when the list opens. */
    val checkedByDefault: Boolean get() = !deleted && !already
}

/** v1.9 A8: a backup found in a picked folder. [root] is null when the folder holds none. */
internal class BackupSource(
    val tree: SafTree,
    val pickedIsRoot: Boolean,
    val root: SafDoc?,
    /** The artworks of the backup's index, or of its folders when the index is missing. */
    val count: Int,
)

/**
 * v1.9 A8 (R16 (b)): reading a backup back. It accepts the picked folder or its
 * [BackupLayout.ROOT_NAME] child, lists the artworks from the index (or by scanning the folders
 * when the index is missing or leaves some out), and restores into the gallery without ever
 * overwriting an artwork there (I23). Blocking; IO.
 */
internal object BackupRestore {
    private const val TAG = "BrushworkBackup"
    const val STAGING_PREFIX = ".restore-"

    /**
     * The backup under [tree]'s picked folder: the folder itself when it holds the index or is
     * named [BackupLayout.ROOT_NAME], else its [BackupLayout.ROOT_NAME] child (null when there is
     * none: nothing is created here).
     */
    fun find(tree: SafTree): BackupSource {
        val picked = tree.picked
        val children = tree.list(picked)
        if (children[BackupLayout.INDEX]?.isDir == false || picked.name == BackupLayout.ROOT_NAME) {
            return BackupSource(tree, pickedIsRoot = true, root = picked, count = count(tree, picked))
        }
        val inner = tree.dir(picked, BackupLayout.ROOT_NAME, create = false)
            ?: return BackupSource(tree, pickedIsRoot = false, root = null, count = 0)
        return BackupSource(tree, pickedIsRoot = false, root = inner, count = count(tree, inner))
    }

    /** The index's artworks still in [root], or the artwork folders when it has no index. */
    private fun count(tree: SafTree, root: SafDoc): Int {
        val children = tree.list(root)
        val index = readIndex(tree, root)
        val folders = children.values.filter { it.isDir && it.name !in BackupLayout.RESERVED && ProjectFormat.isValidId(it.name) }.map { it.name }.toSet()
        return if (index != null) index.artworks.count { it.id in folders } else folders.size
    }

    fun readIndex(tree: SafTree, root: SafDoc): BackupIndexData? {
        val doc = tree.child(root, BackupLayout.INDEX)?.takeIf { !it.isDir } ?: return null
        return try { BackupIndex.decode(tree.readBytes(doc)) } catch (e: IOException) { null }
    }

    /**
     * The artworks [root] holds, newest first: the index's (its names and revisions), then any
     * folder the index leaves out (read from its `project.json`), then the deleted ones (each id
     * once: a deleted folder whose id is listed above is left out). Each is
     * [RestoreItem.already] when [projectsDir] has its id at its revision.
     */
    fun list(tree: SafTree, root: SafDoc, projectsDir: File): List<RestoreItem> {
        val children = tree.list(root)
        val index = readIndex(tree, root)
        val out = ArrayList<RestoreItem>()
        val seen = HashSet<String>()
        fun add(id: String, name: String, modifiedAt: Long, revision: Long, deleted: Boolean) {
            val local = ProjectFormat.readOrNull(File(projectsDir, id))
            out += RestoreItem(id, name, modifiedAt, revision, deleted, already = local != null && local.revision == revision)
        }
        for (e in index?.artworks.orEmpty()) {
            if (children[e.id]?.isDir != true || !seen.add(e.id)) continue
            add(e.id, e.name, e.modifiedAt, e.revision, deleted = false)
        }
        for (doc in children.values) {
            if (!doc.isDir || doc.name in BackupLayout.RESERVED || !ProjectFormat.isValidId(doc.name) || doc.name in seen) continue
            val dto = readProject(tree, doc)?.second ?: continue
            seen += doc.name
            add(doc.name, dto.name, dto.modifiedAt, dto.revision, deleted = false)
        }
        val deletedDir = tree.dir(root, BackupLayout.DELETED, create = false)
        if (deletedDir != null) {
            val names = index?.deleted.orEmpty().associateBy { it.id }
            val deleted = ArrayList<Pair<RestoreItem, Long>>()
            for (doc in tree.list(deletedDir).values) {
                // An id listed above is restored from there ([restore] takes the root's folder first).
                if (!doc.isDir || !ProjectFormat.isValidId(doc.name) || doc.name in seen) continue
                val dto = readProject(tree, doc)?.second ?: continue
                val local = ProjectFormat.readOrNull(File(projectsDir, doc.name))
                val item = RestoreItem(doc.name, dto.name, dto.modifiedAt, dto.revision, deleted = true, already = local != null && local.revision == dto.revision)
                deleted += item to (names[doc.name]?.deletedAt ?: 0L)
            }
            val live = out.sortedByDescending { it.modifiedAt }
            return live + deleted.sortedByDescending { it.second }.map { it.first }
        }
        return out.sortedByDescending { it.modifiedAt }
    }

    /**
     * An artwork folder's project file and its contents: `project.json` when it decodes, else
     * `project.json.new` (the app was stopped between the mirror's delete and rename). Null when
     * neither decodes.
     */
    fun readProject(tree: SafTree, folder: SafDoc): Pair<ByteArray, ProjectFileDto>? {
        val files = tree.list(folder)
        for (name in listOf(ProjectFormat.PROJECT_FILE, ProjectFormat.PROJECT_FILE + BackupLayout.NEW_SUFFIX)) {
            val doc = files[name]?.takeIf { !it.isDir } ?: continue
            val bytes = try { tree.readBytes(doc) } catch (e: IOException) { continue }
            val dto = BackupMirror.decode(bytes) ?: continue
            return bytes to dto
        }
        return null
    }

    /**
     * Restores the artworks [ids] of [root] into [projectsDir] and returns the ids they got. Each
     * is staged in `projects/.restore-<id>`, checked (its project file decodes and every file it
     * references is there, at its size), then renamed into place. An id the gallery already has
     * gets a new id and the name suffix [BackupLabels19.RESTORED_SUFFIX] (never overwritten, I23);
     * otherwise the restored `project.json` is the backup's, byte for byte. An artwork that fails
     * its checks is left out.
     */
    fun restore(tree: SafTree, root: SafDoc, ids: List<String>, projectsDir: File, onEach: (done: Int) -> Unit = {}): List<String> {
        projectsDir.mkdirs()
        val deletedDir = tree.dir(root, BackupLayout.DELETED, create = false)
        val restored = ArrayList<String>()
        for ((i, id) in ids.withIndex()) {
            val folder = tree.dir(root, id, create = false)
                ?: deletedDir?.let { tree.dir(it, id, create = false) }
            if (folder != null) {
                try {
                    restoreOne(tree, folder, projectsDir)?.let { restored += it }
                } catch (e: Exception) {
                    Log.w(TAG, "could not restore $id", e)
                }
            }
            onEach(i + 1)
        }
        return restored
    }

    private fun restoreOne(tree: SafTree, folder: SafDoc, projectsDir: File): String? {
        val (bytes, dto) = readProject(tree, folder) ?: return null
        val referenced = ProjectFormat.referencedFiles(dto)
        if (referenced.any { !ProjectFormat.isDataFile(it) }) return null
        val files = tree.list(folder)
        if (referenced.any { files[it]?.isDir != false }) return null
        val sourceId = folder.name
        val taken = File(projectsDir, sourceId).exists()
        val id = if (taken || !ProjectFormat.isValidId(sourceId)) UUID.randomUUID().toString() else sourceId
        val staging = File(projectsDir, STAGING_PREFIX + id)
        try {
            staging.deleteRecursively()
            if (!staging.mkdirs()) throw IOException("Could not stage $id")
            for (name in referenced) {
                val doc = files.getValue(name)
                tree.open(doc).use { input -> File(staging, name).outputStream().use { input.copyTo(it, BackupLayout.BUFFER) } }
                if (File(staging, name).length() != doc.size) throw IOException("$name is incomplete")
            }
            files[ProjectFormat.THUMB_FILE]?.takeIf { !it.isDir }?.let { doc ->
                try {
                    tree.open(doc).use { input -> File(staging, ProjectFormat.THUMB_FILE).outputStream().use { input.copyTo(it, BackupLayout.BUFFER) } }
                } catch (e: IOException) {
                    File(staging, ProjectFormat.THUMB_FILE).delete() // the gallery shows a blank card until the next save
                }
            }
            if (id == sourceId && dto.id == sourceId) {
                ProjectFormat.writeAtomically(File(staging, ProjectFormat.PROJECT_FILE)) { it.write(bytes) }
            } else {
                val name = if (taken) restoredName(dto.name) else dto.name
                ProjectFormat.write(staging, dto.copy(id = id, name = name))
            }
            val check = ProjectFormat.read(staging)
            for (name in ProjectFormat.referencedFiles(check)) if (!File(staging, name).isFile) throw IOException("$name is missing")
            val target = File(projectsDir, id)
            if (target.exists() || !staging.renameTo(target)) throw IOException("Could not move $id into the gallery")
            return id
        } catch (e: Exception) {
            staging.deleteRecursively()
            throw e
        }
    }

    /** [name] with [BackupLabels19.RESTORED_SUFFIX], within the gallery's 100-character names. */
    fun restoredName(name: String): String = name.take(100 - BackupLabels19.RESTORED_SUFFIX.length).trimEnd() + BackupLabels19.RESTORED_SUFFIX

    /**
     * Restores `settings.json` of [root]: the settings are written back; brush presets and the
     * last preset of each tool are added only where this install has none; palettes are added by
     * id (none is replaced); imported fonts missing here are copied from `fonts/`. False when the
     * backup has no readable settings.
     */
    suspend fun restoreSettings(context: Context, tree: SafTree, root: SafDoc): Boolean {
        val doc = tree.child(root, BackupLayout.SETTINGS)?.takeIf { !it.isDir } ?: return false
        val snapshot = try { BackupSettings.decode(tree.readBytes(doc)) } catch (e: IOException) { null } ?: return false
        val app = context.applicationContext
        app.getSharedPreferences(BackupSettings.SETTINGS_PREFS, Context.MODE_PRIVATE).edit().apply {
            for ((k, v) in snapshot.settings) BackupSettings.put(this, k, v)
        }.commit()
        val brushes = app.getSharedPreferences(BrushPresetStore.PREFS_NAME, Context.MODE_PRIVATE)
        brushes.edit().apply {
            for ((k, v) in snapshot.brushes) if (!brushes.contains(k)) BackupSettings.put(this, k, v)
        }.commit()
        val palettes = (snapshot.palettes[PaletteStore.KEY] as? BackupSettings.PrefValue.Str)?.v
        if (palettes != null) {
            val other = PaletteData.decode(palettes)
            withContext(Dispatchers.Main.immediate) { PaletteStore.mergeMissing(app, other) }
        }
        val fontsDir = tree.dir(root, BackupLayout.FONTS, create = false)
        if (fontsDir != null && snapshot.fonts.isNotEmpty()) {
            val files = tree.list(fontsDir)
            FontStore.get(app).restoreFonts(snapshot.fonts.filter { files[it.file]?.isDir == false }) { f -> tree.open(files.getValue(f.file)) }
        }
        return true
    }
}
