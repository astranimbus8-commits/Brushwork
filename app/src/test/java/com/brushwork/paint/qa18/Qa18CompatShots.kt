package com.brushwork.paint.qa18

import android.graphics.Bitmap
import java.io.File

/** v1.8 QA (compat): PNGs for a person to look at, `_tools/v18-qa-shots/compat-<name>.png` (else `build/qa18-shots`). */
internal object Qa18CompatShots {
    fun save(name: String, bmp: Bitmap): File {
        val qa = File("../../_tools/v18-qa-shots").absoluteFile.normalize()
        val dir = (if (qa.isDirectory) qa else File("build/qa18-shots").absoluteFile).apply { mkdirs() }
        val f = File(dir, "compat-$name.png")
        f.outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f
    }
}
