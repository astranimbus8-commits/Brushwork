package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.svg.SvgItem
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.tools.vector.Bounds
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Final QA (v1.5 §4.11b): real-world-style SVG files land where a browser draws them — Inkscape
 * layers with physical units, arcs, gradient chains and rotations about a point; symbols and
 * `<use>`; nested viewports; the root's preserveAspectRatio; physical units in pt.
 */
class SvgQaFixturesTest {

    private fun shapes(svg: String, dpi: Float = 350f) = SvgToVector.convert(SvgParser.parse(svg.toByteArray()), dpi)
        .items.filterIsInstance<SvgItem.Shape>()

    private fun fixture(name: String, dpi: Float = 350f) = SvgToVector.convert(SvgParser.parse(QaExchange.svgFixture(name)), dpi)

    /** The geometric bounds of a converted path (no stroke). */
    private fun bounds(p: VPath): Bounds = VectorOps.toVectorPath(p).bounds(0.05f)!!

    private fun assertBounds(what: String, l: Float, t: Float, r: Float, b: Float, actual: Bounds, tol: Float = 0.3f) {
        assertEquals("$what left", l, actual.left, tol)
        assertEquals("$what top", t, actual.top, tol)
        assertEquals("$what right", r, actual.right, tol)
        assertEquals("$what bottom", b, actual.bottom, tol)
    }

    @Test
    fun inkscapeLayersArcsGradientChainsAndRotations() {
        val c = fixture("inkscape-layers.svg")
        assertEquals(listOf("Sky", "Hills", "Sun"), c.groups)
        // 2.5 x 1.5 in at 350 dpi; the viewBox 240 x 144 fills it: 875 / 240 px per unit.
        val s = 875f / 240f
        val items = c.items.filterIsInstance<SvgItem.Shape>()
        assertEquals(listOf(0, 1, 2, 2), items.map { it.group })
        // The sky's gradient comes through its xlink:href chain, top to bottom in document px.
        val sky = items[0].path
        assertBounds("sky", 0f, 0f, 875f, 525f, bounds(sky))
        val g = sky.fill as VPaint.Linear
        assertEquals(0f, g.y0, 0.01f)
        assertEquals(144f * s, g.y1, 0.05f)
        assertEquals(listOf(0xFF87CEEB.toInt(), 0xFFFFFFFF.toInt()), g.stops.map { it.color })
        // The hills: two arcs (rx 60 / 40 and 60 / 30, sweep 1 = over the top) in a group moved down 20.
        val hills = items[1].path
        val hb = bounds(hills)
        assertEquals("first arc's top: (60, 100 - 40 + 20)", 80f * s, hb.top, 0.3f)
        assertBounds("hills", 0f, 80f * s, 240f * s, 144f * s, hb)
        assertEquals(2f * s, hills.stroke!!.width, 1e-3f)
        // The ray: rotate(45, 190, 40) of the rect (180, 10) - (200, 14).
        val ray = items[3].path
        val r = Math.toRadians(45.0)
        fun rot(x: Float, y: Float): Pair<Float, Float> {
            val dx = x - 190f
            val dy = y - 40f
            return (190f + (dx * Math.cos(r) - dy * Math.sin(r)).toFloat()) to (40f + (dx * Math.sin(r) + dy * Math.cos(r)).toFloat())
        }
        val corners = listOf(rot(180f, 10f), rot(200f, 10f), rot(200f, 14f), rot(180f, 14f))
        val anchors = ray.subpaths.single().anchors
        assertEquals(4, anchors.size)
        for ((a, e) in anchors.zip(corners)) {
            assertEquals(e.first * s, a.x, 0.05f)
            assertEquals(e.second * s, a.y, 0.05f)
        }
        // The sun: a circle of radius 18 around (190, 40).
        assertBounds("sun", 172f * s, 22f * s, 208f * s, 58f * s, bounds(items[2].path))
    }

