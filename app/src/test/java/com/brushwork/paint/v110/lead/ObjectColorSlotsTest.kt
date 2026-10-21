package com.brushwork.paint.v110.lead

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLayerData
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.ColorSlot
import com.brushwork.paint.vector.ObjectColorSlots
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.10 F0 (I35, C2, C4, R6 slots table; lead, frozen): [ObjectColorSlots] over every object kind:
 * the slots, reading, setting (a gradient becomes Solid; the fill-follows pin of C4), mapping
 * (gradient stops included, the same instance when nothing changes), a layer's content by ids,
 * legacy shapes and texts (color, outline, box fill and border, UQ7).
 */
class ObjectColorSlotsTest {

    private val red = 0xFFFF0000.toInt()
    private val green = 0xFF00FF00.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val black = 0xFF000000.toInt()

    /** Swaps red and blue, keeps alpha (so every color but grays changes). */
    private val swap: (Int) -> Int = { c -> (c and 0xFF00FF00.toInt()) or ((c shr 16) and 0xFF) or ((c and 0xFF) shl 16) }

    private fun stroke(color: Int) = VStroke(
        1, preset = BrushLibrary.defaultBrush, color = color, seed = 3L, stylus = false,
        points = PackedPoints(floatArrayOf(0f, 10f), floatArrayOf(0f, 10f), floatArrayOf(1f, 1f)),
    )

