package com.brushwork.paint.engine.check

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.Tile
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import kotlin.random.Random

/**
 * v1.10 (A10, design 2.10 M5): how fast this phone composites layers.
 *
 * **The composite.** 64 `TiledPixels` layers of 512 x 512 at 35 % occupancy (90 of their 256
 * tiles hold translucent painterly pixels, the rest are transparent) drawn onto a 512 x 512 target
 * through the real `Compositor.drawLayerPixels`: the median of 3 composites, in ms. The tiles are
 * the bench's own (each its own bitmap, never in the pixel ledger, recycled at the end), so the
 * bench runs on the check's thread without touching the session's engine state.
 *
 * **The Classic A/B** (`StrokeBelowCache`): a stroke frame on a 64 x 64 dirty rect with N flat
 * layers below the stroke's layer, N in {16, 32, 64}: without the cache every frame draws the N
 * layers, the stroke's layer and two above; with it a frame copies the cached composite (SRC) and
 * draws the rest, and the cache's build is shared by a 20-frame stroke. The result is the smallest
 * N at which the cache saves at least 10 % of the frame, else null (off, as in v1.9).
 *
 * Until the deadline (the composite's median of what it measured; the A/B skipped when out of
 * time, which leaves the cache off). [run] returns null when stopped. Any thread (the check's).
 */
internal object CompositeBench {
    const val LAYERS = 64
    const val SIDE = 512
    const val OCCUPANCY = 0.35
    const val REPS = 3

    /** The A/B's layer counts, smallest first. */
    val AB_LAYERS = intArrayOf(16, 32, 64)

    /** The share of a frame the cache must save. */
    const val AB_MIN_GAIN = 0.10

    /** The frames of the stroke that shares one cache build. */
    const val STROKE_FRAMES = 20

    /** Timed frames per A/B sample (one sample is their mean), and samples per median. */
    private const val FRAMES_PER_SAMPLE = 4
    private const val SAMPLES = 5

    /** Distinct tile contents; every tile is its own copy of one of them. */
    private const val VARIANTS = 8

    fun run(deadlineNanos: Long, stop: () -> Boolean): M5Result? {
        val made = ArrayList<Bitmap>()
        try {
            val variants = Array(VARIANTS) { variant(it).also(made::add) }
            val layers = layers(variants, made)
            val target = BitmapUtils.createLayerBitmap(SIDE, SIDE).also(made::add)
            val canvas = Canvas(target)
            val compositor = Compositor(Document("engine-check", "", SIDE, SIDE)) { null }
            val times = ArrayList<Double>(REPS)
            for (rep in 0 until REPS) {
                if (stop()) return null
                if (rep > 0 && System.nanoTime() > deadlineNanos) break
                target.eraseColor(Color.TRANSPARENT)
                val t0 = System.nanoTime()
                for (l in layers) compositor.drawLayerPixels(canvas, l, null)
                times += (System.nanoTime() - t0) / 1e6
            }
            val composite = median(times).toFloat()
            if (stop()) return null
            val below = if (System.nanoTime() > deadlineNanos) null else belowCacheMinLayers(variants, deadlineNanos, stop) ?: return null
            return M5Result(composite, below?.takeIf { it > 0 })
        } finally {
            for (b in made) b.recycle()
        }
    }

    /** 64 layers holding 90 tiles in all, at slots a seeded shuffle picks. */
    private fun layers(variants: Array<Bitmap>, made: MutableList<Bitmap>): List<Layer> {
        val perLayer = (SIDE / TILE_SIZE) * (SIDE / TILE_SIZE)
        val slots = (0 until LAYERS * perLayer).shuffled(Random(SEED))
        val occupied = BooleanArray(slots.size)
        for (i in 0 until (slots.size * OCCUPANCY).toInt()) occupied[slots[i]] = true
        return List(LAYERS) { li ->
            val tiles = arrayOfNulls<Tile>(perLayer)
            for (t in 0 until perLayer) {
                if (!occupied[li * perLayer + t]) continue
                val copy = variants[(li + t) % VARIANTS].copy(Bitmap.Config.ARGB_8888, false).also(made::add)
                tiles[t] = Tile(TILE_SIZE, TILE_SIZE).also { it.bitmap = copy }
            }
            Layer(li.toLong(), "", TiledPixels(SIDE, SIDE, tiles))
        }
    }

