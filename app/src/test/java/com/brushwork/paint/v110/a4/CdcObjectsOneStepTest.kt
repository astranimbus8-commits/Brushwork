package com.brushwork.paint.v110.a4

import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
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
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 (R6, A4 step 4; I1, I2): ✓ on Change Drawing Color in object mode records ONE step named
 * after the filter, maps the targets' colors in the layer's data and re-renders it with them (the
 * pixels are a fresh render of the new data), and rasterizes nothing: the layer keeps its vector
 * content or shape. Undo and redo restore data and pixels exactly. Selected objects, or the
 * objects a pixel selection touches, change alone; a result equal to the layer says so and
 * records nothing.
 */
@RunWith(RobolectricTestRunner::class)
class CdcObjectsOneStepTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val cdc get() = FilterRegistry.byId("adjust.change_drawing_color")!!

    /** A box (id 1) with a Linear fill, an ellipse (id 2), a brush stroke (id 3), a Radial path (id 4). */
    private fun withObjects(): EditorController = kit.controller().also { c ->
        val linear = kit.box(40f, 40f, 140f, 120f).copy(fill = VPaint.Linear(40f, 40f, 140f, 120f, listOf(VStop(0f, 0xFFE04020.toInt()), VStop(1f, 0xFF2040E0.toInt()))))
        val radial = kit.box(300f, 220f, 420f, 320f).copy(fill = VPaint.Radial(360f, 270f, 60f, listOf(VStop(0f, 0xFFFFFF00.toInt()), VStop(1f, 0xFF008000.toInt()))))
        c.vectors.addObjects(c.vec, listOf(linear, kit.ellipse(260f, 90f), kit.stroke(60f, 220f, 280f, 330f), radial), "Add")
    }

    private fun start(c: EditorController, color: Int = 0xFF00A040.toInt(), amount: Float = 70f): FilterSession {
        c.startFilter(cdc)
        val s = c.filterSession!!
        s.debounceMs = 0L
        s.update("color", color)
        s.update("amount", amount)
        assertTrue(kit.idleUntil { s.hasPreview && !s.isRendering && !s.previewStale })
        return s
    }

    private fun apply(c: EditorController, s: FilterSession) {
        s.apply()
        assertTrue(kit.idleUntil { s.isClosed })
    }

    @Test
    fun theWholeLayerIsRecoloredAsOneStepAndStaysAVectorLayer() {
        val c = withObjects()
        val before = c.vec.vector!!
        val pixelsBefore = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        apply(c, s)
        val expected = ObjectColorSlots.map(before, null, f)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        assertEquals(expected, c.vec.vector)
        assertTrue(c.vec.isVectorLayer)
        assertArrayEquals("I1: the pixels are the new data rendered", kit.render(expected), kit.pixels(c.vec.bitmap))
        assertNull(c.renderOverride)
        assertNull(c.filterSession)
        // Every slot: stroke color, line, Linear and Radial stops, the shape's stroke and fill.
        val box = c.vec.vector!!.byId(1L) as VPath
        assertEquals((before.byId(1L) as VPath).fill.let { (it as VPaint.Linear).stops.map { st -> f(st.color) } }, (box.fill as VPaint.Linear).stops.map { it.color })
        assertEquals(f((before.byId(1L) as VPath).stroke!!.color), box.stroke!!.color)
        val radial = c.vec.vector!!.byId(4L) as VPath
        assertEquals(f(0xFF008000.toInt()), (radial.fill as VPaint.Radial).stops[1].color)

        c.undo()
        assertSame(before, c.vec.vector)
        assertArrayEquals(pixelsBefore, kit.pixels(c.vec.bitmap))
        c.redo()
        assertEquals(expected, c.vec.vector)
        assertArrayEquals(kit.render(expected), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun selectedObjectsAloneAreRecolored() {
        val c = withObjects()
        val before = c.vec.vector!!
        c.vectors.setSelection(c.vec, setOf(2L, 3L))
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        apply(c, s)
        val expected = ObjectColorSlots.map(before, setOf(2L, 3L), f)
        assertEquals(expected, c.vec.vector)
        assertSame(before.byId(1L), c.vec.vector!!.byId(1L))
        assertSame(before.byId(4L), c.vec.vector!!.byId(4L))
        assertArrayEquals(kit.render(expected), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun aPixelSelectionRecolorsTheObjectsItTouchesWhole() {
        val c = withObjects()
        val before = c.vec.vector!!
        // A corner of the box (id 1) alone.
        c.setSelection(kit.rectSelection(30, 30, 70, 70), recordUndo = false)
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        apply(c, s)
        val expected = ObjectColorSlots.map(before, setOf(1L), f)
        assertEquals(expected, c.vec.vector)
        // Whole: the box's far corner, outside the selection, changed too.
        val far = 110 * kit.w + 130
        assertNotEquals(kit.render(before)[far], kit.pixels(c.vec.bitmap)[far])
        assertArrayEquals(kit.render(expected), kit.pixels(c.vec.bitmap))
    }

    @Test
    fun aResultEqualToTheLayerSaysSoAndRecordsNothing() {
        val c = withObjects()
        val before = c.vec.vector!!
        val steps = c.undoManager.undoCount
        val s = start(c, amount = 0f)
        apply(c, s)
        assertEquals("${cdc.name} didn't change anything here", c.message)
        assertEquals(steps, c.undoManager.undoCount)
        assertSame(before, c.vec.vector)
    }

    @Test
    fun aShapeLayerKeepsItsShape() {
        val c = kit.controller()
        val ring = ShapeObject(ShapeType.ELLIPSE, cx = 256f, cy = 192f, w = 200f, h = 150f, style = ShapeStyle.STROKE_FILL, strokeWidth = 6f,
            strokeColor = 0xFF102030.toInt(), fillColor = 0xFFF0E0D0.toInt())
        val shape = ShapeCodec.encode(ring)
        assertTrue(DataRender.apply(c, c.vec, LayerData(shape = shape), "Shape", Rect(0, 0, kit.w, kit.h), allowHidden = false))
        val pixelsBefore = kit.pixels(c.vec.bitmap)
        val steps = c.undoManager.undoCount
        val s = start(c)
        val f = cdc.colorMapper(s.values)!!
        apply(c, s)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(cdc.name, c.undoManager.undoLabel)
        val after = ShapeCodec.decode(c.vec.shapeData!!)
        assertNotNull(after)
        assertEquals(ObjectColorSlots.mapShape(ring, f), after)
        assertNull(c.vec.vector)
        assertFalse(c.vec.isVectorLayer)
        assertArrayEquals(kit.render(VectorContent(objects = listOf(VShape(0L, shape = after!!)))), kit.pixels(c.vec.bitmap))
        c.undo()
        assertEquals(shape, c.vec.shapeData)
        assertArrayEquals(pixelsBefore, kit.pixels(c.vec.bitmap))
        c.redo()
        assertEquals(after, ShapeCodec.decode(c.vec.shapeData!!))
    }
}
