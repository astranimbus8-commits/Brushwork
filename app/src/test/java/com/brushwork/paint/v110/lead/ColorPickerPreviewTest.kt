package com.brushwork.paint.v110.lead

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.ColorDialogActions
import com.brushwork.paint.ui.color.ColorDialogBody
import com.brushwork.paint.ui.color.ColorDialogModel
import com.brushwork.paint.ui.color.ColorPickerDialog
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.color.rememberColorDialogModel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 F0 (R7, C1; lead, frozen): the color picker's live preview. Opening delivers nothing; a
 * change is delivered, conflated (a burst of changes between two frames gives at most two calls,
 * the last one the latest color); going back to the start color is delivered too. Cancel calls
 * onPreview(start) and then onDismiss, in that order, and nothing after; OK calls onPick once and
 * no preview follows it. The real [ColorPickerDialog] (a modal sheet here) is driven through its
 * semantics actions; the conflation is checked on its body with the model in hand.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.v110.lead.previewsandbox"])
class ColorPickerPreviewTest {

    @Before
    fun restartCompose() = SmokeUi.restartUiDispatcher()

    private val start = 0xFF3366CC.toInt()
    private val events = mutableListOf<String>()
    private fun hex(c: Int) = "%08X".format(c)

    private fun clickable(e: RobolectricUi.Element): () -> Unit {
        var n: SemanticsNode? = e.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "not clickable" }.let { a -> { a() } }
    }

    private fun clickText(text: String) = clickable(
        RobolectricUi.elements().last { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true },
    )()

    private var model: ColorDialogModel? = null

    /** The dialog's actions and body without the sheet, with [model] kept for the test. */
    @Composable
    private fun Body(onDone: () -> Unit) {
        val m = rememberColorDialogModel(start, showAlpha = false)
        model = m
        Surface {
            Column(Modifier.verticalScroll(rememberScrollState())) {
                Row { ColorDialogActions(m, onPick = { events += "pick ${hex(it)}" }, onDismiss = { events += "dismiss"; onDone() }) }
                ColorDialogBody(m, onPreview = { events += "preview ${hex(it)}" })
            }
        }
    }

    @Test
    fun previewIsConflatedCancelRestoresAndOkIsOneCall() {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        var phase by mutableIntStateOf(0)
        activity.setContent {
            BrushworkTheme {
                when (phase) {
                    0 -> ColorPickerDialog(
                        start, onPick = { events += "pick ${hex(it)}" }, onDismiss = { events += "dismiss"; phase = 1 },
                        onPreview = { events += "preview ${hex(it)}" },
                    )
                    1 -> Body(onDone = { phase = 2 })
                    else -> {}
                }
            }
        }
        RobolectricUi.settle()
        assertEquals("opening delivers nothing", emptyList<String>(), events)

        // The real dialog: a typed hex is a change, delivered once.
        RobolectricUi.textFields().first().type("FF0000")
        RobolectricUi.settle()
        assertEquals(listOf("preview FFFF0000"), events)
        // Cancel: onPreview(start), then onDismiss; no pick, and nothing after.
        clickText("Cancel")
        RobolectricUi.settle()
        assertEquals(listOf("preview FFFF0000", "preview ${hex(start)}", "dismiss"), events)
        assertEquals(1, phase)

        // The body with its model: conflated bursts.
        events.clear()
        RobolectricUi.settle()
        val m = requireNotNull(model)
        assertEquals("opening delivers nothing", emptyList<String>(), events)
        val burst = listOf(0xFF010203, 0xFF040506, 0xFF070809, 0xFF0A0B0C, 0xFF0D0E0F).map { it.toInt() }
        burst.forEach { m.state.setColor(it) }
        RobolectricUi.settle()
        assertTrue("a burst of 5 changes gives at most 2 calls: $events", events.size in 1..2)
        assertEquals("preview ${hex(burst.last())}", events.last())
        // Back to exactly the start color: delivered (only the unchanged opening is skipped).
        events.clear()
        m.state.setColor(start)
        RobolectricUi.settle()
        assertEquals(listOf("preview ${hex(start)}"), events)
        // A change and OK in the same frame: one onPick, then onDismiss, and no preview after OK.
        events.clear()
        m.state.setColor(0xFF00FF00.toInt())
        clickText("OK")
        RobolectricUi.settle()
        assertEquals(listOf("pick FF00FF00", "dismiss"), events)
        assertEquals(2, phase)
    }
}
