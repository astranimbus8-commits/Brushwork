package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.image.PngEncoder
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.vector.VPath
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.util.Base64
import kotlin.math.abs

/** v1.5 §4.11e (A8): SVG files into an open artwork (and a new one), one undo step each. */
@RunWith(RobolectricTestRunner::class)
class SvgImportRobolectricTest {

    private fun target(c: EditorController, room: Int = ImportLayers.room(c)) =
        ImportTarget(c.doc.width, c.doc.height, c.doc.dpi, c.doc.colorMode, room, c.maxLayers)

    private fun import(c: EditorController, svg: String, newArtwork: Boolean = false, replace: List<com.brushwork.paint.model.Layer> = emptyList()): ImportOutcome {
        val prepared = VectorImport.prepare(SvgParser.parse(svg.toByteArray()), target(c).let { it.copy(room = it.room + replace.size) }, newArtwork)
        return VectorImport.apply(c, prepared, replace)
    }

    private fun png(color: Int, size: Int = 4): String =
        Base64.getEncoder().encodeToString(PngEncoder.toByteArray(ArgbImage(size, size, IntArray(size * size) { color })))

    @Test
    fun rectCircleAndPathPixelMatchAReferenceRender() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        import(
            c,
            """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">
                <rect x="20" y="20" width="60" height="40" fill="#ff0000"/>
                <circle cx="60" cy="130" r="30" fill="none" stroke="#0000ff" stroke-width="6"/>
                <path d="M120 190 C 140 140 180 140 230 190 Z" fill="#00aa00"/>
            </svg>""",
        )
        val layer = c.doc.layers.first { it.name == "Imported SVG" }
        assertEquals(3, layer.vector!!.objects.size)
        // An independent reference: the same shapes drawn straight with the platform canvas.
        val ref = BitmapUtils.createLayerBitmap(300, 200)
        val cv = Canvas(ref)
        cv.drawRect(20f, 20f, 80f, 60f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFFF0000.toInt() })
        cv.drawCircle(60f, 130f, 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF0000FF.toInt(); style = Paint.Style.STROKE; strokeWidth = 6f; strokeJoin = Paint.Join.MITER })
        cv.drawPath(Path().apply { moveTo(120f, 190f); cubicTo(140f, 140f, 180f, 140f, 230f, 190f); close() }, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF00AA00.toInt() })
        val a = pixels(layer.bitmap)
        val e = pixels(ref)
        var painted = 0
        var off = 0
        for (i in a.indices) {
            if ((e[i] ushr 24) != 0 || (a[i] ushr 24) != 0) painted++
            val d = maxOf(abs((a[i] ushr 24) - (e[i] ushr 24)), abs(((a[i] shr 16) and 255) - ((e[i] shr 16) and 255)), abs(((a[i] shr 8) and 255) - ((e[i] shr 8) and 255)), abs((a[i] and 255) - (e[i] and 255)))
            if (d > 3) off++
            assertTrue("pixel $i differs by $d", d <= 64)
        }
        assertTrue("$off of $painted painted pixels differ", off <= painted / 50)
        // The axis-aligned rect on whole pixels is exact.
        for (y in 15 until 65) for (x in 15 until 85) assertEquals("($x, $y)", e[y * 300 + x], a[y * 300 + x])
    }

    @Test
    fun picturesGoUnderThePathsInTheirOrder() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        import(
            c,
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="200">
                <image x="0" y="0" width="100" height="100" xlink:href="data:image/png;base64,${png(0xFFFF0000.toInt())}"/>
                <rect x="50" y="50" width="100" height="100" fill="#0000ff"/>
                <image x="80" y="80" width="60" height="60" href="data:image/png;base64,${png(0xFF00FF00.toInt())}"/>
            </svg>""",
        )
        val names = c.doc.layers.map { it.name }
        assertTrue(names.indexOf("SVG pictures") < names.indexOf("Imported SVG"))
        val pics = c.doc.layers.first { it.name == "SVG pictures" }.bitmap
        assertEquals(0xFFFF0000.toInt(), pics.getPixel(10, 10))
        assertEquals(0xFF00FF00.toInt(), pics.getPixel(90, 90)) // the later picture on top
        assertEquals(0xFF0000FF.toInt(), c.doc.layers.first { it.name == "Imported SVG" }.bitmap.getPixel(120, 120))
    }

    @Test
    fun oneUndoStepThenTransformOnTheImportedObjects() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val before = c.undoManager.undoCount
        val o = import(
            c,
            """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">
                <rect width="10" height="10"/><rect x="20" width="10" height="10"/>
                <image width="4" height="4" href="data:image/png;base64,${png(-1)}"/>
                <text x="10" y="40">Hi</text>
            </svg>""",
        )
        assertEquals(2, o.shapes)
        assertEquals(1, o.pictures)
        assertEquals(1, o.texts)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(4, c.doc.layers.size)
        val vec = c.doc.layers.first { it.isVectorLayer }
        assertTrue(c.activeLayer === vec)
        assertEquals(vec.vector!!.objects.map { it.id }.toSet(), c.vectors.selectedIds)
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        c.currentTool.discard()
        c.undo()
        assertEquals(1, c.doc.layers.size)
        c.redo()
        assertEquals(4, c.doc.layers.size)
    }

    @Test
    fun textsBecomeTextLayersAnchoredWhereTheSvgPutsThem() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        import(c, """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><text x="20" y="50" font-size="24" fill="#123456">Hello</text></svg>""")
        val layer = c.doc.layers.first { it.textData != null }
        val item = TextCodec.decode(layer.textData)!!
        assertEquals("Hello", item.text)
        assertEquals(24f, item.spec.sizePx, 1e-4f)
        assertEquals(0xFF123456.toInt(), item.spec.color)
        // The first baseline's start lands on (20, 50).
        val block = TextRenderer.layout(item.text, item.spec)
        val paint = android.text.TextPaint().apply { typeface = TextRenderer.typeface(item.spec); textSize = item.spec.sizePx }
        val p = item.localToDoc(block.inset, block.inset - paint.fontMetrics.ascent, block.width, block.height)
        assertEquals(20f, p.x, 0.5f)
        assertEquals(50f, p.y, 0.5f)
    }

    @Test
    fun contentThatFitsKeepsItsCoordinatesElseNinetyPercentCentred() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        import(c, """<svg xmlns="http://www.w3.org/2000/svg" width="100" height="100"><rect x="10" y="10" width="10" height="10"/></svg>""")
        val small = c.doc.layers.last { it.isVectorLayer }.vector!!.objects[0] as VPath
        assertEquals(10f, small.subpaths[0].anchors[0].x, 1e-4f)
        import(c, """<svg xmlns="http://www.w3.org/2000/svg" width="1000" height="1000"><rect width="1000" height="1000"/></svg>""")
        val big = c.doc.layers.last { it.isVectorLayer }.vector!!.objects[0] as VPath
        // 0.9 * min(300, 200) / 1000 = 0.18: 180 px wide, centred.
        assertEquals(60f, big.subpaths[0].anchors[0].x, 1e-3f)
        assertEquals(10f, big.subpaths[0].anchors[0].y, 1e-3f)
        assertEquals(240f, big.subpaths[0].anchors[1].x, 1e-3f)
    }

    @Test
    fun layersAreAddedInPriorityOrderWhileTheyFit() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val svg = SvgParser.parse(
            """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect width="5" height="5"/><image width="4" height="4" href="data:image/png;base64,${png(-1)}"/><text y="20">a</text><text y="40">b</text></svg>""".toByteArray(),
        )
        val two = VectorImport.prepare(svg, target(c, room = 2), newArtwork = false)
        assertEquals(listOf("SVG pictures", "Imported SVG"), two.layers.map { it.name })
        assertEquals(0, two.texts.size)
        assertEquals(2, two.outcome.dropped["texts (layer limit)"])
        val one = VectorImport.prepare(svg, target(c, room = 1), newArtwork = false)
        assertEquals(listOf("Imported SVG"), one.layers.map { it.name })
        assertEquals(1, one.outcome.dropped["pictures (layer limit)"])
        val nine = SvgParser.parse(
            ("""<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">""" + (1..10).joinToString("") { "<text y=\"${it * 15}\">t$it</text>" } + "</svg>").toByteArray(),
        )
        val many = VectorImport.prepare(nine, target(c, room = 20), newArtwork = false)
        assertEquals(VectorImport.MAX_TEXTS, many.texts.size)
        assertEquals(2, many.outcome.dropped["texts (more than 8)"])
    }

    @Test
    fun aNewArtworkGetsOneVectorLayerPerTopLevelGroupInPlaceOfLayerOne() {
        val doc = Smoke.document(400, 400, layers = 2, whiteBottom = true)
        doc.layers[0].name = "Background"
        val c = controller(doc)
        val layer1 = doc.layers[1]
        val before = c.undoManager.undoCount
        import(
            c,
            """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="100mm" height="100mm" viewBox="0 0 100 100">
                <g inkscape:label="Sky"><rect width="100" height="40" fill="#88ccff"/></g>
                <g inkscape:label="Ground"><rect y="60" width="100" height="40" fill="#448800"/></g>
                <g inkscape:label="Sun"><circle cx="80" cy="20" r="10" fill="#ffcc00"/></g>
            </svg>""",
            newArtwork = true, replace = listOf(layer1),
        )
        assertEquals(listOf("Background", "Sky", "Ground", "Sun"), c.doc.layers.map { it.name })
        assertEquals(before + 1, c.undoManager.undoCount)
        // The 100 mm viewport fills the 400 px canvas.
        val ground = c.doc.layers[2].vector!!.objects[0] as VPath
        assertEquals(240f, ground.subpaths[0].anchors[0].y, 1e-3f)
        c.currentTool.discard()
        c.undo()
        assertEquals(listOf("Background", "Layer 2"), c.doc.layers.map { it.name })
    }

    @Test
    fun aTooComplexSvgCanComeInAsOnePicture() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val svg = SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200"><rect x="10" y="10" width="50" height="50" fill="#ff0000"/></svg>""".toByteArray())
        val content = SvgToVector.convert(svg, 350f)
        val prepared = VectorImport.prepare(content, target(c), newArtwork = false, asPicture = true)
        val o = VectorImport.apply(c, prepared)
        assertEquals(1, o.layers)
        val layer = c.doc.layers.last()
        assertEquals(null, layer.vector)
        assertEquals(0xFFFF0000.toInt(), layer.bitmap.getPixel(30, 30))
        assertArrayEquals(IntArray(1) { 0 }, IntArray(1) { layer.bitmap.getPixel(100, 100) })
        assertTrue(layer.bitmap.config == Bitmap.Config.ARGB_8888)
    }
}
