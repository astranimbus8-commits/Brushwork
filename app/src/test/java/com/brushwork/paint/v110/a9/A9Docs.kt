package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.PorterDuffXfermode
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import org.junit.Assert.fail
import java.util.Random
import kotlin.math.ceil

/**
 * v1.10 A9 (design 2.5, 2.16): the documents of A9's compositor goldens. Every content is laid
 * out on the 256 px tile grid so a cut by `TiledPixels.fromBitmap` gives every kind of tile: an
 * ordinary tile (shapes and a patch of noise reaching the tile's edges), a `null` tile (fully
 * transparent), a solid tile (one opaque colour) and a sparse one. Masks likewise: grey shapes, a
 * transparent tile (hides), a solid white tile (DST_IN's identity) and grey noise. Same seed, same
 * pixels (`java.util.Random`, Robolectric NATIVE graphics). Documents stay at or below 512 x 512.
 */
object A9Docs {
    /** 2 x 2 tiles: (0,0) ordinary, (1,0) null, (0,1) solid, (1,1) sparse. */
    const val W = 400
    const val H = 300

    /** The contexts of `TiledCompositeGoldenTest` (design 2.16). */
    enum class Context { PLAIN, CLIP_BASE, CLIPPED, ISOLATED_FOLDER, PASS_THROUGH_100, PASS_THROUGH_60, MASK, ADJUSTMENT_ABOVE, ADJUSTMENT_BELOW }

    /** The name of the layer whose blend mode the case is about. */
    const val SUBJECT = "Subject"