    /**
     * The A/B: the smallest N whose cached frame (plus its share of the build) saves at least
     * [AB_MIN_GAIN] of the uncached frame; 0 when none does; null when stopped.
     */
    private fun belowCacheMinLayers(variants: Array<Bitmap>, deadlineNanos: Long, stop: () -> Boolean): Int? {
        val dirty = Rect(96, 96, 160, 160)
        val tile = BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE)
        val cache = BitmapUtils.createLayerBitmap(dirty.width(), dirty.height())
        try {
            val canvas = Canvas(tile)
            val cacheCanvas = Canvas(cache)
            val over = Paint(Paint.FILTER_BITMAP_FLAG)
            val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }
            val clear = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) }
            fun layer(i: Int) = variants[i % VARIANTS]
            fun drawAbove(c: Canvas, n: Int) {
                for (i in n..n + 2) c.drawBitmap(layer(i), 0f, 0f, over)
            }
            for (n in AB_LAYERS) {
                if (stop()) return null
                if (System.nanoTime() > deadlineNanos) return 0
                val without = sample {
                    canvas.save()
                    canvas.clipRect(dirty)
                    canvas.drawRect(dirty, clear)
                    for (i in 0 until n) canvas.drawBitmap(layer(i), 0f, 0f, over)
                    drawAbove(canvas, n)
                    canvas.restore()
                }
                val t0 = System.nanoTime()
                cache.eraseColor(Color.TRANSPARENT)
                cacheCanvas.save()
                cacheCanvas.translate(-dirty.left.toFloat(), -dirty.top.toFloat())
                for (i in 0 until n) cacheCanvas.drawBitmap(layer(i), 0f, 0f, over)
                cacheCanvas.restore()
                val build = (System.nanoTime() - t0) / 1e6
                val with = sample {
                    canvas.save()
                    canvas.clipRect(dirty)
                    canvas.drawBitmap(cache, dirty.left.toFloat(), dirty.top.toFloat(), src)
                    drawAbove(canvas, n)
                    canvas.restore()
                }
                if (without - (with + build / STROKE_FRAMES) >= AB_MIN_GAIN * without) return n
            }
            return 0
        } finally {
            tile.recycle()
            cache.recycle()
        }
    }

    /** The median over [SAMPLES] of the mean of [FRAMES_PER_SAMPLE] runs of [frame], in ms. */
    private inline fun sample(frame: () -> Unit): Double {
        frame()
        val samples = ArrayList<Double>(SAMPLES)
        repeat(SAMPLES) {
            val t0 = System.nanoTime()
            repeat(FRAMES_PER_SAMPLE) { frame() }
            samples += (System.nanoTime() - t0) / 1e6 / FRAMES_PER_SAMPLE
        }
        return median(samples)
    }

    private fun median(v: List<Double>): Double {
        if (v.isEmpty()) return 0.0
        val s = v.sorted()
        return if (s.size % 2 == 1) s[s.size / 2] else (s[s.size / 2 - 1] + s[s.size / 2]) / 2
    }

    /** Tile content [k]: translucent painterly dabs (never one uniform color). */
    private fun variant(k: Int): Bitmap {
        val b = BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE)
        val c = Canvas(b)
        val rnd = Random(SEED + 1 + k)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        b.eraseColor(Color.argb(64 + rnd.nextInt(64), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256)))
        repeat(24) {
            p.color = Color.argb(60 + rnd.nextInt(150), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
            c.drawCircle(rnd.nextFloat() * TILE_SIZE, rnd.nextFloat() * TILE_SIZE, 8f + rnd.nextFloat() * 48f, p)
        }
        return b
    }

    private const val SEED = 0x110A5
}
