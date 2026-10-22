package com.brushwork.paint.v110.a4

import android.graphics.Canvas
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.filters.FilterSessionPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 (R7, A4 step 6): Change Drawing Color's Color control previews the color being picked on
 * the canvas, live, before OK. A typed color shows at once (object mode: the objects re-rendered
 * with it; a raster layer: its pixels filtered with it) while the layer, the document's history
 * and the panel's steps stay as they were; Cancel puts the opening color back on the canvas and
 * leaves no step; OK records ONE panel step named after the control, and ✓ applies the picked
 * color. The real [FilterSessionPanel] and color picker are driven through their semantics.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a4.livepreviewsandbox"])
class CdcLivePreviewTest {
    private val kit = ObjectTestKit(256, 192)

    @Before
    fun restartCompose() = SmokeUi.restartUiDispatcher()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private val green = 0xFF00A040.toInt()
    private val violet = 0xFF8000FF.toInt()

    private fun clickable(n0: SemanticsNode): () -> Unit {
        var n: SemanticsNode? = n0
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return requireNotNull(n?.config?.getOrNull(SemanticsActions.OnClick)?.action) { "not clickable" }.let { a -> { a() } }
    }

    private fun labels(n: SemanticsNode) =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()

    private fun click(label: String) {
        clickable(RobolectricUi.elements().last { e -> label in labels(e.node) }.node)()
        RobolectricUi.settle()
    }

    private fun shows(label: String) = RobolectricUi.elements().any { e -> label in labels(e.node) }

    /** The picker's hex field (WHEEL mode: its only text field), in the picker's own window. */
    private fun typeHex(hex: String) {
        val window = RobolectricUi.windowRoots().last()
        RobolectricUi.textFields().first { it.window === window }.type(hex)
        RobolectricUi.settle()
    }

    /** What the canvas shows for the filtered layer. */
    private fun shown(c: EditorController): IntArray {
        val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        assertTrue("the canvas shows the preview", c.renderOverride!!.drawContent(Canvas(b)))
        return kit.pixels(b).also { b.recycle() }
    }

    private fun awaitColor(s: FilterSession, color: Int) {
        assertTrue("the preview of %08X".format(color), kit.idleUntil { s.values.color("color") == color && s.hasPreview && !s.isRendering && !s.previewStale })
        RobolectricUi.settle()
    }

    /** The panel at the bottom, as the editor shows it, for [c]'s session. */
    private fun showPanel(c: EditorController) {
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxSize()) {
                    val s = c.filterSession
                    if (s != null) Box(Modifier.align(Alignment.BottomCenter).fillMaxWidth()) { FilterSessionPanel(s, Modifier.fillMaxWidth()) }
                }
            }
        }
        RobolectricUi.settle()
    }

    private fun start(c: EditorController): FilterSession {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        RobolectricUi.settle()
        return s
    }

    @Test
    fun thePickedColorPreviewsLiveCancelRestoresAndOkIsOneStep() {
        // Object mode: two boxes, the first selected (a rendered preview, exact).
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(30f, 30f, 110f, 100f), kit.box(140f, 80f, 220f, 160f)), "Add")
        val one = c.vec.vector!!.objects[0].id
        c.vectors.setSelection(c.vec, setOf(one))
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        val docSteps = c.undoManager.undoCount
        showPanel(c)
        val s = start(c)
        assertTrue(s.objectMode)
        val opening = s.values.color("color")
        fun expected() = kit.render(ObjectColorSlots.map(before, setOf(one), cdc.colorMapper(s.values)!!))
        assertArrayEquals(expected(), shown(c))

        click("Choose color")
        assertTrue("the picker is open", shows("OK") && shows("Cancel"))
        typeHex("FF00A040")
        awaitColor(s, green)
        assertTrue("still picking", shows("OK"))
        assertArrayEquals("the canvas shows the picked color before OK", expected(), shown(c))
        assertNotEquals(kit.render(ObjectColorSlots.map(before, setOf(one), cdc.colorMapper(s.values.copy().set("color", opening))!!))[60 * kit.w + 70], shown(c)[60 * kit.w + 70])
        typeHex("FF8000FF")
        awaitColor(s, violet)
        assertArrayEquals("it follows every change", expected(), shown(c))
        assertSame("the layer is untouched", before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertEquals("no document step", docSteps, c.undoManager.undoCount)
        assertEquals("no panel step", 0, s.history.depth)

        click("Cancel")
        awaitColor(s, opening)
        assertFalse("the picker is closed", shows("OK"))
        assertArrayEquals("Cancel puts the opening color back on the canvas", expected(), shown(c))
        assertEquals(0, s.history.depth)
        assertFalse("nothing left to undo in the panel", s.canUndo)

        click("Choose color")
        typeHex("FF00A040")
        awaitColor(s, green)
        click("OK")
        RobolectricUi.settle()
        assertFalse(shows("Cancel"))
        assertEquals(green, s.values.color("color"))
        assertEquals("OK is one panel step", 1, s.history.depth)
        assertEquals("Color", s.undoLabel)
        assertArrayEquals(expected(), shown(c))
        assertEquals(docSteps, c.undoManager.undoCount)

        val f = cdc.colorMapper(s.values)!!
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
        assertEquals("✓ applies the picked color as one step", docSteps + 1, c.undoManager.undoCount)
        assertEquals(ObjectColorSlots.map(before, setOf(one), f), c.vec.vector)

        // A raster layer: its pixels are filtered with the color being picked, live.
        c.selectLayer(0)
        c.doc.layers[0].bitmap.eraseColor(0xFF3060C0.toInt())
        c.notifyLayersChanged()
        RobolectricUi.settle()
        val r = start(c)
        assertFalse(r.objectMode)
        val rasterBefore = shown(c)
        click("Choose color")
        typeHex("FF8000FF")
        awaitColor(r, violet)
        val px = shown(c)[50 * kit.w + 50]
        assertNotEquals("the raster preview follows the picked color", rasterBefore[50 * kit.w + 50], px)
        assertEquals(cdc.colorMapper(r.values)!!(0xFF3060C0.toInt()), px)
        click("Cancel")
        awaitColor(r, r.history.current.color("color"))
        assertEquals(rasterBefore[50 * kit.w + 50], shown(c)[50 * kit.w + 50])
        r.cancel()
    }
}
