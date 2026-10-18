package com.brushwork.paint.v19.a3

import android.content.Context
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextPathNodeEditor
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.KernLabels19
import com.brushwork.paint.ui.common.LayerLabels19
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.placement.TextStripFacts
import com.brushwork.paint.ui.placement.TextStripItems
import com.brushwork.paint.ui.placement.textSpec
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripKeysV19
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.vector.select.ObjectEdits
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.reflect.Modifier

/**
 * v1.9 A3 step 11 (design 3.2 A3, §3.9 rules 1 to 5, I10): the Text strip in every state, pure
 * ([textSpec] for [TextStripFacts]; no composition, so not one of A3's Robolectric UI classes).
 * - Every state keeps the lead's rules ([StripInventory.rules]) at 392 and 360 dp, with and
 *   without the VECTOR cell, with a one-line and a two-line hint.
 * - While the selection bar or the object bar shows, Duplicate, Bring forward and Send backward
 *   leave the More row (the bars carry them); nothing else changes.
 * - Label scan: no label repeats on the strip, and none holds another (the case-sensitive
 *   `contains` of SmokeUi, LabelsV18Test rule 4), within the strip and its hint, against the bars
 *   beside it (the selection bar's and object bar's labels, the node bar's while points are
 *   edited) and against the Text sheets' labels (TextLabels19, KernLabels19). Rule L (no label
 *   retyped as a literal) is `LabelsV19Test`'s over every main source.
 */
@RunWith(RobolectricTestRunner::class)
class TextStripLabelScanTest {

    private val context: Context = ApplicationProvider.getApplicationContext()

