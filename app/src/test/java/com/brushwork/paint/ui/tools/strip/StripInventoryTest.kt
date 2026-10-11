package com.brushwork.paint.ui.tools.strip

import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 strip inventory (design §3.9 rules 1 to 5, I18, R17): the pure rules a converted spec
 * must keep, the handles a strip shows, and the inspection of one tool state.
 */
internal object StripInventory {

    /** Row 1's content width on a screen [screenDp] wide: less the panel's 2 × 6 dp and its 2 × 8 dp padding. */
    fun content(screenDp: Float) = screenDp - 2 * 6f - 2 * 8f

    private const val TOGGLE = StripLayout.TOGGLE_DP + StripLayout.GAP_DP
    private const val CHIP = StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP
    private const val EPS = 0.01f

    /** Rule 1's budgets for the non-AUTO items: a tool with a More row (240) and without (288). */
    const val WITH_MORE = 240f
    const val WITHOUT_MORE = 288f

    /**
     * The rules a converted [spec] breaks, pure (I18): 1 the non-AUTO sum, and every configuration
     * (360 or 392 dp, VECTOR cell or not) fits; 2 no AUTO item leaves row 1 at 392 dp without the
     * cell; 4 declared widths rounded to 4 dp; 5 the More row within [StripLayout.MAX_MORE_LINES]
     * lines; plus unique keys and `movedInto` naming items.
     */
    fun rules(spec: StripSpec): List<String> {
        val p = mutableListOf<String>()
        val row1 = spec.items.filter { it.row == StripRow.ROW1 }
        val hasMore = spec.items.any { it.row == StripRow.MORE } || spec.hint != null || row1.any { it.overflowRank > 0 }
        val nonAuto = StripLayout.rowWidth(row1.filter { it.overflowRank <= 0 })
        val budget = if (hasMore) WITH_MORE else WITHOUT_MORE
        if (nonAuto > budget + EPS) p += "rule 1: row 1's non-AUTO items take $nonAuto dp, over $budget"
        for (screen in listOf(360f, 392f)) for (chip in listOf(false, true)) {
            val available = content(screen) - if (chip) CHIP else 0f
            val s = StripLayout.split(spec, available)
            val used = StripLayout.rowWidth(s.row1) + if (s.toggle) TOGGLE else 0f
            val where = "${screen.toInt()} dp${if (chip) " with the VECTOR cell" else ""}"
            if (used > available + EPS) p += "rule 1: at $where row 1 takes $used dp, over $available"
            val lines = StripLayout.moreLines(s, spec.hint, content(screen))
            lines.forEachIndexed { i, line ->
                if (i == 0 && spec.hint != null && line.isNotEmpty()) p += "rule 5: at $where the hint's line holds items (over ${StripLayout.MAX_MORE_LINES} lines)"
                val w = StripLayout.rowWidth(line)
                if (w > content(screen) + EPS) p += "rule 5: at $where More line ${i + 1} takes $w dp, over ${content(screen)} (over ${StripLayout.MAX_MORE_LINES} lines)"
            }
        }
        val wide = StripLayout.split(spec, content(392f))
        val left = wide.more.filter { it.row == StripRow.ROW1 }
        if (left.isNotEmpty()) p += "rule 2: at 392 dp without the VECTOR cell ${left.map { it.key }} leave row 1"
        for (item in spec.items) {
            if (item.width.value % 4f != 0f) p += "rule 4: \"${item.key}\" is declared ${item.width.value} dp, not rounded up to 4 dp"
        }
        val keys = spec.items.map { it.key }
        if (keys.toSet().size != keys.size) p += "keys repeat: $keys"
        for ((handle, key) in spec.movedInto) if (key !in keys) p += "movedInto: \"$handle\" names no item \"$key\""
        return p
    }

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    fun isControl(n: SemanticsNode): Boolean =
        n.config.getOrNull(SemanticsActions.OnClick) != null ||
            n.config.getOrNull(SemanticsProperties.ToggleableState) != null ||
            n.config.getOrNull(SemanticsActions.SetProgress) != null ||
            n.config.getOrNull(SemanticsActions.SetText) != null

    private fun underStrip(n: SemanticsNode): Boolean =
        generateSequence(n) { it.parent }.any { it.tag() == ChromeTags.OPTIONS_STRIP || it.tag() == V18Tags.STRIP_MORE_ROW }

