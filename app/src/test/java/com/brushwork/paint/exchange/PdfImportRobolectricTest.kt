package com.brushwork.paint.exchange

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.exchange.ExchangeFixtures.app
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.pdf.PageRasterizer
import com.brushwork.paint.exchange.pdf.PageRasterizerFactory
import com.brushwork.paint.exchange.pdf.PdfImport
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.exchange.ExchangeDialog
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.ui.exchange.PdfPagePicker
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/** A PDF of solid pages (Robolectric has no PdfRenderer: V9). */
internal class FakeRasterizer(private val sizes: List<Pair<Float, Float>>, private val colors: List<Int>) : PageRasterizer {
    var closed = false
    var renders = 0
    override val pageCount: Int get() = sizes.size
    override fun pageSize(index: Int): Pair<Float, Float> = sizes[index]
    override fun render(index: Int, width: Int, height: Int): Bitmap {
        renders++
        val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
        // The page draws a block in its middle; its margins stay transparent.
        Canvas(b).drawRect(width * 0.25f, height * 0.25f, width * 0.75f, height * 0.75f, Paint().apply { color = colors[index] })
        return b
    }
    override fun close() { closed = true }
}

/** v1.5 §4.11e (A8): PDF pages as raster layers, the page picker, the editor flow. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi")
class PdfImportRobolectricTest {
    private val a4 = 595.2756f to 841.8898f

    private fun fake(n: Int = 3) = FakeRasterizer(List(n) { a4 }, listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt()).take(n))

    @Test
    fun pagesFitTheCanvasAtMostAt300Dpi() {
        // An A4 page on a 300 x 200 canvas: limited by the height.
        assertEquals(141 to 200, PdfImport.renderSize(a4.first, a4.second, 300, 200))
        // A 1 inch page on a big canvas: 300 dpi at most.
        assertEquals(300 to 300, PdfImport.renderSize(72f, 72f, 2000, 2000))
        assertEquals(2480 to 3508, PdfImport.canvasSize(a4.first, a4.second, 4096))
        assertEquals(2048 to 2897, PdfImport.canvasSize(a4.first, a4.second, 2897))
    }

    @Test
    fun threePagesAreThreeLayersInOneStep() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val before = c.undoManager.undoCount
        val r = fake()
        val t = ImportTarget(300, 200, 350f, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)
        val layers = PdfImport.prepare(r, listOf(0, 1, 2), t, transparent = false)
        val o = PdfImport.apply(c, layers, 3)
        assertEquals(3, o.pages)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(listOf("Layer 1", "Page 1", "Page 2", "Page 3"), c.doc.layers.map { it.name })
        val p2 = c.doc.layers[2].bitmap
        // Centred 141 x 200 page: white paper, the green block in its middle, nothing beside it.
        assertEquals(0xFF00FF00.toInt(), p2.getPixel(150, 100))
        assertEquals(0xFFFFFFFF.toInt(), p2.getPixel(150, 10))
        assertEquals(0, p2.getPixel(10, 100))
        c.undo()
        assertEquals(1, c.doc.layers.size)
    }

    @Test
    fun transparentPagesKeepTheirEmptyPaper() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val t = ImportTarget(300, 200, 350f, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)
        val layer = PdfImport.prepare(fake(1), listOf(0), t, transparent = true).single()
        assertEquals(0, layer.bitmap.getPixel(150, 10))
        assertEquals(0xFFFF0000.toInt(), layer.bitmap.getPixel(150, 100))
    }

    @Test
    fun theEditorAsksForPagesAndImportsTheChosenOnes() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val r = fake()
        val state = ExchangeUiState(c)
        state.context = app
        state.rasterizers = PageRasterizerFactory { _, _ -> r }
        val file = File(app.cacheDir, "three.pdf").apply { writeBytes("%PDF-1.4\n% not really a pdf\n".toByteArray()) }
        val before = c.undoManager.undoCount
        state.importUri(Uri.fromFile(file))
        assertTrue("page picker", Smoke.pumpUntil { state.dialog is ExchangeDialog.Pages })
        state.answerPages(listOf(2, 0), transparent = false)
        assertTrue(Smoke.pumpUntil { c.doc.layers.size == 3 && c.busyMessage == null })
        assertEquals(listOf("Layer 1", "Page 1", "Page 3"), c.doc.layers.map { it.name })
        assertEquals(before + 1, c.undoManager.undoCount)
        assertTrue(r.closed)
        assertTrue(c.message!!.startsWith("Imported 2 pages"))
    }

    @Test
    fun aSinglePageIsPlacedWithTheTransformTool() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val state = ExchangeUiState(c)
        state.context = app
        val r = fake(1)
        state.rasterizers = PageRasterizerFactory { _, _ -> r }
        val file = File(app.cacheDir, "one.pdf").apply { writeBytes("%PDF-1.4\n".toByteArray()) }
        state.importUri(Uri.fromFile(file))
        assertTrue(Smoke.pumpUntil { c.doc.layers.size == 2 && c.busyMessage == null })
        assertEquals("Page 1", c.doc.layers[1].name)
        assertEquals(com.brushwork.paint.tools.ToolId.TRANSFORM, c.activeToolId)
        assertTrue(c.currentTool.hasPendingWork)
    }
}
