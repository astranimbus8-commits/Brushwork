package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 §4.11c (A8): a large SVG (5000 styled paths, over half a megabyte) parses and converts in linear
 * time (budget on the phone: 300 ms + 500 ms; here a generous bound that catches quadratic work).
 */
class SvgScaleTest {
    @Test
    fun fiveThousandPathsParseAndConvertQuickly() {
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" width="2000" height="2000"><style>""")
        for (i in 0 until 50) sb.append(".c$i{fill:#${"%06x".format(i * 4999)};stroke:#000;stroke-width:${i % 4 + 1}}")
        sb.append("</style>")
        for (g in 0 until 50) {
            sb.append("<g id=\"g$g\" transform=\"translate(${g * 10} 0)\">")
            for (i in 0 until 100) {
                val x = (i * 17) % 1900
                val y = (i * 29 + g * 7) % 1900
                sb.append("<path class=\"c${(g + i) % 50}\" d=\"M$x $y c 10 -20 30 -20 40 0 s 30 20 40 0 l 0 30 q -40 20 -80 0 a 10 10 0 0 1 -5 -15 z\"/>")
            }
            sb.append("</g>")
        }
        sb.append("</svg>")
        val bytes = sb.toString().toByteArray()
        assertTrue(bytes.size > 500_000)
        // Warm up the JIT once, then measure.
        SvgToVector.convert(SvgParser.parse(bytes), 350f)
        val t0 = System.nanoTime()
        val doc = SvgParser.parse(bytes)
        val t1 = System.nanoTime()
        val c = SvgToVector.convert(doc, 350f)
        val t2 = System.nanoTime()
        assertEquals(5000, c.shapeCount)
        val parseMs = (t1 - t0) / 1e6
        val convertMs = (t2 - t1) / 1e6
        println("parse $parseMs ms, convert $convertMs ms")
        assertTrue("parse took $parseMs ms", parseMs < 3000)
        assertTrue("convert took $convertMs ms", convertMs < 5000)
    }
}
