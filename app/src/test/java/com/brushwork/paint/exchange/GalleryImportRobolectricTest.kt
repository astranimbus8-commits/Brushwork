package com.brushwork.paint.exchange

import android.net.Uri
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.exchange.GalleryImports
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** v1.5 §4.11 (A8): "New from SVG or PDF" creates an artwork of the right size and hands the file over. */
@RunWith(RobolectricTestRunner::class)
class GalleryImportRobolectricTest {
    private val bigHeap = 4096L shl 20

    private fun svg(s: String) = SvgParser.parse(s.toByteArray())

    @Test
    fun svgArtworksTakeThePhysicalSizeAt350Dpi() {
        val a4 = NewArtwork.forSvg("A4", svg("""<svg xmlns="http://www.w3.org/2000/svg" width="210mm" height="297mm"/>"""), bigHeap)
        assertEquals(2894, a4.width)
        assertEquals(4093, a4.height)
        assertEquals(350f, a4.dpi)
        // An icon: scaled up so the long side is 1024 px.
        val icon = NewArtwork.forSvg("Icon", svg("""<svg xmlns="http://www.w3.org/2000/svg" width="24" height="12" viewBox="0 0 24 12"/>"""), bigHeap)
        assertEquals(1024 to 512, icon.width to icon.height)
        // A small heap: the long side as large as leaves room for the import layers.
        val poster = NewArtwork.forSvg("Poster", svg("""<svg xmlns="http://www.w3.org/2000/svg" width="40in" height="30in"/>"""), 256L shl 20)
        val cap = CanvasLimits.importMaxSide(14000, 10500, 256L shl 20)
        assertEquals(cap, poster.width)
        assertTrue(CanvasLimits.rawMaxLayers(poster.width, poster.height, 256L shl 20) >= CanvasLimits.IMPORT_MIN_LAYERS)
    }

    @Test
    fun pdfPagesAt300DpiAndBrushworkFilesExactly() {
        val page = NewArtwork.forPdfPage("Doc", 612f, 792f, bigHeap)
        assertEquals(2550 to 3300, page.width to page.height)
        assertEquals(300f, page.dpi)
        val p = BrushworkPayload(width = 1234, height = 567, dpi = 144f)
        val exact = NewArtwork.forPayload("Mine", p, bigHeap)!!
        assertEquals(NewArtworkSpec("Mine", 1234, 567, 144f), exact)
        assertNull(NewArtwork.forPayload("Huge", BrushworkPayload(width = 10_000, height = 10_000), 64L shl 20))
    }

    @Test
    fun theGalleryCreatesTheProjectAndHandsTheFileToTheEditor() {
        val repo = ProjectRepository(app)
        val uri = Uri.parse("content://test/drawing.svg")
        val spec = NewArtwork.forSvg("Drawing", svg("""<svg xmlns="http://www.w3.org/2000/svg" width="3in" height="2in"/>"""), bigHeap)
        val id = runBlocking { GalleryImports.create(repo, spec, PendingImport(uri)) }
        val doc = runBlocking { repo.load(id) }
        assertEquals(1050, doc.width)
        assertEquals(700, doc.height)
        assertEquals(350f, doc.dpi)
        assertEquals("Drawing", doc.name)
        assertEquals(listOf("Background", "Layer 1"), doc.layers.map { it.name })
        // The editor takes the Uri once, then the gallery's decisions about it.
        assertEquals(uri, PendingImports.take(id))
        assertNull(PendingImports.take(id))
        assertNull(PendingImports.details(id, Uri.parse("content://other")))
        val d = PendingImports.details(id, uri)!!
        assertTrue(d.newArtwork)
        assertNull(PendingImports.details(id, uri))
        // put() (the frozen hand-off) is a new artwork too.
        PendingImports.put("p2", uri)
        assertEquals(uri, PendingImports.take("p2"))
        assertTrue(PendingImports.details("p2", uri)!!.newArtwork)
    }
}
