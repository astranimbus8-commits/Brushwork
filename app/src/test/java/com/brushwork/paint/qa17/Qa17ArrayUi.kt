package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.Selection
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs

/**
 * v1.7 final QA, cluster "array" (items 3, 11 and 16: the live Array, transforming data layers
 * without rasterizing, Free deform): the user's own moves on the full editor by the labels and
 * tags the user sees (I10). Setting up what a flow starts from (pixels, a text or shape layer) is
 * done in code; the flow itself goes through the screen and real touches.
 */
internal class Qa17ArrayUi(private val h: ChromeHarness) {
    lateinit var s: ChromeScreen
        private set
    lateinit var ui: Qa16Ui
        private set
    val c: EditorController get() = s.c

    fun editor(doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true), widthDp: Float = 392f): ChromeScreen {
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the phone is $widthDp dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    /** Picks [label] in the tool menu, scrolling it as a finger would. */
    fun tool(label: String) {
        ui.tool(label)
        settle()
    }

    /** Brings the control [label] wholly into view (scrolling its strip or sheet), then taps it. */
    fun press(label: String, minDp: Float = 32f) {
        ui.reach(label, minDp)
        click(label, exact = true)
    }

    fun steps(): Int = c.undoManager.undoCount

    fun seed(layer: Layer, l: Float, t: Float, r: Float, b: Float, color: Int) {
        assertTrue(c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) })
        settle()
    }

    fun rectSelection(l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    /** Every render on its way has landed (a vector array, a background array cache, a busy overlay). */
    fun settleRenders(where: String) {
        assertTrue(
            "$where: renders landed",
            Smoke.pumpUntil(WAIT_MS) { settle(1); !c.vectors.isRendering && !c.arrayRenders.isPending && c.busyMessage == null },
        )
        settle()
    }

    /** Taps the floating ✓ ("Apply <tool> edit") and waits for what it starts. */
    fun applyEdit(label: String) {
        click(label, exact = true)
        assertTrue("$label: done", Smoke.pumpUntil(WAIT_MS) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending })
        settle()
    }

    fun openLayers() {
        click("Open layers (active layer")
        Smoke.pump(600)
        settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    fun closeLayers() {
        click("Close layers", exact = true)
        Smoke.pump(400)
        settle()
    }

    /** The active layer's ⋮ menu in the layer window: [item] tapped; the window closed again. */
    fun layerMenu(item: String) {
        openLayers()
        click(LayerLabels.MORE, exact = true)
        settle()
        click(item, exact = true)
        settle()
        if (SmokeUi.has("Close layers", exact = true)) closeLayers()
    }

    /** The state description of [label]'s node, or of its nearest clickable ancestor. */
    fun stateOf(label: String): String? {
        var n: SemanticsNode? = SmokeUi.find(label, exact = true)?.node
        n?.config?.getOrNull(SemanticsProperties.StateDescription)?.let { return it }
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n?.config?.getOrNull(SemanticsProperties.StateDescription)
    }

    /** Where document point ([x], [y]) is on screen, in dp of the editor (for messages). */
    fun dpOf(x: Float, y: Float): Pair<Float, Float> {
        val (sx, sy) = s.screen(x, y)
        val r = s.root
        return ((sx - r.left) / s.density) to ((sy - r.top) / s.density)
    }

    /** Whether document point ([x], [y]) shows on the canvas: inside the canvas view, not under an open sheet. */
    fun onCanvas(x: Float, y: Float): Boolean {
        val (sx, sy) = s.screen(x, y)
        val loc = IntArray(2).also { s.canvas.getLocationInWindow(it) }
        if (sx < loc[0] || sy < loc[1] || sx > loc[0] + s.canvas.width || sy > loc[1] + s.canvas.height) return false
        val panel = SmokeUi.sheetPanel()?.bounds ?: return true
        return !(sx >= panel.left && sx <= panel.right && sy >= panel.top && sy <= panel.bottom)
    }

    /** One composed render of every window, saved as `array-[name].png` in the QA shots folder (none elsewhere). */
    fun shot(name: String): String? = runCatching { save(IbisShots.capture(), "array-$name.png") }.getOrNull()

    companion object {
        /** How long a render or a computed result may take: 10 s on a desktop, scaled on CI. */
        val WAIT_MS: Long = PerfBudget.ms(10_000.0).toLong()

        const val RED = 0xFFDD2211.toInt()
        const val BLUE = 0xFF2244CC.toInt()

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v17-qa-shots")

        /** Writes [b] as [file] in the QA shots folder when this machine has it; the path, or null. */
        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }

        fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

        fun count(layer: Layer, color: Int): Int = pixels(layer.bitmap).count { it == color }

        /** Pixels of [b] in [r] with alpha above [minAlpha]. */
        fun ink(b: Bitmap, r: RectF, minAlpha: Int = 0): Int {
            var n = 0
            val l = r.left.toInt().coerceIn(0, b.width)
            val t = r.top.toInt().coerceIn(0, b.height)
            val rr = r.right.toInt().coerceIn(0, b.width)
            val bb = r.bottom.toInt().coerceIn(0, b.height)
            for (y in t until bb) for (x in l until rr) if (b.getPixel(x, y) ushr 24 > minAlpha) n++
            return n
        }

        /**
         * [d] (a text, shape or raster array) rendered from scratch into a [w] × [h] bitmap, as
         * every cache writer draws it (I1, I14): the oracle a layer's cache must equal.
         */
        fun freshRender(c: EditorController, d: LayerData): Bitmap {
            val out = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
            val cv = Canvas(out)
            val a = d.array
            if (a?.pixels != null) {
                ArrayDraw.drawPixels(cv, a)
            } else {
                val draw = requireNotNull(ArraySources.sourceDraw(d, c.doc.colorMode, c.doc.width, c.doc.height)) { "no source to draw" }
                val bounds = requireNotNull(ArrayDraw.sourceBounds(d)) { "no source bounds" }
                ArrayDraw.drawWithArray(cv, a, bounds, draw)
            }
            return out
        }

        /** Pixels of [a] and [b] that differ by more than [tol] in a channel. */
        fun differing(a: Bitmap, b: Bitmap, tol: Int = 2): Int {
            val pa = pixels(a)
            val pb = pixels(b)
            var n = 0
            for (i in pa.indices) {
                val x = pa[i]
                val y = pb[i]
                if (x == y) continue
                for (sh in intArrayOf(24, 16, 8, 0)) if (abs(((x ushr sh) and 0xFF) - ((y ushr sh) and 0xFF)) > tol) { n++; break }
            }
            return n
        }
    }
}
