package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.svg.Affine
import com.brushwork.paint.exchange.svg.SvgDocument
import com.brushwork.paint.exchange.svg.SvgFormatException
import com.brushwork.paint.exchange.svg.SvgItem
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgTextAnchor
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.vector.VFillRule
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

/** v1.5 §4.11 (A8): SVG elements, styles and transforms become vector objects (JVM). */
class SvgToVectorTest {
    private fun convert(svg: String, dpi: Float = 350f, place: Affine = Affine.IDENTITY) =
        SvgToVector.convert(SvgParser.parse(svg.toByteArray()), dpi, place)

    private fun paths(svg: String) = convert(svg).items.filterIsInstance<SvgItem.Shape>().map { it.path }

    @Test
    fun basicShapesBecomeOnePathEach() {
        val c = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" width="200" height="100">
                <rect x="10" y="10" width="50" height="30" fill="red"/>
                <rect x="70" y="10" width="50" height="30" rx="5"/>
                <circle cx="150" cy="50" r="20" fill="none" stroke="blue" stroke-width="4"/>
                <ellipse cx="50" cy="80" rx="20" ry="10"/>
                <line x1="0" y1="0" x2="10" y2="10" stroke="black"/>
                <polyline points="0,0 10,5 20,0" fill="none" stroke="black"/>
                <polygon points="0,0 10,5 20,0"/>
                <path d="M0 0 L10 0 L10 10 Z M2 2 L8 2 L8 8 Z" fill-rule="evenodd"/>
                <rect width="0" height="10"/>
            </svg>""",
        )
        val p = c.items.filterIsInstance<SvgItem.Shape>().map { it.path }
        assertEquals(8, p.size)
        // A sharp rectangle: 4 sharp anchors, closed, red.
        val r = p[0]
        assertEquals(1, r.subpaths.size)
        assertTrue(r.subpaths[0].closed)
        assertEquals(4, r.subpaths[0].anchors.size)
        assertEquals(VPaint.Solid(0xFFFF0000.toInt()), r.fill)
        // Default fill is black, default stroke none.
        assertEquals(VPaint.Solid(0xFF000000.toInt()), p[1].fill)
        assertEquals(null, p[1].stroke)
        val circle = p[2]
        assertEquals(null, circle.fill)
        assertEquals(4f, circle.stroke!!.width, 1e-5f)
        // SVG defaults: butt caps, miter joins.
        assertEquals(LineCapStyle.BUTT, circle.stroke!!.cap)
        assertEquals(JoinStyle.MITER, circle.stroke!!.join)
        // A line has no fill.
        assertEquals(null, p[4].fill)
        assertEquals(2, p[7].subpaths.size)
        assertEquals(VFillRule.EVENODD, p[7].fillRule)
        assertEquals(1, c.groups.size)
    }

    @Test
    fun stylesFollowAttributeCssStyleOrderWithInheritance() {
        val p = paths(
            """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <style>.st0{fill:#00FF00} #b{fill:#0000FF} .imp{fill:#123456 !important}</style>
                <g fill="#FF0000" stroke="#000" stroke-width="3">
                    <rect id="a" width="10" height="10"/>
                    <rect class="st0" fill="#FFFF00" width="10" height="10"/>
                    <rect id="b" class="st0" width="10" height="10"/>
                    <rect class="st0" style="fill:#FF00FF" width="10" height="10"/>
                    <rect class="imp" style="fill:#FF00FF" width="10" height="10"/>
                    <rect fill="inherit" stroke="none" width="10" height="10"/>
                </g>
            </svg>""",
        )
        assertEquals(VPaint.Solid(0xFFFF0000.toInt()), p[0].fill) // inherited
        assertEquals(3f, p[0].stroke!!.width, 1e-5f)
        assertEquals(VPaint.Solid(0xFF00FF00.toInt()), p[1].fill) // CSS beats the attribute
        assertEquals(VPaint.Solid(0xFF0000FF.toInt()), p[2].fill) // #id beats .class
        assertEquals(VPaint.Solid(0xFFFF00FF.toInt()), p[3].fill) // style="" beats CSS
        assertEquals(VPaint.Solid(0xFF123456.toInt()), p[4].fill) // !important beats style=""
        assertEquals(VPaint.Solid(0xFFFF0000.toInt()), p[5].fill)
        assertEquals(null, p[5].stroke)
    }

    @Test
    fun transformsAreBakedAndStrokesScale() {
        val p = paths(
            """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400">
                <g transform="translate(100 50) scale(2)">
                    <rect x="10" y="10" width="20" height="10" stroke="#000" stroke-width="2" transform="rotate(90 10 10)"/>
                </g>
            </svg>""",
        )
        val a = p[0].subpaths[0].anchors
        // (10, 10) is the rotation centre: -> scale 2 -> translate.
        assertEquals(120f, a[0].x, 1e-3f)
        assertEquals(70f, a[0].y, 1e-3f)
        // (30, 10) rotates to (10, 30) -> (120, 110).
        assertEquals(120f, a[1].x, 1e-3f)
        assertEquals(110f, a[1].y, 1e-3f)
        assertEquals(4f, p[0].stroke!!.width, 1e-4f)
    }

    @Test
    fun viewBoxAndPhysicalUnitsMapToDocumentPixels() {
        val doc = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="1in" height="0.5in" viewBox="0 0 100 50"><rect width="100" height="50"/></svg>""".toByteArray(),
        )
        assertEquals(350f to 175f, SvgToVector.viewportSize(doc, 350f))
        assertEquals(1f to 0.5f, SvgToVector.physicalInches(doc))
        val p = SvgToVector.convert(doc, 350f).items.filterIsInstance<SvgItem.Shape>()[0].path
        assertEquals(350f, p.subpaths[0].anchors[1].x, 1e-3f)
        assertEquals(175f, p.subpaths[0].anchors[2].y, 1e-3f)
        // A placement (scale 90 %, centred) applies on top.
        val q = SvgToVector.convert(doc, 350f, Affine(0.5f, 0f, 0f, 0.5f, 10f, 20f)).items.filterIsInstance<SvgItem.Shape>()[0].path
        assertEquals(10f + 175f, q.subpaths[0].anchors[1].x, 1e-3f)
        val c = SvgToVector.convert(doc, 350f)
        assertEquals(350f, c.bounds!!.right, 1e-3f)
    }

