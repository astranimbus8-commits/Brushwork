package com.brushwork.paint

import android.graphics.Matrix
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 F2 (item 10): what the first finger of a history tap over the UI changed is put back by
 * `restoreUiMark`: a preset slider (the live preset, by reference), the colour, and a step it
 * pushed; the history is not trimmed while a mark is open.
 */
@RunWith(RobolectricTestRunner::class)
class UiMarkRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()

    private fun controller(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 40, 30)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(40, 30))
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun aPresetSliderMovedAfterTheMarkIsRestored() {
        val c = controller()
        val brush = c.presetFor(ToolId.BRUSH)
        val eraser = c.presetFor(ToolId.ERASER)
        assertNotNull(brush)
        val m = c.uiMark()
        // The first finger drags the size slider of the brush (each move replaces the live preset).
        c.updatePreset(ToolId.BRUSH, brush!!.copy(size = brush.size * 2f + 1f))
        c.updatePreset(ToolId.BRUSH, brush.copy(size = brush.size * 3f + 2f))
        assertNotSame(brush, c.presetFor(ToolId.BRUSH))
        assertTrue(c.restoreUiMark(m))
        assertSame("the very preset of the mark is back", brush, c.presetFor(ToolId.BRUSH))
        assertSame("untouched presets stay", eraser, c.presetFor(ToolId.ERASER))
        assertFalse("nothing more to restore", c.restoreUiMark(m))
        c.releaseUiMark(m)
    }

    @Test
    fun theColourAndAStepFromTheFirstFingerAreTakenBack() {
        val c = controller()
        c.color = 0xFF112233.toInt()
        c.addLayer("Before")
        val m = c.uiMark()
        c.color = 0xFFAA0000.toInt()
        c.addLayer("During")
        assertTrue(c.restoreUiMark(m))
        assertEquals(0xFF112233.toInt(), c.color)
        assertEquals(listOf("Layer 1", "Before"), c.doc.layers.map { it.name })
        // The tap's own history action then runs on what was there before the finger.
        c.undo()
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
        c.releaseUiMark(m)
    }

    @Test
    fun theHistoryIsNotTrimmedWhileAMarkIsOpen() {
        val c = controller()
        c.addLayer("A")
        val m = c.uiMark()
        // More steps than the history keeps (150): the mark's step must survive until release.
        repeat(160) { c.renameLayer(c.doc.activeLayer, "N$it") }
        assertTrue(c.restoreUiMark(m))
        assertEquals("A", c.doc.activeLayer.name)
        c.releaseUiMark(m)
        c.undo()
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
    }

    @Test
    fun releasingAMarkTwiceKeepsAnotherOpenMarkWhole() {
        val c = controller()
        c.addLayer("A")
        val outer = c.uiMark()
        val inner = c.uiMark()
        // A gesture end and a cancel both release the inner mark: only the first counts.
        c.releaseUiMark(inner)
        c.releaseUiMark(inner)
        repeat(160) { c.renameLayer(c.doc.activeLayer, "N$it") }
        val leftHanded = c.settings.leftHanded
        c.settings.leftHanded = !leftHanded
        assertTrue(c.restoreUiMark(outer))
        assertEquals("the outer mark's step was not trimmed", "A", c.doc.activeLayer.name)
        assertEquals("the journal still recorded for the outer mark", leftHanded, c.settings.leftHanded)
        c.releaseUiMark(outer)
    }
}
