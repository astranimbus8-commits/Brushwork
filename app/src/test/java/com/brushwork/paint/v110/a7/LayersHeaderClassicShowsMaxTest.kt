package com.brushwork.paint.v110.a7

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeSwapStore
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LayersLabels110
import com.brushwork.paint.ui.layers.HEADER_COUNT_TAG
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.layers.headerCount
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
 * v1.10 A7 step 5 (design 2.13, R13 summary): the layers panel's header keeps v1.9's "n / max"
 * (`effectiveLayerCount` against `maxLayers`, red once nothing more can be added) wherever a limit
 * remains. Here Classic with the phone's memory known, filled to its limit with new (blank) layers:
 * "2 / 14", then "14 / 14" in red, and the next layer refused with the limit's text. The memory is
 * a small phone's (J = 8 MiB) so that the limit is a few 256² layers. TILES is
 * [LayersHeaderTilesShowsMaxTest]; the count alone, only at FULL with a swap store, is
 * [LayersHeaderFullSwapCountAloneTest].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a7.headerclassicsandbox"])
class LayersHeaderClassicShowsMaxTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.CLASSIC, budgets = Budgets(J_SMALL, (J_SMALL * 0.55).toLong(), known = true, stepDown = 0))

    @Test
    fun classicShowsNOfMaxAndTurnsRedAtTheLimit() = Qa19Ui.uiTest { h ->
        h.section("Classic, memory known") {
            val c = HeaderKit.open(h)
            val max = c.maxLayers
            assertEquals("Classic's count on this memory", LayerBudgetV110.classicMaxLayers(J_SMALL * 55 / 100, J_SMALL, c.doc.layerBytes), max)
            assertEquals(14, max)
            assertFalse((LayerBudget.current as LayerBudgetV110).noFixedLimit)
            HeaderKit.assertCount("2 / 14", description = null)
            assertFalse(headerCount(c.effectiveLayerCount, max, c.canAddLayer, countOnly = false).danger)
            while (c.canAddLayer) assertNotNull(c.addLayer())
            Qa19Ui.settle()
            assertEquals("blank layers count like any other", 14, c.doc.layers.size)
            HeaderKit.assertCount("14 / 14", description = null)
            assertTrue("red at the limit", headerCount(c.effectiveLayerCount, max, c.canAddLayer, countOnly = false).danger)
            c.message = null
            assertNull(c.addLayer())
            assertEquals("Layer limit reached (14) for this canvas size", c.message)
            Qa19Ui.assertNoTextOverflow("Classic at its limit")
        }
    }
}

/** At TILES on the T606: still "n / max" (the TILES count, at most the 10,000 guard). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a7.headertilessandbox"])
class LayersHeaderTilesShowsMaxTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606))

    @Test
    fun tilesShowsNOfMax() = Qa19Ui.uiTest { h ->
        h.section("TILES, T606") {
            val c = HeaderKit.open(h)
            assertFalse((LayerBudget.current as LayerBudgetV110).noFixedLimit)
            assertEquals(LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
            HeaderKit.assertCount("2 / ${c.maxLayers}", description = null)
            Qa19Ui.assertNoTextOverflow("TILES")
        }
    }
}

/** At FULL with a swap store: "2" alone, read "2 layers", never red; without the store it is "n / max" again. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a7.headerfullsandbox"])
class LayersHeaderFullSwapCountAloneTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), swap = FakeSwapStore(capacityBytes = 1L shl 30))

    @Test
    fun fullWithSwapShowsTheCountAlone() = Qa19Ui.uiTest { h ->
        h.section("FULL with a swap store") {
            val c = HeaderKit.open(h)
            assertTrue((LayerBudget.current as LayerBudgetV110).noFixedLimit)
            assertEquals(LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
            HeaderKit.assertCount("2", description = LayersLabels110.count(2))
            assertEquals("2 layers", LayersLabels110.count(2))
            assertNotNull(c.addLayer())
            Qa19Ui.settle()
            HeaderKit.assertCount("3", description = "3 layers")
            assertFalse("never red", headerCount(3, c.maxLayers, canAdd = false, countOnly = true).danger)
            Qa19Ui.assertNoTextOverflow("FULL with swap")
        }
    }
}

/** The pure count: both forms, and the danger color only on "n / max" at the limit. */
class LayersHeaderCountTest {
    @Test
    fun theTwoForms() {
        assertEquals("12 / 93", headerCount(12, 93, canAdd = true, countOnly = false).text)
        assertFalse(headerCount(12, 93, canAdd = true, countOnly = false).danger)
        assertTrue(headerCount(93, 93, canAdd = false, countOnly = false).danger)
        assertNull(headerCount(93, 93, canAdd = false, countOnly = false).description)
        val alone = headerCount(12, LayerBudget.MAX_LAYERS_GUARD, canAdd = false, countOnly = true)
        assertEquals("12", alone.text)
        assertFalse(alone.danger)
        assertEquals("12 layers", alone.description)
        assertEquals("1 layer", headerCount(1, 10_000, canAdd = true, countOnly = true).description)
    }
}

/** The panel of a 256² document with 2 layers, at the 392 dp phone's size. */
private object HeaderKit {
    fun open(h: Qa19Harness): EditorController {
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), Smoke.document(256, 256, layers = 2, whiteBottom = true))
        h.show {
            Box(Modifier.fillMaxSize()) {
                LayersPanel(
                    c,
                    onDismiss = {},
                    onImportPicture = {},
                    modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 520.dp),
                )
            }
        }
        return c
    }

    /** The header's count shows [text] and is read [description] (null: no description of its own). */
    fun assertCount(text: String, description: String?) {
        val node = countNode()
        assertNotNull("the header's count", node)
        assertEquals(listOf(text), node!!.config.getOrNull(SemanticsProperties.Text)?.map { it.text })
        assertEquals(description?.let { listOf(it) }, node.config.getOrNull(SemanticsProperties.ContentDescription))
    }

    private fun countNode(): SemanticsNode? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == HEADER_COUNT_TAG }?.node
}

/** A small phone's Java heap: Classic holds 14 layers of 256² on it. */
private const val J_SMALL = 8L shl 20
