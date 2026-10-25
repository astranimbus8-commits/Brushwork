package com.brushwork.paint.ui.layers

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.RectF
import android.os.Handler
import android.os.Looper
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.graphics.ImageBitmap
import androidx.compose.ui.graphics.asImageBitmap
import com.brushwork.paint.engine.live.WaitingTiles
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import java.util.concurrent.Executor
import kotlin.math.max
import kotlin.math.roundToInt

/**
 * Downscaled layer/mask previews for the layers panel. Main thread only.
 *
 * v1.10 A9 (design 4.4 A9 step 4, lazy keys): cached by (layer id, `contentVersion`,
 * `pixelGeneration`); the generation goes up when the content or the mask store is replaced
 * (flips and merges), never on park, unpark, evict or swap (J5). A FLAT store's bitmap is
 * downscaled at once; a blank store shows its (exact, transparent) placeholder. The panel never
 * materializes a layer.
 *
 * v1.10 A9 step 6 (design 2.13 "The panel", X2): a TILED store (a parked layer or mask) with no
 * picture of its key shows the picture it had, else a transparent placeholder of the thumbnail's
 * size, and its picture is **requested**: made from the tiles, one request at a time on the main
 * looper. Resident tiles are drawn on main; the others are read off main ([worker], the waiting
 * tiles' reader: `BandAssembler.readOff`, never made resident) under a `Layer.pin()` and drawn
 * there into the request's own bitmap. The picture lands when the key is still the same, and
 * [content] / [mask] are observable, so the rows recompose. An EVICTED store (no tiles) keeps the
 * placeholder. Pictures made from tiles hold at most [TILE_PICTURES_BYTES]; the oldest go first
 * (requested again when shown).
 *
 * Thumbnails are never recycled explicitly: Compose may still be drawing the previous one, and at
 * ~100 px they are cheap enough to leave to the GC.
 */
