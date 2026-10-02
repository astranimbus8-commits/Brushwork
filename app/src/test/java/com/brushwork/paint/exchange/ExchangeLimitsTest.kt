package com.brushwork.paint.exchange

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.Pdf
import com.brushwork.paint.exchange.export.PdfDict
import com.brushwork.paint.exchange.export.PdfFile
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PdfBytes
import com.brushwork.paint.exchange.pdf.PdfObj
import com.brushwork.paint.exchange.svg.DataUri
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.exchange.svg.SvgToVector
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException
import java.util.Base64
import java.util.concurrent.CancellationException

/**
 * v1.5 §4.11b (A8 review): hostile or merely large files stay bounded — `<use>` fan-out, the PDF
 * reader on other apps' files — and percent-encoded data URIs decode (JVM).
 */
class ExchangeLimitsTest {

    /** 7 levels of groups holding [fan] uses of the next level, the last one empty: fan^7 copies, no points. */
    private fun useBomb(fan: Int, levels: Int = 7): ByteArray {
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="100" height="100"><defs>""")
        sb.append("<g id=\"g$levels\"/>")
        for (l in levels - 1 downTo 0) {
            sb.append("<g id=\"g$l\">")
            repeat(fan) { sb.append("<use xlink:href=\"#g${l + 1}\"/>") }
            sb.append("</g>")
        }
        sb.append("</defs><use xlink:href=\"#g0\"/></svg>")
        return sb.toString().toByteArray()
    }

    @Test
    fun referencesThatMultiplyAFileAreCutAtTheVisitLimit() {
        val doc = SvgParser.parse(useBomb(fan = 50))
        assertFalse("a few hundred elements: the parser has no reason to stop", doc.truncated)
        val t0 = System.nanoTime()
        val content = SvgToVector.convert(doc, 96f)
        val ms = (System.nanoTime() - t0) / 1_000_000
        // 50^7 copies would never end; the walk stops at the limit (and says so).
        assertTrue(content.truncated)
        assertTrue("took $ms ms", ms < 20_000)
        // A smaller limit stops sooner; a file under it is complete.
        assertTrue(SvgToVector.convert(doc, 96f, maxVisits = 1000).truncated)
        val small = SvgParser.parse(useBomb(fan = 2, levels = 3))
        assertFalse(SvgToVector.convert(small, 96f).truncated)
    }

    @Test
    fun aLongConversionStopsWhenAsked() {
        val doc = SvgParser.parse(useBomb(fan = 50))
        var polls = 0
        try {
            SvgToVector.convert(doc, 96f) { ++polls > 3 }
            fail("not stopped")
        } catch (e: CancellationException) {
            assertEquals(4, polls)
        }
    }

    @Test
    fun percentEncodedBase64DataUrisDecode() {
        // 0xFB 0xFF encode as "+/8=": the characters URL encoding escapes.
        val bytes = ByteArray(6000) { if (it % 2 == 0) 0xFB.toByte() else 0xFF.toByte() }
        val b64 = Base64.getEncoder().encodeToString(bytes)
        assertTrue(b64.contains('+') && b64.contains('/'))
        val escaped = b64.replace("+", "%2B").replace("/", "%2f").replace("=", "%3D")
        fun urlEscaped(s: String) = s.replace("+", "%2B").replace("/", "%2F").replace("\n", "%0A")
        // Short values are strings ...
        val raw = Base64.getEncoder().encodeToString(bytes.copyOf(30))
        assertArrayEquals(bytes.copyOf(30), DataUri.decode("data:image/png;base64,${urlEscaped(raw)}", null)!!.second)
        // ... with escaped line breaks ignored ...
        val wrapped = urlEscaped(raw.substring(0, 8) + "\n" + raw.substring(8))
        assertArrayEquals(bytes.copyOf(30), DataUri.decode("data:image/png;base64,$wrapped", null)!!.second)
        // ... long ones stay byte slices of the file (decoded the same way).
        val svg = """<svg xmlns="http://www.w3.org/2000/svg"><image id="i" width="1" height="1" href="data:image/png;base64,$escaped"/></svg>"""
        val doc = SvgParser.parse(svg.toByteArray())
        assertNotNull(doc.ids["i"]!!.hrefSlice)
        assertArrayEquals(bytes, doc.imageData("i"))
    }

    // ------------------------------------------------------------------ PDF reader

    /** Counts the bytes a reader asks for. */
    private class Counting(bytes: ByteArray) : PdfBytes {
        private val inner = ArrayPdfBytes(bytes)
        var read = 0L
        override val size: Long get() = inner.size
        override fun read(pos: Long, buf: ByteArray, off: Int, len: Int): Int = inner.read(pos, buf, off, len).also { read += it }
    }

    @Test
    fun anotherAppsPdfWithACrossReferenceStreamIsNotScannedThrough() {
        // A PDF 1.5 file: big content, then a cross-reference stream (no classic table).
        val sb = StringBuilder("%PDF-1.5\n")
        sb.append("1 0 obj\n<</Type /Catalog /Pages 2 0 R>>\nendobj\n")
        sb.append("3 0 obj\n<</Length 4000000>>\nstream\n").append("x".repeat(4_000_000)).append("\nendstream\nendobj\n")
        val xrefAt = sb.length
        sb.append("9 0 obj\n<</Type /XRef /Size 10 /Root 1 0 R /W [1 4 1] /Length 0>>\nstream\n\nendstream\nendobj\n")
        sb.append("startxref\n").append(xrefAt).append("\n%%EOF\n")
        val src = Counting(sb.toString().toByteArray(Charsets.ISO_8859_1))
        try {
            OwnPdfReader(src)
            fail("read as a Brushwork file")
        } catch (e: IOException) {
            // Expected: no payload possible.
        }
        assertTrue("read ${src.read} of ${src.size} bytes", src.read < 200_000)
    }

    /** A classic file of [count] objects of about 2 KB each, with a small Brushwork payload. */
    private fun bigClassicPdf(count: Int): ByteArray {
        val out = ByteArrayOutputStream()
        val f = PdfFile(out)
        val catalog = f.reserve()
        val filler = "y".repeat(2000)
        repeat(count) { i -> f.obj("<</N $i /S ($filler)>>") }
        val json = Payload.toJson(BrushworkPayload(width = 10, height = 20))
        val payload = f.stream(PdfDict().apply { this["Filter"] = "/FlateDecode" }, Pdf.flate(json))
        f.obj(catalog, "<</Type /Catalog /BrushworkPayload ${Pdf.ref(payload)}>>")
        f.finish(catalog)
        return out.toByteArray()
    }

    @Test
    fun aLargeClassicFileIsOpenedFromASampleOfItsTable() {
        val bytes = bigClassicPdf(5000)
        val src = Counting(bytes)
        val r = OwnPdfReader(src)
        assertTrue(r.hasPayload())
        assertEquals(20, r.payload()!!.height)
        assertTrue("read ${src.read} of ${src.size} bytes", src.read < bytes.size / 4)
    }

    @Test
    fun aWrongTableEntryOutsideTheSampleIsFoundByRescanning() {
        val bytes = bigClassicPdf(200)
        val text = String(bytes, Charsets.ISO_8859_1)
        val xref = text.lastIndexOf("xref\n0 ")
        // The entry of object 3 (line 4 after the header lines) points at object 4 instead.
        val lines = text.substring(xref).split("\r\n", "\n").toMutableList()
        val firstEntry = lines.indexOfFirst { it.endsWith(" 65535 f") } + 1
        val obj4 = lines[firstEntry + 3]
        lines[firstEntry + 2] = obj4
        val broken = (text.substring(0, xref) + rebuild(text.substring(xref), firstEntry + 2, obj4)).toByteArray(Charsets.ISO_8859_1)
        val r = OwnPdfReader(ArrayPdfBytes(broken))
        // Object 1 is the catalog; the fillers 2, 3... carry N = 0, 1...
        val o = r.obj(3) as PdfObj.Dict
        assertEquals(1.0, (o["N"] as PdfObj.Num).value, 0.0)
        assertTrue(r.hasPayload())
    }

    /** [tail] (the xref section) with line [index] replaced by [line] (line ends kept). */
    private fun rebuild(tail: String, index: Int, line: String): String {
        val sb = StringBuilder()
        var i = 0
        var n = 0
        while (i < tail.length) {
            val end = tail.indexOf('\n', i).let { if (it < 0) tail.length else it + 1 }
            val raw = tail.substring(i, end)
            if (n == index) {
                val eol = raw.substring(raw.trimEnd('\r', '\n').length)
                sb.append(line).append(eol)
            } else {
                sb.append(raw)
            }
            i = end
            n++
        }
        return sb.toString()
    }

    @Test
    fun aDamagedReferenceIsAnUnreadableObjectNotACrash() {
        // An object number beyond Int: the catalog can't be read, nothing throws.
        val src = "%PDF-1.4\n1 0 obj\n<</Type /Catalog /BrushworkPayload 99999999999 0 R>>\nendobj\nxref\n0 2\n0000000000 65535 f\r\n0000000009 00000 n\r\ntrailer\n<</Size 2 /Root 1 0 R>>\nstartxref\n52\n%%EOF\n"
        val fixed = src.replace("startxref\n52", "startxref\n" + src.indexOf("xref\n0 2"))
        val r = OwnPdfReader(ArrayPdfBytes(fixed.toByteArray(Charsets.ISO_8859_1)))
        assertEquals(null, r.obj(1))
        assertFalse(r.hasPayload())
        assertEquals(null, r.payload())
    }
}
