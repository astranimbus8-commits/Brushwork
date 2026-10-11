package com.brushwork.paint.tools.vector

import com.brushwork.paint.compat17.ArrowHeadsV17
import com.brushwork.paint.compat17.CornerStyleV17
import com.brushwork.paint.compat17.LineCapStyleV17
import com.brushwork.paint.compat17.ShapeCodecV17
import com.brushwork.paint.compat17.ShapeHandleV17
import com.brushwork.paint.compat17.ShapeObjectV17
import com.brushwork.paint.compat17.ShapePointV17
import com.brushwork.paint.compat17.ShapeStyleV17
import com.brushwork.paint.compat17.ShapeTypeV17
import com.brushwork.paint.core.Vec2
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.8 F (design §3.8, D23): the cog's v1.7 fallback with the real cog geometry. v1.7 reads a
 * cog as a rectangle with points and draws exactly its teeth (the outer contour, corners
 * included, within 0.01 px; the hole is v1.8's alone); v1.8 reads it back as the cog (no points,
 * its hole again); a cog that v1.7 saved again keeps its teeth; and a plain v1.7 shape goes
 * through v1.8 byte for byte. JVM (the outline geometry is pure Kotlin).
 */
class CogFallbackCodecTest {

    private val cogs: List<ShapeObject> = listOf(
        ShapeObject(type = ShapeType.COG, cx = 120f, cy = 90f, w = 160f, h = 140f, rotation = 20f),
        ShapeObject(type = ShapeType.COG, cx = 80f, cy = 70f, w = 120f, h = 120f, cogTeeth = 7, cogDepth = 0.35f, cogToothWidth = 0.4f, cogTaper = 0.6f, cogHole = 0.45f),
        ShapeObject(type = ShapeType.COG, cx = 60f, cy = 60f, w = 100f, h = 90f, rotation = -35f, corner = CornerStyle.ROUND, cornerRadius = 3f, cogTeeth = 16),
        ShapeObject(type = ShapeType.COG, cx = 60f, cy = 60f, w = 100f, h = 100f, corner = CornerStyle.BEVEL, cornerRadius = 2f, cogTeeth = 5, cogToothWidth = 0.2f, cogHole = 0.2f),
        ShapeObject(type = ShapeType.COG, cx = 200f, cy = 200f, w = 300f, h = 300f, corner = CornerStyle.INVERTED, cornerRadius = 1.5f, cogTeeth = 64, cogDepth = 0.05f),
        ShapeObject(type = ShapeType.COG, cx = 50f, cy = 50f, w = 80f, h = 80f, cogTeeth = 3, cogToothWidth = 0.8f, cogTaper = 0.9f, cogHole = 0.8f, style = ShapeStyle.STROKE_FILL),
    )

    /** How v1.7 draws [v17]: its custom points through the outline code v1.7 and v1.8 share. */
    private fun asV17Draws(v17: ShapeObjectV17): VectorPath {
        val pts = v17.points!!.map { ShapePoint(it.x, it.y) }
        val o = ShapeObject(
            type = ShapeType.valueOf(v17.type.name), cx = v17.cx, cy = v17.cy, w = v17.w, h = v17.h, rotation = v17.rotation,
            corner = CornerStyle.valueOf(v17.corner.name), cornerRadius = v17.cornerRadius, points = pts,
        )
        assertTrue(ShapeOutlines.isCustom(o))
        return ShapeOutlines.outline(o)
    }

    private fun vertices(p: VectorPath): List<Vec2> = p.flatten(0.05f).single().points.let { pts ->
        // (A closing point on top of the first one is the same vertex.)
        if (pts.size > 1 && pts.last().distanceTo(pts.first()) < 1e-3f) pts.dropLast(1) else pts
    }

    private fun assertSameOutline(what: String, expected: VectorPath, actual: VectorPath) {
        val e = vertices(expected)
        val a = vertices(actual)
        assertEquals("$what: vertices", e.size, a.size)
        for (i in e.indices) assertTrue("$what: vertex $i ${a[i]} vs ${e[i]}", a[i].distanceTo(e[i]) <= 0.01f)
    }

