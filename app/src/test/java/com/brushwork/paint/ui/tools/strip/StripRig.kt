package com.brushwork.paint.ui.tools.strip

import android.content.Context
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.material3.Surface
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.SideEffect
import androidx.compose.runtime.derivedStateOf
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clipToBounds
import androidx.compose.ui.layout.Layout
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.dp
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.OptionsStripPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.ToolStrips
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.robolectric.Robolectric

/**
 * v1.8 (F3): the options strip on its own, for the lead's strip tests. A controller over a
 * 400 × 300 document with a raster layer (index 0) and an empty vector layer (index 1), and the
 * active tool's strip with its More-row [state]:
 * - [panel] true: the editor's own panel ([OptionsStripPanel], which hides itself for a tool
 *   without options);
 * - [panel] false: the host as `ToolOptionsBar` calls it, in a 44 dp row tagged
 *   [ChromeTags.OPTIONS_STRIP], showing [override] instead of the tool's spec when set (a test's
 *   fake spec).
 * Beside it a probe keeps the spec the strip shows ([spec]; `ToolStrips.of` with the same vector
 * mode) and, while [measuring], composes every item's content once more, unconstrained in width,
 * each in a box tagged [MEASURE_PREFIX] + its key, to report its own width ([measured], px).
 *
 * Call `SmokeUi.installTestRecomposer()` first (one UI test per class).
 */
internal class StripRig(private val panel: Boolean = true) {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val ctx = ApplicationProvider.getApplicationContext<Context>()

    val doc: Document = Document("t", "t", 400, 300).also { d ->
        d.layers += Layer(d.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(400, 300))
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = 0
    }

    val c: EditorController = EditorController(ctx, doc, scope, AppSettings(ctx).also { it.prefs.edit().clear().commit() }).also { it.tools }

    /** The More row's open state, as `EditorScreen` hoists it. */
    val state = StripState()

    /** The spec the strip shows, as of the last composition. */
    var spec: StripSpec = StripSpec.EMPTY
        private set

    /**
     * The lines [spec]'s hint takes on the More row at 360 and 392 dp (by screen width), measured
     * as the host measures it ([rememberHintLines] at [StripInventory.content]); 1 without a hint.
     */
    var hintLines: Map<Int, Int> = emptyMap()
        private set

    /** A fake spec shown instead of the tool's ([panel] false only). */
    var override: StripSpec? by mutableStateOf(null)

    /** Composes the measuring copies of [spec]'s items while true. */
    var measuring: Boolean by mutableStateOf(false)

    /** Item key to its content's own width (px), from the last measuring pass. */
    val measured = HashMap<String, Int>()

    lateinit var activity: ComponentActivity
        private set

    fun show(): StripRig {
        activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        activity.setContent { BrushworkTheme { Surface { Content() } } }
        settle()
        return this
    }

    @Composable
    private fun Content() {
        val outer = remember { StripContext(selectionBarShown = false, vectorMode = false, closeMore = state::closeAll) }
        val vectorMode by remember { derivedStateOf { c.isVectorMode } }
        val probe = remember(vectorMode) { StripContext(selectionBarShown = false, vectorMode = vectorMode, closeMore = state::closeAll) }
        CompositionLocalProvider(LocalStripContext provides outer) {
            Column {
                if (panel) {
                    OptionsStripPanel(c, state = state)
                    CompositionLocalProvider(LocalStripContext provides probe) {
                        val s = ToolStrips.of(c)
                        val h = hintLinesAtEachWidth(s)
                        SideEffect {
                            spec = s
                            hintLines = h
                        }
                        Measure(s)
                    }
                } else {
                    CompositionLocalProvider(LocalStripContext provides probe) {
                        val s = override ?: ToolStrips.of(c)
                        val h = hintLinesAtEachWidth(s)
                        SideEffect {
                            spec = s
                            hintLines = h
                        }
                        // The panel's 6 dp side margins, as in the editor (row 1's content: the screen less 28 dp).
                        Box(Modifier.padding(horizontal = 6.dp)) {
                            StripHost(
                                spec = s,
                                toolId = c.activeToolId,
                                vectorMode = vectorMode,
                                state = state,
                                modifier = Modifier.fillMaxSize(),
                                panelModifier = Modifier.fillMaxWidth().height(IbisDims.OptionsStripHeight).testTag(ChromeTags.OPTIONS_STRIP),
                            )
                        }
                        Measure(s)
                    }
                }
            }
        }
    }

    @Composable
    private fun hintLinesAtEachWidth(s: StripSpec): Map<Int, Int> {
        val density = androidx.compose.ui.platform.LocalDensity.current
        return WIDTHS.associateWith { w -> rememberHintLines(s.hint, with(density) { StripInventory.content(w.toFloat()).dp.roundToPx() }) }
    }

    /** The measuring copies: each item's content at its own width (44 dp tall at most), out of sight. */
    @Composable
    private fun Measure(s: StripSpec) {
        if (!measuring || s.items.isEmpty()) return
        val maxHeight = with(androidx.compose.ui.platform.LocalDensity.current) { IbisDims.OptionsStripHeight.roundToPx() }
        Box(Modifier.size(0.dp).clipToBounds()) {
            Layout(content = { for (item in s.items) Box(Modifier.testTag(MEASURE_PREFIX + item.key)) { item.content() } }) { ms, _ ->
                val ps = ms.map { it.measure(Constraints(maxHeight = maxHeight)) }
                ps.forEachIndexed { i, p -> measured[s.items[i].key] = p.width }
                layout(0, 0) {
                    var x = 0
                    ps.forEach { p ->
                        p.place(x, 0)
                        x += p.width
                    }
                }
            }
        }
    }

    /** Makes [id] the active tool on the raster layer, or on the vector layer when [vector]. */
    fun select(id: ToolId, vector: Boolean = false) {
        c.selectLayer(if (vector) 1 else 0)
        c.selectTool(id)
        settle()
    }

    fun settle() = RobolectricUi.settle(10, 50)

    fun close() = scope.cancel()

    companion object {
        /** + an item's key: the box of its measuring copy. */
        const val MEASURE_PREFIX = "test.strip.measure."

        /** The screen widths (dp) of [hintLines]. */
        val WIDTHS = listOf(360, 392)
    }
}
