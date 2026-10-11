package com.brushwork.paint.ui.selection

import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.LassoKind
import com.brushwork.paint.tools.select.LassoTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.9 and §5): area C's `*StripTest`. For every selection tool state, on a
 * picture layer and on a vector layer (the VECTOR chip): the spec is converted
 * (`legacyContent == null`); its row 1 and More row hold the §3.9 table's controls (keys, in
 * order); the inventory rules hold; at 360 and 392 dp, with and without the chip, exactly the
 * expected AUTO items leave row 1; every shown item's label is its control's handle on screen
 * (labels unchanged); row 1 and the More row lie on the screen. The More row's "Selection menu"
 * (a sheet) stays open after the row closes; an anchored dropdown opened from the More row stays
 * open with the row.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.stripsandbox"])
class SelectionStripTest {

    /**
     * One tool state: its §3.9 row 1 and More row (item keys), its hint, and the AUTO items that
     * leave row 1 per configuration ("360", "360+chip", "392+chip"; none at 392 dp, rule 2).
     */
    private class State(
        val name: String,
        val tool: ToolId,
        val row1: List<String>,
        val more: List<String>,
        val hint: String? = null,
        val leaving: Map<String, List<String>> = emptyMap(),
        val setUp: (StripRig) -> Unit = {},
    )

    private val shortcuts = listOf("select.deselect", "select.menu")

    private val magneticRow1 = listOf(MODE_KEY, MagneticStrip.DELETE, MagneticStrip.DISCARD, MagneticStrip.CLOSE)
    private val magneticMore =
        listOf(MagneticStrip.REACH, MagneticStrip.CONTRAST, MagneticStrip.SNAP, MagneticStrip.REFER, MagneticStrip.ANTI_ALIAS) + shortcuts

    private fun lasso(rig: StripRig): LassoTool = rig.c.tools.getValue(ToolId.LASSO) as LassoTool

    private fun tapAll(rig: StripRig) {
        for ((x, y) in listOf(80f to 50f, 320f to 50f, 320f to 190f)) {
            rig.c.pointerDown(ToolPoint(x, y))
            rig.c.pointerUp(ToolPoint(x, y))
        }
    }

    private val states = listOf(
        State("bucket", ToolId.FILL, listOf("fill.tolerance", "fill.refer", "fill.gaps"), listOf("fill.expand", "fill.aa")),
        State("eyedropper", ToolId.EYEDROPPER, listOf("eyedropper.sample", "eyedropper.size", "eyedropper.back"), emptyList()),
        State(
            "magic wand", ToolId.MAGIC_WAND, listOf(MODE_KEY, "wand.tolerance", "wand.contiguous"),
            listOf("wand.refer", "wand.aa") + shortcuts,
        ),
        State("freehand lasso", ToolId.LASSO, listOf(MODE_KEY, LASSO_KIND_KEY), listOf("lasso.aa") + shortcuts) {
            lasso(it).setKind(LassoKind.FREEHAND)
        },
        State(
            "polygon lasso", ToolId.LASSO, listOf(MODE_KEY, LASSO_KIND_KEY, "lasso.close", "lasso.discard"),
            listOf("lasso.snap", "lasso.aa") + shortcuts, hint = "Tap corners",
        ) { lasso(it).setKind(LassoKind.POLYGON) },
        State(
            "polygon lasso, 3 corners", ToolId.LASSO, listOf(MODE_KEY, LASSO_KIND_KEY, "lasso.close", "lasso.discard"),
            listOf("lasso.snap", "lasso.aa") + shortcuts,
        ) { lasso(it).setKind(LassoKind.POLYGON); tapAll(it) },
        State(
            "curve lasso, a point selected", ToolId.LASSO,
            listOf(MODE_KEY, LASSO_KIND_KEY, "lasso.close", "lasso.discard", "lasso.sharp", "lasso.delete"),
            listOf("lasso.snap", "lasso.aa") + shortcuts,
            leaving = mapOf("360" to listOf("lasso.sharp"), "360+chip" to listOf("lasso.sharp", "lasso.delete"), "392+chip" to listOf("lasso.sharp")),
        ) {
            val t = lasso(it)
            t.setKind(LassoKind.CURVE)
            tapAll(it)
            it.c.pointerDown(ToolPoint(320f, 50f))
            it.c.pointerLongPress(ToolPoint(320f, 50f))
            it.c.pointerUp(ToolPoint(320f, 50f))
            check(t.curve.selected == 1) { "no curve point selected" }
        },
        State(
            "select shape", ToolId.MARQUEE, listOf(MODE_KEY, "marquee.shape", "marquee.square"),
            listOf("marquee.center", "marquee.snap") + shortcuts,
        ),
        State(
            "object select", ToolId.OBJECT_SELECT, listOf(MODE_KEY, "object.refine", "object.cancel"),
            listOf("object.refer") + shortcuts, hint = "Tap or scribble on an object",
        ),
        State(
            "magnetic lasso", ToolId.MAGNETIC_LASSO, magneticRow1, magneticMore,
        ),
        State("magnetic lasso, 3 anchors", ToolId.MAGNETIC_LASSO, magneticRow1, magneticMore) { tapAll(it) },
    )

    private fun available(screen: Float, chip: Boolean) =
        StripInventory.content(screen) - if (chip) StripLayout.VECTOR_CHIP_DP + StripLayout.GAP_DP else 0f

