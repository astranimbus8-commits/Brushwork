package com.brushwork.paint.ui.tools.strip

import com.brushwork.paint.qa18.V18CrossTestKit
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.ShapeTool
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.9 F1a (design 2.3.5, C8) on a 360 × 640 dp phone: every strip of a tool that shows v1.9
 * keys (Eraser, Curve, Polyline, Path, Shape and Text, on the raster and the vector layer, and the
 * v1.8 states of [V18CrossTestKit.states] for them), with every key the tool can show there added
 * ([StripKeysV19Kit.Situation]: the layer, Shape's Points mode, an open text; Forward and
 * Backward always with Duplicate): the More row stays within `StripLayout.MAX_MORE_LINES` lines
 * with the hint as measured (inventory rule 5), at 360 and 392 dp, with and without the VECTOR
 * cell, and no key repeats. Only problems the v1.8 strip does not have count. Printed, not
 * asserted: rules 1 and 2 (row 1's budgets; the areas own their row 1, 2.3.5) and rule 5 with a
 * two-line hint (a hint an area may lengthen; 2.3.5's budget note).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h640dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.tools.strip.keys19sandbox"])
class StripKeysV19Test {
    private val tools = setOf(ToolId.ERASER, ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE, ToolId.TEXT)

    /** A problem without its numbers: the same rule broken at the same place. */
    private fun kind(p: String) = p.replace(Regex("[0-9]+(\\.[0-9]+)?"), "#")

    @Test
    fun everyToolsWorstCaseMoreRowStaysWithinThreeLinesAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val rig = StripRig(panel = false).show()
        val problems = mutableListOf<String>()
        val budgets = mutableListOf<String>()
        val hintNotes = mutableListOf<String>()
        val walked = sortedSetOf<String>()
        try {
            assertEquals("the phone is 360 dp wide", 360, rig.activity.resources.configuration.screenWidthDp)
            val steps = rig.c.undoManager.undoCount
            for (state in V18CrossTestKit.states()) {
                V18CrossTestKit.reset(rig)
                while (rig.c.undoManager.undoCount > steps) rig.c.undo()
                rig.settle()
                try {
                    state.setup(rig)
                } catch (e: Throwable) {
                    if (state.name.substringBefore(',').substringBefore('@') in tools.map { it.name }) problems += "${state.name}: not reached: ${e.message}"
                    continue
                }
                val tool = rig.c.activeToolId
                if (tool !in tools) continue
                val base = rig.spec
                val layer = rig.c.activeLayer
                val situation = StripKeysV19Kit.Situation(
                    onObjects = layer.isVectorLayer || layer.isShapeLayer,
                    pointsMode = (rig.c.currentTool as? ShapeTool)?.pointsMode == true,
                    textOpen = (rig.c.currentTool as? TextTool)?.item != null,
                )
                val worst = StripKeysV19Kit.augmented(base, tool, situation)
                val added = worst.items.map { it.key } - base.items.map { it.key }.toSet()
                walked += state.name
                val measured: (Int) -> Int = { rig.hintLines[it] ?: 1 }
                val twoLines: (Int) -> Int = { StripLayout.MAX_HINT_LINES }
                for ((what, lines) in listOf("measured hint" to measured, "two-line hint" to twoLines)) {
                    val had = StripInventory.rules(base, lines).map(::kind).toSet()
                    for (p in StripInventory.rules(worst, lines)) {
                        if (kind(p) in had) continue
                        val line = "${state.name} + $added ($what): $p"
                        when {
                            p.startsWith("rule 1:") || p.startsWith("rule 2:") -> budgets += line
                            lines === twoLines -> hintNotes += line
                            else -> problems += line
                        }
                    }
                }
                val nonAuto = StripLayout.rowWidth(worst.items.filter { it.row == StripRow.ROW1 && it.overflowRank <= 0 })
                fun more(chip: Boolean): String {
                    val split = StripLayout.split(worst, StripInventory.content(360f) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f)
                    val lines = StripLayout.moreLines(split, worst.hint, StripInventory.content(360f), measured(360))
                    return "${lines.size} lines, items ${split.more.map { "${it.key} ${it.width.value.toInt()}" }}"
                }
                System.err.println(
                    "[strip keys v19] ${state.name} + $added: row 1 non-AUTO $nonAuto dp; hint lines ${measured(360)}; " +
                        "More row at 360 dp: ${more(false)}; with the VECTOR cell: ${more(true)}",
                )
            }
            V18CrossTestKit.reset(rig)
        } finally {
            rig.close()
            dog.interrupt()
        }
        System.err.println("[strip keys v19] ${walked.size} strips walked; row-1 budget notes (not asserted): ${budgets.size}\n" + budgets.joinToString("\n"))
        System.err.println("[strip keys v19] two-line hint notes (not asserted): ${hintNotes.size}\n" + hintNotes.joinToString("\n"))
        for (t in tools) for (vector in listOf(false, true)) {
            val name = t.name + if (vector) "@vector" else ""
            if (name !in walked) problems += "$name: not walked"
        }
        if (walked.size < 28) problems += "only ${walked.size} strips walked: $walked"
        assertTrue("the v1.9 keys keep every More row within 3 lines:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
