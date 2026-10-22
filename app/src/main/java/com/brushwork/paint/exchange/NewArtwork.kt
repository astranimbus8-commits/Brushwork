package com.brushwork.paint.exchange

import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.pdf.PdfImport
import com.brushwork.paint.exchange.svg.SvgDocument
import com.brushwork.paint.exchange.svg.SvgToVector
import com.brushwork.paint.exchange.svg.SvgUnits
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.storage.NewCanvasSpec
import kotlin.math.max
import kotlin.math.roundToInt

/** The artwork the gallery's "New from SVG or PDF" creates for a file. */
data class NewArtworkSpec(val name: String, val width: Int, val height: Int, val dpi: Float) {
    /** As a new canvas with a white Background. */
    fun canvas(): NewCanvasSpec = NewCanvasSpec(name, width, height, dpi, 0xFFFFFFFF.toInt())
}

/**
 * Sizes of new artworks made from files (v1.5 §4.11a): an SVG at its physical size at 350 dpi
 * (its long side between 1024 px and what this device's memory allows, [CanvasLimits]); a PDF
 * page at 300 dpi (clamped the same way); a Brushwork file at exactly its own size and DPI.
 *
 * v1.10 (F1, design 3.3): the forms without a heap read the gallery's layer budget
 * ([LayerBudget.current]: its `importMaxSide` and `rawMaxLayers`; v1.9's numbers at CLASSIC); the
 * gallery's imports call them. The forms that take a heap keep v1.9's numbers for their tests.
 */
object NewArtwork {
    const val SVG_DPI = 350f
    const val MIN_LONG_SIDE = 1024

    fun forSvg(name: String, svg: SvgDocument, maxHeap: Long): NewArtworkSpec =
        svgSpec(name, svg) { w, h -> CanvasLimits.importMaxSide(w, h, maxHeap) }

    /** v1.10 (F1): [forSvg] within [LayerBudget.current]'s import limit. */
    fun forSvg(name: String, svg: SvgDocument): NewArtworkSpec = svgSpec(name, svg, LayerBudget.current::importMaxSide)

    fun forPdfPage(name: String, pw: Float, ph: Float, maxHeap: Long): NewArtworkSpec =
        pdfSpec(name, pw, ph) { w, h -> CanvasLimits.importMaxSide(w, h, maxHeap) }

    /** v1.10 (F1): [forPdfPage] within [LayerBudget.current]'s import limit. */
    fun forPdfPage(name: String, pw: Float, ph: Float): NewArtworkSpec = pdfSpec(name, pw, ph, LayerBudget.current::importMaxSide)

    /** Exactly the payload's canvas; null when it can't fit this device's memory. */
    fun forPayload(name: String, p: BrushworkPayload, maxHeap: Long): NewArtworkSpec? =
        payloadSpec(name, p) { w, h -> CanvasLimits.rawMaxLayers(w, h, maxHeap) }

    /** v1.10 (F1): [forPayload] with [LayerBudget.current]'s layer count. */
    fun forPayload(name: String, p: BrushworkPayload): NewArtworkSpec? = payloadSpec(name, p, LayerBudget.current::rawMaxLayers)

    /** [w] x [h] with the long side scaled into [MIN_LONG_SIDE] .. the memory limit (aspect kept). */
    fun clampLongSide(w: Float, h: Float, maxHeap: Long): Pair<Int, Int> =
        clampLongSide(w, h) { sw, sh -> CanvasLimits.importMaxSide(sw, sh, maxHeap) }

    private fun svgSpec(name: String, svg: SvgDocument, importMaxSide: (Int, Int) -> Int): NewArtworkSpec {
        val inches = SvgToVector.physicalInches(svg)
            ?: SvgToVector.convert(svg, SvgUnits.CSS_DPI).bounds?.let { b -> (b.right / 96f) to (b.bottom / 96f) }
            ?: (MIN_LONG_SIDE / SVG_DPI to MIN_LONG_SIDE / SVG_DPI)
        val w0 = max(1e-3f, inches.first) * SVG_DPI
        val h0 = max(1e-3f, inches.second) * SVG_DPI
        val (w, h) = clampLongSide(w0, h0, importMaxSide)
        return NewArtworkSpec(name, w, h, SVG_DPI)
    }

    private fun pdfSpec(name: String, pw: Float, ph: Float, importMaxSide: (Int, Int) -> Int): NewArtworkSpec {
        val (w300, h300) = PdfImport.canvasSize(pw, ph, Int.MAX_VALUE)
        val maxSide = importMaxSide(w300, h300)
        val (w, h) = PdfImport.canvasSize(pw, ph, maxSide)
        return NewArtworkSpec(name, w, h, PdfImport.MAX_DPI)
    }

    private fun payloadSpec(name: String, p: BrushworkPayload, rawMaxLayers: (Int, Int) -> Long): NewArtworkSpec? {
        if (p.width !in 1..CanvasLimits.MAX_SIDE || p.height !in 1..CanvasLimits.MAX_SIDE) return null
        if (rawMaxLayers(p.width, p.height) < 2) return null
        return NewArtworkSpec(name, p.width, p.height, p.dpi.takeIf { it.isFinite() && it >= 1f } ?: SVG_DPI)
    }

    private fun clampLongSide(w: Float, h: Float, importMaxSide: (Int, Int) -> Int): Pair<Int, Int> {
        val long = max(w, h)
        // The largest long side memory allows for this shape (asked at the largest import size:
        // importMaxSide never answers above the size it is given).
        val up = CanvasLimits.IMPORT_MAX_SIDE / long
        val cap = importMaxSide(max(1, (w * up).roundToInt()), max(1, (h * up).roundToInt()))
        val target = long.coerceIn(minOf(MIN_LONG_SIDE, cap).toFloat(), cap.toFloat())
        val k = target / long
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }
}
