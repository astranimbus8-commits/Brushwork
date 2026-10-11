package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.robolectric.RuntimeEnvironment
import java.io.File
import java.io.FileOutputStream

/**
 * v1.8 polish (top-menu clarity, request items 8 and 9): what the `Qa18PolishChrome*Test`
 * classes share. Each runs its sections on the user's phone (392 dp) and on a 360 dp phone
 * ([atEachWidth]), reads texts as they are laid out ([layout], [cut]) and renders the top of the
 * screen ([shot]: `polish-chrome-<name>.png`, 2 px per dp, through [Qa18MenusShots.capture]) on
 * the developer's machine only.
 */
internal object Qa18PolishChromeKit {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    /** The user's phone first, then a 360 dp phone (the design's narrowest, §3.9). */
    val WIDTHS = listOf(392 to "w392dp-h873dp-xxhdpi", 360 to "w360dp-h760dp-xxhdpi")

    /** Runs [block] in a section of [h] at each of [WIDTHS] (the screen's width in dp); back to 392 dp after. */
    fun atEachWidth(h: ChromeHarness, what: String, block: (screenDp: Int) -> Unit) {
        try {
            for ((dp, qualifiers) in WIDTHS) {
                RuntimeEnvironment.setQualifiers(qualifiers)
                h.section("$what at $dp dp") { block(dp) }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(WIDTHS.first().second)
        }
    }

    /**
     * Writes the top [heightDp] of the screen (every window, [Qa18MenusShots.capture]) as
     * `polish-chrome-[name].png`, 2 px per dp; the path, or null where renders are not written.
     */
    fun shot(s: ChromeScreen, name: String, heightDp: Float = 300f): String? {
        val shot = Qa18MenusShots.capture()
        if (DIR.parentFile?.isDirectory != true) return null
        DIR.mkdirs()
        val h = (heightDp * s.density).toInt().coerceAtMost(shot.height)
        val top = Bitmap.createBitmap(shot, 0, 0, shot.width, h)
        val scale = 2f / s.density
        val small = Bitmap.createBitmap((shot.width * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(small).apply {
            scale(scale, scale)
            drawBitmap(top, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        val f = File(DIR, "polish-chrome-$name.png")
        FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        System.err.println("[polish-chrome] wrote ${f.absolutePath}")
        return f.absolutePath
    }

    /** The placed node showing exactly [text] whose middle lies in [inside] (dp), or null. */
    fun textNode(s: ChromeScreen, text: String, inside: Rect? = null): SemanticsNode? = s.placed().map { it.node }.lastOrNull { n ->
        n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == text } == true && (inside == null || inside.contains(s.dp(n.boundsInWindow).center))
    }

    /** How [n]'s text is laid out now, or null when it has no text layout. */
    fun layout(n: SemanticsNode): TextLayoutResult? {
        val get = n.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action ?: return null
        val out = mutableListOf<TextLayoutResult>()
        return if (get(out) == true) out.firstOrNull() else null
    }

    /** Why [n]'s text does not show whole in its own width (cut, ellipsized, past its lines), or null when it does. */
    fun cut(n: SemanticsNode): String? {
        val r = layout(n) ?: return null
        val text = r.layoutInput.text.text
        val lines = 0 until r.lineCount
        val wider = lines.any { r.getLineRight(it) - r.getLineLeft(it) > n.size.width + 1f }
        val shortened = r.lineCount == 0 || r.getLineEnd(r.lineCount - 1) < text.length || lines.any { r.isLineEllipsized(it) }
        return if (wider || shortened) "\"$text\": lines ${lines.map { (r.getLineRight(it) - r.getLineLeft(it)).toInt() }} px in ${n.size.width} px" else null
    }

    /** The lines of [r] as shown (trimmed). */
    fun lines(r: TextLayoutResult): List<String> = (0 until r.lineCount).map { i ->
        r.layoutInput.text.text.substring(r.getLineStart(i), r.getLineEnd(i)).trim()
    }
}
