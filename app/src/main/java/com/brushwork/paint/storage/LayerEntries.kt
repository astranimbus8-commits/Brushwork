package com.brushwork.paint.storage

import android.graphics.Bitmap
import android.graphics.Rect
import android.util.Log
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentCodec
import com.brushwork.paint.masks.MaskCodec
import com.brushwork.paint.model.ArrayCodec
import com.brushwork.paint.model.ArraySourceBlob
import com.brushwork.paint.model.CorruptArrayException
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.vector.CorruptVectorException
import com.brushwork.paint.vector.VectorCodec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.io.BufferedInputStream
import java.io.File
import java.io.FileInputStream

/**
 * The layer entries and saved selections of `project.json` (v1.7 extraction X3, §4.3): how a
 * [Layer] becomes a [LayerEntryDto] and back, moved out of [ProjectRepository] so the folder,
 * array and selection fields live in one place. Entries without v1.7 data are read and written
 * exactly as v1.6 did (I13).
 *
 * Load order ([readLayers], then [readSelections], §4.3): pixels (non-folder entries only),
 * masks, the editable data (vector, mask spec, adjustment, then the folder and the array), the
 * tree repair (`LayerTree.sanitize`), adjustment clipping and the wrap-source ids, the saved
 * selections; the repository then sanitizes the symmetry.
 */
internal object LayerEntries {
    private const val TAG = "Brushwork"

    /** Wrap source ids above this are not reserved (damaged data must not exhaust the id range). */
    private const val MAX_RESERVED_LAYER_ID = Int.MAX_VALUE.toLong()

    /** Saved-selection ids (and a `nextSelectionId`) this far from 0 come from a damaged file. */
    private const val MAX_SELECTION_ID = 1L shl 52

    /** The load warning of a damaged array container (`ArrayLabels.damaged`). */
    fun arrayDamaged(name: String) = "The array of layer “$name” could not be read; its copies are kept as pixels"

    /** The load warning when folders lost their tree (`FolderLabels.REPAIRED`, as `LayerTree.sanitize`'s). */
    private const val FOLDERS_REPAIRED = "The folder structure was repaired"

    /** The load warning of a saved selection that could not be read (damaged, missing, or beyond the limits). */
    fun selectionDropped(name: String) = "The saved selection “$name” could not be read and was removed"

    // ------------------------------------------------------------------ load

    /**
     * Load steps 2–6 of §4.3: every entry of [dto] becomes a layer of [doc], bottom first; then
     * the tree is repaired, adjustment layers leave clipping groups and the ids that wrapped
     * texts name are reserved. Problems that affect one layer's data go to `doc.loadWarnings`; a
     * damaged pixel file throws [CorruptProjectException] after recycling every bitmap this
     * allocated. Blocking: call on IO.
     */
    fun readLayers(dir: File, dto: ProjectFileDto, doc: Document) {
        val scratch = ByteArray(LayerCodec.byteLength(doc.width, doc.height))
        val allocated = ArrayList<Bitmap>()
        try {
            for (entry in dto.layers) {
                doc.layers += readEntry(dir, entry, doc, scratch, allocated)
                doc.ensureNextLayerIdAbove(entry.id)
            }
        } catch (e: Throwable) {
            allocated.forEach { it.recycle() }
            throw e
        }
        if (doc.hasFolders) uniqueIds(doc)
        doc.loadWarnings += LayerTree.sanitize(doc.layers)
        // v1.7 QA: folders an older version stripped (LayerEntryDto.isStrippedFolder) open empty.
        if (dto.layers.any { it.isStrippedFolder } && FOLDERS_REPAIRED !in doc.loadWarnings) doc.loadWarnings += FOLDERS_REPAIRED
        sanitizeAdjustmentClipping(doc.layers)
        reserveWrapSourceIds(doc)
    }

