package com.brushwork.paint.v19.a5

import android.graphics.Path
import android.graphics.Region
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.draw.EraseCut
import com.brushwork.paint.vector.pathfinder.PathConvert
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.abs

/**
 * v1.9 (A5 step 4, R11 b): the boolean cut of filled objects by the eraser ([EraseCut]): a cog
 * with a hole cut in two and bitten, 256 pieces cut and 257 refused, the 500 topmost of 501
 * targets, copies baked, an empty result removes, slivers dropped, a failed op leaves the object;
 * what is no operand (a brush stroke, a line) or only grazed stays as it is.
 */
@RunWith(RobolectricTestRunner::class)
class EraseCutTest {
    private val fill = VPaint.Solid(0xFF3070C0.toInt())
    private val outline = VStrokeStyle(color = 0xFF802000.toInt(), width = 4f)

    private fun box(l: Float, t: Float, r: Float, b: Float, id: Long = 1, copies: CopyMaps = CopyMaps.NONE, stroke: VStrokeStyle? = null) = VPath(
        id, opacity = 0.8f, subpaths = listOf(VSubpath(listOf(VAnchor(l, t, true), VAnchor(r, t, true), VAnchor(r, b, true), VAnchor(l, b, true)), closed = true)),
        fill = fill, stroke = stroke, copies = copies,
    )

    private fun cog(id: Long = 7) = VShape(
        id, opacity = 0.9f,
        shape = ShapeObject(ShapeType.COG, cx = 150f, cy = 150f, w = 200f, h = 200f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f, strokeColor = 0xFF101010.toInt(), fillColor = 0xFFE0A020.toInt(), cogHole = 0.5f),
    )

    private fun rect(l: Float, t: Float, r: Float, b: Float) = Path().apply { addRect(l, t, r, b, Path.Direction.CW) }

    private fun band(x0: Float, y0: Float, x1: Float, y1: Float, radius: Float) = EraseCut.strokeRegion(Path().apply { moveTo(x0, y0); lineTo(x1, y1) }, radius)

    private fun regionOf(o: VObject): Region {
        val r = Region()
        r.setPath(PathConvert.region(o)!!, Region(-2000, -2000, 4000, 4000))
        return r
    }

    private fun area(o: VObject): Float = PathConvert.area(PathConvert.region(o)!!)

    private fun pieces(o: EraseCut.Outcome): List<VObject> = (o as EraseCut.Outcome.Cut).pieces

    @Test
    fun aCogWithAHoleIsCutInTwoOrBittenAndKeepsItsHole() {
        val cog = cog()
        val whole = area(cog)
        // The hole: its centre is not in the cog.
        assertFalse(regionOf(cog).contains(150, 150))
        assertTrue(regionOf(cog).contains(150, 150 - 60))

        // A band across the middle (through the hole) leaves an upper and a lower part.
        val across = pieces(EraseCut.cut(cog, band(20f, 150f, 280f, 150f, 8f)))
        assertEquals(2, across.size)
        for (p in across) {
            p as VPath
            // A cut shape reopens as a plain Bézier path in the shape's style.
            assertNull(p.spline)
            assertEquals(0.9f, p.opacity, 1e-6f)
            assertEquals(VPaint.Solid(0xFFE0A020.toInt()), p.fill)
            assertEquals(0xFF101010.toInt(), p.stroke!!.color)
            assertEquals(3f, p.stroke.width, 1e-6f)
            assertEquals(CopyMaps.NONE, p.copies)
        }
        val (upper, lower) = across.sortedBy { (it as VPath).subpaths.first().anchors.minOf { a -> a.y } }
        assertTrue(regionOf(upper).contains(150, 150 - 60))
        assertFalse(regionOf(upper).contains(150, 150 + 60))
        assertTrue(regionOf(lower).contains(150, 150 + 60))
        // Only the band went: 16 px across the rim on both sides of the hole (40 to 60 px wide each).
        val left = across.sumOf { area(it).toDouble() }.toFloat()
        assertTrue("$whole $left", left < whole - 16f * 60f && left > whole - 16f * 200f)

        // A bite out of the rim: one piece, still with its hole.
        val bitten = pieces(EraseCut.cut(cog, band(150f, 40f, 150f, 60f, 12f)))
        assertEquals(1, bitten.size)
        val r = regionOf(bitten[0])
        assertFalse("the hole stays", r.contains(150, 150))
        assertTrue(r.contains(150, 150 - 60))
        assertTrue(r.contains(150 + 60, 150))
        assertTrue(area(bitten[0]) < whole - 50f)
    }

    /** A 10 px high bar across [count] pieces 1 px wide, 0.5 px apart (count - 1 gaps erased). */
    private fun comb(count: Int): Pair<VPath, Path> {
        val right = (count - 1) * 1.5f + 1f
        val eraser = Path()
        for (k in 0 until count - 1) eraser.addRect(k * 1.5f + 1f, -5f, k * 1.5f + 1.5f, 15f, Path.Direction.CW)
        return box(0f, 0f, right, 10f) to eraser
    }

