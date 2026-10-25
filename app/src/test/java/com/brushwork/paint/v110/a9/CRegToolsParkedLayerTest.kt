package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.RenderScaled
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.AdjustmentHistogram
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.select.EyedropperSettings
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.PixelSnapshot
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.text.WrapContours
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 A9 step 4 (design 2.3 C-REG): the readers of layers that may be inactive work on parked
 * layers exactly as on flat ones and never materialize them (`PixelFacade.strict`): the selection
 * tools' snapshot (`PixelSnapshot.take` of a layer, Magic wand, Object select, Color select; and of
 * the canvas), the eyedropper's "This layer" sample, the Color select and Magnetic lasso proxy
 * source (`RenderScaled.Source.layer(store)`), the text wrap outline (`WrapContours`, which keeps
 * its cache across a park: the key is `pixelGeneration`, not the bitmap), and the Adjust sheet's
 * histogram (`AdjustmentHistogram.below`, through `snapshot()`s it releases).
 */
@RunWith(RobolectricTestRunner::class)
class CRegToolsParkedLayerTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        PixelFacade.strict = false
        scope.cancel()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun controller(doc: Document): EditorController {
        val app = RuntimeEnvironment.getApplication()
        return EditorController(app, doc, scope, AppSettings(app))
    }

    private fun park(doc: Document) {
        val chaos = ChaosResidency()
        for (l in doc.layers) if (!l.isFolder) {
            assertTrue("${l.name} parks", chaos.park(l))
            assertTrue(l.pixels is TiledPixels)
        }
    }

    /** Runs [block] under `PixelFacade.strict` and asserts that nothing was materialized. */
    private fun <T> strict(what: String, block: () -> T): T {
        val mats = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            return block()
        } finally {
            PixelFacade.strict = false
            assertEquals("$what: nothing materialized", mats, PixelFacade.materializations)
        }
    }

    @Test
    fun selectionSnapshotsEyedropperAndProxiesReadParkedLayers() {
        val doc = A9Docs.document(A9Docs.Context.MASK, LayerBlendMode.SCREEN, seed = 4L)
        val c = controller(doc)
        val subject = doc.layers[1]
        doc.activeLayerIndex = 1
        val flat = doc.layers.map { keep(it.bitmap.copy(Bitmap.Config.ARGB_8888, false)) }
        val canvas = keep(c.compositor.renderFlattened(withPaper = true))
        val eyedropper = EyedropperTool(c).apply { settings = EyedropperSettings(source = SampleSource.LAYER, sampleSize = 3) }
        val points = listOf(10f to 10f, 130.5f to 77.2f, 255f to 256f, 200f to 150f, 399f to 299f)
        val samples = points.map { (x, y) -> eyedropper.sample(x, y) }
        val proxySize = 200 to 150
        val proxy = keep(RenderScaled.renderAt(RenderScaled.Source.layer(flat[1]), proxySize.first, proxySize.second, withPaper = false))
        park(doc)
        strict("selection snapshots") {
            for ((i, l) in doc.layers.withIndex()) {
                A9Docs.assertBitEqual("the snapshot of parked layer $i", flat[i], keep(PixelSnapshot.take(c, SampleSource.LAYER, l)))
            }
            A9Docs.assertBitEqual("the canvas snapshot over parked layers", canvas, keep(PixelSnapshot.take(c, SampleSource.CANVAS, subject)))
        }
        strict("the eyedropper") {
            assertEquals("This layer, sampled on a parked active layer", samples, points.map { (x, y) -> eyedropper.sample(x, y) })
        }
        strict("the proxy source") {
            val got = keep(RenderScaled.renderAt(RenderScaled.Source.layer(subject.pixels), proxySize.first, proxySize.second, withPaper = false))
            A9Docs.assertBitEqual("the Color select / Magnetic lasso proxy of a parked layer", proxy, got)
        }
        for (l in doc.layers) assertEquals("no pin is left on ${l.name}", 0, l.pins)
    }

    @Test
    fun textWrapOutlinesReadParkedLayersAndKeepTheirCache() {
        val doc = A9Docs.document(A9Docs.Context.MASK, LayerBlendMode.NORMAL, seed = 6L)
        val subject = doc.layers[1]
        val masked = WrapContours()
        val flatOutline = masked.outline(subject)!!
        val traces = masked.traces
        park(doc)
        strict("the wrap outline") {
            assertTrue("the cached outline stays across a park", masked.outline(subject) === flatOutline)
            assertEquals(traces, masked.traces)
            val fresh = WrapContours().outline(subject)!!
            assertEquals("bounds", flatOutline.bounds, fresh.bounds)
            assertEquals("shape", flatOutline.shape, fresh.shape)
            assertEquals("box", flatOutline.box, fresh.box)
        }
        // A replaced store (pixelGeneration) traces again.
        subject.pixels = TiledPixels.fromBitmap(keep(A9Docs.content(99L)))
        masked.outline(subject)
        assertEquals("a replaced store is traced again", traces + 1, masked.traces)
    }

    @Test
    fun theAdjustmentHistogramReadsParkedLayersThroughSnapshots() {
        val doc = A9Docs.document(A9Docs.Context.ADJUSTMENT_ABOVE, LayerBlendMode.MULTIPLY, seed = 8L)
        val c = controller(doc)
        val adjustment = doc.layers.first { it.isAdjustmentLayer }
        val expected = AdjustmentHistogram.below(c, adjustment)!!
        park(doc)
        val refs = doc.layers.flatMap { l -> listOfNotNull(l.pixels as? TiledPixels, l.maskPixels as? TiledPixels) }
            .flatMap { s -> (0 until s.rows).flatMap { row -> (0 until s.cols).mapNotNull { col -> s.tileAt(col, row) } } }
            .associateWith { it.refs }
        val got = strict("the histogram") { AdjustmentHistogram.below(c, adjustment)!! }
        assertArrayEquals("the histogram over parked layers", expected, got)
        for ((t, r) in refs) assertEquals("every snapshot is released", r, t.refs)
    }
}
