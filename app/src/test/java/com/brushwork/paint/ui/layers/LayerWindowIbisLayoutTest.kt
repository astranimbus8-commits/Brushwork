package com.brushwork.paint.ui.layers

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The ibisPaint layer window at the user's phone size (design §3.7.7, V13): hosted at 382 × 520
 * dp as area E's host sizes it, every part has its measured size and place within ±1 dp — header
 * 46, main block 360 (left column 100 = preview 240 + six 50 × 40 buttons; list 220 = rows
 * bottom-aligned above the 40 dp transparency squares; strip 40 = nine icons at a 40 dp pitch),
 * blend row 56, opacity row 48, bottom pad 10; rows and the Selection Layer row 80, thumbnails
 * 62. Every control is at least 40 × 40 dp, and no two controls shown together share a label, the
 * ⋮ menu's entries included (I10). Then the narrower v1.5 host size and a short, wide window keep
 * those rules.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.layers.ibislayoutsandbox"])
class LayerWindowIbisLayoutTest {

    @Test
    fun theWindowHasIbisPaintsSizesAndPlacement() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        val (bottom, top) = c.doc.layers
        // A mask on the top layer shows its mask square too.
        top.mask = BitmapUtils.createMaskBitmap(400, 300)
        val probe = LayerWindowProbe(activity.resources.displayMetrics.density)
        assertEquals("xxhdpi", 3f, activity.resources.displayMetrics.density, 0f)

        var size by mutableStateOf(382 to 520)
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    // What area E's host does: bottom-left, x = 5, on the bottom bar's top.
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

        // ------------------------------------------------------------ the bands, top to bottom
        val window = probe.tagged(WINDOW)
        probe.assertSize("window", 382f, 520f, window)
        val header = probe.tagged(LayerWindowTags.HEADER)
        probe.assertSize("header", 382f, 46f, header)
        probe.assertDp("header at the top", 0f, header.top - window.top)
        val main = probe.tagged(LayerWindowTags.MAIN)
        probe.assertSize("main block", 382f, 360f, main)
        probe.assertDp("main block under the header", 46f, main.top - window.top)
        val blend = probe.tagged(LayerWindowTags.BLEND)
        probe.assertSize("blend row", 382f, 56f, blend)
        probe.assertDp("blend row under the main block", 0f, blend.top - main.bottom)
        val opacity = probe.tagged(LayerWindowTags.OPACITY)
        probe.assertSize("opacity row", 382f, 48f, opacity)
        probe.assertDp("opacity row under the blend row", 0f, opacity.top - blend.bottom)
        probe.assertDp("bottom pad", 10f, window.bottom - opacity.bottom)

        // ------------------------------------------------------------ the main block, left to right
        val left = probe.tagged(LayerWindowTags.LEFT)
        probe.assertSize("left column", 100f, 360f, left)
        probe.assertDp("left pad", 8f, left.left - window.left)
        val preview = probe.tagged(LayerWindowTags.PREVIEW)
        probe.assertSize("preview", 100f, 240f, preview)
        probe.assertDp("preview at the top", 0f, preview.top - main.top)
        val buttons = probe.tagged(LayerWindowTags.BUTTONS)
        probe.assertSize("buttons pane", 100f, 120f, buttons)
        probe.assertDp("buttons under the preview", 0f, buttons.top - preview.bottom)
        val list = probe.tagged(LayerWindowTags.LIST)
        probe.assertSize("list", 220f, 360f, list)
        probe.assertDp("gap after the left column", 6f, list.left - left.right)
        val rows = probe.tagged(LayerWindowTags.ROWS)
        probe.assertSize("rows", 220f, 320f, rows)
        val squares = probe.tagged(LayerWindowTags.TRANSPARENCY)
        probe.assertSize("transparency squares", 220f, 40f, squares)
        probe.assertDp("squares at the bottom of the list", 0f, list.bottom - squares.bottom)
        val strip = probe.tagged(LayerWindowTags.STRIP)
        probe.assertSize("strip", 40f, 360f, strip)
        probe.assertDp("gap before the strip", 2f, strip.left - list.right)
        probe.assertDp("right pad", 6f, window.right - strip.right)

