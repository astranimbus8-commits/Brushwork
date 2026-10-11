package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.vector.VectorContent
import java.io.File
import java.io.FileOutputStream

/**
 * v1.8 final QA (items 8 and 9): what the user SEES of the top menus, rendered through real Skia
 * (`graphicsMode=NATIVE`) from every window of the editor (popups and dialogs where the window
 * manager put them, drawn through a software layer as on the phone), and written to the QA shots
 * folder on the developer's machine as `menus-<name>.png` (cropped to the top of the screen, 2 px
 * per dp). Nothing is written on other hosts (CI on Linux).
 */
internal object Qa18MenusShots {
    private val DIR = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    /** Whether renders are written here (the developer's machine). */
    val enabled: Boolean get() = DIR.parentFile?.isDirectory == true

    /** A 400 × 300 document: a white bottom layer, a raster layer, and with [vector] a vector layer on top, active. */
    fun doc(vector: Boolean, dark: Boolean = false): Document = Smoke.document(400, 300, layers = 2, whiteBottom = true).also { d ->
        if (dark) d.layers[0].bitmap.eraseColor(0xFF141414.toInt())
        if (vector) {
            d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
            d.activeLayerIndex = 2
        }
    }

    /**
     * Picks [id] as a finger does: the bottom bar's tool button opens the tool menu, and its cell
     * (the tool's label, inside the menu) is tapped (a cell scrolled below the menu's fold gets
     * its click action, what a tap does once it is scrolled into view). Windows the tool opens as
     * it is picked are closed.
     */
    fun pick(s: ChromeScreen, id: ToolId) {
        val menuButton = Finger.control(s, "Tools (current: ", exact = false) ?: throw AssertionError("no tool menu button")
        Finger.tapAt(s, menuButton.center.x, menuButton.center.y)
        Smoke.pump(200); SmokeUi.settle()
        val menu = s.tagged(ChromeTags.TOOL_MENU) ?: throw AssertionError("the tool menu did not open")
        val cell = s.placed().map { it.node }.firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == id.label } == true && menu.contains(s.dp(n.boundsInWindow).center)
        } ?: s.placed().map { it.node }.firstOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Text)?.any { it.text == id.label } == true &&
                generateSequence(n) { it.parent }.any { p -> p.config.getOrNull(SemanticsProperties.TestTag) == ChromeTags.TOOL_MENU }
        } ?: throw AssertionError("no \"${id.label}\" cell in the tool menu")
        val r = s.dp(cell.boundsInWindow)
        if (r.height >= 20f && menu.contains(r.center)) {
            Finger.tapAt(s, r.center.x, r.center.y)
        } else {
            var n: androidx.compose.ui.semantics.SemanticsNode? = cell
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            n?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke() ?: throw AssertionError("\"${id.label}\" takes no tap")
            SmokeUi.settle()
        }
        Smoke.pump(200); SmokeUi.settle()
        if (s.tagged(ChromeTags.TOOL_MENU) != null) {
            // The menu stays open after a pick on a phone? Close it as a user would (a tap on its button).
            Finger.tapAt(s, menuButton.center.x, menuButton.center.y)
            Smoke.pump(200); SmokeUi.settle()
        }
        repeat(3) { if (SmokeUi.windows().size > 1) Finger.back() }
        check(s.c.activeToolId == id) { "${id.label} was not picked (${s.c.activeToolId})" }
    }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } + v else listOf(v)

    /**
     * Every window of the screen composed into one bitmap the size of the editor's window: the
     * editor first, then each popup or dialog where the window manager put it (with its dim). A
     * popup's compose view draws nothing into a bitmap through its default layer under
     * Robolectric, so the other windows draw through software layers (as `OpaqueMenusTest` does).
     */
    fun capture(): Bitmap {
        Smoke.pump(200); SmokeUi.settle()
        RobolectricUi.elements()
        val roots = SmokeUi.windows()
        require(roots.isNotEmpty()) { "no window to render" }
        val main = roots.first()
        val out = Bitmap.createBitmap(main.width, main.height, Bitmap.Config.ARGB_8888)
        out.eraseColor(Color.BLACK)
        val canvas = Canvas(out)
        val screen = Rect(0, 0, main.width, main.height)
        for ((i, v) in roots.withIndex()) {
            if (v.width <= 0 || v.height <= 0) continue
            var x = 0
            var y = 0
            val soft = if (i > 0) allViews(v) - v else emptyList()
            if (i > 0) {
                val lp = v.layoutParams as? WindowManager.LayoutParams
                if (lp != null) {
                    if (lp.flags and WindowManager.LayoutParams.FLAG_DIM_BEHIND != 0) {
                        canvas.drawColor(Color.argb((lp.dimAmount * 255).toInt().coerceIn(0, 255), 0, 0, 0))
                    }
                    val r = Rect()
                    val g = if (lp.gravity == 0) Gravity.TOP or Gravity.START else lp.gravity
                    Gravity.apply(g, v.width, v.height, screen, lp.x, lp.y, r, View.LAYOUT_DIRECTION_LTR)
                    x = r.left
                    y = r.top
                }
                soft.forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null); it.invalidate() }
                v.invalidate()
                Smoke.pump(100); SmokeUi.settle()
            }
            val layer = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(layer))
            soft.forEach { it.setLayerType(View.LAYER_TYPE_NONE, null) }
            canvas.drawBitmap(layer, x.toFloat(), y.toFloat(), null)
        }
        return out
    }

    /**
     * Writes the top [heightDp] of [shot] (a [density] screen) as `menus-[name].png`, 2 px per dp;
     * the path, or null where renders are not written.
     */
    fun save(shot: Bitmap, density: Float, name: String, heightDp: Float = 420f): String? {
        if (!enabled) return null
        DIR.mkdirs()
        val h = (heightDp * density).toInt().coerceAtMost(shot.height)
        val top = Bitmap.createBitmap(shot, 0, 0, shot.width, h)
        val scale = 2f / density
        val small = Bitmap.createBitmap((shot.width * scale).toInt(), (h * scale).toInt(), Bitmap.Config.ARGB_8888)
        Canvas(small).apply {
            scale(scale, scale)
            drawBitmap(top, 0f, 0f, Paint(Paint.FILTER_BITMAP_FLAG or Paint.ANTI_ALIAS_FLAG))
        }
        val f = File(DIR, "menus-$name.png")
        FileOutputStream(f).use { small.compress(Bitmap.CompressFormat.PNG, 100, it) }
        return f.absolutePath
    }
}
