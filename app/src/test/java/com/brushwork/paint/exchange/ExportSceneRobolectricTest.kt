package com.brushwork.paint.exchange

import android.graphics.Path
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.StrokeExport
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextExportMode
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextLineRun
import com.brushwork.paint.tools.text.TextRunPaint
import com.brushwork.paint.vector.VPaint
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/** v1.5 §4.10e (A8): the scene builder for raster, vector, text, adjustment and clipping layers. */
@RunWith(RobolectricTestRunner::class)
class ExportSceneRobolectricTest {

    private fun scene(doc: Document, options: ExportOptions, text: TextSource = TextSource.Default): ExportScene {
        val c = controller(doc)
        return runBlocking { ExportSceneBuilder(c, options, text, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
    }

    private fun layerNamed(s: ExportScene, name: String) = s.layers.first { it.name == name }

    @Test
    fun rasterLayersAreCroppedPicturesWithTheirMaskAndProps() {
        val doc = document()
        val s = scene(doc, ExportOptions(VectorFormat.SVG))
        val photo = layerNamed(s, "Photo")
        assertEquals(LayerBlendMode.MULTIPLY, photo.blend)
        assertEquals(0.7f, photo.opacity, 1e-6f)
        val img = (photo.items.single() as SceneItem.Image).image
        // Drawn from x = 40 to 140, y = 30 to 120.
        assertEquals(40, img.left)
        assertEquals(30, img.top)
        assertEquals(100, img.width)
        assertEquals(90, img.height)
        val px = runBlocking { img.source.load() }
        val layer = doc.layers.first { it.name == "Photo" }
        val all = pixels(layer.bitmap)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            assertEquals(all[(y + img.top) * doc.width + x + img.left], px.pixels[y * img.width + x])
        }
        // The mask: white around a black box.
        val mask = photo.mask!!
        assertEquals(255, mask.fillGray)
        assertEquals(60, mask.image!!.left)
        assertEquals(40, mask.image!!.width)
        // The background is opaque white and becomes a full picture.
        assertNotNull(layerNamed(s, "Background").items.single() as SceneItem.Image)
    }

    @Test
    fun vectorLayersBecomePathsOutlinesAndPictureRuns() {
        val s = scene(document(), ExportOptions(VectorFormat.SVG))
        val items = layerNamed(s, "Vector").items
        // box (fill + line), pen stroke outline, one picture for the pencil and chalk strokes in a row,
        // then the ellipse's fill + line.
        assertEquals(4, items.size)
        val box = items[0] as SceneItem.Shape
        assertEquals(VPaint.Solid(0xFFE04020.toInt()), box.fill)
        assertEquals(3f, box.stroke!!.width, 1e-6f)
        val pen = items[1] as SceneItem.Shape
        assertEquals(VPaint.Solid(0xFF2050C0.toInt()), pen.fill)
        assertTrue(pen.stroke == null)
        assertTrue(items[2] is SceneItem.Image)
        assertTrue(items[3] is SceneItem.Shape)
        // As pictures: the strokes are pictures (the two consecutive ones after the pen share one).
        val pics = layerNamed(scene(document(), ExportOptions(VectorFormat.SVG, strokes = StrokeExport.PICTURES)), "Vector").items
        assertEquals(listOf(SceneItem.Shape::class, SceneItem.Image::class, SceneItem.Shape::class), pics.map { it::class })
    }

    @Test
    fun textIsRealTextOutlinesOrPixels() {
        val fake = object : TextSource {
            override fun lines(item: TextItem) = listOf(TextLineRun(item.text, 0f, 30f, TextRunPaint(item.spec, listOf(1f, 0f, 0f, 1f, 100f, 70f))))
            override fun outlines(doc: Document, layer: Layer) = Path().apply { addRect(110f, 80f, 190f, 120f, Path.Direction.CW) }
        }
        val svg = layerNamed(scene(document(), ExportOptions(VectorFormat.SVG), fake), "Text").items.single() as SceneItem.Text
        assertEquals("Hello", svg.lines.single().text)
        assertEquals(40f, svg.style.sizePx, 1e-6f)
        assertEquals(listOf(1f, 0f, 0f, 1f, 100f, 70f), svg.matrix)
        // Outlines wanted, or PDF: the glyph outlines filled with the text colour.
        val outl = layerNamed(scene(document(), ExportOptions(VectorFormat.SVG, text = TextExportMode.OUTLINES), fake), "Text").items.single() as SceneItem.Shape
        assertEquals(VPaint.Solid(0xFF883300.toInt()), outl.fill)
        assertTrue(layerNamed(scene(document(), ExportOptions(VectorFormat.PDF), fake), "Text").items.single() is SceneItem.Shape)
        // Without A7's layout (today's TextExport): the layer's pixels.
        assertTrue(layerNamed(scene(document(), ExportOptions(VectorFormat.SVG)), "Text").items.single() is SceneItem.Image)
    }

    @Test
    fun clippingGroupsAndHiddenLayers() {
        val s = scene(document(), ExportOptions(VectorFormat.SVG))
        assertTrue(s.layers.none { it.name == "Hidden" || it.name == "Clipped" })
        val base = layerNamed(s, "Base")
        assertEquals(0.9f, base.opacity, 1e-6f)
        val img = (base.items.single() as SceneItem.Image).image
        val px = runBlocking { img.source.load() }
        // Inside the base: the clipped magenta; outside it: nothing of the clipped layer.
        assertEquals(0xFFFF00FF.toInt(), px.pixels[(40 - img.top) * img.width + (160 - img.left)])
        assertEquals(0xFF0000FF.toInt(), px.pixels[(25 - img.top) * img.width + (160 - img.left)])
        assertTrue(s.notes.any { it.contains("Clipping") })
        val withHidden = scene(document(), ExportOptions(VectorFormat.SVG, includeHidden = true))
        assertTrue(layerNamed(withHidden, "Hidden").hidden)
    }

    @Test
    fun adjustmentLayersMergeEverythingBelowThem() {
        val doc = document(adjustment = true)
        val s = scene(doc, ExportOptions(VectorFormat.PDF))
        // Background + the adjustment become one picture; the layers above stay.
        assertEquals("Invert 1 (merged with the layers below)", s.layers[0].name)
        val img = (s.layers[0].items.single() as SceneItem.Image).image
        assertEquals(doc.width, img.width)
        assertEquals("Photo", s.layers[1].name)
        assertTrue(s.notes.any { it.contains("adjustment") })
    }

    @Test
    fun thePayloadListsEveryLayerWithItsDataAndPictures() {
        val doc = document(adjustment = true)
        val s = scene(doc, ExportOptions(VectorFormat.SVG))
        val p = s.payload!!
        assertEquals(doc.layers.map { it.id }, p.layers.map { it.id })
        assertEquals(doc.layers.map { it.props() }, p.layers.map { it.props })
        assertEquals(
            listOf(PayloadKind.RASTER, PayloadKind.ADJUSTMENT, PayloadKind.RASTER, PayloadKind.VECTOR, PayloadKind.TEXT, PayloadKind.SHAPE, PayloadKind.RASTER, PayloadKind.RASTER, PayloadKind.RASTER),
            p.layers.map { it.kind },
        )
        val vec = p.layers.first { it.kind == PayloadKind.VECTOR }
        assertEquals(doc.layers.first { it.isVectorLayer }.vector, vec.vector)
        assertEquals(null, vec.imageRef)
        val adj = p.layers.first { it.kind == PayloadKind.ADJUSTMENT }
        assertEquals("adjust.invert", adj.adjustment!!.filterId)
        assertNotNull(adj.maskSpec)
        assertTrue(adj.hasMask)
        // Pictures not otherwise in the file (merged, hidden, text...) travel with the payload.
        val keys = (s.layers.flatMap { l -> l.items.mapNotNull { (it as? SceneItem.Image)?.image?.key } + listOfNotNull(l.mask?.image?.key) } + s.payloadImages.map { it.key }).toSet()
        for (l in p.layers) {
            l.imageRef?.let { assertTrue("${l.props.name}: $it", it in keys) }
            l.maskRef?.let { assertTrue("${l.props.name} mask: $it", it in keys) }
        }
        assertEquals(doc.activeLayerIndex, p.activeLayer)
    }

    @Test
    fun theWholeDocumentWritesWellFormedSvg() {
        val s = scene(document(adjustment = true), ExportOptions(VectorFormat.SVG, includeHidden = true, whiteBackground = true))
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(s).write(out) }
        val dom = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }.newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
        assertEquals("svg", dom.documentElement.localName)
        assertArrayEquals(
            s.layers.map { it.key }.toTypedArray(),
            (0 until dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g").length).map {
                (dom.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g").item(it) as org.w3c.dom.Element).getAttribute("id")
            }.toTypedArray(),
        )
    }
}