        // ------------------------------------------------------------ the list: bottom-aligned rows
        val selectionRow = probe.tagged(LayerWindowTags.SELECTION_ROW)
        probe.assertSize("Selection Layer row", 220f, 80f, selectionRow)
        val topRow = probe.tagged(LayerWindowTags.row(top.id))
        val bottomRow = probe.tagged(LayerWindowTags.row(bottom.id))
        probe.assertSize("layer row", 220f, 80f, topRow)
        probe.assertSize("layer row", 220f, 80f, bottomRow)
        probe.assertDp("the bottom layer sits on the squares", 0f, rows.bottom - bottomRow.bottom)
        probe.assertDp("top layer above it", 0f, bottomRow.top - topRow.bottom)
        probe.assertDp("the Selection Layer row first", 0f, topRow.top - selectionRow.bottom)
        probe.assertDp("filler above few rows", 80f, selectionRow.top - rows.top)
        probe.assertSize("thumbnail", 62f, 62f, probe.tagged(LayerWindowTags.thumb(top.id)))
        probe.assertSize("eye", 40f, 40f, probe.control(LayerLabels.hide(2)))
        probe.assertSize("drag handle", 40f, 80f, probe.control(LayerLabels.reorder(2)))
        probe.assertDp("drag handle at the right end", 0f, rows.right - probe.control(LayerLabels.reorder(2)).right)
        probe.assertSize("mask square", 40f, 40f, probe.control(LayerLabels.maskOf(2)))
        // The masked row stacks its eye over its mask square, beside the thumbnail.
        val eye2 = probe.control(LayerLabels.hide(2))
        val mask2 = probe.control(LayerLabels.maskOf(2))
        probe.assertDp("eye over the mask square", 0f, mask2.top - eye2.bottom)
        probe.assertDp("in one column", 0f, mask2.left - eye2.left)
        assertTrue("right of the thumbnail", mask2.left >= probe.tagged(LayerWindowTags.thumb(top.id)).right - 1f)
        // "100%" over "Normal", spoken as one description naming the row.
        assertTrue("100% over Normal", SmokeUi.has(LayerLabels.rowState(2, 100, "Normal"), exact = true))
        assertTrue(SmokeUi.has(LayerLabels.rowState(1, 100, "Normal"), exact = true))
        for (id in listOf(top.id, bottom.id)) {
            val w = probe.dp(probe.tagged(LayerWindowTags.values(id)).width)
            assertTrue("room for \"100%\" / \"Normal\": $w dp", w >= 60f)
        }
        assertTrue(SmokeUi.has("No Selection", exact = true))

        // ------------------------------------------------------------ icons and their places
        val stripIcons = listOf(
            LayerLabels.CLEAR, LayerLabels.MASK, LayerLabels.TRANSFORM, LayerLabels.FLIP_H, LayerLabels.FLIP_V,
            LayerLabels.MERGE, LayerLabels.DELETE, LayerLabels.FILTERS, LayerLabels.MORE,
        )
        stripIcons.forEachIndexed { i, label ->
            val b = probe.control(label)
            probe.assertSize(label, 40f, 40f, b)
            probe.assertDp("$label in the strip", 0f, b.left - strip.left)
            probe.assertDp("$label is icon ${i + 1} of 9", 40f * i, b.top - strip.top)
        }
        val leftButtons = listOf(
            LayerLabels.ADD, LayerLabels.FLIP_CANVAS_H,
            LayerLabels.DUPLICATE, LayerLabels.FLIP_CANVAS_V,
            LayerLabels.IMPORT, LayerLabels.SPECIAL,
        )
        leftButtons.forEachIndexed { i, label ->
            val b = probe.control(label)
            probe.assertSize(label, 50f, 40f, b)
            probe.assertDp("$label column", 50f * (i % 2), b.left - buttons.left)
            probe.assertDp("$label row", 40f * (i / 2), b.top - buttons.top)
        }
        val close = probe.control(LayerLabels.CLOSE)
        probe.assertSize("✕", 40f, 40f, close)
        assertTrue("✕ at the header's right end", close.right <= window.right + 1f && probe.dp(window.right - close.right) < 12f)
        assertTrue("✕ in the header", header.contains(close.center))
        TransparencyDisplay.entries.forEach { t ->
            val b = probe.control(LayerLabels.transparency(t))
            probe.assertSize(LayerLabels.transparency(t), 40f, 40f, b)
            assertTrue("${t.label} in the squares row", squares.contains(b.center))
        }
        for (label in listOf(LayerLabels.CLIPPING, LayerLabels.ALPHA_LOCK, LayerLabels.LOCK)) {
            val b = probe.control(label)
            probe.assertSize(label, 48f, 48f, b)
            assertTrue("$label in the blend row", blend.contains(b.center))
        }
        val dropdown = probe.control(LayerLabels.BLEND)
        probe.assertDp("blend dropdown target", 40f, dropdown.height)
        assertTrue("the dropdown fills the rest of the row: ${probe.dp(dropdown.width)}", probe.dp(dropdown.width) > 180f)
        probe.assertSize("−", 40f, 40f, probe.control(LayerLabels.LESS_OPACITY))
        probe.assertSize("+", 40f, 40f, probe.control(LayerLabels.MORE_OPACITY))
        probe.assertDp("slider target", 40f, probe.control(LayerLabels.OPACITY).height)
        probe.assertDp("typed value", 48f, probe.control(LayerLabels.TYPE_OPACITY).height)
        for (label in listOf(LayerLabels.LESS_OPACITY, LayerLabels.OPACITY, LayerLabels.MORE_OPACITY, LayerLabels.TYPE_OPACITY)) {
            assertTrue("$label in the opacity row", opacity.contains(probe.control(label).center))
        }

