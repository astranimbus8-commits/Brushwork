package com.brushwork.paint.exchange

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.svg.Affine
import com.brushwork.paint.exchange.svg.SvgColors
import com.brushwork.paint.exchange.svg.SvgCss
import com.brushwork.paint.exchange.svg.SvgFormatException
import com.brushwork.paint.exchange.svg.SvgPaint
import com.brushwork.paint.exchange.svg.SvgPathData
import com.brushwork.paint.exchange.svg.SvgUnits
import com.brushwork.paint.exchange.svg.XmlToken
import com.brushwork.paint.exchange.svg.XmlTokenizer
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.sin

/** v1.5 §4.11e (A8): the own XML tokenizer, path grammar, CSS, colours, units and transforms. */
class SvgSyntaxTest {

    private fun tokens(xml: String): List<XmlToken> {
        val t = XmlTokenizer(xml.toByteArray())
        val out = ArrayList<XmlToken>()
        while (true) out += t.next() ?: break
        return out
    }

    private fun texts(xml: String) = tokens(xml).filterIsInstance<XmlToken.Text>().joinToString("") { it.text ?: it.slice.toString() }

    // ------------------------------------------------------------------ tokenizer

    @Test
    fun tokenizerReadsTagsAttributesTextAndCdata() {
        val tk = tokens("<?xml version='1.0'?><!-- hi --><svg a=\"1\" b='x &amp; y'><g/><style><![CDATA[.a{fill:red}<x>]]></style>t&lt;&#65;&#x42;</svg>")
        val start = tk[0] as XmlToken.Start
        assertEquals("svg", start.name)
        assertEquals("1", start.attrs[0].text)
        assertEquals("x & y", start.attrs[1].text)
        assertTrue((tk[1] as XmlToken.Start).selfClosing)
        assertEquals(".a{fill:red}<x>", (tk[3] as XmlToken.Text).text)
        assertEquals("t<AB", (tk[5] as XmlToken.Text).text)
        assertEquals("svg", (tk[6] as XmlToken.End).name)
    }

    @Test
    fun unknownEntitiesStayLiteralAndTheDoctypeIsNeverProcessed() {
        val bomb = """<?xml version="1.0"?>
            <!DOCTYPE lolz [
              <!ENTITY lol "lol">
              <!ENTITY lol2 "&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;&lol;">
              <!ENTITY lol3 "&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;&lol2;">
              <!ENTITY xxe SYSTEM "file:///etc/passwd">
            ]>
            <svg>&lol3;&xxe;&nbsp;</svg>"""
        assertEquals("&lol3;&xxe;&nbsp;", texts(bomb).trim())
    }

    @Test
    fun malformedInputThrowsInsteadOfHanging() {
        for (bad in listOf("<svg", "<svg a=\"1>", "<!-- never closed", "<svg a=1/>", "<![CDATA[x", "<!DOCTYPE x [ <!ENTITY a 'b'>")) {
            try {
                tokens(bad)
                fail("accepted: $bad")
            } catch (e: SvgFormatException) {
                // expected
            }
        }
    }

    @Test
    fun bigValuesStaySlicesOfTheSource() {
        val data = "A".repeat(10_000)
        val tk = tokens("<image href=\"data:image/png;base64,$data\"/>")
        val a = (tk[0] as XmlToken.Start).attrs[0]
        assertNull(a.value)
        assertTrue(a.slice!!.startsWith("data:image/png"))
        assertEquals(10_000 + 22, a.slice!!.length)
    }

    // ------------------------------------------------------------------ path data

    private fun points(ops: List<PathOp>): List<Vec2> = ops.mapNotNull {
        when (it) {
            is PathOp.MoveTo -> it.p
            is PathOp.LineTo -> it.p
            is PathOp.CubicTo -> it.p
            PathOp.Close -> null
        }
    }

    @Test
    fun pathGrammarEdgeCases() {
        // Relative, implicit lineto after moveto, H / V, packed numbers.
        val p = points(SvgPathData.parse("m10 10 5 5h5v-5l-.5.5zm1,1 L 1e1 2E-1"))
        assertEquals(listOf(Vec2(10f, 10f), Vec2(15f, 15f), Vec2(20f, 15f), Vec2(20f, 10f), Vec2(19.5f, 10.5f), Vec2(11f, 11f), Vec2(10f, 0.2f)), p)
        // Close then a relative move starts at the closed sub-path's start.
        val z = SvgPathData.parse("M5 5 L 10 5 Z m 1 1 l 1 0")
        assertEquals(Vec2(6f, 6f), (z[3] as PathOp.MoveTo).p)
        // Smooth cubic reflects the last control point; smooth quad likewise.
        val s = SvgPathData.parse("M0 0 C 0 10 10 10 10 0 S 20 -10 20 0")
        assertEquals(Vec2(10f, -10f), (s[2] as PathOp.CubicTo).c1)
        val q = SvgPathData.parse("M0 0 Q 5 10 10 0 T 20 0")
        val t = q[2] as PathOp.CubicTo
        // Reflected control (15, -10) as a cubic: 10 + 2/3 * (15 - 10).
        assertEquals(10f + 2f / 3f * 5f, t.c1.x, 1e-4f)
        assertEquals(-10f * 2f / 3f, t.c1.y, 1e-4f)
        // An error keeps what came before it.
        assertEquals(2, SvgPathData.parse("M0 0 L 5 5 L oops 7").size)
        assertEquals(0, SvgPathData.parse("12 13").size)
    }

