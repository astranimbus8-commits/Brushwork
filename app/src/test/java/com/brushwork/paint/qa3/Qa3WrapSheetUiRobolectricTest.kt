package com.brushwork.paint.qa3

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.WrapContour
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.text.WrapSides
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
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
 * Final QA (v1.5 §4.1, §7 checklist 3) of the "Wrap around picture" sheet in the real editor on a
 * narrow phone (360 dp): the Wrap chip opens it around the picture under the text; Box contour,
 * the sides and a typed distance re-flow the pending text live; ✓ (a real tap) records ONE
 * step; after the picture is deleted the
 * sheet shows "(deleted layer)" and the text keeps its outline until Off or another layer is
 * picked; vertical text gets the "horizontal text" message instead of the sheet.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.wrapsheetsandbox"])
class Qa3WrapSheetUiRobolectricTest {

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
        controller.snapping.enabled = false
        activity.setContent { BrushworkTheme { EditorScreen(controller, onExit = {}, onSaveNow = {}) } }
        settle()
        controller.tools
        settle()
        return activity to controller
    }

    /** A white background and a picture with a disc on the left (the text goes over both). */
    private fun pictureDoc(): Document {
        val doc = Document("wrapui", "Wrap", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(400, 300)).also { it.bitmap.eraseColor(WrapFixtures.WHITE) }
        doc.layers += Layer(doc.newLayerId(), "Picture", BitmapUtils.createLayerBitmap(400, 300)).also { WrapFixtures.disc(it, 110f, 150f, 40f) }
        doc.activeLayerIndex = 1
        return doc
    }

    private fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    private fun pixels(b: Bitmap) = WrapFixtures.pixels(b)

    /** A pending paragraph over the picture (typed through the tool: the editor dialog is its own test). */
    private fun pendingText(c: EditorController): TextTool {
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        tool.startTextAt(200f, 150f)
        tool.setText(WrapFixtures.LOREM)
        tool.updateSpec { it.copy(sizePx = 16f, color = WrapFixtures.BLACK, box = it.box.copy(width = 360f)) }
        tool.confirmEditor()
        settle()
        return tool
    }

    private fun assertOnScreen(activity: ComponentActivity, label: String) {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown()}")
        val root = activity.window.decorView
        assertTrue("\"$label\" is on the screen: ${e.bounds}", e.bounds.left >= 0f && e.bounds.right <= root.width && e.bounds.top >= 0f && e.bounds.bottom <= root.height)
    }

    @Test
    fun wrapSheetAt360dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        section("chip -> sheet -> Box, sides, distance -> ✓ one step") { sheetFlow() }
        section("deleted picture: (deleted layer), Off, another layer") { deletedPicture() }
        section("vertical text: no sheet, a message") { verticalText() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun sheetFlow() {
        val (activity, c) = editor { Smoke.controller(it, pictureDoc()) }
        val picture = c.doc.layers[1]
        val tool = pendingText(c)
        assertFalse(tool.item!!.wrap.isOn)
        val steps = c.undoManager.undoCount
        SmokeUi.click("Wrap around picture", exact = true)
        SmokeUi.assertPanelShown("Wrap around picture")
        val w0 = tool.item!!.wrap
        assertEquals("the picture under the text is the default", picture.id, w0.sourceLayerId)
        assertTrue(tool.item!!.wrapActive)
        assertEquals("Shape contour by default", WrapContour.SHAPE, w0.contour)
        assertEquals("0.3 em", 4.8f, w0.gapPx, 0.01f)
        assertTrue("the chip shows wrap is on", SmokeUi.has("Wrap around picture: on", exact = true))
        for (label in listOf("Off", "Picture", "Background", "Shape", "Box", "Largest side", "Both sides", "Left only", "Right only", "Show outline")) {
            assertTrue("\"$label\" is in the sheet", SmokeUi.has(label, exact = true))
        }

        // Box: the outline becomes the disc's rectangle.
        SmokeUi.click("Box", exact = true)
        val box = tool.item!!.wrap
        assertEquals(WrapContour.BOX, box.contour)
        assertEquals("one rectangle", 1, box.polygons.size)
        assertEquals(4, box.polygons[0].xs.size)
        assertEquals(70f, box.polygons[0].xs.min(), 1.5f)
        assertEquals(150f, box.polygons[0].xs.max(), 1.5f)
        // Right only: no line beside the picture starts left of it.
        SmokeUi.click("Right only", exact = true)
        assertEquals(WrapSides.RIGHT, tool.item!!.wrap.sides)
        val right = WrapFixtures.render(tool.item!!, 400, 300)
        for (y in 115..185) for (x in 0 until 150) {
            assertEquals("no ink left of the picture at ($x, $y) with Right only", 0, right.getPixel(x, y) ushr 24)
        }
        // A typed distance.
        SmokeUi.typeAndDone("Distance", "20")
        assertEquals(20f, tool.item!!.wrap.gapPx, 0.01f)
        val far = WrapFixtures.render(tool.item!!, 400, 300)
        for (y in 115..185) for (x in 0 until 170) {
            assertEquals("20 px from the picture's box at ($x, $y)", 0, far.getPixel(x, y) ushr 24)
        }
        SmokeUi.click("Largest side", exact = true)
        SmokeUi.click("Right only", exact = true)
        // The outline toggle.
        assertTrue(tool.showWrapOutline)
        SmokeUi.click("Show outline", exact = true)
        assertFalse(tool.showWrapOutline)
        SmokeUi.click("Show outline", exact = true)
        assertEquals("all of it is the pending text", steps, c.undoManager.undoCount)

        // Close the sheet (the text keeps its wrap), then ✓ with a real finger.
        SmokeUi.click("Close", exact = true)
        assertFalse(tool.wrapSheetOpen)
        assertEquals(20f, tool.item!!.wrap.gapPx, 0.01f)
        assertOnScreen(activity, "Apply text edit")
        SmokeUi.tap("Apply text edit", exact = true)
        settle()
        assertNull("applied", tool.item)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val text = c.doc.layers.single { it.isTextLayer }
        val item = itemOf(text)
        assertEquals(WrapContour.BOX, item.wrap.contour)
        assertEquals(WrapSides.RIGHT, item.wrap.sides)
        assertEquals(20f, item.wrap.gapPx, 0.01f)
        assertArrayEquals("the layer is its item's rendering (I1)", pixels(WrapFixtures.render(item, 400, 300)), pixels(text.bitmap))
        assertFalse("the sheet went with the text", SmokeUi.sheetTitles().contains("Wrap around picture"))
    }

    private fun deletedPicture() {
        val (activity, c) = editor { Smoke.controller(it, pictureDoc()) }
        val picture = c.doc.layers[1]
        val tool = pendingText(c)
        tool.setWrapSource(picture)
        assertTrue(tool.commitItem())
        val text = c.activeLayer
        val kept = itemOf(text)
        c.deleteLayer(picture)
        settle()
        assertEquals("the text keeps its outline", kept, itemOf(text))
        // The text layer is active: the Wrap chip opens it again.
        c.selectLayer(text)
        settle()
        val steps = c.undoManager.undoCount
        SmokeUi.click("Wrap around picture", exact = false)
        SmokeUi.assertPanelShown("Wrap around picture")
        assertTrue("(deleted layer) is listed", SmokeUi.has("(deleted layer)", exact = true))
        assertTrue(SmokeUi.has("The picture was deleted", exact = false))
        assertTrue(tool.wrapSourceDeleted)
        assertEquals("nothing changed by opening it", kept, tool.item)
        // Box still works from the kept outline.
        SmokeUi.click("Box", exact = true)
        assertEquals(1, tool.item!!.wrap.polygons.size)
        // Off: the text takes the whole width again.
        SmokeUi.click("Off", exact = true)
        assertFalse(tool.item!!.wrap.isOn)
        assertFalse("(deleted layer) is gone", SmokeUi.has("(deleted layer)", exact = true))
        SmokeUi.click("Close", exact = true)
        SmokeUi.tap("Apply text edit", exact = true)
        settle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertFalse(itemOf(text).wrap.isOn)
        c.undo()
        assertEquals("undo brings the kept outline back", kept, itemOf(text))

        // Another layer: a new photo.
        val photo = c.addLayer("Photo")!!
        WrapFixtures.disc(photo, 300f, 150f, 40f)
        c.selectLayer(text)
        settle()
        SmokeUi.click("Wrap around picture", exact = false)
        SmokeUi.click("Photo", exact = true)
        assertEquals(photo.id, tool.item!!.wrap.sourceLayerId)
        assertFalse(tool.wrapSourceDeleted)
        assertNotEquals(kept.wrap.polygons, tool.item!!.wrap.polygons)
        SmokeUi.click("Close", exact = true)
        SmokeUi.tap("Apply text edit", exact = true)
        settle()
        assertEquals(photo.id, itemOf(text).wrap.sourceLayerId)
        // The photo now moves the text.
        val before = itemOf(text)
        c.editWholeLayer(photo, "Move") { b -> b.eraseColor(0); android.graphics.Canvas(b).drawCircle(250f, 150f, 40f, android.graphics.Paint().apply { color = 0xFF2266CC.toInt() }) }
        assertNotEquals(before, itemOf(text))
    }

    private fun verticalText() {
        val (_, c) = editor { Smoke.controller(it, pictureDoc()) }
        val tool = pendingText(c)
        tool.toggleVertical()
        settle()
        assertTrue(tool.isVertical)
        assertTrue("dimmed chip explains", SmokeUi.has("Wrap around picture (works with horizontal text)", exact = true))
        SmokeUi.click("Wrap around picture (works with horizontal text)", exact = true)
        assertFalse(tool.wrapSheetOpen)
        assertTrue("the message says why", SmokeUi.has(TextTool.WRAP_HORIZONTAL_ONLY, exact = true))
        assertFalse(tool.item!!.wrap.isOn)
        assertNotNull(tool.item)
    }
}