    @Test
    fun symbolsUsesAndNestedViewports() {
        val c = fixture("use-symbols.svg")
        val items = c.items.filterIsInstance<SvgItem.Shape>()
        assertEquals(4, items.size)
        // The star symbol (viewBox 0 0 10 10) in a 40 x 40 use at (10, 20).
        assertBounds("star 1", 10f, 20f, 50f, 60f, bounds(items[0].path))
        assertEquals(VPaint.Solid(0xFFFFD700.toInt()), items[0].path.fill)
        // In a 20 x 40 use: meet scales by 2 and centres it vertically.
        assertBounds("star 2", 100f, 30f, 120f, 50f, bounds(items[1].path))
        // A group used with translate(200,100) scale(2) and x, y = 5: the circle (r 5) around (210, 110).
        assertBounds("dot", 200f, 100f, 220f, 120f, bounds(items[2].path))
        assertEquals("circle.red wins", VPaint.Solid(0xFFFF0000.toInt()), items[2].path.fill)
        // A nested <svg> with preserveAspectRatio="none" stretches its 10 x 10 rect over 60 x 30.
        assertBounds("nested", 220f, 150f, 280f, 180f, bounds(items[3].path))
        assertTrue("the descendant selector is reported", (c.skipped["CSS selectors"] ?: 0) >= 1)
    }

    @Test
    fun theRootsPreserveAspectRatio() {
        fun root(par: String) = """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="200" viewBox="0 0 100 100" preserveAspectRatio="$par"><rect width="100" height="100"/></svg>"""
        assertBounds("meet", 100f, 0f, 300f, 200f, bounds(shapes(root("xMidYMid meet")).single().path))
        assertBounds("xMinYMin meet", 0f, 0f, 200f, 200f, bounds(shapes(root("xMinYMin meet")).single().path))
        assertBounds("xMaxYMax meet", 200f, 0f, 400f, 200f, bounds(shapes(root("xMaxYMax")).single().path))
        assertBounds("slice", 0f, -100f, 400f, 300f, bounds(shapes(root("xMidYMid slice")).single().path))
        assertBounds("none", 0f, 0f, 400f, 200f, bounds(shapes(root("none")).single().path))
        // A viewBox that doesn't start at 0, 0.
        val offset = """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="200" viewBox="-50 -50 100 100"><circle r="10"/></svg>"""
        assertBounds("origin in the middle", 80f, 80f, 120f, 120f, bounds(shapes(offset).single().path))
    }

    @Test
    fun physicalUnitsAtTheDocumentDpi() {
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="144pt" height="72pt" viewBox="0 0 2 1"><rect width="2" height="1"/><circle cx="1.5" cy="0.5" r="0.25"/></svg>"""
        val doc = SvgParser.parse(svg.toByteArray())
        // 2 x 1 in: 700 x 350 px at 350 dpi, 600 x 300 at 300 dpi.
        assertEquals(700f to 350f, SvgToVector.viewportSize(doc, 350f))
        assertEquals(600f to 300f, SvgToVector.viewportSize(doc, 300f))
        val c = SvgToVector.convert(doc, 300f).items.filterIsInstance<SvgItem.Shape>()
        assertBounds("rect", 0f, 0f, 600f, 300f, bounds(c[0].path))
        assertBounds("circle", 375f, 75f, 525f, 225f, bounds(c[1].path))
        assertNotNull(SvgToVector.physicalInches(doc))
        assertEquals(2f, SvgToVector.physicalInches(doc)!!.first, 1e-4f)
    }

    @Test
    fun figmaFillNoneOnTheRootIsInherited() {
        val c = fixture("figma.svg")
        val items = c.items.filterIsInstance<SvgItem.Shape>()
        // The ring circle has only a stroke (fill="none" from the root), the background is white.
        val ring = items.first { it.path.stroke != null && it.path.fill == null }
        assertEquals(4f, ring.path.stroke!!.width, 1e-4f)
        assertEquals(VPaint.Solid(0xFFFFFFFF.toInt()), items[0].path.fill)
    }
}
