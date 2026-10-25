package com.brushwork.paint.v110.a3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import kotlin.math.abs

/**
 * v1.10 (R5 (a), A3 step 5): a Recolored look gives each copy k the target `g(t_k)`, the
 * gradient color at the same position Tinted uses (t_k = pace((k − 1) / (N − 2))), and changes
 * the copy's own colors toward it by the strength: `ColorUtils.recolor(c, g(t_k), s)`. Checked on
 * the first, middle and last copy of a raster array (the matrix, ±1) and a vector array (exact);
 * the same spec Tinted keeps v1.9's tint. A copy without a tint carries no mode. In the Look tab a
 * mode chosen with no look records nothing and waits ([ArrayTool.plainColorMode]) for the tint.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayRecolorGradientTest {
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun ch(c: Int, shift: Int) = (c shr shift) and 0xFF

    private fun maxChannelDiff(a: Int, b: Int): Int = maxOf(abs(ch(a, 16) - ch(b, 16)), abs(ch(a, 8) - ch(b, 8)), abs(ch(a, 0) - ch(b, 0)))

    /** Red, green, blue: copies 1, 3 and 5 of six instances sit exactly on a stop. */
    private val green = 0xFF00FF00.toInt()
    private val stops = listOf(VStop(0f, RED), VStop(0.5f, green), VStop(1f, BLUE))
    private val strength = 0.8f
    private val recolored = ArrayLook(tint = stops, tintStrength = strength, colorMode = ArrayColorMode.RECOLOR)

    /** Copy k's target of six instances (copies 1..5 at t = (k − 1) / 4, even pace). */
    private fun targetOf(k: Int): Int = ArrayTint.sample(stops, (k - 1) / 4f)

    @Test
    fun theFirstMiddleAndLastPixelCopies() {
        // A 20 x 20 source of two flat colors at (10, 10); copy k is 30 k px to the right.
        val orange = 0xFFE07020.toInt()
        val bmp = Bitmap.createBitmap(20, 20, Bitmap.Config.ARGB_8888).apply {
            eraseColor(orange)
            Canvas(this).drawRect(10f, 0f, 20f, 20f, Paint().apply { color = GRAY })
        }
        val spec = ArraySpec(count = 6, relativeX = 1.5f, look = recolored)
        val out = BitmapUtils.createLayerBitmap(220, 40)
        ArrayDraw.drawPixels(Canvas(out), LayerArray(spec, ArrayPixels(bmp, 10, 10)))
        assertEquals(RED, targetOf(1))
        assertEquals(green, targetOf(3))
        assertEquals(BLUE, targetOf(5))
        for (k in listOf(1, 3, 5, 2)) for ((x, c) in listOf(15 to orange, 25 to GRAY)) {
            val want = ColorUtils.recolor(c, targetOf(k), strength)
            val got = out.getPixel(x + 30 * k, 20)
            assertTrue("copy $k at $x: %08X vs %08X".format(got, want), maxChannelDiff(want, got) <= 1)
            assertEquals(0xFF, got ushr 24)
        }
        assertEquals("the source as it is", orange, out.getPixel(15, 20))
        assertEquals(GRAY, out.getPixel(25, 20))

        // The pace moves the positions as it does for a tint: Speeds up puts copy 3 at t = 0.25.
        val eased = BitmapUtils.createLayerBitmap(220, 40)
        ArrayDraw.drawPixels(Canvas(eased), LayerArray(spec.copy(look = recolored.copy(easing = ArrayEasing.EASE_IN)), ArrayPixels(bmp, 10, 10)))
        val quarter = ArrayTint.sample(stops, ArrayInstances.ease(ArrayEasing.EASE_IN, 0.5f))
        assertEquals(ColorUtils.lerp(RED, green, 0.5f), quarter)
        assertTrue(maxChannelDiff(ColorUtils.recolor(GRAY, quarter, strength), eased.getPixel(25 + 90, 20)) <= 1)

        // Tinted, the same spec: v1.9's tint.
        val tinted = BitmapUtils.createLayerBitmap(220, 40)
        ArrayDraw.drawPixels(Canvas(tinted), LayerArray(spec.copy(look = recolored.copy(colorMode = ArrayColorMode.TINT)), ArrayPixels(bmp, 10, 10)))
        for (k in listOf(1, 3, 5)) {
            assertTrue("Tinted copy $k", maxChannelDiff(ArrayTint.apply(GRAY, targetOf(k), strength), tinted.getPixel(25 + 30 * k, 20)) <= 2)
        }
    }

    @Test
    fun theFirstMiddleAndLastVectorCopies() {
        val c = ArrayLookKit.controller(app)
        val fill = 0xFFE07020.toInt()
        val outline = 0xFF202020.toInt()
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 40f, 50f, fill = fill, stroke = outline)))
        val spec = layer.array!!.spec.copy(count = 6, relativeX = 1.5f, look = recolored)
        ArrayLookKit.edit(c, layer, spec)
        val source = layer.vector!!.objects.single() as VPath
        val shown = ArrayDraw.effectiveVector(layer.dataSnapshot())!!.objects
        fun copy(k: Int) = shown.single { it.id == source.id + (k.toLong() shl ArrayDraw.COPY_ID_SHIFT) } as VPath
        for (k in listOf(1, 3, 5)) {
            assertEquals("copy $k fill", VPaint.Solid(ColorUtils.recolor(fill, targetOf(k), strength)), copy(k).fill)
            assertEquals("copy $k outline", ColorUtils.recolor(outline, targetOf(k), strength), copy(k).stroke!!.color)
        }
        assertEquals("the source as it is", source, shown.last())

        // The instances carry the mode and the targets; a look without a tint carries none.
        val instances = ArrayInstances.of(spec, ArrayDraw.sourceBounds(layer.dataSnapshot())!!)
        for (inst in instances.filter { it.k >= 1 }) {
            assertEquals(ArrayColorMode.RECOLOR, inst.look!!.colorMode)
            assertEquals(targetOf(inst.k), inst.look!!.tintColor)
        }
        val fadeOnly = ArrayLook(opacityEnd = 0.5f, tint = stops, tintStrength = 0f, colorMode = ArrayColorMode.RECOLOR)
        val faded = ArrayInstances.of(spec.copy(look = fadeOnly), ArrayDraw.sourceBounds(layer.dataSnapshot())!!)
        assertTrue(faded.filter { it.k >= 1 }.all { it.look!!.colorMode == ArrayColorMode.TINT && it.look!!.colorMatrix() == null })
    }

    @Test
    fun aModeChosenWithoutALookWaitsForTheTint() {
        val c = ArrayLookKit.controller(app)
        val layer = ArrayLookKit.pixelArray(c)
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertEquals(ArrayColorMode.TINT, tool.shownColorMode)

        // Recolored with no look: no step, no look; the tab shows it.
        val steps = c.undoManager.undoCount
        tool.commitLook { it.copy(colorMode = ArrayColorMode.RECOLOR) }
        ArrayLookKit.settle(c)
        assertEquals("a mode alone records nothing", steps, c.undoManager.undoCount)
        assertNull(layer.array!!.spec.look)
        assertEquals(ArrayColorMode.RECOLOR, tool.plainColorMode)
        assertEquals(ArrayColorMode.RECOLOR, tool.shownColorMode)

        // The tint then: ONE step "Array look", Recolored.
        tool.commitLook { it.copy(tint = stops, tintStrength = 1f) }
        ArrayLookKit.settle(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
        assertEquals(ArrayColorMode.RECOLOR, layer.array!!.spec.look!!.colorMode)

        // Tinted again: one step, the look kept otherwise.
        tool.commitLook { it.copy(colorMode = ArrayColorMode.TINT) }
        ArrayLookKit.settle(c)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals(recolored.copy(tintStrength = 1f, colorMode = ArrayColorMode.TINT), layer.array!!.spec.look)
        c.undo()
        ArrayLookKit.settle(c)
        assertEquals(ArrayColorMode.RECOLOR, layer.array!!.spec.look!!.colorMode)
        assertEquals(ArrayColorMode.RECOLOR, tool.shownColorMode)

        // A strength set to 0 leaves no look, the mode kept for the next tint; Reset look forgets it.
        tool.commitLook { it.copy(tintStrength = 0f) }
        ArrayLookKit.settle(c)
        assertNull(layer.array!!.spec.look)
        assertEquals(ArrayColorMode.RECOLOR, tool.shownColorMode)
        tool.resetLook()
        assertEquals(ArrayColorMode.TINT, tool.plainColorMode)
        assertEquals(ArrayColorMode.TINT, tool.shownColorMode)
        assertTrue(tool.plainTint.isEmpty())
        resetLeavesOtherLayersAlone(c)
    }

    /** Another layer's array opened: the tab starts Tinted. */
    private fun resetLeavesOtherLayersAlone(c: EditorController) {
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        tool.commitLook { it.copy(colorMode = ArrayColorMode.RECOLOR) }
        assertEquals(ArrayColorMode.RECOLOR, tool.plainColorMode)
        ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(200f, 30f, 240f, 70f)))
        c.selectTool(ToolId.ARRAY)
        ArrayLookKit.settle(c)
        assertEquals(ArrayColorMode.TINT, tool.shownColorMode)
    }
}