    /** A control's handle: its description, else a description inside it, else its text, else a text inside it. */
    fun handle(n: SemanticsNode): String? {
        fun desc(m: SemanticsNode) = m.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty().firstOrNull { it.isNotBlank() }
        fun text(m: SemanticsNode) = m.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }.firstOrNull { it.isNotBlank() }
        fun below(m: SemanticsNode, f: (SemanticsNode) -> String?): String? {
            for (ch in m.children) {
                if (isControl(ch)) continue
                f(ch)?.let { return it }
                below(ch, f)?.let { return it }
            }
            return null
        }
        return desc(n) ?: below(n, ::desc) ?: text(n) ?: below(n, ::text) ?: n.config.getOrNull(SemanticsActions.OnClick)?.label
    }

    /** The controls on the strip now (row 1 and, when open, the More row), but its toggle. */
    fun controls(): List<SemanticsNode> = RobolectricUi.elements().map { it.node }.filter { n ->
        n.layoutInfo.isPlaced && isControl(n) && n.tag() != V18Tags.STRIP_TOGGLE && underStrip(n)
    }

    fun handlesShown(): Set<String> = controls().mapNotNull(::handle).toSortedSet()

    /** [record] less the removed duplicates that [shown] does not offer (a handle of [StripHandlesV17.SAME] counts under either name). */
    fun missing(record: Set<String>, shown: Set<String>): List<String> = (record - StripHandlesV17.REMOVED).filter { h ->
        h !in shown && StripHandlesV17.SAME[h]?.let { it in shown } != true
    }

    private fun clickNode(n: SemanticsNode, rig: StripRig) {
        var m: SemanticsNode? = n
        while (m != null && m.config.getOrNull(SemanticsActions.OnClick) == null) m = m.parent
        m?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        rig.settle()
    }

    /** Closes a popup or dialog window over the activity (Back to the top window). */
    private fun dismissTopWindow(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    /**
     * Inspects the state the rig shows as [key] against every rule: [rules], its items' own widths
     * (measuring copies: no wider than declared, 40 dp at least when tappable) and [record] covered
     * by row 1, the More row and the `movedInto` items' popups. Returns the handles shown and the
     * problems found.
     */
    fun inspect(rig: StripRig, key: String, record: Set<String>?): Pair<Set<String>, List<String>> {
        val spec = rig.spec
        val p = mutableListOf<String>()
        rules(spec).forEach { p += "$key: $it" }
        // Rule 4: each item's own width.
        rig.measuring = true
        rig.settle()
        val density = rig.activity.resources.displayMetrics.density
        for (item in spec.items) {
            val px = rig.measured[item.key]
            if (px == null) {
                p += "$key: \"${item.key}\" was not measured"
                continue
            }
            val dp = px / density
            if (dp > item.width.value + 0.5f) p += "$key: rule 4: \"${item.key}\" is $dp dp wide, declared ${item.width.value}"
            val box = RobolectricUi.elements().map { it.node }.lastOrNull { it.tag() == StripRig.MEASURE_PREFIX + item.key }
            val tappable = box != null && generateSequence(listOf(box)) { l -> l.flatMap { it.children }.ifEmpty { null } }.flatten().any { isControl(it) }
            if (tappable && item.width.value < 40f - EPS) p += "$key: rule 4: \"${item.key}\" is tappable and declared ${item.width.value} dp, under 40"
        }
        rig.measuring = false
        rig.settle()
        // Rule 5: row 1, the More row, and what the movedInto items open.
        val shown = handlesShown().toMutableSet()
        StripTestKit.openMoreRow()
        shown += handlesShown()
        for ((handle, itemKey) in spec.movedInto) {
            val item = spec.items.firstOrNull { it.key == itemKey } ?: continue
            StripTestKit.openMoreRow()
            val n = controls().lastOrNull { handle(it) == item.label }
            if (n == null) {
                p += "$key: movedInto: no control \"${item.label}\" (item \"$itemKey\") on the strip"
                continue
            }
            clickNode(n, rig)
            if (SmokeUi.has(handle, exact = true)) shown += handle else p += "$key: movedInto: \"$handle\" is not inside \"${item.label}\""
            dismissTopWindow(rig)
        }
        StripTestKit.closeMoreRow()
        if (record != null) missing(record, shown).forEach { p += "$key: rule 5: \"$it\" is on neither row nor inside an item" }
        return shown to p
    }
}

/** The pure rules of [StripInventory] on fake specs (JVM). */
class StripInventoryRulesTest {
    private fun item(key: String, w: Float, row: StripRow = StripRow.ROW1, rank: Int = 0) =
        StripItem(key = key, label = key, width = w.dp, row = row, overflowRank = rank) {}

    @Test
    fun aSpecThatKeepsTheRulesPasses() {
        val spec = StripSpec(
            listOf(item("a", 96f), item("b", 64f), item("c", 72f, rank = 1), item("m1", 40f, StripRow.MORE), item("m2", 104f, StripRow.MORE)),
            hint = "Probe hint",
            movedInto = mapOf("Inside" to "m2"),
        )
        assertEquals(emptyList<String>(), StripInventory.rules(spec))
        assertEquals(emptyList<String>(), StripInventory.rules(StripSpec.EMPTY))
        // Without a More row: 288 dp of non-AUTO items.
        assertEquals(emptyList<String>(), StripInventory.rules(StripSpec(listOf(item("a", 140f), item("b", 144f)))))
    }

