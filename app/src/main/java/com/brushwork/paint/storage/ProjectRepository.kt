package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.brushwork.paint.ImageImport
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext
import java.io.File
import java.io.FileNotFoundException
import java.io.IOException
import java.nio.ByteBuffer
import java.util.UUID
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.max
import kotlin.math.roundToInt

data class ProjectInfo(
    val id: String,
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float,
    val layerCount: Int,
    val modifiedAt: Long,
    /** PNG thumbnail file, or null if none yet. */
    val thumbnail: File?,
)

/**
 * Parameters of a new blank canvas. [background] null = transparent. v1.9 (design 2.4.8): [start]
 * is how the canvas starts ([CanvasCreate]); null = v1.8 ("Background" + "Layer 1").
 */
data class NewCanvasSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float,
    val background: Int? = 0xFFFFFFFF.toInt(),
    val start: CanvasStart? = null,
)

enum class ExportFormat(val extension: String, val mimeType: String) {
    PNG("png", "image/png"),
    JPEG("jpg", "image/jpeg"),
    WEBP("webp", "image/webp"),
}

/**
 * Stores projects in app-private storage (one folder per project) and exports images.
 * All suspend functions are main-safe (they switch to IO internally). [save] must be called
 * from the main thread's coroutine context because it reads layer bitmaps.
 *
 * Project folder: `filesDir/projects/<id>/` with `project.json`, one compressed pixel file per
 * layer and mask, and `thumb.png` (see [ProjectFormat] and [LayerCodec]). Operations on one
 * project are serialized by a per-project [Mutex].
 */
class ProjectRepository(private val context: Context) {
    private val changeCounter = MutableStateFlow(0)

    /** Incremented whenever the project list changes (gallery observes it). */
    val changes: StateFlow<Int> = changeCounter

    private val root: File get() = File(context.filesDir, "projects")
    private val locks = ConcurrentHashMap<String, Mutex>()

    /** True on Android 9 and older while WRITE_EXTERNAL_STORAGE (needed by [exportToGallery]) isn't granted. */
    val needsStoragePermission: Boolean get() = ImageExport.needsLegacyPermission(context)

    // ------------------------------------------------------------------ listing

    /** All readable projects, most recently modified first (corrupt folders are skipped). */
    suspend fun list(): List<ProjectInfo> = withContext(Dispatchers.IO) {
        val all = root.listFiles().orEmpty()
        // Leftovers of a duplicate() interrupted by a crash.
        val staleBefore = System.currentTimeMillis() - 60 * 60 * 1000L
        all.filter { it.name.startsWith(DUP_PREFIX) && it.lastModified() < staleBefore }.forEach { it.deleteRecursively() }
        val dirs = all.filter { it.isDirectory && ProjectFormat.isValidId(it.name) }
        dirs.mapNotNull { dir ->
            val dto = ProjectFormat.readOrNull(dir) ?: return@mapNotNull null
            ProjectInfo(
                id = dir.name,
                name = dto.name,
                width = dto.width,
                height = dto.height,
                dpi = dto.dpi,
                layerCount = dto.layers.count { it.folderSpec(dto.formatVersion) == null },
                modifiedAt = dto.modifiedAt,
                thumbnail = File(dir, ProjectFormat.THUMB_FILE).takeIf { it.isFile },
            )
        }.sortedByDescending { it.modifiedAt }
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a project from [spec] and returns the new id. v1.9 (R15, design 2.4.8): the layers,
     * the active layer and the paper are [CanvasCreate.seed]'s: one "Layer 1" for every
     * [NewCanvasSpec.start], v1.8's "Background" (filled with [NewCanvasSpec.background], or
     * transparent) under an empty "Layer 1" for a null start. Writes the pixel files directly, so
     * no full-size bitmaps are allocated.
     */
    suspend fun create(spec: NewCanvasSpec): String = withContext(Dispatchers.IO) {
        val w = spec.width
        val h = spec.height
        require(w in 1..MAX_SIDE && h in 1..MAX_SIDE) { "The canvas size must be between 1 and $MAX_SIDE px" }
        val id = newId()
        val dir = dirOf(id)
        try {
            if (!dir.mkdirs()) throw IOException("Could not create the project folder")
            val seed = CanvasCreate.seed(spec)
            val layers = writeSeedLayers(dir, w, h, seed)
            val now = System.currentTimeMillis()
            ProjectFormat.write(
                dir,
                ProjectFileDto(
                    id = id,
                    name = cleanName(spec.name),
                    width = w,
                    height = h,
                    dpi = validDpi(spec.dpi),
                    createdAt = now,
                    modifiedAt = now,
                    activeLayerIndex = seed.activeLayerIndex,
                    layers = layers,
                    paper = seed.paper,
                ),
            )
            val (tw, th) = thumbnailSize(w, h)
            val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            try {
                drawSeedThumbnail(thumb, seed)
                writeThumbnail(dir, thumb)
            } finally {
                thumb.recycle()
            }
        } catch (e: Throwable) {
            dir.deleteRecursively()
            throw e
        }
        notifyChanged()
        id
    }

