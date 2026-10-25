package com.brushwork.paint.qa110

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.common.EngineLabels110
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.gallery.NewCanvasDialog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 A7 step 6 (design 2.13, R13): the layer limits in the real editor and the new-canvas
 * dialog on a 392 dp and a 360 dp phone, at font scale 1, 1.3 and 2.0 (I27), with the phone's
 * memory known through `EngineRule`.
 *
 * Classic at its limit (a small phone, J = 8 MiB: 14 layers of 256²): the layers are added up to
 * 14, the 15th is refused and the editor says "Layer limit reached (14) for this canvas size",
 * with no text cut. The new-canvas dialog's screen-size canvas is too big for that memory: under
 * the size fields "Largest on this phone: about W × H px", and the refusal "This canvas is too big
 * for this phone's memory. The largest is about W × H." with the same W × H.
 * [Qa110LayerLimitsFullUiTest] is FULL without a limit, [Qa110LayerLimitsTilesUiTest] the refused
 * first write, the canvas operation and the 10,000-layer guard.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a7classicsandbox"])
class Qa110LayerLimitsUiTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.CLASSIC, budgets = Budgets(J_SMALL, (J_SMALL * 0.55).toLong(), known = true, stepDown = 0))

    @Test
    fun classicAtItsLimit() = Limits.everyPhone { h, where, scale ->
        val s = h.editor(Limits.doc(), fontScale = scale)
        val c = s.c
        while (c.canAddLayer) assertNotNull(where, c.addLayer())
        Qa19Ui.settle()
        assertEquals(where, 14, c.doc.layers.size)
        assertEquals(where, 14, c.maxLayers)
        c.message = null
        assertNull(where, c.addLayer())
        assertEquals(where, 14, c.doc.layers.size)
        Qa19Ui.settle()
        Limits.assertShown(where, "Layer limit reached (14) for this canvas size")
        Qa19Ui.assertNoTextOverflow(where)

        h.show(fontScale = scale) { NewCanvasDialog(creating = false, onDismiss = {}, onCreate = {}) }
        val largest = Qa19Ui.shown().firstOrNull { it.startsWith(LARGEST) }
        assertNotNull("$where: the largest canvas under the size fields", largest)
        val size = largest!!.removePrefix(LARGEST).removeSuffix(" px")
        val (w, hgt) = size.split(" × ").map { it.toInt() }
        Limits.assertShown(where, LayersLabels110.canvasTooBig(w, hgt))
        Qa19Ui.assertNoTextOverflow("$where, the new-canvas dialog")
    }
}