    @Test
    fun packedArcFlagsAndArcToCubicStaysWithinATenthOfAPixel() {
        // rx ry rot large sweep x y, the flags packed with the next number: "0110 10" = 0, 1, 10, 10.
        val ops = SvgPathData.parse("M0 0 a10 10 0 0110 10")
        assertTrue(ops.size >= 2)
        assertEquals(Vec2(10f, 10f), (ops.last() as PathOp.CubicTo).p)
        // A big rotated elliptical arc: every flattened point lies on the ellipse within 0.1 px.
        val rx = 400f; val ry = 150f; val rot = 30.0
        val cx = 500f; val cy = 300f
        val start = ellipsePoint(cx, cy, rx, ry, rot, 0.3)
        val end = ellipsePoint(cx, cy, rx, ry, rot, 4.0)
        val arc = SvgPathData.parse("M${start.x} ${start.y} A$rx $ry $rot 1 1 ${end.x} ${end.y}", tolerance = 0.05f)
        val poly = VectorPath(arc).flatten(0.01f)[0].points
        var worst = 0f
        for (pt in poly) worst = maxOf(worst, ellipseDistance(pt, cx, cy, rx, ry, rot))
        assertTrue("arc error $worst", worst < 0.1f)
    }

    private fun ellipsePoint(cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Double, t: Double): Vec2 {
        val r = Math.toRadians(rotDeg)
        val x = rx * cos(t); val y = ry * sin(t)
        return Vec2((cx + x * cos(r) - y * sin(r)).toFloat(), (cy + x * sin(r) + y * cos(r)).toFloat())
    }

    /** Distance from [p] to the ellipse (numerically, by dense sampling). */
    private fun ellipseDistance(p: Vec2, cx: Float, cy: Float, rx: Float, ry: Float, rotDeg: Double): Float {
        var best = Float.MAX_VALUE
        val n = 20000
        for (i in 0 until n) {
            val q = ellipsePoint(cx, cy, rx, ry, rotDeg, i * 2 * Math.PI / n)
            best = minOf(best, hypot(p.x - q.x, p.y - q.y))
        }
        return best
    }

    // ------------------------------------------------------------------ css

    @Test
    fun cssSelectorsSpecificityAndUnsupportedOnes() {
        val sheet = SvgCss.parse(
            """
            /* Illustrator */
            .st0{fill:#FF0000;stroke:none}
            .st1, .st2 { fill: blue }
            #hero { fill: green !important }
            rect.st0 { stroke: black }
            g > rect { fill: pink }
            a:hover { fill: red }
            @media print { rect { fill: white } }
            """.trimIndent(),
        )
        assertEquals(2, sheet.skippedSelectors)
        val sels = sheet.rules.map { it.selector }
        assertTrue(sels.contains(SvgCss.Selector(null, emptyList(), listOf("st0"))))
        val id = sheet.rules.first { it.selector.ids == listOf("hero") }
        assertTrue("fill" in id.important)
        val compound = sheet.rules.first { it.selector.tag == "rect" }
        assertTrue(compound.selector.specificity > sheet.rules[0].selector.specificity)
        assertTrue(compound.selector.matches("rect", null, setOf("st0", "x")))
        assertTrue(!compound.selector.matches("circle", null, setOf("st0")))
        val (decls, _) = SvgCss.declarations("fill: url(data:image/png;base64,AA;BB) ; stroke-width:2 ;")
        assertEquals("url(data:image/png;base64,AA;BB)", decls["fill"])
        assertEquals("2", decls["stroke-width"])
    }

    // ------------------------------------------------------------------ colours

