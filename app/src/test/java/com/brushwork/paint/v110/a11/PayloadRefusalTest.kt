package com.brushwork.paint.v110.a11

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.Payload
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.ui.common.LayersLabels110
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.io.IOException

/**
 * v1.10 (A11, D47, design 2.16 "Share format above 1,000 layers"): the share format holds at most
 * 1000 layers. An artwork of more is never written with Brushwork data: every payload write
 * (the JSON of a PDF, the Base64 of an SVG) refuses with `LayersLabels110.PAYLOAD_TOO_MANY`, so an
 * SVG or PDF export with Brushwork data stops with that text and no file. 1000 layers still write
 * and read back.
 */
class PayloadRefusalTest {
    private fun payload(layers: Int) = BrushworkPayload(
        width = 8, height = 8,
        layers = List(layers) { i -> PayloadLayer(i + 1L, ProjectFormat.defaultProps("Layer ${i + 1}")) },
    )

    private fun refused(what: String, block: () -> Unit) {
        try {
            block()
            fail("$what: written with more than 1000 layers")
        } catch (e: IOException) {
            assertEquals(what, LayersLabels110.PAYLOAD_TOO_MANY, e.message)
        }
    }

    @Test
    fun theShareFormatRefusesAbove1000Layers() {
        assertNull("1000 layers fit", Payload.refusal(1000))
        assertEquals(LayersLabels110.PAYLOAD_TOO_MANY, Payload.refusal(1001))
        assertEquals(LayersLabels110.PAYLOAD_TOO_MANY, Payload.refusal(10_000))

        // 1000 layers write and read back.
        val fits = payload(1000)
        assertEquals(1000, Payload.fromBase64(Payload.toBase64(fits)).layers.size)
        assertEquals(1000, Payload.fromJson(Payload.toJson(fits)).layers.size)

        // 1001 layers: no payload write.
        val tooMany = payload(1001)
        refused("toJson") { Payload.toJson(tooMany) }
        refused("toBase64") { Payload.toBase64(tooMany) }
        refused("deflated") { Payload.deflated(tooMany) }

        // An SVG or PDF export with Brushwork data stops with the text (the export shows it; no file is kept).
        val scene = ExportScene(8, 8, 72f, "Many", null, emptyList(), tooMany)
        refused("SVG export") { runBlocking { SvgWriter(scene).write(ByteArrayOutputStream()) } }
        refused("PDF export") { runBlocking { PdfWriter(scene).write(ByteArrayOutputStream()) } }

        // Without Brushwork data the same artwork exports.
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(ExportScene(8, 8, 72f, "Many", null, emptyList(), null)).write(out) }
        assertTrue("an SVG without Brushwork data", out.size() > 0)
    }
}
