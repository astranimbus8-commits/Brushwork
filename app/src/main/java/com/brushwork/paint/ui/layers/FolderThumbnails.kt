package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.Stable
import androidx.compose.runtime.mutableStateMapOf
import androidx.compose.runtime.withFrameNanos
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.PixelPin
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import java.util.IdentityHashMap
import java.util.concurrent.Executor
import java.util.concurrent.RejectedExecutionException
import kotlinx.coroutines.delay

/**
 * v1.7 (item 8, design §3.8): the small composite inside each folder row's glyph, [FOLDER_THUMB_PX]
 * on its longest side ([FolderComposite.renderBlockThumbnail]: the folder's layers composited
 * isolated, through a scaled canvas, never a document-sized bitmap).
 *
 * A picture is out of date when anything inside the folder changed ([key]: the block's ids,
 * parents, pixels, masks, adjustments and properties). The window renders the out-of-date ones
 * [FOLDER_THUMB_DEBOUNCE_MS] after the last change, one per frame ([FolderThumbnailEffect]); a row
 * shows its last picture meanwhile, the glyph alone before the first. Rendering reads layer
 * pixels, so it runs on the main thread (I3: the document is never read from another thread; a
 * layer bitmap may be replaced or recycled by any edit), within the §6.3 budget of 30 ms each.
 *
 * v1.10 A9: never a read on main. When some layer of the block holds tiles that are not resident
 * (PACKED or SWAPPED, content or enabled mask), [render] reads them first on [worker] under pins
 * (`BandAssembler.readOff`), at most [readLimit] bytes, and composites on main later with the
 * copies lent to their tiles (`BandAssembler.lend`, as the waiting display tiles do, P4); the
 * picture lands then, when the key is still the one read. A block that needs more keeps its last
 * picture (or the glyph) until its layers are resident again.
 *
 * [image] is observable: a row recomposes when its picture lands.
 */
