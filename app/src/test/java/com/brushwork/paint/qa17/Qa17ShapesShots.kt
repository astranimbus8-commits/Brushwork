package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import java.io.File
import java.io.FileOutputStream

/**
 * v1.7 final QA renders: what the user sees (the editor window) and the flattened picture, as
 * PNGs in the v1.7 QA shots folder, kept out of the repository. Nothing is written where the
 * folder is absent (CI, other machines).
 */
internal object Qa17ShapesShots {
    private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v17-qa-shots")

    /** The flattened picture of [s] to `<name>-doc.png` (on white); the path written, or null. */
    fun doc(s: ChromeScreen, name: String): String? {
        if (!SHOTS.isDirectory) return null
        return runCatching { write(s.c.compositor.renderFlattened(-1), "$name-doc.png") }.getOrNull()
    }

    /** The editor window of [s] to `<name>-screen.png`; the path written, or null. */
    fun screen(s: ChromeScreen, name: String): String? {
        if (!SHOTS.isDirectory) return null
        return runCatching {
            val v = s.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            write(bmp, "$name-screen.png")
        }.getOrNull()
    }

    private fun write(bmp: Bitmap, file: String): String {
        val f = File(SHOTS, file)
        FileOutputStream(f).use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }
}
