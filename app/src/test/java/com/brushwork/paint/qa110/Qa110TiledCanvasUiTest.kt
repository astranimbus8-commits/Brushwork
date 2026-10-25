package com.brushwork.paint.qa110

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.v110.a9.A9Docs
import com.brushwork.paint.v110.a9.WaitingTimingKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import java.util.Random

/**
 * v1.10 QA (R13, A9 step 5; design 2.16): a 200-layer document at TILES is drawn on the canvas
 * exactly as Classic draws it, at 392 and 360 dp and at each font scale. The stack holds every
 * blend mode, translucent and masked layers, clipped layers, an isolated folder, a pass-through
 * folder at 60 % and a masked adjustment layer; the top layer is active.
 *
 * At TILES every layer but the active one is parked: the canvas renders from the tiles, through
 * the below projection (its renders counted). Then every parked tile is packed and a layer below
 * is hidden: the canvas re-renders through the waiting tiles (P4; chunks counted) and the parked
 * layers stay parked. The same document, flat, at CLASSIC (a second editor of the same size),
 * gives the reference frames: the canvas view's pixels are equal bit for bit, before and after
 * the eye toggle. No text of either editor overflows its box (I27).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class Qa110TiledCanvasUiTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    /** The canvas view of [s]. */
    private fun canvasOf(s: ChromeScreen): CanvasView =
        Smoke.find(s.activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

    /** Draws [s]'s canvas as frames do until no display tile is dirty or waiting. */
    private fun finish(s: ChromeScreen, where: String) {
        val view = canvasOf(s)
        assertTrue("$where: the canvas is laid out", view.width > 0 && view.height > 0)
        val scratch = Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888)
        val frame = Canvas(scratch)
        try {
            val done = Smoke.pumpUntil(90_000) {
                view.draw(frame)
                !s.c.tiles.hasDirty && !s.c.tiles.hasWaiting
            }
            assertTrue("$where: the canvas finished rendering", done)
        } finally {
            scratch.recycle()
        }
    }

    /** The canvas view's pixels now (one more frame, nothing left to render). */
    private fun shown(s: ChromeScreen): Bitmap {
        val view = canvasOf(s)
        return Bitmap.createBitmap(view.width, view.height, Bitmap.Config.ARGB_8888).also { view.draw(Canvas(it)) }
    }

    /** Parks every layer but the top one, recycling the flat bitmaps (the stack never holds them all twice). */
    private fun park(d: Document) {
        val chaos = ChaosResidency()
        for (l in d.layers) {
            if (l.isFolder || l === d.layers.last()) continue
            val content = l.bitmap
            val mask = if (l.hasMask) l.mask else null
            assertTrue("${l.name} parks", chaos.park(l))
            content.recycle()
            mask?.recycle()
        }
    }

    /** Packs every ordinary tile of the parked content and masks (as a reload leaves them). */
    private fun packAll(d: Document): Int =
        WaitingTimingKit.packAll(d.layers.flatMap { listOfNotNull(it.pixels as? TiledPixels, it.maskPixels as? TiledPixels) })

    private fun assertParked(d: Document, where: String) {
        for (l in d.layers) if (!l.isFolder && l !== d.layers.last()) assertTrue("$where: ${l.name} is still parked", l.pixels is TiledPixels)
    }

    @Test
    fun twoHundredLayersAtTilesDrawAsClassic() = Qa19Ui.uiTest { h ->
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "at ${width.substringBefore('-')} and font scale $scale"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                val made = mutableListOf<Bitmap>()
                try {
                    // TILES: parked layers, through the below projection.
                    A9Docs.setLevel(EngineLevel.TILES)
                    val tiled = stack()
                    park(tiled)
                    val t = h.editor(tiled, fontScale = scale)
                    assertEquals(where, if (width == Qa19Ui.Q360) 360 else 392, t.activity.resources.configuration.screenWidthDp)
                    finish(t, "$where, TILES")
                    Qa19Ui.assertNoTextOverflow("$where, TILES")
                    assertTrue("$where: the frames drew from the below projection", t.c.liveAdjust.belowProjection.hits > 0)
                    assertParked(tiled, "$where, TILES")
                    val tiles1 = shown(t).also { made += it }

                    // Packed tiles and an eye toggle below: through the waiting tiles.
                    assertTrue("$where: tiles to pack", packAll(tiled) > 0)
                    val chunks = t.c.tiles.waiting?.chunks ?: 0L
                    t.c.toggleVisibility(tiled.layers[HIDDEN])
                    finish(t, "$where, TILES after the eye toggle")
                    assertTrue("$where: the parts waited for their reads", (t.c.tiles.waiting?.chunks ?: 0L) > chunks)
                    assertParked(tiled, "$where, TILES after the eye toggle")
                    val tiles2 = shown(t).also { made += it }

                    // CLASSIC: the same document, flat, in an editor of the same size.
                    A9Docs.setLevel(EngineLevel.CLASSIC)
                    val flat = stack()
                    val k = h.editor(flat, fontScale = scale)
                    finish(k, "$where, Classic")
                    Qa19Ui.assertNoTextOverflow("$where, Classic")
                    val classic1 = shown(k).also { made += it }
                    k.c.toggleVisibility(flat.layers[HIDDEN])
                    finish(k, "$where, Classic after the eye toggle")
                    val classic2 = shown(k).also { made += it }

                    assertTrue("$where: the canvas shows the document (the eye toggle changes it)", A9Docs.differing(classic1, classic2) > 0)
                    A9Docs.assertBitEqual("$where: 200 layers at TILES drawn as Classic", classic1, tiles1)
                    A9Docs.assertBitEqual("$where: after an eye toggle below, through waiting tiles", classic2, tiles2)
                } finally {
                    A9Docs.setLevel(EngineLevel.TILES)
                    made.forEach { if (!it.isRecycled) it.recycle() }
                }
            }
        }
    }

    /**
     * [N] layers of [W] x [H], built the same each time: an opaque backdrop; sparse painted layers
     * cycling through every blend mode, translucent ones among them, every 17th masked, every 23rd
     * clipped to the layer under it; an isolated folder of 8 (Overlay, 85 %) at 60, a pass-through
     * folder of 6 at 60 % at 120, a masked Invert adjustment at 150; a plain top layer, active.
     */
    private fun stack(): Document {
        val d = Document("tiled200", "Tiled 200", W, H)
        val r = Random(SEED)
        val p = Paint().apply { isAntiAlias = true }
        val modes = LayerBlendMode.entries
        fun painted(): Bitmap {
            val b = BitmapUtils.createLayerBitmap(W, H)
            val c = Canvas(b)
            repeat(if (r.nextInt(3) == 0) 2 else 1) {
                val cx = r.nextInt(W)
                val cy = r.nextInt(H)
                repeat(3) {
                    p.color = Color.argb(80 + r.nextInt(176), r.nextInt(256), r.nextInt(256), r.nextInt(256))
                    c.drawCircle(cx + r.nextInt(81) - 40f, cy + r.nextInt(81) - 40f, 8f + r.nextInt(40), p)
                }
            }
            return b
        }
        fun layer(name: String, parent: Long = Layer.ROOT_ID, blend: LayerBlendMode = LayerBlendMode.NORMAL, opacity: Float = 1f) =
            Layer(d.newLayerId(), name, painted()).apply {
                parentId = parent
                blendMode = blend
                this.opacity = opacity
            }
        d.layers += Layer(d.newLayerId(), "Backdrop", A9Docs.backdrop(SEED, W, H))
        while (d.layers.size < N - 1) {
            val i = d.layers.size
            when (i) {
                60 -> {
                    val f = Layer.newFolder(d.newLayerId(), "Isolated", FolderSpec(passThrough = false))
                    repeat(8) { j -> d.layers += layer("Isolated $j", f.id, modes[(i + j) % modes.size], if (j % 2 == 0) 0.9f else 1f) }
                    f.blendMode = LayerBlendMode.OVERLAY
                    f.opacity = 0.85f
                    d.layers += f
                }
                120 -> {
                    val f = Layer.newFolder(d.newLayerId(), "Pass-through", FolderSpec(passThrough = true))
                    repeat(6) { j -> d.layers += layer("Pass $j", f.id, modes[(i + 3 * j) % modes.size]) }
                    f.opacity = 0.6f
                    d.layers += f
                }
                150 -> d.layers += Layer(d.newLayerId(), "Invert", A9Docs.empty(W, H)).apply {
                    adjustment = AdjustmentSpec(filterId = "adjust.invert")
                    opacity = 0.5f
                    mask = A9Docs.mask(SEED + i, W, H)
                    maskEnabled = true
                }
                else -> {
                    val under = d.layers.last()
                    val l = layer("L$i", blend = modes[i % modes.size], opacity = if (i % 5 == 0) 0.6f else 1f)
                    if (i % 17 == 0) {
                        l.mask = A9Docs.mask(SEED + i, W, H)
                        l.maskEnabled = true
                    }
                    if (i % 23 == 0 && !under.isFolder && !under.isAdjustmentLayer && under.parentId == Layer.ROOT_ID) l.clipping = true
                    d.layers += l
                }
            }
        }
        d.layers += layer("Top")
        d.activeLayerIndex = d.layers.lastIndex
        assertEquals(N, d.layers.size)
        return d
    }

    private companion object {
        const val N = 200
        const val W = 400
        const val H = 300
        const val SEED = 110L

        /** The plain layer hidden by the eye toggle (well below the active top layer). */
        const val HIDDEN = 5
    }
}