        // ------------------------------------------------------------ touch targets and labels
        val activityWindow = SmokeUi.windows().first()
        probe.assertTouchTargets("382 × 520", window, activityWindow)
        val labels = probe.assertUniqueLabels("382 × 520", window, activityWindow)
        // E's screen-wide audit: no text or description on two clickables ("Normal" on every row
        // and on the blend dropdown would be).
        probe.assertUniqueMergedLabels("382 × 520", window, activityWindow)
        // The ⋮ menu is shown together with the window: none of its entries may repeat a label.
        assertMenuLabelsAreNew(probe, activityWindow, labels, LayerLabels.MORE, "⋮")
        // Its mask page too (the strip's "Layer mask" opens the menu there).
        assertMenuLabelsAreNew(probe, activityWindow, labels, LayerLabels.MASK, "mask page")
        // And the special-layer menu.
        assertMenuLabelsAreNew(probe, activityWindow, labels, LayerLabels.SPECIAL, "special layers")

        // ------------------------------------------------------------ the v1.5 host size: stacked
        size = 300 to 436
        SmokeUi.settle()
        val narrow = probe.tagged(WINDOW)
        probe.assertSize("v1.5 window", 300f, 436f, narrow)
        assertFalse("no preview in the narrow window", probe.hasTag(LayerWindowTags.PREVIEW))
        probe.assertDp("one column of buttons", 50f, probe.tagged(LayerWindowTags.LEFT).width)
        probe.assertDp("strip", 40f, probe.tagged(LayerWindowTags.STRIP).width)
        assertEquals("rows stay 80 dp", 80f, probe.taggedHeightDp(LayerWindowTags.row(top.id)), 1f)
        assertEquals("rows stay 80 dp", 80f, probe.taggedHeightDp(LayerWindowTags.row(bottom.id)), 1f)
        probe.assertTouchTargets("300 × 436", narrow, activityWindow)
        probe.assertUniqueLabels("300 × 436", narrow, activityWindow)
        probe.assertUniqueMergedLabels("300 × 436", narrow, activityWindow)

        // ------------------------------------------------------------ short and wide: side by side
        size = 380 to 260
        SmokeUi.settle()
        val wide = probe.tagged(WINDOW)
        probe.assertSize("short window", 380f, 260f, wide)
        assertTrue("side by side", probe.hasTag(LayerWindowTags.CONTROLS))
        val controls = probe.tagged(LayerWindowTags.CONTROLS)
        assertTrue("the list beside the controls", probe.tagged(LayerWindowTags.LIST).right <= controls.left + 1f)
        probe.assertTouchTargets("380 × 260", wide, activityWindow)
        probe.assertUniqueLabels("380 × 260", wide, activityWindow)
        probe.assertUniqueMergedLabels("380 × 260", wide, activityWindow)
        Smoke.assertQuiet(c, "layout")
    }

    /** Opens the menu of [opener], checks its entries against [windowLabels], closes it. */
    private fun assertMenuLabelsAreNew(probe: LayerWindowProbe, activityWindow: android.view.View, windowLabels: Set<String>, opener: String, what: String) {
        SmokeUi.click(opener, exact = true)
        val items = probe.menuItems(activityWindow)
        assertTrue("$what menu opened", items.isNotEmpty())
        val repeated = items.filter { it in windowLabels }
        assertTrue("$what entries repeating a window label: $repeated", repeated.isEmpty())
        val dup = items.groupingBy { it }.eachCount().filterValues { it > 1 }.keys
        assertTrue("$what entries shown twice: $dup", dup.isEmpty())
        // Close it the way a user does: Back, which the menu's popup handles as a dismiss.
        LayerWindowProbe.pressBackOnPopups(activityWindow)
        assertTrue("$what menu closed", probe.menuItems(activityWindow).isEmpty())
    }

    private companion object {
        const val WINDOW = "test.layerWindow"
    }
}