/**
 * At FULL with a swap store on the T606: no limit but the guard. 103 layers of 256² (above
 * v1.9's most, 100) go in with no refusal; the new-canvas dialog says "No fixed limit". With R
 * and the store both full the next layer is refused: "Not enough free storage for more layers".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a7fullsandbox"])
class Qa110LayerLimitsFullUiTest {
    private val store = FakeSwapStore(capacityBytes = 1L shl 30)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), swap = store, residency = Limits.Quiet())

    @Test
    fun fullWithoutALimit() = Limits.everyPhone { h, where, scale ->
        val s = h.editor(Limits.doc(), fontScale = scale)
        val c = s.c
        assertTrue(where, (LayerBudget.current as LayerBudgetV110).noFixedLimit)
        c.message = null
        repeat(101) { assertNotNull("$where, layer ${it + 3}", c.addLayer()) }
        assertNull("$where: no refusal", c.message)
        Qa19Ui.settle()
        assertEquals(where, 103, c.doc.layers.size)
        assertFalse(where, Qa19Ui.has("Layer limit reached"))

        Limits.full {
            store.capacityBytes = 1L
            try {
                assertFalse(where, c.canAddLayer)
                assertNull(where, c.addLayer())
                Qa19Ui.settle()
                Limits.assertShown(where, EngineLabels110.STORAGE_FULL)
                assertEquals(EngineLabels110.STORAGE_FULL, LayerBudgetV110.firstWriteRefusal())
            } finally {
                store.capacityBytes = 1L shl 30
            }
        }
        Qa19Ui.assertNoTextOverflow(where)

        h.show(fontScale = scale) { NewCanvasDialog(creating = false, onDismiss = {}, onCreate = {}) }
        Limits.assertShown(where, LayersLabels110.NO_FIXED_LIMIT)
        assertTrue(where, Qa19Ui.shown().any { it.startsWith(LARGEST) })
        Qa19Ui.assertNoTextOverflow("$where, the new-canvas dialog")
    }
}

/**
 * At TILES on the T606 (a residency that parks nothing). With R full, a stroke on a blank layer
 * is refused before any pixel changes: "Not enough memory to paint on this layer. Merge or
 * delete layers", no undo step; a canvas operation is refused with "Not enough memory to change
 * the canvas with this many layers" (asserted on the budget: A11 calls it from the canvas
 * operations). At 10,000 layers the next one is refused: "An artwork can have at most 10,000
 * layers".
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.a7tilessandbox"])
class Qa110LayerLimitsTilesUiTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Limits.Quiet())

    @Test
    fun tilesRefusals() = Limits.everyPhone { h, where, scale ->
        val doc = Limits.doc()
        doc.layers += Layer(doc.newLayerId(), "Blank", BlankPixels(256, 256))
        doc.activeLayerIndex = doc.layers.lastIndex
        val s = h.editor(doc, fontScale = scale)
        val c = s.c
        val steps = c.undoManager.undoCount
        Limits.full {
            c.message = null
            // Read before the frame: the editor's snackbar takes the message (and clears it) when it shows.
            Limits.stroke(c, settle = false)
            assertEquals(where, LayerBudgetV110.firstWriteRefusal(), c.message)
            Qa19Ui.settle()
            Limits.assertShown(where, EngineLabels110.PAINT_REFUSED)
            assertEquals("$where: no undo step", steps, c.undoManager.undoCount)
            assertTrue(where, doc.layers.last().pixels is BlankPixels)
            assertEquals(where, EngineLabels110.CANVAS_OP_TOO_BIG, LayerBudget.current.canTransformCanvas(doc, 512, 512))
        }
        assertNull("$where: with room the canvas may change", LayerBudget.current.canTransformCanvas(doc, 512, 512))
        Qa19Ui.assertNoTextOverflow(where)

        val big = Limits.doc()
        while (big.layers.size < LayerBudget.MAX_LAYERS_GUARD) big.layers += Layer(big.newLayerId(), "Blank", BlankPixels(256, 256))
        big.activeLayerIndex = big.layers.lastIndex
        val g = h.editor(big, fontScale = scale).c
        assertFalse(where, g.canAddLayer)
        assertNull(where, g.addLayer())
        assertEquals(where, LayerBudget.MAX_LAYERS_GUARD, big.layers.size)
        Qa19Ui.settle()
        Limits.assertShown(where, LayersLabels110.GUARD)
        Qa19Ui.assertNoTextOverflow("$where, at 10,000 layers")
    }
}

/** The phones, the documents, R filled and the residency of the three classes. */
private object Limits {
    /** [block] in a section at 392 and 360 dp, each at font scale 1, 1.3 and 2.0. */
    fun everyPhone(block: (Qa19Harness, String, Float) -> Unit) {
        try {
            Qa19Ui.uiTest { h ->
                for (width in listOf(392, 360)) {
                    RuntimeEnvironment.setQualifiers(if (width == 392) Qa19Ui.Q392 else Qa19Ui.Q360)
                    for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
                        val where = "at $width dp, font scale $scale"
                        h.section(where) { block(h, where, scale) }
                    }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** A 256² document: a white layer and an empty one. */
    fun doc(): Document = Smoke.document(256, 256, layers = 2, whiteBottom = true)

    fun assertShown(where: String, text: String) =
        assertTrue("$where: \"$text\" on screen; shown: ${Qa19Ui.shown()}", Qa19Ui.has(text, exact = true))

    /** [block] with R full: no layer can be admitted and the tile RAM is 0. */
    fun full(block: () -> Unit) {
        val b = EngineConfig.budget
        val filled = (b.nativeBudget - (b.ledger.native - b.ledger.of(LedgerCategory.TILES))).coerceAtLeast(0L)
        b.ledger.add(LedgerCategory.TRANSIENT, filled)
        try {
            block()
        } finally {
            b.ledger.sub(LedgerCategory.TRANSIENT, filled)
        }
    }

    /** A brush stroke across the canvas, then a frame unless [settle] is false. */
    fun stroke(c: EditorController, settle: Boolean = true) {
        c.pointerDown(ToolPoint(40f, 40f))
        for (i in 1..12) c.pointerMove(ToolPoint(40f + 14f * i, 40f + 10f * i))
        c.pointerUp(ToolPoint(208f, 160f))
        if (settle) Qa19Ui.settle()
    }

    /** A residency that parks nothing and frees nothing, so the ledger stays where a test puts it. */
    class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit

        override fun deactivated(layer: Layer) = Unit

        override fun pressure(p: MemoryPressure) = Unit

        override fun idleTick() = Unit
    }
}

/** The start of the new-canvas dialog's line under the size fields. */
private const val LARGEST = "Largest on this phone: about "

/** A small phone's Java heap: Classic holds 14 layers of 256² on it. */
private const val J_SMALL = 8L shl 20
