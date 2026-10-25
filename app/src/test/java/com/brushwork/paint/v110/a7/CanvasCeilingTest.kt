package com.brushwork.paint.v110.a7

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.budget.PixelLedger
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.gallery.CanvasCheck
import com.brushwork.paint.ui.gallery.CanvasPresets
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 A7 step 2 (design 2.4 J12 and 2.13): the canvas ceiling. A canvas fits when
 * `6.0·L + 184 MB ≤ R` at TILES and FULL, or `3.8·L + 184 MB` plus one L per painted layer at
 * CLASSIC (which also keeps every size v1.9 accepted); a bigger one is refused with "This canvas is
 * too big for this phone's memory. The largest is about W × H.", W × H at the asked aspect ratio
 * and itself accepted. Sides stay at most 10,000 px. About 6000 × 6000 on the T606.
 *
 * The session of [engine] is the T606 at TILES; the other cases build their budgets.
 */
@RunWith(RobolectricTestRunner::class)
class CanvasCeilingTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606))

    private fun budget(level: EngineLevel, r: Long = R) =
        LayerBudgetV110(MemoryBudget(Budgets(J, r, known = true, stepDown = 0), PixelLedger()), level)

    @Test
    fun theT606TakesAbout6000Square() {
        val b = LayerBudget.current
        assertTrue(b is LayerBudgetV110)
        assertTrue(b.canvasCheck(6000, 6000).ok)
        assertTrue(b.canvasFits(6400, 6400))
        assertFalse(b.canvasFits(6500, 6500))
        assertEquals(CanvasCheck(false, LayersLabels110.canvasTooBig(6400, 6400)), b.canvasCheck(7000, 7000))
        // v1.9 refused 4000 x 5000 on this heap; v1.10 accepts it.
        assertFalse(CanvasPresets.check(4000, 5000, J).ok)
        assertTrue(b.canvasCheck(4000, 5000).ok)
    }

    @Test
    fun atR1100MBTheLargestIsAbout6100Square() {
        val b = budget(EngineLevel.TILES)
        assertEquals(6100 to 6100, b.largestCanvas(1, 1))
        assertTrue(b.canvasFits(6100, 6100))
        assertFalse(b.canvasFits(6200, 6200))
        assertEquals(CanvasCheck(false, LayersLabels110.canvasTooBig(6100, 6100)), b.canvasCheck(6200, 6200))
        // A 2:1 canvas keeps its ratio.
        assertEquals(8700 to 4300, b.largestCanvas(10_000, 5000))
        assertEquals(CanvasCheck(false, LayersLabels110.canvasTooBig(8700, 4300)), b.canvasCheck(10_000, 5000))
        // FULL has the same ceiling.
        assertEquals(b.largestCanvas(3, 4), budget(EngineLevel.FULL).largestCanvas(3, 4))
        assertFalse(budget(EngineLevel.FULL).canvasFits(6200, 6200))
    }

    @Test
    fun classicAddsOneLayerPerPaintedLayer() {
        val b = budget(EngineLevel.CLASSIC)
        assertTrue(b.canvasFits(6000, 6000, 2))
        assertFalse(b.canvasFits(6000, 6000, 3))
        // The phone size holds Classic's 84 layers (section 2.15), not 85.
        assertTrue(b.canvasFits(1080, 2408, 84))
        assertFalse(b.canvasFits(1080, 2408, 85))
        assertEquals(84, b.capacity(1080, 2408))
        // TILES holds its own count at 6000²: 2.
        assertTrue(budget(EngineLevel.TILES).canvasFits(6000, 6000, 2))
        assertFalse(budget(EngineLevel.TILES).canvasFits(6000, 6000, 3))
    }

    @Test
    fun classicKeepsEverySizeV19Accepted() {
        val lo = (J * 0.55).toLong()
        for (r in listOf(lo, 512L shl 20)) {
            val b = budget(EngineLevel.CLASSIC, r)
            for (w in 500..10_000 step 500) for (h in 500..10_000 step 500) {
                if (CanvasPresets.check(w, h, J).ok) assertTrue("${w}x$h at R = $r", b.canvasCheck(w, h).ok)
            }
        }
    }

    @Test
    fun theLargestSizeIsAlwaysAccepted() {
        val aspects = listOf(1 to 1, 3 to 4, 16 to 9, 1 to 3, 10 to 1, 1 to 40, 2408 to 1080)
        for (level in EngineLevel.entries) {
            for (r in listOf((J * 0.55).toLong(), 512L shl 20, R, 2L shl 30)) {
                val b = budget(level, r)
                for ((aw, ah) in aspects) {
                    val (w, h) = b.largestCanvas(aw, ah)
                    assertTrue("$level R = $r $aw:$ah -> ${w}x$h", w in 1..CanvasLimits.MAX_SIDE && h in 1..CanvasLimits.MAX_SIDE)
                    assertTrue("$level R = $r $aw:$ah -> ${w}x$h", b.canvasFits(w, h))
                    assertTrue(b.canvasCheck(w, h).ok)
                }
            }
        }
    }

    @Test
    fun theSidesStayAtTenThousand() {
        for (level in EngineLevel.entries) {
            val b = budget(level, 2L shl 30)
            assertEquals(CanvasPresets.check(10_001, 100, Long.MAX_VALUE), b.canvasCheck(10_001, 100))
            assertFalse(b.canvasCheck(10_001, 100).ok)
            assertEquals(CanvasPresets.check(0, 5, Long.MAX_VALUE), b.canvasCheck(0, 5))
            assertFalse(b.canvasFits(10_001, 100))
            assertTrue(b.canvasCheck(10_000, 1000).ok)
        }
    }

    @Test
    fun fullHasNoFixedNumberInTheGallery() {
        val b = budget(EngineLevel.FULL)
        assertNull(b.capacity(2048, 2048))
        assertEquals(LayerBudget.MAX_LAYERS_GUARD.toLong(), b.rawMaxLayers(2048, 2048))
        // A canvas past the ceiling has TILES' (negative) count.
        assertTrue(b.rawMaxLayers(9000, 9000) < CanvasLimits.MIN_LAYERS)
        assertEquals(84L, budget(EngineLevel.CLASSIC).rawMaxLayers(1080, 2408))
        assertEquals(235L, budget(EngineLevel.TILES).rawMaxLayers(1080, 2408))
    }

    @Test
    fun importsKeepWhatFits() {
        val tiles = budget(EngineLevel.TILES)
        assertEquals(CanvasLimits.IMPORT_MAX_SIDE, tiles.importMaxSide(6000, 6000))
        assertEquals(3000, tiles.importMaxSide(3000, 2000))
        assertEquals(CanvasLimits.IMPORT_MAX_SIDE, budget(EngineLevel.FULL).importMaxSide(6000, 4000))
        // With little R a picture shrinks until its canvas holds 5 layers, and no further.
        val small = budget(EngineLevel.TILES, (J * 0.55).toLong())
        val side = small.importMaxSide(4096, 4096)
        assertTrue(side < CanvasLimits.IMPORT_MAX_SIDE)
        assertTrue(small.rawMaxLayers(side, side) >= CanvasLimits.IMPORT_MIN_LAYERS)
        assertTrue(small.rawMaxLayers(side + 1, side + 1) < CanvasLimits.IMPORT_MIN_LAYERS)
        // CLASSIC never keeps less than v1.9 did on the same heap.
        for ((w, h) in listOf(4096 to 4096, 4000 to 3000, 1000 to 9000)) {
            val classic = budget(EngineLevel.CLASSIC, (J * 0.55).toLong())
            assertTrue(classic.importMaxSide(w, h) >= CanvasLimits.importMaxSide(w, h, J))
        }
    }

    private companion object {
        const val J = 512L shl 20
        const val R = 1_100_000_000L
    }
}
