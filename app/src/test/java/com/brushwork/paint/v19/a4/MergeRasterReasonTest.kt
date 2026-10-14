package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextThreadSpec
import com.brushwork.paint.ui.common.LayerLabels19
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4): `LayerMerge.rasterReason`, the reason the layers panel names before two data
 * layers (text, shape or vector) merge into pixels: one [LayerLabels19] WHY_* text per failed
 * condition, [LayerLabels19.WHY_OVERLAP] only when it is the only one; null when either layer is
 * raster, an adjustment layer or a folder, and when the type is kept. `EditorController.mergeDown`
 * itself never asks: it merges at once (as pixels when there is a reason).
 */
@RunWith(RobolectricTestRunner::class)
class MergeRasterReasonTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    /** Background, "Lower", "Upper" (active), each from its builder. */
    private fun pair(lower: (EditorController) -> Layer, upper: (EditorController) -> Layer): Triple<EditorController, Layer, Layer> {
        val c = kit.controller()
        val l = lower(c)
        val u = upper(c)
        return Triple(c, u, l)
    }

    private fun lowerText(c: EditorController) = kit.textLayer(c, kit.text("One", 60f, 40f), name = "Lower")
    private fun upperText(c: EditorController) = kit.textLayer(c, kit.text("Two", 160f, 140f), name = "Upper")
    private fun lowerShape(c: EditorController) = kit.shapeLayer(c, kit.rect(60f, 50f, 70f, 50f), "Lower")
    private fun upperShape(c: EditorController) = kit.shapeLayer(c, kit.rect(170f, 130f, 50f, 40f), "Upper")
    private fun lowerVector(c: EditorController) = kit.vectorLayer(c, listOf(kit.box(20f, 20f, 90f, 80f)), "Lower")

    /** Two objects that overlap: see-through, their opacity can't be folded exactly. */
    private fun upperOverlapping(c: EditorController) =
        kit.vectorLayer(c, listOf(kit.box(120f, 100f, 200f, 160f), kit.box(150f, 120f, 230f, 175f)), "Upper")

    private fun opacity(c: EditorController, l: Layer, o: Float) = c.setLayerProps(l, l.props().copy(opacity = o))

    @Test
    fun eachFailedConditionHasItsReason() {
        val cases: List<Triple<String, Triple<EditorController, Layer, Layer>, String>> = listOf(
            Triple("texts, lower masked", pair(::lowerText, ::upperText).also { (c, _, l) -> c.addMask(l, fromSelection = false) }, LayerLabels19.WHY_MASK),
            Triple("texts, lower Screen", pair(::lowerText, ::upperText).also { (c, _, l) -> c.setLayerProps(l, l.props().copy(blendMode = LayerBlendMode.SCREEN)) }, LayerLabels19.WHY_BLEND),
            Triple("texts, upper clipping", pair(::lowerText, ::upperText).also { (c, u, _) -> c.setLayerProps(u, u.props().copy(clipping = true)) }, LayerLabels19.WHY_CLIP),
            Triple("texts, upper with copies", pair(::lowerText, ::upperText).also { (_, u, _) -> u.array = LayerArray(ArraySpec(count = 3)) }, LayerLabels19.WHY_COPIES),
            Triple("texts, lower a frame", pair(::lowerText, ::upperText).also { (_, _, l) -> l.textData = TextCodec.encodeAll(TextCodec.decodeAll(l.textData).map { it.copy(spec = it.spec.copy(box = TextBoxSpec(width = 120f, minHeight = 40f)), thread = TextThreadSpec(storyId = 9L, story = "One", end = 3)) }) }, LayerLabels19.WHY_FRAMES),
            Triple("texts, 65 in all", pair({ c -> kit.textLayer(c, *Array(64) { kit.text("t$it", 20f + (it % 8) * 26f, 20f + (it / 8) * 20f) }, name = "Lower") }, ::upperText), LayerLabels19.WHY_TOO_MANY),
            Triple("texts that meet, both at 60 %", pair(::lowerText, { c -> kit.textLayer(c, kit.text("Two", 70f, 48f), name = "Upper") }).also { (c, u, l) -> opacity(c, u, 0.6f); opacity(c, l, 0.6f) }, LayerLabels19.WHY_OVERLAP),
            Triple("text onto shape", pair(::lowerShape, ::upperText), LayerLabels19.WHY_KINDS),
            Triple("vector onto text", pair(::lowerText, { c -> kit.vectorLayer(c, listOf(kit.box(120f, 100f, 200f, 160f)), "Upper") }), LayerLabels19.WHY_KINDS),
            Triple("shapes, lower masked", pair(::lowerShape, ::upperShape).also { (c, _, l) -> c.addMask(l, fromSelection = false) }, LayerLabels19.WHY_MASK),
            Triple("shapes, upper Multiply", pair(::lowerShape, ::upperShape).also { (c, u, _) -> c.setLayerProps(u, u.props().copy(blendMode = LayerBlendMode.MULTIPLY)) }, LayerLabels19.WHY_BLEND),
            Triple("shapes, upper clipping", pair(::lowerShape, ::upperShape).also { (c, u, _) -> c.setLayerProps(u, u.props().copy(clipping = true)) }, LayerLabels19.WHY_CLIP),
            Triple("vectors, lower with copies", pair(::lowerVector, ::upperOverlapping).also { (_, _, l) -> l.array = LayerArray(ArraySpec(count = 3)) }, LayerLabels19.WHY_COPIES),
            Triple("overlapping objects at 50 %", pair(::lowerVector, ::upperOverlapping).also { (c, u, _) -> opacity(c, u, 0.5f) }, LayerLabels19.WHY_OVERLAP),
            Triple("overlap and a mask: the mask", pair(::lowerVector, ::upperOverlapping).also { (c, u, l) -> opacity(c, u, 0.5f); c.addMask(l, fromSelection = false) }, LayerLabels19.WHY_MASK),
        )
        for ((what, layers, why) in cases) {
            val (c, upper, lower) = layers
            assertEquals(what, MergeKind.RASTER, c.layerMerge.kindOf(upper, lower))
            assertEquals(what, why, c.layerMerge.rasterReason(upper, lower))
        }
    }

    @Test
    fun noReasonWhenTheTypeIsKeptOrALayerIsNotADataLayer() {
        val cases: List<Pair<String, Triple<EditorController, Layer, Layer>>> = listOf(
            "two texts" to pair(::lowerText, ::upperText),
            "shape onto vector" to pair(::lowerVector, ::upperShape),
            "the lower layer's blend and clipping stay with the objects" to pair(::lowerVector, ::upperShape).also { (c, _, l) -> c.setLayerProps(l, l.props().copy(blendMode = LayerBlendMode.MULTIPLY)) },
            "overlapping objects at 100 %" to pair(::lowerVector, ::upperOverlapping),
            "text onto raster" to pair({ c -> c.addLayer()!! }, ::upperText),
            "raster onto shape" to pair(::lowerShape, { c -> c.addLayer()!! }),
            "raster onto raster" to pair({ c -> c.addLayer()!! }, { c -> c.addLayer()!! }),
            "adjustment onto text" to pair(::lowerText, { c -> c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), null)!! }),
        )
        for ((what, layers) in cases) {
            val (c, upper, lower) = layers
            assertNull(what, c.layerMerge.rasterReason(upper, lower))
        }
        // A pair that is not upper-right-above-lower has none either.
        val (c, upper, lower) = pair(::lowerText, ::upperText)
        assertNull("reversed", c.layerMerge.rasterReason(lower, upper))
        assertNull("not adjacent", c.layerMerge.rasterReason(upper, c.doc.layers[0]))
    }

    @Test
    fun mergeDownStaysImmediateAndMergesAsPixelsForAReason() {
        val (c, upper, lower) = pair(::lowerShape, ::upperShape)
        c.setLayerProps(upper, upper.props().copy(blendMode = LayerBlendMode.MULTIPLY))
        assertNotNull(c.layerMerge.rasterReason(upper, lower))
        val steps = c.undoManager.undoCount
        c.mergeDown(upper)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals("Merge down", c.undoManager.undoLabel)
        assertEquals(listOf("Background", "Lower"), c.doc.layers.map { it.name })
        assertNull("pixels", lower.shapeData)
        assertNull("pixels", lower.vector)
    }
}
