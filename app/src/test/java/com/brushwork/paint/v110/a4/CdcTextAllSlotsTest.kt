package com.brushwork.paint.v110.a4

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R6, A4 step 4; UQ7): Change Drawing Color on a text layer maps every color slot of each
 * recolored text (its color, outline, box fill and box border) as ONE step and keeps the layer a
 * text layer whose pixels are its new texts rendered. With a pixel selection only the texts whose
 * ink it touches change, whole; the others keep every value.
 */
@RunWith(RobolectricTestRunner::class)
class CdcTextAllSlotsTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private val spec = TextSpec(
        sizePx = 40f, color = 0xFF202020.toInt(), strokeWidthPx = 3f, strokeColor = 0xFFFFD000.toInt(),
        box = TextBoxSpec(padding = 8f, fill = true, fillColor = 0xFFE0F0FF.toInt(), borderWidth = 3f, borderColor = 0xFF8000C0.toInt()),
    )

    private fun textLayer(c: EditorController, items: List<TextItem>): String {
        val text = TextCodec.encodeAll(items)
        assertTrue(DataRender.apply(c, c.vec, LayerData(text = text), "Text", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        return text
    }

    private fun run(c: EditorController): (Int) -> Int {
        c.startFilter(cdc)
        val s: FilterSession = c.filterSession!!
        s.debounceMs = 0L
        s.update("color", 0xFF00A040.toInt())
        s.update("amount", 70f)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        val f = cdc.colorMapper(s.values)!!
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
        return f
    }

    /** The texts drawn as the layer's cache draws them. */
    private fun render(items: List<TextItem>): IntArray {
        val b = BitmapUtils.createLayerBitmap(kit.w, kit.h)
        val cv = Canvas(b)
        for (i in items) TextRenderer.drawItem(cv, i, TextRenderer.prepare(i), null)
        return kit.pixels(b).also { b.recycle() }
    }

    @Test
    fun everySlotOfATextChangesAsOneStep() {
        val c = kit.controller()
        val text = textLayer(c, listOf(TextItem("Brushwork", spec, cx = 256f, cy = 190f, id = 1)))
        val pixels = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val f = run(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        val after = TextCodec.decodeAll(c.vec.textData!!)
        assertEquals(1, after.size)
        val sp = after[0].spec
        assertEquals("color", f(spec.color), sp.color)
        assertEquals("outline", f(spec.strokeColor), sp.strokeColor)
        assertEquals("box fill", f(spec.box.fillColor), sp.box.fillColor)
        assertEquals("box border", f(spec.box.borderColor), sp.box.borderColor)
        // Nothing but the colors.
        val o = TextCodec.decodeAll(text)[0].spec
        assertEquals(o.copy(color = sp.color, strokeColor = sp.strokeColor, box = o.box.copy(fillColor = sp.box.fillColor, borderColor = sp.box.borderColor)), sp)
        assertNull("still a text layer", c.vec.vector)
        assertArrayEquals(render(after), kit.pixels(c.vec.bitmap))
        c.undo()
        assertEquals(text, c.vec.textData)
        assertArrayEquals(pixels, kit.pixels(c.vec.bitmap))
        c.redo()
        assertEquals(after, TextCodec.decodeAll(c.vec.textData!!))
    }

    @Test
    fun aPixelSelectionRecolorsTheTextsItTouches() {
        val c = kit.controller()
        val one = TextItem("Brush", spec, cx = 150f, cy = 100f, id = 1)
        val two = TextItem("work", spec.copy(color = 0xFF0050A0.toInt()), cx = 330f, cy = 270f, id = 2)
        val original = TextCodec.decodeAll(textLayer(c, listOf(one, two)))
        c.setSelection(kit.rectSelection(100, 80, 200, 120), recordUndo = false)
        val f = run(c)
        val after = TextCodec.decodeAll(c.vec.textData!!)
        assertEquals(f(one.spec.color), after[0].spec.color)
        assertEquals(f(one.spec.box.borderColor), after[0].spec.box.borderColor)
        assertEquals("untouched text, every value kept", original[1], after[1])
        assertArrayEquals(render(after), kit.pixels(c.vec.bitmap))
    }
}