    private fun readEntry(dir: File, entry: LayerEntryDto, doc: Document, scratch: ByteArray, allocated: MutableList<Bitmap>): Layer {
        val props = sanitized(entry.props)
        entry.folderSpec?.let { spec ->
            // A folder has no pixels, mask or editable data (I11); anything else in its entry is ignored.
            return Layer.newFolder(entry.id, entry.props.name, spec).apply {
                copyPropsFrom(props)
                parentId = entry.parentId
                folderOpen = entry.folderOpen
            }
        }
        val bitmap = BitmapUtils.createLayerBitmap(doc.width, doc.height).also { allocated += it }
        readPixels(dir, entry.contentFileName, bitmap, scratch, "Layer \"${entry.props.name}\"")
        val layer = Layer(entry.id, entry.props.name, bitmap)
        layer.copyPropsFrom(props)
        layer.parentId = entry.parentId
        layer.textData = entry.textData
        layer.shapeData = entry.shapeData
        if (entry.hasMask) {
            val mask = BitmapUtils.createLayerBitmap(doc.width, doc.height).also { allocated += it }
            readPixels(dir, entry.maskFileName, mask, scratch, "The mask of layer \"${entry.props.name}\"")
            layer.mask = mask
        }
        readEditableData(dir, entry, layer, doc, allocated)
        return layer
    }

    /**
     * The editable data of [entry] (v1.5: vector content, mask spec, adjustment; v1.7: the
     * array). Data that can't be read affects only its own layer: it loads without it (its
     * pixels are intact; an adjustment layer becomes a plain empty layer, an arrayed layer keeps
     * its copies as pixels) and a message goes to the document's load warnings.
     */
    private fun readEditableData(dir: File, entry: LayerEntryDto, layer: Layer, doc: Document, allocated: MutableList<Bitmap>) {
        val warnings = doc.loadWarnings
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
        if (entry.array != null) readArray(dir, entry, layer, doc, allocated)
    }

    /**
     * v1.7 (I14): the array of [entry]. The container restores the source: the layer's text,
     * shape or vector data, or the array's pixels. A damaged spec or container leaves the layer
     * a raster layer showing its copies, with [arrayDamaged].
     */
    private fun readArray(dir: File, entry: LayerEntryDto, layer: Layer, doc: Document, allocated: MutableList<Bitmap>) {
        val name = entry.props.name
        val source = try {
            val spec = ArrayCodec.decodeSpec(entry.array) ?: throw CorruptArrayException("the array spec can't be read")
            val file = entry.arrayFile
            if (file == null || !ProjectFormat.isArrayFile(file)) throw CorruptArrayException("invalid array file name")
            if (layer.isAdjustmentLayer) throw CorruptArrayException("an adjustment layer has no array")
            val blob = FileInputStream(File(dir, file)).use { ArrayCodec.readSource(BufferedInputStream(it), doc.width, doc.height) }
            spec to blob
        } catch (e: Exception) {
            Log.w(TAG, "array of layer $name unreadable", e)
            doc.loadWarnings += arrayDamaged(name)
            return
        }
        val (spec, blob) = source
        // The source lives only in the container: it replaces whatever else the entry held.
        layer.textData = null
        layer.shapeData = null
        layer.vector = null
        when (blob) {
            is ArraySourceBlob.Pixels -> {
                allocated += blob.pixels.bitmap
                layer.array = LayerArray(spec, blob.pixels)
            }
            is ArraySourceBlob.Vector -> {
                layer.vector = blob.content
                layer.array = LayerArray(spec.copy(editingSource = false))
            }
            is ArraySourceBlob.Text -> {
                layer.textData = blob.textData
                layer.array = LayerArray(spec.copy(editingSource = false))
            }
            is ArraySourceBlob.Shape -> {
                layer.shapeData = blob.shapeData
                layer.array = LayerArray(spec.copy(editingSource = false))
            }
        }
    }

