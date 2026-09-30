package com.brushwork.paint.tools.transform

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.rememberScrollState
import androidx.compose.ui.Modifier
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.placement.TransformToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The transform options strip and Numbers sheet on a 392dp-wide phone: Delete, From center and
 * Snap in the strip; the reference point picker, its X / Y, scaling around it, the snapping
 * toggle and Delete in the sheet.
 *
 * One test: Compose's frame clock (sheet animations) only serves the first test of a Robolectric
 * sandbox (see HalfHeightSheetsTest).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h860dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.tools.transform.uisandbox"])
class TransformOptionsUiRobolectricTest {

    @Test
    fun stripAndNumbersSheetDriveTheTool() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c: EditorController = Smoke.controller(activity, Smoke.document(400, 300, layers = 2))
        val layer = c.activeLayer
        c.editWholeLayer(layer, "Seed") { b ->
            android.graphics.Canvas(b).drawRect(100f, 100f, 180f, 140f, android.graphics.Paint().apply { color = RED })
        }
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        activity.setContent {
            BrushworkTheme {
                Row(Modifier.horizontalScroll(rememberScrollState())) { TransformToolOptions(tool) }
            }
        }
        c.selectTool(ToolId.TRANSFORM)
        Smoke.pump(100)
        SmokeUi.settle()
        assertTrue("lifted", tool.hasPendingWork)
        for (label in listOf("Delete", "From center", "Snap to objects", "Numbers")) assertTrue("\"$label\" in the strip", SmokeUi.has(label, exact = true))

        // Strip toggles.
        assertTrue(tool.snapToObjects)
        SmokeUi.click("Snap to objects", exact = true)
        assertFalse(tool.snapToObjects)
        SmokeUi.click("Snap to objects", exact = true)
        assertTrue(tool.snapToObjects)
        assertFalse(tool.scaleFromCenter)
        SmokeUi.click("From center", exact = true)
        assertTrue(tool.scaleFromCenter)

        // Numbers sheet: reference point picker, X / Y of that point, scale around it.
        SmokeUi.click("Numbers", exact = true)
        SmokeUi.settle(20, 50)
        assertTrue(tool.numbersOpen)
        assertTrue(SmokeUi.has("Reference point: center", exact = true))
        assertEquals(TransformAnchor.CENTER, tool.anchor)
        SmokeUi.click("Reference point: top left", exact = true)
        assertEquals(TransformAnchor.TOP_LEFT, tool.anchor)
        SmokeUi.typeAndDone("Scale", "50")
        assertEquals(DocBox(100f, 100f, 140f, 120f), tool.transformState!!.bounds())
        SmokeUi.click("Reference point: center", exact = true)
        SmokeUi.typeAndDone("Scale", "100")
        assertEquals("scaled back around its center", DocBox(80f, 90f, 160f, 130f), tool.transformState!!.bounds())
        SmokeUi.typeAndDone("X", "200")
        assertEquals(200f, tool.anchorPosition!!.x, 1e-3f)
        assertTrue(SmokeUi.has("Snap to objects", exact = true))

        // Delete (the sheet's button, the last one): one step, the sheet closes, nothing left floating.
        val undo0 = c.undoManager.undoCount
        SmokeUi.click("Delete", exact = true)
        SmokeUi.settle(20, 50)
        assertFalse(tool.hasPendingWork)
        assertFalse(tool.numbersOpen)
        assertEquals(undo0 + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.DELETE_LABEL, c.undoManager.undoLabel)
        assertEquals(0, layer.bitmap.getPixel(120, 120))
        Smoke.assertQuiet(c, "transform strip")
        c.dispose()
    }

    private companion object {
        const val RED = 0xFFFF0000.toInt()
    }
}
