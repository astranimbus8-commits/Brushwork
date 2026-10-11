package com.brushwork.paint.ui.selection

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.tools.strip.StripRig
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.lang.reflect.Modifier

/**
 * v1.8 (C, design §4.8 rule 4, `<Area>LabelsTest`): on each selection strip at 360 dp (row 1, the
 * More row, and each dropdown or popup its items open), no handle and no visible text is a
 * substring of another where one of the two is a v1.8 text (SmokeUi matches by case-sensitive
 * `contains`). Row 1 and the More row stay on the 360 dp screen.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.selection.labelssandbox"])
class SelectionLabelsTest {

    private fun values(o: Any): List<String> = o.javaClass.declaredFields
        .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
        .map { f -> f.isAccessible = true; f.get(null) as String }

    private val v18: Set<String> = (values(MagneticLabels18) + values(ColorSelectLabels18) + values(StripLabels18)).toSet()

    /** Per tool state: the handles (prefixes) of the items that open a dropdown or popup. */
    private val openers: Map<String, List<String>> = mapOf(
        "FILL" to listOf("Tolerance", "Refer:", "Close gaps", "Expand"),
        "EYEDROPPER" to listOf("Sample:", "Size:"),
        "MAGIC_WAND" to listOf(MagneticLabels18.SELECTION_MODE, "Tolerance", "Refer:"),
        "LASSO" to listOf(MagneticLabels18.SELECTION_MODE, "Freehand lasso"),
        "MARQUEE" to listOf(MagneticLabels18.SELECTION_MODE),
        "OBJECT_SELECT" to listOf(MagneticLabels18.SELECTION_MODE, "Refer:"),
        "MAGNETIC_LASSO" to listOf(MagneticLabels18.SELECTION_MODE, MagneticLabels18.EDGE_REACH, MagneticLabels18.EDGE_CONTRAST, "Refer:"),
    )

    /** Everything a person reads or hears on the placed nodes. */
    private fun shown(): List<String> = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }.flatMap { e ->
        e.node.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            e.node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
    }.filter { it.isNotBlank() }.distinct()

    private fun substringPairs(screen: String, labels: List<String>): List<String> {
        val out = mutableListOf<String>()
        for (a in labels) for (b in labels) {
            if (a == b || !b.contains(a)) continue
            if (a in v18 || b in v18) out += "$screen: \"$a\" inside \"$b\""
        }
        return out
    }

    private fun back() {
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        SmokeUi.settle()
    }

    /** Every node of the strip's rows lies within the screen's width. */
    private fun offScreen(screen: String, widthPx: Int): List<String> = RobolectricUi.elements().filter { e ->
        e.node.layoutInfo.isPlaced && StripTestKit.inConvertedStrip(e.node)
    }.filter { it.bounds.right > widthPx + 1 || it.bounds.left < -1 }.map { e ->
        "$screen: ${e.node.config.getOrNull(SemanticsProperties.ContentDescription) ?: e.node.config.getOrNull(SemanticsProperties.Text)} at ${e.bounds}"
    }

    @Test
    fun noV18LabelInsideAnotherOnTheSelectionStrips() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false).show()
        val problems = mutableListOf<String>()
        val width = rig.activity.resources.displayMetrics.widthPixels
        try {
            for (vector in listOf(false, true)) for ((key, list) in openers) {
                val id = ToolId.valueOf(key)
                val screen = key + if (vector) "@vector" else ""
                rig.select(id, vector)
                if (rig.spec.legacyContent != null) {
                    problems += "$screen: still legacy"
                    continue
                }
                problems += substringPairs("$screen row 1", shown())
                problems += offScreen("$screen row 1", width)
                if (StripTestKit.openMoreRow()) {
                    assertTrue(StripTestKit.tagged(V18Tags.STRIP_MORE_ROW) != null)
                    problems += substringPairs("$screen More row", shown())
                    problems += offScreen("$screen More row", width)
                    StripTestKit.closeMoreRow()
                }
                for (opener in list) {
                    val before = SmokeUi.windows().size
                    SmokeUi.click(opener)
                    if (SmokeUi.windows().size <= before) {
                        problems += "$screen: \"$opener\" opened no popup"
                        continue
                    }
                    problems += substringPairs("$screen \"$opener\" open", shown())
                    back()
                    StripTestKit.closeMoreRow()
                }
            }
        } finally {
            rig.close()
        }
        assertTrue("the selection strips' labels:\n" + problems.joinToString("\n"), problems.isEmpty())
    }
}
