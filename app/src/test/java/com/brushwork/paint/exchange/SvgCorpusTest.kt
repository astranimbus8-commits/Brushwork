package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.svg.SvgItem
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.vector.VPaint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * v1.5 §4.11e (A8): a fixture corpus of real-world exports (Illustrator, Inkscape, Figma,
 * Affinity) and an edge-case file — every file imports without an exception.
 */
class SvgCorpusTest {
    private fun load(name: String): ByteArray =
        javaClass.classLoader!!.getResourceAsStream("svg/$name")!!.use { it.readBytes() }

    private val files = listOf("illustrator.svg", "inkscape.svg", "figma.svg", "affinity.svg", "edge-cases.svg")

    @Test
    fun everyFixtureImportsWithoutAnException() {
        for (f in files) {
            val doc = SvgParser.parse(load(f))
            val c = SvgToVector.convert(doc, 350f)
            assertTrue("$f draws something", c.items.isNotEmpty())
            assertNotNull("$f has bounds", c.bounds)
            for (item in c.items) if (item is SvgItem.Shape) {
                for (s in item.path.subpaths) for (a in s.anchors) assertTrue("$f finite", a.x.isFinite() && a.y.isFinite())
            }
        }
    }

    @Test
    fun illustratorExportSkipsItsPrivateDataAndUsesItsClasses() {
        val c = SvgToVector.convert(SvgParser.parse(load("illustrator.svg")), 350f)
        // rect, circle, gradient path, polygon, ellipse, ring path, line, polyline.
        assertEquals(8, c.shapeCount)
        assertEquals(1, c.textCount)
        val shapes = c.items.filterIsInstance<SvgItem.Shape>().map { it.path }
        assertEquals(VPaint.Solid(0xFFE30613.toInt()), shapes[0].fill)
        assertEquals(3f, shapes[1].stroke!!.width, 1e-4f)
        assertTrue(shapes[2].fill is VPaint.Linear)
        assertEquals(0.6f, shapes[4].opacity, 1e-5f)
        assertEquals(2, shapes[5].subpaths.size)
    }

    @Test
    fun inkscapeLayersBecomeGroupsAndHiddenLayersStayOut() {
        val doc = SvgParser.parse(load("inkscape.svg"))
        // 210 x 297 mm at 350 dpi.
        val (w, h) = SvgToVector.viewportSize(doc, 350f)!!
        assertEquals(2894f, w, 1f)
        assertEquals(4093f, h, 1f)
        val c = SvgToVector.convert(doc, 350f)
        assertEquals(listOf("Layer 1", "Layer 2", "Hidden"), c.groups)
        assertTrue(c.items.none { it.group == 2 })
        // Layer 2's opacity is multiplied into its objects.
        val layer2 = c.items.filter { it.group == 1 }.filterIsInstance<SvgItem.Shape>()
        assertEquals(2, layer2.size)
        assertTrue(layer2.all { it.path.opacity == 0.5f })
        assertEquals(1, c.skipped["dashed lines (drawn solid)"])
        assertEquals(1, c.skipped["markers"])
        val text = c.items.filterIsInstance<SvgItem.Label>().single().text
        assertEquals(listOf("Hello", "Inkscape"), text.lines)
    }

    @Test
    fun figmaClipsFiltersAndMasksAreCounted() {
        val c = SvgToVector.convert(SvgParser.parse(load("figma.svg")), 350f)
        assertEquals(1, c.skipped["clip paths"])
        assertEquals(1, c.skipped["filters"])
        assertEquals(1, c.skipped["masks"])
        // background, card, ring, circle, rounded card, masked circle
        assertEquals(6, c.shapeCount)
        val radial = c.items.filterIsInstance<SvgItem.Shape>()[4].path.fill as VPaint.Radial
        assertEquals(6, radial.matrix!!.size)
    }

    @Test
    fun affinityUsesImagesAndPercentSize() {
        val c = SvgToVector.convert(SvgParser.parse(load("affinity.svg")), 350f)
        assertEquals(1, c.pictureCount)
        val pic = c.items.filterIsInstance<SvgItem.Picture>().single().image
        assertEquals("image/png", pic.mime)
        // The 1 x 1 picture stretched into the 64 px box at (150, 400) - (120.5, 80.25).
        val m = pic.placement(1, 1)
        assertEquals(64f, m.a, 1e-3f)
        assertEquals(29.5f, m.e, 1e-3f)
        assertEquals(319.75f, m.f, 1e-3f)
    }

    @Test
    fun edgeCasesNeverExpandEntitiesOrReadFiles() {
        val c = SvgToVector.convert(SvgParser.parse(load("edge-cases.svg")), 350f)
        val text = c.items.filterIsInstance<SvgItem.Label>().single().text
        assertEquals("&lol2;&xxe;", text.lines.single())
        assertEquals(1, c.skipped["external pictures"])
        assertEquals(1, c.pictureCount)
        assertTrue((c.skipped["CSS selectors"] ?: 0) >= 2)
        assertTrue((c.skipped["animations"] ?: 0) >= 2)
    }
}
