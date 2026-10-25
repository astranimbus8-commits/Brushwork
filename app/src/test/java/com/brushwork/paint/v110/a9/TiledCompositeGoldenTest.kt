package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.testing.EngineGolden
import com.brushwork.paint.testing.TiledGoldenKit
import com.brushwork.paint.testing.TiledGoldenKit.Stores
import org.junit.After
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner

/**
 * v1.10 A9 step 1 (design 2.5 X1-X3, 2.16): the exactness goldens. One case per blend mode (17),
 * context (9: plain, clip base, clipped, isolated folder, pass-through folder at 100 % and at
 * 60 %, a mask, an adjustment layer above and below), target (scale 1 and 0.37) and store family
 * (Flat, Tiled, Blank): 918 cases, each bit for bit.
 *
 * - **Tiled, Blank:** the document with every content and mask cut into tiles (null, solid and
 *   ordinary tiles; masks with a transparent and a solid white tile), or with every empty content
 *   and mask blank, composites exactly as the Flat copy: at scale 1 through `renderFlattened`
 *   (X1, X3), at 0.37 through a scaled `CompositeTarget` (X2: `BandAssembler`), also offset by a
 *   fractional zoomed-out tile position and cut into many bands of 37 target rows.
 * - **Flat** (the reference): at scale 1 the banded flatten (bands of 64 rows, A9 step 1) equals
 *   one pass; at 0.37 the subject's blend mode shows (the case is not vacuous) and the render is
 *   the same twice.
 */
@Category(EngineGolden::class)
@RunWith(ParameterizedRobolectricTestRunner::class)
class TiledCompositeGoldenTest(
    private val mode: LayerBlendMode,
    private val context: A9Docs.Context,
    private val scale: Float,
    private val stores: Stores,
) {
    private val made = mutableListOf<Bitmap>()
    private var variants: TiledGoldenKit.Variants? = null

    @After
    fun tearDown() {
        Compositor.flattenBand = Compositor.FLATTEN_BAND
        BandAssembler.bandRows = BandAssembler.BAND_ROWS
        made.forEach { if (!it.isRecycled) it.recycle() }
        variants?.recycle()
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private val label get() = "$mode, $context, scale $scale, $stores"

    @Test
    fun drawsAsTheFlatStores() {
        val v = TiledGoldenKit.of { A9Docs.document(context, mode) }.also { variants = it }
        when (stores) {
            Stores.FLAT -> flatReference(v)
            else -> {
                assertTrue("$label: the copy holds $stores stores", TiledGoldenKit.count(v[stores], stores) > 0)
                val expected = keep(A9Docs.render(v.flat, scale))
                val before = BandAssembler.bands
                A9Docs.assertBitEqual(label, expected, keep(A9Docs.render(v[stores], scale)))
                if (scale != 1f) {
                    if (stores == Stores.TILED) assertTrue("$label: drawn through the bands", BandAssembler.bands > before)
                    // A zoomed-out display tile: the same scale, offset by whole target pixels.
                    A9Docs.assertBitEqual("$label, offset", keep(A9Docs.render(v.flat, scale, 13, 7)), keep(A9Docs.render(v[stores], scale, 13, 7)))
                    // Many bands, each with its own offset.
                    BandAssembler.bandRows = 37
                    A9Docs.assertBitEqual("$label, bands of 37 rows", expected, keep(A9Docs.render(v[stores], scale)))
                }
            }
        }
    }

    private fun flatReference(v: TiledGoldenKit.Variants) {
        val doc = v.flat
        if (scale == 1f) {
            Compositor.flattenBand = 10_000
            val onePass = keep(A9Docs.render(doc, 1f))
            Compositor.flattenBand = 64
            A9Docs.assertBitEqual("$label: banded flatten", onePass, keep(A9Docs.render(doc, 1f)))
            return
        }
        val expected = keep(A9Docs.render(doc, scale))
        A9Docs.assertBitEqual("$label: the same twice", expected, keep(A9Docs.render(doc, scale)))
        val subject = doc.layers.first { it.name == A9Docs.SUBJECT }
        subject.visible = false
        val without = keep(A9Docs.render(doc, scale))
        subject.visible = true
        assertTrue("$label: the subject shows", A9Docs.differing(expected, without) > 0)
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0} {1} {2} {3}")
        fun cases(): List<Array<Any>> {
            val out = ArrayList<Array<Any>>()
            for (mode in LayerBlendMode.entries) for (context in A9Docs.Context.entries) for (scale in listOf(1f, 0.37f)) for (s in Stores.entries) {
                out += arrayOf(mode, context, scale, s)
            }
            return out
        }
    }
}
