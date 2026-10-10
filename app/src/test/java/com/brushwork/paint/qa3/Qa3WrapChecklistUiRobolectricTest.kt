package com.brushwork.paint.qa3

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
import com.brushwork.paint.tools.text.WrapFixtures
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * Final QA (v1.5 §7 checklist 3) as the user does it on the phone (392 dp), with real fingers
 * and buttons: a paragraph wrapped around a picture (Wrap chip, ✓), the picture picked in the
 * layers window, Transform, the picture pinched with one finger on it, ✓; the text re-flowed in
 * that step; the Undo button restores picture AND text at once, Redo brings both back, and
 * neither re-flows anything.
 *
 * Own sandbox; all UI work in ONE test.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.wrapchecklistsandbox"])
class Qa3WrapChecklistUiRobolectricTest {

    private fun itemOf(layer: Layer): TextItem = TextCodec.decode(layer.textData)!!

    private fun pixels(layer: Layer) = WrapFixtures.pixels(layer.bitmap)

    @Test
    fun wrapMoveUndoRedo() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val doc = Document("wrapcheck", "Wrap", 400, 300)
            doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(400, 300)).also { it.bitmap.eraseColor(WrapFixtures.WHITE) }
            doc.layers += Layer(doc.newLayerId(), "Picture", BitmapUtils.createLayerBitmap(400, 300)).also { WrapFixtures.disc(it, 110f, 150f, 40f) }
            doc.activeLayerIndex = 1
            val c: EditorController = Smoke.controller(activity, doc)
            c.snapping.enabled = false
            activity.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
            settle()
            c.tools
            settle()
            val picture = doc.layers[1]

            // The paragraph, wrapped with the chip, ✓ with a finger.
            c.selectTool(ToolId.TEXT)
            val text = c.tools.getValue(ToolId.TEXT) as TextTool
            text.startTextAt(200f, 150f)
            text.setText(WrapFixtures.LOREM)
            text.updateSpec { it.copy(sizePx = 16f, color = WrapFixtures.BLACK, box = it.box.copy(width = 360f)) }
            text.confirmEditor()
            settle()
            SmokeUi.click("Wrap around picture", exact = true)
            assertEquals(picture.id, text.item!!.wrap.sourceLayerId)
            SmokeUi.click("Close", exact = true)
            SmokeUi.tap("Apply text edit", exact = true)
            settle()
            val textLayer = doc.layers.single { it.isTextLayer }
            val t0 = itemOf(textLayer)
            val textPx0 = pixels(textLayer)
            val picPx0 = pixels(picture)

            // The picture, picked in the layers window; Transform from the tools grid.
            SmokeUi.click("Open layers")
            SmokeUi.click("Picture", exact = true)
            SmokeUi.click("Close layers")
            assertEquals(picture, c.activeLayer)
            SmokeUi.click("Tools (current")
            SmokeUi.click("Transform", exact = true)
            settle()
            val tt = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
            assertTrue("lifted", Smoke.pumpUntil { tt.transformState != null })
            settle()
            val steps = c.undoManager.undoCount
            val reflows = c.textWrap.reflowCount

            // One finger on the picture, the other far away on the free canvas: a pinch out.
            val view = Smoke.find(activity.window.decorView, CanvasView::class.java)!!
            val loc = IntArray(2).also { view.getLocationInWindow(it) }
            fun screen(x: Float, y: Float) = c.viewTransform.docToScreen(x, y).let { (it.x + loc[0]) to (it.y + loc[1]) }
            val dp = activity.resources.displayMetrics.density
            val on = screen(110f, 150f)
            // The free canvas measured between the chrome (the ✓ / ✕ and slider rows sit at the bottom).
            val far = Qa3FreeCanvas.farFrom(activity, c, 110f, 150f)
            val touch = Smoke.Touch(activity.window.decorView)
            touch.idle(300)
            touch.pinch(far, on, far.first + 30f * dp to far.second + 30f * dp, on.first - 30f * dp to on.second - 30f * dp)
            settle(4)
            val w = tt.transformState!!.bounds().width
            assertTrue("the picture grew: $w", w > 84f)
            SmokeUi.tap("Apply transform edit", exact = true)
            settle()
            assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
            assertEquals("the text re-flowed once, inside that step", reflows + 1, c.textWrap.reflowCount)
            val t1 = itemOf(textLayer)
            assertNotEquals("the text follows the bigger picture", t0, t1)
            assertArrayEquals("the text layer is its item's rendering", WrapFixtures.pixels(WrapFixtures.render(t1, 400, 300)), pixels(textLayer))
            val textPx1 = pixels(textLayer)
            val picPx1 = pixels(picture)

            // The Undo button: both at once. Redo: both again. No re-flow either way.
            SmokeUi.click("Undo", exact = true)
            assertEquals(t0, itemOf(textLayer))
            assertArrayEquals(textPx0, pixels(textLayer))
            assertArrayEquals(picPx0, pixels(picture))
            SmokeUi.click("Redo", exact = true)
            assertEquals(t1, itemOf(textLayer))
            assertArrayEquals(textPx1, pixels(textLayer))
            assertArrayEquals(picPx1, pixels(picture))
            assertEquals("undo / redo never re-flow", reflows + 1, c.textWrap.reflowCount)
        } finally {
            dog.interrupt()
            runCatching { ctl.pause().stop().destroy() }
        }
    }
}