    /** Opaque noise on the left two thirds, translucent noise on the rest (the adjustment's non-opaque path). */
    fun backdrop(seed: Long, w: Int = W, h: Int = H): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (x < w * 2 / 3) 255 else 40 + r.nextInt(200)
            px[y * w + x] = Color.argb(a, r.nextInt(256), r.nextInt(256), r.nextInt(256))
        }
        return BitmapUtils.createLayerBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** Content on the tile grid: shapes and noise in tile (0,0) up to its edges, (1,0) empty, (0,1) solid, (1,1) one circle. */
    fun content(seed: Long, w: Int = W, h: Int = H): Bitmap {
        val r = Random(seed)
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        val p = Paint().apply { isAntiAlias = true }
        c.save()
        c.clipRect(0, 0, minOf(256, w), minOf(256, h))
        repeat(4) {
            p.color = Color.argb(60 + r.nextInt(196), r.nextInt(256), r.nextInt(256), r.nextInt(256))
            val x = r.nextInt(256).toFloat()
            val y = r.nextInt(256).toFloat()
            val s = 20f + r.nextInt(120)
            if (it % 2 == 0) c.drawCircle(x, y, s / 2f, p) else c.drawRect(x - s / 2f, y - s / 3f, x + s / 2f, y + s / 3f, p)
        }
        c.restore()
        // Noise against the right and bottom edges of tile (0,0): next to the null and solid tiles.
        val nw = 56
        val nh = 56
        val noise = IntArray(nw * nh) { r.nextInt() }
        b.setPixels(noise, 0, nw, 256 - nw, 256 - nh, nw, nh)
        // Tile (0,1): one opaque colour (a solid tile).
        if (h > 256) {
            p.isAntiAlias = false
            p.color = Color.rgb(r.nextInt(256), r.nextInt(256), r.nextInt(256))
            c.drawRect(0f, 256f, minOf(256, w).toFloat(), h.toFloat(), p)
            p.isAntiAlias = true
        }
        // Tile (1,1): one small circle well inside it.
        if (w > 256 && h > 256) {
            p.color = Color.argb(255, r.nextInt(256), r.nextInt(256), r.nextInt(256))
            c.drawCircle(256f + (w - 256) / 2f, 256f + (h - 256) / 2f, 16f, p)
        }
        return b
    }

    /** A mask on the tile grid: (0,0) white with grey and black shapes, (1,0) transparent, (0,1) white, (1,1) grey noise. */
    fun mask(seed: Long, w: Int = W, h: Int = H): Bitmap {
        val r = Random(seed)
        val b = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(b)
        c.drawColor(Color.WHITE)
        val p = Paint().apply { isAntiAlias = true }
        c.save()
        c.clipRect(0, 0, minOf(256, w), minOf(256, h))
        repeat(3) {
            val v = if (it == 0) 0 else r.nextInt(256)
            p.color = Color.rgb(v, v, v)
            c.drawCircle(r.nextInt(256).toFloat(), r.nextInt(256).toFloat(), 20f + r.nextInt(70), p)
        }
        c.restore()
        if (w > 256) {
            c.drawRect(256f, 0f, w.toFloat(), minOf(256, h).toFloat(), Paint().apply { xfermode = PorterDuffXfermode(PorterDuff.Mode.CLEAR) })
        }
        if (w > 256 && h > 256) {
            val nw = w - 256
            val nh = h - 256
            val px = IntArray(nw * nh) { val v = r.nextInt(256); Color.rgb(v, v, v) }
            b.setPixels(px, 0, nw, 256, 256, nw, nh)
        }
        return b
    }

    /** A transparent bitmap of the document's size. */
    fun empty(w: Int = W, h: Int = H): Bitmap = BitmapUtils.createLayerBitmap(w, h)

    private val tracked = ArrayList<Document>()

    /** Registers [d] for [disposeTracked]; returns it. */
    fun track(d: Document): Document {
        tracked += d
        return d
    }

    /**
     * Gives back the tiles and flat bitmaps of [d]'s layers (`Layer.recycleBitmaps`). A7's ledger
     * (design 2.4) is shared by every test of one JVM and limited to R, so a parked stack a test
     * never releases takes room from every later test.
     */
    fun dispose(d: Document) {
        for (l in d.layers) l.recycleBitmaps()
    }

    /** [dispose] of every [track]ed document (each A9 test's `@After`). */
    fun disposeTracked() {
        tracked.forEach(::dispose)
        tracked.clear()
    }

    /** Sets `EngineConfig.level` inside a test (its setter is private; `EngineRule` restores the level after the test). */
    fun setLevel(level: EngineLevel) {
        EngineConfig::class.java.getDeclaredField("level").apply { isAccessible = true }.set(null, level)
    }

    /**
     * The document of [context] with the case's [mode] (design 2.16), built the same each time.
     * Every context holds a backdrop, the subject ([SUBJECT], [mode], 80 %), and an empty layer of
     * [mode] (blank in the Blank copy); the adjustment contexts hold a masked adjustment layer.
     */
    fun document(context: Context, mode: LayerBlendMode, seed: Long = 1L): Document {
        val d = track(Document("a9-$context-$mode", "A9 $context $mode", W, H))
        fun layer(name: String, b: Bitmap, parent: Long = Layer.ROOT_ID, blend: LayerBlendMode = LayerBlendMode.NORMAL, opacity: Float = 1f): Layer =
            Layer(d.newLayerId(), name, b).apply {
                parentId = parent
                blendMode = blend
                this.opacity = opacity
            }
        fun subject(parent: Long = Layer.ROOT_ID) = layer(SUBJECT, content(seed + 10), parent, mode, 0.8f)
        fun emptyOf(parent: Long = Layer.ROOT_ID) = layer("Empty", empty(), parent, mode)
        d.layers += layer("Backdrop", backdrop(seed))
        when (context) {
            Context.PLAIN -> {
                d.layers += subject()
                d.layers += emptyOf()
            }
            Context.CLIP_BASE -> {
                d.layers += subject()
                d.layers += layer("Clipped", content(seed + 20), opacity = 0.9f).apply { clipping = true }
                d.layers += emptyOf().apply { clipping = true }
            }
            Context.CLIPPED -> {
                d.layers += layer("Base", content(seed + 30))
                d.layers += subject().apply { clipping = true }
                d.layers += emptyOf().apply { clipping = true }
            }
            Context.ISOLATED_FOLDER, Context.PASS_THROUGH_100, Context.PASS_THROUGH_60 -> {
                val passThrough = context != Context.ISOLATED_FOLDER
                val f = Layer.newFolder(d.newLayerId(), "Folder", FolderSpec(passThrough = passThrough))
                d.layers += layer("Child", content(seed + 40), f.id, opacity = 0.9f)
                d.layers += subject(f.id)
                d.layers += emptyOf(f.id)
                when (context) {
                    Context.ISOLATED_FOLDER -> {
                        f.blendMode = mode
                        f.opacity = 0.85f
                    }
                    Context.PASS_THROUGH_60 -> f.opacity = 0.6f
                    else -> Unit
                }
                d.layers += f
            }
            Context.MASK -> {
                d.layers += subject().apply {
                    mask = mask(seed + 50)
                    maskEnabled = true
                }
                d.layers += layer("Disabled mask", content(seed + 60), blend = mode, opacity = 0.7f).apply {
                    mask = mask(seed + 70)
                    maskEnabled = false
                }
                d.layers += emptyOf().apply {
                    mask = empty()
                    maskEnabled = true
                }
            }
            Context.ADJUSTMENT_ABOVE -> {
                d.layers += subject()
                d.layers += adjustment(d, "adjust.invert", mode, 0.7f, mask(seed + 80))
                d.layers += emptyOf()
            }
            Context.ADJUSTMENT_BELOW -> {
                d.layers += adjustment(d, "adjust.grayscale", LayerBlendMode.NORMAL, 0.9f, mask(seed + 90))
                d.layers += subject()
                d.layers += emptyOf()
            }
        }
        return d
    }

    private fun adjustment(d: Document, effect: String, mode: LayerBlendMode, opacity: Float, mask: Bitmap): Layer =
        Layer(d.newLayerId(), "Adjustment", empty()).apply {
            adjustment = AdjustmentSpec(filterId = effect)
            blendMode = mode
            this.opacity = opacity
            this.mask = mask
            maskEnabled = true
        }

    /**
     * [doc] composited by the real compositor onto a new bitmap: at scale 1 [Compositor.renderFlattened];
     * else through a canvas and [CompositeTarget] of `scale(scale) · translate(-dx, -dy)` target px
     * (a thumbnail, a zoomed-out display tile when [dx], [dy] are not 0). The caller owns the bitmap.
     */
    fun render(doc: Document, scale: Float, dx: Int = 0, dy: Int = 0): Bitmap {
        if (scale == 1f && dx == 0 && dy == 0) return Compositor(doc) { null }.renderFlattened()
        val w = maxOf(1, ceil(doc.width * scale.toDouble()).toInt() - dx)
        val h = maxOf(1, ceil(doc.height * scale.toDouble()).toInt() - dy)
        val out = BitmapUtils.createLayerBitmap(w, h)
        val m = Matrix().apply {
            setScale(scale, scale)
            postTranslate(-dx.toFloat(), -dy.toFloat())
        }
        val c = Canvas(out)
        c.setMatrix(m)
        Compositor(doc) { null }.drawDocument(c, null, useOverrides = false, target = CompositeTarget(out, m))
        return out
    }

    /** The number of pixels that differ between [a] and [b] (same size). */
    fun differing(a: Bitmap, b: Bitmap): Int {
        val w = a.width
        val pa = IntArray(w * a.height).also { a.getPixels(it, 0, w, 0, 0, w, a.height) }
        val pb = IntArray(w * b.height).also { b.getPixels(it, 0, w, 0, 0, w, b.height) }
        return pa.indices.count { pa[it] != pb[it] }
    }

    /** [actual] equals [expected] bit for bit; the first differing pixel in the message. */
    fun assertBitEqual(message: String, expected: Bitmap, actual: Bitmap) {
        if (expected.width != actual.width || expected.height != actual.height) {
            fail("$message: size ${actual.width}x${actual.height}, expected ${expected.width}x${expected.height}")
        }
        val w = expected.width
        val e = IntArray(w * expected.height).also { expected.getPixels(it, 0, w, 0, 0, w, expected.height) }
        val a = IntArray(w * actual.height).also { actual.getPixels(it, 0, w, 0, 0, w, actual.height) }
        var count = 0
        var first = -1
        for (i in e.indices) if (e[i] != a[i]) { if (first < 0) first = i; count++ }
        if (first >= 0) {
            fail("$message: $count pixels differ; first (${first % w}, ${first / w}) is ${"#%08X".format(a[first])}, expected ${"#%08X".format(e[first])}")
        }
    }
}
