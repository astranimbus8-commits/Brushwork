package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream

/**
 * The v1.7 final QA of multi-point editing, sharp points, point thickness, prepend, Stroke /
 * Fill / Both and moving and scaling whole curves (items 1, 4, 5, 7, 12, 19): the editor at the
 * user's phone size with snapping off, the user's own moves (finger taps and drags in document
 * px, strip controls by their labels), what the canvas shows (the composite, overlays left out)
 * and PNG renders for the lead (`points-*.png`, written only where the QA tools folder exists).
 */
internal class PointsQa(private val h: ChromeHarness) {
    lateinit var s: ChromeScreen
    lateinit var ui: Qa16Ui
    val c: EditorController get() = s.c

    /** The editor on a 400 × 300 document: a white bottom layer under an empty one ([vector]: a vector layer). */
    fun editor(widthDp: Float = 392f, vector: Boolean = false): ChromeScreen {
        val doc = Smoke.document(400, 300, layers = 2, whiteBottom = true)
        if (vector) doc.layers.last().vector = VectorContent.EMPTY
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the phone is ${widthDp.toInt()} dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    fun tool(label: String): CurveTool {
        ui.tool(label)
        settle()
        return c.currentTool as CurveTool
    }

    fun tap(x: Float, y: Float) = ui.tap(x, y)

    /** Brings the control [label] wholly into view (sliding its strip), then taps it. */
    fun press(label: String, minDp: Float = 32f) {
        ui.reach(label, minDp)
        click(label, exact = true)
    }

    /** "Apply …" in the top bar, then waits until the path has landed. */
    fun apply(label: String) {
        click(label)
        assertTrue("$label: done", Smoke.pumpUntil(WAIT_MS) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering })
        settle()
    }

    /** Lets posted work (the live brush replay) run, as the looper does between frames. */
    fun idle() {
        s.touch.idle(100)
        Smoke.pump(60)
        settle(2)
    }

    companion object {
        /** How long a render may take: 10 s on a desktop, scaled on CI. */
        val WAIT_MS: Long = PerfBudget.ms(10_000.0).toLong()

        /**
         * The in-tool steps of [t] (undone and redone again; the selection put back as it was:
         * undoing down to no points forgets it, as in v1.6).
         */
        fun steps(t: CurveTool): Int {
            val sel = t.pointSelection
            var n = 0
            while (t.undoStep()) n++
            repeat(n) { t.redoStep() }
            t.selectPoints(sel)
            settle(2)
            return n
        }

        /** What the canvas shows of the document (the composite with the live previews; no guides). */
        fun composite(c: EditorController): Bitmap {
            val b = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
            c.compositor.drawDocument(Canvas(b), null, true, CompositeTarget.identity(b))
            return b
        }

        /** The editor window as the user sees it. */
        fun window(s: ChromeScreen): Bitmap {
            val v = s.activity.window.decorView
            val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
            v.draw(Canvas(b))
            return b
        }

        private fun lum(p: Int) = (Color.red(p) + Color.green(p) + Color.blue(p)) / 3f

        /**
         * The thickness (px) of [ink] over white in column [x] of [b], rows [top] until [bottom]:
         * each pixel counts by how far it went from white towards [ink] (edges partly).
         */
        fun thickness(b: Bitmap, x: Int, ink: Int, top: Int = 0, bottom: Int = b.height): Float {
            val full = 255f - lum(ink)
            var sum = 0f
            for (y in top until bottom) sum += ((255f - lum(b.getPixel(x, y))) / full).coerceIn(0f, 1f)
            return sum
        }

        /** Pixels of [b] that are not white (any ink) in [l, r) × [t, b). */
        fun inked(b: Bitmap, l: Int, t: Int, r: Int, bo: Int): Int {
            var n = 0
            for (y in t until bo) for (x in l until r) if (lum(b.getPixel(x, y)) < 250f) n++
            return n
        }

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v17-qa-shots")

        /** Writes [b] to `points-<name>.png` for the lead; the path, or null on other machines. */
        fun save(b: Bitmap, name: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, "points-$name.png")
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            println("QA17 points render: ${f.absolutePath}")
            return f.absolutePath
        }

        /** Whether [label] is on screen (exact). */
        fun shows(label: String) = SmokeUi.has(label, exact = true)
    }
}
