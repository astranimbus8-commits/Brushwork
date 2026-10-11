package com.brushwork.paint.qa18

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.mutableStateOf
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.LocalStripOverride
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import org.robolectric.Robolectric

/**
 * v1.8 (area H): fake options strips for the qa18 chrome tests. [spec] has one 40 dp item on row 1
 * and [spec]'s `more` items on the More row; at 300 dp each takes a line of its own at 360 and 392
 * dp, so `more` = 1, 2, 3 gives that many lines, and more than 3 still gives 3
 * (`StripLayout.MAX_MORE_LINES`, the last line scrolls). Each item is a tappable box described by
 * its label; [taps] counts the taps per label. With `closer` the More row ends with [CLOSER].
 */
internal object FakeStrips {
    const val ROW1 = "Fake row 1 item"

    /** A More item that does what an item opening a sheet does first: `LocalStripContext.current.closeMore()`. */
    const val CLOSER = "Fake sheet opener"

    fun more(k: Int) = "Fake More item $k"

    val taps = HashMap<String, Int>()

    private fun cell(label: String, width: Dp, closes: Boolean = false): @Composable () -> Unit = {
        val context = LocalStripContext.current
        Box(
            Modifier
                .size(width, 40.dp)
                .clickable {
                    if (closes) context.closeMore()
                    taps[label] = (taps[label] ?: 0) + 1
                }
                .semantics { contentDescription = label },
        )
    }

    fun spec(more: Int, moreWidth: Dp = 300.dp, closer: Boolean = false): StripSpec = StripSpec(
        listOf(StripItem("fake.row1", ROW1, 40.dp, content = cell(ROW1, 40.dp))) +
            (1..more).map { k -> StripItem("fake.more$k", more(k), moreWidth, StripRow.MORE, content = cell(more(k), moreWidth)) } +
            (if (closer) listOf(StripItem("fake.closer", CLOSER, 120.dp, StripRow.MORE, content = cell(CLOSER, 120.dp, closes = true))) else emptyList()),
    )
}

/**
 * The editor in a fresh activity, as `ChromeHarness.editor` builds it, with the options strip
 * showing [spec] (through [LocalStripOverride]) instead of the tool's; [spec] can change while it
 * shows. [close] destroys the activity.
 */
internal class FakeStripEditor private constructor(
    private val ctl: org.robolectric.android.controller.ActivityController<ComponentActivity>,
    val screen: ChromeScreen,
    val spec: androidx.compose.runtime.MutableState<StripSpec?>,
) : AutoCloseable {

    override fun close() {
        runCatching { ctl.pause().stop().destroy() }
        runCatching { SmokeUi.settle() }
    }

    companion object {
        fun open(doc: Document, spec: StripSpec?, setup: (EditorController) -> Unit = {}): FakeStripEditor {
            SmokeUi.markBaseline()
            val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
            val activity = ctl.get()
            val c = Smoke.controller(activity, doc)
            setup(c)
            val state = mutableStateOf(spec)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalStripOverride provides state.value) { EditorScreen(c, onExit = {}, onSaveNow = {}) }
                }
            }
            SmokeUi.settle()
            c.tools
            SmokeUi.settle()
            return FakeStripEditor(ctl, ChromeScreen(activity, c), state)
        }
    }
}
