package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.WindowManager
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import java.io.File
import java.io.FileOutputStream

/**
 * Renders what the user sees (every window of the current screen, the activity first, popups and
 * dialogs where the window manager put them, with their dim) to a bitmap through real Skia, and
 * writes it next to the ibisPaint reference it should look like.
 *
 * Output: the machine's QA folder (`.wt/_tools/v16-qa-shots`, two levels above a worktree) when
 * it exists, else `app/build/qa16-shots`. Renders are never committed.
 */
internal object IbisShots {

    /** Where the PNGs go (created on demand). */
    val dir: File by lazy {
        val qa = File("../../_tools/v16-qa-shots").absoluteFile.normalize()
        (if (qa.isDirectory) qa else File("build/qa16-shots").absoluteFile).apply { mkdirs() }
    }

    /** The reference screenshots (345 × 768 px, 1 px = 1.136 dp), or null when not on this machine. */
    private fun reference(name: String): Bitmap? {
        val f = File("../../_tools/v16-refs/$name").absoluteFile.normalize()
        return if (f.isFile) BitmapFactory.decodeFile(f.path) else null
    }

    /** Every window of the current screen composed into one bitmap of the activity window's size. */
    fun capture(): Bitmap {
        Smoke.pump(200)
        SmokeUi.settle()
        val roots = SmokeUi.windows()
        require(roots.isNotEmpty()) { "no window to render" }
        val main = roots.first()
        val out = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        val canvas = Canvas(out)
        val screen = Rect(0, 0, main.width, main.height)
        for ((i, v) in roots.withIndex()) {
            val lp = v.layoutParams as? WindowManager.LayoutParams
            if (v.width <= 0 || v.height <= 0) continue
            var x = 0
            var y = 0
            if (i > 0 && lp != null) {
                if (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                    canvas.drawColor(Color.argb((lp.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                }
                val r = Rect()
                val g = if (lp.gravity == 0) Gravity.TOP or Gravity.START else lp.gravity
                Gravity.apply(g, v.width, v.height, screen, lp.x, lp.y, r, View.LAYOUT_DIRECTION_LTR)
                x = r.left
                y = r.top
            }
            val layer = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(layer))
            if (i > 0 && blank(layer)) schematic(v, Canvas(layer))
            canvas.drawBitmap(layer, x.toFloat(), y.toFloat(), null)
        }
        return out
    }

    private fun blank(b: Bitmap): Boolean {
        for (yy in 0 until b.height step 7) for (xx in 0 until b.width step 7) if (Color.alpha(b.getPixel(xx, yy)) != 0) return false
        return true
    }

    /**
     * A popup window (a Material dropdown menu) draws nothing into a bitmap under Robolectric,
     * though it is composed, placed and positioned. Draws it from its semantics instead: its
     * container in the sheet colour, dividers, and every text and icon description at its bounds
     * (a schematic of placement and content, not of the exact look).
     */
    private fun schematic(v: View, c: Canvas) {
        val els = com.brushwork.paint.ui.color.RobolectricUi.elements().filter { it.window === v && it.node.layoutInfo.isPlaced }
        val d = v.resources.displayMetrics.density
        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC7000000.toInt() }
        val container = els.firstOrNull { it.node.config.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.IsContainer) == true }
            ?: els.firstOrNull()
        container?.bounds?.let { b -> c.drawRoundRect(b.left, b.top, b.right, b.bottom, 10 * d, 10 * d, fill) }
        val text = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.WHITE; textSize = 14 * d }
        val dim = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0x66FFFFFF; style = Paint.Style.STROKE; strokeWidth = d }
        for (e in els) {
            val cfg = e.node.config
            val b = e.bounds
            if (cfg.getOrNull(androidx.compose.ui.semantics.SemanticsActions.OnClick) != null) {
                c.drawRect(b.left + d, b.top + d, b.right - d, b.bottom - d, dim)
            }
            cfg.getOrNull(androidx.compose.ui.semantics.SemanticsProperties.Text)?.joinToString(" ") { it.text }?.let { t ->
                c.drawText(t, b.left, b.top + (b.height + text.textSize * 0.7f) / 2f, text)
            }
        }
    }

    /**
     * Writes [shot] as `ibis-[name].png` (full resolution) and `ibis-[name]-vs-ref.png`: the
     * reference [ref] (scaled to 1 px = 1 dp) at the left and the render (1 px = 1 dp) at the right.
     */
    fun save(name: String, shot: Bitmap, density: Float, ref: String? = null): File {
        val f = File(dir, "ibis-$name.png")
        FileOutputStream(f).use { shot.compress(Bitmap.CompressFormat.PNG, 100, it) }
        val wDp = (shot.width / density).toInt()
        val hDp = (shot.height / density).toInt()
        val small = Bitmap.createScaledBitmap(shot, wDp * 2, hDp * 2, true)
        val r = ref?.let { reference(it) }
        if (r != null) {
            // 345 px = 392 dp on the reference phone: 1.136 dp per px.
            val rw = (r.width * 1.136f * 2).toInt()
            val rh = (r.height * 1.136f * 2).toInt()
            val rs = Bitmap.createScaledBitmap(r, rw, rh, true)
            val both = Bitmap.createBitmap(rw + 24 + small.width, maxOf(rh, small.height), Bitmap.Config.ARGB_8888)
            val c = Canvas(both)
            c.drawColor(Color.MAGENTA)
            c.drawBitmap(rs, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            c.drawBitmap(small, (rw + 24).toFloat(), 0f, Paint(Paint.FILTER_BITMAP_FLAG))
            FileOutputStream(File(dir, "ibis-$name-vs-ref.png")).use { both.compress(Bitmap.CompressFormat.PNG, 100, it) }
        } else {
            FileOutputStream(File(dir, "ibis-$name-2x.png")).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
        return f
    }

    /**
     * The editor in a fresh activity ([doc], [setup]), [drive] through its controls, then ONE
     * render saved as `ibis-[name]` beside [ref]; [check] gets the screen and the render.
     */
    fun shoot(
        name: String,
        ref: String?,
        doc: com.brushwork.paint.model.Document = Smoke.document(300, 430, layers = 2, whiteBottom = true),
        setup: (com.brushwork.paint.EditorController) -> Unit = {},
        drive: (com.brushwork.paint.ui.editor.chrome.ChromeScreen) -> Unit = {},
        check: (com.brushwork.paint.ui.editor.chrome.ChromeScreen, Bitmap) -> Unit = { _, _ -> },
    ) {
        org.robolectric.shadows.ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = com.brushwork.paint.ui.editor.chrome.ChromeHarness()
        h.section(name) {
            val s = h.editor(doc, setup)
            drive(s)
            val shot = capture()
            save(name, shot, s.density, ref)
            check(s, shot)
        }
        dog.interrupt()
        h.finish()
    }

    /** The colour at ([xDp], [yDp]) of [shot], in dp of a [density] screen. */
    fun at(shot: Bitmap, density: Float, xDp: Float, yDp: Float): Int =
        shot.getPixel((xDp * density).toInt().coerceIn(0, shot.width - 1), (yDp * density).toInt().coerceIn(0, shot.height - 1))

    fun hex(c: Int) = String.format("#%08X", c)

    /** Whether [actual] is within [tol] per channel of [expected] (alpha ignored). */
    fun close(expected: Int, actual: Int, tol: Int = 6): Boolean =
        Math.abs(Color.red(expected) - Color.red(actual)) <= tol &&
            Math.abs(Color.green(expected) - Color.green(actual)) <= tol &&
            Math.abs(Color.blue(expected) - Color.blue(actual)) <= tol
}
