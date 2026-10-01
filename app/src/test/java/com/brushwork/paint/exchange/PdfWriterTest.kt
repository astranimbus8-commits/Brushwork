package com.brushwork.paint.exchange

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.exchange.export.Pdf
import com.brushwork.paint.exchange.export.PdfPage
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SceneImage
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SceneLayer
import com.brushwork.paint.exchange.export.SceneMask
import com.brushwork.paint.exchange.export.SceneStroke
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PdfObj
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream

/**
 * v1.5 §4.10e (A8): the pure-Kotlin PDF writer, checked with the own mini reader — every xref
 * offset lands on its object, every stream inflates, every soft mask, optional content group and
 * payload reference resolves.
 */
class PdfWriterTest {
    private val w = 200
    private val h = 150

    private fun picture(pw: Int, ph: Int, alpha: Boolean): ArgbImage = ArgbImage(pw, ph, IntArray(pw * ph) { i ->
        val a = if (alpha) (i * 7) and 0xFF else 0xFF
        (a shl 24) or ((i * 3 and 0xFF) shl 16) or ((i * 5 and 0xFF) shl 8) or (i and 0xFF)
    })

    private val photo = picture(40, 30, alpha = true)
    private val maskPixels = ArgbImage(50, 20, IntArray(50 * 20) { i -> val g = (i * 2) and 0xFF; (0xFF shl 24) or (g shl 16) or (g shl 8) or g })
    private val hiddenPixels = picture(10, 10, alpha = false)

    private fun rect(l: Float, t: Float, r: Float, b: Float) = VectorPath.polygon(listOf(Vec2(l, t), Vec2(r, t), Vec2(r, b), Vec2(l, b)))

