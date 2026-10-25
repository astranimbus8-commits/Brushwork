package com.brushwork.paint.storage

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import android.util.Log
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.budget.NativePurpose
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
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
import java.nio.ByteBuffer
import kotlin.math.max
import kotlin.math.min

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

    /** v1.9 (A7b step 4, R9): the Java scratch of a banded save at most (the design allows 6 MB). */
    const val BAND_BYTES = 4 * 1024 * 1024

    /**
     * Test seam (v1.9, A7b step 4): false saves pixel files as v1.8 did, from one buffer the size
     * of the layer; `BandedSaveByteIdenticalTest` and the back-compat generator compare the two.
     */
    @Volatile
    internal var bandedSave = true

    /** Test seam: the band size of a banded save ([BAND_BYTES]); a test makes it small. */
    @Volatile
    internal var bandBytes = BAND_BYTES

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
     *
     * v1.10 (A11, design 2.9): streamed ([StreamedLoad]). A transparent entry is a [BlankPixels]
     * store (the blank fast path: nothing decoded); any other is read [BAND_ROWS] rows at a time
     * through one band, into a [FlatPixels] store at Classic and into tiles ([TiledPixels]) at
     * TILES and FULL, each bitmap and tile admitted before it is allocated. The active entry
     * (`dto.activeLayerIndex`) and its mask are FLAT at every level. No Java buffer is larger
     * than the band. On any failure every store built so far is released; an
     * [OutOfMemoryError] (also a refused admission) goes on as one ("Not enough memory to open
     * this artwork", `EditorSession`).
     */
    fun readLayers(dir: File, dto: ProjectFileDto, doc: Document) {
        val active = dto.activeLayerIndex.coerceIn(0, dto.layers.lastIndex)
        val load = StreamedLoad(dir, doc.width, doc.height, tiled = EngineConfig.level != EngineLevel.CLASSIC)
        // The array source pixels (v1.7 containers) stay bitmaps.
        val allocated = ArrayList<Bitmap>()
        try {
            for ((i, entry) in dto.layers.withIndex()) {
                doc.layers += readEntry(dir, entry, dto.formatVersion, doc, load, flat = i == active, allocated)
                doc.ensureNextLayerIdAbove(entry.id)
            }
        } catch (e: Throwable) {
            load.releaseAll()
            allocated.forEach { it.recycle() }
            throw e
        } finally {
            load.close()
        }
        if (doc.hasFolders) uniqueIds(doc)
        doc.loadWarnings += LayerTree.sanitize(doc.layers)
        // v1.7 QA: folders an older version stripped (LayerEntryDto.isStrippedFolder) open empty.
        if (dto.layers.any { it.isStrippedFolder(dto.formatVersion) } && FOLDERS_REPAIRED !in doc.loadWarnings) doc.loadWarnings += FOLDERS_REPAIRED
        sanitizeAdjustmentClipping(doc.layers)
        reserveWrapSourceIds(doc)
    }

    private fun readEntry(
        dir: File,
        entry: LayerEntryDto,
        formatVersion: Int,
        doc: Document,
        load: StreamedLoad,
        flat: Boolean,
        allocated: MutableList<Bitmap>,
    ): Layer {
        val props = sanitized(entry.props)
        entry.folderSpec(formatVersion)?.let { spec ->
            // A folder has no pixels, mask or editable data (I11); anything else in its entry is ignored.
            return Layer.newFolder(entry.id, entry.props.name, spec).apply {
                copyPropsFrom(props)
                parentId = entry.parentId
                folderOpen = entry.folderOpen
            }
        }
        val content = load.read(entry.contentFileName, "Layer \"${entry.props.name}\"", flat)
        val layer = Layer(entry.id, entry.props.name, content)
        layer.copyPropsFrom(props)
        layer.parentId = entry.parentId
        layer.textData = entry.textData
        layer.shapeData = entry.shapeData
        if (entry.hasMask) {
            layer.maskPixels = load.read(entry.maskFileName, "The mask of layer \"${entry.props.name}\"", flat, mask = true)
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
            if (spec != null && layer.hasMask) layer.maskSpec = spec
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
     *
     * v1.10 (A11, design 2.3): the new layer takes the old one's stores as they are (never through
     * `bitmap` or `mask`, so nothing is materialized).
     */
    private fun uniqueIds(doc: Document) {
        reserveWrapSourceIds(doc)
        val seen = HashSet<Long>()
        for (i in doc.layers.indices) {
            val l = doc.layers[i]
            if (seen.add(l.id)) continue
            doc.layers[i] = Layer(doc.newLayerId(), l.name, l.pixels).also { n ->
                n.copyPropsFrom(l.props())
                n.maskPixels = l.maskPixels
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

    /** v1.10 (A11, design 2.9): the rows of one band of a streamed load: one row of tiles. */
    const val BAND_ROWS = TILE_SIZE

    /** v1.10 (A11): the purpose a load's Java band is admitted for ([MemoryGovernor.admit]). */
    private const val OPEN_PURPOSE = "Open"

    /**
     * Test seam (v1.10, A11): the largest Java buffer a [readLayers] allocated since it was last
     * set to 0 (the band; `StreamedLoadNoScratchTest`).
     */
    @Volatile
    internal var loadScratchBytes = 0

    /**
     * v1.10 (A11, design 2.9): the pixel files of one [readLayers], read on its (IO) thread into
     * stores of a [w] x [h] canvas: [tiled] at TILES and FULL. One [LayerCodec.Band] serves every
     * entry; it is made at the first entry that needs decoding (a document of blank layers never
     * makes one) and admitted first, the Java bytes by [MemoryGovernor.admit] and the bitmap twin
     * as TRANSIENT. Every FLAT bitmap is admitted as LAYER (F1's rule: admitted, then not kept in
     * the ledger) and every tile reserved under TILES before it is allocated; a refusal throws
     * [OutOfMemoryError]. The stores are unpublished until [readLayers] returns, so the tiles'
     * holder counts and the releases on failure ([releaseAll]) happen on this thread with no other
     * reader (the threading rules of `Tile` speak of published tiles).
     */
    internal class StreamedLoad(private val dir: File, private val w: Int, private val h: Int, private val tiled: Boolean) {
        private var band: LayerCodec.Band? = null
        private var bandReserved = 0L
        private val built = ArrayList<PixelStore>()
        private val cols = (w + TILE_SIZE - 1) / TILE_SIZE

        /**
         * The store of the pixel file [fileName] ([what] names it in errors): [BlankPixels] for a
         * transparent one unless [flat], else FLAT at Classic or when [flat], tiles otherwise. A FLAT
         * [mask] is admitted as MASK, any other FLAT bitmap as LAYER.
         */
        fun read(fileName: String, what: String, flat: Boolean, mask: Boolean = false): PixelStore {
            val purpose = if (mask) NativePurpose.MASK else NativePurpose.LAYER
            if (!ProjectFormat.isPixelFile(fileName)) throw CorruptProjectException("$what refers to an invalid file")
            val file = File(dir, fileName)
            val store = try {
                when {
                    LayerCodec.isBlankEncoding(file, w, h) -> if (flat) FlatPixels(newFlat(purpose)) else BlankPixels(w, h)
                    flat || !tiled -> decodeFlat(file, flat, purpose)
                    else -> decodeTiles(file)
                }
            } catch (e: CorruptProjectException) {
                throw CorruptProjectException("$what is damaged or missing (${e.message})", e)
            }
            built += store
            return store
        }

        /** FLAT: the first band that is not all zero allocates the bitmap; none gives [BlankPixels] unless [flat]. */
        private fun decodeFlat(file: File, flat: Boolean, purpose: NativePurpose): PixelStore {
            val b = band()
            var bitmap: Bitmap? = null
            try {
                var canvas: Canvas? = null
                LayerCodec.readBands(file, w, h, b.bytes) { y, n ->
                    if (!b.isZero(n)) {
                        val c = canvas ?: Canvas(newFlat(purpose).also { bitmap = it }).also { canvas = it }
                        b.load()
                        b.copyRows(c, 0, w, n, 0, y)
                    }
                }
            } catch (e: Throwable) {
                bitmap?.recycle()
                throw e
            }
            val out = bitmap
            return when {
                out != null -> FlatPixels(out)
                flat -> FlatPixels(newFlat(purpose))
                else -> BlankPixels(w, h)
            }
        }

        /** TILES / FULL: each band is one row of tiles; a slice is no tile, `Tile.solid` or its own tile. None gives [BlankPixels]. */
        private fun decodeTiles(file: File): PixelStore {
            val b = band()
            val tiles = arrayOfNulls<Tile>(cols * ((h + TILE_SIZE - 1) / TILE_SIZE))
            var any = false
            try {
                LayerCodec.readBands(file, w, h, b.bytes) { y, n ->
                    val row = y / TILE_SIZE
                    var loaded = false
                    for (col in 0 until cols) {
                        val x = col * TILE_SIZE
                        val tw = min(TILE_SIZE, w - x)
                        val kind = b.kind(x, tw, n)
                        if (kind == LayerCodec.Band.ZERO) continue
                        if (!loaded) {
                            b.load()
                            loaded = true
                        }
                        val color = if (kind == LayerCodec.Band.UNIFORM) b.native.getPixel(x, 0) else 0
                        val tile = if (kind == LayerCodec.Band.UNIFORM && Color.alpha(color) == 0xFF) Tile.solid(color, tw, n) else newTile(b, x, tw, n)
                        tile.refs = 1
                        tiles[row * cols + col] = tile
                        any = true
                    }
                }
            } catch (e: Throwable) {
                for (t in tiles) if (t != null) TileOps.release(t)
                throw e
            }
            return if (any) TiledPixels(w, h, tiles) else BlankPixels(w, h)
        }

        /** A [tw] x [n] tile of the band's pixels from column [x], reserved under TILES before it is allocated. */
        private fun newTile(b: LayerCodec.Band, x: Int, tw: Int, n: Int): Tile {
            val bytes = tw.toLong() * n * 4
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.TILE)) throw OutOfMemoryError("No room for a tile while opening a ${w}x$h artwork")
            try {
                val bmp = BitmapUtils.createLayerBitmap(tw, n).apply { density = Bitmap.DENSITY_NONE }
                try {
                    b.copyRows(Canvas(bmp), x, tw, n, 0, 0)
                } catch (e: Throwable) {
                    bmp.recycle()
                    throw e
                }
                // Tile.of counts it under TILES; the reservation made room for it until then.
                return Tile.of(bmp)
            } finally {
                budget.release(bytes, NativePurpose.TILE)
            }
        }

        /** A transparent FLAT bitmap of the canvas, admitted for [purpose] first. */
        private fun newFlat(purpose: NativePurpose): Bitmap {
            val bytes = w.toLong() * h * 4
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, purpose)) throw OutOfMemoryError("No room for a ${w}x$h layer while opening")
            budget.release(bytes, purpose)
            return BitmapUtils.createLayerBitmap(w, h)
        }

        /** The band, made (and admitted) at the first decode. */
        private fun band(): LayerCodec.Band {
            band?.let { return it }
            val rows = min(BAND_ROWS, h)
            val bytes = LayerCodec.byteLength(w, rows).toLong()
            if (!MemoryGovernor.admit(bytes, OPEN_PURPOSE)) throw OutOfMemoryError("No room for a ${w}x$rows band while opening")
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw OutOfMemoryError("No room for a ${w}x$rows band while opening")
            bandReserved = bytes
            val made = LayerCodec.Band(w, rows)
            loadScratchBytes = max(loadScratchBytes, made.bytes.size)
            band = made
            return made
        }

        /** Lets go of every store [read] built (the open failed). */
        fun releaseAll() {
            for (s in built) TileOps.dispose(s)
            built.clear()
        }

        /** Recycles the band and returns its reservation. */
        fun close() {
            band?.close()
            band = null
            if (bandReserved > 0L) EngineConfig.budget.release(bandReserved, NativePurpose.TRANSIENT)
            bandReserved = 0L
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
     * written on IO; v1.9 (A7b step 4): copied natively and written in bands ([BAND_BYTES]).
     *
     * v1.10 (A11, design 2.9): written from each layer's stores, never through the facade: a
     * FLAT store as v1.9, a tiled one from a `snapshot()` and a non-resident pin taken on main
     * (its bands assembled from the tiles on IO), a blank one as the canonical blank encoding.
     * Equal pixels write equal bytes whatever the store (X6).
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
            val hasMask = layer.hasMask
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
            val maskSpecText = if (hasMask) layer.maskSpec?.let { MaskCodec.encode(it) } else null
            val adjustmentText = layer.adjustment?.let { AdjustmentCodec.encode(it) }
            val arrayText = if (arrayed) ArrayCodec.encodeSpec(array.spec) else null
            val prevVector = prev?.vectorFile
            val prevArray = prev?.arrayFile
            if (prev != null && !sizeChanged &&
                layer.contentVersion == layer.savedVersion &&
                prev.hasMask == hasMask &&
                prev.contentFileName in existing &&
                (!hasMask || prev.maskFileName in existing) &&
                (vector == null || (prevVector != null && prevVector in existing)) &&
                (!arrayed || (prevArray != null && prevArray in existing))
            ) {
                return LayerEntryDto(
                    layer.id, props.name, props, hasMask, prev.contentFileName, if (hasMask) prev.maskFileName else null,
                    textData, shapeData, if (vector != null) prevVector else null, maskSpecText, adjustmentText,
                    parentId = layer.parentId, array = arrayText, arrayFile = if (arrayed) prevArray else null,
                )
            }
            // v1.10 (A11, design 2.9): written from the stores, never through the facade.
            val content = layer.pixels
            if (!fits(content)) return null
            val maskStore = if (hasMask) layer.maskPixels ?: return null else null
            if (maskStore != null && !fits(maskStore)) return null
            val version = layer.contentVersion
            val contentName = ProjectFormat.layerFile(layer.id, revision)
            var maskName: String? = null
            // A tiled store's tiles stay where they are for the write: pinned here (main), not made resident.
            val held = if (content is TiledPixels || maskStore is TiledPixels) TileOps.pin(layer, resident = false) else null
            try {
                writeStore(File(dir, contentName), content)
                if (maskStore != null) {
                    maskName = ProjectFormat.maskFile(layer.id, revision)
                    writeStore(File(dir, maskName), maskStore)
                }
            } finally {
                if (held != null) TileOps.unpin(layer, held)
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
                if (!writeArray(File(dir, name), source, scratch ?: ByteArray(0))) return null
                arrayName = name
            }
            savedVersions += layer to version
            return LayerEntryDto(
                layer.id, props.name, props, hasMask, contentName, maskName,
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

        /**
         * Writes [bitmap] (a layer's pixels or its mask) as the pixel file [file]. v1.9 (A7b step
         * 4, R9): the pixels are taken here (main thread) as a native copy and written on IO a
         * band at a time through a scratch of at most [BAND_BYTES], byte-identical to v1.8's
         * write from a whole-layer buffer ([bandedSave] false), which needed the layer's size in
         * Java heap.
         */
        private suspend fun writePixels(file: File, bitmap: Bitmap) {
            if (!bandedSave) {
                val buffer = scratch?.takeIf { it.size >= length } ?: ByteArray(length).also { scratch = it }
                LayerCodec.copyPixels(bitmap, buffer)
                withContext(Dispatchers.IO) { LayerCodec.write(file, w, h, buffer, length) }
                return
            }
            val rows = minOf(LayerCodec.bandRows(w, bandBytes), h)
            val buffer = scratch?.takeIf { it.size >= rows * w * 4 } ?: ByteArray(rows * w * 4).also { scratch = it }
            val snapshot = LayerCodec.snapshot(bitmap)
            try {
                withContext(Dispatchers.IO) { LayerCodec.writeBands(file, snapshot, buffer) }
            } finally {
                snapshot.recycle()
            }
        }

        /**
         * v1.10 (A11, design 2.9): writes [store] (a layer's pixels or its mask) as the pixel file
         * [file], byte-identical whatever the store (X6): FLAT as v1.9 ([writePixels]); BLANK as
         * the cached canonical blank encoding; tiled from a `snapshot()` taken here (main), whose
         * tiles the IO writer assembles into bands ([writeTiles]).
         */
        private suspend fun writeStore(file: File, store: PixelStore) {
            when (store) {
                is FlatPixels -> writePixels(file, store.bitmap) // I28: one layer
                is TiledPixels -> writeTiles(file, store)
                is BlankPixels, FolderPixels -> withContext(Dispatchers.IO) { LayerCodec.writeBlank(file, w, h) }
            }
        }

        /**
         * A tiled store: main takes a `snapshot()` (copy on write: the tiles stay as they are while
         * the layer is edited meanwhile) and lets go of it after the write; the IO writer reads
         * 256-row bands of it ([TileOps.readRegion], inflating a packed or swapped tile into a copy
         * of its own) into a band bitmap, admitted as TRANSIENT first, and encodes them as
         * [LayerCodec.writeRows]. An EVICTED store (unchanged, A8) is its saved file, copied.
         */
        private suspend fun writeTiles(file: File, store: TiledPixels) {
            val ref = store.evictedTo
            if (ref != null) {
                withContext(Dispatchers.IO) {
                    val src = File(ref.projectDir, ref.file)
                    ProjectFormat.writeAtomically(file) { out -> FileInputStream(src).use { it.copyTo(out) } }
                }
                return
            }
            val rows = minOf(LayerCodec.bandRows(w, bandBytes), h)
            val buffer = scratch?.takeIf { it.size >= rows * w * 4 } ?: ByteArray(rows * w * 4).also { scratch = it }
            val snap = store.snapshot()
            try {
                withContext(Dispatchers.IO) { writeBandsOf(file, snap, rows, buffer) }
            } finally {
                TileOps.releaseStore(snap)
            }
        }

        /** IO: [snap] written band by band ([rows] rows, through a band bitmap and [buffer]). */
        private fun writeBandsOf(file: File, snap: TiledPixels, rows: Int, buffer: ByteArray) {
            val bytes = w.toLong() * rows * 4
            val budget = EngineConfig.budget
            if (!budget.admitNative(bytes, NativePurpose.TRANSIENT)) throw OutOfMemoryError("No room for a ${w}x$rows band while saving")
            try {
                val band = BitmapUtils.createLayerBitmap(w, rows).apply { density = Bitmap.DENSITY_NONE }
                try {
                    val r = Rect()
                    LayerCodec.writeRows(file, w, h, buffer) { y, n, into ->
                        r.set(0, y, w, y + n)
                        TileOps.readRegion(snap, r, band)
                        // The whole band (rows x w x 4 bytes, at most the buffer); the encoder takes its first n rows.
                        band.copyPixelsToBuffer(ByteBuffer.wrap(into))
                    }
                } finally {
                    band.recycle()
                }
            } finally {
                budget.release(bytes, NativePurpose.TRANSIENT)
            }
        }

        /** v1.9 (A7b step 4): the Java scratch this save holds (a band, or v1.8's whole layer). */
        internal val scratchBytes: Int get() = scratch?.size ?: 0

        /** Lets the (possibly large) pixel buffer be collected before the slower steps. */
        fun release() {
            scratch = null
        }

        /** v1.10 (A11): [store] is the document's size, which has not changed since the save started (a recycled FLAT bitmap does not fit). */
        private fun fits(store: PixelStore): Boolean = doc.width == w && doc.height == h && when (store) {
            is FlatPixels -> !store.bitmap.isRecycled && store.width == w && store.height == h // I28: one layer
            FolderPixels -> true
            else -> store.width == w && store.height == h
        }
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
