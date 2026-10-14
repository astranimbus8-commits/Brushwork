package com.brushwork.paint.qa19

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.select.SelectionDuplicate
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.v19.a4.A4Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 QA (R14, A4; I27): the selection bar's [LayerLabels19.DUPLICATE] at 392 and 360 dp and at
 * each font scale. It is the bar's last item, after "Save", inside the bar and whole; a tap copies
 * the selected pixels within the layer (one step [LayerLabels19.DUPLICATE_STEP], no new layer);
 * on a vector layer it copies the objects the selection touches and its confirmation
 * ([LayerLabels19.DUPLICATED]) shows whole; on a legacy shape layer it is not there.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class Qa19DuplicateUiTest {

    private fun select(c: EditorController, l: Float, t: Float, r: Float, b: Float) =
        c.setSelection(Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false))

    /** Slides the selection bar to its end (its scroll action, as far as it goes). */
    private fun scrollToEnd() {
        fun holds(n: SemanticsNode, tag: String): Boolean =
            n.config.getOrNull(SemanticsProperties.TestTag) == tag || n.children.any { holds(it, tag) }
        val row = RobolectricUi.elements().lastOrNull { e ->
            e.node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null && holds(e.node, V19Tags.DUPLICATE)
        } ?: throw AssertionError("no sideways scroll holds Duplicate")
        row.node.config.getOrNull(SemanticsActions.ScrollBy)?.action?.invoke(100_000f, 0f)
        Qa19Ui.settle()
    }

    @Test
    fun duplicateOnTheSelectionBar() = Qa19Ui.uiTest { h ->
        val kit = A4Kit(400, 300)
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "at ${width.substringBefore('-')} and font scale $scale"
            h.section("$where: pixels") {
                RuntimeEnvironment.setQualifiers(width)
                lateinit var layer: Layer
                val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true), fontScale = scale) { c ->
                    layer = c.addLayerWithContent("Painted", "Add") { cv -> cv.drawRect(60f, 60f, 140f, 120f, Paint().apply { color = 0xFFE02020.toInt() }) }!!
                    select(c, 50f, 50f, 150f, 130f)
                }
                assertEquals(where, if (width == Qa19Ui.Q360) 360 else 392, s.activity.resources.configuration.screenWidthDp)
                val bar = s.tagged(ChromeTags.SELECTION_BAR) ?: throw AssertionError("$where: no selection bar")
                assertNotNull("$where: Duplicate on the bar", Qa19Ui.tagged(V19Tags.DUPLICATE))
                assertTrue("$where: the bar $bar within the screen ${s.widthDp} dp", bar.left >= -0.5f && bar.right <= s.widthDp + 0.5f)
                // Slid to its end (the bar scrolls sideways): Duplicate is its last item, after Save.
                scrollToEnd()
                val duplicate = s.tagged(V19Tags.DUPLICATE) ?: throw AssertionError("$where: no Duplicate once slid")
                val save = Qa19Ui.find(SavedSelectionLabels.SAVE_BUTTON, exact = true)?.let { s.dp(it.bounds) } ?: throw AssertionError("$where: no \"${SavedSelectionLabels.SAVE_BUTTON}\"")
                assertTrue("$where: after Save ($duplicate after $save)", duplicate.left >= save.right - 0.5f)
                assertTrue("$where: inside the bar ($duplicate in $bar)", duplicate.left >= bar.left - 0.5f && duplicate.right <= bar.right + 0.5f && duplicate.top >= bar.top - 0.5f && duplicate.bottom <= bar.bottom + 0.5f)
                assertTrue("$where: a finger target ($duplicate)", duplicate.width >= 40f - 0.5f && duplicate.height >= 40f - 0.5f)
                Qa19Ui.assertNoTextOverflow(where) { it != LayerLabels19.DUPLICATE }
                val layers = s.c.doc.layers.size
                val steps = s.c.undoManager.undoCount
                Qa19Ui.click(LayerLabels19.DUPLICATE, exact = true)
                assertEquals("$where: one step", steps + 1, s.c.undoManager.undoCount)
                assertEquals(LayerLabels19.DUPLICATE_STEP, s.c.undoManager.undoLabel)
                assertEquals("$where: no new layer", layers, s.c.doc.layers.size)
                val d = SelectionDuplicate.offset(s.c)
                assertEquals("$where: the copy", 0xFFE02020.toInt(), layer.bitmap.getPixel(139 + d, 119 + d))
                assertEquals("$where: the selection on the copy", 50 + d, s.c.selection!!.bounds.left)
            }
            h.section("$where: objects, and the confirmation") {
                RuntimeEnvironment.setQualifiers(width)
                lateinit var layer: Layer
                h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true), fontScale = scale) { c ->
                    layer = kit.vectorLayer(c, listOf(kit.box(60f, 60f, 140f, 120f), kit.box(220f, 160f, 300f, 220f)), "Objects")
                    select(c, 50f, 50f, 150f, 130f)
                }
                Qa19Ui.click(LayerLabels19.DUPLICATE, exact = true)
                Qa19Ui.settle()
                assertEquals("$where: one copy", 3, layer.vector!!.objects.size)
                val confirmation = String.format(LayerLabels19.DUPLICATED, 1)
                assertTrue("$where: the confirmation", Qa19Ui.has(confirmation, exact = true))
                Qa19Ui.assertNoTextOverflow("$where, the confirmation") { it != confirmation }
            }
            h.section("$where: not on a shape layer") {
                RuntimeEnvironment.setQualifiers(width)
                h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true), fontScale = scale) { c ->
                    kit.shapeLayer(c, kit.rect(100f, 90f, 80f, 60f))
                    select(c, 50f, 50f, 150f, 130f)
                }
                assertNotNull("$where: the bar", Qa19Ui.find(SavedSelectionLabels.SAVE_BUTTON, exact = true))
                assertNull("$where: no Duplicate", Qa19Ui.tagged(V19Tags.DUPLICATE))
                assertFalse(Qa19Ui.has(LayerLabels19.DUPLICATE_DESC))
                Qa19Ui.assertNoTextOverflow(where) { it != LayerLabels19.DUPLICATE }
            }
        }
        kit.close()
    }
}
