package com.brushwork.paint.tools.vector

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Geometry
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.7 item 11 (design §3.11, area C): [ShapeTransforms.mapped] maps a shape layer's data
 * exactly (`ShapeAffine`): a move, scale, rotation or flip keeps the shape's type, a skew of a
 * rectangle gives a point shape with the mapped outline; the stroke width, the corner radius,
 * each point's own radius and the brush stay in document px. A projective map or unreadable data
 * gives null (the pixel lift).
 */
class ShapeTransformsTest {

    private val rect = ShapeObject(type = ShapeType.RECTANGLE, cx = 100f, cy = 80f, w = 120f, h = 60f, strokeWidth = 8f, corner = CornerStyle.ROUND, cornerRadius = 10f)

    @Test
    fun aMoveShiftsTheBox() {
        val t = map(rect, Affine2.translate(10f, -20f))
        assertEquals(rect.copy(cx = 110f, cy = 60f), t)
    }

    @Test
    fun aScaleKeepsTheWidthsInDocumentPx() {
        val pencil = BrushLibrary.byId("pencil")!!
        val o = rect.copy(strokeWith = ShapeStroke.BRUSH, brushPreset = pencil)
        val t = map(o, Affine2.scaleAbout(Vec2(100f, 80f), 2f, 2f))
        assertEquals(ShapeType.RECTANGLE, t.type)
        assertEquals(240f, t.w, 1e-3f)
        assertEquals(120f, t.h, 1e-3f)
        assertEquals(8f, t.strokeWidth)
        assertEquals(10f, t.cornerRadius)
        assertEquals(pencil, t.brushPreset)
    }

    @Test
    fun aFlipAndARotationKeepARegularShape() {
        val star = ShapeObject(type = ShapeType.STAR, starPoints = 5, cx = 150f, cy = 150f, w = 100f, h = 95f)
        val flip = Affine2.scaleAbout(Vec2(150f, 150f), -1f, 1f)
        val flipped = map(star, flip)
        assertEquals(ShapeType.STAR, flipped.type)
        assertNull(flipped.points)
        assertMappedOutline(star, flip, flipped)
        val turn = Affine2.rotateAbout(Vec2(0f, 0f), 30f)
        val turned = map(star, turn)
        assertNull(turned.points)
        assertMappedOutline(star, turn, turned)
    }

    @Test
    fun aSkewGivesAPointShapeWithTheMappedOutline() {
        val o = rect.copy(cornerRadius = 0f, corner = CornerStyle.SHARP, rotation = 20f)
        val skew = Affine2(1f, 0f, 0.5f, 1f, 5f, 7f)
        val t = map(o, skew)
        assertEquals(ShapeType.RECTANGLE, t.type)
        assertNotNull(t.points)
        assertMappedOutline(o, skew, t)
    }

    @Test
    fun pointRadiiStayInDocumentPx() {
        val pts = ShapePoints.fromRegular(ShapeType.RECTANGLE, rect.outlineParams).mapIndexed { i, p -> if (i == 1) p.copy(radius = 12f) else p }
        val o = rect.copy(points = pts, corner = CornerStyle.SHARP, cornerRadius = 0f)
        val t = map(o, Affine2.scaleAbout(Vec2(0f, 0f), 3f, 1.5f))
        assertEquals(12f, t.points!![1].radius)
        assertNull(t.points!![0].radius)
    }

    @Test
    fun aProjectiveMapOrBadDataIsRefused() {
        val data = ShapeCodec.encode(rect)
        assertNull(ShapeTransforms.mapped(data, floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0.001f, 0f, 1f)))
        assertNull(ShapeTransforms.mapped(data, floatArrayOf(0f, 0f, 0f, 0f, 0f, 0f, 0f, 0f, 1f)))
        assertNull(ShapeTransforms.mapped("{}", Affine2.translate(1f, 1f).toArray9()))
        assertNull(ShapeTransforms.mapped("not json", Affine2.translate(1f, 1f).toArray9()))
    }

    private fun map(o: ShapeObject, m: Affine2): ShapeObject {
        val data = ShapeTransforms.mapped(ShapeCodec.encode(o), m.toArray9())
        assertNotNull(data)
        return ShapeCodec.decode(data)!!
    }

    /** [t]'s outline is [o]'s mapped by [m], within 0.01 px both ways. */
    private fun assertMappedOutline(o: ShapeObject, m: Affine2, t: ShapeObject) {
        val expected = ShapeOutlines.outline(o).transformed { m.map(it) }.flatten(0.001f)
        val actual = ShapeOutlines.outline(t).flatten(0.001f)
        for (line in actual) for (p in line.points) assertTrue("$p", distanceTo(p, expected) <= 0.01f)
        for (line in expected) for (p in line.points) assertTrue("$p", distanceTo(p, actual) <= 0.01f)
    }

    private fun distanceTo(p: Vec2, polys: List<Polyline>): Float {
        var best = Float.MAX_VALUE
        for (poly in polys) {
            val pts = poly.points
            for (i in 1 until pts.size) best = minOf(best, Geometry.distanceToSegment(p, pts[i - 1], pts[i]))
            if (poly.closed && pts.size > 2) best = minOf(best, Geometry.distanceToSegment(p, pts.last(), pts[0]))
        }
        return best
    }
}
