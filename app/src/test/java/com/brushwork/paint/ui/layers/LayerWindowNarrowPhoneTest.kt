package com.brushwork.paint.ui.layers

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The layer window on a 360 dp phone (design §3.7.11 asks for 360 as well as 392), as wide as area
 * E sizes it there (w − 10 = 350; taller than 520 so every row shows), with the rows that need the
 * most room: a masked Tone
 * adjustment layer (the Masks tool's), and a clipped, masked, locked and alpha-locked layer. Every
 * row keeps its eye, mask square and ≡ at 40 dp, "100%" / "Normal" keep their room (the
 * thumbnail shrinks instead), and no text or description is on two clickables, values aside
 * (I10: a screen reader's merged reading of each clickable). Then the same rows in the 382 dp
 * window on the user's 392 dp phone.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(instrumentedPackages = ["com.brushwork.paint.ui.layers.narrowphonesandbox"])
class LayerWindowNarrowPhoneTest {

    @Test
    @Config(qualifiers = "w360dp-h760dp-xxhdpi")
    fun masksLocksAndClippingKeepEveryTargetAndValueOnA360DpPhone() = check(width = 350, list = 188f, thumb = 48f)

    @Test
    @Config(qualifiers = "w392dp-h873dp-xxhdpi")
    fun theSameRowsOnTheUsersPhone() = check(width = 382, list = 220f, thumb = 62f)

    /** The window [width] dp wide (E: w − 10), its list [list] dp and the thumbnails [thumb] dp. */
    private fun check(width: Int, list: Float, thumb: Float) {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 3, whiteBottom = true))
        val (_, clipped, plain) = c.doc.layers
        clipped.clipping = true
        clipped.locked = true
        clipped.alphaLocked = true
        clipped.mask = BitmapUtils.createMaskBitmap(400, 300)
        c.selectLayer(plain)
        val tone = c.addAdjustmentLayer(
            AdjustmentEffects.defaultSpec(),
            MaskSpec(components = listOf(LinearMask(1, x0 = 40f, y0 = 0f, x1 = 360f, y1 = 0f)), nextId = 2),
        )!!
        assertTrue("the Tone layer has its mask", tone.mask != null)
        val steps = c.undoManager.undoCount
        val density = activity.resources.displayMetrics.density
        val probe = LayerWindowProbe(density)

        val size = width to HEIGHT
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    LayersPanel(
                        c, onDismiss = {}, onImportPicture = {},
                        modifier = Modifier
                            .align(Alignment.BottomStart)
                            .padding(start = 5.dp, bottom = 92.dp)
                            .size(size.first.dp, size.second.dp)
                            .testTag(WINDOW),
                    )
                }
            }
        }
        SmokeUi.settle()
        val activityWindow = SmokeUi.windows().first()

        run {
            val w = width
            val window = probe.tagged(WINDOW)
            probe.assertSize("$w: window", w.toFloat(), HEIGHT.toFloat(), window)
            probe.assertDp("$w: list", list, probe.tagged(LayerWindowTags.LIST).width)
            for (layer in c.doc.layers) {
                val n = c.doc.indexOf(layer) + 1
                val what = "$w dp, layer $n"
                probe.assertSize("$what: thumbnail", thumb, thumb, probe.tagged(LayerWindowTags.thumb(layer.id)))
                probe.assertSize("$what: eye", 40f, 40f, probe.control(LayerLabels.hide(n)))
                probe.assertSize("$what: ≡", 40f, 80f, probe.control(LayerLabels.reorder(n)))
                val values = probe.tagged(LayerWindowTags.values(layer.id))
                assertTrue("$what: \"100%\" / \"Normal\" keep ${probe.dp(values.width)} dp", probe.dp(values.width) >= 44f)
                if (layer.mask != null) {
                    val mask = probe.control(LayerLabels.maskOf(n))
                    probe.assertSize("$what: mask square", 40f, 40f, mask)
                    probe.assertDp("$what: the eye stacks over the mask square", 0f, mask.top - probe.control(LayerLabels.hide(n)).bottom)
                    assertTrue("$what: the values beside them", values.left >= mask.right - 1f)
                }
            }
            // The rows say what they show, each naming its layer.
            assertTrue(SmokeUi.has(LayerLabels.rowState(2, 100, "Normal", locked = true, alphaLocked = true), exact = true))
            assertTrue(SmokeUi.has(LayerLabels.rowState(4, 100, "Normal", effect = AdjustmentEffects.displayName(tone.adjustment!!)), exact = true))
            probe.assertTouchTargets("$w × $HEIGHT", window, activityWindow)
            probe.assertUniqueLabels("$w × $HEIGHT", window, activityWindow)
            probe.assertUniqueMergedLabels("$w × $HEIGHT", window, activityWindow)
        }
        assertEquals("showing the window records nothing", steps, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "narrow phone")
    }

    private companion object {
        const val WINDOW = "test.layerWindow"

        /** Taller than E's 520 so that all five rows are on screen at once. */
        const val HEIGHT = 600
    }
}
