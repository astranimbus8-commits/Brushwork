package com.brushwork.paint.qa3

import androidx.activity.ComponentActivity
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeTags

/**
 * The canvas the chrome leaves free, measured on screen (v1.7): below the lowest of the top row,
 * the options strip, the X / Y pill and the selection bar, above the highest of the ✓ / ✕, the
 * slider rows and the bottom bar. Measured, not assumed: what shows over the canvas depends on
 * the tool (the pill's Scale row moves the selection bar down to 209–249 dp on the phone).
 */
internal object Qa3FreeCanvas {
    private val above = listOf(ChromeTags.TOP_ROW, ChromeTags.OPTIONS_STRIP, ChromeTags.PILL_SLOT, ChromeTags.SELECTION_BAR)
    private val below = listOf(ChromeTags.PENDING_BAR, ChromeTags.SLIDER_ROWS, ChromeTags.BOTTOM_BAR)

    /** Bounds (window px) of what is placed in [activity]'s window and tagged one of [tags]. */
    private fun chrome(activity: ComponentActivity, tags: List<String>): List<Rect> = RobolectricUi.elements()
        .filter { e -> e.window === activity.window.decorView && e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) in tags }
        .map { it.bounds }
        .filter { it.height > 0f && it.width > 0f }

    /**
     * The corner of the free canvas (window px; [inset] dp in from the canvas' sides and from the
     * chrome) farthest from document point ([x], [y]).
     */
    fun farFrom(activity: ComponentActivity, c: EditorController, x: Float, y: Float, inset: Float = 40f): Pair<Float, Float> {
        val d = activity.resources.displayMetrics.density
        val v = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas view")
        val loc = IntArray(2)
        v.getLocationInWindow(loc)
        val top = chrome(activity, above).maxOfOrNull { it.bottom } ?: throw AssertionError("no top chrome on screen")
        val bottom = chrome(activity, below).minOfOrNull { it.top } ?: throw AssertionError("no bottom chrome on screen")
        val l = loc[0] + inset * d
        val r = loc[0] + v.width - inset * d
        val t = maxOf(top, loc[1].toFloat()) + inset * d
        val b = minOf(bottom, (loc[1] + v.height).toFloat()) - inset * d
        if (t >= b || l >= r) throw AssertionError("no free canvas between the chrome: x $l..$r, y $t..$b")
        val p = c.viewTransform.docToScreen(x, y)
        val px = p.x + loc[0]
        val py = p.y + loc[1]
        return listOf(l to t, r to t, l to b, r to b).maxBy { (cx, cy) -> (cx - px) * (cx - px) + (cy - py) * (cy - py) }
    }
}