    /**
     * Creates a project from a picture: a document of the image size (350 dpi, longest side at
     * most 4096 px) with the picture as its one layer, "Picture" (v1.9, R15: no empty "Layer 1"
     * above it; [CanvasCreate.seedFromImage]). Pictures too large for this device's memory are
     * scaled down until at least [CanvasLimits.IMPORT_MIN_LAYERS] layers fit (the same rule the
     * editor's layer limit uses); wide-gamut photos are converted to sRGB like every layer.
     */
    suspend fun createFromImage(uri: Uri): String = withContext(Dispatchers.IO) {
        val maxSide = pictureBounds(uri)?.let { (w, h) ->
            CanvasLimits.importMaxSide(w, h, Runtime.getRuntime().maxMemory())
        } ?: CanvasLimits.IMPORT_MAX_SIDE
        val decoded = ImageImport.decode(context, uri, maxSide)
        val picture = if (LayerCodec.isStorable(decoded)) {
            decoded
        } else {
            try { LayerCodec.toStorable(decoded) } finally { decoded.recycle() }
        }
        try {
            val w = picture.width
            val h = picture.height
            val id = newId()
            val dir = dirOf(id)
            try {
                if (!dir.mkdirs()) throw IOException("Could not create the project folder")
                val name = cleanName(displayNameOf(uri) ?: "Imported picture")
                val seed = CanvasCreate.seedFromImage(picture, name)
                val layers = writeSeedLayers(dir, w, h, seed)
                val now = System.currentTimeMillis()
                ProjectFormat.write(
                    dir,
                    ProjectFileDto(
                        id = id,
                        name = name,
                        width = w,
                        height = h,
                        dpi = 350f,
                        createdAt = now,
                        modifiedAt = now,
                        activeLayerIndex = seed.activeLayerIndex,
                        layers = layers,
                        paper = seed.paper,
                    ),
                )
                val (tw, th) = thumbnailSize(w, h)
                // v1.8: the picture is the thumbnail; a seed with a paper would draw it underneath.
                val thumb = when {
                    seed.paper != null -> Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888).also { drawSeedThumbnail(it, seed) }
                    tw == w && th == h -> picture
                    else -> Bitmap.createScaledBitmap(picture, tw, th, true)
                }
                try {
                    writeThumbnail(dir, thumb)
                } finally {
                    if (thumb !== picture) thumb.recycle()
                }
            } catch (e: Throwable) {
                dir.deleteRecursively()
                throw e
            }
            notifyChanged()
            id
        } finally {
            picture.recycle()
        }
    }

    // ------------------------------------------------------------------ load / save

    /**
     * Loads a project into a new [Document]. Throws [FileNotFoundException] if the project
     * doesn't exist and [CorruptProjectException] (with a readable message) if it is damaged.
     */
    suspend fun load(id: String): Document = lockFor(id).withLock {
        withContext(Dispatchers.IO) { loadLocked(id) }
    }

    private fun loadLocked(id: String): Document {
        val dir = dirOf(id)
        if (!dir.isDirectory) throw FileNotFoundException("This artwork no longer exists")
        val dto = try {
            ProjectFormat.read(dir)
        } catch (e: FileNotFoundException) {
            throw CorruptProjectException("The artwork's project file is missing", e)
        } catch (e: Exception) {
            throw CorruptProjectException("The artwork's project file is damaged", e)
        }
        if (dto.formatVersion > ProjectFormat.VERSION) throw IOException("This artwork was saved by a newer version of Brushwork")
        val w = dto.width
        val h = dto.height
        if (w !in 1..MAX_SIDE || h !in 1..MAX_SIDE) throw CorruptProjectException("The artwork has an invalid size (${w}x$h)")
        if (dto.layers.isEmpty()) throw CorruptProjectException("The artwork has no layers")

        val doc = Document(id, dto.name, w, h, validDpi(dto.dpi))
        doc.colorMode = dto.colorMode
        if (dto.createdAt > 0) doc.createdAt = dto.createdAt
        if (dto.modifiedAt > 0) doc.modifiedAt = dto.modifiedAt
        doc.grid = dto.grid
        doc.ruler = dto.ruler

        // Layer entries and saved selections: LayerEntries (v1.7 X3; load order in §4.3).
        LayerEntries.readLayers(dir, dto, doc)
        LayerEntries.readSelections(dir, dto, doc)
        doc.symmetry = dto.symmetry.sanitized()
        doc.paper = dto.paper
        doc.activeLayerIndex = dto.activeLayerIndex.coerceIn(0, doc.layers.lastIndex)
        for (layer in doc.layers) layer.savedVersion = layer.contentVersion
        return doc
    }

    /**
     * Saves [doc] incrementally: only layers whose `contentVersion` differs from `savedVersion`
     * (or whose files are missing) are written. Pixels are copied on the calling (main) thread
     * one layer at a time, compressed and written on IO. Changed layers go to new files and
     * `project.json` is replaced atomically, so an interrupted save never damages the project.
     * Throws on I/O errors.
     */
    suspend fun save(doc: Document, thumbnail: Bitmap?) {
        val id = doc.id
        val dir = dirOf(id)
        lockFor(id).withLock {
            val (existing, previous) = withContext(Dispatchers.IO) {
                if (!dir.isDirectory && !dir.mkdirs()) throw IOException("Could not create the project folder")
                (dir.list()?.toHashSet() ?: HashSet()) to ProjectFormat.readOrNull(dir)
            }
            // From here on we are on the caller's (main) thread except inside withContext(IO).
            val w = doc.width
            val h = doc.height
            val revision = (previous?.revision ?: 0L) + 1
            val layers = doc.layers.toList()
            val selections = doc.savedSelections
            val meta = ProjectFileDto(
                formatVersion = ProjectFormat.writtenVersion(layers),
                id = id,
                name = doc.name,
                width = w,
                height = h,
                dpi = validDpi(doc.dpi),
                colorMode = doc.colorMode,
                createdAt = doc.createdAt,
                modifiedAt = doc.modifiedAt,
                activeLayerIndex = doc.activeLayerIndex.coerceIn(0, max(0, layers.lastIndex)),
                grid = doc.grid,
                ruler = doc.ruler,
                revision = revision,
                symmetry = doc.symmetry,
                nextSelectionId = doc.nextSelectionId,
                paper = doc.paper,
            )
            // Layer entries: LayerEntries.Writer (v1.7 X3). Null = the document was resized
            // while we were writing: the next save retries.
            val writer = LayerEntries.Writer(dir, doc, revision, existing, previous)
            val entries = ArrayList<LayerEntryDto>(layers.size)
            for (layer in layers) entries += writer.entryFor(layer) ?: return@withLock
            writer.release() // let the (possibly large) buffer be collected before the slower steps
            val selectionEntries = LayerEntries.writeSelections(dir, selections, existing, previous)
            val dto = meta.copy(layers = entries, selections = selectionEntries)
            withContext(Dispatchers.IO) {
                ProjectFormat.write(dir, dto)
                ProjectFormat.deleteUnreferenced(dir, dto)
                if (thumbnail != null && !thumbnail.isRecycled) {
                    // The project itself is saved at this point; a thumbnail problem must not
                    // turn it into a failed save.
                    try {
                        writeThumbnail(dir, thumbnail)
                    } catch (e: Exception) {
                        Log.w(TAG, "thumbnail write failed", e)
                    }
                }
            }
            // Only now is the new content referenced by project.json.
            for ((layer, version) in writer.savedVersions) layer.savedVersion = version
        }
        notifyChanged()
    }

    // ------------------------------------------------------------------ manage

    /** Deletes a project folder and everything in it. */
    suspend fun delete(id: String) {
        lockFor(id).withLock {
            withContext(Dispatchers.IO) {
                val dir = dirOf(id)
                if (dir.exists() && !dir.deleteRecursively()) throw IOException("Could not delete the artwork")
            }
        }
        notifyChanged()
    }

    /** Copies a project (new id, name "<name> copy", fresh dates). Returns the new id. */
    suspend fun duplicate(id: String): String {
        val newId = newId()
        lockFor(id).withLock {
            withContext(Dispatchers.IO) {
                val src = dirOf(id)
                val dto = try { ProjectFormat.read(src) } catch (e: Exception) { throw CorruptProjectException("The artwork's project file is damaged", e) }
                val staging = File(root, DUP_PREFIX + newId)
                try {
                    if (!staging.mkdirs()) throw IOException("Could not create the copy")
                    for (name in ProjectFormat.referencedFiles(dto)) {
                        if (!ProjectFormat.isDataFile(name)) throw CorruptProjectException("The artwork refers to an invalid file")
                        File(src, name).copyTo(File(staging, name), overwrite = true)
                    }
                    File(src, ProjectFormat.THUMB_FILE).takeIf { it.isFile }?.copyTo(File(staging, ProjectFormat.THUMB_FILE), overwrite = true)
                    val now = System.currentTimeMillis()
                    ProjectFormat.write(staging, dto.copy(id = newId, name = cleanName("${dto.name} copy"), createdAt = now, modifiedAt = now))
                    if (!staging.renameTo(dirOf(newId))) throw IOException("Could not create the copy")
                } catch (e: Throwable) {
                    staging.deleteRecursively()
                    throw e
                }
            }
        }
        notifyChanged()
        return newId
    }

    /** Renames a project (blank names are ignored). */
    suspend fun rename(id: String, name: String) {
        val clean = name.trim()
        if (clean.isEmpty()) return
        lockFor(id).withLock {
            withContext(Dispatchers.IO) {
                val dir = dirOf(id)
                val dto = try { ProjectFormat.read(dir) } catch (e: Exception) { throw CorruptProjectException("The artwork's project file is damaged", e) }
                ProjectFormat.write(dir, dto.copy(name = cleanName(clean)))
            }
        }
        notifyChanged()
    }

    // ------------------------------------------------------------------ export

    /**
     * Saves [bitmap] to the device gallery (Pictures/Brushwork). Returns the content Uri, or
     * null on failure — including Android 9 and older when WRITE_EXTERNAL_STORAGE hasn't been
     * granted (request it first; see [needsStoragePermission]). JPEG is flattened onto white.
     */
    suspend fun exportToGallery(bitmap: Bitmap, name: String, format: ExportFormat): Uri? = withContext(Dispatchers.IO) {
        guarded("export") { ImageExport.saveToGallery(context, bitmap, name, format) }
    }

    /** Writes [bitmap] to a shareable cache file and returns a FileProvider Uri (null on failure). */
    suspend fun exportForShare(bitmap: Bitmap, name: String, format: ExportFormat): Uri? = withContext(Dispatchers.IO) {
        guarded("share export") { ImageExport.saveForShare(context, bitmap, name, format) }
    }

    /** Loads, flattens and exports a project without opening it in the editor (null on failure). */
    suspend fun exportProject(id: String, format: ExportFormat): Uri? = guarded("project export") {
        val (flat, name) = renderFlattened(id)
        try { exportToGallery(flat, name, format) } finally { flat.recycle() }
    }

    /** Loads and flattens a project into a share file (see [exportForShare]); null on failure. */
    suspend fun shareProject(id: String, format: ExportFormat = ExportFormat.PNG): Uri? = guarded("project share") {
        val (flat, name) = renderFlattened(id)
        try { exportForShare(flat, name, format) } finally { flat.recycle() }
    }

    /**
     * Full-resolution flattened image of a stored project. The caller recycles it. v1.9 (design
     * 2.4.9): on its paper, if any, like the editor's export and share.
     */
    private suspend fun renderFlattened(id: String): Pair<Bitmap, String> {
        val doc = load(id)
        return withContext(Dispatchers.Default) {
            try {
                Compositor(doc) { null }.renderFlattened(withPaper = true) to doc.name
            } finally {
                for (layer in doc.layers) { layer.recycleBitmaps(); layer.array?.pixels?.bitmap?.recycle() }
            }
        }
    }

    private inline fun <T> guarded(what: String, block: () -> T?): T? = try {
        block()
    } catch (e: CancellationException) {
        throw e
    } catch (e: Exception) {
        Log.w(TAG, "$what failed", e)
        null
    } catch (e: OutOfMemoryError) {
        Log.w(TAG, "$what ran out of memory", e)
        null
    }

    // ------------------------------------------------------------------ helpers

    private fun lockFor(id: String): Mutex = locks.getOrPut(id) { Mutex() }

    private fun dirOf(id: String): File {
        if (!ProjectFormat.isValidId(id)) throw FileNotFoundException("Invalid artwork id")
        return File(root, id)
    }

    private fun notifyChanged() = changeCounter.update { it + 1 }

    private fun newId(): String = UUID.randomUUID().toString()

    private fun cleanName(name: String): String = name.trim().replace(Regex("\\s+"), " ").take(MAX_NAME).ifEmpty { "Untitled" }

    private fun validDpi(dpi: Float): Float = if (dpi.isFinite() && dpi >= 1f) dpi else 350f

    /**
     * v1.9 (design 2.4.8): writes [seed]'s layers as the pixel files of a new project in [dir]
     * (ids 1, 2, ... bottom first, revision 0, as v1.8 numbered them) and returns their entries.
     * A filled layer is written row by row, so no full-size bitmap is allocated (v1.8).
     */
    private fun writeSeedLayers(dir: File, w: Int, h: Int, seed: CanvasCreate.Seed): List<LayerEntryDto> =
        seed.layers.mapIndexed { i, layer ->
            val layerId = i + 1L
            val file = ProjectFormat.layerFile(layerId, 0)
            val picture = layer.picture
            if (picture != null) {
                val buffer = ByteArray(LayerCodec.byteLength(w, h))
                LayerCodec.copyPixels(picture, buffer)
                LayerCodec.write(File(dir, file), w, h, buffer)
            } else {
                LayerCodec.writeRepeatedRow(File(dir, file), w, h, uniformRow(w, layer.fill))
            }
            LayerEntryDto(layerId, layer.name, ProjectFormat.defaultProps(layer.name), file = file)
        }

    /**
     * v1.9 (design 2.4.8): [seed]'s look in [thumb]. Without a paper and with at most one filled
     * layer (and no picture), v1.8's single erase with that fill (or transparent); otherwise the
     * paper, then each layer bottom first.
     */
    private fun drawSeedThumbnail(thumb: Bitmap, seed: CanvasCreate.Seed) {
        val fills = seed.layers.filter { it.picture == null && it.fill != 0 }
        if (seed.paper == null && fills.size <= 1 && seed.layers.none { it.picture != null }) {
            thumb.eraseColor(fills.firstOrNull()?.fill ?: 0)
            return
        }
        val canvas = Canvas(thumb)
        seed.paper?.let { canvas.drawColor(it.color) }
        val paint = Paint(Paint.FILTER_BITMAP_FLAG)
        val dst = Rect(0, 0, thumb.width, thumb.height)
        for (layer in seed.layers) {
            val picture = layer.picture
            when {
                picture != null -> canvas.drawBitmap(picture, null, dst, paint)
                layer.fill != 0 -> canvas.drawColor(layer.fill)
            }
        }
    }

    /** One row of `width` pixels of [color] in the bitmap's raw (premultiplied) byte layout. */
    private fun uniformRow(width: Int, color: Int): ByteArray {
        val row = ByteArray(width * 4)
        if (color == 0) return row
        val b = Bitmap.createBitmap(width, 1, Bitmap.Config.ARGB_8888)
        try {
            b.eraseColor(color)
            b.copyPixelsToBuffer(ByteBuffer.wrap(row))
        } finally {
            b.recycle()
        }
        return row
    }

    private fun writeThumbnail(dir: File, bitmap: Bitmap) {
        ProjectFormat.writeAtomically(File(dir, ProjectFormat.THUMB_FILE)) {
            if (!bitmap.compress(Bitmap.CompressFormat.PNG, 100, it)) throw IOException("Could not encode the thumbnail")
        }
    }

    /** The picture's stored size (before EXIF rotation), or null if it can't be read. BLOCKING. */
    private fun pictureBounds(uri: Uri): Pair<Int, Int>? = try {
        val opts = BitmapFactory.Options().apply { inJustDecodeBounds = true }
        context.contentResolver.openInputStream(uri)?.use { BitmapFactory.decodeStream(it, null, opts) }
        if (opts.outWidth > 0 && opts.outHeight > 0) opts.outWidth to opts.outHeight else null
    } catch (e: Exception) {
        null
    }

    private fun displayNameOf(uri: Uri): String? = try {
        context.contentResolver.query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)?.use { c ->
            if (c.moveToFirst() && !c.isNull(0)) c.getString(0) else null
        }
    } catch (e: Exception) {
        null
    }?.substringBeforeLast('.')?.trim()?.takeIf { it.isNotEmpty() }?.take(MAX_NAME)

    companion object {
        private const val TAG = "Brushwork"
        /** Longest canvas side accepted by [create] and [load]. */
        const val MAX_SIDE = CanvasLimits.MAX_SIDE
        private const val MAX_NAME = 100
        private const val DUP_PREFIX = ".dup-"

        /** Size of the stored thumbnail (longest side [ProjectFormat.THUMB_SIZE]), like `Compositor.renderThumbnail`. */
        internal fun thumbnailSize(w: Int, h: Int): Pair<Int, Int> {
            val s = minOf(1f, ProjectFormat.THUMB_SIZE.toFloat() / max(w, h))
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }
    }
}