    /**
     * v1.7: in a document with folders a repeated layer id makes the tree ambiguous (and only a
     * damaged file holds one): every later layer with an id already used gets a fresh id. Ids
     * that wrapped texts name are reserved first, so a fresh id is never one of them.
     */
    private fun uniqueIds(doc: Document) {
        reserveWrapSourceIds(doc)
        val seen = HashSet<Long>()
        for (i in doc.layers.indices) {
            val l = doc.layers[i]
            if (seen.add(l.id)) continue
            doc.layers[i] = Layer(doc.newLayerId(), l.name, l.bitmap).also { n ->
                n.copyPropsFrom(l.props())
                n.mask = l.mask
                n.restoreData(l.dataSnapshot())
                n.parentId = l.parentId
                n.folderOpen = l.folderOpen
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
     * Load step 7 (v1.7, item 14): the saved selections of [dto], newest first. One that is
     * damaged, missing, repeated or beyond [SavedSelection.MAX] / [SavedSelection.MAX_TOTAL_BYTES]
     * is dropped with [selectionDropped]. Ids are never reused: the next id stays above every
     * listed one, dropped ones included. Blocking: call on IO.
     */
    fun readSelections(dir: File, dto: ProjectFileDto, doc: Document) {
        val out = ArrayList<SavedSelection>()
        val ids = HashSet<Long>()
        var total = 0L
        for (e in dto.selections) {
            if (e.id in 1..MAX_SELECTION_ID) doc.ensureNextSelectionIdAbove(e.id)
            val sel = try {
                readSelection(dir, e, doc.width, doc.height)
            } catch (x: Exception) {
                Log.w(TAG, "saved selection ${e.name} unreadable", x)
                null
            } catch (x: OutOfMemoryError) {
                Log.w(TAG, "saved selection ${e.name} too large", x)
                null
            }
            if (sel == null || !ids.add(sel.id) || out.size >= SavedSelection.MAX || total + sel.bytes > SavedSelection.MAX_TOTAL_BYTES) {
                doc.loadWarnings += selectionDropped(e.name)
                continue
            }
            total += sel.bytes
            out += sel
        }
        doc.savedSelections = out
        if (dto.nextSelectionId in 2..MAX_SELECTION_ID) doc.ensureNextSelectionIdAbove(dto.nextSelectionId - 1)
    }

    private fun readSelection(dir: File, e: SelectionEntryDto, w: Int, h: Int): SavedSelection? {
        if (!ProjectFormat.isSelectionFile(e.file) || e.id !in 1..MAX_SELECTION_ID) return null
        if (e.width !in 1..w || e.height !in 1..h || e.left !in -w..w || e.top !in -h..h) return null
        val f = File(dir, e.file)
        if (!f.isFile || f.length() > SavedSelection.MAX_TOTAL_BYTES) return null
        val s = SavedSelection(e.id, e.name, Rect(e.left, e.top, e.left + e.width, e.top + e.height), f.readBytes(), e.revision)
        return s.takeIf { it.isIntact() }
    }

    // ------------------------------------------------------------------ save

    /**
     * The layer entries of one save, written incrementally (the per-layer part of
     * `ProjectRepository.save`): only layers whose `contentVersion` differs from `savedVersion`
     * (or whose files are missing) write new files, named with [revision]. [existing] lists the
     * folder's files when the save started; [previous] is the `project.json` being replaced.
     * Pixels are copied on the calling (main) thread, one layer at a time, and compressed and
     * written on IO.
     */
    class Writer(
        private val dir: File,
        private val doc: Document,
        private val revision: Long,
        private val existing: Set<String>,
        previous: ProjectFileDto?,
    ) {
        private val w = doc.width
        private val h = doc.height
        private val length = LayerCodec.byteLength(w, h)
        private val sizeChanged = previous == null || previous.width != w || previous.height != h
        private val previousEntries = previous?.layers?.associateBy { it.id }.orEmpty()
        private var scratch: ByteArray? = null

        /** The layers whose files this save wrote, with the content version written. */
        val savedVersions = ArrayList<Pair<Layer, Long>>()

        /**
         * The entry of [layer]. A folder writes no file; an arrayed layer is a plain raster entry
         * (its cache) plus its spec and source container (I14). Null when the document was
         * resized while the save ran: the save stops and the next one retries.
         */
        suspend fun entryFor(layer: Layer): LayerEntryDto? {
            val props = layer.props()
            if (layer.isFolder) {
                return LayerEntryDto(
                    layer.id, props.name, props, file = "",
                    parentId = layer.parentId, folder = layer.folder, folderOpen = layer.folderOpen,
                )
            }
            val mask = layer.mask
            val prev = previousEntries[layer.id]
            val array = layer.array
            // An array without a source (damaged in memory) is written as plain pixels.
            val source = if (array != null) ArraySourceBlob.of(layer.dataSnapshot()) else null
            val arrayed = array != null && source != null
            // An arrayed layer's text, shape or vector source lives only in its container.
            val vector = if (arrayed) null else layer.vector
            val textData = if (arrayed) null else layer.textData
            val shapeData = if (arrayed) null else layer.shapeData
            // Specs are small: stored inside project.json (a damaged one only affects its layer).
            val maskSpecText = if (mask != null) layer.maskSpec?.let { MaskCodec.encode(it) } else null
            val adjustmentText = layer.adjustment?.let { AdjustmentCodec.encode(it) }
            val arrayText = if (arrayed) ArrayCodec.encodeSpec(array.spec) else null
            val prevVector = prev?.vectorFile
            val prevArray = prev?.arrayFile
            if (prev != null && !sizeChanged &&
                layer.contentVersion == layer.savedVersion &&
                prev.hasMask == (mask != null) &&
                prev.contentFileName in existing &&
                (mask == null || prev.maskFileName in existing) &&
                (vector == null || (prevVector != null && prevVector in existing)) &&
                (!arrayed || (prevArray != null && prevArray in existing))
            ) {
                return LayerEntryDto(
                    layer.id, props.name, props, mask != null, prev.contentFileName, if (mask != null) prev.maskFileName else null,
                    textData, shapeData, if (vector != null) prevVector else null, maskSpecText, adjustmentText,
                    parentId = layer.parentId, array = arrayText, arrayFile = if (arrayed) prevArray else null,
                )
            }
            if (!fits(layer.bitmap)) return null
            val version = layer.contentVersion
            val buffer = scratch ?: ByteArray(length).also { scratch = it }
            val contentName = ProjectFormat.layerFile(layer.id, revision)
            LayerCodec.copyPixels(layer.bitmap, buffer)
            withContext(Dispatchers.IO) { LayerCodec.write(File(dir, contentName), w, h, buffer, length) }
            var maskName: String? = null
            if (mask != null) {
                if (!fits(mask)) return null
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
            var arrayName: String? = null
            if (arrayed) {
                val name = ProjectFormat.arrayFile(layer.id, revision)
                if (!writeArray(File(dir, name), source, buffer)) return null
                arrayName = name
            }
            savedVersions += layer to version
            return LayerEntryDto(
                layer.id, props.name, props, mask != null, contentName, maskName,
                textData, shapeData, vectorName, maskSpecText, adjustmentText,
                parentId = layer.parentId, array = arrayText, arrayFile = arrayName,
            )
        }

        /**
         * Writes [source] as the container [file]. A raster source is copied here (main thread)
         * into [buffer] when it fits, as layer pixels are; other sources are immutable and written
         * on IO. False when the source pixels were recycled meanwhile (the save stops).
         */
        private suspend fun writeArray(file: File, source: ArraySourceBlob, buffer: ByteArray): Boolean {
            if (source is ArraySourceBlob.Pixels) {
                val p = source.pixels
                val bmp = p.bitmap
                if (bmp.isRecycled) return false
                val len = LayerCodec.byteLength(bmp.width, bmp.height)
                val bytes = if (len <= buffer.size) buffer else ByteArray(len)
                LayerCodec.copyPixels(bmp, bytes)
                withContext(Dispatchers.IO) {
                    ProjectFormat.writeAtomically(file) { ArrayCodec.writePixels(it, bmp.width, bmp.height, p.left, p.top, bytes, len) }
                }
            } else {
                withContext(Dispatchers.IO) { ProjectFormat.writeAtomically(file) { ArrayCodec.writeSource(it, source) } }
            }
            return true
        }

        /** Lets the (possibly large) pixel buffer be collected before the slower steps. */
        fun release() {
            scratch = null
        }

        private fun fits(bitmap: Bitmap): Boolean =
            doc.width == w && doc.height == h && !bitmap.isRecycled && bitmap.width == w && bitmap.height == h
    }

    /**
     * v1.7 (item 14): the selection entries of a save, newest first. A saved selection is
     * immutable at its revision, so its file is written only when it is not on disk yet; a file
     * of that name that [previous] does not list is an orphan (another version's save dropped
     * it) and is written again.
     */
    suspend fun writeSelections(dir: File, list: List<SavedSelection>, existing: Set<String>, previous: ProjectFileDto?): List<SelectionEntryDto> {
        if (list.isEmpty()) return emptyList()
        val listed = previous?.selections?.mapTo(HashSet()) { it.file }.orEmpty()
        val out = ArrayList<SelectionEntryDto>(list.size)
        for (s in list) {
            val name = ProjectFormat.selectionFile(s.id, s.revision)
            if (name !in existing || name !in listed) {
                withContext(Dispatchers.IO) { ProjectFormat.writeAtomically(File(dir, name)) { it.write(s.packed) } }
            }
            val b = s.bounds
            out += SelectionEntryDto(s.id, s.name, name, s.revision, b.left, b.top, b.width(), b.height())
        }
        return out
    }
}
