package com.brushwork.paint.qa3

import android.graphics.Bitmap
import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.editor.ToolMenu
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Final QA (v1.5 §4.9, §5.9; v1.6 ibisPaint chrome) of the editor chrome on a narrow phone (360 dp):
 * a new canvas, the Vector circle before Selection in the top row (its highlight follows the active
 * layer, also when layers are picked in the layers window and when undo takes the conversion back),
 * drawing on the converted layer, the tool menu ("px" badges, the Filters cell with the vector banner),
 * the layers window badges and menu entries of vector, adjustment and editable-mask layers, and
 * "+" adding a vector layer in vector mode.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.chromesandbox"])
class Qa3ChromeNarrowUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun editor(c: (ComponentActivity) -> EditorController): Pair<ComponentActivity, EditorController> {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        val activity = ctl.get()
        val controller = c(activity)
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    /** A new canvas as the gallery makes it: a white Background and an empty "Layer 1". */
    private fun newCanvas(activity: ComponentActivity): EditorController {
        val doc = Smoke.document(540, 960, layers = 2, whiteBottom = true)
        doc.layers[0].name = "Background"
        doc.layers[1].name = "Layer 1"
        return Smoke.controller(activity, doc)
    }

    private fun canvasOf(activity: ComponentActivity): CanvasView =
        Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")