    @Test
    fun v17DrawsTheTeethOfEveryCog() {
        for (c in cogs) {
            val v17 = ShapeCodecV17.decode(ShapeCodec.encode(c))
            assertNotNull(v17)
            assertEquals(ShapeTypeV17.RECTANGLE, v17!!.type)
            // The teeth: the first contour of the cog's outline (with its corners), never the hole.
            val teeth = ShapeOutlines.contoursOf(ShapeOutlines.outline(c)).first()
            assertSameOutline("${c.cogTeeth} teeth, ${c.corner}", teeth, asV17Draws(v17))
            assertEquals(CogGeometry.vertexCount(c.cogParams), v17.points!!.size)
            // Every point is a sharp corner (the teeth are straight sides).
            assertTrue(v17.points!!.none { it.smooth || it.handleIn != null || it.handleOut != null || it.radius != null })
        }
    }

    @Test
    fun v18ReadsTheCogBackWithItsHole() {
        for (c in cogs) {
            val back = ShapeCodec.decode(ShapeCodec.encode(c))!!
            assertNull("v1.8 drops the fallback points", back.points)
            assertFalse(back.cogFallback)
            assertFalse(ShapeOutlines.isCustom(back))
            assertEquals(c, back)
            val outline = ShapeOutlines.outline(back)
            assertEquals(CogGeometry.contours(back.box, back.outlineParams).ops, outline.ops)
            assertEquals("${c.cogHole}", if (c.cogHole > 0f) 2 else 1, outline.ops.count { it is PathOp.MoveTo })
        }
    }

    @Test
    fun aCogThatV17SavedAgainKeepsItsTeeth() {
        for (c in cogs) {
            val v17 = ShapeCodecV17.decode(ShapeCodec.encode(c))!!
            val resaved = ShapeCodecV17.encode(v17)
            assertFalse(resaved, resaved.contains("cog"))
            val back = ShapeCodec.decode(resaved)!!
            // v1.7 knew nothing of the cog: a rectangle with the teeth as its own points.
            assertEquals(ShapeType.RECTANGLE, back.type)
            assertTrue(ShapeOutlines.isCustom(back))
            assertSameOutline("${c.cogTeeth} teeth resaved", ShapeOutlines.contoursOf(ShapeOutlines.outline(c)).first(), ShapeOutlines.outline(back))
            // ...and v1.8 writes it as v1.7 did.
            assertEquals(resaved, ShapeCodec.encode(back))
        }
    }

    @Test
    fun aPlainV17ShapeGoesThroughV18ByteForByte() {
        val shapes = ArrayList<ShapeObjectV17>()
        for (t in ShapeTypeV17.entries) for (corner in CornerStyleV17.entries) {
            shapes += ShapeObjectV17(
                type = t, cx = 40.5f, cy = 61.25f, w = 120f, h = if (t.isLineLike) 0f else 75f, rotation = 12.5f,
                style = ShapeStyleV17.STROKE_FILL, strokeWidth = 5f, corner = corner, cornerRadius = 9f, sides = 7, starPoints = 6, innerRatio = 0.3f,
                lineCap = LineCapStyleV17.SQUARE, arrowHeads = ArrowHeadsV17.BOTH, fillColor = 0xFF3366CC.toInt(), fillFollowsColor = false,
            )
        }
        shapes += ShapeObjectV17(
            type = ShapeTypeV17.STAR, w = 90f, h = 90f, corner = CornerStyleV17.ROUND, cornerRadius = 4f,
            points = listOf(
                ShapePointV17(0f, -0.5f, radius = 6f), ShapePointV17(0.5f, 0.5f, smooth = true),
                ShapePointV17(-0.5f, 0.5f, handleIn = ShapeHandleV17(0.1f, 0f), handleOut = ShapeHandleV17(-0.1f, 0f)),
            ),
        )
        shapes += ShapeObjectV17(type = ShapeTypeV17.LINE, w = 100f, h = 20f, points = listOf(ShapePointV17(-0.5f, 0f), ShapePointV17(0f, 0.5f, smooth = true), ShapePointV17(0.5f, 0f)))
        for (s in shapes) {
            val text = ShapeCodecV17.encode(s)
            val back = ShapeCodec.decode(text)
            assertNotNull(text, back)
            assertEquals("${s.type} ${s.corner}: the same bytes", text, ShapeCodec.encode(back!!))
            assertFalse(back.cogFallback)
            assertTrue(back.copies.isEmpty)
        }
    }
}
