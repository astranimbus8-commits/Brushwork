package com.brushwork.paint.v110.a7

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.exchange.NewArtwork
import com.brushwork.paint.exchange.NewArtworkSpec
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.clone.CloneSource
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.gallery.CanvasPresets
import com.brushwork.paint.ui.gallery.largestLine
import com.brushwork.paint.ui.gallery.layersOnDevice
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.max

/**
 * v1.10 A7 step 3 (design 2.4, 2.13 and J12): the gallery on R. With the phone's memory known
 * (the T606 here, at TILES) the new-canvas dialog shows "Largest on this phone: about W × H px"
 * under the size fields and the level's count in "Layers on this device"; picture imports keep up
 * to 4096 px; a Brushwork file the phone cannot hold is refused with "This canvas is too big for
 * this phone's memory. The largest is about W × H."; and the clone source's All-layers snapshot
 * may take `max(48 MiB, R/8)`. The other levels and unknown memory are the classes below.
 */
@RunWith(RobolectricTestRunner::class)
class GalleryLimitsOnRTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606))

    private val budget: LayerBudgetV110 get() = LayerBudget.current as LayerBudgetV110

    @Test
    fun theDialogShowsTheLargestCanvasUnderTheSizeFields() {
        assertTrue(EngineConfig.budget.budgets.known)
        val (w, h) = budget.largestNewCanvas(PHONE_W, PHONE_H)!!
        assertEquals("Largest on this phone: about $w × $h px", largestLine(budget, PHONE_W, PHONE_H))
        assertTrue("the line's size is accepted", budget.canvasCheck(w, h).ok)
        assertTrue("at the phone's shape", h > w)
        // A side still empty: square, the size the refusal names.
        assertEquals("Largest on this phone: about 6400 × 6400 px", largestLine(budget, 0, 500))
        assertEquals(LayersLabels110.canvasTooBig(6400, 6400), budget.canvasCheck(7000, 7000).message)
    }

    @Test
    fun layersOnThisDeviceIsTheTilesCount() {
        val per = CanvasLimits.layerBytes(PHONE_W, PHONE_H)
        val count = LayerBudgetV110.tilesMaxLayers(EngineConfig.budget.nativeBudget, J, per)
        assertEquals("up to $count", layersOnDevice(budget, budget.canvasCheck(PHONE_W, PHONE_H), PHONE_W, PHONE_H))
        assertEquals(260, count)
        assertEquals("v1.9's count on this heap", 25, CanvasPresets.maxLayers(PHONE_W, PHONE_H, J))
        assertEquals("a refused size", "—", layersOnDevice(budget, budget.canvasCheck(7000, 7000), 7000, 7000))
    }

    @Test
    fun picturesImportUpTo4096Px() {
        val poster = NewArtwork.forSvg("Poster", svg(POSTER_SVG))
        assertEquals(4096 to 3072, poster.width to poster.height)
        assertTrue("v1.9 kept less on this heap", NewArtwork.forSvg("Poster", svg(POSTER_SVG), J).width < 4096)
        val a0 = NewArtwork.forPdfPage("A0", A0_W, A0_H)
        assertEquals(4096, max(a0.width, a0.height))
        val v19 = NewArtwork.forPdfPage("A0", A0_W, A0_H, J)
        assertTrue("v1.9 kept less on this heap: $v19", max(v19.width, v19.height) < 4096)
    }

    @Test
    fun aBrushworkFileOpensAtItsSizeOrSaysHowLargeFits() {
        val p = BrushworkPayload(width = 4000, height = 5000, dpi = 300f)
        assertEquals(NewArtworkSpec("Big", 4000, 5000, 300f), NewArtwork.forPayload("Big", p))
        assertNull("v1.9 refused it on this heap", NewArtwork.forPayload("Big", p, J))
        val huge = BrushworkPayload(width = 10_000, height = 10_000)
        assertNull(NewArtwork.forPayload("Huge", huge))
        assertEquals(LayersLabels110.canvasTooBig(6400, 6400), NewArtwork.payloadRefusal(huge))
        assertEquals("the new-canvas dialog's text", budget.canvasCheck(10_000, 10_000).message, NewArtwork.payloadRefusal(huge))
    }

    @Test
    fun theCloneSnapshotMayTakeAnEighthOfR() {
        val cap = max(48L * MIB, EngineConfig.budget.nativeBudget / 8)
        assertEquals(cap, EngineConfig.budget.cloneSnapshotCap())
        assertEquals(cap, cloneSnapshotBudget())
        assertTrue("above v1.9's max(48 MiB, J/4)", cap > max(48L * MIB, J / 4))
    }
}

