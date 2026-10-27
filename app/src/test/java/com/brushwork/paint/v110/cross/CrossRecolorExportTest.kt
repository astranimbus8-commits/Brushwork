package com.brushwork.paint.v110.cross

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.array.ArrayLookKit.BLUE
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.core.ColorUtils
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PdfObj
import com.brushwork.paint.model.ArrayColorMode
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory
import kotlin.math.abs

/**
 * v1.10 Gate 2 cross test (R5 x export): a vector array's copies in the written SVG and PDF
 * files. Two fill-only boxes, one opaque and one translucent, get an array of 3 whose look is
 * Recolored (tint stops red to blue, strength 0.6, the last copy at 50 %). In the SVG file (the
 * layer's group) and in the PDF file (the `rg` fill colors of the layer's form) every copy carries
 * `ColorUtils.recolor(own, g(t_k), s)` of each box's color within A3's documented bound (±1 per
 * channel opaque, ±3 translucent; the alpha kept), the source its own color, in drawing order
 * (copy 2, copy 1, the source). The same array Tinted writes v1.9's colors (`ArrayTint.apply`),
 * as before R5. CLASSIC and FULL.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossRecolorExportTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val app get() = RuntimeEnvironment.getApplication()
    private var controller: EditorController? = null

    @After
    fun tearDown() {
        ArrayDraw.clearCaches()
        controller?.let { c ->
            c.dispose()
            A8Kit.drain()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
    }

    /** A written fill: its color (alpha from fill-opacity or the slot) and the item's opacity. */
    private data class Fill(val argb: Int, val opacity: Float)

    private fun scene(c: EditorController, format: VectorFormat): ExportScene =
        runBlocking { ExportSceneBuilder(c, ExportOptions(format), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }

    /** The fills of [layer]'s group in the written SVG file, in document order. */
    private fun svgFills(c: EditorController, layer: Layer): List<Fill> {
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene(c, VectorFormat.SVG)).write(out) }
        val xml = DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
        val groups = xml.getElementsByTagName("g")
        val group = (0 until groups.length).map { groups.item(it) as Element }.single { it.getAttribute("inkscape:label") == layer.name }
        val paths = group.getElementsByTagName("path")
        return (0 until paths.length).map { i ->
            val p = paths.item(i) as Element
            val rgb = p.getAttribute("fill").removePrefix("#").toInt(16)
            val alpha = p.getAttribute("fill-opacity").ifEmpty { "1" }.toFloat()
            Fill(((alpha * 255f + 0.5f).toInt() shl 24) or rgb, p.getAttribute("opacity").ifEmpty { "1" }.toFloat())
        }
    }

    /** The `r g b rg` fill colors (opaque) of every form of the written PDF file's page, in order. */
    private fun pdfFills(c: EditorController): List<Int> {
        val out = ByteArrayOutputStream()
        runBlocking { PdfWriter(scene(c, VectorFormat.PDF)) {}.write(out) }
        val r = OwnPdfReader(ArrayPdfBytes(out.toByteArray()))
        val pageRef = ((r.resolve(r.catalog()!!["Pages"]) as PdfObj.Dict)["Kids"] as PdfObj.Arr).items[0] as PdfObj.Ref
        val res = (r.obj(pageRef.num) as PdfObj.Dict)["Resources"] as PdfObj.Dict
        val forms = (res["XObject"] as PdfObj.Dict).map.values.map { r.resolve(it) as PdfObj.Stream }
        val rg = Regex("([0-9.]+) ([0-9.]+) ([0-9.]+) rg")
        return forms.flatMap { f ->
            rg.findAll(String(r.streamData(f), Charsets.ISO_8859_1)).map { m ->
                val (cr, cg, cb) = m.destructured
                fun ch(s: String) = (s.toDouble() * 255.0 + 0.5).toInt()
                (0xFF shl 24) or (ch(cr) shl 16) or (ch(cg) shl 8) or ch(cb)
            }.toList()
        }
    }

    /** [got] is [want] within ±1 per channel when [want] is opaque, ±3 when translucent (alpha: [alpha] compares it too). */
    private fun assertNear(what: String, want: Int, got: Int, alpha: Boolean) {
        val tol = if (want ushr 24 == 0xFF) 1 else 3
        for (shift in listOf(16, 8, 0)) {
            val d = abs(((want shr shift) and 0xFF) - ((got shr shift) and 0xFF))
            assertTrue("$what: %08X vs %08X".format(want, got), d <= tol)
        }
        if (alpha) assertTrue("$what: alpha %08X vs %08X".format(want, got), abs((want ushr 24) - (got ushr 24)) <= 1)
    }

    /** The colors expected per drawn item: copy 2 (blue, 50 %), copy 1 (red), the source; each box in order. */
    private fun expected(own: List<Int>, map: (Int, Int) -> Int): List<Fill> =
        own.map { Fill(map(it, BLUE), 0.5f) } + own.map { Fill(map(it, RED), 1f) } + own.map { Fill(it, 1f) }

    private fun check(c: EditorController, layer: Layer, mode: ArrayColorMode, own: List<Int>, map: (Int, Int) -> Int) {
        val want = expected(own, map)
        val svg = svgFills(c, layer)
        assertEquals("$mode, SVG: one path per box and copy", want.size, svg.size)
        for (i in want.indices) {
            assertNear("$mode, SVG item $i", want[i].argb, svg[i].argb, alpha = true)
            assertEquals("$mode, SVG item $i: the copy's opacity", want[i].opacity, svg[i].opacity, 1e-3f)
        }
        val pdf = pdfFills(c)
        assertEquals("$mode, PDF: one fill per box and copy", want.size, pdf.size)
        for (i in want.indices) assertNear("$mode, PDF item $i", want[i].argb, pdf[i], alpha = false)
    }

    @Test
    fun recoloredCopiesExportInTheirColorsAndTintedOnesAsBefore() {
        val c = ArrayLookKit.controller(app).also { controller = it }
        val translucent = 0x80C04020.toInt()
        val own = listOf(GRAY, translucent)
        val layer = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(20f, 30f, 60f, 70f), ArrayLookKit.box(20f, 90f, 60f, 130f, fill = translucent)))
        val look = ArrayLook(opacityEnd = 0.5f, tint = listOf(VStop(0f, RED), VStop(1f, BLUE)), tintStrength = 0.6f, colorMode = ArrayColorMode.RECOLOR)
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(count = 3, look = look))
        // The bound tells the two modes apart: every copy's Recolored color is beyond it from its Tinted one.
        for (argb in own) for (target in listOf(RED, BLUE)) {
            val r = ColorUtils.recolor(argb, target, 0.6f)
            val t = ArrayTint.apply(argb, target, 0.6f)
            val tol = if (argb ushr 24 == 0xFF) 1 else 3
            val apart = listOf(16, 8, 0).any { abs(((r shr it) and 0xFF) - ((t shr it) and 0xFF)) > tol }
            assertTrue("%08X to %08X: recolored %08X, tinted %08X".format(argb, target, r, t), apart)
        }

        check(c, layer, ArrayColorMode.RECOLOR, own) { argb, target -> ColorUtils.recolor(argb, target, 0.6f) }
        ArrayLookKit.edit(c, layer, layer.array!!.spec.copy(look = look.copy(colorMode = ArrayColorMode.TINT)))
        check(c, layer, ArrayColorMode.TINT, own) { argb, target -> ArrayTint.apply(argb, target, 0.6f) }
    }

    companion object {
        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
