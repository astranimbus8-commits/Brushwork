package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import java.io.File
import java.io.FileOutputStream

/**
 * v1.7 final QA renders (real Skia: `graphicsMode=NATIVE`) to the QA shots folder on the
 * developer's machine, `chrome-<name>.png`; nothing is written on other hosts (CI on Linux).
 */
internal object Qa17Shots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v17-qa-shots")

    /** Writes [b] as `chrome-<name>.png`; the path, or null where there is no shots folder. */
    fun save(b: Bitmap, name: String): String? {
        if (DIR.parentFile?.isDirectory != true) return null
        DIR.mkdirs()
        val f = File(DIR, "chrome-$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }

    /** The editor's window as drawn now (its own window only: dialogs and menus are other windows). */
    fun screen(s: ChromeScreen, name: String): String? {
        if (DIR.parentFile?.isDirectory != true) return null
        val v = s.activity.window.decorView
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        return save(b, name)
    }
}
