package com.brushwork.paint.v110.a4

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLayerData
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R6, A4 step 4): on a layer with a live array, Change Drawing Color recolors the source
 * objects and the copies follow them; the array, its tint stops included, is kept, so the copies
 * keep their tint over the new colors. A pixel selection that touches only a copy recolors that
 * copy's source (and so every copy of it).
 */
@RunWith(RobolectricTestRunner::class)
class CdcArrayTintKeptTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    private val tint = ArrayLook(tint = listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt())), tintStrength = 0.6f)

    /** Copies 150 px apart to the right: copy 1 at x + 150, copy 2 at x + 300. */
    private fun array(look: ArrayLook? = tint) =
        LayerArray(ArraySpec(mode = ArrayMode.LINE, count = 3, relativeX = 0f, constantX = 150f, constantY = 0f, look = look))

    private fun run(c: EditorController): (Int) -> Int {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        s.update("color", 0xFF00A040.toInt())
        s.update("amount", 80f)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        val f = cdc.colorMapper(s.values)!!
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
        return f
    }

    @Test
    fun aVectorArrayKeepsItsTintAndTheCopiesFollowTheSource() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(30f, 40f, 110f, 120f), kit.ellipse(70f, 250f)), "Add")
        c.vectors.updateArray(c.vec, array(), "Array")
        val before = c.vec.vector!!
        val arr = c.vec.array!!
        val steps = c.undoManager.undoCount
        val f = run(c)
        assertEquals(steps + 1, c.undoManager.undoCount)
        val expected = ObjectColorSlots.map(before, null, f)
        assertEquals(expected, c.vec.vector)
        assertSame("the array and its tint are kept", arr, c.vec.array)
        val drawn = ArrayDraw.effectiveVector(LayerData(vector = expected, array = arr))!!
        assertArrayEquals(kit.render(drawn), kit.pixels(c.vec.bitmap))
        c.undo()
        assertSame(before, c.vec.vector)
        assertSame(arr, c.vec.array)
    }

    @Test
    fun aTextArrayKeepsItsTint() {
        val c = kit.controller()
        val spec = TextSpec(sizePx = 36f, color = 0xFF202020.toInt(), strokeWidthPx = 2f, strokeColor = 0xFFFFD000.toInt())
        val items = listOf(TextItem("Ab", spec, cx = 60f, cy = 100f, id = 1))
        val arr = array()
        assertTrue(DataRender.apply(c, c.vec, LayerData(text = TextCodec.encodeAll(items), array = arr), "Text", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        val f = run(c)
        val decoded = TextCodec.decodeAll(TextCodec.encodeAll(items))
        val mapped = ObjectColorSlots.mapText(TextLayerData(TextCodec.VERSION, decoded[0], decoded.drop(1)), null, f)
        assertEquals(listOf(mapped.item) + mapped.more, TextCodec.decodeAll(c.vec.textData!!))
        assertEquals("the array and its tint are kept", arr, c.vec.array)
        // The cache is the new data rendered with its array, as a fresh render of it.
        val c2 = kit.controller()
        assertTrue(DataRender.apply(c2, c2.vec, c.vec.dataSnapshot(), "Text", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        assertArrayEquals(kit.pixels(c2.vec.bitmap), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun aSelectionOverACopyRecolorsItsSource() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(30f, 40f, 110f, 120f), kit.ellipse(70f, 250f)), "Add")
        c.vectors.updateArray(c.vec, array(look = null), "Array")
        val before = c.vec.vector!!
        // Over copy 1 of the box (x 180..260) only: no source object is under it.
        c.setSelection(kit.rectSelection(200, 60, 240, 100), recordUndo = false)
        val f = run(c)
        val box = before.objects[0].id
        assertEquals(ObjectColorSlots.map(before, setOf(box), f), c.vec.vector)
        assertSame(before.objects[1], c.vec.vector!!.objects[1])
    }
}
