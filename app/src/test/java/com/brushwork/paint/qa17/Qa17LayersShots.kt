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
}
