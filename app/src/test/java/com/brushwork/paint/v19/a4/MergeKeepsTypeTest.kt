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
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VShape
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
 * order, the lower layer's name and opacity) and of two shape or vector layers (their objects in
 * one vector layer with the lower layer's name, a shape layer converted) as ONE step "Merge down"
 * that undo restores; any condition of R19 that fails merges them as pixels, as in v1.8.
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

    /** Shape and vector layers: "Lower" below "Upper" (active), built by [lower] and [upper]. */
    private class ObjectPair(val why: String, val lower: (A4Kit, EditorController) -> Layer, val upper: (A4Kit, EditorController) -> Layer)

    private val objectPairs = listOf(
        ObjectPair("shape onto shape", { k, c -> k.shapeLayer(c, k.rect(60f, 50f, 70f, 50f), "Lower") }, { k, c -> k.shapeLayer(c, k.rect(150f, 110f, 60f, 60f, 0xFF30A0F0.toInt()), "Upper") }),
        ObjectPair("shape onto vector", { k, c -> k.vectorLayer(c, listOf(k.box(20f, 20f, 90f, 80f)), "Lower") }, { k, c -> k.shapeLayer(c, k.rect(80f, 70f, 60f, 60f), "Upper") }),
        ObjectPair("vector onto shape", { k, c -> k.shapeLayer(c, k.rect(60f, 50f, 70f, 50f), "Lower") }, { k, c -> k.vectorLayer(c, listOf(k.box(70f, 40f, 160f, 120f), k.box(150f, 100f, 220f, 160f, 0xFF20A040.toInt())), "Upper") }),
        ObjectPair("vector onto vector", { k, c -> k.vectorLayer(c, listOf(k.box(20f, 20f, 90f, 80f)), "Lower") }, { k, c -> k.vectorLayer(c, listOf(k.box(60f, 50f, 160f, 120f)), "Upper") }),
    )

    @Test
    fun shapeAndVectorLayersKeepTheirObjectsInOneStep() {
        for (p in objectPairs) {
            val c = kit.controller()
            val lower = p.lower(kit, c)
            val upper = p.upper(kit, c)
            assertSame(p.why, upper, c.activeLayer)
            val lowerData = lower.dataSnapshot()
            val upperData = upper.dataSnapshot()
            val expected = (objectsOf(lower) + objectsOf(upper)).map { it.withId(0) }
            assertEquals(p.why, MergeKind.VECTOR_OBJECTS, c.layerMerge.kindOf(upper, lower))
            val steps = c.undoManager.undoCount
            c.mergeDown(upper)
            kit.settle(c)
            assertEquals(p.why, steps + 1, c.undoManager.undoCount)
            assertEquals(p.why, "Merge down", c.undoManager.undoLabel)
            assertEquals(p.why, listOf("Background", "Lower"), c.doc.layers.map { it.name })
            assertSame(p.why, lower, c.activeLayer)
            assertNull("${p.why}: converted", lower.shapeData)
            val merged = lower.vector!!
            assertEquals("${p.why}: the lower objects, then the upper ones", expected, merged.objects.map { it.withId(0) })
            assertEquals("${p.why}: ids unique", merged.objects.size, merged.objects.map { it.id }.toSet().size)
            assertEquals(p.why, 1f, lower.opacity)
            c.undo()
            kit.settle(c)
            assertEquals(p.why, listOf("Background", "Lower", "Upper"), c.doc.layers.map { it.name })
            assertEquals("${p.why}: undo restores the lower layer", lowerData, lower.dataSnapshot())
            assertEquals("${p.why}: undo restores the upper layer", upperData, upper.dataSnapshot())
            c.redo()
            kit.settle(c)
            assertEquals(p.why, expected.size, lower.vector!!.objects.size)
            assertEquals(p.why, -1, c.doc.indexOf(upper))
        }
    }

    @Test
    fun shapeConditionsThatFailMergeAsPixels() {
        val cases: List<Pair<String, (EditorController, Layer, Layer) -> Unit>> = listOf(
            "upper Multiply" to { c, u, _ -> c.setLayerProps(u, u.props().copy(blendMode = LayerBlendMode.MULTIPLY)) },
            "upper clipping" to { c, u, _ -> c.setLayerProps(u, u.props().copy(clipping = true)) },
            "lower masked" to { c, _, l -> c.addMask(l, fromSelection = false) },
            "upper masked" to { c, u, _ -> c.addMask(u, fromSelection = false) },
        )
        for ((why, apply) in cases) {
            val c = kit.controller()
            val lower = kit.shapeLayer(c, kit.rect(60f, 50f, 70f, 50f), "Lower")
            val upper = kit.shapeLayer(c, kit.rect(150f, 110f, 60f, 60f), "Upper")
            apply(c, upper, lower)
            assertEquals(why, MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
            c.mergeDown(upper)
            assertEquals(why, listOf("Background", "Lower"), c.doc.layers.map { it.name })
            assertNull("$why: pixels", lower.shapeData)
            assertNull("$why: pixels", lower.vector)
        }
        // Different kinds: a text onto a shape, a shape onto a text.
        val c = kit.controller()
        val shape = kit.shapeLayer(c, kit.rect(60f, 50f, 70f, 50f), "Shape")
        val text = kit.textLayer(c, kit.text("Two", 160f, 140f), name = "Text")
        assertEquals(MergeKind.RASTER, c.layerMerge.kindOf(text, shape))
        val d = kit.controller()
        val t2 = kit.textLayer(d, kit.text("Two", 160f, 140f), name = "Text")
        val s2 = kit.shapeLayer(d, kit.rect(60f, 50f, 70f, 50f), "Shape")
        assertEquals(MergeKind.RASTER, d.layerMerge.kindOf(s2, t2))
    }

    private fun objectsOf(layer: Layer): List<VObject> = layer.vector?.objects ?: listOf(VShape(0L, shape = ShapeCodec.decode(layer.shapeData)!!))

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
