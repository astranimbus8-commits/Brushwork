package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.MergeKind
import com.brushwork.paint.model.Layer
import com.brushwork.paint.v19.a4.A4Kit.Companion.assertClose
import com.brushwork.paint.v19.a4.A4Kit.Companion.premultiplied
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R19, A4): every merge that keeps the type looks the same: the picture after "Merge down"
 * is within 1/255 of the picture before it (3/255 where an opacity folds: an anti-aliased edge is
 * rounded a different number of times), and the merged layer's pixels, premultiplied, are as close to a
 * fresh rendering of its data (I1).
 */
@RunWith(RobolectricTestRunner::class)
class MergeExactTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private fun mergeAndCompare(why: String, c: EditorController, upper: Layer, lower: Layer, kind: MergeKind, tol: Int = 1, fresh: (Layer) -> IntArray) {
        assertEquals(why, kind, c.layerMerge.kindOf(upper, lower))
        val before = kit.picture(c)
        c.mergeDown(upper)
        kit.settle(c)
        assertEquals("$why: merged", -1, c.doc.indexOf(upper))
        assertClose("$why: the picture", before, kit.picture(c), tol)
        assertClose("$why: pixels and data", premultiplied(fresh(lower)), premultiplied(kit.pixels(lower.bitmap)), tol)
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

    @Test
    fun opacityFoldsLookTheSame() {
        val red = 0xFFE04020.toInt()
        val blue = 0xFF3070D0.toInt()
        fun at(c: EditorController, layer: Layer, opacity: Float) = layer.also { c.setLayerProps(it, it.props().copy(opacity = opacity)) }
        val cases: List<Pair<String, (EditorController) -> Pair<Layer, Layer>>> = listOf(
            "upper at 50 % with one object onto a lower at 40 %" to { c ->
                at(c, kit.vectorLayer(c, listOf(kit.box(30f, 30f, 130f, 120f, red)), "Lower"), 0.4f) to
                    at(c, kit.vectorLayer(c, listOf(kit.box(60f, 50f, 150f, 130f, blue)), "Upper"), 0.5f)
            },
            "a shape at 60 % onto a vector layer" to { c ->
                kit.vectorLayer(c, listOf(kit.box(30f, 30f, 130f, 120f, red)), "Lower") to at(c, kit.shapeLayer(c, kit.rect(120f, 100f, 90f, 70f, blue), "Upper"), 0.6f)
            },
            "two apart objects at 70 % onto a shape" to { c ->
                kit.shapeLayer(c, kit.rect(80f, 70f, 90f, 70f), "Lower") to
                    at(c, kit.vectorLayer(c, listOf(kit.box(20f, 20f, 70f, 60f, blue), kit.box(140f, 100f, 220f, 170f, red)), "Upper"), 0.7f)
            },
            "a lower at 30 % with two apart objects under an opaque one" to { c ->
                at(c, kit.vectorLayer(c, listOf(kit.box(20f, 20f, 90f, 80f, red), kit.box(130f, 90f, 220f, 170f, blue)), "Lower"), 0.3f) to
                    kit.vectorLayer(c, listOf(kit.box(60f, 50f, 170f, 130f, blue)), "Upper")
            },
        )
        for ((why, build) in cases) {
            val c = kit.controller()
            val (lower, upper) = build(c)
            // (3 levels: an anti-aliased edge over a see-through object is rounded once more in
            // 8-bit premultiplied pixels on one side of the fold than on the other.)
            mergeAndCompare(why, c, upper, lower, MergeKind.VECTOR_OBJECTS, tol = 3) { kit.render(it.vector!!) }
            assertEquals("$why: at 100 %", 1f, lower.opacity)
        }
    }
}