    @Test
    fun gradientsInBoundingBoxAndUserSpaceUnits() {
        val p = paths(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="300">
                <defs>
                    <linearGradient id="g"><stop offset="0" stop-color="red"/><stop offset="100%" stop-color="blue" stop-opacity="0.5"/></linearGradient>
                    <linearGradient id="h" xlink:href="#g" gradientUnits="userSpaceOnUse" x1="0" y1="0" x2="0" y2="100"/>
                    <radialGradient id="r" cx="0.5" cy="0.5" r="0.5"><stop offset="0" style="stop-color:#fff"/><stop offset="1" stop-color="#000"/></radialGradient>
                </defs>
                <rect x="100" y="0" width="200" height="50" fill="url(#g)"/>
                <rect width="10" height="100" fill="url(#h)"/>
                <rect x="0" y="200" width="100" height="50" fill="url(#r)"/>
                <rect width="10" height="10" fill="url(#missing) green"/>
            </svg>""",
        )
        val lin = p[0].fill as VPaint.Linear
        // Bounding-box units: from the rect's left edge to its right edge.
        assertEquals(100f, lin.x0, 1e-3f)
        assertEquals(300f, lin.x1, 1e-3f)
        assertEquals(0x80, lin.stops[1].color ushr 24)
        val user = p[1].fill as VPaint.Linear
        assertEquals(100f, user.y1, 1e-3f)
        assertEquals(2, user.stops.size) // stops inherited through href
        val rad = p[2].fill as VPaint.Radial
        val m = rad.matrix!!
        // Unit box -> (100 x 50 at 0, 200): the circle becomes an ellipse.
        assertEquals(100f, m[0], 1e-3f)
        assertEquals(50f, m[3], 1e-3f)
        assertEquals(200f, m[5], 1e-3f)
        assertEquals(VPaint.Solid(0xFF008000.toInt()), p[3].fill)
    }

    @Test
    fun useSymbolsGroupOpacityAndHiddenElements() {
        val c = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="300">
                <defs>
                    <symbol id="s" viewBox="0 0 10 10"><rect width="10" height="10"/></symbol>
                    <circle id="c" r="5" fill="green"/>
                    <g id="loop"><use xlink:href="#loop"/></g>
                </defs>
                <g opacity="0.5">
                    <use xlink:href="#c" x="100" y="100" opacity="0.5"/>
                    <use href="#s" x="10" y="10" width="20" height="20"/>
                </g>
                <rect width="5" height="5" display="none"/>
                <g display="none"><rect width="5" height="5"/></g>
                <rect width="5" height="5" visibility="hidden"/>
                <use xlink:href="#loop"/>
                <use xlink:href="other.svg#x"/>
            </svg>""",
        )
        val p = c.items.filterIsInstance<SvgItem.Shape>().map { it.path }
        assertEquals(2, p.size)
        assertEquals(0.25f, p[0].opacity, 1e-5f)
        assertEquals(100f + 5f, p[0].subpaths[0].anchors[0].x, 1e-3f)
        // The symbol's 10-unit box scaled into 20 px at (10, 10).
        assertEquals(30f, p[1].subpaths[0].anchors[1].x, 1e-3f)
        assertEquals(0.5f, p[1].opacity, 1e-5f)
        assertTrue(c.skipped.keys.any { it.startsWith("circular") })
        assertTrue(c.skipped.keys.any { it.startsWith("external") })
    }

