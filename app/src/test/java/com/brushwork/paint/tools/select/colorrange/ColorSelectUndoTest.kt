package com.brushwork.paint.tools.select.colorrange

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SampleSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.shadows.ShadowLog

/**
 * v1.8 (C, design §3.5): Color select's steps. A tap adds the color under the finger (the loupe
 * shows while the finger is down; a transparent pixel adds nothing), as one in-tool step Undo
 * takes back and Redo brings back; the brush color adds one too; a 9th sample replaces the
 * oldest; clearing is one step; ✕ goes back to the samples of the last confirm. Confirming is
 * ONE selection step "Color select", combined by the selection mode, and the samples stay.
 * Leaving the tool selects nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ColorSelectUndoTest {

    private val red = 0xFFE01010.toInt()
    private val blue = 0xFF1030D0.toInt()

    /** Bottom layer: left half red, right half blue; the empty layer above it is active unless [onColors]. */
    private fun setUp(onColors: Boolean = true): Pair<EditorController, ColorSelectTool> {
        ShadowLog.stream = null
        val doc = Smoke.document(400, 300)
        val canvas = Canvas(doc.layers[0].bitmap)
        val paint = Paint()
        paint.color = red; canvas.drawRect(0f, 0f, 200f, 300f, paint)
        paint.color = blue; canvas.drawRect(200f, 0f, 400f, 300f, paint)
        doc.activeLayerIndex = if (onColors) 0 else 1
        val c = Smoke.controller(ApplicationProvider.getApplicationContext(), doc)
        val tool = c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue("the proxy is built", Smoke.pumpUntil { tool.proxyReady && tool.idle })
        return c to tool
    }

    private fun EditorController.tap(x: Float, y: Float) {
        pointerDown(ToolPoint(x, y))
        pointerUp(ToolPoint(x, y))
    }

    @Test
    fun aTapAddsTheColorUnderTheFingerAsOneStep() {
        val (c, t) = setUp()
        c.pointerDown(ToolPoint(50f, 150f))
        assertNotNull("the loupe while the finger is down", t.loupe)
        c.pointerUp(ToolPoint(50f, 150f))
        assertNull(t.loupe)
        assertEquals(listOf(red), t.sampleColors)
        assertTrue(t.hasPendingWork)
        assertTrue(t.canUndoStep)

        c.undo()
        assertEquals(emptyList<Int>(), t.sampleColors)
        assertFalse(t.hasPendingWork)
        c.redo()
        assertEquals(listOf(red), t.sampleColors)

        c.tap(300f, 150f)
        assertEquals(listOf(red, blue), t.sampleColors)
        // The brush color: one more step.
        c.color = 0xFF20A040.toInt()
        t.useCurrentColor()
        assertEquals(listOf(red, blue, 0xFF20A040.toInt()), t.sampleColors)
        c.undo()
        c.undo()
        assertEquals(listOf(red), t.sampleColors)
        Smoke.assertQuiet(c, "color select steps")
    }

    @Test
    fun transparentPixelsAndTheOutsideAddNothing() {
        val (c, t) = setUp(onColors = false)
        c.tap(50f, 150f)
        assertEquals(emptyList<Int>(), t.sampleColors)
        assertFalse(t.hasPendingWork)
        // Refer: the canvas sees the colors under the empty layer.
        t.updateSettings(t.settings.copy(source = SampleSource.CANVAS))
        assertTrue(Smoke.pumpUntil { t.proxyReady && t.idle })
        c.tap(50f, 150f)
        assertEquals(listOf(red), t.sampleColors)
        c.tap(-5f, 150f)
        assertEquals(listOf(red), t.sampleColors)
    }

    @Test
    fun aNinthSampleReplacesTheOldestAndClearingIsOneStep() {
        val (c, t) = setUp()
        val colors = (1..9).map { 0xFF000000.toInt() or (it * 20 shl 16) }
        for (col in colors) t.addSample(col)
        assertEquals(colors.drop(1), t.sampleColors)
        t.clearSamples()
        assertEquals(emptyList<Int>(), t.sampleColors)
        c.undo()
        assertEquals(colors.drop(1), t.sampleColors)
        c.undo()
        assertEquals(colors.dropLast(1), t.sampleColors)
    }

    @Test
    fun selectColorsIsOneSelectionStepAndTheSamplesStay() {
        val (c, t) = setUp()
        c.tap(50f, 150f)
        t.selectColors()
        assertFalse("confirmed: no steps left", t.hasPendingWork)
        assertTrue(Smoke.pumpUntil { t.idle })
        val s = c.selection
        assertNotNull(s)
        assertEquals(255, s!!.alphaAt(50, 150))
        assertEquals(0, s.alphaAt(300, 150))
        assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        assertEquals(listOf(red), t.sampleColors)

        // ✓ (the floating confirm) is the same step.
        c.tap(300f, 150f)
        assertTrue(t.hasPendingWork)
        c.currentTool.commit()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals(255, c.selection!!.alphaAt(300, 150))
        assertEquals(listOf(red, blue), t.sampleColors)

        // Undo takes the selection back, never the confirmed samples.
        c.undo()
        assertEquals(0, c.selection?.alphaAt(300, 150) ?: 0)
        assertEquals(255, c.selection!!.alphaAt(50, 150))
        assertEquals(listOf(red, blue), t.sampleColors)
        Smoke.assertQuiet(c, "color select confirm")
    }

    @Test
    fun theModesCombineWithTheSelectionBefore() {
        val (c, t) = setUp()
        fun before(): Selection = Selection.fromPath(Path().apply { addRect(100f, 0f, 300f, 300f, Path.Direction.CW) }, 400, 300)
        t.addSample(red)
        for ((mode, expect) in listOf(
            SelectionMode.ADD to listOf(255, 255, 255, 0),
            SelectionMode.SUBTRACT to listOf(0, 0, 255, 0),
            SelectionMode.INTERSECT to listOf(0, 255, 0, 0),
            SelectionMode.REPLACE to listOf(255, 255, 0, 0),
        )) {
            c.setSelection(before(), label = "before")
            t.mode = mode
            t.selectColors()
            assertTrue(Smoke.pumpUntil { t.idle })
            val s = c.selection
            val got = listOf(50, 150, 250, 350).map { s?.alphaAt(it, 150) ?: 0 }
            assertEquals("$mode", expect, got)
            assertEquals(ToolId.COLOR_SELECT.label, c.undoManager.undoLabel)
        }
    }

    @Test
    fun discardGoesBackToTheLastConfirm() {
        val (c, t) = setUp()
        t.addSample(red)
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        t.addSample(blue)
        t.clearSamples()
        t.addSample(0xFF00FF00.toInt())
        c.currentTool.discard()
        assertEquals(listOf(red), t.sampleColors)
        assertFalse(t.hasPendingWork)
    }

    @Test
    fun leavingTheToolSelectsNothingAndKeepsTheSamples() {
        val (c, t) = setUp()
        t.addSample(red)
        t.addSample(blue)
        c.selectTool(ToolId.MAGIC_WAND)
        assertTrue(Smoke.pumpUntil { t.idle })
        assertNull(c.selection)
        c.selectTool(ToolId.COLOR_SELECT)
        assertTrue(Smoke.pumpUntil { t.proxyReady && t.idle })
        assertEquals(listOf(red, blue), t.sampleColors)
        assertFalse("the steps ended with the tool", t.hasPendingWork)
        assertNotNull(t.preview)
    }

    @Test
    fun aPresetIgnoresTheSamplesButKeepsThem() {
        val (c, t) = setUp()
        t.addSample(blue)
        t.updateSettings(t.settings.copy(preset = ColorPreset.REDS))
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals(255, c.selection!!.alphaAt(50, 150))
        assertEquals(0, c.selection!!.alphaAt(300, 150))
        assertEquals(listOf(blue), t.sampleColors)
        t.updateSettings(t.settings.copy(preset = ColorPreset.SAMPLED, invert = true))
        t.selectColors()
        assertTrue(Smoke.pumpUntil { t.idle })
        assertEquals(255, c.selection!!.alphaAt(50, 150))
        assertEquals(0, c.selection!!.alphaAt(300, 150))
    }
}
