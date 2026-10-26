package com.brushwork.paint.v110.a7

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.ui.layers.LayerWindowTags
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.time.Duration
import java.util.Locale

/**
 * v1.10 A7 step 5 (design 2.17's engine proxy for device check Q2, "panel-scroll frame p95 ≤ 17
 * ms"): the layers panel of a 500-layer document scrolled from the top to the bottom and back,
 * [STEP] rows a frame (a fast fling at about 13,000 dp/s), keeps the frame's main-thread time (the
 * scroll, the recomposition of the rows that come into view, layout and the frame's callbacks)
 * within the budget. The measurement is LA1's ×6 proxy: the median of a pass's frames (well over
 * 15) after 3 warm-up passes, with the pass's p95 printed beside it; the phone's p95 is Q2's. On
 * the JVM a pass's p95 is mostly the garbage collector's pauses, not the rows' cost. The rows are
 * the lazy list's, keyed by layer id, so a frame composes only the rows that come into view,
 * whatever the document's layer count.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a7.panelscrollsandbox"])
class PanelScroll500TimingTest {
    @Test
    fun fiveHundredRowsScrollWithinTheFrameBudget() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val doc = Smoke.document(64, 48, layers = LAYERS, whiteBottom = true)
        val c = Smoke.controller(RuntimeEnvironment.getApplication(), doc)
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    LayersPanel(
                        c,
                        onDismiss = {},
                        onImportPicture = {},
                        modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 92.dp).size(382.dp, 520.dp),
                    )
                }
            }
        }
        SmokeUi.settle()
        val list = StripTestKit.tagged(LayerWindowTags.ROWS)
        assertNotNull("the panel's rows", list)
        val scrollTo = list!!.config.getOrNull(SemanticsActions.ScrollToIndex)?.action
        assertNotNull("the rows scroll", scrollTo)
        // The lazy list's items: the two headers, then every layer's row.
        val items = LAYERS + 2
        val path = (0 until items step STEP).toList().let { down -> down + down.asReversed() }
        val looper = shadowOf(Looper.getMainLooper())
        val frame = { index: Int ->
            PerfBudget.timeMs {
                scrollTo!!(index)
                looper.idleFor(Duration.ofMillis(FRAME_MS))
            }
        }
        // Whole passes down and back: a few frames leave the rows' code cold (the first passes ran
        // 4 to 35 ms at p95, then 1.4 to 2.1 ms whether the document held 40 layers or 500).
        repeat(WARM_UPS) { path.forEach { frame(it) } }
        val budget = PerfBudget.ms(BUDGET_MS)
        // Each try is one pass; its median frame is the proxy (LA1), its p95 is reported with it.
        val (median, p95) = PerfBudget.until(
            what = "a panel-scroll frame at 500 layers (median of a pass)",
            measure = {
                val times = path.map(frame).sorted()
                assertTrue("at least $MIN_FRAMES frames", times.size >= MIN_FRAMES)
                times[times.size / 2] to times[(times.size * 95 + 99) / 100 - 1]
            },
            failure = { (m, p) ->
                if (m <= budget) null else String.format(Locale.ROOT, "median %.3f ms (p95 %.3f ms) over the budget of %.3f ms", m, p, budget)
            },
        )
        assertEquals("still 500 layers", LAYERS, doc.layers.size)
        println(
            String.format(
                Locale.ROOT,
                "PanelScroll500TimingTest: PC median %.3f ms of %d frames after %d warm-up passes (p95 %.3f ms); x6 = %.3f ms; budget %.3f ms",
                median, path.size, WARM_UPS, p95, median * 6, BUDGET_MS,
            ),
        )
    }

    private companion object {
        const val LAYERS = 500

        /** Rows a frame: 4 rows of about 56 dp in 16 ms. */
        const val STEP = 4
        const val FRAME_MS = 16L

        /** Whole passes down and back before the measured one. */
        const val WARM_UPS = 3
        const val MIN_FRAMES = 15

        /** Device check Q2's 17 ms frame over the ×6 proxy. */
        const val BUDGET_MS = 17.0 / 6
    }
}