    @Test
    fun colourFormats() {
        assertEquals(0xFFFF0000.toInt(), SvgColors.parse("#f00"))
        assertEquals(0x88FF0000.toInt(), SvgColors.parse("#f008"))
        assertEquals(0xFF102030.toInt(), SvgColors.parse("#102030"))
        assertEquals(0x80102030.toInt(), SvgColors.parse("#10203080"))
        assertEquals(0xFF0A141E.toInt(), SvgColors.parse("rgb(10, 20, 30)"))
        assertEquals(0xFFFF0000.toInt(), SvgColors.parse("rgb(100%,0%,0%)"))
        assertEquals(0x800A141E.toInt(), SvgColors.parse("rgba(10,20,30,0.5)"))
        assertEquals(0x800A141E.toInt(), SvgColors.parse("rgb(10 20 30 / 50%)"))
        assertEquals(0xFF00FF00.toInt(), SvgColors.parse("hsl(120, 100%, 50%)"))
        assertEquals(0xFF6495ED.toInt(), SvgColors.parse("CornflowerBlue"))
        assertEquals(0, SvgColors.parse("transparent"))
        assertNull(SvgColors.parse("notacolor"))
        assertTrue(SvgColors.NAMED.size >= 147)
        assertEquals(SvgPaint.None, SvgColors.paint("none"))
        assertEquals(SvgPaint.CurrentColor, SvgColors.paint("currentColor"))
        assertEquals(SvgPaint.Url("g1", SvgPaint.Color(0xFFFF0000.toInt())), SvgColors.paint("url(#g1) red"))
        assertEquals(SvgPaint.Url("g2", null), SvgColors.paint("url('#g2')"))
    }

    // ------------------------------------------------------------------ units and transforms

    @Test
    fun lengthsUnitsAndClampedNumbers() {
        assertEquals(96f, SvgUnits.length("1in")!!, 1e-4f)
        assertEquals(96f / 25.4f * 10f, SvgUnits.length("10mm")!!, 1e-3f)
        assertEquals(32f, SvgUnits.length("2em", fontSize = 16f)!!, 1e-4f)
        assertEquals(50f, SvgUnits.length("25%", percentOf = 200f)!!, 1e-4f)
        assertNull(SvgUnits.length("12furlongs"))
        // Physical units at the document DPI for the root size; px stay px.
        assertEquals(350f, SvgUnits.rootLength("1in", 350f)!!, 1e-3f)
        assertEquals(350f * 210f / 25.4f, SvgUnits.rootLength("210mm", 350f)!!, 0.01f)
        assertEquals(800f, SvgUnits.rootLength("800px", 350f)!!, 1e-3f)
        assertEquals(8.5f, SvgUnits.inches("612pt")!!, 1e-4f)
        // Hostile numbers become finite and bounded.
        assertEquals(SvgUnits.MAX_NUMBER, SvgUnits.numbers("1e308")[0])
        assertEquals(-SvgUnits.MAX_NUMBER, SvgUnits.numbers("-1e999")[0])
        assertEquals(listOf(1f, 2f, 3f, -0.5f, 0.5f), SvgUnits.numbers("1,2 3-.5.5").toList())
    }

    @Test
    fun transformsComposeLeftToRight() {
        val t = SvgUnits.transform("translate(10,20) scale(2) rotate(90)")!!
        // (1, 0) -> rotate -> (0, 1) -> scale -> (0, 2) -> translate -> (10, 22)
        assertEquals(10f, t.mapX(1f, 0f), 1e-4f)
        assertEquals(22f, t.mapY(1f, 0f), 1e-4f)
        val r = SvgUnits.transform("rotate(180 5 5)")!!
        assertEquals(10f, r.mapX(0f, 0f), 1e-4f)
        assertEquals(10f, r.mapY(0f, 0f), 1e-4f)
        val m = SvgUnits.transform("matrix(1 0 0 1 3 4) skewX(45)")!!
        assertEquals(3f + 1f, m.mapX(0f, 1f), 1e-4f)
        assertNull(SvgUnits.transform("translate(1,2"))
        assertNull(SvgUnits.transform("wobble(3)"))
        assertEquals(Affine.IDENTITY, SvgUnits.transform(null))
        // viewBox + preserveAspectRatio.
        val meet = SvgUnits.viewBoxTransform("0 0 100 50", null, 0f, 0f, 200f, 200f)!!
        assertEquals(2f, meet.a, 1e-6f)
        assertEquals(50f, meet.f, 1e-4f) // centred vertically
        val slice = SvgUnits.viewBoxTransform("0 0 100 50", "xMinYMin slice", 0f, 0f, 200f, 200f)!!
        assertEquals(4f, slice.a, 1e-6f)
        assertEquals(0f, slice.e, 1e-6f)
        val none = SvgUnits.viewBoxTransform("10 10 100 50", "none", 0f, 0f, 200f, 200f)!!
        assertEquals(2f, none.a, 1e-6f)
        assertEquals(4f, none.d, 1e-6f)
        assertEquals(-20f, none.e, 1e-4f)
        assertTrue(abs(none.inverse()!!.mapX(none.mapX(3f, 4f), none.mapY(3f, 4f)) - 3f) < 1e-3f)
    }
}
