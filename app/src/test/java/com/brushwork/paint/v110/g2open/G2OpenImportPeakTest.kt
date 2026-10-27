package com.brushwork.paint.v110.g2open

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.MemoryPressure
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.NewLayer
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorImport
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.pdf.PageRasterizer
import com.brushwork.paint.exchange.pdf.PdfImport
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v110.a8.A8Kit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertSame
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.util.concurrent.CancellationException

/**
 * v1.10 Gate 2 (lane open, O3, the A11 deviation): the PDF, SVG and Brushwork-payload imports park
 * each new layer as the next one is made (`ImportLayers.parkNewest`), so an import peaks at about
 * one layer's bitmap plus the tiles the budget keeps, not at every layer's bitmap before the
 * insert. At TILES on the T606 numbers, with a 512² artwork:
 *
 * - while an importer makes layer k, every pixel layer before it is already parked: the TILES
 *   ledger has grown by exactly their tiles (the PDF's rasterizer and the payload's pictures read
 *   it at each layer);
 * - after `prepare` every pixel layer but the last (the one that becomes active) is tiled, or
 *   blank when it is empty, and the ledger grew by exactly their resident bytes;
 * - the insert takes the parked stores as they are (the same objects; nothing is materialized
 *   again): ONE undo step, the right active layer FLAT, the pixels as rendered; undo and redo
 *   put the same stores back;
 * - an import stopped or failed lets every tile go: the ledger is back where it started (the
 *   PDF stopped after two pages and the payload's failed picture inside `prepare`, the SVG
 *   stopped after `prepare`, as `ExchangeUi` drops it on the main thread) and nothing is inserted.
 *
 * The residency is [Quiet] (it parks and activates nothing), so every tile the ledger shows is the
 * import's. The ledger is the process' own: each check is relative to a reading taken just before.
 */
