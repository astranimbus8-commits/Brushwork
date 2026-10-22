package com.brushwork.paint.testing

import android.graphics.Bitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import org.junit.Assert.assertEquals
import org.junit.Assert.fail

/**
 * v1.10 F1 (design 2.5, 2.16 and 6.5; lead, frozen kit): the Flat, Tiled and Blank stores of one
 * document, for the engine goldens (`@Category(EngineGolden)`, A9's compositor and thumbnail
 * goldens, A8's tile parity, A11's banded save). A golden builds its document three times with one
 * deterministic builder (a [RandomStacks] seed, or its own code) and converts two of the copies:
 *
 * - [Stores.FLAT]: as built (every content and mask a `FlatPixels`, as Classic holds them);
 * - [Stores.TILED]: every flat content and mask cut by `TiledPixels.fromBitmap` (null tiles where
 *   transparent, shared solid tiles where uniform: what a parked layer holds, [toTiled]);
 * - [Stores.BLANK]: every empty content and empty mask a `BlankPixels`, the rest flat (what a
 *   never-painted layer or an adjustment layer holds at TILES and FULL, [toBlank]).
 *
 * The three must draw the same picture: [assertSameRender] flattens each with the real compositor
 * (`Compositor.renderFlattened`, no overrides) and compares them bit for bit with [Stores.FLAT]
 * (or within a channel tolerance). Folders are left alone (`FolderPixels`). Conversions use the
 * public setters (`Layer.pixels`, `Layer.maskPixels`), so they bump `pixelGeneration` like any
 * replacement; the flat bitmaps they replace are left to the garbage collector.
 */
object TiledGoldenKit {
    /** The store family of one copy. */
    enum class Stores { FLAT, TILED, BLANK }

    /** The three copies of one document. */
    class Variants(val flat: Document, val tiled: Document, val blank: Document) {
        /** Each copy with its family, FLAT first. */
        fun all(): List<Pair<Stores, Document>> = listOf(Stores.FLAT to flat, Stores.TILED to tiled, Stores.BLANK to blank)

        /** The copy of [s]. */
        operator fun get(s: Stores): Document = when (s) {
            Stores.FLAT -> flat
            Stores.TILED -> tiled
            Stores.BLANK -> blank
        }

        /** Recycles every layer bitmap of the three copies. */
        fun recycle() {
            for ((_, d) in all()) for (l in d.layers) l.recycleBitmaps()
        }
    }

    /** Builds the three copies with [build] (called three times; it must give the same document each time). */
    fun of(build: () -> Document): Variants = Variants(build(), toTiled(build()), toBlank(build()))

    /** The three copies of the [RandomStacks] document of [seed] under [spec]. */
    fun of(seed: Long, spec: RandomStacks.Spec = RandomStacks.Spec()): Variants = of { RandomStacks.document(seed, spec) }

    /** Cuts every flat content and mask of [doc] into tiles, in place; returns [doc]. */
    fun toTiled(doc: Document): Document {
        for (l in doc.layers) {
            if (l.isFolder) continue
            (l.pixels as? FlatPixels)?.let { l.pixels = TiledPixels.fromBitmap(it.bitmap) }
            (l.maskPixels as? FlatPixels)?.let { l.maskPixels = TiledPixels.fromBitmap(it.bitmap) }
        }
        return doc
    }

    /** Makes every empty flat content and mask of [doc] a `BlankPixels`, in place; returns [doc]. */
    fun toBlank(doc: Document): Document {
        for (l in doc.layers) {
            if (l.isFolder) continue
            (l.pixels as? FlatPixels)?.let { if (it.isEmpty()) l.pixels = BlankPixels(it.width, it.height) }
            (l.maskPixels as? FlatPixels)?.let { if (it.isEmpty()) l.maskPixels = BlankPixels(it.width, it.height) }
        }
        return doc
    }

    /** How many layers of [doc] hold a store of [s]'s kind (content or mask), to prove a conversion did something. */
    fun count(doc: Document, s: Stores): Int = doc.layers.count { l -> stores(l).any { kindOf(it) == s } }

    /** [doc] flattened by the real compositor (no overrides, no background). The caller owns the bitmap. */
    fun render(doc: Document): Bitmap = Compositor(doc) { null }.renderFlattened()

    /** Flattens every copy of [v] and compares TILED and BLANK with FLAT, within [tolerance] per channel. */
    fun assertSameRender(message: String, v: Variants, tolerance: Int = 0) {
        val expected = render(v.flat)
        try {
            for ((s, d) in v.all().drop(1)) {
                val actual = render(d)
                try {
                    assertPixels("$message ($s against FLAT)", expected, actual, tolerance)
                } finally {
                    actual.recycle()
                }
            }
        } finally {
            expected.recycle()
        }
    }

    /** [actual] equals [expected] within [tolerance] per channel; the first differing pixel in the message. */
    fun assertPixels(message: String, expected: Bitmap, actual: Bitmap, tolerance: Int = 0) {
        assertEquals("$message: width", expected.width, actual.width)
        assertEquals("$message: height", expected.height, actual.height)
        val w = expected.width
        val e = IntArray(w * expected.height).also { expected.getPixels(it, 0, w, 0, 0, w, expected.height) }
        val a = IntArray(w * actual.height).also { actual.getPixels(it, 0, w, 0, 0, w, actual.height) }
        for (i in e.indices) {
            if (e[i] == a[i]) continue
            var d = 0
            for (shift in 0..24 step 8) d = maxOf(d, kotlin.math.abs((e[i] ushr shift and 0xFF) - (a[i] ushr shift and 0xFF)))
            if (d > tolerance) fail("$message: pixel (${i % w}, ${i / w}) is ${"#%08X".format(a[i])}, expected ${"#%08X".format(e[i])}")
        }
    }

    private fun stores(l: Layer) = listOfNotNull(l.pixels, l.maskPixels)

    private fun kindOf(p: com.brushwork.paint.engine.tiles.PixelStore): Stores? = when (p) {
        is FlatPixels -> Stores.FLAT
        is TiledPixels -> Stores.TILED
        is BlankPixels -> Stores.BLANK
        else -> null
    }
}