    private fun scene(): ExportScene {
        val photoImg = SceneImage("img-1", 10, 20, photo.width, photo.height, gray = false) { photo }
        val maskImg = SceneImage("mask-2", 30, 40, maskPixels.width, maskPixels.height, gray = true) { maskPixels }
        val hiddenImg = SceneImage("img-3", 100, 100, 10, 10, gray = false) { hiddenPixels }
        val layer1 = SceneLayer(
            "layer-1", "Photo (1)", 1f, LayerBlendMode.NORMAL, false, null,
            listOf(
                SceneItem.Image(photoImg),
                SceneItem.Shape(rect(60f, 10f, 120f, 70f), fill = VPaint.Solid(0x80FF0000.toInt()), stroke = SceneStroke(0xFF0000FF.toInt(), 3f)),
                SceneItem.Shape(rect(130f, 10f, 190f, 70f), fill = VPaint.Solid(0xFF00FF00.toInt()), stroke = SceneStroke(0xFF000000.toInt(), 2f), opacity = 0.5f),
            ),
        )
        val layer2 = SceneLayer(
            "layer-2", "Grädients", 0.75f, LayerBlendMode.MULTIPLY, false, SceneMask("mask-2", 255, maskImg),
            listOf(
                SceneItem.Shape(
                    rect(0f, 80f, 100f, 140f),
                    fill = VPaint.Linear(0f, 0f, 100f, 0f, listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(0.5f, 0x8000FF00.toInt()), VStop(1f, 0x000000FF))),
                ),
                SceneItem.Shape(
                    rect(100f, 80f, 200f, 140f), evenOdd = true,
                    fill = VPaint.Radial(0f, 0f, 1f, listOf(VStop(0f, 0xFFFFFFFF.toInt()), VStop(1f, 0xFF000000.toInt())), listOf(50f, 0f, 0f, 30f, 150f, 110f)),
                ),
                // Off the canvas by a lot: clamped, not broken.
                SceneItem.Shape(rect(-1e9f, 0f, 1e9f, 5f), fill = VPaint.Solid(0xFF000000.toInt())),
            ),
        )
        val layer3 = SceneLayer("layer-3", "Glow", 1f, LayerBlendMode.ADD, true, null, listOf(SceneItem.Image(hiddenImg)))
        val payload = BrushworkPayload(
            width = w, height = h, dpi = 300f,
            layers = listOf(
                PayloadLayer(1, props("Photo (1)"), PayloadKind.RASTER, imageRef = "img-1", imageRect = PayloadRect(10, 20, 40, 30)),
                PayloadLayer(2, props("Grädients"), PayloadKind.VECTOR, hasMask = true, maskRef = "mask-2", maskRect = PayloadRect(30, 40, 50, 20),
                    vector = VectorContent(objects = listOf(VPath(1, subpaths = listOf(VSubpath(listOf(VAnchor(1f, 2f), VAnchor(3f, 4f)))))), nextId = 2)),
                PayloadLayer(3, props("Glow"), PayloadKind.RASTER, imageRef = "img-3", imageRect = PayloadRect(100, 100, 10, 10)),
                PayloadLayer(4, props("Below"), PayloadKind.RASTER, imageRef = "pimg-4", imageRect = PayloadRect(0, 0, 5, 5)),
            ),
        )
        val payloadOnly = SceneImage("pimg-4", 0, 0, 5, 5, gray = false) { picture(5, 5, alpha = true) }
        return ExportScene(w, h, 300f, "Test (pdf)", 0xFFFFFFFF.toInt(), listOf(layer1, layer2, layer3), payload, listOf(payloadOnly))
    }

    private fun props(name: String) = LayerProps(name, 1f, LayerBlendMode.NORMAL, true, false, false, false, true)

    private fun write(page: PdfPage = PdfPage.CANVAS): ByteArray {
        val out = ByteArrayOutputStream()
        runBlocking { PdfWriter(scene(), page).write(out) }
        return out.toByteArray()
    }

    @Test
    fun everyXrefOffsetLandsOnItsObjectAndEveryStreamInflates() {
        val bytes = write()
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue(text.startsWith("%PDF-1.7"))
        assertTrue(text.trimEnd().endsWith("%%EOF"))
        val startxref = Regex("startxref\\s+(\\d+)").findAll(text).last().groupValues[1].toInt()
        assertTrue(text.startsWith("xref", startxref))
        val r = OwnPdfReader(ArrayPdfBytes(bytes))
        assertTrue(r.offsets.size > 20)
        for ((num, off) in r.offsets) {
            assertTrue("object $num at $off", text.startsWith("$num 0 obj", off.toInt()))
            val o = r.obj(num)
            assertNotNull("object $num parses", o)
            if (o is PdfObj.Stream) r.streamData(o) // throws when broken
        }
        // Every reference anywhere resolves.
        for (num in r.offsets.keys) visitRefs(r.obj(num)) { ref -> assertNotNull("ref ${ref.num}", r.obj(ref.num)) }
    }

    private fun visitRefs(o: PdfObj?, f: (PdfObj.Ref) -> Unit) {
        when (o) {
            is PdfObj.Ref -> f(o)
            is PdfObj.Arr -> o.items.forEach { visitRefs(it, f) }
            is PdfObj.Dict -> o.map.values.forEach { visitRefs(it, f) }
            is PdfObj.Stream -> o.dict.map.values.forEach { visitRefs(it, f) }
            else -> {}
        }
    }

    @Test
    fun layersAreOptionalContentGroupsWithBlendOpacityAndLuminosityMasks() {
        val r = OwnPdfReader(ArrayPdfBytes(write()))
        val cat = r.catalog()!!
        val ocp = r.resolve(cat["OCProperties"]) as PdfObj.Dict
        val ocgs = (ocp["OCGs"] as PdfObj.Arr).items.map { (it as PdfObj.Ref).num }
        assertEquals(3, ocgs.size)
        val names = ocgs.map { ((r.obj(it) as PdfObj.Dict)["Name"] as PdfObj.Str).text }
        assertEquals(listOf("Photo (1)", "Grädients", "Glow"), names)
        val d = ocp["D"] as PdfObj.Dict
        val off = (d["OFF"] as PdfObj.Arr).items.map { (it as PdfObj.Ref).num }
        assertEquals(listOf(ocgs[2]), off)
        // Top layer first in the panel order.
        assertEquals(ocgs.reversed(), (d["Order"] as PdfObj.Arr).items.map { (it as PdfObj.Ref).num })

        val pageRef = ((r.resolve(cat["Pages"]) as PdfObj.Dict)["Kids"] as PdfObj.Arr).items[0] as PdfObj.Ref
        val page = r.obj(pageRef.num) as PdfObj.Dict
        val media = (page["MediaBox"] as PdfObj.Arr).items.map { (it as PdfObj.Num).value }
        assertEquals(w * 72.0 / 300.0, media[2], 1e-3)
        assertEquals(h * 72.0 / 300.0, media[3], 1e-3)
        val res = page["Resources"] as PdfObj.Dict
        val states = (res["ExtGState"] as PdfObj.Dict).map.values.map { r.resolve(it) as PdfObj.Dict }
        val blends = states.mapNotNull { (it["BM"] as? PdfObj.Name)?.name }
        assertEquals(listOf("Normal", "Multiply", "Screen"), blends) // Add (Glow) -> Screen
        assertEquals(0.75, (states[1]["ca"] as PdfObj.Num).value, 1e-6)
        val smask = states[1]["SMask"] as PdfObj.Dict
        assertEquals("Luminosity", (smask["S"] as PdfObj.Name).name)
        val maskForm = r.resolve(smask["G"]) as PdfObj.Stream
        val group = maskForm.dict["Group"] as PdfObj.Dict
        assertEquals("DeviceGray", (group["CS"] as PdfObj.Name).name)
        // The mask background is painted over the whole box before the picture.
        val maskContent = String(r.streamData(maskForm), Charsets.ISO_8859_1)
        assertTrue(maskContent, maskContent.startsWith("1 g 0 0 $w $h re f"))
        val content = String(r.streamData(r.resolve(page["Contents"]) as PdfObj.Stream), Charsets.ISO_8859_1)
        assertTrue(content, content.contains("BDC") && content.contains("EMC"))
        // Doc px (y down) -> pt: scale 72/300, flipped.
        assertTrue(content, content.startsWith("q 0.24 0 0 -0.24 0 36 cm"))
        // Huge coordinates are clamped to the implementation limit.
        assertTrue(r.offsets.keys.any { n -> (r.obj(n) as? PdfObj.Stream)?.let { s -> String(r.streamData(s), Charsets.ISO_8859_1).contains("-32000 0 m") } == true })
    }

    @Test
    fun picturesRoundTripWithTheirSoftMaskAndThePayloadResolves() {
        val r = OwnPdfReader(ArrayPdfBytes(write()))
        val p = r.payload()!!
        assertEquals(scene().payload, p)
        assertArrayEquals(photo.pixels, r.payloadImage("img-1")!!.pixels)
        assertArrayEquals(hiddenPixels.pixels, r.payloadImage("img-3")!!.pixels)
        assertArrayEquals(picture(5, 5, alpha = true).pixels, r.payloadImage("pimg-4")!!.pixels)
        val mask = r.payloadImage("mask-2")!!
        assertArrayEquals(maskPixels.pixels, mask.pixels)
        // The payload is also an embedded file named brushwork.json.
        val names = (r.resolve(r.catalog()!!["Names"]) as PdfObj.Dict)["EmbeddedFiles"] as PdfObj.Dict
        val list = (names["Names"] as PdfObj.Arr).items
        assertEquals(Payload.PDF_FILE_NAME, (list[0] as PdfObj.Str).text)
        val spec = r.resolve(list[1]) as PdfObj.Dict
        val ef = r.resolve((spec["EF"] as PdfObj.Dict)["F"]) as PdfObj.Stream
        assertEquals(p, Payload.fromJson(r.streamData(ef)))
    }

    @Test
    fun gradientsAreShadingsAndStopOpacityIsASoftMask() {
        val r = OwnPdfReader(ArrayPdfBytes(write()))
        val shadings = r.offsets.keys.mapNotNull { r.obj(it) as? PdfObj.Dict }.filter { it["ShadingType"] != null }
        val types = shadings.map { (it["ShadingType"] as PdfObj.Num).int }.sorted()
        // Linear: RGB + its gray alpha twin; radial: RGB only (opaque stops).
        assertEquals(listOf(2, 2, 3), types)
        val stitched = shadings.first { (it["ShadingType"] as PdfObj.Num).int == 2 && (it["ColorSpace"] as PdfObj.Name).name == "DeviceRGB" }
        val fn = stitched["Function"] as PdfObj.Dict
        assertEquals(3, (fn["FunctionType"] as PdfObj.Num).int)
        assertEquals(listOf(0.5), (fn["Bounds"] as PdfObj.Arr).items.map { (it as PdfObj.Num).value })
    }

    @Test
    fun a4FitsAndCentresTheArtworkInTheMatchingOrientation() {
        val r = OwnPdfReader(ArrayPdfBytes(write(PdfPage.A4)))
        val cat = r.catalog()!!
        val pageRef = ((r.resolve(cat["Pages"]) as PdfObj.Dict)["Kids"] as PdfObj.Arr).items[0] as PdfObj.Ref
        val media = ((r.obj(pageRef.num) as PdfObj.Dict)["MediaBox"] as PdfObj.Arr).items.map { (it as PdfObj.Num).value }
        // 200 x 150 is landscape: A4 landscape.
        assertEquals(841.8898, media[2], 1e-3)
        assertEquals(595.2756, media[3], 1e-3)
    }

    @Test
    fun numbersNamesAndStringsAreFormattedSafely() {
        assertEquals("0", Pdf.num(0f))
        assertEquals("1.5", Pdf.num(1.5f))
        assertEquals("-0.25", Pdf.num(-0.25f))
        assertEquals("0.0001", Pdf.num(0.0001))
        assertEquals("0", Pdf.num(Float.NaN))
        assertEquals("32000", Pdf.num(1e30f))
        assertEquals("/A#20B#2F", Pdf.name("A B/"))
        assertEquals("(a\\(b\\)c\\\\)", Pdf.text("a(b)c\\"))
        assertEquals("<FEFF00E9>", Pdf.text("é"))
    }
}