    @Test
    fun eachRuleIsCaught() {
        fun broken(spec: StripSpec, rule: String) {
            val p = StripInventory.rules(spec)
            assertTrue("$rule expected in $p", p.any { it.startsWith(rule) })
        }
        // 1: 244 dp of non-AUTO items with a More row; 292 without.
        broken(StripSpec(listOf(item("a", 120f), item("b", 120f), item("m", 40f, StripRow.MORE))), "rule 1")
        broken(StripSpec(listOf(item("a", 144f), item("b", 144f))), "rule 1")
        // 2: an AUTO item that leaves even at 392 dp.
        broken(StripSpec(listOf(item("a", 200f), item("b", 120f, rank = 1), item("m", 40f, StripRow.MORE))), "rule 2")
        // 4: a width not rounded to 4 dp.
        broken(StripSpec(listOf(item("a", 42f))), "rule 4")
        // 5: more than three lines of More items.
        broken(StripSpec(listOf(item("a", 40f)) + (1..13).map { item("m$it", 100f, StripRow.MORE) }), "rule 5")
        // movedInto names an item.
        broken(StripSpec(listOf(item("a", 40f)), movedInto = mapOf("x" to "nope")), "movedInto")
    }

    @Test
    fun missingHandlesLeaveOutTheRemovedDuplicates() {
        val record = setOf("Brush size", "Undo last point", "Snap")
        assertEquals(listOf("Brush size"), StripInventory.missing(record, setOf("Snap to objects")))
        assertEquals(emptyList<String>(), StripInventory.missing(record, setOf("Brush size", "Snap")))
    }
}

/**
 * v1.8 inventory (design §3.9, §4.9): every tool state, on the raster and on the vector layer, at
 * 360 dp: each keeps rules 1 to 5, and its handles (row 1, the More row and the `movedInto`
 * items' popups) still include the v1.7 record ([StripHandlesV17], recorded at F3). The test first
 * runs its own inspection on two fake converted specs (one that keeps the rules, with a handle
 * moved into a dropdown, one whose item is wider than declared), so the machinery the areas meet
 * is known to work.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.inventorysandbox"])
class StripInventoryTest {

    @Composable
    private fun ProbeButton(label: String, width: Float, onClick: () -> Unit) {
        Box(Modifier.size(width.dp, 40.dp).clickable(onClick = onClick).semantics { contentDescription = label })
    }

    @Test
    fun everyToolStateKeepsTheInventory() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        val problems = mutableListOf<String>()
        try {
            // The machinery, on fake converted specs.
            var menuOpen by mutableStateOf(false)
            val good = StripSpec(
                items = listOf(
                    StripItem("one", "Probe one", 64.dp) { ProbeButton("Probe one", 64f) {} },
                    StripItem("two", "Probe two", 40.dp, row = StripRow.MORE) { ProbeButton("Probe two", 40f) {} },
                    StripItem("menu", "Probe menu", 56.dp, row = StripRow.MORE) {
                        Box {
                            ProbeButton("Probe menu", 56f) { menuOpen = true }
                            DropdownMenu(expanded = menuOpen, onDismissRequest = { menuOpen = false }) {
                                DropdownMenuItem(text = { Text("Probe inside") }, onClick = { menuOpen = false })
                            }
                        }
                    },
                ),
                movedInto = mapOf("Probe inside" to "menu"),
            )
            rig.override = good
            rig.settle()
            val (fakeShown, fakeProblems) = StripInventory.inspect(rig, "fake", setOf("Probe one", "Probe two", "Probe menu", "Probe inside", "Undo last point"))
            assertEquals("the fake spec keeps the rules", emptyList<String>(), fakeProblems)
            assertTrue("the More row and the dropdown were reached: $fakeShown", fakeShown.containsAll(setOf("Probe two", "Probe inside")))
            assertTrue(!menuOpen && !rig.state.anyOpen)
            rig.override = StripSpec(listOf(StripItem("wide", "Probe wide", 40.dp) { ProbeButton("Probe wide", 80f) {} }))
            rig.settle()
            val (_, wideProblems) = StripInventory.inspect(rig, "fake wide", null)
            assertTrue("an item wider than declared is caught: $wideProblems", wideProblems.any { "rule 4" in it && "wide" in it })
            rig.override = null
            rig.settle()

            // Every tool state.
            for (vector in listOf(false, true)) for (id in ToolId.entries) {
                val key = id.name + if (vector) "@vector" else ""
                rig.select(id, vector)
                if (rig.c.activeToolId != id) {
                    problems += "$key: the tool was not selected"
                    continue
                }
                val (_, p) = StripInventory.inspect(rig, key, StripHandlesV17.HANDLES[key])
                problems += p
            }
            for (k in StripHandlesV17.HANDLES.keys) {
                // Every state is inspected above; a recorded state must still exist.
                val exists = ToolId.entries.any { it.name == k.removeSuffix("@vector") }
                if (!exists) problems += "$k: recorded, but no such tool state"
            }
            rig.select(ToolId.BRUSH)
        } finally {
            rig.close()
        }
        assertTrue("the strip inventory:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
