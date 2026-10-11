package com.brushwork.paint.ui.vector

import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.ShapeStripKit.back
import com.brushwork.paint.ui.vector.ShapeStripKit.clippedTexts
import com.brushwork.paint.ui.vector.ShapeStripKit.fresh
import com.brushwork.paint.ui.vector.ShapeStripKit.moreHandles
import com.brushwork.paint.ui.vector.ShapeStripKit.staysOpen
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 F (design §3.8 (a), D21): the cog in the Shape tool's UI on a 360 dp phone:
 * - "Cog" is in the "Shape type" dropdown (`pickerTypes` = every type) and picking it makes cogs;
 * - "Shape settings" (from the More row) shows the cog's section: "Number of teeth" (slider and
 *   field) and the four percent sliders "Tooth depth", "Tooth width", "Tooth taper", "Hole size",
 *   then "Corners" (a cog has corners). Each control is inside the screen's width, on screen or in
 *   the sheet's scroll, its texts not cut, and each one sets its value;
 * - "Points" with a hole says "Set the hole size to 0 to edit points" and stays off; at hole 0 it
 *   turns the cog into points (its teeth).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h780dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.vector.coguisandbox"])
class CogUiTest {

    private val sliders = listOf(CogLabels18.TEETH, CogLabels18.TOOTH_DEPTH, CogLabels18.TOOTH_WIDTH, CogLabels18.TOOTH_TAPER, CogLabels18.HOLE_SIZE)

    /** The (first) placed node whose text is [label]. */
    private fun textNode(label: String): RobolectricUi.Element? = RobolectricUi.elements().firstOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    }

    /** The slider under the label [label] (the next node after it that sets a progress). */
    private fun sliderOf(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        val at = all.indexOfFirst { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        assertTrue("\"$label\" shows", at >= 0)
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            ?: throw AssertionError("no slider under \"$label\"")
    }

    private fun setSlider(label: String, value: Float) {
        val e = sliderOf(label)
        requireNotNull(e.node.config.getOrNull(SemanticsActions.SetProgress)?.action).invoke(value)
        SmokeUi.settle()
    }

    private fun inField(n: SemanticsNode): Boolean = generateSequence(n) { it.parent }.any { it.config.getOrNull(SemanticsActions.SetText) != null }

    private fun scrolls(n: SemanticsNode): Boolean = generateSequence(n) { it.parent }.any { it.config.getOrNull(SemanticsActions.ScrollBy) != null }

    /**
     * [e] fits the screen's width, and is on screen or inside a scroll that brings it there
     * (where it is laid out, not where a scroll clips it: the sheet starts at its top).
     */
    private fun assertReachable(what: String, e: RobolectricUi.Element) {
        val p = e.node.positionInWindow
        val b = androidx.compose.ui.geometry.Rect(p.x, p.y, p.x + e.node.size.width, p.y + e.node.size.height)
        val w = e.window.width.toFloat()
        val h = e.window.height.toFloat()
        assertTrue("$what: inside the screen's width ($b in $w px)", b.left >= -0.5f && b.right <= w + 0.5f && b.width > 0f)
        assertTrue("$what: on screen or in the sheet's scroll ($b in $h px)", (b.top >= -0.5f && b.bottom <= h + 0.5f) || scrolls(e.node))
    }

    @Test
    fun theCogIsInTheDropdownAndItsSettingsFitA360DpPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val rig = StripRig(panel = false).show()
        try {
            val c = rig.c

            // ---- "Cog" in the dropdown, last; the pick makes cogs.
            val t = fresh(rig)
            SmokeUi.click("Shape type", exact = true)
            assertTrue("the type menu lists \"Cog\"", SmokeUi.has(ShapeType.COG.label, exact = true))
            assertReachable("the menu's \"Cog\"", requireNotNull(SmokeUi.find(ShapeType.COG.label, exact = true)))
            SmokeUi.click(ShapeType.COG.label, exact = true)
            assertEquals(ShapeType.COG, t.settings.type)
            assertFalse("the menu closed", SmokeUi.has(ShapeType.COG.label, exact = true))
            assertTrue(StripTestKit.openMoreRow())
            assertTrue("a cog keeps \"Proportional\"", "Proportional" in moreHandles())
            StripTestKit.closeMoreRow()

            // ---- "Shape settings": the cog's section, then "Corners".
            staysOpen(rig, "Shape settings", "the Shape settings sheet") { SmokeUi.has(CogLabels18.TEETH, exact = true) }
            // (Section headers show in capitals.)
            for (label in listOf(ShapeType.COG.label.uppercase()) + sliders + "CORNERS") {
                val e = textNode(label) ?: throw AssertionError("\"$label\" in the cog's settings; shown: ${SmokeUi.shown().take(80)}")
                assertReachable("\"$label\"", e)
            }
            for (style in CornerStyle.entries) assertTrue("\"${style.label}\" corners", SmokeUi.has(style.label, exact = true))
            for (label in sliders) assertReachable("the \"$label\" slider", sliderOf(label))
            val field = SmokeUi.field(CogLabels18.TEETH)
            assertReachable("the \"${CogLabels18.TEETH}\" field", field)
            // No text on screen (the sheet, the strip under it) is cut at 360 dp. A number field's
            // inner label is the shared v1.7 field's (cut as "Number of sides" is; the slider over
            // it shows the whole label), so it is left out.
            assertEquals("the cog's settings: texts fit", emptyList<String>(), clippedTexts { n -> !inField(n) })

            // Each control sets its value.
            SmokeUi.typeAndDone(CogLabels18.TEETH, "24")
            assertEquals(24, t.settings.cogTeeth)
            setSlider(CogLabels18.TEETH, 16f)
            assertEquals(16, t.settings.cogTeeth)
            setSlider(CogLabels18.TOOTH_DEPTH, 30f)
            assertEquals(0.30f, t.settings.cogDepth, 1e-4f)
            setSlider(CogLabels18.TOOTH_WIDTH, 40f)
            assertEquals(0.40f, t.settings.cogToothWidth, 1e-4f)
            setSlider(CogLabels18.TOOTH_TAPER, 20f)
            assertEquals(0.20f, t.settings.cogTaper, 1e-4f)
            setSlider(CogLabels18.HOLE_SIZE, 30f)
            assertEquals(0.30f, t.settings.cogHole, 1e-4f)
            assertTrue("the value shows in whole percent", SmokeUi.has("30 %", exact = true))
            back(rig)
            assertFalse("the sheet closed", SmokeUi.has(CogLabels18.TEETH, exact = true))

            // ---- "Points" with a hole: refused with the hint, nothing changes.
            assertTrue(t.ensurePending())
            rig.settle()
            c.message = null
            SmokeUi.click("Points", exact = true)
            assertEquals(CogLabels18.HOLE_REFUSAL, c.message)
            assertFalse(t.pointsMode)
            assertEquals(null, t.points)

            // ---- At hole 0 the cog turns into its teeth.
            t.update { it.copy(cogHole = 0f) }
            rig.settle()
            SmokeUi.click("Points", exact = true)
            assertTrue("Points mode at hole 0", t.pointsMode)
            assertNotNull(t.points)
            assertTrue("16 teeth, 4 points each at least", t.pointCount >= 16 * 4)
        } finally {
            StripTestKit.closeMoreRow()
            rig.close()
        }
    }
}
