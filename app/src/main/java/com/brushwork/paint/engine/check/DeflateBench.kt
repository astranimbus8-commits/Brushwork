package com.brushwork.paint.engine.check

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.engine.tiles.TileOps
import kotlin.random.Random

/**
 * v1.10 (A10, design 2.10 M3): the swap codec's speed on this phone. 48 deterministic synthetic
 * 256 x 256 tiles (16 line art, 16 painterly, 16 flat), each packed and inflated through the
 * codec the swap uses (`TileOps.pack`, Deflate level 1 nowrap; `TileOps.inflate`): MB/s of raw
 * pixels each way and the ratio. Making a tile is not timed. Until the deadline, then on what it
 * measured (at least one tile). Any thread (the check's).
 */
internal object DeflateBench {
    const val TILES = 48

    /** M3's three kinds, in turn. */
    private const val KINDS = 3

    fun run(deadlineNanos: Long, stop: () -> Boolean): M3Result? {
        var raw = 0L
        var packed = 0L
        var deflateNanos = 0L
        var inflateNanos = 0L
        for (i in 0 until TILES) {
            if (stop()) return null
            if (i > 0 && System.nanoTime() > deadlineNanos) break
            val tile = synthetic(i)
            try {
                val t0 = System.nanoTime()
                val bytes = TileOps.pack(tile)
                val t1 = System.nanoTime()
                TileOps.inflate(bytes, TILE_SIZE, TILE_SIZE).recycle()
                val t2 = System.nanoTime()
                deflateNanos += t1 - t0
                inflateNanos += t2 - t1
                raw += tile.byteCount
                packed += bytes.size
            } finally {
                tile.recycle()
            }
        }
        return M3Result(mbs(raw, deflateNanos), mbs(raw, inflateNanos), if (packed > 0) raw.toFloat() / packed else 0f)
    }

    /** MB/s (10^6 bytes) of [bytes] in [nanos]. */
    private fun mbs(bytes: Long, nanos: Long): Float = if (nanos <= 0) 0f else (bytes / 1e6 / (nanos / 1e9)).toFloat()

    /** Tile [i]: line art, painterly or flat in turn, each from its own seed. */
    internal fun synthetic(i: Int): Bitmap {
        val b = BitmapUtils.createLayerBitmap(TILE_SIZE, TILE_SIZE)
        val c = Canvas(b)
        val rnd = Random(SEED + i)
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        val s = TILE_SIZE.toFloat()
        when (i % KINDS) {
            0 -> {
                // Line art: dark strokes on transparent.
                p.style = Paint.Style.STROKE
                p.color = Color.argb(255, 20, 20, 24)
                repeat(14) {
                    p.strokeWidth = 1.5f + rnd.nextFloat() * 3f
                    c.drawLine(rnd.nextFloat() * s, rnd.nextFloat() * s, rnd.nextFloat() * s, rnd.nextFloat() * s, p)
                }
            }
            1 -> {
                // Painterly: overlapping translucent dabs over a soft wash.
                b.eraseColor(Color.argb(96, 200, 180, 150))
                repeat(60) {
                    p.color = Color.argb(40 + rnd.nextInt(160), rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                    c.drawCircle(rnd.nextFloat() * s, rnd.nextFloat() * s, 6f + rnd.nextFloat() * 40f, p)
                }
            }
            else -> {
                // Flat: a few opaque color regions.
                b.eraseColor(Color.rgb(240, 236, 228))
                repeat(4) {
                    p.color = Color.rgb(rnd.nextInt(256), rnd.nextInt(256), rnd.nextInt(256))
                    val x = rnd.nextFloat() * s
                    val y = rnd.nextFloat() * s
                    c.drawRect(x, y, x + 40f + rnd.nextFloat() * 120f, y + 40f + rnd.nextFloat() * 120f, p)
                }
            }
        }
        return b
    }

    private const val SEED = 0x110A10
}