    private fun screen(activity: ComponentActivity, c: EditorController, x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvasOf(activity).getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun dp(activity: ComponentActivity, v: Float) = v * activity.resources.displayMetrics.density

    /** The back key, sent to the newest window (closes a menu). */
    private fun pressBack() {
        val w = SmokeUi.windows().last()
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_DOWN, android.view.KeyEvent.KEYCODE_BACK))
        w.dispatchKeyEvent(android.view.KeyEvent(android.view.KeyEvent.ACTION_UP, android.view.KeyEvent.KEYCODE_BACK))
        settle()
    }

    /** The editor window as drawn. */
    private fun drawn(activity: ComponentActivity): Bitmap {
        settle()
        val root = activity.window.decorView
        return Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888).also { root.draw(Canvas(it)) }
    }

    /** The color inside the round button labelled [label], beside its icon. */
    private fun buttonFill(activity: ComponentActivity, bmp: Bitmap, label: String): Int {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        return bmp.getPixel((e.bounds.center.x - dp(activity, 15f)).toInt(), e.bounds.center.y.toInt())
    }

    /** The Vector button is drawn highlighted (unlike the never-selected Selection button). */
    private fun vectorHighlighted(activity: ComponentActivity): Boolean {
        val bmp = drawn(activity)
        return buttonFill(activity, bmp, "Vector") != buttonFill(activity, bmp, "Selection")
    }

    @Test
    fun theEditorChromeAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("new canvas, Vector first, highlight follows the layer") { vectorButton() }
        section("tools grid in vector mode, Filters with the vector banner") { toolsGrid() }
        section("layers window: badges, menus, + in vector mode") { layersWindow() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun vectorButton() {
        val (activity, c) = editor { newCanvas(it) }
        // Vector is the first action of the top bar and in the bar itself (not the overflow).
        val vector = SmokeUi.find("Vector", exact = true) ?: throw AssertionError("no Vector button; shown: ${SmokeUi.shown()}")
        val selection = SmokeUi.find("Selection", exact = true)!!
        assertTrue("Vector comes before Selection", vector.bounds.center.x < selection.bounds.center.x)
        assertTrue("on the screen", vector.bounds.right <= activity.window.decorView.width)
        assertFalse("Filters left the top bar", SmokeUi.has("Filters", exact = true))
        assertFalse(vectorHighlighted(activity))
        // Tap: the empty Layer 1 becomes a vector layer, one step.
        SmokeUi.click("Vector", exact = true)
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        assertEquals("Vector 1", layer.name)
        assertEquals(1, c.undoManager.undoCount)
        assertTrue("the VECTOR chip leads the options", SmokeUi.has("Vector mode is on"))
        assertTrue("highlighted while on", vectorHighlighted(activity))
        // A stroke on the canvas is a vector object.
        val touch = Smoke.Touch(activity.window.decorView)
        touch.idle(300)
        touch.stroke(screen(activity, c, 100f, 300f), screen(activity, c, 300f, 420f), screen(activity, c, 420f, 300f))
        Smoke.pump(100)
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(2, c.undoManager.undoCount)
        // The layers window: picking the Background turns vector mode off; the vector layer back on.
        SmokeUi.click("Open layers")
        assertTrue("vector badge", SmokeUi.has("Vector layer", exact = true))
        SmokeUi.click("Background", exact = true)
        assertFalse(c.isVectorMode)
        assertFalse("not highlighted on a raster layer", vectorHighlighted(activity))
        SmokeUi.click("Vector 1", exact = true)
        assertTrue(c.isVectorMode)
        assertTrue(vectorHighlighted(activity))
        SmokeUi.click("Close layers")
        // Undo the stroke and the conversion: back to raster.
        SmokeUi.click("Undo", exact = true)
        SmokeUi.click("Undo", exact = true)
        assertFalse(layer.isVectorLayer)
        assertFalse(c.isVectorMode)
        assertFalse("not highlighted after undo", vectorHighlighted(activity))
    }

    private fun toolsGrid() {
        val (_, c) = editor { newCanvas(it) }
        SmokeUi.click("Vector", exact = true)
        assertTrue(c.isVectorMode)
        // v1.6: the ibisPaint tool menu replaced the sectioned tools grid.
        SmokeUi.click("Tools (current")
        SmokeUi.assertPanelShown("Tools")
        for (id in ToolMenu.tools) assertTrue("cell ${id.label}", SmokeUi.has(id.label, exact = true))
        val badges = RobolectricUi.elements().count { e ->
            e.node.layoutInfo.isPlaced && e.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.any { it.text == "px" } == true
        }
        val pixelOnly = ToolMenu.tools.count { it in LayerToolRules.PIXEL_ONLY }
        assertTrue("pixel-only tools exist", pixelOnly > 0)
        assertEquals("a px badge on each pixel-only cell", pixelOnly, badges)
        SmokeUi.click("Filters", exact = true)
        SmokeUi.assertPanelShown("Filters")
        assertTrue("the vector banner", SmokeUi.has("Applying rasterizes this vector layer"))
        SmokeUi.click("Close", exact = true)
        // Back to raster: no badges.
        SmokeUi.click("Vector", exact = true)
        assertFalse(c.isVectorMode)
        SmokeUi.click("Tools (current")
        assertFalse("no px badges in raster mode", SmokeUi.has("px", exact = true))
        // The tools button closes the menu again.
        SmokeUi.click("Tools (current")
        assertFalse("menu closed", "Tools" in SmokeUi.sheetTitles())
    }

    private fun layersWindow() {
        val (_, c) = editor { newCanvas(it) }
        // A vector layer, an adjustment layer with an editable mask, a picture with an editable mask.
        val vector = c.addVectorLayer()!!
        val adjustment = c.addAdjustmentLayer(AdjustmentSpec(), MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 0f, y1 = 400f))))
        assertNotNull(adjustment)
        c.selectLayer(c.doc.layers.first())
        val picture: Layer = c.addLayer("Picture")!!
        com.brushwork.paint.masks.MaskEdits.apply(c, picture, MaskSpec(components = listOf(LinearMask(1, x0 = 0f, y0 = 0f, x1 = 400f, y1 = 0f))), "Gradient mask")
        assertNotNull(picture.maskSpec)
        settle()
        // Badges and menus (the window scrolls to the active layer when it opens).
        fun menuOf(layer: Layer, badge: String): List<String> {
            c.selectLayer(layer)
            settle()
            SmokeUi.click("Open layers")
            assertTrue("badge \"$badge\" of ${layer.name}", SmokeUi.has(badge, exact = true))
            SmokeUi.click("More layer actions")
            val shown = SmokeUi.shown()
            pressBack()
            settle()
            SmokeUi.click("Close layers", exact = true)
            return shown
        }
        val vm = menuOf(vector, "Vector layer")
        assertTrue("vector menu: $vm", "Edit objects" in vm && "Rasterize vector layer" in vm && "New vector layer" in vm)
        val am = menuOf(adjustment!!, "Adjustment layer")
        assertTrue("adjustment menu: $am", "Edit adjustment" in am && "Edit mask" in am && "Apply to layer below" in am && "Use mask as selection" in am)
        menuOf(picture, "Editable mask")
        val bm = menuOf(c.doc.layers.first { it.name == "Layer 1" }, "Layer 1")
        assertTrue("an empty raster layer converts: $bm", "Convert to vector layer" in bm && "New adjustment layer (Tone)" in bm)
        // "+" adds a vector layer while vector mode is on.
        c.selectLayer(vector)
        settle()
        SmokeUi.click("Open layers")
        assertTrue(c.isVectorMode)
        val n = c.doc.layers.size
        SmokeUi.click("Add layer")
        assertEquals(n + 1, c.doc.layers.size)
        assertTrue("a vector layer in vector mode", c.activeLayer.isVectorLayer)
        assertNotEquals(vector, c.activeLayer)
    }
}
