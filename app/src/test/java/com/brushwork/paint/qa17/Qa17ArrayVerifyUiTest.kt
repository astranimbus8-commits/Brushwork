package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.item
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.RED
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.atan2

/**
 * v1.7 final QA, cluster "array" (items 3 and 11), the verifier's flows on the user's 392 dp
 * phone, beyond the tester's:
 *
 * - an arrayed TEXT in the Transform tool is turned and scaled only proportionally, as a text
 *   kept as text is: no side handles, no "Flip horizontally" / "Flip vertically", no "Keep aspect
 *   ratio" toggle (before the fix all were offered, and a flip or a side stretch was lost on ✓
 *   with "Apply the array to transform it"); "Numbers" › Rotation and Scale is ONE step, still a
 *   live text array, the copies turned and spaced with it; a two-finger tap takes it back;
 * - "Edit source pixels" (design §3.3: "Every painting tool, filter, transform and selection edit
 *   then changes the source as ordinary pixel steps, without baking"): the Transform tool turns
 *   the source as pixels, ONE step, nothing baked; "Finish source edit" gives turned copies
 *   (before the fix the tool refused with "Apply the array to transform it");
 * - a folder holding a raster array whose source is being edited and an arrayed text turns as
 *   ONE step, without flips, and a two-finger tap takes both back exactly (before the fix the
 *   folder was refused).
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.arrayverifysandbox"])
class Qa17ArrayVerifyUiTest {

    @Test
    fun arrayedTextAndSourceEditInTransformAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("an arrayed text: proportional only, Numbers turn and scale, one step, undone exactly") { textArray(Qa17ArrayUi(h)) }
        h.section("Edit source pixels: Transform turns the source as pixels, nothing baked; Finish gives turned copies") { sourceTurn(Qa17ArrayUi(h)) }
        h.section("a folder with a source being edited and an arrayed text: one step, no flips, undone exactly") { folder(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** The Transform tool's refusal of an array shows (its snackbar). */
    private fun refused(): Boolean = has(TransformTool.ARRAY_REFUSAL, exact = true)

    /** The Transform tool from the tool menu; it must lift [lifted] (else whether it refused fails the test). */
    private fun transform(u: Qa17ArrayUi, lifted: TransformTool.Lifted): TransformTool {
        u.tool("Transform")
        val t = u.c.currentTool as TransformTool
        val ok = Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS / 4) { settle(1); t.lifted != null || refused() }
        settle()
        assertTrue("lifted $lifted; \"${TransformTool.ARRAY_REFUSAL}\" shown: ${refused()}", ok && t.lifted != null)
        assertEquals(lifted, t.lifted)
        return t
    }

    /** ✓ "Apply transform edit": ONE step "Transform", no "Array applied". */
    private fun applyTransform(u: Qa17ArrayUi) {
        val n = u.steps()
        u.applyEdit("Apply transform edit")
        assertEquals("one step; \"${TransformTool.ARRAY_REFUSAL}\" shown: ${refused()}", n + 1, u.steps())
        assertEquals(TransformTool.TRANSFORM_LABEL, u.c.undoManager.undoLabel)
        assertFalse("nothing baked", has(ArrayLabels.APPLIED, exact = true))
    }

    /** The bounds of [b]'s pixels with alpha above 200. */
    private fun inkBounds(b: Bitmap): Rect {
        val px = pixels(b)
        val r = Rect(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        for (i in px.indices) if (px[i] ushr 24 > 200) {
            val x = i % b.width
            val y = i / b.width
            r.union(x, y, x + 1, y + 1)
        }
        return r
    }

    /** Where copy k of [layer]'s array takes the source's centre (document px). */
    private fun copyCentres(layer: Layer): List<Vec2> {
        val d = layer.dataSnapshot()
        val src = requireNotNull(ArrayDraw.sourceBounds(d))
        return ArrayLayout.matrices(d.array!!.spec, src).map { m ->
            Vec2(m[0] * src.centerX() + m[1] * src.centerY() + m[2], m[3] * src.centerX() + m[4] * src.centerY() + m[5])
        }
    }

    private fun norm(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    /** A text "Ab" typed where a tap at ([x], [y]) puts it; the new text layer. */
    private fun typeText(u: Qa17ArrayUi, x: Float, y: Float): Layer {
        u.tool("Text")
        val tt = u.c.currentTool as TextTool
        u.ui.tap(x, y)
        assertTrue("a tap opens the text editor", tt.editorOpen)
        SmokeUi.field("Text").type("Ab")
        settle()
        click("OK", exact = true)
        u.applyEdit("Apply text edit")
        val layer = u.c.activeLayer
        assertTrue(layer.isTextLayer)
        return layer
    }

    /** Layer ⋮ "Array…" on the active text layer: one step, a live text array, the Array tool. */
    private fun arrayText(u: Qa17ArrayUi, layer: Layer) {
        val n = u.steps()
        u.layerMenu(ArrayLabels.OPEN)
        u.settleRenders("array")
        assertEquals(n + 1, u.steps())
        assertNotNull(layer.array)
        assertEquals(ToolId.ARRAY, u.c.activeToolId)
        assertTrue("copies", copyCentres(layer).size >= 2)
    }

    /** A raster array from a selection around [l, t, r, b] of red pixels; the new array layer (active). */
    private fun rasterArray(u: Qa17ArrayUi, l: Float, t: Float, r: Float, b: Float): Layer {
        val c = u.c
        u.seed(c.activeLayer, l, t, r, b, RED)
        c.setSelection(u.rectSelection(l - 4f, t - 4f, r + 4f, b + 4f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("the array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        assertNotNull("a live raster array", layer.array?.pixels)
        return layer
    }

    // ------------------------------------------------------------------ an arrayed text

    private fun textArray(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = typeText(u, 120f, 120f)
        arrayText(u, layer)
        val text0 = layer.textData
        val spec0 = layer.array!!.spec
        val cache0 = pixels(layer.bitmap)
        val item0 = layer.item()
        val centres0 = copyCentres(layer)
        val n = u.steps()

        val t = transform(u, TransformTool.Lifted.ARRAY)
        if (has("Flip horizontally", exact = true)) {
            // What the user then gets: the flip previews, ✓ refuses it and the transform is gone.
            u.press("Flip horizontally")
            u.applyEdit("Apply transform edit")
            throw AssertionError("an arrayed text offers \"Flip horizontally\": ✓ then recorded ${u.steps() - n} step(s); \"${TransformTool.ARRAY_REFUSAL}\" shown: ${refused()}")
        }
        assertFalse(has("Flip vertically", exact = true))
        assertFalse("no side handles on a text", t.sideHandlesShown)
        assertEquals("Distort on an array", ArrayLabels.DEFORM_REFUSAL, u.stateOf("Distort"))

        // "Numbers": no "Keep aspect ratio" (always kept); Rotation 30, Scale 150.
        u.press("Numbers")
        settle()
        assertFalse("a text always keeps its aspect ratio", has("Keep aspect ratio", exact = true))
        u.typeField("Rotation", "30")
        u.typeField("Scale", "150")
        settle()
        if (has("Close", exact = true)) click("Close", exact = true)
        settle()
        applyTransform(u)
        u.settleRenders("turned")
        assertTrue("still a text", layer.isTextLayer)
        assertNotNull("still a live array", layer.array)
        val item1 = layer.item()
        assertEquals("the same words", item0.text, item1.text)
        assertEquals("type 150 %", item0.spec.sizePx * 1.5f, item1.spec.sizePx, item0.spec.sizePx * 0.02f)
        val turn = norm(item1.rotationDeg - item0.rotationDeg)
        assertEquals("turned 30°", 30f, minOf(turn, 360f - turn), 0.5f)
        // The copies went with it: each step 150 % as long, turned as the text was.
        val centres1 = copyCentres(layer)
        assertEquals(centres0.size, centres1.size)
        val d0 = centres0[1] - centres0[0]
        val d1 = centres1[1] - centres1[0]
        assertEquals("the copies 150 % apart: $centres0 -> $centres1", d0.length * 1.5f, d1.length, 1.5f)
        val a0 = Math.toDegrees(atan2(d0.y, d0.x).toDouble()).toFloat()
        val a1 = Math.toDegrees(atan2(d1.y, d1.x).toDouble()).toFloat()
        assertEquals("the row turned as the text did", 0f, norm(a1 - a0 - (item1.rotationDeg - item0.rotationDeg) + 180f) - 180f, 1f)
        assertEquals("I1: the cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        u.shot("verify-text-array-turned")

        u.ui.twoFingerUndo()
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals(text0, layer.textData)
        assertEquals(spec0, layer.array!!.spec)
        assertArrayEquals("the copies as they were", cache0, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "arrayed text transform")
    }

    // ------------------------------------------------------------------ the source being edited

    private fun sourceTurn(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val layer = rasterArray(u, 60f, 60f, 120f, 80f)
        val spec0 = layer.array!!.spec
        val src0 = pixels(layer.array!!.pixels!!.bitmap)
        val cache0 = pixels(layer.bitmap)
        val n = u.steps()

        u.press(ArrayLabels.EDIT_SOURCE)
        settle()
        assertTrue("editing the source", layer.array!!.spec.editingSource)
        val wide = inkBounds(layer.bitmap)
        assertEquals("only the source shows: $wide", 60, wide.width())

        val t = transform(u, TransformTool.Lifted.PIXELS)
        assertNull("Distort works on the source pixels", u.stateOf("Distort"))
        u.press("Rotate 90° clockwise")
        applyTransform(u)
        assertEquals(n + 2, u.steps())
        assertNotNull("the array stays", layer.array)
        assertTrue("still editing the source", layer.array!!.spec.editingSource)
        assertEquals("the source's spec as it was", spec0, layer.array!!.spec.copy(editingSource = false))
        val tall = inkBounds(layer.bitmap)
        assertEquals("the source turned a quarter: $wide -> $tall", wide.width().toFloat(), tall.height().toFloat(), 2f)
        assertEquals(wide.height().toFloat(), tall.width().toFloat(), 2f)
        assertSame(t, c.currentTool)

        // Layer ⋮ "Finish source edit": the turned source, copied.
        u.layerMenu(ArrayLabels.FINISH_SOURCE)
        u.settleRenders("finished")
        assertFalse(layer.array!!.spec.editingSource)
        assertEquals(n + 3, u.steps())
        val px = layer.array!!.pixels!!
        assertTrue("the new source is tall: ${px.bitmap.width} × ${px.bitmap.height}", px.bitmap.height > 2 * px.bitmap.width)
        val all = inkBounds(layer.bitmap)
        assertTrue("copies beside the turned source: $tall -> $all", all.width() >= 2 * tall.width() && all.height() == tall.height())
        assertEquals("I1: the cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        u.shot("verify-source-turned")

        // Three two-finger taps: the finish, the turn, the source edit.
        repeat(3) { u.ui.twoFingerUndo() }
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals(spec0, layer.array!!.spec)
        assertArrayEquals("the source pixels as they were", src0, pixels(layer.array!!.pixels!!.bitmap))
        assertArrayEquals("the copies as they were", cache0, pixels(layer.bitmap))
        Smoke.assertQuiet(c, "source turn")
    }

    // ------------------------------------------------------------------ a folder with both

    private fun folder(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val raster = rasterArray(u, 150f, 110f, 210f, 130f)
        u.press(ArrayLabels.EDIT_SOURCE)
        settle()
        assertTrue(raster.array!!.spec.editingSource)
        val f = requireNotNull(c.putInNewFolder(raster))
        c.selectLayer(raster)
        settle()
        val text = typeText(u, 230f, 180f)
        assertEquals("the text went into the folder", f.id, text.parentId)
        arrayText(u, text)
        assertTrue("the other array's source is still being edited", raster.array!!.spec.editingSource)

        u.openLayers()
        click(f.name, exact = true)
        settle()
        u.closeLayers()
        assertSame(f, c.activeLayer)
        val text0 = text.textData
        val textSpec0 = text.array!!.spec
        val textPx0 = pixels(text.bitmap)
        val rasterData0 = raster.dataSnapshot()
        val rasterPx0 = pixels(raster.bitmap)
        val wide = inkBounds(raster.bitmap)
        val item0 = text.item()
        val n = u.steps()

        val t = transform(u, TransformTool.Lifted.FOLDER)
        assertFalse("a folder holding a text is never mirrored", has("Flip horizontally", exact = true))
        assertFalse(t.sideHandlesShown)
        u.press("Rotate 90° clockwise")
        applyTransform(u)
        u.settleRenders("turned")
        assertEquals(n + 1, u.steps())
        assertTrue("the text stays a text", text.isTextLayer)
        assertNotNull("and a live array", text.array)
        assertEquals(90f, norm(text.item().rotationDeg - item0.rotationDeg), 0.5f)
        assertEquals("I1: the text array's cache", 0, differing(text.bitmap, freshRender(c, text.dataSnapshot())))
        assertTrue("the source is still being edited", raster.array?.spec?.editingSource == true)
        val tall = inkBounds(raster.bitmap)
        assertEquals("the source turned a quarter: $wide -> $tall", wide.width().toFloat(), tall.height().toFloat(), 2f)
        u.shot("verify-folder-turned")

        u.ui.twoFingerUndo()
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals(text0, text.textData)
        assertEquals(textSpec0, text.array!!.spec)
        assertArrayEquals("the text's copies", textPx0, pixels(text.bitmap))
        assertEquals("the source being edited, as it was", rasterData0, raster.dataSnapshot())
        assertArrayEquals("its pixels", rasterPx0, pixels(raster.bitmap))
        Smoke.assertQuiet(c, "folder transform")
    }
}
