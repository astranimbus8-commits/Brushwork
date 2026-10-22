package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.BlendModes
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.5, X3, finding 10; lead, frozen): the compositor's dispatch of empty stores.
 * A blank layer, or a tiled one whose every tile is null, composites exactly as a transparent flat
 * bitmap does, for the 17 blend modes, plain, masked and as a clipping base, at API 34 (skipped,
 * X3) and on the API 26-28 path, where MULTIPLY's transparent source clears what is below
 * ([BlendModes.clearsOnTransparent]: drawn, never skipped). A blank or empty tiled mask hides the
 * layer as a transparent flat mask does; a disabled one changes nothing. No composite here
 * materializes a store ([PixelFacade.strict]).
 */
@RunWith(RobolectricTestRunner::class)
class BlankDispatchTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun transparent() = keep(BitmapUtils.createLayerBitmap(W, H))

    /** Opaque noise below, then the layer under test ([mode], 70 %), optionally masked, then [clipped] above it. */
    private fun doc(mode: LayerBlendMode, masked: Boolean = false, clipped: Boolean = false): Document {
        val d = Document("blank", "blank", W, H)
        d.layers += Layer(d.newLayerId(), "Bottom", keep(F1Pixels.noise(W, H, 31L, opaque = true)))
        d.layers += Layer(d.newLayerId(), "Subject", transparent()).apply {
            blendMode = mode
            opacity = 0.7f
            if (masked) mask = keep(F1Pixels.grayNoise(W, H, 32L))
        }
        if (clipped) {
            d.layers += Layer(d.newLayerId(), "Clipped", keep(F1Pixels.noise(W, H, 33L))).apply { clipping = true }
        }
        return d
    }

    /** [d] flattened under strict, so a materialization would throw. */
    private fun strictFlatten(d: Document): Bitmap {
        PixelFacade.strict = true
        try {
            return keep(F1Pixels.flatten(d))
        } finally {
            PixelFacade.strict = false
        }
    }

    private fun emptyTiles(): PixelStore = TiledPixels.fromBitmap(transparent())

    @Test
    fun blankAndEmptyTiledContentDrawAsTransparentFlat() {
        for (sdk in listOf(34, 28)) F1Pixels.atSdk(sdk) {
            for (mode in LayerBlendMode.entries) for (masked in listOf(false, true)) for (clipped in listOf(false, true)) {
                val d = doc(mode, masked, clipped)
                val expected = keep(F1Pixels.flatten(d))
                val subject = d.layers[1]
                for ((name, store) in listOf("blank" to BlankPixels(W, H), "empty tiles" to emptyTiles())) {
                    subject.pixels = store
                    F1Pixels.assertBitEqual("API $sdk, $mode, masked $masked, clipped $clipped, $name", expected, strictFlatten(d))
                    assertFalse(subject.residency == ResidencyState.FLAT)
                }
            }
        }
    }

    @Test
    fun onTheApi28PathABlankMultiplyClearsWhatIsBelow() {
        F1Pixels.atSdk(28) {
            assertTrue(BlendModes.clearsOnTransparent(LayerBlendMode.MULTIPLY))
            assertFalse(BlendModes.clearsOnTransparent(LayerBlendMode.SCREEN))
            val d = doc(LayerBlendMode.MULTIPLY)
            val flat = keep(F1Pixels.flatten(d))
            val below = keep(F1Pixels.flatten(Document("b", "b", W, H).apply { layers += d.layers[0] }))
            assertTrue("the flat draw changes what is below", F1Pixels.maxDiff(flat, below) > 0)
            d.layers[1].pixels = BlankPixels(W, H)
            F1Pixels.assertBitEqual("blank", flat, strictFlatten(d))
        }
        F1Pixels.atSdk(34) { assertFalse(BlendModes.clearsOnTransparent(LayerBlendMode.MULTIPLY)) }
    }

    @Test
    fun blankAndEmptyTiledMasksHideAsATransparentFlatMask() {
        for (sdk in listOf(34, 28)) F1Pixels.atSdk(sdk) {
            for (mode in LayerBlendMode.entries) {
                val d = doc(mode)
                val subject = d.layers[1]
                subject.bitmap = keep(F1Pixels.noise(W, H, 34L))
                val unmasked = keep(F1Pixels.flatten(d))
                subject.mask = transparent()
                val hidden = keep(F1Pixels.flatten(d))
                for ((name, store) in listOf("blank" to BlankPixels(W, H), "empty tiles" to emptyTiles())) {
                    subject.maskEnabled = true
                    subject.maskPixels = store
                    F1Pixels.assertBitEqual("API $sdk, $mode, $name mask", hidden, strictFlatten(d))
                    subject.maskEnabled = false
                    F1Pixels.assertBitEqual("API $sdk, $mode, $name mask disabled", unmasked, strictFlatten(d))
                    assertTrue(subject.hasMask)
                    assertFalse(subject.maskPixels is com.brushwork.paint.engine.tiles.FlatPixels)
                }
            }
        }
    }

    @Test
    fun aDisabledMaskOfAnyKindChangesNothing() {
        val d = doc(LayerBlendMode.NORMAL)
        val subject = d.layers[1]
        subject.bitmap = keep(F1Pixels.noise(W, H, 35L))
        val unmasked = keep(F1Pixels.flatten(d))
        val stores = listOf<PixelStore>(
            BlankPixels(W, H),
            emptyTiles(),
            TiledPixels.fromBitmap(keep(F1Pixels.mixedMask(W, H, 36L))),
        )
        for (s in stores) {
            subject.maskPixels = s
            subject.maskEnabled = false
            F1Pixels.assertBitEqual("${s::class.simpleName} disabled", unmasked, strictFlatten(d))
        }
        assertEquals(3, stores.size)
        subject.recycleBitmaps()
    }

    private companion object {
        const val W = 300
        const val H = 200
    }
}
