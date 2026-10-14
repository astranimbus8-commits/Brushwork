package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.v19.a4.A4Kit.Companion.assertClose
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4): every merge that keeps the type looks the same: the picture after "Merge down"
 * is within 1/255 of the picture before it, and the merged layer's pixels are within 1/255 of a
 * fresh rendering of its data (I1).
 */
@RunWith(RobolectricTestRunner::class)
class MergeExactTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private fun mergeAndCompare(why: String, c: EditorController, upper: Layer, lower: Layer, kind: MergeKind, fresh: (Layer) -> IntArray) {
        assertEquals(why, kind, c.layerMerge.kindOf(upper, lower))
        val before = kit.picture(c)
        c.mergeDown(upper)
        kit.settle(c)
        assertEquals("$why: merged", -1, c.doc.indexOf(upper))
        assertClose("$why: the picture", before, kit.picture(c))
        assertClose("$why: pixels and data", fresh(lower), kit.pixels(lower.bitmap))
    }

    @Test
    fun textMergesLookTheSame() {
        for (opacity in listOf(1f, 0.55f)) {
            val c = kit.controller()
            val lower = kit.textLayer(c, kit.text("Lower", 70f, 50f), kit.text("Below", 70f, 140f), name = "Lower")
            // Over the lower texts at 100 %; beside them when see-through.
            val upper = kit.textLayer(c, if (opacity < 1f) kit.text("Up", 190f, 90f, 0xFFC02020.toInt()) else kit.text("Upper", 80f, 56f, 0xFFC02020.toInt()), name = "Upper")
            if (opacity < 1f) {
                c.setLayerProps(lower, lower.props().copy(opacity = opacity))
                c.setLayerProps(upper, upper.props().copy(opacity = opacity))
            }
            mergeAndCompare("texts at $opacity", c, upper, lower, MergeKind.TEXT_ITEMS) { kit.renderTexts(it) }
        }
    }

    @Test
    fun shapeAndVectorMergesLookTheSame() {
        val red = 0xFFE04020.toInt()
        val blue = 0xFF3070D0.toInt()
        val cases: List<Pair<String, (EditorController) -> Pair<Layer, Layer>>> = listOf(
            "shape onto shape" to { c -> kit.shapeLayer(c, kit.rect(80f, 70f, 90f, 70f), "Lower") to kit.shapeLayer(c, kit.rect(120f, 100f, 90f, 70f, blue), "Upper") },
            "shape onto vector" to { c -> kit.vectorLayer(c, listOf(kit.box(30f, 30f, 130f, 120f, red)), "Lower") to kit.shapeLayer(c, kit.rect(120f, 100f, 90f, 70f, blue), "Upper") },
            "vector onto shape" to { c -> kit.shapeLayer(c, kit.rect(80f, 70f, 90f, 70f), "Lower") to kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue), kit.box(120f, 90f, 210f, 160f, red)), "Upper") },
            "vector onto vector" to { c -> kit.vectorLayer(c, listOf(kit.box(30f, 30f, 130f, 120f, red)), "Lower") to kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue)), "Upper") },
        )
        for ((why, build) in cases) {
            val c = kit.controller()
            val (lower, upper) = build(c)
            mergeAndCompare(why, c, upper, lower, MergeKind.VECTOR_OBJECTS) { kit.render(it.vector!!) }
        }
    }
}
