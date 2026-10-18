package com.brushwork.paint.v19.a4

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.PaperSpec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R15, R19, I21; A4): "Merge down" on a document with a paper never draws the paper into
 * the merged layer. Each kind of merge (pixels, texts kept, objects kept, the opacity fold,
 * "Keep editable") gives the same pixels as on the same document without a paper, and its empty
 * parts stay transparent.
 */
@RunWith(RobolectricTestRunner::class)
class LayerMergePaperTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private val cream = 0xFFF3E9D2.toInt()

    /** A merge: builds "Lower" and "Upper" (active) over a transparent background, then merges. */
    private class Case(val what: String, val build: (A4Kit, EditorController) -> Pair<Layer, Layer>, val merge: (EditorController, Layer) -> Unit = { c, u -> c.mergeDown(u) })

    private fun raster(c: EditorController, name: String, l: Float, t: Float, color: Int): Layer =
        c.addLayerWithContent(name, "Add") { cv: Canvas -> cv.drawRect(l, t, l + 80f, t + 60f, Paint().apply { this.color = color }) }!!

    private val cases = listOf(
        Case("two raster layers", { k, c -> raster(c, "Lower", 20f, 20f, 0x80FF0000.toInt()) to raster(c, "Upper", 60f, 50f, 0x8000FF00.toInt()) }),
        Case("pixels for a reason (Multiply)", { k, c ->
            val lower = k.shapeLayer(c, k.rect(60f, 50f, 70f, 50f), "Lower")
            val upper = k.shapeLayer(c, k.rect(100f, 80f, 60f, 60f), "Upper")
            c.setLayerProps(upper, upper.props().copy(blendMode = LayerBlendMode.MULTIPLY))
            lower to upper
        }),
        Case("texts kept", { k, c -> k.textLayer(c, k.text("One", 60f, 40f), name = "Lower") to k.textLayer(c, k.text("Two", 160f, 140f), name = "Upper") }),
        Case("objects kept", { k, c -> k.shapeLayer(c, k.rect(60f, 50f, 70f, 50f), "Lower") to k.vectorLayer(c, listOf(k.box(90f, 60f, 170f, 140f)), "Upper") }),
        Case("the opacity fold", { k, c ->
            val lower = k.vectorLayer(c, listOf(k.box(20f, 20f, 90f, 80f)), "Lower")
            val upper = k.vectorLayer(c, listOf(k.box(60f, 50f, 160f, 120f)), "Upper")
            c.setLayerProps(upper, upper.props().copy(opacity = 0.5f))
            lower to upper
        }),
        Case("keep editable", { k, c ->
            val lower = k.vectorLayer(c, listOf(k.box(20f, 20f, 90f, 80f)), "Lower")
            val upper = k.vectorLayer(c, listOf(k.box(60f, 50f, 160f, 120f), k.box(120f, 90f, 200f, 160f)), "Upper")
            c.setLayerProps(upper, upper.props().copy(opacity = 0.5f))
            lower to upper
        }, merge = { c, u -> c.layerMerge.mergeDownEditable(u) }),
    )

    /** The merged layer's pixels for [case], on a document with [paper]. */
    private fun merged(case: Case, paper: PaperSpec?): IntArray {
        val c = kit.controller(white = false)
        c.doc.paper = paper
        val (lower, upper) = case.build(kit, c)
        kit.settle(c)
        case.merge(c, upper)
        kit.settle(c)
        assertEquals(case.what, listOf("Background", "Lower"), c.doc.layers.map { it.name })
        return kit.pixels(lower.bitmap)
    }

    @Test
    fun noMergeDrawsThePaper() {
        for (case in cases) {
            val plain = merged(case, null)
            val onPaper = merged(case, PaperSpec(cream))
            assertArrayEquals("${case.what}: the same pixels as without a paper", plain, onPaper)
            assertEquals("${case.what}: an empty corner stays transparent", 0, onPaper[onPaper.lastIndex])
            assertEquals("${case.what}: no paper anywhere", 0, onPaper.count { it == cream })
        }
    }
}
