package com.brushwork.paint.exchange.export

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.ExchangeFixtures
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgDocument
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.ArrayLayout
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.geom.ObjectIndex
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.sign

/**
 * v1.7 area A (§5.1 A; items 3 and 18, I14): a vector array exports every copy's objects placed
 * by the `ArrayLayout` matrices (source on top), a text array its copies' outlines placed the same
 * way (v1.7 §6.2, no `<text>`), a raster array its cache; a stroke with
 * symmetry copies is one outline of one envelope per copy, every envelope winding the same way;
 * the payload carries an array (spec and source container) and the import gives it back on the
 * payload's canvas, keeping the copies as pixels elsewhere or when the array is damaged.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayExportRobolectricTest {
    private val w = 256
    private val h = 128

    // ------------------------------------------------------------------ building

    private fun pixels(b: Bitmap): IntArray = ExchangeFixtures.pixels(b)

    /** Equal, but for one level of premultiplied rounding where alpha is partial (a PNG round trip). */
    private fun assertPixels(what: String, e: IntArray, a: IntArray) {
        assertEquals(e.size, a.size)
        for (i in e.indices) {
            if (e[i] == a[i]) continue
            val ea = e[i] ushr 24
            assertEquals("$what alpha at $i", ea, a[i] ushr 24)
            assertTrue("$what partial alpha at $i", ea in 1..254)
            for (s in intArrayOf(16, 8, 0)) {
                assertTrue("$what channel at $i", abs(((e[i] shr s) and 0xFF) - ((a[i] shr s) and 0xFF)) <= 255 / ea + 1)
            }
        }
    }

    /** [objects] repeated per matrix of [spec], copy N − 1 at the bottom (as the cache draws them). */
    private fun expanded(content: VectorContent, spec: ArraySpec): List<VObject> {
        val ms = ArrayLayout.matrices(spec, ObjectIndex.of(content).unionBounds())
        return ms.indices.reversed().flatMap { k -> content.objects.map { if (k == 0) it else VectorOps.transformed(it, ms[k]).withId(it.id + (k.toLong() shl 53)) } }
    }

    /** A vector layer arrayed by [spec]; its cache shows every copy. */
    private fun vectorArray(doc: Document, name: String, content: VectorContent, spec: ArraySpec): Layer {
        val cache = ExchangeFixtures.render(VectorContent(objects = expanded(content, spec)), doc.width, doc.height)
        return Layer(doc.newLayerId(), name, cache).also { it.vector = content; it.array = LayerArray(spec) }
    }

    /** A 20 x 20 red square at (10, 10) arrayed three times 40 px apart (the cache drawn by hand). */
    private fun rasterArray(doc: Document, name: String = "Stamps"): Layer {
        val src = BitmapUtils.createLayerBitmap(20, 20).also { it.eraseColor(0xFFE03020.toInt()) }
        val cache = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        for (k in 2 downTo 0) Canvas(cache).drawBitmap(src, 10f + 40f * k, 10f, null)
        return Layer(doc.newLayerId(), name, cache).also { it.array = LayerArray(ArraySpec(count = 3, relativeX = 0f, constantX = 40f), ArrayPixels(src, 10, 10)) }
    }

    /** A text layer arrayed twice (its cache: the text drawn at both places by hand). */
    private fun textArray(doc: Document, name: String = "Words"): Layer {
        val item = TextItem("Hi", TextSpec(sizePx = 30f, color = 0xFF2040E0.toInt()), cx = 40f, cy = 90f)
        val cache = BitmapUtils.createLayerBitmap(doc.width, doc.height)
        for (dx in floatArrayOf(60f, 0f)) {
            val c = Canvas(cache)
            c.translate(dx, 0f)
            TextRenderer.drawItem(c, item, TextRenderer.prepare(item), null)
        }
        return Layer(doc.newLayerId(), name, cache).also {
            it.textData = TextCodec.encode(item)
            it.array = LayerArray(ArraySpec(count = 2, relativeX = 0f, constantX = 60f))
        }
    }

    private fun boxes(): VectorContent = VectorContent.EMPTY.plus(listOf(ExchangeFixtures.box(10f, 10f, 40f, 30f))).first

    private fun docOf(build: (Document) -> List<Layer>): Document = Document("arrays", "arrays", w, h).also { d ->
        d.layers += Layer(d.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(-1) })
        d.layers += build(d)
        d.activeLayerIndex = d.layers.lastIndex
    }

    private fun scene(doc: Document, format: VectorFormat = VectorFormat.SVG): ExportScene = scene(ExchangeFixtures.controller(doc), format)

    private fun scene(c: EditorController, format: VectorFormat = VectorFormat.SVG): ExportScene =
        runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    private fun List<SceneLayer>.named(name: String): SceneLayer = first { it.name == name }

    private fun VectorPath.box(): FloatArray = bounds()!!.let { floatArrayOf(it.left, it.top, it.right, it.bottom) }

    /** Twice the signed area of [p]'s flattened contours (their winding: the sign). */
    private fun signedArea(p: VectorPath): Double {
        var sum = 0.0
        for (line in p.flatten(0.1f)) {
            val pts = line.points
            for (i in pts.indices) {
                val a = pts[i]
                val b = pts[(i + 1) % pts.size]
                sum += a.x.toDouble() * b.y - b.x.toDouble() * a.y
            }
        }
        return sum
    }

    // ------------------------------------------------------------------ the scene

    @Test
    fun aVectorArrayExportsEveryCopysObjectsPlacedByItsMatrixSourceOnTop() {
        val spec = ArraySpec(count = 3, relativeX = 0f, constantX = 50f)
        val doc = docOf { d -> listOf(vectorArray(d, "Boxes", boxes(), spec)) }
        val items = scene(doc).layers.named("Boxes").items
        assertEquals("one shape per copy", 3, items.size)
        val lefts = items.map { (it as SceneItem.Shape).path.box()[0] }
        // Copy 2 at the bottom, the source on top (as the cache draws them).
        assertEquals(110f, lefts[0], 1e-3f)
        assertEquals(60f, lefts[1], 1e-3f)
        assertEquals(10f, lefts[2], 1e-3f)
        for (item in items) {
            val s = item as SceneItem.Shape
            assertEquals(30f, s.path.box()[2] - s.path.box()[0], 1e-3f)
            assertEquals(0xFFE04020.toInt(), (s.fill as VPaint.Solid).color)
        }

        // More copies than the editor lists (count × objects > MAX_INSTANCES): the cache.
        val many = VectorContent.EMPTY.plus(List(101) { i -> ExchangeFixtures.box(1f + i % 50, 1f + i / 50, 2f + i % 50, 2f + i / 50) }).first
        val big = ArraySpec(count = ArraySpec.MAX_COUNT, relativeX = 0f, constantX = 0.5f)
        assertTrue(big.count.toLong() * many.objects.size > ArraySpec.MAX_INSTANCES)
        val doc2 = docOf { d -> listOf(Layer(d.newLayerId(), "Many", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(0xFF00FF00.toInt()) }).also { it.vector = many; it.array = LayerArray(big) }) }
        assertTrue(scene(doc2).layers.named("Many").items.single() is SceneItem.Image)
    }

    @Test
    fun aRasterArrayExportsItsCacheAndATextArrayItsCopiesOutlines() {
        val doc = docOf { d -> listOf(rasterArray(d), textArray(d)) }
        val s = scene(doc)
        val item = s.layers.named("Stamps").items.single()
        assertTrue("its cache, every copy in it", item is SceneItem.Image)
        val img = (item as SceneItem.Image).image
        val px = runBlocking { img.source.load() }.pixels
        val all = pixels(doc.layers.first { it.name == "Stamps" }.bitmap)
        for (y in 0 until img.height) for (x in 0 until img.width) {
            assertEquals("Stamps at $x, $y", all[(y + img.top) * w + x + img.left], px[y * img.width + x])
        }
        // The raster array's picture reaches its last copy.
        assertEquals(10, img.left)
        assertEquals(110, img.left + img.width)

        // v1.7 (§6.2): a text array is every copy's outlines (never `<text>`), copy 1 under the
        // source, placed by the matrix (60 px to the right), each in the text's color.
        val words = s.layers.named("Words").items
        assertEquals("one outline per copy", 2, words.size)
        val shapes = words.map { it as SceneItem.Shape }
        for (sh in shapes) assertEquals(VPaint.Solid(0xFF2040E0.toInt()), sh.fill)
        val copy = shapes[0].path.box()
        val source = shapes[1].path.box()
        for (i in 0..3) assertEquals(source[i] + if (i % 2 == 0) 60f else 0f, copy[i], 1e-3f)
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(s).write(out) }
        assertFalse("no <text>", out.toString("UTF-8").contains("<text"))
    }

    @Test
    fun aStrokeWithThreeCopiesIsOneOutlineOfThreeEnvelopesWindingTheSameWay() {
        val pen = BrushLibrary.defaultBrush.copy(size = 10f)
        val base = ExchangeFixtures.stroke(20f, 40f, 100f, 60f, preset = pen)
        val identity = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)
        val shift = floatArrayOf(1f, 0f, 100f, 0f, 1f, 0f, 0f, 0f, 1f)
        val mirror = floatArrayOf(-1f, 0f, 256f, 0f, 1f, 0f, 0f, 0f, 1f)
        val stroke = base.copy(copies = listOf(identity, shift, mirror))
        // Without copies: the v1.6 outline, unchanged.
        val own = StrokeEnvelopeExport.of(base)!!
        assertEquals(listOf(own.ops), StrokeEnvelopeExport.ofCopies(base).map { it.ops })
        val envs = StrokeEnvelopeExport.ofCopies(stroke)
        assertEquals("one envelope per copy", 3, envs.size)
        assertEquals("the identity copy is the stroke's own outline", own.ops, envs[0].ops)
        val b = own.box()
        val moved = envs[1].box()
        for (i in 0..3) assertEquals(b[i] + if (i % 2 == 0) 100f else 0f, moved[i], 0.05f)
        val mirrored = envs[2].box()
        assertEquals(256f - b[2], mirrored[0], 0.2f)
        assertEquals(256f - b[0], mirrored[2], 0.2f)
        assertEquals(b[1], mirrored[1], 0.2f)
        // Built after the map: the mirrored copy winds as the others (non-zero fills the union).
        val sign = signedArea(own).sign
        assertTrue(sign != 0.0)
        for (e in envs) assertEquals(sign, signedArea(e).sign, 0.0)

        // In the scene: ONE shape with the three envelopes, non-zero, the stroke's fill color.
        val content = VectorContent.EMPTY.plus(listOf(stroke)).first
        val doc = docOf { d -> listOf(Layer(d.newLayerId(), "Strokes", ExchangeFixtures.render(content, w, h)).also { it.vector = content }) }
        val shape = scene(doc).layers.named("Strokes").items.single() as SceneItem.Shape
        assertFalse(shape.evenOdd)
        assertEquals(envs.flatMap { it.ops }, shape.path.ops)
        val placed = content.objects.single() as VStroke
        assertEquals(StrokeEnvelopeExport.fillColor(placed.color, placed.preset, placed.opacity), (shape.fill as VPaint.Solid).color)
    }

    // ------------------------------------------------------------------ the payload

    private fun svgOf(c: EditorController): SvgDocument {
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene(c)).write(out) }
        return SvgParser.parse(out.toByteArray())
    }

    private fun prepare(svg: SvgDocument, target: ImportTarget): PayloadImport.Prepared =
        PayloadImport.prepare(svg.payload()!!, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target)

    @Test
    fun thePayloadCarriesArraysAndTheImportGivesThemBack() {
        val spec = ArraySpec(count = 3, relativeX = 0f, constantX = 50f)
        val doc = docOf { d -> listOf(vectorArray(d, "Boxes", boxes(), spec), rasterArray(d), textArray(d)) }
        val svg = svgOf(ExchangeFixtures.controller(doc))
        val payload = svg.payload()!!
        assertEquals("arrays are v1.7 data", 2, payload.version)
        for (name in listOf("Boxes", "Stamps", "Words")) {
            val pl = payload.layers.first { it.props.name == name }
            assertEquals("$name: its cache, a raster layer for v1.6", PayloadKind.RASTER, pl.kind)
            assertNotNull(name, pl.array)
            assertNotNull(name, pl.imageRef)
            assertNull("$name: the source lives in the container only", pl.vector)
            assertNull(name, pl.textData)
        }
        // A raster array's source pixels take a slot of their own.
        assertEquals(listOf("Background", "Boxes"), PayloadImport.fitting(payload.layers, 3).map { it.props.name })
        assertEquals(4, PayloadImport.fitting(payload.layers, 5).size)

        // Into a new artwork of the same size: every array back.
        val fresh = Smoke.document(w, h, layers = 1, whiteBottom = true)
        val c = ExchangeFixtures.controller(fresh)
        val replace = fresh.layers.toList()
        val target = ImportTarget(w, h, fresh.dpi, fresh.colorMode, ImportLayers.room(c) + replace.size, c.maxLayers)
        val o = PayloadImport.apply(c, prepare(svg, target), replace)
        assertTrue(o.dropped.toString(), o.dropped.isEmpty())
        assertEquals(doc.layers.map { it.name }, c.doc.layers.map { it.name })
        val boxes = c.doc.layers.first { it.name == "Boxes" }
        assertEquals(doc.layers.first { it.name == "Boxes" }.vector, boxes.vector)
        assertEquals(spec, boxes.array!!.spec)
        assertNull(boxes.array!!.pixels)
        val stamps = c.doc.layers.first { it.name == "Stamps" }
        val pixelsBack = stamps.array!!.pixels!!
        assertEquals(10, pixelsBack.left)
        assertEquals(10, pixelsBack.top)
        assertArrayEquals(pixels(doc.layers.first { it.name == "Stamps" }.array!!.pixels!!.bitmap), pixels(pixelsBack.bitmap))
        val words = c.doc.layers.first { it.name == "Words" }
        assertEquals(doc.layers.first { it.name == "Words" }.textData, words.textData)
        assertFalse(words.array!!.spec.editingSource)
        for (name in listOf("Boxes", "Stamps")) {
            assertPixels("$name: the cache", pixels(doc.layers.first { it.name == name }.bitmap), pixels(c.doc.layers.first { it.name == name }.bitmap))
        }
    }

    @Test
    fun onAnotherCanvasOrDamagedTheCopiesStayPixels() {
        val doc = docOf { d -> listOf(vectorArray(d, "Boxes", boxes(), ArraySpec(count = 3, relativeX = 0f, constantX = 50f)), rasterArray(d)) }
        val svg = svgOf(ExchangeFixtures.controller(doc))

        // Another canvas size: placed pixels only.
        val small = Smoke.document(128, 64, layers = 1)
        val c = ExchangeFixtures.controller(small)
        val o = PayloadImport.apply(c, prepare(svg, ImportTarget(128, 64, 300f, small.colorMode, ImportLayers.room(c), c.maxLayers)))
        assertEquals(2, o.dropped["arrays (kept as pixels: other canvas size)"])
        for (name in listOf("Boxes", "Stamps")) {
            val l = c.doc.layers.first { it.name == name }
            assertNull(name, l.array)
            assertNull(name, l.vector)
            assertTrue(name, pixels(l.bitmap).any { it != 0 })
        }

        // A damaged container on the payload's canvas: the copies as pixels, said in the summary.
        val payload = svg.payload()!!
        val damaged = payload.copy(layers = payload.layers.map { pl -> pl.array?.let { pl.copy(array = it.copy(source = "QldBUgEJ")) } ?: pl })
        val fresh = Smoke.document(w, h, layers = 1, whiteBottom = true)
        val c2 = ExchangeFixtures.controller(fresh)
        val target = ImportTarget(w, h, fresh.dpi, fresh.colorMode, ImportLayers.room(c2) + 1, c2.maxLayers)
        val prepared = PayloadImport.prepare(damaged, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target)
        val o2 = PayloadImport.apply(c2, prepared, fresh.layers.toList())
        assertEquals(2, o2.dropped["damaged arrays (kept as pixels)"])
        val boxes = c2.doc.layers.first { it.name == "Boxes" }
        assertNull(boxes.array)
        assertPixels("Boxes", pixels(doc.layers.first { it.name == "Boxes" }.bitmap), pixels(boxes.bitmap))
        assertEquals(3, o2.layers)
    }
}
