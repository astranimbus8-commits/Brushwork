package com.brushwork.paint.v110.a4

import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.ObjectColorTargets
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.roundToInt

/**
 * v1.10 (R6, A4 step 1): the three rules of [ObjectColorTargets]: the layer's selected objects;
 * else, with a pixel selection, every object whose drawn coverage meets the selection, recolored
 * whole (never by its bounds: a long diagonal curve whose box meets the selection while its ink
 * passes beside it stays unchanged); else every object. `EditorController.startFilter` keeps the
 * selected objects for the session.
 */
@RunWith(RobolectricTestRunner::class)
class CdcSelectionRuleTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val green: (Int) -> Int = { c -> (c and 0xFF000000.toInt()) or 0x00FF00 }

    /** A smooth open curve from the top left to the bottom right, line only. */
    private fun diagonalCurve() = VPath(
        0,
        subpaths = listOf(VSubpath(listOf(VAnchor(20f, 20f), VAnchor(300f, 230f), VAnchor(490f, 370f)))),
        fill = null,
        stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 4f),
    )

    /** Curve 1, stroke 2 (parallel to it), a box 3 in the top right corner, a box 4 at the bottom left. */
    private fun content(): VectorContent {
        val c = kit.controller()
        c.vectors.addObjects(
            c.vec,
            listOf(diagonalCurve(), kit.stroke(60f, 20f, 490f, 370f), kit.box(440f, 30f, 480f, 70f), kit.box(30f, 300f, 90f, 360f)),
            "Add",
        )
        return c.vec.vector!!
    }

    /** The top right corner: inside the curve's and the stroke's bounds, far from their ink. */
    private fun corner() = kit.rectSelection(420, 20, 500, 90)

    @Test
    fun selectedObjectsAreTheTargetsWhateverThePixelSelection() {
        val v = content()
        val t = ObjectColorTargets.of(LayerData(vector = v), setOf(2L, 4L, 99L), corner())
        assertEquals(ObjectColorTargets.Rule.OBJECTS, t.rule)
        assertEquals("only ids that exist", setOf(2L, 4L), t.vectorIds)
        val m = t.map(LayerData(vector = v), green).vector!!
        for (id in listOf(1L, 3L)) assertSame(v.byId(id), m.byId(id))
        for (id in listOf(2L, 4L)) assertNotSame(v.byId(id), m.byId(id))
    }

    @Test
    fun selectedIdsOfVanishedObjectsFallBackToTheNextRule() {
        val v = content()
        val t = ObjectColorTargets.of(LayerData(vector = v), setOf(77L), null)
        assertEquals(ObjectColorTargets.Rule.ALL, t.rule)
        assertNull(t.vectorIds)
    }

    @Test
    fun aPixelSelectionHitsByDrawnCoverageNeverByBounds() {
        val v = content()
        val data = LayerData(vector = v)
        val t = ObjectColorTargets.of(data, emptySet(), corner())
        assertEquals(ObjectColorTargets.Rule.SELECTION, t.rule)
        assertEquals("only the box drawn inside the corner", setOf(3L), t.vectorIds)
        val m = t.map(data, green).vector!!
        assertSame("the diagonal curve stays unchanged", v.byId(1L), m.byId(1L))
        assertSame("the parallel stroke stays unchanged", v.byId(2L), m.byId(2L))
        assertSame(v.byId(4L), m.byId(4L))
        val box = m.byId(3L) as VPath
        assertEquals("recolored whole: the fill", 0xFF00FF00.toInt(), (box.fill as VPaint.Solid).color)
        assertEquals("and the line, outside the selection too", 0xFF00FF00.toInt(), box.stroke!!.color)
    }

    @Test
    fun aSelectionOnTheInkTouchesTheCurve() {
        val v = content()
        // Around (300, 230), the curve's middle anchor.
        val t = ObjectColorTargets.of(LayerData(vector = v), emptySet(), kit.rectSelection(290, 220, 310, 240))
        assertTrue(1L in t.vectorIds!!)
        assertFalse(3L in t.vectorIds!!)
    }

    @Test
    fun aSelectionTouchingNothingChangesNothing() {
        val v = content()
        val data = LayerData(vector = v)
        val t = ObjectColorTargets.of(data, emptySet(), kit.rectSelection(200, 330, 230, 360))
        assertEquals(emptySet<Long>(), t.vectorIds)
        assertSame(data, t.map(data, green))
    }

    @Test
    fun withoutSelectionsEveryObjectIsATarget() {
        val v = content()
        val data = LayerData(vector = v)
        val t = ObjectColorTargets.of(data, emptySet(), null)
        assertEquals(ObjectColorTargets.Rule.ALL, t.rule)
        assertNull(t.vectorIds)
        val m = t.map(data, green).vector!!
        for (o in v.objects) assertNotSame(o, m.byId(o.id))
    }

    @Test
    fun aLegacyShapeIsHitByItsRingNotByItsHollowMiddle() {
        val ring = ShapeObject(
            ShapeType.ELLIPSE, cx = 256f, cy = 192f, w = 200f, h = 150f, style = ShapeStyle.STROKE, strokeWidth = 6f,
            strokeColor = 0xFF202080.toInt(), fillColor = 0xFFF0C040.toInt(),
        )
        val data = LayerData(shape = ShapeCodec.encode(ring))
        val middle = ObjectColorTargets.of(data, emptySet(), kit.rectSelection(236, 172, 276, 212))
        assertFalse("the hollow middle", middle.shape)
        assertSame(data, middle.map(data, green))
        // The ring's right edge passes x = 356 at y = 192.
        val edge = ObjectColorTargets.of(data, emptySet(), kit.rectSelection(350, 186, 362, 198))
        assertTrue(edge.shape)
        val mapped = ShapeCodec.decode(edge.map(data, green).shape)!!
        assertEquals(0xFF00FF00.toInt(), mapped.strokeColor)
        assertEquals("the fill slot too (recolored whole)", 0xFF00FF00.toInt(), mapped.fillColor)
    }

    @Test
    fun aTextIsHitByItsInkNotByTheGapInsideItsBounds() {
        val spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt(), strokeWidthPx = 2f, strokeColor = 0xFFFF0000.toInt())
        val gap = TextItem("I            I", spec, cx = 200f, cy = 120f, id = 1)
        val far = TextItem("Far", spec, cx = 400f, cy = 320f, id = 2)
        val data = LayerData(text = TextCodec.encodeAll(listOf(gap, far)))
        val b = TextRenderer.prepare(gap).docBounds(gap)
        val cx = b.centerX().roundToInt()
        val cy = b.centerY().roundToInt()
        val inGap = ObjectColorTargets.of(data, emptySet(), kit.rectSelection(cx - 15, cy - 8, cx + 15, cy + 8))
        assertEquals(ObjectColorTargets.Rule.SELECTION, inGap.rule)
        assertEquals("the spaces paint nothing", emptySet<Int>(), inGap.textIds)
        assertSame(data, inGap.map(data, green))
        // The first "I", at the left end of the bounds.
        val onInk = ObjectColorTargets.of(data, emptySet(), kit.rectSelection(b.left.roundToInt(), cy - 8, b.left.roundToInt() + 20, cy + 8))
        assertEquals(setOf(1), onInk.textIds)
        val items = TextCodec.decodeAll(onInk.map(data, green).text)
        assertEquals(0xFF00FF00.toInt(), items[0].spec.color)
        assertEquals("the outline too", 0xFF00FF00.toInt(), items[0].spec.strokeColor)
        assertEquals("the other text keeps its colors", spec, items[1].spec)
    }

    @Test
    fun startFilterKeepsTheSelectedObjectsOfTheActiveLayer() {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, listOf(kit.box(40f, 40f, 120f, 120f), kit.box(200f, 40f, 280f, 120f)), "Add")
        c.vectors.setSelection(c.vec, setOf(2L))
        c.startFilter(FilterRegistry.byId("adjust.change_drawing_color")!!)
        kit.idle()
        val s = c.filterSession!!
        assertEquals(setOf(2L), s.selectedIds)
        assertEquals("still shown as selected", setOf(2L), c.vectors.selectedIds)
        s.cancel()
        kit.idle()
        // Objects selected on another layer are not this layer's targets.
        c.selectLayer(0)
        c.startFilter(FilterRegistry.byId("adjust.change_drawing_color")!!)
        kit.idle()
        assertEquals(emptySet<Long>(), c.filterSession!!.selectedIds)
        c.filterSession!!.cancel()
        kit.idle()
    }
}
