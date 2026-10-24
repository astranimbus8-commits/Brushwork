package com.brushwork.paint.v110.a2

import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ObjectColorFace
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.vector.CurveSettingsSheet
import com.brushwork.paint.ui.vector.GRADIENT_KEPT
import com.brushwork.paint.v110.a2.A2SheetKit.assertClosed
import com.brushwork.paint.v110.a2.A2SheetKit.byTag
import com.brushwork.paint.v110.a2.A2SheetKit.openSheet
import com.brushwork.paint.v110.a2.A2SheetKit.settle
import com.brushwork.paint.v110.a2.A2SheetKit.tab
import com.brushwork.paint.v110.a2.A2SheetKit.tapIn
import com.brushwork.paint.v110.a2.A2SheetKit.tapTag
import com.brushwork.paint.v110.a2.A2SheetKit.typeIn
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R2 "gradients are kept until touched", R7; design 4.4 A2 step 7): a reopened path with
 * a gradient fill (an imported SVG) keeps it until a fill color is picked, through both pickers,
 * driven as the user does:
 * - the Object colors sheet (the pill's cell): its Fill tab says [GRADIENT_KEPT]; a color typed
 *   there previews a solid fill and says [ObjectColorLabels110.GRADIENT_REPLACED]; Cancel brings
 *   the gradient back;
 * - the Curve settings sheet's fill row ([GRADIENT_KEPT] under "Fill color"): its picker opened
 *   and cancelled untouched says nothing and changes nothing; a color typed previews the solid fill
 *   live (R7) and says so; Cancel brings the gradient back; OK makes the fill that solid color
 *   ("Custom color"), ✓ stores it in one step, and one Undo brings the gradient back.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.gradientkeptsandbox"])
class GradientKeptTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    @Test
    fun aReopenedGradientIsKeptThroughBothPickersUntilAColorIsKept() {
        val c = rig.controller(400, 300)
        c.snapping.enabled = false
        val layer = c.addVectorLayer() ?: throw AssertionError("a vector layer")
        val gradient = VPaint.Linear(100f, 0f, 300f, 0f, listOf(VStop(0f, RED), VStop(1f, BLUE)))
        val blob = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(100f, 100f), VAnchor(300f, 100f), VAnchor(300f, 220f), VAnchor(100f, 220f)), closed = true)),
            fill = gradient,
        )
        c.vectors.addObjects(layer, listOf(blob), "Import")
        c.settleVectorWork()
        c.selectTool(ToolId.CURVE)
        val tool = c.tools.getValue(ToolId.CURVE) as CurveTool
        var settingsOpen by mutableStateOf(false)
        A2SheetKit.show(c) { if (settingsOpen) CurveSettingsSheet(tool) { settingsOpen = false } }
        assertTrue(tool.reopen(layer.vector!!.objects.single() as VPath))
        c.settleVectorWork()
        settle()
        assertTrue("reopened", tool.isReopened)
        val ed = requireNotNull(tool.objectColors) { "an open path has colors" }
        val kept = ObjectColorFace.Gradient(listOf(RED, BLUE))
        assertEquals("the gradient is the Fill face", kept, ed.face(ObjectColorTarget.FILL))
        fun fillState() = byTag(tab(ObjectColorTarget.FILL))?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

        // ---- The Object colors sheet: the Fill tab says the gradient is kept; Cancel keeps it.
        openSheet("the Object colors sheet")
        assertEquals("the Fill tab says the gradient is kept", GRADIENT_KEPT, fillState())
        tapTag(tab(ObjectColorTarget.FILL))
        c.message = null
        typeIn(ObjectColorLabels110.CELL, "00FF00")
        settle()
        assertEquals("a color previews a solid fill", ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.FILL))
        assertEquals("and says the gradient is replaced", ObjectColorLabels110.GRADIENT_REPLACED, c.message)
        assertNull("the Fill tab shows the color", fillState())
        tapIn(ObjectColorLabels110.CELL, "Cancel")
        settle()
        assertClosed("Cancel")
        assertEquals("Cancel brings the gradient back", kept, ed.face(ObjectColorTarget.FILL))

        // ---- The Curve settings sheet's fill row.
        settingsOpen = true
        settle()
        val title = tool.id.label
        assertTrue("the settings sheet", title in SmokeUi.sheetTitles())
        assertTrue("the fill row says the gradient is kept", SmokeUi.has(GRADIENT_KEPT, exact = true))

        // Opened and cancelled untouched: nothing said, nothing changed.
        c.message = null
        SmokeUi.click("Pick", exact = true)
        assertTrue("the picker opened", FILL_COLOR in SmokeUi.sheetTitles())
        tapIn(FILL_COLOR, "Cancel")
        settle()
        assertFalse("the picker closed", FILL_COLOR in SmokeUi.sheetTitles())
        assertNull("nothing said", c.message)
        assertEquals("the gradient untouched", kept, ed.face(ObjectColorTarget.FILL))

        // A color typed: a live solid fill; Cancel brings the gradient back.
        SmokeUi.click("Pick", exact = true)
        typeIn(FILL_COLOR, "00FF00")
        settle()
        assertEquals("the picker previews the fill live", ObjectColorFace.Solid(GREEN), ed.face(ObjectColorTarget.FILL))
        assertEquals("and says the gradient is replaced", ObjectColorLabels110.GRADIENT_REPLACED, c.message)
        tapIn(FILL_COLOR, "Cancel")
        settle()
        assertFalse("Cancel closed the picker", FILL_COLOR in SmokeUi.sheetTitles())
        assertEquals("Cancel brings the gradient back", kept, ed.face(ObjectColorTarget.FILL))
        assertTrue("the fill row still says the gradient is kept", SmokeUi.has(GRADIENT_KEPT, exact = true))

        // OK: the fill is that solid color, "Custom color"; ✓ stores it in one step.
        SmokeUi.click("Pick", exact = true)
        typeIn(FILL_COLOR, "0000FF")
        settle()
        tapIn(FILL_COLOR, "OK")
        settle()
        assertFalse("OK closed the picker", FILL_COLOR in SmokeUi.sheetTitles())
        assertEquals("OK: the fill is solid", ObjectColorFace.Solid(BLUE), ed.face(ObjectColorTarget.FILL))
        assertEquals("OK: the tool's fill color", BLUE, tool.settings.fillColor)
        assertTrue("the fill row says Custom color", SmokeUi.has("Custom color", exact = true))
        settingsOpen = false
        settle()
        val steps = c.undoManager.undoCount
        tool.commit()
        c.settleVectorWork()
        settle()
        assertEquals("✓: the edit's one step", steps + 1, c.undoManager.undoCount)
        assertEquals("✓: stored with the solid fill", VPaint.Solid(BLUE), (layer.vector!!.objects.single() as VPath).fill)
        c.undo()
        c.settleVectorWork()
        settle()
        assertEquals("one Undo brings the gradient back", gradient, (layer.vector!!.objects.single() as VPath).fill)
    }

    private companion object {
        /** The fill row's picker title (VectorWidgets, v1.9's "Fill color", the same words as the Fill tab's name). */
        const val FILL_COLOR = ObjectColorLabels110.FILL
    }
}
