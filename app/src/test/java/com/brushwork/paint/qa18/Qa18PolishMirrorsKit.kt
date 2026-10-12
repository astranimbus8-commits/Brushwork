package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import java.io.File
import java.io.FileOutputStream

/**
 * v1.8 polish (mirrors follow-ups): the renders of the `Qa18PolishMirrors*` UI tests go to the
 * lead's QA folder as `polish-mirrors-<name>.png` (only where that folder's parent exists, never on
 * CI). The document is [Qa18MirrorsKit.flat]'s.
 */
internal object Qa18PolishMirrorsKit {
    private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    /** `polish-mirrors-<name>.png` (the document) and `polish-mirrors-<name>-screen.png` (the editor window). */
    fun shoot(s: ChromeScreen, name: String): List<String> {
        val out = mutableListOf<String>()
        png(Qa18MirrorsKit.flat(s.c), name)?.let { out += it }
        runCatching {
            val v = s.activity.window.decorView
            val bmp = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(bmp))
            png(bmp, "$name-screen")?.let { out += it }
        }
        return out
    }

    fun png(b: Bitmap, name: String): String? {
        if (SHOTS.parentFile?.isDirectory != true) return null
        SHOTS.mkdirs()
        val f = File(SHOTS, "polish-mirrors-$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Qa18PolishMirrors: wrote ${f.absolutePath}")
        return f.absolutePath
    }
}
