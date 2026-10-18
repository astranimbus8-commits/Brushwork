package com.brushwork.paint.qa19

import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.v19.a4.A4Kit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 QA (R19, A4; I27): the merge dialog at 392 and 360 dp and at each font scale. The longest
 * form of it (the see-through overlap: the body, "Keep editable" and its note, "Merge as pixels",
 * Cancel) shows every text whole, every button inside the screen, and "Merge as pixels" leaves its
 * confirmation whole too.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392)
class Qa19MergeUiTest {

    @Test
    fun theMergeDialogFits() = Qa19Ui.uiTest { h ->
        val kit = A4Kit(400, 300)
        for (width in listOf(Qa19Ui.Q392, Qa19Ui.Q360)) for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) {
            val where = "at ${width.substringBefore('-')} and font scale $scale"
            h.section(where) {
                RuntimeEnvironment.setQualifiers(width)
                lateinit var lower: Layer
                val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true), fontScale = scale) { c ->
                    lower = kit.vectorLayer(c, listOf(kit.box(40f, 40f, 160f, 140f)), "Lower")
                    val upper = kit.vectorLayer(c, listOf(kit.box(120f, 100f, 240f, 200f, 0xFF3070D0.toInt()), kit.box(200f, 150f, 320f, 250f)), "Upper")
                    c.setLayerProps(upper, upper.props().copy(opacity = 0.5f))
                    kit.settle(c)
                }
                assertEquals(where, if (width == Qa19Ui.Q360) 360 else 392, s.activity.resources.configuration.screenWidthDp)
                Qa19Ui.click("Open layers")
                Qa19Ui.click(LayerLabels.MERGE, exact = true)
                assertNotNull("$where: the dialog", Qa19Ui.tagged(V19Tags.MERGE_DIALOG))
                val screen = s.root
                val dialogTexts = listOf(
                    LayerLabels19.MERGE_TITLE,
                    String.format(LayerLabels19.MERGE_BODY, LayerLabels19.WHY_OVERLAP),
                    LayerLabels19.KEEP_EDITABLE,
                    LayerLabels19.KEEP_EDITABLE_NOTE,
                    LayerLabels19.MERGE_CONFIRM,
                    "Cancel",
                )
                for (label in dialogTexts) {
                    val e: RobolectricUi.Element = Qa19Ui.find(label, exact = true) ?: throw AssertionError("$where: no \"$label\"; shown: ${Qa19Ui.shown().take(60)}")
                    assertTrue("$where: \"$label\" inside the screen (${e.bounds} in $screen)", e.bounds.left >= screen.left - 1 && e.bounds.right <= screen.right + 1)
                    // (The body scrolls; the dialog's own buttons are always on screen.)
                    if (label == LayerLabels19.MERGE_CONFIRM || label == "Cancel") {
                        assertTrue("$where: \"$label\" on screen (${e.bounds} in $screen)", e.bounds.top >= screen.top - 1 && e.bounds.bottom <= screen.bottom + 1)
                    }
                }
                // (Only this dialog's texts: the editor and the layer window behind it are checked elsewhere.)
                Qa19Ui.assertNoTextOverflow(where) { it !in dialogTexts }
                Qa19Ui.click(LayerLabels19.MERGE_CONFIRM, exact = true)
                assertEquals("$where: merged", 2, s.c.doc.layers.size)
                assertTrue("$where: pixels", !lower.isVectorLayer)
                Qa19Ui.settle()
                val confirmation = String.format(LayerLabels19.MERGED_AS_PIXELS, LayerLabels19.WHY_OVERLAP)
                assertTrue("$where: the confirmation", Qa19Ui.has(confirmation, exact = true))
                Qa19Ui.settle()
                Qa19Ui.assertNoTextOverflow("$where, the confirmation") { it != confirmation }
            }
        }
        kit.close()
    }
}