    private fun path(fill: VPaint?, strokeColor: Int?) = VPath(
        2, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f), VAnchor(10f, 0f), VAnchor(10f, 10f)), closed = true)),
        fill = fill, stroke = strokeColor?.let { VStrokeStyle(color = it, width = 3f) },
    )

    private fun shape(stroke: Int, fill: Int, follows: Boolean, style: ShapeStyle = ShapeStyle.STROKE_FILL, type: ShapeType = ShapeType.RECTANGLE) =
        VShape(3, shape = ShapeObject(type = type, w = 10f, h = 10f, style = style, strokeColor = stroke, fillColor = fill, fillFollowsColor = follows))

    private val linear = VPaint.Linear(0f, 0f, 10f, 0f, listOf(VStop(0f, red), VStop(0.5f, 0x80FF0000.toInt()), VStop(1f, blue)))
    private val radial = VPaint.Radial(5f, 5f, 5f, listOf(VStop(0f, green), VStop(1f, red)))

    @Test
    fun slotsAndColorsOfEveryObjectKind() {
        assertEquals(listOf(ColorSlot.STROKE), ObjectColorSlots.slotsOf(stroke(red)))
        assertEquals(red, ObjectColorSlots.colorOf(stroke(red), ColorSlot.STROKE))
        assertNull(ObjectColorSlots.colorOf(stroke(red), ColorSlot.FILL))

        val both = path(VPaint.Solid(green), red)
        assertEquals(listOf(ColorSlot.STROKE, ColorSlot.FILL), ObjectColorSlots.slotsOf(both))
        assertEquals(red, ObjectColorSlots.colorOf(both, ColorSlot.STROKE))
        assertEquals(green, ObjectColorSlots.colorOf(both, ColorSlot.FILL))
        assertEquals(listOf(ColorSlot.STROKE), ObjectColorSlots.slotsOf(path(null, red)))
        assertEquals(listOf(ColorSlot.FILL), ObjectColorSlots.slotsOf(path(VPaint.Solid(green), null)))
        // A gradient is a FILL slot that reads as null.
        assertEquals(listOf(ColorSlot.FILL), ObjectColorSlots.slotsOf(path(linear, null)))
        assertNull(ObjectColorSlots.colorOf(path(linear, null), ColorSlot.FILL))
        assertNull(ObjectColorSlots.colorOf(path(null, red), ColorSlot.FILL))

        val sh = shape(red, green, follows = false)
        assertEquals(listOf(ColorSlot.STROKE, ColorSlot.FILL), ObjectColorSlots.slotsOf(sh))
        assertEquals(red, ObjectColorSlots.colorOf(sh, ColorSlot.STROKE))
        assertEquals(green, ObjectColorSlots.colorOf(sh, ColorSlot.FILL))
        // A stroke-only closed shape keeps its fill slot; a line has none.
        assertEquals(green, ObjectColorSlots.colorOf(shape(red, green, false, ShapeStyle.STROKE), ColorSlot.FILL))
        assertEquals(listOf(ColorSlot.STROKE), ObjectColorSlots.slotsOf(shape(red, red, true, type = ShapeType.LINE)))
        for (o in listOf(stroke(red), both, sh)) for (s in listOf(ColorSlot.TEXT, ColorSlot.OUTLINE, ColorSlot.BOX_FILL, ColorSlot.BOX_BORDER)) {
            assertNull(ObjectColorSlots.colorOf(o, s))
            assertSame("no such slot: unchanged", o, ObjectColorSlots.set(o, s, blue))
        }
    }

    @Test
    fun setPaintsOneSlotAndAGradientBecomesSolid() {
        val st = stroke(red)
        assertEquals(blue, (ObjectColorSlots.set(st, ColorSlot.STROKE, blue) as VStroke).color)
        assertSame(st, ObjectColorSlots.set(st, ColorSlot.STROKE, red))
        assertSame(st, ObjectColorSlots.set(st, ColorSlot.FILL, blue))

        val p = path(linear, red)
        val filled = ObjectColorSlots.set(p, ColorSlot.FILL, green) as VPath
        assertEquals(VPaint.Solid(green), filled.fill)
        assertEquals(red, filled.stroke?.color)
        val stroked = ObjectColorSlots.set(p, ColorSlot.STROKE, blue) as VPath
        assertEquals(blue, stroked.stroke?.color)
        assertEquals(linear, stroked.fill)
        assertEquals(3f, stroked.stroke?.width)
        // A path without a fill gets none (turning a fill on is the editor's setPaintMode).
        val noFill = path(null, red)
        assertSame(noFill, ObjectColorSlots.set(noFill, ColorSlot.FILL, green))
        // The shape style never changes.
        val strokeOnly = shape(red, red, follows = true, style = ShapeStyle.STROKE)
        assertEquals(ShapeStyle.STROKE, (ObjectColorSlots.set(strokeOnly, ColorSlot.FILL, blue) as VShape).shape.style)
    }

    @Test
    fun theFillFollowsPinOfC4() {
        // The fill follows the main color (both red). A stroke-only edit keeps the fill red: pinned.
        val following = shape(red, red, follows = true)
        val s1 = (ObjectColorSlots.set(following, ColorSlot.STROKE, blue) as VShape).shape
        assertEquals(blue, s1.strokeColor)
        assertEquals(red, s1.fillColor)
        assertFalse("pinned: the fill no longer follows", s1.fillFollowsColor)
        // Back to the fill's color: it follows again (the equality rule of v1.9's recolor).
        val s2 = ObjectColorSlots.set(VShape(3, shape = s1), ColorSlot.STROKE, red) as VShape
        assertEquals(red, s2.shape.fillColor)
        assertTrue(s2.shape.fillFollowsColor)
        // A fill edit sets fillColor and follows only when it equals the stroke.
        val s3 = (ObjectColorSlots.set(following, ColorSlot.FILL, green) as VShape).shape
        assertEquals(green, s3.fillColor)
        assertEquals(red, s3.strokeColor)
        assertFalse(s3.fillFollowsColor)
        val s4 = (ObjectColorSlots.set(shape(red, green, false), ColorSlot.FILL, red) as VShape).shape
        assertTrue(s4.fillFollowsColor)
        // Nothing to change: the same instance.
        assertSame(following, ObjectColorSlots.set(following, ColorSlot.STROKE, red))
        // A map keeps fillFollowsColor = (fill' == stroke').
        val mapped = (ObjectColorSlots.mapObject(following, swap) as VShape).shape
        assertEquals(blue, mapped.strokeColor)
        assertEquals(blue, mapped.fillColor)
        assertTrue(mapped.fillFollowsColor)
        val split = ObjectColorSlots.mapShape(ShapeObject(strokeColor = red, fillColor = green, fillFollowsColor = false)) { c -> if (c == green) blue else c }
        assertEquals(red, split.strokeColor)
        assertEquals(blue, split.fillColor)
        assertFalse(split.fillFollowsColor)
        val joined = ObjectColorSlots.mapShape(ShapeObject(strokeColor = red, fillColor = green, fillFollowsColor = false)) { black }
        assertTrue(joined.fillFollowsColor)
    }

    @Test
    fun mapObjectCoversEverySlotAndGradientStops() {
        assertEquals(blue, (ObjectColorSlots.mapObject(stroke(red), swap) as VStroke).color)

        val lin = ObjectColorSlots.mapObject(path(linear, green), swap) as VPath
        val stops = (lin.fill as VPaint.Linear).stops
        assertEquals(listOf(blue, 0x800000FF.toInt(), red), stops.map { it.color })
        assertEquals(listOf(0f, 0.5f, 1f), stops.map { it.offset })
        assertEquals(green, lin.stroke?.color)
        val rad = ObjectColorSlots.mapObject(path(radial, null), swap) as VPath
        assertEquals(listOf(green, blue), (rad.fill as VPaint.Radial).stops.map { it.color })
        assertEquals(VPaint.Solid(red), (ObjectColorSlots.mapObject(path(VPaint.Solid(blue), null), swap) as VPath).fill)

        val sh = (ObjectColorSlots.mapObject(shape(red, blue, false), swap) as VShape).shape
        assertEquals(blue, sh.strokeColor)
        assertEquals(red, sh.fillColor)

        // Unchanged: the very same instance (identity, not equality), for every kind.
        val same: (Int) -> Int = { it }
        for (o in listOf(stroke(red), path(linear, red), path(radial, null), path(VPaint.Solid(red), red), shape(red, blue, false))) {
            assertSame(o, ObjectColorSlots.mapObject(o, same))
        }
        val gray = path(VPaint.Solid(0xFF808080.toInt()), black)
        assertSame(gray, ObjectColorSlots.mapObject(gray, swap))
        assertNotSame(path(linear, red), ObjectColorSlots.mapObject(path(linear, red), swap))
    }

    @Test
    fun mapContentByIdsShapesAndTexts() {
        val a = stroke(red).copy(id = 1)
        val b = path(VPaint.Solid(red), null).copy(id = 2)
        val c = shape(red, red, true).copy(id = 3)
        val content = VectorContent(objects = listOf(a, b, c), nextId = 4)
        val all = ObjectColorSlots.map(content, null, swap)
        assertEquals(listOf(blue, blue, blue), all.objects.map { ObjectColorSlots.colorOf(it, ObjectColorSlots.slotsOf(it).last()) })
        assertEquals(4L, all.nextId)
        val some = ObjectColorSlots.map(content, setOf(2L), swap)
        assertSame(a, some.objects[0])
        assertEquals(VPaint.Solid(blue), (some.objects[1] as VPath).fill)
        assertSame(c, some.objects[2])
        assertSame(content, ObjectColorSlots.map(content, setOf(99L), swap))
        assertSame(content, ObjectColorSlots.map(content, null) { it })

        val legacy = ShapeObject(strokeColor = red, fillColor = 0xFF808080.toInt(), fillFollowsColor = false)
        assertSame(legacy, ObjectColorSlots.mapShape(legacy) { it })
        assertEquals(blue, ObjectColorSlots.mapShape(legacy, swap).strokeColor)

        val box = TextBoxSpec(fill = true, fillColor = red, borderWidth = 2f, borderColor = blue)
        val t1 = TextItem(text = "a", spec = TextSpec(color = red, strokeWidthPx = 2f, strokeColor = blue, box = box), id = 1)
        val t2 = TextItem(text = "b", spec = TextSpec(color = red), id = 2)
        val data = TextLayerData(item = t1, more = listOf(t2))
        val mapped = ObjectColorSlots.mapText(data, null, swap)
        val s1 = mapped.item.spec
        assertEquals(listOf(blue, red, blue, red), listOf(s1.color, s1.strokeColor, s1.box.fillColor, s1.box.borderColor))
        assertEquals(2f, s1.box.borderWidth)
        assertEquals("a", mapped.item.text)
        assertEquals(blue, mapped.more[0].spec.color)
        // Only the texts asked for (by TextItem.id).
        val only2 = ObjectColorSlots.mapText(data, setOf(2), swap)
        assertSame(t1, only2.item)
        assertEquals(blue, only2.more[0].spec.color)
        assertSame(data, ObjectColorSlots.mapText(data, setOf(5), swap))
        assertSame(data, ObjectColorSlots.mapText(data, null) { it })
    }
}
