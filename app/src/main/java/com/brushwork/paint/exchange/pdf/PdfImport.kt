package com.brushwork.paint.exchange.pdf

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.ColorModeOps
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.ImportOutcome
import com.brushwork.paint.exchange.ImportTarget
import com.brushwork.paint.exchange.NewLayer
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.recycleUnlessShared
import java.util.concurrent.CancellationException
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt

/**
 * PDF pages as raster layers (v1.5 §4.11a): each chosen page becomes a layer "Page N", rendered
 * to fit the canvas (at most 300 dpi of the page), centred, over white unless the transparent
 * background is asked for; several pages are ONE undo step (a single page is placed with the
 * Transform tool like an imported picture). Vector content of foreign PDFs is not read (§8).
 */
object PdfImport {
    const val MAX_DPI = 300f
    const val LABEL = "Import PDF"

    /** The size a [pw] x [ph] pt page renders at to fit a [docW] x [docH] canvas, at most [MAX_DPI]. */
    fun renderSize(pw: Float, ph: Float, docW: Int, docH: Int): Pair<Int, Int> {
        if (!(pw > 0f) || !(ph > 0f)) return 1 to 1
        val fit = min(docW / pw, docH / ph)
        val s = min(fit, MAX_DPI / 72f)
        return max(1, (pw * s).roundToInt()) to max(1, (ph * s).roundToInt())
    }

    /** A new artwork's size for a [pw] x [ph] pt page: at [MAX_DPI], the long side at most [maxSide]. */
    fun canvasSize(pw: Float, ph: Float, maxSide: Int): Pair<Int, Int> {
        if (!(pw > 0f) || !(ph > 0f)) return 1 to 1
        var w = pw * MAX_DPI / 72f
        var h = ph * MAX_DPI / 72f
        val long = max(w, h)
        if (long > maxSide) { val k = maxSide / long; w *= k; h *= k }
        return max(1, w.roundToInt()) to max(1, h.roundToInt())
    }

    /** Page [index] rendered for [target] (its own size, over white unless [transparent]). Not on the main thread. */
    fun renderPage(r: PageRasterizer, index: Int, target: ImportTarget, transparent: Boolean): Bitmap {
        val (pw, ph) = r.pageSize(index)
        val (w, h) = renderSize(pw, ph, target.width, target.height)
        val page = r.render(index, w, h)
        if (transparent) return page
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        out.eraseColor(0xFFFFFFFF.toInt())
        Canvas(out).drawBitmap(page, 0f, 0f, null)
        page.recycle()
        return out
    }

    /**
     * One layer per page of [pages] (0-based), each page centred on the canvas. Not on the
     * main thread; [cancelled] is polled between pages.
     */
    fun prepare(r: PageRasterizer, pages: List<Int>, target: ImportTarget, transparent: Boolean, cancelled: () -> Boolean = { false }): List<NewLayer> {
        val out = ArrayList<NewLayer>()
        try {
            for (index in pages.take(target.room)) {
                if (cancelled()) throw CancellationException("Import stopped")
                val page = renderPage(r, index, target, transparent)
                val bmp = BitmapUtils.createLayerBitmap(target.width, target.height)
                try {
                    Canvas(bmp).drawBitmap(page, ((target.width - page.width) / 2).toFloat(), ((target.height - page.height) / 2).toFloat(), Paint(Paint.FILTER_BITMAP_FLAG))
                } finally {
                    page.recycle()
                }
                if (target.colorMode != ColorMode.RGB) ColorModeOps.constrain(bmp, Rect(0, 0, bmp.width, bmp.height), target.colorMode)
                out += NewLayer("Page ${index + 1}", bmp)
            }
        } catch (e: Throwable) {
            out.forEach { it.bitmap.recycleUnlessShared() }
            throw e
        }
        return out
    }

    /** Inserts the page layers as ONE undo step (removing [replace]). Main thread. */
    fun apply(c: EditorController, layers: List<NewLayer>, asked: Int, replace: List<Layer> = emptyList()): ImportOutcome {
        val created = ImportLayers.insert(c, layers, LABEL, replace)
        val dropped = if (asked > created.size) mapOf("pages (layer limit)" to asked - created.size) else emptyMap()
        return ImportOutcome(pages = created.size, layers = created.size, dropped = dropped)
    }
}
