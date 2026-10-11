package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.Gravity
import android.view.WindowManager
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import java.io.File
import java.io.FileOutputStream

/**
 * v1.8 final QA (the selection tools): renders (real Skia, `graphicsMode=NATIVE`) to the QA shots
 * folder on the developer's machine, `select-<name>.png`; nothing is written on other hosts (CI
 * on Linux, where the Windows path has no parent).
 */
internal object Qa18SelectShots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    /** Writes [b] as `select-<name>.png`; the path, or null where there is no shots folder. */
    fun save(b: Bitmap, name: String): String? {
        if (DIR.parentFile?.isDirectory != true) return null
        DIR.mkdirs()
        val f = File(DIR, "select-$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    /**
     * What the phone shows now: the editor's window with every later window (a popup, a dropdown, a
     * dialog) drawn over it where it sits on the screen.
     */
    fun screen(s: ChromeScreen, name: String): String? {
        if (DIR.parentFile?.isDirectory != true) return null
        val decor = s.activity.window.decorView
        val b = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        decor.draw(canvas)
        for (w in SmokeUi.windows()) {
            if (w === decor || w.width == 0 || w.height == 0) continue
            val p = w.layoutParams as? WindowManager.LayoutParams
            val g = p?.gravity ?: 0
            val x = if (g and Gravity.HORIZONTAL_GRAVITY_MASK == Gravity.CENTER_HORIZONTAL) (decor.width - w.width) / 2 + (p?.x ?: 0) else p?.x ?: 0
            val y = if (g and Gravity.VERTICAL_GRAVITY_MASK == Gravity.CENTER_VERTICAL) (decor.height - w.height) / 2 + (p?.y ?: 0) else p?.y ?: 0
            canvas.save()
            canvas.translate(x.toFloat(), y.toFloat())
            w.draw(canvas)
            canvas.restore()
        }
        return save(b, name).also { b.recycle() }
    }

    /** The window px rectangle [l, t, r, b] of the phone's screen, cropped (a strip, the canvas around an outline). */
    fun crop(s: ChromeScreen, name: String, l: Int, t: Int, r: Int, bottom: Int): String? {
        if (DIR.parentFile?.isDirectory != true) return null
        val decor = s.activity.window.decorView
        val full = Bitmap.createBitmap(decor.width, decor.height, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(full)
        decor.draw(canvas)
        for (w in SmokeUi.windows()) {
            if (w === decor || w.width == 0 || w.height == 0) continue
            val p = w.layoutParams as? WindowManager.LayoutParams
            canvas.save()
            canvas.translate((p?.x ?: 0).toFloat(), (p?.y ?: 0).toFloat())
            w.draw(canvas)
            canvas.restore()
        }
        val x0 = l.coerceIn(0, full.width - 1); val y0 = t.coerceIn(0, full.height - 1)
        val part = Bitmap.createBitmap(full, x0, y0, (r - x0).coerceIn(1, full.width - x0), (bottom - y0).coerceIn(1, full.height - y0))
        return save(part, name).also { part.recycle(); full.recycle() }
    }
}