/** At FULL (no swap store in the gallery): "No fixed limit", the same ceiling as TILES. */
@RunWith(RobolectricTestRunner::class)
class GalleryLimitsOnRFullTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606))

    @Test
    fun noFixedLimitAndTheSameCeiling() {
        val b = LayerBudget.current as LayerBudgetV110
        assertNull(b.capacity(PHONE_W, PHONE_H))
        assertEquals(LayersLabels110.NO_FIXED_LIMIT, layersOnDevice(b, b.canvasCheck(PHONE_W, PHONE_H), PHONE_W, PHONE_H))
        assertEquals("—", layersOnDevice(b, b.canvasCheck(7000, 7000), 7000, 7000))
        assertEquals("Largest on this phone: about 6400 × 6400 px", largestLine(b, 1, 1))
        val p = BrushworkPayload(width = 4000, height = 5000, dpi = 300f)
        assertEquals(LayerBudget.MAX_LAYERS_GUARD.toLong(), b.rawMaxLayers(4000, 5000))
        assertEquals(NewArtworkSpec("Big", 4000, 5000, 300f), NewArtwork.forPayload("Big", p))
        val huge = BrushworkPayload(width = 10_000, height = 10_000)
        assertNull(NewArtwork.forPayload("Huge", huge))
        assertEquals(LayersLabels110.canvasTooBig(6400, 6400), NewArtwork.payloadRefusal(huge))
        assertEquals(4096, NewArtwork.forSvg("Poster", svg(POSTER_SVG)).width)
    }
}

/** At CLASSIC with memory known: Classic's count on R (never below v1.9's), a larger ceiling. */
@RunWith(RobolectricTestRunner::class)
class GalleryLimitsOnRClassicTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.CLASSIC, memory = FakeMemoryProbe(MemoryPreset.T606))

    @Test
    fun classicCountsOnR() {
        val b = LayerBudget.current as LayerBudgetV110
        val per = CanvasLimits.layerBytes(PHONE_W, PHONE_H)
        val count = LayerBudgetV110.classicMaxLayers(EngineConfig.budget.nativeBudget, J, per)
        assertEquals("up to $count", layersOnDevice(b, b.canvasCheck(PHONE_W, PHONE_H), PHONE_W, PHONE_H))
        assertTrue("never below v1.9's: $count", count >= CanvasPresets.maxLayers(PHONE_W, PHONE_H, J))
        val (w, h) = b.largestNewCanvas(1, 1)!!
        assertEquals("Largest on this phone: about $w × $h px", largestLine(b, 1, 1))
        assertTrue(b.canvasCheck(w, h).ok)
        val p = BrushworkPayload(width = 4000, height = 5000, dpi = 300f)
        assertEquals(NewArtworkSpec("Big", 4000, 5000, 300f), NewArtwork.forPayload("Big", p))
        assertEquals("R's cap at every level", max(48L * MIB, EngineConfig.budget.nativeBudget / 8), cloneSnapshotBudget())
    }
}

/** Memory unknown (J3, I32): every gallery number is v1.9's, and no "Largest" line shows. */
@RunWith(RobolectricTestRunner::class)
class GalleryLimitsOnRUnknownMemoryTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.UNKNOWN))

    private val heap = Runtime.getRuntime().maxMemory()

    @Test
    fun itIsV19s() {
        val b = LayerBudget.current
        assertNull(largestLine(b, PHONE_W, PHONE_H))
        assertNull((b as? LayerBudgetV110)?.largestNewCanvas(PHONE_W, PHONE_H))
        assertEquals("up to ${CanvasPresets.maxLayers(PHONE_W, PHONE_H, heap)}", layersOnDevice(b, b.canvasCheck(PHONE_W, PHONE_H), PHONE_W, PHONE_H))
        val huge = BrushworkPayload(width = 10_000, height = 10_000)
        assertEquals(NewArtwork.TOO_LARGE, NewArtwork.payloadRefusal(huge))
        for (p in listOf(BrushworkPayload(width = 4000, height = 5000, dpi = 300f), huge)) {
            assertEquals(NewArtwork.forPayload("P", p, heap), NewArtwork.forPayload("P", p))
        }
        assertEquals(NewArtwork.forPdfPage("A0", A0_W, A0_H, heap), NewArtwork.forPdfPage("A0", A0_W, A0_H))
        assertEquals(NewArtwork.forSvg("Poster", svg(POSTER_SVG), heap), NewArtwork.forSvg("Poster", svg(POSTER_SVG)))
        assertEquals(max(48L * MIB, heap / 4), cloneSnapshotBudget())
    }
}

private const val MIB = 1024L * 1024

/** The T606 preset's Java heap. */
private const val J = 512L * MIB

/** A canvas of the phone's screen (1080 × 2408). */
private const val PHONE_W = 1080
private const val PHONE_H = 2408

/** A0 in PDF points. */
private const val A0_W = 2384f
private const val A0_H = 3370f

/** 40 × 30 in: 14,000 × 10,500 px at 350 dpi. */
private const val POSTER_SVG = """<svg xmlns="http://www.w3.org/2000/svg" width="40in" height="30in"/>"""

private fun svg(s: String) = SvgParser.parse(s.toByteArray())

/** A new clone source's All-layers snapshot cap in the current session. */
private fun cloneSnapshotBudget(): Long {
    val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    try {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val settings = AppSettings(context)
        val doc = Document("clone", "Clone", 64, 48)
        doc.layers += Layer(doc.newLayerId(), "Layer", BitmapUtils.createLayerBitmap(64, 48))
        return CloneSource(EditorController(context, doc, scope, settings)).snapshotBudgetBytes
    } finally {
        scope.cancel()
    }
}
