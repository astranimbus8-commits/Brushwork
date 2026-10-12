package com.brushwork.paint.ui.vector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.CurveStripKit.back
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Modifier

/**
 * v1.8 E (I10, design §4.8 rule 4 and R15): the substring rule on the curve tools' actual screens.
 * On each screen below, no handle or visible text that v1.8 adds is a substring of another handle
 * or visible text, nor has one inside it (`SmokeUi.matches` is a case-sensitive `contains` over
 * both, so such a pair makes a finder match the wrong control); equal texts on two controls count.
 * Texts with a digit are values ("5 px", "Order 4"), not labels, and are left out; a slider's name
 * and its typing affordance (the shared slider's [typing] label) are one control's two parts and
 * do not count. The screens, at
 * 360 dp on the raster layer:
 * - Curve and Path with nothing open, the More row open; the [CurveGenLabels18.CURVES] dropdown open;
 * - a generated Curve and a generated Path with the More row open (the chip);
 * - the generator sheet over the Curve strip, for each of the six kinds;
 * - Curve and Path open with mirror copies and a point selected, the More row open
 *   ([MirrorLabels18.REMOVE]).
 * The dropdown is reached by its description [CurveGenLabels18.CURVES_DESC], which one control
 * carries on every screen it shows on (R15: "Curves" holds "Curve", the tool's name).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.curveslabelssandbox"])
class CurvesLabelsTest {

    /** The `const val` Strings of [o]. */
    private fun strings(o: Any): List<String> = o.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { f -> f.isAccessible = true; f.get(null) as String }

    /** The texts v1.8 adds that the curve screens can show. */
    private val v18: Set<String> by lazy { listOf(CurveGenLabels18, MirrorLabels18, StripLabels18).flatMap(::strings).toSet() }

    /** R15, the one known pair: the dropdown's visible text holds the tool's name (tests use its description). */
    private val allowed = setOf("Curve" to CurveGenLabels18.CURVES)

    /**
     * The shared slider's ([com.brushwork.paint.ui.common.LabeledSlider]) typing affordance for the
     * slider [label]: one control's two parts (its name and how to type it), as every slider of the
     * app since v1.5, reached exact; not a clash of two controls.
     */
    private fun typing(label: String) = "Type a value for $label"

    /**
     * v1.8 polish (accessibility): the shared slider is now named "[label] slider" for a screen
     * reader (it had no name); like [typing], the same control as its label, not a clash.
     */
    private fun slider(label: String) = "$label slider"

    private fun SemanticsNode.labels(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(config.getOrNull(SemanticsActions.OnClick)?.label)

    /** (node id, label) of every placed node of every window, values left out. */
    private fun shown(): List<Pair<Int, String>> = RobolectricUi.elements().map { it.node }
        .filter { it.layoutInfo.isPlaced }
        .flatMap { n -> n.labels().filter { it.isNotBlank() && it.none(Char::isDigit) }.distinct().map { n.id to it } }

    /** The pairs (a, b) on screen now, a inside b, on two nodes, one of them a v1.8 text. */
    private fun clashes(): List<Pair<String, String>> {
        val s = shown()
        val out = mutableListOf<Pair<String, String>>()
        for ((i, a) in s.withIndex()) for ((j, b) in s.withIndex()) {
            if (i == j || a.first == b.first) continue
            if (!b.second.contains(a.second)) continue
            if (a.second !in v18 && b.second !in v18) continue
            if (a.second to b.second in allowed) continue
            if (b.second == typing(a.second) || b.second == slider(a.second)) continue
            out += a.second to b.second
        }
        return out.distinct()
    }

    private fun assertScreen(screen: String) {
        val all = shown().map { it.second }.distinct()
        assertTrue("$screen: shows a v1.8 text (${all.take(40)})", all.any { it in v18 })
        assertEquals("$screen: a v1.8 text inside another, or one inside it (shown: $all)", emptyList<Pair<String, String>>(), clashes())
    }

    /** How many controls carry [label] exactly. */
    private fun carriers(label: String): Int = shown().filter { it.second == label }.map { it.first }.distinct().size

    private fun tap(rig: StripRig, x: Float, y: Float) {
        StripTestKit.closeMoreRow()
        rig.c.pointerDown(ToolPoint(x, y))
        rig.c.pointerUp(ToolPoint(x, y))
        rig.settle()
    }

    @Test
    fun theCurveScreensKeepTheSubstringRule() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        try {
            for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
                rig.select(id)
                val t = rig.c.tools.getValue(id) as CurveTool
                val name = id.name

                // Nothing open: the dropdown is the one control known as CURVES_DESC.
                assertEquals("$name: one control known as \"${CurveGenLabels18.CURVES_DESC}\"", 1, carriers(CurveGenLabels18.CURVES_DESC))
                assertTrue(StripTestKit.openMoreRow())
                assertScreen("$name, nothing open, the More row open")
                StripTestKit.closeMoreRow()
                SmokeUi.click(CurveGenLabels18.CURVES_DESC, exact = true)
                rig.settle()
                assertTrue("$name: the dropdown opened", SmokeUi.has(CurveGenLabels18.FERMAT, exact = true))
                assertScreen("$name, the Curves dropdown")
                SmokeUi.click(CurveGenLabels18.ROSE, exact = true)
                rig.settle()

                // Generated: the chip, the one control known as CHANGE.
                assertEquals(1, carriers(CurveGenLabels18.CHANGE).let { if (it == 0) { StripTestKit.openMoreRow(); carriers(CurveGenLabels18.CHANGE) } else it })
                StripTestKit.openMoreRow()
                assertScreen("$name, a generated rose, the More row open")
                StripTestKit.closeMoreRow()
                t.discard()
                rig.settle()
            }

            // The generator sheet over the Curve strip, for each kind.
            rig.select(ToolId.CURVE)
            val curve = rig.c.tools.getValue(ToolId.CURVE) as CurveTool
            for (kind in CURVES_MENU) {
                assertTrue(curve.startGenerator(kind, null))
                rig.settle()
                SmokeUi.click(CurveGenLabels18.CHANGE, exact = true)
                rig.settle()
                assertTrue("${kind.label}: the sheet", SmokeUi.has(CurveGenLabels18.POINTS_PER_TURN, exact = true))
                assertScreen("the ${kind.label} sheet over the Curve strip")
                back(rig)
                curve.discard()
                rig.settle()
            }

            // Mirror copies: "Remove mirror copies" on the More row, with a point selected.
            rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
            for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
                rig.select(id)
                val t = rig.c.tools.getValue(id) as CurveTool
                tap(rig, 60f, 70f)
                tap(rig, 110f, 100f)
                tap(rig, 150f, 80f)
                t.select(1)
                rig.settle()
                assertTrue("${id.name}: the open path has copies", t.hasCopies)
                assertTrue(StripTestKit.openMoreRow())
                assertTrue("${id.name}: \"${MirrorLabels18.REMOVE}\"", SmokeUi.has(MirrorLabels18.REMOVE, exact = true))
                assertScreen("${id.name} open with copies, a point selected, the More row open")
                StripTestKit.closeMoreRow()
                t.discard()
                rig.settle()
            }
            rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.OFF))
            rig.select(ToolId.BRUSH)
        } finally {
            StripTestKit.closeMoreRow()
            rig.close()
        }
    }
}
