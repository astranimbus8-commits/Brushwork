package com.brushwork.paint.exchange

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.5 §4.10 / §4.11 (A8 review): what an import may add and how, a file over the limits, the
 * gallery's new artwork, and the merged picture of an adjustment export.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeImportRulesRobolectricTest {

    private fun target(c: com.brushwork.paint.EditorController, room: Int = ImportLayers.room(c)) =
        ImportTarget(c.doc.width, c.doc.height, c.doc.dpi, c.doc.colorMode, room, c.maxLayers)

    private fun props(name: String, clipping: Boolean = false) = LayerProps(name, 1f, LayerBlendMode.NORMAL, true, clipping, false, false, true)

    @Test
    fun anSvgOverTheLimitsIsAskedAboutNotHalfImported() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val sb = StringBuilder("""<svg xmlns="http://www.w3.org/2000/svg" xmlns:xlink="http://www.w3.org/1999/xlink" width="300" height="200"><defs><g id="g7"/>""")
        for (l in 6 downTo 0) {
            sb.append("<g id=\"g$l\">")
            repeat(40) { sb.append("<use xlink:href=\"#g${l + 1}\"/>") }
            sb.append("</g>")
        }
        sb.append("""</defs><rect width="50" height="50" fill="red"/><use xlink:href="#g0"/></svg>""")
        val svg = SvgParser.parse(sb.toString().toByteArray())
        val before = c.undoManager.undoCount
        val p = VectorImport.prepare(svg, target(c), newArtwork = false)
        assertTrue(p.tooComplex)
        assertTrue(p.layers.isEmpty())
        // Nothing reaches the document unless the user takes it as a picture.
        assertEquals(1, c.doc.layers.size)
        assertEquals(before, c.undoManager.undoCount)
        val picture = VectorImport.prepare(svg, target(c), newArtwork = false, asPicture = true)
        assertFalse(picture.tooComplex)
        assertEquals(listOf("Imported SVG (picture)"), picture.layers.map { it.name })
        assertEquals(0xFFFF0000.toInt(), picture.layers[0].bitmap.getPixel(20, 20))
    }

    @Test
    fun aNewArtworkFromAnSvgIsNotOpenedInTransform() {
        val doc = Smoke.document(200, 200, layers = 2, whiteBottom = true)
        val c = controller(doc)
        val layer1 = doc.layers[1]
        val svg = SvgParser.parse("""<svg xmlns="http://www.w3.org/2000/svg" width="20mm" height="20mm" viewBox="0 0 20 20"><rect width="10" height="10" fill="#00f"/></svg>""".toByteArray())
        val before = c.undoManager.undoCount
        val prepared = VectorImport.prepare(svg, target(c).let { it.copy(room = it.room + 1) }, newArtwork = true)
        VectorImport.apply(c, prepared, listOf(layer1))
        assertEquals(before + 1, c.undoManager.undoCount)
        val vec = c.doc.layers.single { it.isVectorLayer }
        assertTrue(c.activeLayer === vec)
        assertNotEquals(ToolId.TRANSFORM, c.activeToolId)
        assertTrue(c.vectors.selectedIds.isEmpty())
        assertEquals(2, c.doc.layers.size)
        // Into an open artwork the same file opens Transform on its objects.
        VectorImport.apply(c, VectorImport.prepare(svg, target(c), newArtwork = false))
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        c.currentTool.discard()
    }

    @Test
    fun anAdjustmentLayerNeedsTwoFreeLayersAsInTheEditor() {
        val raster = PayloadLayer(1, props("A"))
        val adjustment = PayloadLayer(2, props("Tone"), PayloadKind.ADJUSTMENT, adjustment = AdjustmentSpec())
        assertEquals(listOf(raster, adjustment, raster), PayloadImport.fitting(listOf(raster, adjustment, raster), 3))
        assertEquals(listOf(raster), PayloadImport.fitting(listOf(raster, adjustment, raster), 2))
        assertEquals(listOf(adjustment, raster), PayloadImport.fitting(listOf(adjustment, raster, raster), 2))
        assertEquals(emptyList<PayloadLayer>(), PayloadImport.fitting(listOf(adjustment), 1))
    }

    @Test
    fun importedLayersFollowTheClippingRulesOfAdjustmentLayers() {
        val c = controller(Smoke.document(100, 80, layers = 1))
        val payload = BrushworkPayload(
            width = 100, height = 80,
            layers = listOf(
                PayloadLayer(1, props("Tone", clipping = true), PayloadKind.ADJUSTMENT, adjustment = AdjustmentSpec()),
                PayloadLayer(2, props("Above", clipping = true)),
                PayloadLayer(3, props("Clipped", clipping = true)),
            ),
        )
        PayloadImport.apply(c, PayloadImport.prepare(payload, { null }, target(c)))
        val byName = c.doc.layers.associateBy { it.name }
        assertFalse(byName.getValue("Tone").clipping)
        assertFalse("right above an adjustment layer", byName.getValue("Above").clipping)
        assertTrue("an ordinary clipping layer stays clipping", byName.getValue("Clipped").clipping)
    }

    @Test
    fun aRestoredArtworkTakesItsColorModeInTheImportStep() {
        val fresh = Smoke.document(60, 40, layers = 2, whiteBottom = true)
        val c = controller(fresh)
        val payload = BrushworkPayload(width = 60, height = 40, colorMode = ColorMode.GRAYSCALE, layers = listOf(PayloadLayer(1, props("Ink"))))
        val replace = fresh.layers.toList()
        val before = c.undoManager.undoCount
        val t = target(c).let { it.copy(room = it.room + replace.size, colorMode = ColorMode.GRAYSCALE) }
        PayloadImport.apply(c, PayloadImport.prepare(payload, { null }, t), replace, ColorMode.GRAYSCALE)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(ColorMode.GRAYSCALE, c.doc.colorMode)
        assertEquals(listOf("Ink"), c.doc.layers.map { it.name })
        c.undo()
        assertEquals(ColorMode.RGB, c.doc.colorMode)
        assertEquals(listOf("Layer 1", "Layer 2"), c.doc.layers.map { it.name })
        c.redo()
        assertEquals(ColorMode.GRAYSCALE, c.doc.colorMode)
    }

    @Test
    fun aHiddenClippingGroupExportedWithHiddenLayersKeepsItsPicture() {
        val doc = ExchangeFixtures.document()
        doc.layers.first { it.name == "Base" }.visible = false
        val c = controller(doc)
        val scene = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, includeHidden = true), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val base = scene.layers.first { it.name == "Base" }
        assertTrue(base.hidden)
        val img = (base.items.single() as SceneItem.Image).image
        val px = runBlocking { img.source.load() }
        // The clipped magenta inside the (hidden) base, the base's blue around it.
        assertEquals(0xFFFF00FF.toInt(), px.pixels[(40 - img.top) * img.width + (160 - img.left)])
        assertEquals(0xFF0000FF.toInt(), px.pixels[(25 - img.top) * img.width + (160 - img.left)])
    }

    @Test
    fun theMergedPictureUnderAnAdjustmentLayerIsTheCompositeBandForBand() {
        // Taller than two composite bands (512 rows), so the picture is put together from three.
        val w = 120
        val h = 1100
        val doc = Document("m", "Merged", w, h, dpi = 300f)
        fun layer(name: String) = Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { doc.layers += it }
        layer("Background").bitmap.eraseColor(0xFFFFFFFF.toInt())
        layer("Stripes").apply {
            val cv = Canvas(bitmap)
            for (i in 0 until 22) cv.drawRect(0f, i * 50f, w.toFloat(), i * 50f + 30f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = (0x80 shl 24) or (i * 11 shl 16) or 0x3366 })
        }
        layer("Multiply").apply {
            Canvas(bitmap).drawCircle(60f, 520f, 300f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2288CC.toInt() })
            blendMode = LayerBlendMode.MULTIPLY
            opacity = 0.6f
            mask = BitmapUtils.createMaskBitmap(w, h, -1).also { m -> Canvas(m).drawRect(0f, 400f, 60f, 700f, Paint().apply { color = 0xFF404040.toInt() }) }
        }
        layer("Invert").adjustment = AdjustmentSpec(filterId = "adjust.invert")
        layer("Top").bitmap.eraseColor(0x400000FF)
        val c = controller(doc)
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val img = (scene.layers[0].items.single() as SceneItem.Image).image
        assertEquals(0 to 0, img.left to img.top)
        assertEquals(w to h, img.width to img.height)
        val merged = runBlocking { img.source.load() }
        // The reference: the same layers flattened in one pass.
        val below = Document("r", "Reference", w, h, dpi = 300f)
        below.layers += doc.layers.subList(0, 4)
        val ref = Compositor(below) { null }.renderFlattened()
        assertArrayEquals(pixels(ref), merged.pixels)
        assertEquals("Top", scene.layers[1].name)
    }
}
