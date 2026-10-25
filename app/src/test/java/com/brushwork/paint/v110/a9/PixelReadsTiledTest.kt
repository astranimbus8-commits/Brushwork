package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.PixelReads
import com.brushwork.paint.engine.tiles.TileState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Random

/**
 * v1.10 A9 step 4 (design 2.3 C-REG): `PixelReads` on parked layers. On a layer whose content is
 * TILED (parked; at FULL with every ordinary tile SWAPPED too) `sample`, `region` (aligned, across
 * tiles, partly outside the layer, into a given bitmap), `alphaRegion` and `flattenedRegion` give
 * exactly what the flat layer gives, under `PixelFacade.strict` (no materialization), with the
 * layer pinned only for the read (no pin left) and no swapped tile made resident.
 */
@RunWith(RobolectricTestRunner::class)
class PixelReadsTiledTest {
    private val swap = FakeSwapStore()

    @get:Rule
    val engine = EngineRule(
        EngineLevel.FULL,
        memory = FakeMemoryProbe(MemoryPreset.T606),
        budgets = Budgets(javaMax = 512L shl 20, nativeBudget = 64L shl 20, known = true, stepDown = 0),
        residency = ChaosResidency(),
        swap = swap,
    )

    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        PixelFacade.strict = false
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** Three layers of [W] x [H]: a backdrop, sparse content (blank and solid tiles among them), masked translucent noise. */
    private fun document(): Document {
        val d = Document("reads", "reads", W, H)
        d.layers += Layer(d.newLayerId(), "back", keep(A9Docs.backdrop(3L, W, H)))
        d.layers += Layer(d.newLayerId(), "sparse", keep(A9Docs.content(5L, W, H))).apply { blendMode = LayerBlendMode.MULTIPLY }
        val r = Random(9L)
        val px = IntArray(W * H) { Color.argb(r.nextInt(256), r.nextInt(256), r.nextInt(256), r.nextInt(256)) }
        d.layers += Layer(d.newLayerId(), "noise", keep(BitmapUtils.createLayerBitmap(W, H).apply { setPixels(px, 0, W, 0, 0, W, H) })).apply {
            opacity = 0.7f
            mask = keep(A9Docs.mask(11L, W, H))
            maskEnabled = true
        }
        return d
    }

    /** A flat copy of every layer's content (taken before parking). */
    private fun flatCopies(d: Document): List<Bitmap> = d.layers.map { keep(it.bitmap.copy(Bitmap.Config.ARGB_8888, false)) }

    /** Parks every layer; with [swapOut] also swaps every ordinary tile of content and mask out. */
    private fun park(d: Document, swapOut: Boolean) {
        val chaos = ChaosResidency()
        for (l in d.layers) {
            assertTrue(chaos.park(l))
            if (!swapOut) continue
            for (store in listOf(l.pixels, l.maskPixels)) {
                val t = store as? TiledPixels ?: continue
                for (row in 0 until t.rows) for (col in 0 until t.cols) {
                    val tile = t.tileAt(col, row) ?: continue
                    if (tile.solidColor == null) swap.swapOut(tile)
                }
            }
        }
    }

    private fun crop(src: Bitmap, r: Rect): Bitmap {
        val out = keep(BitmapUtils.createLayerBitmap(r.width(), r.height()))
        Canvas(out).drawBitmap(src, -r.left.toFloat(), -r.top.toFloat(), null)
        return out
    }

    private val rects = listOf(
        Rect(0, 0, W, H),
        Rect(256, 0, 512, 256),
        Rect(100, 37, 400, 333),
        Rect(-30, -20, 70, 90),
        Rect(W - 50, H - 40, W + 60, H + 10),
        Rect(255, 255, 258, 258),
    )

    private fun assertReads(d: Document, flat: List<Bitmap>, what: String) {
        val mats = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            val r = Random(21L)
            for ((i, l) in d.layers.withIndex()) {
                assertTrue("$what: layer $i is parked", l.pixels is TiledPixels)
                repeat(200) {
                    val x = r.nextInt(W + 20) - 10
                    val y = r.nextInt(H + 20) - 10
                    val expected = if (x in 0 until W && y in 0 until H) flat[i].getPixel(x, y) else 0
                    assertEquals("$what: layer $i sample ($x, $y)", expected, PixelReads.sample(l, x, y))
                }
                for (rect in rects) {
                    val got = keep(PixelReads.region(l, rect))
                    A9Docs.assertBitEqual("$what: layer $i region $rect", crop(flat[i], rect), got)
                    val into = keep(BitmapUtils.createLayerBitmap(rect.width(), rect.height()).apply { eraseColor(Color.RED) })
                    PixelReads.region(l, rect, into)
                    A9Docs.assertBitEqual("$what: layer $i region $rect into a given bitmap", crop(flat[i], rect), into)
                    val alpha = ByteArray(rect.width() * rect.height())
                    PixelReads.alphaRegion(l, rect, alpha)
                    val ref = crop(flat[i], rect)
                    val row = IntArray(rect.width())
                    for (y in 0 until rect.height()) {
                        ref.getPixels(row, 0, rect.width(), 0, y, rect.width(), 1)
                        for (x in 0 until rect.width()) {
                            assertEquals("$what: layer $i alpha ($x, $y) of $rect", row[x] ushr 24, alpha[y * rect.width() + x].toInt() and 0xFF)
                        }
                    }
                }
                assertEquals("$what: no pin is left on layer $i", 0, l.pins)
            }
        } finally {
            PixelFacade.strict = false
        }
        assertEquals("$what: nothing was materialized", mats, PixelFacade.materializations)
    }

    @Test
    fun readsOfParkedLayersEqualTheFlatReads() {
        val d = document()
        val flat = flatCopies(d)
        val twin = document()
        val flattened = rects.map { keep(PixelReads.flattenedRegion(twin, it)) }
        park(d, swapOut = false)
        assertReads(d, flat, "parked")
        PixelFacade.strict = true
        try {
            for ((k, rect) in rects.withIndex()) {
                A9Docs.assertBitEqual("flattenedRegion $rect", flattened[k], keep(PixelReads.flattenedRegion(d, rect)))
            }
        } finally {
            PixelFacade.strict = false
        }
    }

    @Test
    fun readsOfSwappedTilesNeverMakeThemResident() {
        val d = document()
        val flat = flatCopies(d)
        park(d, swapOut = true)
        val readsBefore = swap.reads
        assertReads(d, flat, "swapped")
        assertTrue("the swapped tiles were read from the swap store", swap.reads > readsBefore)
        for (l in d.layers) for (store in listOf(l.pixels, l.maskPixels)) {
            val t = store as? TiledPixels ?: continue
            for (row in 0 until t.rows) for (col in 0 until t.cols) {
                val tile = t.tileAt(col, row) ?: continue
                if (tile.solidColor == null) assertEquals("a read never swaps a tile in", TileState.SWAPPED, tile.state)
                assertEquals("no tile pin is left", 0, tile.pins)
            }
        }
    }

    private companion object {
        const val W = 512
        const val H = 384
    }
}