class LayerThumbnails internal constructor(
    private val maxSize: Int,
    private val worker: Executor,
    private val main: Handler,
) {
    constructor(maxSize: Int) : this(maxSize, WaitingTiles.defaultWorker, Handler(Looper.getMainLooper()))

    private class Entry(val version: Long, val generation: Long, val image: ImageBitmap, val exact: Boolean, val tileBytes: Long = 0L)

    private val content = HashMap<Long, Entry>()
    private val masks = HashMap<Long, Entry>()

    /** Previews made from pixels so far (not from the cache, not placeholders); for tests. */
    var made: Int = 0
        private set

    /** Previews made from tiles so far (step 6); for tests. */
    var madeFromTiles: Int = 0
        private set

    /** Bumped when a picture made from tiles lands: [content] and [mask] read it, so rows recompose. */
    private var landed by mutableIntStateOf(0)

    /** One request: the [store] of [layer] (its mask when [ofMask]) at its key. */
    private class Request(val layer: Layer, val ofMask: Boolean, val version: Long, val generation: Long, val store: TiledPixels) {
        /** Its own paint: the request draws on main and on the worker. */
        val paint by lazy { Paint(Paint.FILTER_BITMAP_FLAG) }
        val want = Want(layer.id, ofMask, version, generation)
    }

    private data class Want(val id: Long, val mask: Boolean, val version: Long, val generation: Long)

    private class Cell(val tile: Tile, val col: Int, val row: Int)

    private val queue = ArrayDeque<Request>()
    private val wanted = HashSet<Want>()

    /** Keys whose read failed (a swap I/O error): not requested again until the key changes. */
    private val failed = HashSet<Want>()
    private var busy = false
    private val pump = Runnable { pumpOne() }

    /** Bytes of the pictures made from tiles, and their order (oldest first) for [TILE_PICTURES_BYTES]. */
    private var tileBytes = 0L
    private val tileOrder = ArrayDeque<Pair<Boolean, Long>>()

    /** Requests queued or in flight (tests). */
    internal val pending: Int get() = wanted.size

    /** Preview of the layer's pixels. */
    fun content(layer: Layer): ImageBitmap {
        observe()
        return lookup(content, false, layer, layer.pixels)
    }

    /** Preview of the layer's mask, or null when it has none. */
    fun mask(layer: Layer): ImageBitmap? {
        observe()
        val m = layer.maskPixels ?: run { remove(masks, layer.id); return null }
        return lookup(masks, true, layer, m)
    }

    /** Drops entries of layers that no longer exist. */
    fun retain(ids: Set<Long>) {
        for (cache in listOf(content, masks)) {
            val iter = cache.entries.iterator()
            while (iter.hasNext()) {
                val e = iter.next()
                if (e.key !in ids) {
                    tileBytes -= e.value.tileBytes
                    iter.remove()
                }
            }
        }
        failed.retainAll { it.id in ids }
    }

    val size: Int get() = content.size + masks.size

    @Suppress("UNUSED_VARIABLE")
    private fun observe() {
        val revision = landed
    }

    private fun lookup(cache: HashMap<Long, Entry>, mask: Boolean, layer: Layer, store: PixelStore): ImageBitmap {
        val version = layer.contentVersion
        val generation = layer.pixelGeneration
        val e = cache[layer.id]
        val current = e != null && e.version == version && e.generation == generation
        if (current && e!!.exact) return e.image
        val source = BandAssembler.flatBitmap(store)
        if (source == null) {
            // Not flat: never materialize. A tiled store's picture is requested (step 6); the
            // picture of the same key stays meanwhile, else a placeholder (exact for a blank store).
            val blank = store is BlankPixels || (store is TiledPixels && store.isEmpty())
            if (store is TiledPixels && !blank) request(Request(layer, mask, version, generation, store))
            if (current) return e!!.image
            val placeholder = placeholder(store)
            put(cache, layer.id, Entry(version, generation, placeholder, exact = blank))
            return placeholder
        }
        val image = downscale(source, maxSize).asImageBitmap()
        made++
        put(cache, layer.id, Entry(version, generation, image, exact = true))
        return image
    }

    private fun put(cache: HashMap<Long, Entry>, id: Long, e: Entry) {
        cache.put(id, e)?.let { tileBytes -= it.tileBytes }
    }

    private fun remove(cache: HashMap<Long, Entry>, id: Long) {
        cache.remove(id)?.let { tileBytes -= it.tileBytes }
    }

    /** A transparent picture of the thumbnail's size for [store]. */
    private fun placeholder(store: PixelStore): ImageBitmap {
        val (w, h) = fitSize(store.width, store.height, maxSize)
        return Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).asImageBitmap()
    }

    // ------------------------------------------------------------------ pictures from tiles (step 6)

    private fun request(r: Request) {
        if (r.store.evictedTo != null || r.want in wanted || r.want in failed) return
        wanted += r.want
        queue.addLast(r)
        schedule()
    }

    private fun schedule() {
        if (busy || queue.isEmpty()) return
        busy = true
        main.post(pump)
    }

    /** [r] ended (landed, dropped or failed): the next request starts on a later turn of the looper. */
    private fun done(r: Request) {
        wanted -= r.want
        busy = false
        schedule()
    }

    /** True when [r] still describes its layer's store and key. */
    private fun isCurrent(r: Request): Boolean {
        val l = r.layer
        val store = if (r.ofMask) l.maskPixels else l.pixels
        return store === r.store && l.contentVersion == r.version && l.pixelGeneration == r.generation && r.store.evictedTo == null
    }

    /** Starts the oldest request: resident tiles drawn now, the others read and drawn by [worker]. */
    private fun pumpOne() {
        val r = queue.removeFirstOrNull()
        if (r == null) {
            busy = false
            return
        }
        val have = (if (r.ofMask) masks else content)[r.layer.id]
        if (!isCurrent(r) || (have != null && have.exact && have.version == r.version && have.generation == r.generation)) return done(r)
        val store = r.store
        val (w, h) = fitSize(store.width, store.height, maxSize)
        // Big reductions go through 3x the size, as [downscale] does (thin strokes stay).
        val big = store.width > w * 8 && store.height > h * 8
        val iw = if (big) w * 3 else w
        val ih = if (big) h * 3 else h
        val inter = try {
            Bitmap.createBitmap(iw, ih, Bitmap.Config.ARGB_8888)
        } catch (e: OutOfMemoryError) {
            return done(r)
        }
        val canvas = Canvas(inter)
        val sx = iw.toFloat() / store.width
        val sy = ih.toFloat() / store.height
        val need = ArrayList<Cell>()
        for (row in 0 until store.rows) for (col in 0 until store.cols) {
            val t = store.tileAt(col, row) ?: continue
            if (BandAssembler.isResident(t)) drawTile(canvas, t, BandAssembler.readOff(t).first, col, row, sx, sy, r.paint) else need += Cell(t, col, row)
        }
        if (need.isEmpty()) {
            land(r, inter, w, h, big)
            return done(r)
        }
        val pin = r.layer.pin()
        worker.execute {
            val held = ArrayList<Cell>()
            var failure: Throwable? = null
            for (cell in need) {
                try {
                    val (b, own) = BandAssembler.readOff(cell.tile)
                    if (own) {
                        drawTile(canvas, cell.tile, b, cell.col, cell.row, sx, sy, r.paint)
                        b.recycle()
                    } else {
                        // The tile's own bitmap (resident meanwhile): drawn on main.
                        held += cell
                    }
                } catch (e: Throwable) {
                    failure = e
                    break
                }
            }
            main.post { readDone(r, pin, inter, canvas, held, failure, w, h, big, sx, sy) }
        }
    }

    private fun readDone(
        r: Request,
        pin: AutoCloseable,
        inter: Bitmap,
        canvas: Canvas,
        held: List<Cell>,
        failure: Throwable?,
        w: Int,
        h: Int,
        big: Boolean,
        sx: Float,
        sy: Float,
    ) {
        try {
            if (failure != null) {
                // A swap read failed: the placeholder stays until the key changes.
                failed += r.want
                inter.recycle()
                return
            }
            if (!isCurrent(r)) {
                inter.recycle()
                return
            }
            for (cell in held) {
                // Resident at the read; read again (a copy, recycled) only if it is not any more.
                val (b, own) = BandAssembler.readOff(cell.tile)
                drawTile(canvas, cell.tile, b, cell.col, cell.row, sx, sy, r.paint)
                if (own) b.recycle()
            }
            land(r, inter, w, h, big)
        } finally {
            pin.close()
            done(r)
        }
    }

    /** Draws tile [t] at ([col], [row]), whose pixels are [b] (a shared solid bitmap may be of another size), at scale ([sx], [sy]). */
    private fun drawTile(c: Canvas, t: Tile, b: Bitmap, col: Int, row: Int, sx: Float, sy: Float, paint: Paint) {
        val x = col * TILE_SIZE
        val y = row * TILE_SIZE
        c.drawBitmap(b, null, RectF(x * sx, y * sy, (x + t.w) * sx, (y + t.h) * sy), paint)
    }

    /** The finished picture of [r] goes into the cache (within [TILE_PICTURES_BYTES]) and the rows recompose. */
    private fun land(r: Request, inter: Bitmap, w: Int, h: Int, big: Boolean) {
        val picture = if (big) Bitmap.createScaledBitmap(inter, w, h, true).also { if (it !== inter) inter.recycle() } else inter
        val bytes = picture.allocationByteCount.toLong()
        put(if (r.ofMask) masks else content, r.layer.id, Entry(r.version, r.generation, picture.asImageBitmap(), exact = true, tileBytes = bytes))
        tileBytes += bytes
        tileOrder.addLast(r.ofMask to r.layer.id)
        while (tileBytes > TILE_PICTURES_BYTES) {
            val (mask, id) = tileOrder.removeFirstOrNull() ?: break
            val cache = if (mask) masks else content
            if ((cache[id]?.tileBytes ?: 0L) > 0L) remove(cache, id)
        }
        madeFromTiles++
        landed++
    }

    companion object {
        /** Design 2.13: the pictures made from tiles hold at most 16 MiB. */
        const val TILE_PICTURES_BYTES: Long = 16L shl 20

        /** Size of the thumbnail of a [w] x [h] image fitted into [maxSize] (never upscaled). */
        fun fitSize(w: Int, h: Int, maxSize: Int): Pair<Int, Int> {
            val s = minOf(1f, maxSize.toFloat() / max(1, max(w, h)))
            return max(1, (w * s).roundToInt()) to max(1, (h * s).roundToInt())
        }

        /**
         * A new bitmap of [src] fitted into [maxSize] x [maxSize]. Big reductions go through one
         * intermediate size so that a single bilinear pass doesn't skip most source pixels
         * (thin strokes would vanish). Never returns [src] itself.
         */
        fun downscale(src: Bitmap, maxSize: Int): Bitmap {
            val (w, h) = fitSize(src.width, src.height, maxSize)
            var from = src
            if (src.width > w * 8 && src.height > h * 8) {
                from = Bitmap.createScaledBitmap(src, w * 3, h * 3, true)
            }
            val out = Bitmap.createScaledBitmap(from, w, h, true)
            if (from !== src && from !== out) from.recycle()
            return if (out === src) src.copy(Bitmap.Config.ARGB_8888, false) else out
        }
    }
}
