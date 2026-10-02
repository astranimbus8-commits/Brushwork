package com.brushwork.paint.exchange

import android.net.Uri
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.export.Payload
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.InputStream
import java.util.zip.GZIPOutputStream

/** v1.5 §4.11a (A8): the content decides what a picked file is; SVG size limits. */
@RunWith(RobolectricTestRunner::class)
class ImportSourceRobolectricTest {

    @Test
    fun theContentDecidesTheKind() {
        assertEquals(ImportKind.PDF, ImportSource.sniff("%PDF-1.7\n".toByteArray()))
        assertEquals(ImportKind.PDF, ImportSource.sniff("\u0000\u0001junk%PDF-1.4".toByteArray()))
        assertEquals(ImportKind.SVG, ImportSource.sniff("<?xml version=\"1.0\"?>\n<svg/>".toByteArray()))
        assertEquals(ImportKind.SVG, ImportSource.sniff("﻿  <svg xmlns=\"http://www.w3.org/2000/svg\"/>".toByteArray()))
        assertEquals(ImportKind.SVG, ImportSource.sniff("<!-- made by hand -->\n<svg/>".toByteArray()))
        assertEquals(ImportKind.SVG, ImportSource.sniff(byteArrayOf(0x1F, 0x8B.toByte(), 8, 0)))
        assertNull(ImportSource.sniff("\u0089PNG\r\n".toByteArray()))
        assertNull(ImportSource.sniff("hello".toByteArray()))
    }

    @Test
    fun svgFilesOverTwentyMegabytesAreRefusedUnlessTheyCarryBrushworkData() {
        fun zeros(n: Long, prefix: String = ""): InputStream = object : InputStream() {
            var left = n
            val p = prefix.toByteArray()
            var i = 0
            override fun read(): Int {
                if (i < p.size) return p[i++].toInt()
                if (left-- <= 0) return -1
                return ' '.code
            }
        }
        try {
            ImportSource.readSvg(zeros(21L shl 20, "<svg>"))
            fail("a 21 MB SVG was read")
        } catch (e: ImportException) {
            assertTrue(e.message!!.contains("20 MB"))
        }
        val withData = ImportSource.readSvg(zeros(21L shl 20, "<svg xmlns:bw=\"${Payload.SVG_NAMESPACE}\">"))
        assertTrue(withData.size > (21 shl 20))
    }

    @Test
    fun gzippedSvgAndPdfFilesOpenFromOneRead() {
        val svg = "<svg xmlns=\"http://www.w3.org/2000/svg\" width=\"10\" height=\"10\"><rect width=\"5\" height=\"5\"/></svg>".toByteArray()
        val gz = ByteArrayOutputStream().also { o -> GZIPOutputStream(o).use { it.write(svg) } }.toByteArray()
        val svgz = File(app.cacheDir, "drawing.svgz").apply { writeBytes(gz) }
        ImportSource.open(app, Uri.fromFile(svgz)).use { f ->
            assertEquals(ImportKind.SVG, f.kind)
            assertEquals("drawing", f.name)
            assertArrayEquals(svg, f.bytes)
        }
        val pdfBytes = ("%PDF-1.4\n" + "x".repeat(100_000)).toByteArray()
        val pdf = File(app.cacheDir, "doc.pdf").apply { writeBytes(pdfBytes) }
        val copy: File
        ImportSource.open(app, Uri.fromFile(pdf)).use { f ->
            assertEquals(ImportKind.PDF, f.kind)
            copy = f.file!!
            assertArrayEquals(pdfBytes, copy.readBytes())
        }
        assertTrue("the copy is deleted on close", !copy.exists())
        val png = File(app.cacheDir, "x.png").apply { writeBytes(byteArrayOf(0x89.toByte(), 'P'.code.toByte(), 'N'.code.toByte(), 'G'.code.toByte())) }
        try {
            ImportSource.open(app, Uri.fromFile(png))
            fail("a PNG was accepted")
        } catch (e: ImportException) {
            assertEquals("This file is neither an SVG nor a PDF", e.message)
        }
    }
}