    @Test
    fun twoHundredFiftySixPiecesAreCutAndMoreAreTooComplex() {
        val (bar, eraser) = comb(256)
        val cut = pieces(EraseCut.cut(bar, eraser))
        assertEquals(EraseCut.MAX_PIECES, cut.size)
        for (p in cut) assertEquals(10f, area(p), 0.2f)

        val (bar2, eraser2) = comb(257)
        assertEquals(EraseCut.Outcome.Partly, EraseCut.cut(bar2, eraser2))
        val all = EraseCut.cutAll(listOf(bar2), eraser2)
        assertTrue(all.pieces.isEmpty())
        assertTrue(all.tooComplex)
    }

    @Test
    fun ofFiveHundredAndOneTargetsTheFiveHundredTopmostAreCut() {
        val boxes = (0 until 501).map { k -> box(10f + (k % 23) * 20f, 10f + (k / 23) * 20f, 18f + (k % 23) * 20f, 18f + (k / 23) * 20f, id = k + 1L) }
        val eraser = rect(0f, 0f, 512f, 512f)
        val cuts = EraseCut.cutAll(boxes, eraser)
        assertEquals(EraseCut.MAX_TARGETS, cuts.pieces.size)
        assertFalse("the bottom one stays", 1L in cuts.pieces)
        for (id in 2L..501L) assertEquals(emptyList<VObject>(), cuts.pieces[id])
        assertTrue(cuts.tooComplex)
        assertFalse(cuts.copiesBaked)

        // 500 are all cut, and nothing is too complex.
        val five = EraseCut.cutAll(boxes.drop(1), eraser)
        assertEquals(500, five.pieces.size)
        assertFalse(five.tooComplex)
    }

    @Test
    fun copiesAreBakedIntoPiecesOfTheirOwn() {
        val shift = floatArrayOf(1f, 0f, 200f, 0f, 1f, 0f, 0f, 0f, 1f)
        val linked = box(20f, 20f, 120f, 80f, id = 3, copies = CopyMaps.of(listOf(shift)))
        assertTrue(ObjectCopies.hasCopies(linked))
        // A band down the source only.
        val eraser = band(70f, 0f, 70f, 100f, 5f)
        val cuts = EraseCut.cutAll(listOf(linked), eraser)
        assertTrue(cuts.copiesBaked)
        assertFalse(cuts.tooComplex)
        val out = cuts.pieces[3L]!!
        // The source's two halves, then the copy, whole and plain.
        assertEquals(3, out.size)
        for (p in out) assertFalse(ObjectCopies.hasCopies(p))
        assertEquals(45f * 60f, area(out[0]), 2f)
        assertEquals(45f * 60f, area(out[1]), 2f)
        assertEquals(100f * 60f, area(out[2]), 1f)
        assertTrue(regionOf(out[2]).contains(270, 50))

        // One object cut on its own unites its copies' regions (I17): the copy is a piece too.
        val one = pieces(EraseCut.cut(linked, eraser))
        assertEquals(3, one.size)
        for (p in one) assertEquals(CopyMaps.NONE, (p as VPath).copies)

        // Copies the eraser does not reach stay linked.
        val missed = EraseCut.cutAll(listOf(linked), band(400f, 0f, 400f, 100f, 5f))
        assertTrue(missed.pieces.isEmpty())
        assertFalse(missed.copiesBaked)
    }

    @Test
    fun anEmptyResultRemovesTheObjectAndSliversAreDropped() {
        val b = box(0f, 0f, 10f, 10f)
        assertEquals(EraseCut.Outcome.Cut(emptyList()), EraseCut.cut(b, rect(-5f, -5f, 15f, 15f)))
        assertEquals(emptyList<VObject>(), EraseCut.cutAll(listOf(b), rect(-5f, -5f, 15f, 15f)).pieces[1L])
        // What is left is a 0.03 px strip (0.3 px²): a sliver, so nothing is left.
        assertEquals(EraseCut.Outcome.Cut(emptyList()), EraseCut.cut(b, rect(-5f, -5f, 9.97f, 15f)))
        // A 2 px strip and a sliver: the strip alone.
        val one = pieces(EraseCut.cut(b, rect(2f, -5f, 9.97f, 15f)))
        assertEquals(1, one.size)
        assertEquals(20f, area(one[0]), 0.1f)
        // A sliver of a bite (0.3 px²) touches nothing.
        assertEquals(EraseCut.Outcome.Unchanged, EraseCut.cut(b, rect(9.97f, -5f, 20f, 15f)))
    }

