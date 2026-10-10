package com.brushwork.paint.qa17

import android.graphics.Bitmap
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.Compositor
import java.io.File
import java.io.FileOutputStream

/**
 * v1.7 final QA (layers cluster: folders, saved selections, symmetry): renders for the lead,
 * written as `layers-*.png` outside the repository, and only where the QA tools folder exists (on
 * CI, a Linux host, the Windows path has no parent: nothing is written).
 */
internal object Qa17LayersShots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v17-qa-shots")

    fun save(b: Bitmap, name: String): File? {
        if (DIR.parentFile?.isDirectory != true) return null
        DIR.mkdirs()
        val f = File(DIR, "layers-$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }

    /** The flattened picture (what the canvas shows, without tool previews). The caller recycles it. */
    fun flat(c: EditorController): Bitmap = Compositor(c.doc) { null }.renderFlattened()

    /** [flat] of [c] saved as `layers-[name].png`; returns the picture's pixels. */
    fun shoot(c: EditorController, name: String): IntArray {
        val b = flat(c)
        try {
            save(b, name)?.let { println("Qa17Layers: wrote ${it.path}") }
            return IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }
        } finally {
            b.recycle()
        }
    }

    /** The flattened pixel at ([x], [y]). */
    fun pixel(c: EditorController, x: Int, y: Int): Int {
        val b = flat(c)
        try { return b.getPixel(x, y) } finally { b.recycle() }
    }

    /** "r g b a" of [argb], for messages. */
    fun rgb(argb: Int): String = "(${(argb shr 16) and 0xFF}, ${(argb shr 8) and 0xFF}, ${argb and 0xFF}, a ${argb ushr 24})"

    /** Whether every channel of [a] is within [tol] of ([r], [g], [b]) and [a] is opaque. */
    fun near(a: Int, r: Int, g: Int, b: Int, tol: Int = 2): Boolean =
        (a ushr 24) == 255 && kotlin.math.abs(((a shr 16) and 0xFF) - r) <= tol &&
            kotlin.math.abs(((a shr 8) and 0xFF) - g) <= tol && kotlin.math.abs((a and 0xFF) - b) <= tol

    fun near(a: Int, want: Int, tol: Int = 2): Boolean = near(a, (want shr 16) and 0xFF, (want shr 8) and 0xFF, want and 0xFF, tol)

    /** [base] (opaque ARGB) with the pixels [mask] covers washed half-way to [color]. */
    fun tinted(base: IntArray, mask: ByteArray, color: Int): IntArray = IntArray(base.size) { i ->
        val k = (mask[i].toInt() and 0xFF) / 510.0
        if (k == 0.0) base[i] else {
            fun ch(sh: Int) = (((base[i] shr sh) and 0xFF) * (1 - k) + ((color shr sh) and 0xFF) * k).toInt()
            (0xFF shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
        }
    }

    /**
     * [tiles] (each [w] × [h]) laid out [cols] to a row with a 4 px grey gutter, saved as
     * `layers-[name].png`.
     */
    fun saveGrid(name: String, tiles: List<IntArray>, w: Int, h: Int, cols: Int) {
        val rows = (tiles.size + cols - 1) / cols
        val g = 4
        val b = Bitmap.createBitmap(cols * w + (cols + 1) * g, rows * h + (rows + 1) * g, Bitmap.Config.ARGB_8888)
        b.eraseColor(0xFF606060.toInt())
        tiles.forEachIndexed { i, px -> b.setPixels(px, 0, w, g + (i % cols) * (w + g), g + (i / cols) * (h + g), w, h) }
        try {
            save(b, name)?.let { println("Qa17Layers: wrote ${it.path}") }
        } finally {
            b.recycle()
        }
    }
}
