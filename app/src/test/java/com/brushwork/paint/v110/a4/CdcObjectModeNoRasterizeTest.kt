package com.brushwork.paint.v110.a4

import android.graphics.Canvas
import android.graphics.Rect
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.ObjectColorTargets
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLayerData
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.max

/**
 * v1.10 (R6, A4 step 3): Change Drawing Color on a vector, text or shape layer runs in object
 * mode and its preview shows the recolored objects while the layer's data, pixels, kind and
 * history stay as they were. Preview (C12): a whole vector or shape layer without an array tint
 * keeps the v1.9 pixel preview, exact up to rounding (CDC is affine); selected objects, a pixel
 * selection, an array tint or texts re-render the data with mapped colors and hold no copy of
 * the layer's pixels, and the session moves between the two when the selection changes. An edit of the layer during the
 * session refreshes the preview; a raster layer keeps the pixel path; a mask added during the
 * session still ends it.
 */
@RunWith(RobolectricTestRunner::class)
class CdcObjectModeNoRasterizeTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    /** A box (id 1), an ellipse (id 2) and a brush stroke (id 3). */
    private fun withObjects(): EditorController = kit.controller().also { c ->
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 140f, 120f), kit.ellipse(260f, 90f), kit.stroke(60f, 220f, 400f, 300f)), "Add")
    }

    private fun start(c: EditorController): FilterSession {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        assertTrue("preview ready", kit.idleUntil { s.hasPreview && !s.isRendering })
        return s
    }

    /** Sets [key] and waits for the matching preview. */
    private fun set(s: FilterSession, key: String, value: Any) {
        s.update(key, value)
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale })
    }

    /** Sets the pixel selection as a menu would and waits for the session to follow. */
    private fun select(c: EditorController, s: FilterSession, sel: Selection?) {
        c.setSelection(sel, recordUndo = false)
        // (The editor sends these every frame.)
        Snapshot.sendApplyNotifications()
        kit.idle()
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale && s.hasPreview })
    }

    /** What the canvas shows for the filtered layer. */
    private fun shown(c: EditorController): IntArray {
        val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        assertTrue(c.renderOverride!!.drawContent(Canvas(b)))
        return kit.pixels(b).also { b.recycle() }
    }

    private fun mapped(s: FilterSession, v: VectorContent, ids: Set<Long>? = null) = ObjectColorSlots.map(v, ids, cdc.colorMapper(s.values)!!)

    /**
     * [actual] equals [expected] up to rounding, compared premultiplied as the layer stores them
     * (an edge pixel of alpha 10 unpremultiplies a step of 1 into a step of 25): within 2 per
     * channel, as each side rounds once in Skia's 8-bit blend (the cache, the re-render) and the
     * pixel recolor rounds once more.
     */
    private fun assertWithinRounding(expected: IntArray, actual: IntArray) {
        assertEquals(expected.size, actual.size)
        fun pm(c: Int, shift: Int) = (((c shr shift) and 0xFF) * (c ushr 24) + 127) / 255
        for (i in expected.indices) {
            val e = expected[i]
            val a = actual[i]
            if (e == a) continue
            var worst = abs((e ushr 24) - (a ushr 24))
            for (shift in intArrayOf(16, 8, 0)) worst = max(worst, abs(pm(e, shift) - pm(a, shift)))
            if (worst > 2) fail("pixel (${i % kit.w}, ${i / kit.w}): expected %08X, shown %08X".format(e, a))
        }
    }

    @Test
    fun theWholeLayerPreviewsExactlyByPixelsAndNothingIsRasterized() {
        val c = withObjects()
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        c.color = 0xFFD02060.toInt()
        val s = start(c)
        assertTrue(s.objectMode)
        assertEquals(ObjectColorTargets.Rule.ALL, s.objectRule)
        assertTrue("C12: the whole layer keeps the v1.9 pixel preview", s.holdsPixelCopy)
        assertWithinRounding(kit.render(mapped(s, before)), shown(c))
        set(s, "color", 0xFF00A040.toInt())
        set(s, "amount", 40f)
        val expected = kit.render(mapped(s, before))
        assertNotEquals(0xFF00A040.toInt(), expected[80 * kit.w + 90])
        assertWithinRounding(expected, shown(c))
        // The layer itself is untouched: data, pixels, kind, history.
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        assertTrue(c.vec.isVectorLayer)
        assertEquals(steps, c.undoManager.undoCount)
        s.cancel()
        kit.idle()
        assertNull(c.renderOverride)
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
    }

    @Test
    fun selectedObjectsAloneChangeInARenderedPreview() {
        val c = withObjects()
        val before = c.vec.vector!!
        val pixels = kit.pixels(c.vec.bitmap)
        c.vectors.setSelection(c.vec, setOf(2L))
        val s = start(c)
        assertEquals(ObjectColorTargets.Rule.OBJECTS, s.objectRule)
        assertFalse("no copy of the layer's pixels", s.holdsPixelCopy)
        assertArrayEquals(kit.render(mapped(s, before, setOf(2L))), shown(c))
        set(s, "color", 0xFF00A040.toInt())
        assertArrayEquals(kit.render(mapped(s, before, setOf(2L))), shown(c))
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        s.cancel()
    }

    @Test
    fun aSelectionMadeOrDroppedDuringTheSessionSwitchesThePreview() {
        val c = withObjects()
        val before = c.vec.vector!!
        val s = start(c)
        assertTrue(s.holdsPixelCopy)
        // Over the box (id 1) alone: the box changes whole, the rest stays.
        select(c, s, kit.rectSelection(30, 30, 150, 130))
        assertEquals(ObjectColorTargets.Rule.SELECTION, s.objectRule)
        assertFalse(s.holdsPixelCopy)
        assertArrayEquals(kit.render(mapped(s, before, setOf(1L))), shown(c))
        select(c, s, null)
        assertEquals(ObjectColorTargets.Rule.ALL, s.objectRule)
        assertTrue(s.holdsPixelCopy)
        assertWithinRounding(kit.render(mapped(s, before)), shown(c))
        s.cancel()
    }

    @Test
    fun anArrayTintRendersTheCopiesWithTheirTintKept() {
        val c = withObjects()
        val tint = ArrayLook(tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.8f)
        val array = LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 3, relativeX = 0f, constantX = 30f, constantY = 20f, look = tint))
        c.vectors.updateArray(c.vec, array, "Array")
        val before = c.vec.vector!!
        val s = start(c)
        assertEquals(ObjectColorTargets.Rule.ALL, s.objectRule)
        assertFalse("recolor and tint do not commute: rendered", s.holdsPixelCopy)
        val expected = ArrayDraw.effectiveVector(LayerData(vector = mapped(s, before), array = c.vec.array))!!
        assertArrayEquals(kit.render(expected), shown(c))
        s.cancel()
    }

    @Test
    fun anEditOfTheLayerDuringTheSessionRefreshesThePreview() {
        val c = withObjects()
        val s = start(c)
        c.vectors.addObjects(c.vec, listOf(kit.box(300f, 200f, 380f, 260f)), "Add")
        // (The editor sends these every frame.)
        Snapshot.sendApplyNotifications()
        kit.idle()
        assertTrue(kit.idleUntil { !s.isRendering && !s.previewStale && s.hasPreview })
        assertEquals(4, c.vec.vector!!.objects.size)
        assertWithinRounding(kit.render(mapped(s, c.vec.vector!!)), shown(c))
        s.cancel()
    }

    @Test
    fun textAndShapeLayersPreviewWithoutRasterizing() {
        val c = kit.controller()
        val spec = TextSpec(sizePx = 44f, color = 0xFF202020.toInt(), strokeWidthPx = 3f, strokeColor = 0xFFFFD000.toInt())
        val items = listOf(TextItem("Brush", spec, cx = 150f, cy = 100f, id = 1), TextItem("work", spec, cx = 330f, cy = 260f, id = 2))
        val text = TextCodec.encodeAll(items)
        // Set and rendered as the editor does (DataRender: the cache shows exactly the texts).
        assertTrue(DataRender.apply(c, c.vec, LayerData(text = text), "Text", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        fun textPixels(f: (Int) -> Int, ids: Set<Int>?): IntArray {
            val m = ObjectColorSlots.mapText(TextLayerData(TextCodec.VERSION, items[0], items.drop(1)), ids, f)
            val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
            val cv = Canvas(b)
            for (i in listOf(m.item) + m.more) TextRenderer.drawItem(cv, i, TextRenderer.prepare(i), null)
            return kit.pixels(b).also { b.recycle() }
        }
        var s = start(c)
        assertTrue(s.objectMode)
        // Skia antialiases a glyph by its color: a text layer is always re-rendered.
        assertFalse(s.holdsPixelCopy)
        assertArrayEquals(textPixels(cdc.colorMapper(s.values)!!, null), shown(c))
        // A selection over "Brush" alone: rendered, "work" unchanged.
        select(c, s, kit.rectSelection(100, 80, 200, 120))
        assertFalse(s.holdsPixelCopy)
        assertArrayEquals(textPixels(cdc.colorMapper(s.values)!!, setOf(1)), shown(c))
        s.cancel()
        c.setSelection(null, recordUndo = false)
        assertSame(text, c.vec.textData)

        val ring = ShapeObject(ShapeType.ELLIPSE, cx = 256f, cy = 192f, w = 200f, h = 150f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f)
        val shape = ShapeCodec.encode(ring)
        assertTrue(DataRender.apply(c, c.vec, LayerData(shape = shape), "Shape", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        s = start(c)
        assertTrue(s.objectMode)
        val after = VectorContent(objects = listOf(VShape(0L, shape = ObjectColorSlots.mapShape(ring, cdc.colorMapper(s.values)!!))))
        assertWithinRounding(kit.render(after), shown(c))
        s.cancel()
        assertSame(shape, c.vec.shapeData)
    }

    @Test
    fun aRasterLayerKeepsThePixelPath() {
        val c = withObjects()
        c.selectLayer(0)
        c.startFilter(cdc)
        val s = c.filterSession!!
        assertFalse(s.objectMode)
        assertTrue(s.holdsPixelCopy)
        assertNull(s.objectRule)
        s.cancel()
    }

    @Test
    fun aMaskAddedDuringTheSessionEndsItSafely() {
        val c = withObjects()
        val before = c.vec.vector!!
        val s = start(c)
        c.vec.mask = BitmapUtils.createMaskBitmap(kit.w, kit.h)
        s.update("amount", 50f)
        assertTrue(kit.idleUntil { s.isClosed })
        assertEquals("The layer changed, so ${cdc.name} was cancelled", c.message)
        assertNull(c.filterSession)
        assertNull(c.renderOverride)
        assertSame(before, c.vec.vector)
    }
}