    private val tool: TextTool by lazy {
        val d = Document("t", "t", 320, 240).also { it.layers += Layer(it.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(320, 240)) }
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, d, kotlinx.coroutines.CoroutineScope(kotlinx.coroutines.Dispatchers.Unconfined), settings)
        c.viewTransform.set(Matrix())
        c.snapping.enabled = false
        c.selectTool(ToolId.TEXT)
        c.tools.getValue(ToolId.TEXT) as TextTool
    }

    private val bools = listOf(false, true)

    private companion object {
        /** How [StripInventory.rules] names the configurations with the VECTOR cell. */
        const val WITH_THE_CELL = "with the VECTOR cell"
    }

    /** Every consistent state: nothing open (the switch, an active text layer or not), or a text open in every combination. */
    private val states: List<TextStripFacts> = buildList {
        for (vertical in bools) for (active in bools) for (bar in bools) {
            add(TextStripFacts(vertical = vertical, activeText = active, barShown = bar))
        }
        for (onPath in bools) for (nodes in if (onPath) bools else listOf(false)) for (vertical in bools) for (wrapOn in bools)
            for (boxed in bools) for (arrange in bools) for (active in bools) for (bar in bools) {
                add(TextStripFacts(true, onPath, nodes, vertical, wrapOn, boxed, arrange, active, bar))
            }
    }

    private fun spec(f: TextStripFacts): StripSpec = textSpec(tool, f)

    private val objectKeys = listOf(StripKeysV19.DUPLICATE, StripKeysV19.FORWARD, StripKeysV19.BACKWARD).map { it.key }
    private val objectLabels = listOf(LayerLabels19.DUPLICATE, ObjectEdits.Arrange.FORWARD.label, ObjectEdits.Arrange.BACKWARD.label)

    /** The labels of the selection bar and the object bar that a strip could share (the object bar's arrange items, the selection bar's v1.7 and v1.9 items). */
    private val barLabels = objectLabels + listOf(ArrayLabels.BUTTON, SavedSelectionLabels.SAVE_BUTTON)

    /** The node bar's labels and hint, shown while the path's points are edited. */
    private val nodeBar = listOf(
        TextPathNodeEditor.ADD_POINT_STEP, TextPathNodeEditor.DELETE_POINT_STEP, TextPathNodeEditor.SHARP_CORNER_STEP,
        TextLabels19.POINT_COUNT, TextLabels19.DONE_POINTS, TextLabels19.NODES_HINT,
    )

    /** The String constants of a labels object (its `const val`s). */
    private fun constants(o: Any): List<String> = o.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { it.isAccessible = true; it.get(null) as String }

    private val sheetLabels: List<String> by lazy { constants(TextLabels19) + constants(KernLabels19) }

    /**
     * The VECTOR cell shows only while the active layer is a vector layer (`EditorController.isVectorMode`).
     * An open text of a text layer ([TextStripFacts.arrange]: one of several) has that layer active
     * ([theFactsFollowTheTool] shows it), so the rules' configurations with the cell do not occur there.
     */
    private fun cellPossible(f: TextStripFacts) = !(f.open && f.arrange)

    @Test
    fun everyStateKeepsTheLeadsRules() {
        assertEquals(8 + 3 * 64, states.size)
        val problems = mutableListOf<String>()
        val leftOut = sortedSetOf<String>()
        for (f in states) {
            val spec = spec(f)
            assertNotNull("$f: the sheets and dialogs are overlays", spec.overlays)
            for (lines in 1..2) for (p in StripInventory.rules(spec) { lines }) {
                if (!cellPossible(f) && p.contains(WITH_THE_CELL)) leftOut += "a hint of $lines lines: $p" else problems += "$f, a hint of $lines lines: $p"
            }
        }
        assertTrue("the rules:\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
        // What is left out: a two-line hint at 360 dp with the cell, where Wrap, the three object
        // items, Snap and Numbers would take one 388 dp line.
        assertEquals(sortedSetOf("a hint of 2 lines: rule 5: at 360 dp $WITH_THE_CELL More line 3 takes 388.0 dp, over 332.0 (over 3 lines)"), leftOut)
    }

    @Test
    fun theBarsTakeTheObjectItemsAndNothingElse() {
        var shown = 0
        for (f in states.filter { !it.barShown }) {
            val alone = spec(f).items
            val withBars = spec(f.copy(barShown = true)).items
            assertTrue("$f with the bars: ${withBars.map { it.key }}", withBars.none { it.key in objectKeys || it.label in objectLabels })
            assertEquals("$f: the bars take the object items only", alone.filter { it.key !in objectKeys }.map { it.key }, withBars.map { it.key })
            val objects = alone.filter { it.key in objectKeys }
            val expected = when {
                !f.open || f.nodes -> emptyList()
                f.arrange -> objectKeys
                else -> objectKeys.take(1)
            }
            assertEquals("$f: the object items without the bars", expected, objects.map { it.key })
            assertTrue("$f: in the More row", objects.all { it.row == StripRow.MORE })
            if (objects.isNotEmpty()) shown++
        }
        assertTrue("states with object items: $shown", shown >= 32)
    }

    /** The strip as the editor builds it now ([TextStripFacts.of]) matches its facts. */
    @Test
    fun theFactsFollowTheTool() {
        val c = tool.controller
        val closed = TextStripFacts.of(tool, activeText = false, barShown = false)
        assertEquals(TextStripFacts(), closed)
        tap(100f, 100f)
        assertTrue(tool.editorOpen)
        tool.setText("Facts")
        tool.confirmEditor()
        val open = TextStripFacts.of(tool, activeText = false, barShown = true)
        assertTrue(open.open)
        assertFalse(open.onPath || open.nodes || open.arrange || open.wrapOn)
        assertTrue(open.barShown)
        assertEquals("the wrap label of the tool and of its facts", TextStripItems.wrapLabel(tool), TextStripItems.wrapLabel(open))
        assertTrue(spec(open).items.none { it.key in objectKeys })
        assertTrue(spec(open.copy(barShown = false)).items.any { it.key == StripKeysV19.DUPLICATE.key })
        assertTrue(tool.commitItem())

        // A second text joins the layer; a tap opens the first one: the arrange items apply, and
        // the active layer is that text layer (no VECTOR cell: [cellPossible]).
        tap(240f, 180f)
        tool.setText("More")
        tool.confirmEditor()
        assertTrue(tool.commitItem())
        val layer = c.activeLayer
        assertEquals(listOf("Facts", "More"), TextCodec.decodeAll(layer.textData).map { it.text })
        tap(110f, 100f)
        assertEquals(0, tool.openIndex)
        val arranged = TextStripFacts.of(tool, activeText = c.activeLayer.isTextLayer, barShown = false)
        assertTrue(arranged.open && arranged.arrange && arranged.activeText)
        assertFalse("no VECTOR cell", c.isVectorMode)
        assertFalse(cellPossible(arranged))
        assertEquals(objectKeys, spec(arranged).items.filter { it.key in objectKeys }.map { it.key })
        tool.discard()
    }

    private fun tap(x: Float, y: Float) {
        tool.controller.pointerDown(ToolPoint(x, y))
        tool.controller.pointerUp(ToolPoint(x, y))
    }

    @Test
    fun noLabelRepeatsOrHoldsAnother() {
        val problems = sortedSetOf<String>()
        fun holds(a: String, b: String) = a != b && a.isNotEmpty() && b.isNotEmpty() && (a.contains(b) || b.contains(a))
        for (f in states) {
            val spec = spec(f)
            val labels = spec.items.map { it.label }.filter { it.isNotEmpty() }
            if (labels.toSet().size != labels.size) problems += "$f: a label repeats: $labels"
            val texts = labels + listOfNotNull(spec.hint)
            for (i in texts.indices) for (j in i + 1 until texts.size) if (holds(texts[i], texts[j])) problems += "the strip: \"${texts[i]}\" and \"${texts[j]}\""
            val beside = buildList {
                if (f.barShown) addAll(barLabels)
                if (f.nodes) addAll(nodeBar)
                addAll(sheetLabels)
            }
            for (l in texts) for (b in beside) {
                if (l == b) problems += "$f: \"$l\" on the strip and beside it"
                if (holds(l, b)) problems += "\"$l\" on the strip and \"$b\" beside it"
            }
        }
        assertTrue("the label scan:\n" + problems.take(40).joinToString("\n"), problems.isEmpty())
        assertTrue("the sheets' labels read: ${sheetLabels.size}", sheetLabels.size > 40 && TextLabels19.NUMBERING in sheetLabels)
    }
}