    @Test
    fun unsupportedFeaturesAreCountedNotDrawn() {
        val c = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100">
                <defs><clipPath id="cp"><rect width="5" height="5"/></clipPath><pattern id="pt"/><filter id="f"/></defs>
                <rect width="50" height="50" clip-path="url(#cp)"/>
                <rect width="50" height="50" filter="url(#f)" fill="url(#pt)"/>
                <path d="M0 0 L10 10" stroke="#000" stroke-dasharray="4 2"/>
                <foreignObject><div xmlns="http://www.w3.org/1999/xhtml">hi</div></foreignObject>
                <script>alert(1)</script>
                <image href="https://example.com/x.png" width="10" height="10"/>
            </svg>""",
        )
        assertEquals(1, c.skipped["clip paths"])
        assertEquals(1, c.skipped["filters"])
        assertEquals(1, c.skipped["patterns"])
        assertEquals(1, c.skipped["dashed lines (drawn solid)"])
        assertEquals(1, c.skipped["embedded HTML"])
        assertEquals(1, c.skipped["scripts"])
        assertEquals(1, c.skipped["external pictures"])
        // The clipped rect is still drawn; the pattern-filled one draws nothing.
        assertEquals(2, c.shapeCount)
        assertEquals(0, c.pictureCount)
    }

    @Test
    fun topLevelGroupsAreNamedLayers() {
        val c = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="100" height="100">
                <rect width="1" height="1"/>
                <g inkscape:groupmode="layer" inkscape:label="Sky"><rect width="1" height="1"/></g>
                <g id="Ground"><rect width="1" height="1"/><rect width="1" height="1"/></g>
                <rect width="1" height="1"/>
            </svg>""",
        )
        assertEquals(listOf("Layer 1", "Sky", "Ground", "Layer 4"), c.groups)
        assertEquals(listOf(0, 1, 2, 2, 3), c.items.map { it.group })
    }

    @Test
    fun textBecomesLabelsWithLinesFontAndAnchor() {
        val c = convert(
            """<svg xmlns="http://www.w3.org/2000/svg" width="400" height="400">
                <text x="10" y="40" font-family="Georgia, serif" font-size="20" font-weight="700" fill="#336699" text-anchor="middle">
                    <tspan x="10" y="40">Hello</tspan><tspan x="10" y="64">world &amp; more</tspan>
                </text>
                <text transform="translate(100 100) rotate(30) scale(2)" font-style="italic">Hi</text>
            </svg>""",
        )
        val t = c.items.filterIsInstance<SvgItem.Label>().map { it.text }
        assertEquals(2, t.size)
        assertEquals(listOf("Hello", "world & more"), t[0].lines)
        assertEquals("serif", t[0].family)
        assertTrue(t[0].bold)
        assertEquals(0xFF336699.toInt(), t[0].color)
        assertEquals(SvgTextAnchor.MIDDLE, t[0].anchor)
        assertEquals(1.2f, t[0].lineHeightEm, 1e-4f)
        assertEquals(30f, t[1].rotationDeg, 1e-3f)
        assertEquals(32f, t[1].sizePx, 1e-3f)
        assertTrue(t[1].italic)
        assertEquals(100f, t[1].x, 1e-3f)
    }

    @Test
    fun limitsDeepNestingHugeNumbersAndElementCount() {
        val deep = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\">")
        repeat(100) { deep.append("<g>") }
        deep.append("<rect width=\"1\" height=\"1\"/>")
        repeat(100) { deep.append("</g>") }
        deep.append("</svg>")
        val d = convert(deep.toString())
        assertEquals(0, d.shapeCount)
        assertTrue((d.skipped["deeply nested elements"] ?: 0) >= 1)

        val huge = paths("""<svg xmlns="http://www.w3.org/2000/svg"><rect x="1e308" y="-1e308" width="1e308" height="5"/></svg>""")
        val a = huge[0].subpaths[0].anchors
        assertTrue(a.all { it.x.isFinite() && it.y.isFinite() })
        assertEquals(1e6f, a[0].x, 1f)

        val many = StringBuilder("<svg xmlns=\"http://www.w3.org/2000/svg\">")
        repeat(SvgDocument.MAX_ELEMENTS + 10) { many.append("<rect width=\"1\" height=\"1\"/>") }
        many.append("</svg>")
        val doc = SvgParser.parse(many.toString().toByteArray())
        assertTrue(doc.truncated)
        val m = SvgToVector.convert(doc, 350f)
        assertTrue(m.truncated)
        assertEquals(SvgDocument.MAX_ELEMENTS - 1, m.shapeCount)

        // The point limit stops conversion too.
        val pts = SvgToVector.convert(SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg"><rect width="1" height="1"/><rect width="1" height="1"/></svg>""".toByteArray()), 350f, maxPoints = 6)
        assertTrue(pts.truncated)
        assertEquals(1, pts.shapeCount)
    }

    @Test
    fun notSvgIsRejected() {
        for (bad in listOf("<html><body/></html>", "", "just text")) {
            try {
                SvgParser.parse(bad.toByteArray())
                fail("accepted $bad")
            } catch (e: SvgFormatException) {
                assertNotNull(e.message)
            }
        }
    }

    @Test
    fun cubicHandlesSurviveExactly() {
        val p = paths("""<svg xmlns="http://www.w3.org/2000/svg"><path d="M0 0 C 10 -20 30 -20 40 0 S 70 20 80 0" fill="none" stroke="#000"/></svg>""")[0]
        val a = p.subpaths[0].anchors
        assertEquals(3, a.size)
        assertTrue(a.all { it.sharp })
        assertEquals(10f, a[0].outX!!, 1e-5f)
        assertEquals(-20f, a[0].outY!!, 1e-5f)
        assertEquals(-10f, a[1].inX!!, 1e-5f)
        // S reflects (30, -20) about (40, 0): (50, 20) -> offset (10, 20).
        assertEquals(10f, a[1].outX!!, 1e-5f)
        assertEquals(20f, a[1].outY!!, 1e-5f)
        assertTrue(p == p.copy())
        assertTrue(VPath::class.java.isInstance(p))
    }
}
