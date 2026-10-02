package com.brushwork.paint.exchange.pdf

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import android.graphics.pdf.PdfRenderer
import android.os.ParcelFileDescriptor
import com.brushwork.paint.exchange.ImportException
import java.io.Closeable
import java.io.File
import java.io.IOException

/**
 * The pages of a PDF as pictures (v1.5 §4.11): the platform's [PdfRenderer] on the phone
 * ([PdfRendererRasterizer]), a fake in tests (V9: Robolectric has no PdfRenderer natives).
 * Implementations are thread-safe (one page renders at a time).
 */
interface PageRasterizer : Closeable {
    val pageCount: Int

    /** Size of page [index] in points (1/72 in). */
    fun pageSize(index: Int): Pair<Float, Float>

    /** Page [index] scaled into a new [width] x [height] ARGB bitmap (transparent where the page draws nothing). */
    fun render(index: Int, width: Int, height: Int): Bitmap
}

/** Opens a [PageRasterizer] for a PDF file (throws [ImportException] with a user message). */
fun interface PageRasterizerFactory {
    fun open(context: Context, file: File): PageRasterizer
}

/** [PageRasterizer] over the platform renderer. */
class PdfRendererRasterizer private constructor(private val fd: ParcelFileDescriptor) : PageRasterizer {
    private val renderer: PdfRenderer = try {
        PdfRenderer(fd)
    } catch (e: SecurityException) {
        fd.close()
        throw ImportException("Protected PDFs can't be opened")
    } catch (e: IOException) {
        fd.close()
        throw ImportException("This PDF can't be read")
    }

    override val pageCount: Int get() = renderer.pageCount

    @Synchronized
    override fun pageSize(index: Int): Pair<Float, Float> = renderer.openPage(index).use { it.width.toFloat() to it.height.toFloat() }

    @Synchronized
    override fun render(index: Int, width: Int, height: Int): Bitmap {
        val bmp = Bitmap.createBitmap(width.coerceAtLeast(1), height.coerceAtLeast(1), Bitmap.Config.ARGB_8888)
        renderer.openPage(index).use { page ->
            val m = Matrix().apply { setScale(width / page.width.toFloat(), height / page.height.toFloat()) }
            page.render(bmp, null, m, PdfRenderer.Page.RENDER_MODE_FOR_DISPLAY)
        }
        return bmp
    }

    @Synchronized
    override fun close() {
        try {
            renderer.close()
        } finally {
            fd.close()
        }
    }

    companion object {
        /** Opens [file] (a PDF copied to the cache). */
        val factory = PageRasterizerFactory { _, file ->
            PdfRendererRasterizer(ParcelFileDescriptor.open(file, ParcelFileDescriptor.MODE_READ_ONLY))
        }
    }
}
