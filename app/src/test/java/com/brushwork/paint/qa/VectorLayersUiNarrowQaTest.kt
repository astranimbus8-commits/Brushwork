package com.brushwork.paint.qa

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.MagicWandTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * QA at 360 dp (the narrowest phone the design supports), through the real editor screen: the
 * layers window's menu on a vector layer (Edit objects, Rasterize vector layer, New vector layer,
 * Convert to vector layer on an empty layer) — each entry on screen and doing what it says — and
 * the Selection sheet's Clear on a vector layer (the touched objects go, the layer stays a vector
 * layer). Its own sandbox (Compose's frame clock only runs in the first test of one): one test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h740dp-xhdpi", instrumentedPackages = ["com.brushwork.paint.qa.vectorlayersuisandbox"])
class VectorLayersUiNarrowQaTest {

    @Test
    fun layersMenuAndSelectionSheetOnAVectorLayerAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        Smoke.scopeErrors.clear()
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        val activity = ctl.get()
        val c = Smoke.controller(activity, Smoke.document(480, 320, 2, whiteBottom = true))
        activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        c.snapping.enabled = false
        settle()
        val root = activity.window.decorView
        val width = root.width.toFloat()
        val height = root.height.toFloat()
        val touch = Smoke.Touch(root)
        val canvas = Smoke.find(root, CanvasView::class.java) ?: throw AssertionError("no canvas")
        fun screen(x: Float, y: Float): Pair<Float, Float> {
            val loc = IntArray(2)
            canvas.getLocationInWindow(loc)
            val p = c.viewTransform.docToScreen(x, y)
            return (p.x + loc[0]) to (p.y + loc[1])
        }
        fun onScreen(label: String) {
            val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("\"$label\" not shown; shown: ${SmokeUi.shown().take(80)}")
            assertTrue("\"$label\" on screen (${e.bounds}, $width x $height)", e.bounds.left >= 0f && e.bounds.right <= width + 0.5f && e.bounds.top >= 0f && e.bounds.bottom <= height + 0.5f)
        }

        click("Vector", exact = true)
        assertTrue(c.isVectorMode)
        val vec = c.activeLayer
        c.color = 0xFF1A2A6C.toInt()
        c.brush = BrushLibrary.defaultBrush.copy(size = 10f)
        for (y in listOf(70f, 170f, 260f)) {
            touch.idle(300)
            touch.stroke(screen(40f, y), screen(240f, y + 10f), screen(440f, y))
            settle()
        }
        assertEquals(3, vec.vector!!.objects.size)

        // The layers window's menu on the vector layer.
        click("Open layers")
        click("More layer actions", exact = true)
        for (entry in listOf("Edit objects", "Rasterize vector layer", "New vector layer")) onScreen(entry)
        click("Rasterize vector layer", exact = true)
        assertNull("rasterized", vec.vector)
        assertFalse(c.isVectorMode)
        c.undo()
        settle()
        assertNotNull("undo gives the objects back", vec.vector)
        assertTrue(c.isVectorMode)
        click("More layer actions", exact = true)
        click("Edit objects", exact = true)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        c.currentTool.discard()
        c.selectTool(ToolId.BRUSH)
        settle()
        click("More layer actions", exact = true)
        click("New vector layer", exact = true)
        assertEquals(3, c.doc.layers.size)
        val added = c.activeLayer
        assertTrue(added.isVectorLayer)
        c.undo()
        settle()
        // An empty raster layer offers Convert to vector layer.
        c.selectLayer(c.doc.layers[0])
        c.addLayer()
        settle()
        val empty = c.activeLayer
        click("More layer actions", exact = true)
        onScreen("Convert to vector layer")
        click("Convert to vector layer", exact = true)
        assertTrue(empty.isVectorLayer)
        c.undo(); c.undo()
        settle()
        if (has("Close layers", exact = true)) click("Close layers", exact = true)
        c.selectLayer(vec)
        settle()

        // The Selection sheet's Clear with a Magic wand selection on the middle stroke.
        c.selectTool(ToolId.MAGIC_WAND)
        (c.tools.getValue(ToolId.MAGIC_WAND) as MagicWandTool).selectAt(240f, 178f)
        assertTrue(Smoke.pumpUntil(10_000) { c.selection != null })
        settle()
        click("Selection", exact = true)
        settle()
        SmokeUi.assertPanelShown()
        onScreen("Clear")
        click("Clear", exact = true)
        assertTrue(Smoke.pumpUntil(10_000) { !c.vectors.isRendering })
        settle()
        assertNotNull("still a vector layer", vec.vector)
        assertEquals("the touched stroke went", 2, vec.vector!!.objects.size)
        Smoke.assertQuiet(c, "end")
        assertTrue("scope errors ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
        runCatching { ctl.pause().stop().destroy() }
        c.dispose()
    }
}