    /** The pure checks of [spec] in [s]: membership, rules, AUTO leaving per configuration. */
    private fun pure(s: State, where: String, spec: StripSpec): List<String> {
        val p = mutableListOf<String>()
        if (spec.legacyContent != null) return listOf("$where: still legacy")
        val row1 = spec.items.filter { it.row == StripRow.ROW1 }.map { it.key }
        val more = spec.items.filter { it.row == StripRow.MORE }.map { it.key }
        if (row1 != s.row1) p += "$where: row 1 $row1, expected ${s.row1}"
        if (more != s.more) p += "$where: More row $more, expected ${s.more}"
        if (spec.hint != s.hint) p += "$where: hint \"${spec.hint}\", expected \"${s.hint}\""
        p += StripInventory.rules(spec).map { "$where: $it" }
        for (screen in listOf(360f, 392f)) for (chip in listOf(false, true)) {
            val config = "${screen.toInt()}" + if (chip) "+chip" else ""
            val split = StripLayout.split(spec, available(screen, chip))
            val left = split.more.filter { it.row == StripRow.ROW1 }.map { it.key }
            val expected = s.leaving[config].orEmpty()
            if (left != expected) p += "$where at $config: $left leave row 1, expected $expected"
        }
        return p
    }

    /** Every shown item's label is a handle on screen (row 1, then the More row opened); all on the screen. */
    private fun onScreen(where: String, spec: StripSpec, width: Int): List<String> {
        val p = mutableListOf<String>()
        val handles = StripInventory.handlesShown().toMutableSet()
        val opened = StripTestKit.openMoreRow()
        if (opened) handles += StripInventory.handlesShown()
        for (item in spec.items.filter { it.shown }) {
            if (item.label !in handles) p += "$where: \"${item.label}\" (${item.key}) is no handle on screen; shown $handles"
        }
        for (n in StripInventory.controls()) {
            val b = RobolectricUi.elements().firstOrNull { it.node.id == n.id }?.bounds ?: continue
            if (b.left < -1f || b.right > width + 1f) p += "$where: \"${StripInventory.handle(n)}\" at $b, off the $width px screen"
        }
        if (opened) StripTestKit.closeMoreRow()
        return p
    }

    /** Opens the More row's "Selection menu": the row closes, the sheet stays. */
    private fun menuStaysOpen(where: String, rig: StripRig): List<String> {
        val p = mutableListOf<String>()
        SmokeUi.click("Selection menu", exact = true)
        rig.settle()
        if (StripTestKit.moreRowShown()) p += "$where: the More row stays open under the sheet"
        if (!SmokeUi.has("Nothing is selected")) p += "$where: the selection menu did not open"
        rig.settle()
        if (!SmokeUi.has("Nothing is selected")) p += "$where: the selection menu closed with the row"
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        rig.settle()
        return p
    }

    /** An anchored dropdown opened from the More row ("Refer: ..."): it opens and the row stays. */
    private fun dropdownKeepsRow(where: String, rig: StripRig): List<String> {
        val p = mutableListOf<String>()
        val before = SmokeUi.windows().size
        SmokeUi.click("Refer:")
        rig.settle()
        if (SmokeUi.windows().size <= before) p += "$where: \"Refer\" opened no dropdown"
        if (!StripTestKit.moreRowShown()) p += "$where: the dropdown closed the More row"
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        rig.settle()
        StripTestKit.closeMoreRow()
        return p
    }

    private fun run(rig: StripRig, screen: String, full: Boolean): List<String> {
        val problems = mutableListOf<String>()
        val width = rig.activity.resources.displayMetrics.widthPixels
        for (vector in listOf(false, true)) for (s in states) {
            val where = "$screen ${s.name}" + if (vector) " (vector layer)" else ""
            rig.select(s.tool, vector)
            rig.c.deselect()
            s.setUp(rig)
            rig.settle()
            val spec = rig.spec
            problems += pure(s, where, spec)
            if (spec.legacyContent != null) continue
            problems += onScreen(where, spec, width)
            if (full && "select.menu" in s.more) problems += menuStaysOpen(where, rig)
            if (full && s.more.any { it.endsWith(".refer") }) problems += dropdownKeepsRow(where, rig)
            (rig.c.currentTool as? LassoTool)?.let { if (it.hasPendingWork) it.discard() }
            (rig.c.currentTool as? MagneticLassoTool)?.let { if (it.hasPendingWork) it.discard() }
            rig.settle()
        }
        return problems
    }

    @Test
    fun selectionStripsHoldTheirRowsAt360And392() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val problems = mutableListOf<String>()
        SmokeUi.markBaseline()
        val wide = StripRig(panel = false).show()
        try {
            problems += run(wide, "392 dp", full = true)
        } finally {
            wide.close()
            // Its window out of the way: the strip helpers read every window.
            runCatching { wide.activity.windowManager.removeViewImmediate(wide.activity.window.decorView) }
        }
        RuntimeEnvironment.setQualifiers("w360dp-h760dp-xxhdpi")
        SmokeUi.markBaseline()
        val narrow = StripRig(panel = false).show()
        try {
            problems += run(narrow, "360 dp", full = false)
        } finally {
            narrow.close()
        }
        assertTrue("the selection strips:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
