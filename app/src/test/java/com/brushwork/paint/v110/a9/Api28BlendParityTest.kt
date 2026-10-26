package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import android.os.Build
import com.brushwork.paint.engine.BlendModes
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.util.ReflectionHelpers

/**
 * v1.10 A9 step 1 (design 2.5 "API 26-28", finding 55): on the API 26-28 path, where MULTIPLY is
 * `PorterDuff.Mode.MULTIPLY` and a transparent source clears what is below
 * (`BlendModes.clearsOnTransparent`), NORMAL and MULTIPLY layers composite at Classic, TILES and
 * FULL exactly as v1.9's flat draw: a parked layer with a `null` tile (cut by
 * `TiledPixels.fromBitmap`), a blank layer, and a layer parked by the residency's own park
 * (`ChaosResidency.park`), at scale 1 and on a scaled target (X2: a `null` tile is transparent in
 * its band, so MULTIPLY clears there too).
 *
 * Deviation from the design's `@Config(sdk = [28])`: only the SDK 34 android-all jar is available
 * offline, so the test runs in the SDK 34 sandbox with `Build.VERSION.SDK_INT` set to 28, which is
 * what `BlendModes` reads (the same technique as the lead's `BlankDispatchTest`).
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class Api28BlendParityTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level, memory = FakeMemoryProbe(MemoryPreset.T606), swap = if (level == EngineLevel.FULL) FakeSwapStore() else null)

    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        A9Docs.disposeTracked()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun <T> atSdk28(block: () -> T): T {
        val saved = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", 28)
        try {
            return block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", saved)
        }
    }

    /** Backdrop, then the subject ([mode], 80 %) holding [content]. */
    private fun doc(mode: LayerBlendMode, content: Bitmap): Document {
        val d = A9Docs.track(Document("api28", "api28", A9Docs.W, A9Docs.H))
        d.layers += Layer(d.newLayerId(), "Backdrop", keep(A9Docs.backdrop(9L)))
        d.layers += Layer(d.newLayerId(), A9Docs.SUBJECT, content).apply {
            blendMode = mode
            opacity = 0.8f
        }
        return d
    }

    @Test
    fun nullTilesBlankAndParkedLayersMatchTheFlatDraw() = atSdk28 {
        assertEquals(level, EngineConfig.level)
        assertTrue(BlendModes.clearsOnTransparent(LayerBlendMode.MULTIPLY))
        assertFalse(BlendModes.clearsOnTransparent(LayerBlendMode.NORMAL))
        for (mode in listOf(LayerBlendMode.NORMAL, LayerBlendMode.MULTIPLY)) for (scale in listOf(1f, 0.37f)) {
            val label = "$level, $mode, scale $scale"
            // A null tile: the content's tile (1,0) is transparent.
            val painted = doc(mode, keep(A9Docs.content(10L)))
            val flat = keep(A9Docs.render(painted, scale))
            painted.layers[1].pixels = TiledPixels.fromBitmap(keep(A9Docs.content(10L)))
            assertTrue((painted.layers[1].pixels as TiledPixels).tileAt(1, 0) == null)
            A9Docs.assertBitEqual("$label, null tile", flat, keep(A9Docs.render(painted, scale)))
            // Parked by the residency's park.
            val parked = doc(mode, keep(A9Docs.content(10L)))
            assertTrue(ChaosResidency().park(parked.layers[1]))
            assertTrue(parked.layers[1].pixels is TiledPixels)
            A9Docs.assertBitEqual("$label, parked", flat, keep(A9Docs.render(parked, scale)))
            // A blank layer against a transparent flat one.
            val transparent = doc(mode, keep(A9Docs.empty()))
            val flatEmpty = keep(A9Docs.render(transparent, scale))
            transparent.layers[1].pixels = BlankPixels(A9Docs.W, A9Docs.H)
            A9Docs.assertBitEqual("$label, blank", flatEmpty, keep(A9Docs.render(transparent, scale)))
            if (mode == LayerBlendMode.MULTIPLY) {
                // v1.9's look there: the transparent MULTIPLY layer clears what is below.
                val below = Document("below", "below", A9Docs.W, A9Docs.H).apply { layers += transparent.layers[0] }
                assertTrue("$label: MULTIPLY clears under a transparent source", A9Docs.differing(flatEmpty, keep(A9Docs.render(below, scale))) > 0)
            }
        }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = EngineLevel.entries.map { arrayOf<Any>(it) }
    }
}
