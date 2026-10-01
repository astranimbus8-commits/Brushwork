package com.brushwork.paint.exchange

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
 */
object NewArtwork {
    const val SVG_DPI = 350f
    const val MIN_LONG_SIDE = 1024

    fun forSvg(name: String, svg: SvgDocument, maxHeap: Long): NewArtworkSpec {
        val inches = SvgToVector.physicalInches(svg)
            ?: SvgToVector.convert(svg, SvgUnits.CSS_DPI).bounds?.let { b -> (b.right / 96f) to (b.bottom / 96f) }
            ?: (MIN_LONG_SIDE / SVG_DPI to MIN_LONG_SIDE / SVG_DPI)
        val w0 = max(1e-3f, inches.first) * SVG_DPI
        val h0 = max(1e-3f, inches.second) * SVG_DPI
        val (w, h) = clampLongSide(w0, h0, maxHeap)
        return NewArtworkSpec(name, w, h, SVG_DPI)
    }

    fun forPdfPage(name: String, pw: Float, ph: Float, maxHeap: Long): NewArtworkSpec {
        val (w300, h300) = PdfImport.canvasSize(pw, ph, Int.MAX_VALUE)
        val maxSide = CanvasLimits.importMaxSide(w300, h300, maxHeap)
        val (w, h) = PdfImport.canvasSize(pw, ph, maxSide)
        return NewArtworkSpec(name, w, h, PdfImport.MAX_DPI)
    }

    /** Exactly the payload's canvas; null when it can't fit this device's memory. */
    fun forPayload(name: String, p: BrushworkPayload, maxHeap: Long): NewArtworkSpec? {
        if (p.width !in 1..CanvasLimits.MAX_SIDE || p.height !in 1..CanvasLimits.MAX_SIDE) return null
        if (CanvasLimits.rawMaxLayers(p.width, p.height, maxHeap) < 2) return null
        return NewArtworkSpec(name, p.width, p.height, p.dpi.takeIf { it.isFinite() && it >= 1f } ?: SVG_DPI)
    }

    /** [w] x [h] with the long side scaled into [MIN_LONG_SIDE] .. the memory limit (aspect kept). */
    fun clampLongSide(w: Float, h: Float, maxHeap: Long): Pair<Int, Int> {
        val long = max(w, h)
        val wi = max(1, w.roundToInt())
        val hi = max(1, h.roundToInt())
        val cap = CanvasLimits.importMaxSide(wi, hi, maxHeap)
        val target = long.coerceIn(minOf(MIN_LONG_SIDE, cap).toFloat(), cap.toFloat())
        val k = target / long
        return max(1, (w * k).roundToInt()) to max(1, (h * k).roundToInt())
    }
}
