package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Rect
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa16.item
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.BLUE
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
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 final QA, item 11 (design §3.11) on the user's 392 dp phone: the Transform tool turns and
 * scales a text, a shape, a vector and an arrayed layer WITHOUT rasterizing them ("Rotate 90°
 * clockwise", "Numbers" › "Scale", a finger dragging the box), ✓ "Apply transform edit" is ONE
 * step, and each layer is then edited with its own tool (Text, Shape, Path, Array). Distort on a
 * text says "Rasterize to deform", on an array "Apply the array to deform". A folder holding a
 * text and pixels turns as ONE step that a two-finger tap takes back exactly.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.transformobjectssandbox"])
class Qa17TransformObjectsUiTest {

    @Test
    fun dataLayersTransformWithoutRasterizingAt392Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 120_000)
        val h = ChromeHarness()
        h.section("text: turned and scaled, still text; Distort asks to rasterize; the Text tool edits it") { text(Qa17ArrayUi(h)) }
        h.section("shape: moved by a finger, turned and scaled, still a shape; the Shape tool edits it") { shape(Qa17ArrayUi(h)) }
        h.section("vector: turned and scaled, still paths; the Path tool edits it") { vector(Qa17ArrayUi(h)) }
        h.section("array: turned and scaled, still an array; the Array sheet edits it") { array(Qa17ArrayUi(h)) }
        h.section("folder with a text and pixels: one step, undone exactly") { folder(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    /** The Transform tool from the tool menu, with what it lifted from the active layer. */
    private fun transform(u: Qa17ArrayUi, lifted: TransformTool.Lifted): TransformTool {
        u.tool("Transform")
        val t = u.c.currentTool as TransformTool
        assertTrue("lifted", Smoke.pumpUntil(Qa17ArrayUi.WAIT_MS) { settle(1); t.lifted != null })
        settle()
        assertEquals(lifted, t.lifted)
        return t
    }

    /** ✓ "Apply transform edit": ONE step [label]. */
    private fun applyTransform(u: Qa17ArrayUi, label: String) {
        val n = u.steps()
        u.applyEdit("Apply transform edit")
        assertEquals("one step", n + 1, u.steps())
        assertEquals(label, u.c.undoManager.undoLabel)
    }

    /** "Numbers" › "Scale" typed, the sheet closed again. */
    private fun scaleByNumbers(u: Qa17ArrayUi, percent: String) {
        u.press("Numbers")
        settle()
        u.typeField("Scale", percent)
        settle()
        if (has("Close", exact = true)) click("Close", exact = true)
        settle()
    }

    private fun norm(deg: Float): Float = ((deg % 360f) + 360f) % 360f

    private fun text(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        u.tool("Text")
        val tt = c.currentTool as TextTool
        u.ui.tap(200f, 150f)
        assertTrue("a tap opens the text editor", tt.editorOpen)
        SmokeUi.field("Text").type("Ab")
        settle()
        click("OK", exact = true)
        u.applyEdit("Apply text edit")
        val layer = c.activeLayer
        assertTrue(layer.isTextLayer)
        val before = layer.item()

        val t = transform(u, TransformTool.Lifted.TEXT)
        assertEquals("Distort on a text", TransformLabels17.RASTERIZE_TO_DEFORM, u.stateOf("Distort"))
        assertEquals(TransformLabels17.RASTERIZE_TO_FREE_DEFORM, u.stateOf(TransformLabels17.FREE_DEFORM))
        assertFalse("a text kept as text is never mirrored", has("Flip horizontally", exact = true))
        // A tap on the dimmed chip shows its caption and the way out; it switches nothing.
        u.press("Distort")
        assertEquals(TransformTool.Mode.FREE, t.mode)
        assertTrue(has(TransformLabels17.RASTERIZE_AND_DEFORM, exact = true))
        u.ui.backKey()
        settle()
        u.press("Rotate 90° clockwise")
        scaleByNumbers(u, "150")
        applyTransform(u, TransformTool.TRANSFORM_LABEL)
        assertTrue("still a text layer", layer.isTextLayer)
        val turned = layer.item()
        assertEquals("the same words", before.text, turned.text)
        assertEquals("turned a quarter", 90f, norm(turned.rotationDeg - before.rotationDeg), 0.5f)
        assertEquals("type 150 %", before.spec.sizePx * 1.5f, turned.spec.sizePx, before.spec.sizePx * 0.02f)
        assertEquals("I1: the pixels are the text's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))

        // The Text tool edits it: a tap on it loads it, a second opens the editor.
        u.tool("Text")
        u.ui.tap(turned.cx, turned.cy)
        if (!tt.editorOpen) u.ui.tap(turned.cx, turned.cy)
        assertTrue("the editor opens for the turned text", tt.editorOpen && tt.editingLayer === layer)
        SmokeUi.field("Text").type("Hi")
        settle()
        click("OK", exact = true)
        val n = u.steps()
        u.applyEdit("Apply text edit")
        assertEquals(n + 1, u.steps())
        assertEquals("Edit text", c.undoManager.undoLabel)
        val edited = layer.item()
        assertEquals("Hi", edited.text)
        assertEquals("still turned", turned.rotationDeg, edited.rotationDeg, 0.01f)
        assertEquals("still 150 %", turned.spec.sizePx, edited.spec.sizePx, 0.01f)
        Smoke.assertQuiet(c, "transform text")
    }

    private fun shape(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        u.tool("Shape")
        val st = c.currentTool as ShapeTool
        u.ui.stroke(140f to 110f, 200f to 140f, 260f to 190f)
        assertTrue("a pending shape", st.hasPendingWork)
        u.applyEdit("Apply shape edit")
        val layer = c.activeLayer
        val before = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "a shape layer" }

        transform(u, TransformTool.Lifted.SHAPE)
        assertEquals("Distort on a shape", TransformLabels17.RASTERIZE_TO_DEFORM, u.stateOf("Distort"))
        // A finger drags the box 40 px right and 20 px down, then a quarter turn and 150 % (about the centre).
        u.ui.stroke(before.cx to before.cy, before.cx + 20f to before.cy + 10f, before.cx + 40f to before.cy + 20f)
        u.press("Rotate 90° clockwise")
        scaleByNumbers(u, "150")
        applyTransform(u, TransformTool.TRANSFORM_LABEL)
        val turned = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "still a shape layer" }
        assertEquals(before.type, turned.type)
        assertEquals("moved by the finger", before.cx + 40f, turned.cx, 2f)
        assertEquals(before.cy + 20f, turned.cy, 2f)
        assertEquals("turned a quarter", 90f, norm(turned.rotation - before.rotation), 0.5f)
        assertEquals("150 %", before.w * 1.5f, turned.w, 1f)
        assertEquals(before.h * 1.5f, turned.h, 1f)

        // The Shape tool opens it with a tap; a finger moves it back 40 px; ✓.
        u.tool("Shape")
        // (Its outline: a tap on the drawn shape, nearest its centre.)
        val hit = inkNear(layer.bitmap, Vec2(turned.cx, turned.cy), r = 90)
        u.ui.tap(hit.x, hit.y)
        assertSame("the tap opens the shape", layer, st.editingLayer)
        u.ui.stroke(turned.cx to turned.cy, turned.cx - 20f to turned.cy, turned.cx - 40f to turned.cy)
        val n = u.steps()
        u.applyEdit("Apply shape edit")
        assertEquals(n + 1, u.steps())
        val edited = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "still a shape layer" }
        assertEquals(turned.cx - 40f, edited.cx, 2f)
        assertEquals("still turned", turned.rotation, edited.rotation, 0.01f)
        Smoke.assertQuiet(c, "transform shape")
    }

    private fun vector(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        val layer = requireNotNull(c.addVectorLayer("Arch"))
        settle()
        u.tool("Path")
        for ((x, y) in listOf(150f to 160f, 200f to 110f, 250f to 160f)) u.ui.tap(x, y)
        u.applyEdit("Apply path edit")
        val before = (layer.vector!!.objects.single() as VPath).spline!!.points.map { Vec2(it.x, it.y) }
        assertEquals(3, before.size)

        transform(u, TransformTool.Lifted.VECTOR)
        assertNull("Distort is offered for paths", u.stateOf("Distort"))
        u.press("Rotate 90° clockwise")
        scaleByNumbers(u, "150")
        applyTransform(u, TransformTool.TRANSFORM_OBJECTS_LABEL)
        assertTrue("still a vector layer", layer.isVectorLayer)
        val after = (layer.vector!!.objects.single() as VPath).spline!!.points.map { Vec2(it.x, it.y) }
        // A quarter turn clockwise on screen (y down) and 150 %: (dx, dy) becomes 1.5 (-dy, dx).
        for (i in 1 until before.size) {
            val d0 = before[i] - before[0]
            val d1 = after[i] - after[0]
            assertEquals("point $i turned and scaled: $before -> $after", -1.5f * d0.y, d1.x, 0.75f)
            assertEquals(1.5f * d0.x, d1.y, 0.75f)
        }

        // The Path tool reopens it with a tap on its line; a finger drags its first point 30 px left; ✓.
        u.tool("Path")
        val curve = c.currentTool as CurveTool
        // (The drawn line nearest the middle point: a spline point may sit off the curve.)
        val onLine = inkNear(layer.bitmap, after[1], r = 80)
        u.ui.tap(onLine.x, onLine.y)
        assertTrue("a tap on the line reopens the path (at $onLine)", curve.isReopened)
        val p0 = curve.spline!!.points.map { Vec2(it.x, it.y) }.minBy { it.distanceTo(after[0]) }
        QaCurves.drag(u.s, p0, Vec2(-30f, 0f))
        val n = u.steps()
        u.applyEdit("Apply path edit")
        assertEquals(n + 1, u.steps())
        assertEquals("Edit path", c.undoManager.undoLabel)
        assertTrue(layer.isVectorLayer)
        val edited = (layer.vector!!.objects.single() as VPath).spline!!.points.map { Vec2(it.x, it.y) }
        assertTrue("the point followed the finger: $edited", edited.any { it.distanceTo(p0 + Vec2(-30f, 0f)) < 2f })
        assertTrue("the others stayed", after.drop(1).all { a -> edited.any { it.distanceTo(a) < 0.5f } })
        Smoke.assertQuiet(c, "transform vector")
    }

    /** The opaque pixel of [b] nearest [p] (a point on a drawn line). */
    private fun inkNear(b: Bitmap, p: Vec2, r: Int = 30): Vec2 {
        var best = p
        var bd = Float.MAX_VALUE
        for (y in (p.y.toInt() - r).coerceAtLeast(0)..(p.y.toInt() + r).coerceAtMost(b.height - 1)) {
            for (x in (p.x.toInt() - r).coerceAtLeast(0)..(p.x.toInt() + r).coerceAtMost(b.width - 1)) {
                if (b.getPixel(x, y) ushr 24 < 200) continue
                val d = Vec2(x + 0.5f, y + 0.5f).distanceTo(p)
                if (d < bd) { bd = d; best = Vec2(x + 0.5f, y + 0.5f) }
            }
        }
        assertTrue("ink near $p", bd < r)
        return best
    }

    /** The bounds of [color]'s pixels in [b]. */
    private fun boundsOf(b: Bitmap, color: Int): Rect {
        val px = pixels(b)
        val r = Rect(Int.MAX_VALUE, Int.MAX_VALUE, -1, -1)
        for (i in px.indices) if (px[i] == color) {
            val x = i % b.width
            val y = i / b.width
            r.union(x, y, x + 1, y + 1)
        }
        return r
    }

    private fun array(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val painted = c.activeLayer
        u.seed(painted, 60f, 120f, 100f, 160f, RED)
        // A plain layer arrays what is selected (else "Select some pixels, or pick a text, shape or vector layer").
        c.setSelection(u.rectSelection(56f, 116f, 104f, 164f), recordUndo = false)
        settle(4)
        u.press(ArrayLabels.FROM_SELECTION)
        u.settleRenders("array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        // "Array from selection" puts the array in a layer of its own, which is active.
        val layer = c.activeLayer
        assertNotNull(layer.array)
        val spec0 = layer.array!!.spec
        val wide = boundsOf(layer.bitmap, RED)
        assertTrue("copies in a row: $wide", wide.width() > 2 * wide.height())

        transform(u, TransformTool.Lifted.ARRAY)
        assertEquals("Distort on an array", ArrayLabels.DEFORM_REFUSAL, u.stateOf("Distort"))
        u.press("Rotate 90° clockwise")
        scaleByNumbers(u, "150")
        applyTransform(u, TransformTool.TRANSFORM_LABEL)
        u.settleRenders("turned")
        assertNotNull("still an array", layer.array)
        assertNotEquals("its spec turned with it", spec0, layer.array!!.spec)
        val tall = boundsOf(layer.bitmap, RED)
        assertTrue("copies in a column now: $tall", tall.height() > 2 * tall.width())
        assertEquals("each copy 150 %: $wide -> $tall", wide.height() * 1.5f, tall.width().toFloat(), 3f)
        assertEquals("I1: the cache is the array's own render", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))

        // Layer ⋮ "Edit array": the Array sheet; Count 5, one step, the column grows.
        u.layerMenu(ArrayLabels.EDIT)
        u.settleRenders("open")
        assertEquals(ToolId.ARRAY, c.activeToolId)
        u.showArraySheet()
        val n = u.steps()
        u.typeField("Count", "5")
        u.settleRenders("count")
        assertEquals(n + 1, u.steps())
        assertEquals(ArrayLabels.EDIT, c.undoManager.undoLabel)
        assertEquals(5, layer.array!!.spec.count)
        val taller = boundsOf(layer.bitmap, RED)
        assertTrue("five copies down the column: $tall -> $taller", taller.height() > tall.height() && abs(taller.width() - tall.width()) <= 2)
        assertEquals("I1", 0, differing(layer.bitmap, freshRender(c, layer.dataSnapshot())))
        Smoke.assertQuiet(c, "transform array")
    }

    private fun folder(u: Qa17ArrayUi) {
        u.editor()
        val c = u.c
        val pixelsLayer = c.activeLayer
        u.seed(pixelsLayer, 60f, 60f, 140f, 120f, BLUE)
        val f = requireNotNull(c.putInNewFolder(pixelsLayer))
        c.selectLayer(pixelsLayer)
        settle()
        u.tool("Text")
        val tt = c.currentTool as TextTool
        u.ui.tap(260f, 160f)
        assertTrue(tt.editorOpen)
        SmokeUi.field("Text").type("Ab")
        settle()
        click("OK", exact = true)
        u.applyEdit("Apply text edit")
        val textLayer = c.activeLayer
        assertTrue(textLayer.isTextLayer)
        assertEquals("the text went into the folder", f.id, textLayer.parentId)

        // The folder's row tapped in the layer window: the folder is what is transformed.
        u.openLayers()
        click(f.name, exact = true)
        settle()
        u.closeLayers()
        assertSame(f, c.activeLayer)
        val textBefore = textLayer.textData
        val textPixels = pixels(textLayer.bitmap)
        val blueBefore = pixels(pixelsLayer.bitmap)
        val n = u.steps()
        transform(u, TransformTool.Lifted.FOLDER)
        assertEquals("Distort works on one layer", u.stateOf("Distort"))
        assertEquals(TransformLabels17.ONE_LAYER, u.stateOf(TransformLabels17.FREE_DEFORM))
        u.press("Rotate 90° clockwise")
        applyTransform(u, TransformTool.TRANSFORM_LABEL)
        assertEquals(n + 1, u.steps())
        assertTrue("the text stays a text", textLayer.isTextLayer)
        assertEquals(90f, norm(textLayer.item().rotationDeg - TextCodec.decode(textBefore)!!.rotationDeg), 0.5f)
        assertFalse("the pixels turned too", blueBefore.contentEquals(pixels(pixelsLayer.bitmap)))
        u.shot("transform-folder")

        // Two fingers on the canvas: both children back exactly.
        u.ui.twoFingerUndo()
        u.settleRenders("undone")
        assertEquals(n, u.steps())
        assertEquals(textBefore, textLayer.textData)
        assertArrayEquals("the text's pixels", textPixels, pixels(textLayer.bitmap))
        assertArrayEquals("the pixels", blueBefore, pixels(pixelsLayer.bitmap))
        Smoke.assertQuiet(c, "transform folder")
    }
}
