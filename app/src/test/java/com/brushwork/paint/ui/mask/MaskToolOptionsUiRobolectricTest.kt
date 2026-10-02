package com.brushwork.paint.ui.mask

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.5 A5: the Masks tool's options strip and sheets in a real activity at the user's phone size
 * (392 dp): arming a kind, the Components sheet (one step per change), the Adjust sheet (the
 * effect changes live, one step when it closes) and the target menu.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.ui.mask.uisandbox"])
class MaskToolOptionsUiRobolectricTest {

    @Test
    fun theStripArmsKindsAndTheSheetsEditTheMaskAndTheEffect() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        c.selectTool(ToolId.MASK)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        activity.setContent { BrushworkTheme { ToolOptionsBar(c) } }
        SmokeUi.settle()
        for (label in listOf("+ Linear", "+ Radial", "+ Brush", "Components (0)", "Add", "Subtract", "Intersect", "New Tone")) {
            assertTrue("strip shows $label (${SmokeUi.shown()})", SmokeUi.has(label, exact = true))
        }
        assertFalse("Adjust needs an adjustment layer", SmokeUi.isEnabled("Adjust…"))

        // Arm a radial and drag: a new Tone layer with the component.
        SmokeUi.click("+ Radial", exact = true)
        assertEquals(MaskTool.Kind.RADIAL, tool.armed)
        c.pointerDown(ToolPoint(200f, 150f)); c.pointerMove(ToolPoint(230f, 150f)); c.pointerMove(ToolPoint(260f, 150f)); c.pointerUp(ToolPoint(260f, 150f))
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Components (1)", exact = true))
        assertTrue(SmokeUi.has("Tone 1 mask", exact = true))
        assertTrue(SmokeUi.isEnabled("Adjust…"))
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)

        // Components: invert the whole mask (one step), delete the part (one step).
        SmokeUi.click("Components (1)", exact = true)
        assertTrue(SmokeUi.has("Mask of Tone 1", exact = true))
        assertTrue(SmokeUi.has("Radial 1", exact = true))
        val steps = c.undoManager.undoCount
        SmokeUi.click("Invert mask", exact = true)
        assertTrue(adj.maskSpec!!.invert)
        assertEquals(steps + 1, c.undoManager.undoCount)
        SmokeUi.click("Delete Radial 1", exact = true)
        assertTrue(adj.maskSpec!!.components.isEmpty())
        assertEquals(steps + 2, c.undoManager.undoCount)
        SmokeUi.click("Close", exact = true)
        assertFalse(tool.componentsOpen)

        // Adjust: another effect shows at once; one step when the sheet closes.
        SmokeUi.click("Adjust…", exact = true)
        assertTrue(SmokeUi.has("Adjust: Tone 1", exact = true))
        assertTrue("Tone's sliders", SmokeUi.has("Exposure", exact = true))
        val before = c.undoManager.undoCount
        SmokeUi.click("Choose the effect", exact = true)
        SmokeUi.click("Invert Color", exact = true)
        assertEquals("adjust.invert", adj.adjustment!!.filterId)
        assertEquals("the default name follows the effect", "Invert Color 1", adj.name)
        assertEquals("live, no step yet", before, c.undoManager.undoCount)
        SmokeUi.click("Close", exact = true)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals("Edit adjustment", c.undoManager.undoLabel)
        c.undo()
        assertEquals("adjust.tone", adj.adjustment!!.filterId)
        assertEquals("Tone 1", adj.name)

        // The target menu on a pixel layer: this layer's mask.
        c.selectLayer(c.doc.layers[1])
        SmokeUi.settle()
        SmokeUi.click("Choose what the mask is for", exact = true)
        SmokeUi.click("This layer's mask", exact = true)
        assertEquals(MaskTool.Target.ThisLayer, tool.target)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Layer 2 mask", exact = true))
        assertNull(tool.replacePrompt)
        Smoke.assertQuiet(c, "mask strip")
        SmokeUi.assertIdle("mask strip", settleMs = 1_500)
    }
}