@Stable
internal class FolderThumbnails(
    private val maxSize: Int = FOLDER_THUMB_PX,
    private val worker: Executor = WaitingTiles.defaultWorker,
    private val main: Handler = Handler(Looper.getMainLooper()),
) {
    private val images = mutableStateMapOf<Long, ImageBitmap>()
    private val keys = HashMap<Long, Long>()

    /** Folders whose tiles are being read off main (their picture lands when the reads finish). */
    private val reading = HashSet<Long>()

    /** v1.10 A9: the most bytes of tiles one picture reads off main ([FOLDER_READ_BYTES]; tests change it). */
    internal var readLimit: Long = FOLDER_READ_BYTES

    /** Pictures that waited for reads off main and landed (tests). */
    internal var landedAfterReads: Int = 0
        private set

    /** Folders whose tiles are being read now (tests). */
    internal val pending: Int get() = reading.size

    /** Why the last picture did not land: a failed read or render (tests). */
    internal var lastFailure: Throwable? = null
        private set

    /** The last picture of folder [id], or null before the first. */
    fun image(id: Long): ImageBitmap? = images[id]

    /** The folders of [layers] among [listed] (ids of the listed rows) whose picture is out of date, top first. */
    fun stale(layers: List<Layer>, listed: Set<Long>): List<Layer> {
        val out = ArrayList<Layer>()
        for (i in layers.indices.reversed()) {
            val l = layers[i]
            if (l.isFolder && l.id in listed && keys[l.id] != key(layers, i)) out += l
        }
        return out
    }

    /**
     * Renders [folder]'s picture now (main thread). False when it is no longer a folder of [doc],
     * or without memory (the last picture stays), or when tiles of its block must be read first
     * (v1.10 A9: off main; the picture lands later, or not when they exceed [readLimit]).
     */
    fun render(compositor: Compositor, doc: Document, folder: Layer): Boolean {
        val i = doc.indexOf(folder)
        if (i < 0 || !folder.isFolder || folder.id in reading) return false
        val key = key(doc.layers, i)
        val need = ArrayList<Tile>()
        val pins = ArrayList<PixelPin>()
        var bytes = 0L
        val seen = IdentityHashMap<Tile, Boolean>()
        for (j in LayerTree.block(doc.layers, i)) {
            val l = doc.layers[j]
            if (l.isFolder || !l.visible || l.opacity <= 0f) continue
            val before = need.size
            bytes += collect(l.pixels, need, seen)
            if (l.maskEnabled) bytes += collect(l.maskPixels, need, seen)
            if (need.size > before) pins += l.pin()
        }
        if (need.isEmpty()) return renderNow(compositor, doc, folder, key)
        if (bytes > readLimit) {
            pins.forEach { it.close() }
            return false
        }
        reading += folder.id
        val read = Runnable {
            val copies = ArrayList<Pair<Tile, Pair<Bitmap, Boolean>>>(need.size)
            var failure: Throwable? = null
            for (t in need) {
                try {
                    copies += t to BandAssembler.readOff(t)
                } catch (e: Throwable) {
                    failure = e
                    break
                }
            }
            main.post { readDone(compositor, doc, folder, key, copies, pins, failure) }
        }
        try {
            worker.execute(read)
        } catch (e: RejectedExecutionException) {
            pins.forEach { it.close() }
            reading -= folder.id
        }
        return false
    }

    /** The reads of [folder]'s tiles finished (main): composited with the copies lent, when the key is still [key]. */
    private fun readDone(
        compositor: Compositor,
        doc: Document,
        folder: Layer,
        key: Long,
        copies: List<Pair<Tile, Pair<Bitmap, Boolean>>>,
        pins: List<PixelPin>,
        failure: Throwable?,
    ) {
        val lent = BooleanArray(copies.size)
        try {
            if (failure != null) {
                lastFailure = failure
                return
            }
            val i = doc.indexOf(folder)
            if (i < 0 || !folder.isFolder || key(doc.layers, i) != key) return
            try {
                for ((k, c) in copies.withIndex()) lent[k] = BandAssembler.lend(c.first, c.second.first)
                if (renderNow(compositor, doc, folder, key)) landedAfterReads++
            } finally {
                for ((k, c) in copies.withIndex()) if (lent[k]) BandAssembler.unlend(c.first, c.second.first)
            }
        } finally {
            // As the waiting tiles: only an own copy that was never lent is recycled (a lent one
            // may have been handed to a reader off main meanwhile; the GC frees it).
            for ((k, c) in copies.withIndex()) {
                val (b, own) = c.second
                if (own && !lent[k] && !b.isRecycled) b.recycle()
            }
            pins.forEach { it.close() }
            reading -= folder.id
        }
    }

    /** Renders [folder]'s picture on main now; its key was [key]. */
    private fun renderNow(compositor: Compositor, doc: Document, folder: Layer, key: Long): Boolean {
        val bitmap = try {
            FolderComposite.renderBlockThumbnail(compositor, doc, folder, maxSize)
        } catch (e: OutOfMemoryError) {
            lastFailure = e
            return false
        } catch (e: RuntimeException) {
            lastFailure = e
            // A layer recycled under a closing editor: keep the last picture.
            return false
        }
        keys[folder.id] = key
        images[folder.id] = bitmap.asImageBitmap()
        return true
    }

    /** Drops the pictures of folders that no longer exist. */
    fun retain(ids: Set<Long>) {
        keys.keys.retainAll(ids)
        images.keys.retainAll(ids)
    }

    /** The number of folders with a picture. */
    val size: Int get() = images.size

    /** Adds [p]'s tiles that are not resident (not yet in [seen]) to [out]; returns their bytes. */
    private fun collect(p: PixelStore?, out: MutableList<Tile>, seen: IdentityHashMap<Tile, Boolean>): Long {
        if (p !is TiledPixels) return 0L
        var bytes = 0L
        for (row in 0 until p.rows) for (col in 0 until p.cols) {
            val t = p.tileAt(col, row) ?: continue
            if (!BandAssembler.isResident(t) && seen.put(t, true) == null) {
                out += t
                bytes += t.bytes
            }
        }
        return bytes
    }

    companion object {
        /**
         * What the picture of the folder at flat index [index] of [layers] depends on, hashed: for
         * every layer of its block (the folder's own row too, for its pass-through flag; its own
         * opacity, blend and eye are not drawn in it but cost nothing to include) the id, parent,
         * pixels (`pixelGeneration`, bumped when the content or mask store is replaced, and
         * `contentVersion`), mask (`hasMask`, the switch), adjustment and properties. v1.10 A9: never
         * `identityHashCode(bitmap)`: reading a parked layer's `bitmap` would materialize it.
         */
        fun key(layers: List<Layer>, index: Int): Long {
            var h = 1125899906842597L
            fun mix(v: Long) { h = 31 * h + v }
            for (i in LayerTree.block(layers, index)) {
                val l = layers[i]
                mix(l.id)
                mix(l.parentId)
                mix(l.pixelGeneration)
                mix(l.contentVersion)
                mix(if (l.hasMask) 1 else 0)
                mix(if (l.maskEnabled) 1 else 0)
                mix(l.adjustment?.hashCode()?.toLong() ?: 0)
                mix(l.opacity.toRawBits().toLong())
                mix(l.blendMode.ordinal.toLong())
                mix((if (l.visible) 1L else 0L) or (if (l.clipping) 2L else 0L) or (if (l.folder?.passThrough == true) 4L else 0L))
            }
            return h
        }
    }
}

/**
 * Renders the out-of-date pictures of the folders among [rows] (the listed ones)
 * [FOLDER_THUMB_DEBOUNCE_MS] after the last change of the document, one per frame, so a frame
 * pays for one folder at most. Every edit, undo, structure or property change restarts the wait.
 */
@Composable
internal fun FolderThumbnailEffect(controller: EditorController, thumbs: FolderThumbnails, rows: List<LayerRowModel>) {
    val layersVersion = controller.layersVersion
    val editCount = controller.editCount
    val docVersion = controller.docVersion
    LaunchedEffect(thumbs, rows, layersVersion, editCount, docVersion) {
        if (rows.none { it.isFolder }) return@LaunchedEffect
        delay(FOLDER_THUMB_DEBOUNCE_MS)
        val doc = controller.doc
        val listed = rows.mapTo(HashSet()) { it.layer.id }
        for (folder in thumbs.stale(doc.layers, listed)) {
            withFrameNanos { }
            thumbs.render(controller.compositor, doc, folder)
        }
    }
}

/** The folder rows' picture, longest side in pixels (design §3.8). */
internal const val FOLDER_THUMB_PX = 96

/** The pictures wait this long after the last change (design §3.8, §6.3). */
internal const val FOLDER_THUMB_DEBOUNCE_MS = 300L

/**
 * v1.10 A9: the most bytes of tiles a folder's picture reads off main and holds as copies while it
 * composites (two of the waiting tiles' chunks, `WaitingTiles.CHUNK_BYTES`: 64 tiles of 256 KiB).
 */
internal const val FOLDER_READ_BYTES = 2L * WaitingTiles.CHUNK_BYTES
