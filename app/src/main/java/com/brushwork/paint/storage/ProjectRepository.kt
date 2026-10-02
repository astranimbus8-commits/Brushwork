package com.brushwork.paint.storage

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.net.Uri
import android.provider.OpenableColumns
import android.util.Log
import com.brushwork.paint.ImageImport
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.vector.CorruptVectorException
import com.brushwork.paint.vector.VectorCodec
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

/** Parameters of a new blank canvas. [background] null = transparent. */
data class NewCanvasSpec(
    val name: String,
    val width: Int,
    val height: Int,
    val dpi: Float,
    val background: Int? = 0xFFFFFFFF.toInt(),
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
                layerCount = dto.layers.size,
                modifiedAt = dto.modifiedAt,
                thumbnail = File(dir, ProjectFormat.THUMB_FILE).takeIf { it.isFile },
            )
        }.sortedByDescending { it.modifiedAt }
    }

    // ------------------------------------------------------------------ create

    /**
     * Creates a project with a "Background" layer (filled with [NewCanvasSpec.background], or
     * transparent) and an empty "Layer 1" on top. Returns the new id. Writes the pixel files
     * directly, so no full-size bitmaps are allocated.
     */
    suspend fun create(spec: NewCanvasSpec): String = withContext(Dispatchers.IO) {
        val w = spec.width
        val h = spec.height
        require(w in 1..MAX_SIDE && h in 1..MAX_SIDE) { "The canvas size must be between 1 and $MAX_SIDE px" }
        val id = newId()
        val dir = dirOf(id)
        try {
            if (!dir.mkdirs()) throw IOException("Could not create the project folder")
            val background = spec.background ?: 0
            val bg = ProjectFormat.layerFile(1, 0)
            val top = ProjectFormat.layerFile(2, 0)
            LayerCodec.writeRepeatedRow(File(dir, bg), w, h, uniformRow(w, background))
            LayerCodec.writeRepeatedRow(File(dir, top), w, h, ByteArray(w * 4))
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
                    activeLayerIndex = 1,
                    layers = listOf(
                        LayerEntryDto(1, "Background", ProjectFormat.defaultProps("Background"), file = bg),
                        LayerEntryDto(2, "Layer 1", ProjectFormat.defaultProps("Layer 1"), file = top),
                    ),
                ),
            )
            val (tw, th) = thumbnailSize(w, h)
            val thumb = Bitmap.createBitmap(tw, th, Bitmap.Config.ARGB_8888)
            try {
                thumb.eraseColor(background)
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
     * most 4096 px) with the picture as a "Picture" layer and an empty "Layer 1" above it.
     * Pictures too large for this device's memory are scaled down until at least
     * [CanvasLimits.IMPORT_MIN_LAYERS] layers fit (the same rule the editor's layer limit uses);
     * wide-gamut photos are converted to sRGB like every layer.
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
                val pic = ProjectFormat.layerFile(1, 0)
                val top = ProjectFormat.layerFile(2, 0)
                val buffer = ByteArray(LayerCodec.byteLength(w, h))
                LayerCodec.copyPixels(picture, buffer)
                LayerCodec.write(File(dir, pic), w, h, buffer)
                LayerCodec.writeRepeatedRow(File(dir, top), w, h, ByteArray(w * 4))
                val now = System.currentTimeMillis()
                ProjectFormat.write(
                    dir,
                    ProjectFileDto(
                        id = id,
                        name = cleanName(displayNameOf(uri) ?: "Imported picture"),
                        width = w,
                        height = h,
                        dpi = 350f,
                        createdAt = now,
                        modifiedAt = now,
                        activeLayerIndex = 1,
                        layers = listOf(
                            LayerEntryDto(1, "Picture", ProjectFormat.defaultProps("Picture"), file = pic),
                            LayerEntryDto(2, "Layer 1", ProjectFormat.defaultProps("Layer 1"), file = top),
                        ),
                    ),
                )
                val (tw, th) = thumbnailSize(w, h)
                val thumb = if (tw == w && th == h) picture else Bitmap.createScaledBitmap(picture, tw, th, true)
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

        val scratch = ByteArray(LayerCodec.byteLength(w, h))
        val allocated = ArrayList<Bitmap>()
        try {
            for (entry in dto.layers) {
                val bitmap = BitmapUtils.createLayerBitmap(w, h).also { allocated += it }
                readPixels(dir, entry.contentFileName, bitmap, scratch, "Layer \"${entry.props.name}\"")
                val layer = Layer(entry.id, entry.props.name, bitmap)
                layer.copyPropsFrom(sanitized(entry.props))
                layer.textData = entry.textData
                layer.shapeData = entry.shapeData
                if (entry.hasMask) {
                    val mask = BitmapUtils.createLayerBitmap(w, h).also { allocated += it }
                    readPixels(dir, entry.maskFileName, mask, scratch, "The mask of layer \"${entry.props.name}\"")
                    layer.mask = mask
                }
                readEditableData(dir, entry, layer, doc.loadWarnings)
                doc.layers += layer
                doc.ensureNextLayerIdAbove(entry.id)
            }
        } catch (e: Throwable) {
            allocated.forEach { it.recycle() }
            throw e
        }
        sanitizeAdjustmentClipping(doc.layers)
        reserveWrapSourceIds(doc)
        doc.activeLayerIndex = dto.activeLayerIndex.coerceIn(0, doc.layers.lastIndex)
        for (layer in doc.layers) layer.savedVersion = layer.contentVersion
        return doc
    }

    /**
     * The v1.5 editable data of [entry] (vector content, mask spec, adjustment). Data that can't
     * be read affects only its own layer: it loads without it (its pixels are intact; an
     * adjustment layer becomes a plain empty layer) and a message goes to [warnings].
     */
    private fun readEditableData(dir: File, entry: LayerEntryDto, layer: Layer, warnings: MutableList<String>) {
        val name = entry.props.name
        entry.vectorFile?.let { file ->
            layer.vector = try {
                if (!ProjectFormat.isVectorFile(file)) throw CorruptVectorException("invalid file name")
                VectorCodec.decode(File(dir, file).readBytes())
            } catch (e: Exception) {
                Log.w(TAG, "vector data of layer $name unreadable", e)
                warnings += "The vector objects of \"$name\" could not be read: it opened as a regular layer."
                null
            }
        }
        entry.maskSpec?.let { s ->
            val spec = MaskCodec.decode(s)
            if (spec != null && layer.mask != null) layer.maskSpec = spec
            else warnings += "The editable mask of \"$name\" could not be read: it opened as a painted mask."
        }
        entry.adjustment?.let { s ->
            val spec = AdjustmentCodec.decode(s)
            when {
                spec == null -> warnings += "The effect of adjustment layer \"$name\" could not be read: it has no effect now."
                else -> {
                    layer.adjustment = spec
                    // Kept (an update may know it) and drawn as pass-through meanwhile.
                    if (FilterRegistry.byId(spec.filterId) == null) warnings += "Adjustment layer \"$name\" uses an unknown effect: it shows no effect."
                }
            }
        }
    }

    /**
     * A text wrapped around a picture that was deleted keeps that picture's layer id (v1.5 §4.1:
     * it keeps its outline, and its sheet says the layer is gone). Ids are only made unique above
     * the layers that were saved, so a new layer could take the deleted picture's id and the text
     * would follow that layer: the ids such texts name are reserved as well.
     */
    private fun reserveWrapSourceIds(doc: Document) {
        for (layer in doc.layers) {
            val source = TextCodec.wrapSourceId(layer.textData)
            if (source in 1..MAX_RESERVED_LAYER_ID) doc.ensureNextLayerIdAbove(source)
        }
    }

    /**
     * Adjustment layers are never part of a clipping group: a clipping flag on one, or on the
     * layer right above one, is cleared (the compositor already ignores them).
     */
    private fun sanitizeAdjustmentClipping(layers: List<Layer>) {
        for (i in layers.indices) {
            val l = layers[i]
            if (!l.clipping) continue
            if (l.isAdjustmentLayer || (i > 0 && layers[i - 1].isAdjustmentLayer)) l.clipping = false
        }
    }

    /** Layer properties with a usable opacity (a damaged or hand-edited file may hold NaN). */
    private fun sanitized(props: LayerProps): LayerProps {
        val o = props.opacity
        return if (o.isFinite() && o in 0f..1f) props else props.copy(opacity = if (o.isNaN()) 1f else o.coerceIn(0f, 1f))
    }

    private fun readPixels(dir: File, fileName: String, into: Bitmap, scratch: ByteArray, what: String) {
        if (!ProjectFormat.isPixelFile(fileName)) throw CorruptProjectException("$what refers to an invalid file")
        try {
            LayerCodec.readInto(File(dir, fileName), into, scratch)
        } catch (e: CorruptProjectException) {
            throw CorruptProjectException("$what is damaged or missing (${e.message})", e)
        }
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
            val length = LayerCodec.byteLength(w, h)
            val sizeChanged = previous == null || previous.width != w || previous.height != h
            val previousEntries = previous?.layers?.associateBy { it.id }.orEmpty()
            val revision = (previous?.revision ?: 0L) + 1
            val layers = doc.layers.toList()
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
            )
            val entries = ArrayList<LayerEntryDto>(layers.size)
            val savedVersions = ArrayList<Pair<Layer, Long>>()
            var scratch: ByteArray? = null
            for (layer in layers) {
                val mask = layer.mask
                val props = layer.props()
                val prev = previousEntries[layer.id]
                val vector = layer.vector
                // Specs are small: stored inside project.json (a damaged one only affects its layer).
                val maskSpecText = if (mask != null) layer.maskSpec?.let { MaskCodec.encode(it) } else null
                val adjustmentText = layer.adjustment?.let { AdjustmentCodec.encode(it) }
                val prevVector = prev?.vectorFile
                if (prev != null && !sizeChanged &&
                    layer.contentVersion == layer.savedVersion &&
                    prev.hasMask == (mask != null) &&
                    prev.contentFileName in existing &&
                    (mask == null || prev.maskFileName in existing) &&
                    (vector == null || (prevVector != null && prevVector in existing))
                ) {
                    entries += LayerEntryDto(
                        layer.id, props.name, props, mask != null, prev.contentFileName, if (mask != null) prev.maskFileName else null,
                        layer.textData, layer.shapeData, if (vector != null) prevVector else null, maskSpecText, adjustmentText,
                    )
                    continue
                }
                // The document may have been resized while we were writing: the next save retries.
                if (!fits(doc, w, h, layer.bitmap)) return@withLock
                val version = layer.contentVersion
                val buffer = scratch ?: ByteArray(length).also { scratch = it }
                val contentName = ProjectFormat.layerFile(layer.id, revision)
                LayerCodec.copyPixels(layer.bitmap, buffer)
                withContext(Dispatchers.IO) { LayerCodec.write(File(dir, contentName), w, h, buffer, length) }
                var maskName: String? = null
                if (mask != null) {
                    if (!fits(doc, w, h, mask)) return@withLock
                    maskName = ProjectFormat.maskFile(layer.id, revision)
                    LayerCodec.copyPixels(mask, buffer)
                    withContext(Dispatchers.IO) { LayerCodec.write(File(dir, maskName), w, h, buffer, length) }
                }
                // The vector content is immutable: encoded off the main thread, written atomically.
                var vectorName: String? = null
                if (vector != null) {
                    val name = ProjectFormat.vectorFile(layer.id, revision)
                    val bytes = withContext(Dispatchers.Default) { VectorCodec.encode(vector) }
                    withContext(Dispatchers.IO) { ProjectFormat.writeAtomically(File(dir, name)) { it.write(bytes) } }
                    vectorName = name
                }
                entries += LayerEntryDto(
                    layer.id, props.name, props, mask != null, contentName, maskName,
                    layer.textData, layer.shapeData, vectorName, maskSpecText, adjustmentText,
                )
                savedVersions += layer to version
            }
            @Suppress("UNUSED_VALUE")
            scratch = null // let the (possibly large) buffer be collected before the slower steps
            val dto = meta.copy(layers = entries)
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
            for ((layer, version) in savedVersions) layer.savedVersion = version
        }
        notifyChanged()
    }

    private fun fits(doc: Document, w: Int, h: Int, bitmap: Bitmap): Boolean =
        doc.width == w && doc.height == h && !bitmap.isRecycled && bitmap.width == w && bitmap.height == h

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
                        if (!ProjectFormat.isPixelFile(name) && !ProjectFormat.isVectorFile(name)) throw CorruptProjectException("The artwork refers to an invalid file")
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

    /** Full-resolution flattened image of a stored project. The caller recycles it. */
    private suspend fun renderFlattened(id: String): Pair<Bitmap, String> {
        val doc = load(id)
        return withContext(Dispatchers.Default) {
            try {
                Compositor(doc) { null }.renderFlattened() to doc.name
            } finally {
                for (layer in doc.layers) { layer.bitmap.recycle(); layer.mask?.recycle() }
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

        /** Wrap source ids above this are not reserved (damaged data must not exhaust the id range). */
        private const val MAX_RESERVED_LAYER_ID = Int.MAX_VALUE.toLong()

        /** Size of the stored thumbnail (longest side [ProjectFormat.THUMB_SIZE]), like `Compositor.renderThumbnail`. */
        internal fun thumbnailSize(w: Int, h: Int): Pair<Int, Int> {
            val s = minOf(1f, ProjectFormat.THUMB_SIZE.toFloat() / max(w, h))
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }
    }
}