    @Test
    fun aFailedOpLeavesTheObject() {
        val broken = VPath(
            5, subpaths = listOf(VSubpath(listOf(VAnchor(0f, 0f, true), VAnchor(Float.NaN, 0f, true), VAnchor(50f, 50f, true), VAnchor(0f, 50f, true)), closed = true)),
            fill = fill,
        )
        val eraser = rect(-5f, 10f, 60f, 20f)
        assertEquals(EraseCut.Outcome.Unchanged, EraseCut.cut(broken, eraser))
        assertEquals(EraseCut.Detail.Failed, EraseCut.cutOne(broken, eraser))
        val cuts = EraseCut.cutAll(listOf(broken, box(0f, 0f, 50f, 50f, id = 6)), eraser)
        assertTrue(cuts.tooComplex)
        assertFalse(5L in cuts.pieces)
        // The good one is cut all the same.
        assertEquals(2, cuts.pieces[6L]!!.size)

        // An eraser Skia cannot read: nothing is cut, and the user is told.
        val bad = Path().apply { moveTo(0f, 0f); lineTo(Float.NaN, 10f); lineTo(50f, 50f); close() }
        val none = EraseCut.cutAll(listOf(box(0f, 0f, 50f, 50f, id = 6)), bad)
        assertTrue(none.pieces.isEmpty())
        assertTrue(none.tooComplex)
    }

    @Test
    fun whatIsNoOperandOrOnlyGrazedStaysAsItIs() {
        val pen = BrushLibrary.defaultBrush.copy(size = 8f)
        val stroke = VStroke(1, preset = pen, color = -1, seed = 1L, stylus = false, points = PackedPoints(floatArrayOf(0f, 100f), floatArrayOf(5f, 5f), floatArrayOf(1f, 1f)))
        val line = VShape(2, shape = ShapeObject(ShapeType.LINE, cx = 50f, cy = 5f, w = 100f, h = 0f, strokeWidth = 6f))
        val arrow = VShape(3, shape = ShapeObject(ShapeType.ARROW, cx = 50f, cy = 5f, w = 100f, h = 0f, strokeWidth = 6f))
        val eraser = rect(40f, -20f, 60f, 30f)
        for (o in listOf(stroke, line, arrow)) {
            assertFalse(EraseCut.isOperand(o))
            assertEquals(EraseCut.Outcome.Unchanged, EraseCut.cut(o, eraser))
        }
        assertTrue(EraseCut.isOperand(cog()))
        assertTrue(EraseCut.isOperand(VShape(4, shape = ShapeObject(ShapeType.ELLIPSE, cx = 50f, cy = 50f, w = 40f, h = 40f, style = ShapeStyle.STROKE))))
        // A graze of the outline's outer half: the region (the fill) is not touched.
        val stroked = box(0f, 0f, 50f, 50f, stroke = outline)
        val graze = rect(51f, 10f, 70f, 20f)
        assertEquals(EraseCut.Outcome.Unchanged, EraseCut.cut(stroked, graze))
        val cuts = EraseCut.cutAll(listOf(stroke, line, stroked), graze)
        assertTrue(cuts.pieces.isEmpty())
        assertFalse(cuts.tooComplex)
        // Far away.
        assertEquals(EraseCut.Outcome.Unchanged, EraseCut.cut(stroked, rect(200f, 200f, 210f, 210f)))
    }

    @Test
    fun theSweptRegionIsTheSessionsCapsules() {
        // A finger: one radius, one stroke region.
        val xs = floatArrayOf(10f, 50f, 50f)
        val ys = floatArrayOf(10f, 10f, 50f)
        val finger = EraseCut.sweptRegion(xs, ys, floatArrayOf(5f, 5f, 5f), 3)
        // Two 40 x 10 bands overlapping in 5 x 5, two half discs at the ends, a quarter disc at the turn.
        val expected = 800f - 25f + PI.toFloat() * 25f * 1.25f
        assertEquals(PathConvert.area(EraseCut.strokeRegion(Path().apply { moveTo(10f, 10f); lineTo(50f, 10f); lineTo(50f, 50f) }, 5f)), PathConvert.area(finger), 0.5f)
        assertTrue(abs(PathConvert.area(finger) - expected) < 4f)
        // A stylus: each capsule takes the larger radius of its ends.
        val stylus = EraseCut.sweptRegion(xs, ys, floatArrayOf(2f, 6f, 3f), 3)
        val r = Region().apply { setPath(stylus, Region(-100, -100, 200, 200)) }
        assertTrue("the first capsule has radius 6", r.contains(12, 15))
        assertTrue("so has the second", r.contains(55, 30))
        assertFalse(r.contains(10, 17))
        // One point: a disc; nothing finite: nothing.
        val dot = EraseCut.sweptRegion(floatArrayOf(Float.NaN, 30f), floatArrayOf(0f, 30f), floatArrayOf(4f, 4f), 2)
        // (Read back, a round cap is a quad per quarter: a little more than the disc.)
        assertEquals(PI.toFloat() * 16f, PathConvert.area(dot), 3f)
        assertTrue(EraseCut.sweptRegion(floatArrayOf(Float.NaN), floatArrayOf(1f), floatArrayOf(4f), 1).isEmpty)
        // Fill rule of the result is nonzero, as a stroke region's.
        assertEquals(VFillRule.NONZERO, PathConvert.ruleOf(finger))
    }
}
