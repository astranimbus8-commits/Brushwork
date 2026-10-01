package com.brushwork.paint.exchange

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.exchange.export.SceneImage
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SceneLayer
import com.brushwork.paint.exchange.export.SceneMask
import com.brushwork.paint.exchange.export.SceneStroke
import com.brushwork.paint.exchange.export.SceneTextLine
import com.brushwork.paint.exchange.export.SceneTextStyle
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgItem
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** v1.5 §4.10e (A8): golden SVG snippets, well-formed output, the payload and pictures read back. */
class SvgWriterTest {
    private val w = 120
    private val h = 80
    private val photo = ArgbImage(6, 4, IntArray(24) { i -> ((i * 10) shl 24) or (i * 0x030507) })
    private val mask = ArgbImage(5, 5, IntArray(25) { i -> val g = i * 10; (0xFF shl 24) or (g * 0x010101) })

    private fun scene(): ExportScene {
        val img = SceneImage("img-1", 3, 4, 6, 4, false) { photo }
        val m = SceneImage("mask-2", 10, 10, 5, 5, true) { mask }
        val path = VectorPath(listOf(PathOp.MoveTo(Vec2(1f, 2f)), PathOp.LineTo(Vec2(10.5f, 2f)), PathOp.CubicTo(Vec2(11f, 3f), Vec2(12f, 4f), Vec2(13.25f, 5f)), PathOp.Close))
        return ExportScene(
            w, h, 300f, "A & B <test>", 0xFFFFFFFF.toInt(),
            listOf(
                SceneLayer("layer-1", "Ink & \"paint\"", 0.5f, LayerBlendMode.ADD, false, SceneMask("mask-2", 0, m), listOf(
                    SceneItem.Image(img),
                    SceneItem.Shape(path, evenOdd = true, fill = VPaint.Solid(0x80FF8000.toInt()), stroke = SceneStroke(0xFF0000FF.toInt(), 2.5f, LineCapStyle.SQUARE, JoinStyle.MITER, 7f), opacity = 0.75f),
                    SceneItem.Shape(path, fill = VPaint.Linear(0f, 0f, 10f, 0f, listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0x400000FF)))),
                    SceneItem.Shape(path, fill = VPaint.Radial(5f, 5f, 3f, listOf(VStop(0f, -1), VStop(1f, 0xFF000000.toInt())), listOf(2f, 0f, 0f, 1f, 3f, 4f))),
                    SceneItem.Text(listOf(SceneTextLine("Hi <you>", 0f, 20f), SceneTextLine("2nd", 0f, 44f)), SceneTextStyle("serif", 20f, true, false, 0xFF112233.toInt(), 3f, 0xFFFFFFFF.toInt()), listOf(1f, 0f, 0f, 1f, 7f, 8f)),
                )),
                SceneLayer("layer-2", "Hidden", 1f, LayerBlendMode.MULTIPLY, true, null, listOf(SceneItem.Shape(path, stroke = SceneStroke(0xFF000000.toInt(), 1f)))),
            ),
            BrushworkPayload(width = w, height = h, dpi = 300f, layers =listOf(PayloadLayer(1, LayerProps("Ink", 1f, LayerBlendMode.NORMAL, true, false, false, false, true), imageRef = "img-1", imageRect = PayloadRect(3, 4, 6, 4)))),
            listOf(SceneImage("pimg-9", 0, 0, 5, 5, true) { mask }),
        )
    }

    private fun write(): String {
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene()).write(out) }
        return out.toString("UTF-8")
    }

    @Test
    fun outputIsWellFormedXmlWithTheExpectedStructure() {
        val svg = write()
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        val dom = f.newDocumentBuilder().parse(ByteArrayInputStream(svg.toByteArray()))
        val root = dom.documentElement
        assertEquals("svg", root.localName)
        assertEquals("http://www.w3.org/2000/svg", root.namespaceURI)
        assertEquals("0 0 $w $h", root.getAttribute("viewBox"))
        // 120 px at 300 dpi = 10.16 mm.
        assertEquals("10.16mm", root.getAttribute("width"))
        assertEquals("A & B <test>", dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "title").item(0).textContent)
        val groups = dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g")
        assertEquals(2, groups.length)
        val g1 = groups.item(0) as org.w3c.dom.Element
        assertEquals("layer", g1.getAttributeNS("http://www.inkscape.org/namespaces/inkscape", "groupmode"))
        assertEquals("Ink & \"paint\"", g1.getAttributeNS("http://www.inkscape.org/namespaces/inkscape", "label"))
        assertEquals("opacity:0.5;mix-blend-mode:plus-lighter;isolation:isolate", g1.getAttribute("style"))
        assertEquals("url(#mask-2)", g1.getAttribute("mask"))
        val g2 = groups.item(1) as org.w3c.dom.Element
        assertEquals("mix-blend-mode:multiply;isolation:isolate;display:none", g2.getAttribute("style"))
    }

    @Test
    fun goldenSnippets() {
        val svg = write()
        assertTrue(svg, svg.contains("<rect id=\"background\" x=\"0\" y=\"0\" width=\"120\" height=\"80\" fill=\"#ffffff\"/>"))
        assertTrue(svg, svg.contains("<mask id=\"mask-2\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"120\" height=\"80\" style=\"mask-type:luminance\"><rect x=\"0\" y=\"0\" width=\"120\" height=\"80\" fill=\"#000000\"/><image x=\"10\" y=\"10\" width=\"5\" height=\"5\" preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,"))
        assertTrue(svg, svg.contains("<path d=\"M1 2 L10.5 2 C11 3 12 4 13.25 5 Z\" fill=\"#ff8000\" fill-opacity=\"0.502\" fill-rule=\"evenodd\" stroke=\"#0000ff\" stroke-width=\"2.5\" stroke-linecap=\"square\" stroke-linejoin=\"miter\" stroke-miterlimit=\"7\" opacity=\"0.75\"/>"))
        assertTrue(svg, svg.contains("<linearGradient id=\"grad-1\" gradientUnits=\"userSpaceOnUse\" x1=\"0\" y1=\"0\" x2=\"10\" y2=\"0\"><stop offset=\"0\" stop-color=\"#ff0000\"/><stop offset=\"1\" stop-color=\"#0000ff\" stop-opacity=\"0.251\"/></linearGradient>"))
        assertTrue(svg, svg.contains("fill=\"url(#grad-1)\""))
        assertTrue(svg, svg.contains("<radialGradient id=\"grad-2\" gradientUnits=\"userSpaceOnUse\" cx=\"5\" cy=\"5\" r=\"3\" gradientTransform=\"matrix(2 0 0 1 3 4)\">"))
        assertTrue(svg, svg.contains("<text xml:space=\"preserve\" transform=\"matrix(1 0 0 1 7 8)\" font-family=\"serif\" font-size=\"20\" font-weight=\"bold\" fill=\"#112233\" stroke=\"#ffffff\" stroke-width=\"3\" stroke-linejoin=\"round\" paint-order=\"stroke\"><tspan x=\"0\" y=\"20\">Hi &lt;you&gt;</tspan><tspan x=\"0\" y=\"44\">2nd</tspan></text>"))
        assertTrue(svg, svg.contains("<bw:payload version=\"1\" encoding=\"deflate+base64\">"))
    }

    @Test
    fun ownParserReadsThePayloadAndEveryPictureBack() {
        val bytes = write().toByteArray()
        val doc = SvgParser.parse(bytes)
        assertEquals(scene().payload, doc.payload())
        assertArrayEquals(photo.pixels, PngDecoder.decode(doc.imageData("img-1")!!)!!.pixels)
        assertArrayEquals(mask.pixels, PngDecoder.decode(doc.imageData("pimg-9")!!)!!.pixels)
        // A foreign reader would see the visible content: the hidden layer draws nothing.
        val c = SvgToVector.convert(doc, 300f)
        assertEquals(1, c.pictureCount)
        // The background rect and layer 1's three paths (the mask is counted as skipped).
        assertEquals(4, c.shapeCount)
        assertEquals(1, c.skipped["masks"])
        assertTrue(c.items.any { it is SvgItem.Label })
    }
}
