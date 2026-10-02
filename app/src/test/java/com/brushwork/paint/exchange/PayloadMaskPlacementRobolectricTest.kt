package com.brushwork.paint.exchange

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.smoke.Smoke
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.math.floor
import kotlin.math.max

/**
 * v1.5 review (B-i): a Brushwork layer placed on a canvas of another size draws its editable mask
 * from the placed spec (ExchangeSeamsRobolectricTest: mask = render(spec)); here the placed mask
 * is also checked to be the source mask WHERE the layer was placed (90 %, centred), so the spec
 * moves with the pixels and not elsewhere.
 */
@RunWith(RobolectricTestRunner::class)
class PayloadMaskPlacementRobolectricTest {

    private fun target(c: EditorController) = ImportTarget(c.doc.width, c.doc.height, c.doc.dpi, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)

    private fun gray(b: Bitmap, x: Int, y: Int) = b.getPixel(x, y) and 0xFF

    @Test
    fun aPlacedMaskIsTheSourceMaskWhereTheLayerWasPlaced() {
        val doc = document(adjustment = true)
        val source = doc.layers.first { it.maskSpec != null }
        val scene = runBlocking { ExportSceneBuilder(controller(doc), ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = SvgParser.parse(out.toByteArray())
        val c = controller(Smoke.document(150, 100, layers = 1))
        PayloadImport.apply(c, PayloadImport.prepare(svg.payload()!!, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target(c)))
        val placed = c.doc.layers.first { it.name == source.name }.mask!!
        val p = VectorImport.placement(doc.width.toFloat() to doc.height.toFloat(), 150, 100, fill = false)
        val old = source.mask!!
        var worst = 0
        for (y in 0 until 100) for (x in 0 until 150) {
            // This pixel's centre, back in the source document.
            val ox = ((x + 0.5f - p.e) / p.a - 0.5f).let { floor(it + 0.5f).toInt() }.coerceIn(0, doc.width - 1)
            val oy = ((y + 0.5f - p.f) / p.d - 0.5f).let { floor(it + 0.5f).toInt() }.coerceIn(0, doc.height - 1)
            worst = max(worst, abs(gray(placed, x, y) - gray(old, ox, oy)))
        }
        assertTrue("placed mask vs the source mask under the placement: off by up to $worst", worst <= 3)
    }
}
