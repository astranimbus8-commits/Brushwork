package com.brushwork.paint.v110.a11

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.LayerEntryDto
import com.brushwork.paint.storage.ProjectFileDto
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import java.io.File
import java.nio.ByteBuffer
import java.util.Random

/**
 * v1.10 (A11): fixtures of the streamed load and save tests: a fresh repository, documents with
 * painted, sparse, solid and blank layers (valid premultiplied pixels), projects written straight
 * from rows (no bitmap of the canvas, for the 4000 x 5000 and 6000² files), and the raw bytes of
 * any store, read without materializing it.
 */
internal object A11Kit {
    val context: Context get() = ApplicationProvider.getApplicationContext()

    /** A repository over an empty projects folder. */
    fun freshRepo(): ProjectRepository {
        File(context.filesDir, "projects").deleteRecursively()
        return ProjectRepository(context)
    }

    /** A repository over the projects folder as it is. */
    fun repo(): ProjectRepository = ProjectRepository(context)

    fun dirOf(id: String): File = File(context.filesDir, "projects/$id")

    /** The raw bytes of [b] as pixel files hold them. */
    fun raw(b: Bitmap): ByteArray = ByteArray(b.byteCount).also { b.copyPixelsToBuffer(ByteBuffer.wrap(it)) }

    /** The raw bytes of [p] (a [w] x [h] content or mask store), read exactly without materializing it. */
    fun bytesOf(p: PixelStore, w: Int, h: Int): ByteArray = when (p) {
        is FlatPixels -> raw(p.bitmap)
        is BlankPixels, FolderPixels -> ByteArray(w * h * 4)
        is TiledPixels -> {
            val out = BitmapUtils.createLayerBitmap(w, h)
            try {
                TileOps.readRegion(p, Rect(0, 0, w, h), out)
                raw(out)
            } finally {
                out.recycle()
            }
        }
    }

    private val src = Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.SRC) }

    /** Random colors (premultiplied by Skia, so valid) in [r] of [b]. */
    fun noise(b: Bitmap, r: Rect, seed: Long) {
        val rnd = Random(seed)
        val px = IntArray(r.width() * r.height()) { rnd.nextInt() }
        b.setPixels(px, 0, r.width(), r.left, r.top, r.width(), r.height())
    }

    /** [color] (unpremultiplied ARGB) over [r] of [b], replacing what is there. */
    fun fill(b: Bitmap, r: Rect, color: Int) {
        Canvas(b).drawRect(r, Paint(src).apply { this.color = color })
    }

    /**
     * The index of the alpha byte in a pixel as pixel files hold it (Skia's N32 order), found by
     * a probe instead of assumed.
     */
    val alphaIndex: Int by lazy {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        b.eraseColor(0x80FF0000.toInt())
        val bytes = raw(b)
        b.recycle()
        bytes.indexOfFirst { it == 0x80.toByte() }
    }

    /** The 4 stored bytes of an opaque [rgb] color, in the order pixel files hold them. */
    fun opaqueBytes(rgb: Int): ByteArray {
        val b = Bitmap.createBitmap(1, 1, Bitmap.Config.ARGB_8888)
        b.eraseColor(rgb or (0xFF shl 24))
        return raw(b).also { b.recycle() }
    }

    /**
     * Rows of a "painted" layer: [count] seeded opaque rectangles covering about [coverage] of a
     * [w] x [h] canvas (as the check artworks' strokes, design 7), the rest transparent.
     */
    class PaintedRows(private val w: Int, private val h: Int, seed: Long, coverage: Double = 0.35, count: Int = 24) {
        private val rects = ArrayList<IntArray>()
        private val colors = ArrayList<ByteArray>()

        init {
            val rnd = Random(seed)
            val area = coverage * w * h / count
            repeat(count) {
                val rw = (Math.sqrt(area) * (0.5 + rnd.nextDouble())).toInt().coerceIn(1, w)
                val rh = (area / rw).toInt().coerceIn(1, h)
                val x = rnd.nextInt(w - rw + 1)
                val y = rnd.nextInt(h - rh + 1)
                rects += intArrayOf(x, y, x + rw, y + rh)
                colors += opaqueBytes(rnd.nextInt() and 0xFFFFFF)
            }
        }

        /** Fills rows [y] until [y] + [n] into [into] (`LayerCodec.writeRows`' callback). */
        fun fill(y: Int, n: Int, into: ByteArray) {
            java.util.Arrays.fill(into, 0, n * w * 4, 0)
            for (k in rects.indices) {
                val r = rects[k]
                val c = colors[k]
                for (row in maxOf(y, r[1]) until minOf(y + n, r[3])) {
                    var o = ((row - y) * w + r[0]) * 4
                    for (x in r[0] until r[2]) {
                        into[o] = c[0]; into[o + 1] = c[1]; into[o + 2] = c[2]; into[o + 3] = c[3]
                        o += 4
                    }
                }
            }
        }
    }

    /** A pixel file of [w] x [h] written from rows ([fill] as `LayerCodec.writeRows` takes it). */
    fun writeRows(file: File, w: Int, h: Int, fill: (Int, Int, ByteArray) -> Unit) {
        val rows = minOf(h, maxOf(1, (1 shl 20) / (w * 4)))
        LayerCodec.writeRows(file, w, h, ByteArray(w * rows * 4), fill)
    }

    /**
     * A project [id] of [w] x [h] whose layer i (bottom first) is written by `files[i]` into
     * `layer_<i+1>_r1.bin` (null: an empty folder), with [active] as the active index, as format
     * [formatVersion] (default 1, or 3 with a folder). Returns its
     * folder.
     */
    fun writeProject(id: String, w: Int, h: Int, files: List<((File) -> Unit)?>, active: Int = 0, formatVersion: Int? = null): File {
        val dir = dirOf(id)
        dir.mkdirs()
        val entries = files.mapIndexed { i, write ->
            val layerId = i + 1L
            if (write == null) {
                LayerEntryDto(
                    layerId, "Folder $layerId", ProjectFormat.defaultProps("Folder $layerId"), file = "",
                    folder = com.brushwork.paint.model.FolderSpec(),
                )
            } else {
                val name = ProjectFormat.layerFile(layerId, 1)
                write(File(dir, name))
                LayerEntryDto(layerId, "Layer $layerId", ProjectFormat.defaultProps("Layer $layerId"), file = name)
            }
        }
        val version = formatVersion ?: if (files.any { it == null }) 3 else 1
        ProjectFormat.write(dir, ProjectFileDto(formatVersion = version, id = id, name = id, width = w, height = h, activeLayerIndex = active, layers = entries, revision = 1))
        return dir
    }

    /** Lets go of every store of [doc] (main thread). */
    fun dispose(doc: Document) {
        for (l in doc.layers) l.recycleBitmaps()
    }
}
