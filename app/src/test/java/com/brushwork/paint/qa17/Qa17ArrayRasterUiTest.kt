package com.brushwork.paint.qa17

import android.graphics.RectF
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.count
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.ink
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayHandles
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA, item 3 (design §3.3) on the user's 392 dp phone: a raster array made from a
 * selection with the selection bar's "Array", by finger and label only.
 * - Line (3 side by side), then "Array in a circle", Count typed 12 and the centre handle dragged
 *   out by a finger: twelve copies round the circle, each step ONE "Edit array".
 * - "Edit source pixels", a Brush stroke (nothing baked, no "Array applied"), layer ⋮ "Finish
 *   source edit": every copy shows the stroke; three two-finger undos give the array back exactly.
 * - A Brush stroke on the arrayed layer: "Array applied" in the same step; one undo brings the live
 *   array back with its pixels.
 * - The sheet's "Apply array" and "Remove array", each one step, each undone.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.arrayrastersandbox"])
class Qa17ArrayRasterUiTest {

    @Test
    fun rasterArrayFlowsAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("selection array: Line, then Circle with 12 copies") { circle12(Qa17ArrayUi(h)) }
        h.section("Edit source pixels, a stroke, Finish source edit, three undos") { sourceEdit(Qa17ArrayUi(h)) }
        h.section("a stroke on the arrayed layer: Array applied, then undo") { paintBakes(Qa17ArrayUi(h)) }
        h.section("Apply array and Remove array, each undone") { applyRemove(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** A red square seeded on the active layer, selected, and arrayed with the selection bar's "Array". */
    private fun arrayFromSelection(u: Qa17ArrayUi, l: Float, t: Float, r: Float, b: Float): Layer {
        val c = u.c
        u.seed(c.activeLayer, l, t, r, b, RED)
        c.setSelection(u.rectSelection(l - 6f, t - 6f, r + 6f, b + 6f), recordUndo = false)
        settle(4)
        val before = u.steps()
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("the array")
        assertEquals("one step", before + 1, u.steps())
        assertEquals(ArrayLabels.BUTTON, c.undoManager.undoLabel)
        val layer = c.activeLayer
        assertNotNull("a live array on the new layer", layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNotNull("the Array sheet", u.s.tagged(V17Tags.ARRAY_SHEET))
        // The user lets the selection go (the selection bar's Deselect).
        c.setSelection(null, recordUndo = false)
        settle(4)
        return layer
    }

    /** The Array sheet shown expanded: its minimized pill tapped, or the strip chip "Array settings". */
    private fun showSheet(u: Qa17ArrayUi) {
        if (u.s.tagged(V17Tags.ARRAY_SHEET) != null) return
        if (has("Show Array", exact = true)) click("Show Array", exact = true) else click("Array settings", exact = true)
        settle()
        assertNotNull("the Array sheet is back; shown: ${SmokeUi.shown().take(60)}", u.s.tagged(V17Tags.ARRAY_SHEET))
    }

    /** Where copy k of the array of [layer] takes the source's centre (document px). */
    private fun copyCentres(layer: Layer): List<Vec2> {
        val d = layer.dataSnapshot()
        val src = requireNotNull(ArrayDraw.sourceBounds(d))
        val cx = src.centerX()
        val cy = src.centerY()
        return ArrayLayout.matrices(d.array!!.spec, src).map { m -> Vec2(m[0] * cx + m[1] * cy + m[2], m[3] * cx + m[4] * cy + m[5]) }
    }

    // ------------------------------------------------------------------ Line, then Circle × 12

    private fun circle12(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = arrayFromSelection(u, 192f, 40f, 208f, 56f)
        assertEquals(ArrayMode.LINE, layer.array!!.spec.mode)
        assertEquals(3, layer.array!!.spec.count)
        for ((k, p) in copyCentres(layer).withIndex()) assertEquals("Line copy $k side by side", RED, layer.bitmap.getPixel(p.x.toInt(), p.y.toInt()))
        assertEquals(200f + 32f, copyCentres(layer)[2].x, 0.01f)

        // Circle: one segment tap, one step.
        var n = u.steps()
        u.press(ArrayLabels.CIRCLE)
        u.settleRenders("circle")
        assertEquals(ArrayMode.CIRCLE, layer.array!!.spec.mode)
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)

        // Count 12, typed: one step.
        n = u.steps()
        u.ui.reach("Increase Count")
        SmokeUi.typeAndDone("Count", "12")
        u.settleRenders("count 12")
        assertEquals(12, layer.array!!.spec.count)
        assertEquals("one step for the typed count", n + 1, u.steps())

        // The centre handle, dragged down by a finger: the circle grows (one step).
        val src = requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot()))
        val centre = ArrayHandles.handles(layer.array!!.spec, src).single().pos
        assertTrue("the centre handle shows on the canvas at ${u.dpOf(centre.x, centre.y)}", u.onCanvas(centre.x, centre.y))
        n = u.steps()
        QaCurves.drag(u.s, centre, Vec2(0f, 150f - centre.y))
        u.settleRenders("centre dragged")
        val spec = layer.array!!.spec
        assertEquals("the centre followed the finger", 150f, spec.centerY ?: Float.NaN, 1.5f)
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        val centres = copyCentres(layer)
        assertEquals(12, centres.size)
        for ((k, p) in centres.withIndex()) assertEquals("copy $k at $p is red", RED, layer.bitmap.getPixel(p.x.toInt(), p.y.toInt()))
        // Twelve separate squares (none overlap at this radius): about 12 × 16² red pixels.
        val red = count(layer, RED)
        assertTrue("twelve copies' worth of red: $red", red > 12 * 14 * 14 && red <= 12 * 16 * 16 + 40)
        assertEquals("I1: the cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        showSheet(u)
        u.shot("raster-circle12")
        Smoke.assertQuiet(c, "circle 12")
    }

    // ------------------------------------------------------------------ Edit source pixels

    private fun sourceEdit(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = arrayFromSelection(u, 60f, 60f, 100f, 100f)
        val spec0 = layer.array!!.spec
        val cache0 = pixels(layer.bitmap)
        val src0 = pixels(layer.array!!.pixels!!.bitmap)
        val n = u.steps()

        u.press(ArrayLabels.EDIT_SOURCE)
        settle()
        assertTrue("editing the source", layer.array!!.spec.editingSource)
        assertEquals("the last painting tool", c.lastPaintTool, c.activeToolId)
        assertEquals("one step", n + 1, u.steps())
        assertEquals("the copies are gone while the source is edited", 0, ink(layer.bitmap, RectF(102f, 60f, 180f, 100f)))

        // A stroke that reaches below the source: an ordinary pixel step, nothing baked.
        u.ui.stroke(70f to 80f, 90f to 80f, 90f to 118f)
        assertFalse("nothing baked", has(ArrayLabels.APPLIED, exact = true))
        assertNotNull(layer.array)
        assertTrue(layer.array!!.spec.editingSource)
        assertEquals(n + 2, u.steps())
        assertTrue("the stroke went below the source", ink(layer.bitmap, RectF(80f, 104f, 100f, 116f), 200) > 0)

        u.layerMenu(ArrayLabels.FINISH_SOURCE)
        u.settleRenders("finished")
        val a = layer.array!!
        assertFalse(a.spec.editingSource)
        assertEquals(n + 3, u.steps())
        assertEquals(ArrayLabels.FINISH_SOURCE, c.undoManager.undoLabel)
        val px = a.pixels!!
        assertTrue("the new source holds the stroke: ${px.bitmap.height} px high", px.top + px.bitmap.height >= 112)
        // Every copy shows the stroke, at its own place.
        val w = px.bitmap.width.toFloat()
        for (k in 1..2) {
            assertTrue("copy $k shows the stroke", ink(layer.bitmap, RectF(80f + k * w, 104f, 100f + k * w, 116f), 200) > 0)
        }
        assertEquals("I1: the cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))

        // Three two-finger undos: the finish, the stroke, the source edit.
        repeat(3) { u.ui.twoFingerUndo() }
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals("the spec as it was", spec0, layer.array!!.spec)
        assertArrayEquals("the source pixels as they were", src0, pixels(layer.array!!.pixels!!.bitmap))
        assertArrayEquals("the copies as they were", cache0, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "source edit")
    }

    // ------------------------------------------------------------------ painting bakes

    private fun paintBakes(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = arrayFromSelection(u, 60f, 60f, 100f, 100f)
        val spec0 = layer.array!!.spec
        val cache0 = pixels(layer.bitmap)
        val red0 = count(layer, RED)
        val n = u.steps()
        u.tool("Brush")
        u.ui.stroke(60f to 200f, 300f to 200f)
        assertTrue("\"${ArrayLabels.APPLIED}\" shows; shown: ${SmokeUi.shown().take(60)}", has(ArrayLabels.APPLIED, exact = true))
        assertNull("baked", layer.array)
        assertEquals("the stroke and the bake are ONE step", n + 1, u.steps())
        assertEquals("the copies stay as pixels", red0, count(layer, RED))
        u.ui.twoFingerUndo()
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals("the live array is back", spec0, layer.array?.spec)
        assertArrayEquals("with its pixels", cache0, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "paint bakes")
    }

    // ------------------------------------------------------------------ Apply and Remove

    private fun applyRemove(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = arrayFromSelection(u, 60f, 60f, 100f, 100f)
        val spec0 = layer.array!!.spec
        val cache0 = pixels(layer.bitmap)
        val n = u.steps()

        // Real finger taps on the sheet's footer, which sits where the slider rows are: the sheet gets them.
        u.fingerPress(ArrayLabels.APPLY)
        u.settleRenders("applied")
        assertNull("applied", layer.array)
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.APPLY, c.undoManager.undoLabel)
        assertArrayEquals("the copies are plain pixels now", cache0, pixels(layer.bitmap))
        u.ui.twoFingerUndo()
        u.settleRenders("apply undone")
        assertEquals(spec0, layer.array?.spec)
        assertArrayEquals(cache0, pixels(layer.bitmap))
        assertEquals(n, u.steps())

        showSheet(u)
        u.fingerPress(ArrayLabels.REMOVE)
        u.settleRenders("removed")
        assertNull("removed", layer.array)
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.REMOVE, c.undoManager.undoLabel)
        assertEquals("the copies are gone", 0, ink(layer.bitmap, RectF(102f, 60f, 180f, 100f)))
        assertEquals("the source stays", 40 * 40, count(layer, RED))
        click("Undo", exact = true)
        u.settleRenders("remove undone")
        assertEquals(spec0, layer.array?.spec)
        assertArrayEquals(cache0, pixels(layer.bitmap))
        assertEquals(n, u.steps())
        Smoke.assertQuiet(c, "apply and remove")
    }
}
