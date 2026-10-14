package com.brushwork.paint.engine

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1b (design 2.4.6): "Merge down", moved into [LayerMerge] with `EditorController.mergeDown`
 * as its delegate. [LayerMerge.kindOf] says [MergeKind.VECTOR_OBJECTS] exactly where v1.8 merges
 * two vector layers as objects (adjacent, the upper one Normal at 100 % without mask, clipping or
 * adjustment, the lower one at 100 % without mask), and the merge itself is v1.8's: one step
 * "Merge down" that undo restores, the same refusals. [LayerMerge.rasterReason] is null (A4 fills it).
 *
 * v1.9 A4 (R19, 2.8 forced update): a layer under 100 % whose objects don't overlap folds its
 * opacity into them, so the 50 % variants (one object each) keep their objects too; and
 * [LayerMerge.rasterReason] names why two vector layers become pixels ([LayerLabels19.WHY_BLEND],
 * [LayerLabels19.WHY_CLIP]).
 */
@RunWith(RobolectricTestRunner::class)
class LayerMergeExtractionTest {
    private val kit = ObjectTestKit(160, 120)

    @After
    fun tearDown() = kit.close()

    private fun settle(c: EditorController) {
        c.vectors.flushPending()
        kit.idleUntil { !c.vectors.isRendering }
    }

    /** Background (raster), "Vector 1" with an ellipse, "Vector 2" with a rectangle (active, on top). */
    private fun fixture(): Triple<EditorController, Layer, Layer> {
        val c = kit.controller()
        val lower = c.doc.layers[1]
        c.setLayerData(lower, lower.dataSnapshot().copy(vector = VectorContent.EMPTY.plus(listOf(kit.ellipse(50f, 50f))).first), "Add shape")
        val upper = c.addVectorLayer()!!
        c.setLayerData(upper, upper.dataSnapshot().copy(vector = VectorContent.EMPTY.plus(listOf(kit.rectangle(90f, 60f, 40f, 30f))).first), "Add shape")
        settle(c)
        assertEquals(listOf("Background", "Vector 1", "Vector 2"), c.doc.layers.map { it.name })
        return Triple(c, upper, lower)
    }

    /** What would stop v1.8 from merging as objects, each applied the way the user would. */
    private val variants: List<Pair<String, (EditorController, Layer, Layer) -> Unit>> = listOf(
        "plain" to { _, _, _ -> },
        "upper at 50 % (folded)" to { c, u, _ -> c.setLayerProps(u, u.props().copy(opacity = 0.5f)) },
        "upper Multiply" to { c, u, _ -> c.setLayerProps(u, u.props().copy(blendMode = LayerBlendMode.MULTIPLY)) },
        "upper clipping" to { c, u, _ -> c.setLayerProps(u, u.props().copy(clipping = true)) },
        "lower at 50 % (folded)" to { c, _, l -> c.setLayerProps(l, l.props().copy(opacity = 0.5f)) },
        "lower Multiply (kept)" to { c, _, l -> c.setLayerProps(l, l.props().copy(blendMode = LayerBlendMode.MULTIPLY)) },
        "upper hidden" to { c, u, _ -> c.setLayerProps(u, u.props().copy(visible = false)) },
    )

    @Test
    fun kindOfIsVectorObjectsOnlyForTwoPlainAdjacentVectorLayers() {
        val (c, upper, lower) = fixture()
        val merge = c.layerMerge
        val background = c.doc.layers[0]
        assertEquals(MergeKind.VECTOR_OBJECTS, merge.kindOf(upper, lower))
        assertEquals("onto a raster layer", MergeKind.RASTER, merge.kindOf(lower, background))
        assertEquals("not adjacent", MergeKind.RASTER, merge.kindOf(upper, background))
        assertEquals("upside down", MergeKind.RASTER, merge.kindOf(lower, upper))
        upper.mask = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        assertEquals("upper masked", MergeKind.RASTER, merge.kindOf(upper, lower))
        upper.mask = null
        lower.mask = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        assertEquals("lower masked", MergeKind.RASTER, merge.kindOf(upper, lower))
        lower.mask = null
        assertEquals(MergeKind.VECTOR_OBJECTS, merge.kindOf(upper, lower))
        for ((why, apply) in variants) {
            val (v, u, l) = fixture()
            apply(v, u, l)
            val expected = if (why == "plain" || why.endsWith("(kept)") || why.endsWith("(folded)") || why == "upper hidden") MergeKind.VECTOR_OBJECTS else MergeKind.RASTER
            assertEquals(why, expected, v.layerMerge.kindOf(u, l))
            val reason = when (why) { "upper Multiply" -> LayerLabels19.WHY_BLEND; "upper clipping" -> LayerLabels19.WHY_CLIP; else -> null }
            assertEquals(why, reason, v.layerMerge.rasterReason(u, l))
        }
        assertNull(merge.rasterReason(lower, background))
    }

    @Test
    fun kindOfAgreesWithWhatMergeDownDoes() {
        for ((why, apply) in variants) {
            val (c, upper, lower) = fixture()
            apply(c, upper, lower)
            settle(c)
            val kind = c.layerMerge.kindOf(upper, lower)
            val upperContent = upper.vector
            val lowerContent = lower.vector
            val steps = c.undoManager.undoCount
            c.mergeDown(upper)
            settle(c)
            assertEquals("$why: one step", steps + 1, c.undoManager.undoCount)
            assertEquals(why, "Merge down", c.undoManager.undoLabel)
            assertEquals(why, listOf("Background", "Vector 1"), c.doc.layers.map { it.name })
            assertSame(why, lower, c.doc.activeLayer)
            assertEquals("$why: objects kept exactly when kindOf says so", kind == MergeKind.VECTOR_OBJECTS, lower.isVectorLayer)
            if (kind == MergeKind.VECTOR_OBJECTS) {
                assertEquals(why, lowerContent!!.objects.size + upperContent!!.objects.size, lower.vector!!.objects.size)
            }
            c.undo()
            settle(c)
            assertEquals("$why: undone", listOf("Background", "Vector 1", "Vector 2"), c.doc.layers.map { it.name })
            assertSame(why, upperContent, upper.vector)
            assertSame(why, lowerContent, lower.vector)
            assertEquals(why, steps, c.undoManager.undoCount)
            c.redo()
            settle(c)
            assertEquals("$why: redone", 2, c.doc.layers.size)
            assertEquals(why, kind == MergeKind.VECTOR_OBJECTS, lower.isVectorLayer)
        }
    }

    @Test
    fun theRefusalsAndTheRasterMergeAreV18() {
        val (c, upper, lower) = fixture()
        val background = c.doc.layers[0]
        val steps = c.undoManager.undoCount
        c.mergeDown(background)
        assertEquals("There is no layer below to merge into", c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertNull(c.layerMerge.siblingBelow(0))
        assertSame(lower, c.layerMerge.siblingBelow(c.doc.indexOf(upper)))
        assertSame(background, c.layerMerge.siblingBelow(c.doc.indexOf(lower)))
        // A vector layer onto the raster Background: pixels, one step, undoable.
        c.selectLayer(lower)
        c.layerMerge.mergeDown(lower)
        settle(c)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(listOf("Background", "Vector 2"), c.doc.layers.map { it.name })
        assertFalse(background.isVectorLayer)
        assertSame(background, c.doc.activeLayer)
        c.undo()
        settle(c)
        assertEquals(listOf("Background", "Vector 1", "Vector 2"), c.doc.layers.map { it.name })
        assertTrue(lower.isVectorLayer)
    }
}