@RunWith(RobolectricTestRunner::class)
class G2OpenImportPeakTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = Quiet())

    private var controller: EditorController? = null
    private var scope: CoroutineScope? = null

    @After
    fun tearDown() {
        controller?.dispose()
        scope?.cancel()
        idle()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun tiles(): Long = EngineConfig.budget.ledger.of(LedgerCategory.TILES)

    private fun open(): EditorController {
        val doc = Smoke.document(SIZE, SIZE, layers = 1)
        val s = Smoke.newScope().also { scope = it }
        return Smoke.controller(RuntimeEnvironment.getApplication(), doc, s).also { controller = it; idle() }
    }

    private fun target(c: EditorController) = ImportTarget(SIZE, SIZE, c.doc.dpi, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)

    private fun parked(p: PixelStore) = p is TiledPixels || p is BlankPixels

    /**
     * Every pixel layer of [layers] but the last parked (tiled, [blank] of them blank), the last
     * FLAT, and the ledger grown from [start] by exactly the parked ones' tiles. Returns those.
     */
    private fun assertParkedAsMade(what: String, layers: List<NewLayer>, start: Long, blank: Int = 0): List<NewLayer> {
        val pixel = layers.filter { it.folder == null }
        val before = pixel.dropLast(1)
        assertTrue("$what: layers to park", before.size >= 2)
        for (n in before) assertTrue("$what: ${n.name} is parked as the next layer is made: ${n.pixels}", parked(n.pixels))
        assertEquals("$what: blank layers", blank, before.count { it.pixels is BlankPixels })
        assertTrue("$what: the last layer (the active one) stays FLAT: ${pixel.last().pixels}", pixel.last().pixels is FlatPixels)
        val held = before.sumOf { it.pixels.residentBytes() }
        assertTrue("$what: the parked layers hold tiles", held > 0L)
        assertEquals("$what: the ledger holds exactly the parked layers' tiles", start + held, tiles())
        return before
    }

    /** [made] read at each layer: while layer k is made, the k pixel layers before it are already parked. */
    private fun assertParkedBefore(what: String, made: List<Long>, parkedFirst: List<NewLayer>, start: Long) {
        var held = 0L
        for ((k, seen) in made.withIndex()) {
            assertEquals("$what: the ledger while layer $k is made", start + held, seen)
            if (k < parkedFirst.size) held += parkedFirst[k].pixels.residentBytes()
        }
    }

    /** The import's layers in the document with the very stores the importer parked; [active] active and FLAT. */
    private fun assertInsertedAsParked(what: String, c: EditorController, parkedOnes: List<NewLayer>, active: String) {
        for (n in parkedOnes) assertSame("$what: ${n.name} keeps its parked store", n.pixels, byName(c, n.name).pixels)
        assertEquals("$what: the active layer", active, c.doc.activeLayer.name)
        assertTrue("$what: the active layer is FLAT", c.doc.activeLayer.pixels is FlatPixels)
    }

    private fun byName(c: EditorController, name: String): Layer = c.doc.layers.first { it.name == name }

    private fun pixel(l: Layer, x: Int, y: Int): Int = A8Kit.pixelsOf(l).first[y * l.width + x]

    @Test
    fun pdfPagesAreParkedAsTheyAreRendered() {
        val c = open()
        val start = tiles()
        val r = Pages(this)
        val layers = PdfImport.prepare(r, listOf(0, 1, 2, 3), target(c), transparent = false)
        val parkedOnes = assertParkedAsMade("pdf", layers, start)
        assertParkedBefore("pdf", r.seen, parkedOnes, start)
        assertEquals(4, r.seen.size)
        val steps = c.undoManager.undoCount
        PdfImport.apply(c, layers, 4)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(listOf("Layer 1", "Page 1", "Page 2", "Page 3", "Page 4"), c.doc.layers.map { it.name })
        assertInsertedAsParked("pdf", c, parkedOnes, "Page 4")
        fun assertPages() {
            for (k in 0 until 4) {
                val page = byName(c, "Page ${k + 1}")
                // The centred 362 x 512 page: its block in the middle, white paper above it, nothing beside it.
                assertEquals("page ${k + 1}", COLORS[k], pixel(page, 256, 256))
                assertEquals("page ${k + 1}", WHITE, pixel(page, 256, 10))
                assertEquals("page ${k + 1}", 0, pixel(page, 10, 256))
            }
        }
        assertPages()
        c.undo()
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
        c.redo()
        assertInsertedAsParked("pdf, redone", c, parkedOnes, "Page 4")
        assertPages()

        // Stopped after two pages: the parked first page's tiles and the second's bitmap go.
        val again = tiles()
        var polls = 0
        val stopped = Pages(this)
        assertThrows(CancellationException::class.java) {
            PdfImport.prepare(stopped, listOf(0, 1, 2, 3), target(c), transparent = false) { ++polls > 2 }
        }
        assertEquals("two pages were made", 2, stopped.seen.size)
        assertEquals("the second page was rendered with the first parked", again + parkedOnes[0].pixels.residentBytes(), stopped.seen[1])
        assertEquals("a stopped import lets every tile go", again, tiles())
        assertEquals(5, c.doc.layers.size)
    }

    @Test
    fun svgLayersAndTextsAreParkedAsTheyAreMade() {
        val c = open()
        val start = tiles()
        val svg = SvgParser.parse(SVG.toByteArray())
        val prepared = VectorImport.prepare(svg, target(c), newArtwork = true)
        assertEquals(listOf("Sky", "Ground", "Sun"), prepared.layers.map { it.name })
        assertEquals(2, prepared.texts.size)
        val parkedOnes = assertParkedAsMade("svg", prepared.layers, start)
        val steps = c.undoManager.undoCount
        VectorImport.apply(c, prepared)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(listOf("Layer 1", "Sky", "Ground", "Sun", "Text: Hi", "Text: Yo"), c.doc.layers.map { it.name })
        for (n in parkedOnes) assertSame("svg: ${n.name} keeps its parked store", n.pixels, byName(c, n.name).pixels)
        // The texts were made on the main thread in apply, each after the layer before was parked.
        assertTrue("svg: Sun parked before the texts", parked(byName(c, "Sun").pixels))
        assertTrue("svg: the first text parked before the second", parked(byName(c, "Text: Hi").pixels))
        assertTrue("svg: the last text stays FLAT", byName(c, "Text: Yo").pixels is FlatPixels)
        // A new artwork's import selects its first vector layer.
        assertEquals("Sky", c.doc.activeLayer.name)
        fun assertDrawn() {
            // The 100-unit viewport fills the 512 px canvas (x 5.12): the sky above, the ground below.
            assertEquals(SKY, pixel(byName(c, "Sky"), 256, 100))
            assertEquals(0, pixel(byName(c, "Sky"), 256, 400))
            assertEquals(GROUND, pixel(byName(c, "Ground"), 256, 400))
            assertEquals(SUN, pixel(byName(c, "Sun"), 410, 102))
            assertEquals(1, byName(c, "Sky").vector!!.objects.size)
        }
        assertDrawn()
        c.undo()
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
        c.redo()
        for (n in parkedOnes) assertSame("svg, redone: ${n.name} keeps its parked store", n.pixels, byName(c, n.name).pixels)
        assertDrawn()

        // Stopped after prepare: ExchangeUi lets the layers go on the main thread.
        val again = tiles()
        val dropped = VectorImport.prepare(svg, target(c), newArtwork = true)
        assertTrue("prepared again with parked layers", tiles() > again)
        dropped.layers.forEach { it.recycleBitmaps() }
        assertEquals("a stopped import lets every tile go", again, tiles())
        assertEquals(6, c.doc.layers.size)
    }

    @Test
    fun payloadLayersAreParkedAsTheyAreRestoredFoldersIncluded() {
        val c = open()
        val start = tiles()
        val seen = ArrayList<Long>()
        val images = PayloadImport.Images { key -> seen += tiles(); ArgbImage(100, 100, IntArray(100 * 100) { PAYLOAD_COLORS.getValue(key) }) }
        val prepared = PayloadImport.prepare(PAYLOAD, images, target(c))
        assertEquals(listOf("Red", "Empty", "Green", "Blue", "Folder", "Top"), prepared.layers.map { it.name })
        val parkedOnes = assertParkedAsMade("payload", prepared.layers, start, blank = 1)
        // The pictures are read as their layers are made (the empty layer reads none, the folder holds no pixels).
        assertEquals(4, seen.size)
        for ((k, n) in listOf("Red", "Green", "Blue", "Top").withIndex()) {
            val before = parkedOnes.takeWhile { it.name != n }
            assertEquals("payload: the ledger while $n is made", start + before.sumOf { it.pixels.residentBytes() }, seen[k])
        }
        val steps = c.undoManager.undoCount
        PayloadImport.apply(c, prepared)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(listOf("Layer 1", "Red", "Empty", "Green", "Blue", "Folder", "Top"), c.doc.layers.map { it.name })
        assertInsertedAsParked("payload", c, parkedOnes, "Top")
        fun assertTree() {
            val folder = byName(c, "Folder")
            assertTrue(folder.isFolder)
            assertEquals(folder.id, byName(c, "Green").parentId)
            assertEquals(folder.id, byName(c, "Blue").parentId)
            assertEquals(Layer.ROOT_ID, byName(c, "Top").parentId)
            assertEquals(0xFFFF0000.toInt(), pixel(byName(c, "Red"), 40, 40))
            assertEquals(0, pixel(byName(c, "Red"), 200, 200))
            assertEquals(0xFF00FF00.toInt(), pixel(byName(c, "Green"), 140, 140))
            assertEquals(0xFF0000FF.toInt(), pixel(byName(c, "Blue"), 300, 300))
            assertEquals(0xFF000000.toInt(), pixel(byName(c, "Top"), 420, 420))
        }
        assertTree()
        c.undo()
        assertEquals(listOf("Layer 1"), c.doc.layers.map { it.name })
        c.redo()
        assertInsertedAsParked("payload, redone", c, parkedOnes, "Top")
        assertTree()

        // A picture that fails to load inside prepare: the layers made so far let their tiles go.
        val again = tiles()
        val failing = PayloadImport.Images { key -> if (key == "top") throw IllegalStateException("unreadable") else images.load(key) }
        assertThrows(IllegalStateException::class.java) { PayloadImport.prepare(PAYLOAD, failing, target(c)) }
        assertEquals("a failed import lets every tile go", again, tiles())
        assertEquals(7, c.doc.layers.size)
    }

    /** A PDF of [COLORS] pages (Robolectric has no PdfRenderer); each render reads the TILES ledger first. */
    private class Pages(private val test: G2OpenImportPeakTest) : PageRasterizer {
        val seen = ArrayList<Long>()
        override val pageCount: Int get() = COLORS.size
        override fun pageSize(index: Int): Pair<Float, Float> = A4
        override fun render(index: Int, width: Int, height: Int): Bitmap {
            seen += test.tiles()
            val b = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
            Canvas(b).drawRect(width * 0.25f, height * 0.25f, width * 0.75f, height * 0.75f, Paint().apply { color = COLORS[index] })
            return b
        }
        override fun close() = Unit
    }

    /** Residency hooks that park and activate nothing: every tile in the ledger is the import's. */
    private class Quiet : ResidencyHooks {
        override fun activate(layer: Layer) = Unit
        override fun deactivated(layer: Layer) = Unit
        override fun pressure(p: MemoryPressure) = Unit
        override fun idleTick() = Unit
    }

    private companion object {
        const val SIZE = 512
        const val WHITE = 0xFFFFFFFF.toInt()
        const val SKY = 0xFF88CCFF.toInt()
        const val GROUND = 0xFF448800.toInt()
        const val SUN = 0xFFFFCC00.toInt()
        val A4 = 595.2756f to 841.8898f
        val COLORS = listOf(0xFFFF0000.toInt(), 0xFF00FF00.toInt(), 0xFF0000FF.toInt(), 0xFF000000.toInt())

        val SVG = """<svg xmlns="http://www.w3.org/2000/svg" xmlns:inkscape="http://www.inkscape.org/namespaces/inkscape" width="100mm" height="100mm" viewBox="0 0 100 100">
            <g inkscape:label="Sky"><rect width="100" height="40" fill="#88ccff"/></g>
            <g inkscape:label="Ground"><rect y="60" width="100" height="40" fill="#448800"/></g>
            <g inkscape:label="Sun"><circle cx="80" cy="20" r="10" fill="#ffcc00"/></g>
            <text x="10" y="50" font-size="6">Hi</text>
            <text x="50" y="50" font-size="6">Yo</text>
        </svg>"""

        val PAYLOAD_COLORS = mapOf("red" to 0xFFFF0000.toInt(), "green" to 0xFF00FF00.toInt(), "blue" to 0xFF0000FF.toInt(), "top" to 0xFF000000.toInt())

        private fun props(name: String) = LayerProps(name, 1f, LayerBlendMode.NORMAL, true, false, false, false, true)

        private fun raster(id: Long, name: String, key: String?, at: Int, parent: Long = 0L) = PayloadLayer(
            id, props(name), PayloadKind.RASTER,
            imageRef = key, imageRect = key?.let { PayloadRect(at, at, 100, 100) }, parentId = parent,
        )

        /** Bottom first: Red, an empty layer, a folder of Green and Blue, Top (the active one). */
        val PAYLOAD = BrushworkPayload(
            version = 2, width = SIZE, height = SIZE, activeLayer = 5,
            layers = listOf(
                raster(11, "Red", "red", 0),
                raster(12, "Empty", null, 0),
                raster(13, "Green", "green", 100, parent = 15),
                raster(14, "Blue", "blue", 250, parent = 15),
                PayloadLayer(15, props("Folder"), PayloadKind.FOLDER, folder = FolderSpec()),
                raster(16, "Top", "top", 400),
            ),
        )
    }
}
