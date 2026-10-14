package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.tools.text.TextWrapSpec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4): "Merge down" keeps the type of two text layers (their texts appended in picture
 * order, the lower layer's name and opacity) as ONE step "Merge down" that undo restores; any
 * condition of R19 that fails merges them as pixels, as in v1.8.
 */
@RunWith(RobolectricTestRunner::class)
class MergeKeepsTypeTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    /** Background, "Lower" with "One" (and [moreBelow]), "Upper" with "Two" on top (active). */
    private fun texts(moreBelow: List<TextItem> = emptyList(), apart: Boolean = true): Triple<EditorController, Layer, Layer> {
        val c = kit.controller()
        val lower = kit.textLayer(c, kit.text("One", 60f, 40f), *moreBelow.toTypedArray(), name = "Lower")
        val upper = kit.textLayer(c, kit.text("Two", if (apart) 160f else 70f, if (apart) 140f else 48f, 0xFFC02020.toInt()), name = "Upper")
        assertSame(upper, c.activeLayer)
        return Triple(c, upper, lower)
    }

    @Test
    fun twoTextLayersKeepTheirTextsInOneStep() {
        val (c, upper, lower) = texts()
        val upperData = upper.textData
        val lowerData = lower.textData
        assertEquals(MergeKind.TEXT_ITEMS, c.layerMerge.kindOf(upper, lower))
        val steps = c.undoManager.undoCount
        c.mergeDown(upper)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertEquals(listOf("Background", "Lower"), c.doc.layers.map { it.name })
        assertSame(lower, c.activeLayer)
        val items = TextCodec.decodeAll(lower.textData)
        assertEquals(listOf("One", "Two"), items.map { it.text })
        assertEquals("ids unique 1..n", listOf(1, 2), items.map { it.id }.sorted())
        assertEquals(1f, lower.opacity)
        assertNull(lower.vector)
        c.undo()
        assertEquals(listOf("Background", "Lower", "Upper"), c.doc.layers.map { it.name })
        assertEquals(upperData, upper.textData)
        assertEquals(lowerData, lower.textData)
        c.redo()
        assertEquals(2, TextCodec.decodeAll(lower.textData).size)
        assertEquals(-1, c.doc.indexOf(upper))
    }

    @Test
    fun aLayerOfSeveralTextsTakesTheUpperTextsOnTopWithTheirIdsKeptOrRenumbered() {
        val (c, upper, lower) = texts(moreBelow = listOf(kit.text("Three", 60f, 100f)))
        c.mergeDown(upper)
        val items = TextCodec.decodeAll(lower.textData)
        assertEquals(listOf("One", "Three", "Two"), items.map { it.text })
        assertEquals(3, items.map { it.id }.toSet().size)
        assertTrue(items.all { it.id > 0 })
    }

    @Test
    fun theSameOpacityKeepsTheTypeWhenTheTextsDoNotMeet() {
        val (c, upper, lower) = texts()
        c.setLayerProps(upper, upper.props().copy(opacity = 0.5f))
        c.setLayerProps(lower, lower.props().copy(opacity = 0.5f))
        assertEquals(MergeKind.TEXT_ITEMS, c.layerMerge.kindOf(upper, lower))
        c.mergeDown(upper)
        assertEquals(2, TextCodec.decodeAll(lower.textData).size)
        assertEquals("the lower layer's opacity", 0.5f, lower.opacity)
    }

    @Test
    fun everyFailedConditionMergesAsPixels() {
        val cases: List<Pair<String, (EditorController, Layer, Layer) -> Unit>> = listOf(
            "different opacities" to { c, u, _ -> c.setLayerProps(u, u.props().copy(opacity = 0.5f)) },
            "upper Multiply" to { c, u, _ -> c.setLayerProps(u, u.props().copy(blendMode = LayerBlendMode.MULTIPLY)) },
            "lower Screen" to { c, _, l -> c.setLayerProps(l, l.props().copy(blendMode = LayerBlendMode.SCREEN)) },
            "upper clipping" to { c, u, _ -> c.setLayerProps(u, u.props().copy(clipping = true)) },
            "lower masked" to { c, _, l -> c.addMask(l, fromSelection = false) },
            "upper wraps" to { _, u, _ -> u.textData = TextCodec.encodeAll(TextCodec.decodeAll(u.textData).map { it.copy(wrap = TextWrapSpec(sourceLayerId = 1L)) }) },
            "lower is a frame" to { _, _, l -> l.textData = TextCodec.encodeAll(TextCodec.decodeAll(l.textData).map { it.copy(spec = it.spec.copy(box = TextBoxSpec(width = 120f, minHeight = 40f)), thread = TextThreadSpec(storyId = 9L, story = "One", end = 3)) }) },
        )
        for ((why, apply) in cases) {
            val (c, upper, lower) = texts()
            apply(c, upper, lower)
            assertEquals(why, MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
            c.mergeDown(upper)
            assertEquals(why, listOf("Background", "Lower"), c.doc.layers.map { it.name })
            assertNull("$why: pixels", lower.textData)
        }
    }

    @Test
    fun seeThroughTextsThatMeetMergeAsPixels() {
        val (c, upper, lower) = texts(apart = false)
        c.setLayerProps(upper, upper.props().copy(opacity = 0.6f))
        c.setLayerProps(lower, lower.props().copy(opacity = 0.6f))
        assertEquals(MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
        // At 100 % the same texts merge as texts: the upper one covers the lower one as before.
        val (d, u2, l2) = texts(apart = false)
        assertEquals(MergeKind.TEXT_ITEMS, d.layerMerge.kindOf(u2, l2))
        c.mergeDown(upper)
        assertNull(lower.textData)
    }

    @Test
    fun atMost64TextsInAll() {
        fun many(n: Int, y: Float) = List(n) { kit.text("t$it", 10f + (it % 8) * 28f, y + (it / 8) * 4f) }
        val c = kit.controller()
        val lower = kit.textLayer(c, *many(40, 20f).toTypedArray(), name = "Lower")
        val upper = kit.textLayer(c, *many(25, 120f).toTypedArray(), name = "Upper")
        assertEquals(MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
        val d = kit.controller()
        val l2 = kit.textLayer(d, *many(40, 20f).toTypedArray(), name = "Lower")
        val u2 = kit.textLayer(d, *many(24, 120f).toTypedArray(), name = "Upper")
        assertEquals(MergeKind.TEXT_ITEMS, d.layerMerge.kindOf(u2, l2))
        d.mergeDown(u2)
        assertEquals(64, TextCodec.decodeAll(l2.textData).size)
        c.mergeDown(upper)
        assertNull(lower.textData)
    }
}
