package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import com.brushwork.paint.engine.tiles.BandAssembler
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A9 step 1 (design 2.5 X3): the blank skip is exact for each of the 17 blend modes at
 * API 29 and above, at scale 1 and on a scaled target. An empty layer (blank, or tiled with every
 * tile null), an empty masked layer and an empty clip base composite exactly as the transparent
 * flat bitmap they stand for; and on a scaled target the skip is real: the empty unit draws no
 * band (`BandAssembler.bands` does not move), nor does a tiled layer clipped to an empty base,
 * while the same document with a painted tiled layer does. No composite materializes a store.
 */
@RunWith(RobolectricTestRunner::class)
class BlankSkipExactTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        PixelFacade.strict = false
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** Backdrop, then [subject] (the case's mode, 80 %), optionally masked, then optionally a painted layer clipped to it. */
    private fun doc(mode: LayerBlendMode, subject: Bitmap, masked: Boolean, clipped: Boolean): Document {
        val d = Document("skip", "skip", A9Docs.W, A9Docs.H)
        d.layers += Layer(d.newLayerId(), "Backdrop", keep(A9Docs.backdrop(5L)))
        d.layers += Layer(d.newLayerId(), A9Docs.SUBJECT, subject).apply {
            blendMode = mode
            opacity = 0.8f
            if (masked) {
                mask = keep(A9Docs.mask(6L))
                maskEnabled = true
            }
        }
        if (clipped) d.layers += Layer(d.newLayerId(), "Clipped", keep(A9Docs.content(7L))).apply { clipping = true }
        return d
    }

    /** [d] rendered under strict (a materialization throws) with the bands it drew. */
    private fun strictRender(d: Document, scale: Float): Pair<Bitmap, Long> {
        val before = BandAssembler.bands
        PixelFacade.strict = true
        try {
            val b = keep(A9Docs.render(d, scale))
            return b to (BandAssembler.bands - before)
        } finally {
            PixelFacade.strict = false
        }
    }

    private fun emptyTiles(): PixelStore = TiledPixels.fromBitmap(keep(A9Docs.empty()))

    @Test
    fun anEmptyUnitSkipsExactlyForEveryMode() {
        for (mode in LayerBlendMode.entries) for (masked in listOf(false, true)) for (clipped in listOf(false, true)) {
            val d = doc(mode, keep(A9Docs.empty()), masked, clipped)
            val subject = d.layers[1]
            // The clipped layer and the mask parked too: only the empty base can make them skip.
            d.layers.getOrNull(2)?.let { it.pixels = TiledPixels.fromBitmap(keep(A9Docs.content(7L))) }
            if (masked) subject.maskPixels = TiledPixels.fromBitmap(keep(A9Docs.mask(6L)))
            for (scale in listOf(1f, 0.37f)) {
                subject.bitmap = keep(A9Docs.empty())
                val expected = keep(A9Docs.render(d, scale))
                for ((name, store) in listOf("blank" to BlankPixels(A9Docs.W, A9Docs.H), "empty tiles" to emptyTiles())) {
                    subject.pixels = store
                    val (actual, bands) = strictRender(d, scale)
                    val label = "$mode, masked $masked, clipped $clipped, scale $scale, $name"
                    A9Docs.assertBitEqual(label, expected, actual)
                    if (scale != 1f) assertEquals("$label: the empty unit draws no band", 0L, bands)
                }
            }
        }
    }

    @Test
    fun aPaintedTiledUnitDoesDrawThroughTheBands() {
        for (mode in listOf(LayerBlendMode.NORMAL, LayerBlendMode.MULTIPLY, LayerBlendMode.SCREEN)) {
            val d = doc(mode, keep(A9Docs.content(8L)), masked = true, clipped = true)
            val expected = keep(A9Docs.render(d, 0.37f))
            d.layers[1].pixels = TiledPixels.fromBitmap(keep(A9Docs.content(8L)))
            d.layers[1].maskPixels = TiledPixels.fromBitmap(keep(A9Docs.mask(6L)))
            d.layers[2].pixels = TiledPixels.fromBitmap(keep(A9Docs.content(7L)))
            val (actual, bands) = strictRender(d, 0.37f)
            A9Docs.assertBitEqual("$mode painted", expected, actual)
            assertTrue("$mode: content, mask and clipped layer drawn through the bands ($bands)", bands >= 3)
        }
    }
}
