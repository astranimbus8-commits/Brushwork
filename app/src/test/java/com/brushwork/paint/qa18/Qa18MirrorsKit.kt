package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Typeface
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.symmetry.SymmetryStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.hypot

/**
 * v1.8 final QA, item 1 (mirrors for Curve, Polyline, Path, Shape and the clone stamp): what the
 * `Qa18Mirrors*` UI tests share. The ruler is put on the way the user does it (the Symmetry tool,
 * its ruler dropdown, a value button's field, Done); the maps are the editor's own
 * ([com.brushwork.paint.vector.ObjectCopies.mapsAt]); renders go to the lead's QA folder as
 * `mirrors-<name>.png` (only where that folder's parent exists, never on CI).
 */
internal object Qa18MirrorsKit {
    private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

    /** A value of the ruler typed in its field: the strip's button (its current text), the field's label, the text. */
    class Value(val button: (com.brushwork.paint.model.SymmetrySettings) -> String, val field: String, val text: String)

    fun divisions(text: String) = Value(SymmetryStripItems::divisionsText, SymmetryLabels.DIVISIONS, text)

    fun spacingX(text: String) = Value({ SymmetryStripItems.spacingText(true, it) }, SymmetryStripItems.SPACING_X_LABEL, text)

    fun spacingY(text: String) = Value({ SymmetryStripItems.spacingText(false, it) }, SymmetryStripItems.SPACING_Y_LABEL, text)

    /**
     * The ruler [type] on, by finger: the Symmetry tool (picking it turns the mirror on), the ruler
     * dropdown opened through the current ruler's button and [type] picked in the menu's own
     * window, each of [values] typed in the field its button opens (closed with Back), each of
     * [drags] (a canvas handle dragged by a finger: from, to, document px), then Done.
     */
    fun rulerOn(ui: Qa16Ui, type: SymmetryType, values: List<Value> = emptyList(), drags: List<Pair<Vec2, Vec2>> = emptyList()) {
        val s = ui.s
        val c = s.c
        ui.tool("Symmetry")
        settle()
        assertNotEquals("the Symmetry tool turns a ruler on", SymmetryType.OFF, c.symmetry.type)
        if (c.symmetry.type != type) {
            val current = c.symmetry.type.label
            ui.reach(current, 32f)
            Finger.tap(s, current)
            settle()
            SmokeUi.click(type.label, exact = true)
            settle()
        }
        assertEquals("the ruler", type, c.symmetry.type)
        for (v in values) {
            val button = v.button(c.symmetry)
            ui.reach(button, 32f)
            click(button, exact = true)
            settle()
            SmokeUi.typeAndDone(v.field, v.text)
            Finger.back()
            settle()
        }
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        settle()
        for ((from, to) in drags) {
            val before = c.symmetry
            QaCurves.drag(s, from, Vec2(to.x - from.x, to.y - from.y))
            assertNotEquals("the handle at $from moved", before, c.symmetry)
        }
        click("Done", exact = true)
        settle()
        assertNotEquals("Done leaves the Symmetry tool", ToolId.SYMMETRY, c.activeToolId)
        assertEquals("still the ruler", type, c.symmetry.type)
    }

    /** The ruler off, by finger: the Symmetry tool, its ruler dropdown, "Off", Done. */
    fun rulerOff(ui: Qa16Ui) {
        val s = ui.s
        val c = s.c
        ui.tool("Symmetry")
        settle()
        val current = c.symmetry.type.label
        ui.reach(current, 32f)
        Finger.tap(s, current)
        settle()
        SmokeUi.click(SymmetryType.OFF.label, exact = true)
        settle()
        assertEquals("the ruler is off", SymmetryType.OFF, c.symmetry.type)
        if (StripTestKit.moreRowShown()) StripTestKit.closeMoreRow()
        click("Done", exact = true)
        settle()
        assertNotEquals("Done leaves the Symmetry tool", ToolId.SYMMETRY, c.activeToolId)
        assertEquals("still off", SymmetryType.OFF, c.symmetry.type)
    }

    /** The most opaque pixel of [b] within [r] px of [p] (0 off the bitmap). */
    fun inkNear(b: Bitmap, p: Vec2, r: Int): Int {
        var best = 0
        for (y in p.y.toInt() - r..p.y.toInt() + r) for (x in p.x.toInt() - r..p.x.toInt() + r) {
            if (x in 0 until b.width && y in 0 until b.height) best = maxOf(best, b.getPixel(x, y) ushr 24)
        }
        return best
    }

    /**
     * "Select several" (tag [V18Tags.PILL_SEVERAL]) ends the X / Y pill's first row: on screen,
     * finger-sized, on the fold cell's row, right of every other row-1 cell (fold; X, Y and
     * "Increments" unless [folded]; the trash cell), next to the last of them, nothing clipped;
     * the scale row (when there is one) under the row, none while [folded].
     */
    fun severalAtRowEnd(s: ChromeScreen, what: String, folded: Boolean) {
        val r = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "$what: no \"Select several\" cell; shown: ${SmokeUi.shown().take(80)}" }
        assertTrue("$what: on screen: $r", r.left >= 0f && r.right <= s.widthDp + 0.5f && r.top >= 0f && r.bottom <= s.heightDp + 0.5f)
        assertTrue("$what: a finger's size: $r", r.width >= 40f - 0.5f && r.height >= 40f - 0.5f)
        val fold = requireNotNull(Finger.control(s, if (folded) "Unfold the X / Y strip" else "Fold the X / Y strip")) { "$what: no fold cell" }
        assertEquals("$what: on the fold cell's row ($fold vs $r)", fold.center.y, r.center.y, 2f)
        val left = mutableListOf("fold" to fold)
        s.tagged(V17Tags.PILL_TRASH)?.let { left += "trash" to it }
        for (label in listOf("Type X", "Type Y", "Increments")) {
            val b = Finger.control(s, label)
            if (folded) {
                assertTrue("$what: folded, no \"$label\" on the row: $b", b == null || abs(b.center.y - r.center.y) > 2f)
            } else {
                requireNotNull(b) { "$what: no \"$label\"" }
                assertTrue("$what: \"$label\" shows whole ($b)", b.width >= 24f && b.left >= 0f && b.right <= s.widthDp + 0.5f)
                left += label to b
            }
        }
        for ((name, b) in left) assertTrue("$what: $name ($b) left of the cell ($r)", b.right <= r.left + 0.5f)
        val gap = r.left - left.maxOf { it.second.right }
        assertTrue("$what: right next to the last cell (gap $gap dp)", gap in -0.5f..12f)
        val scale = s.tagged(V17Tags.PILL_SCALE_ROW)
        if (folded) assertNull("$what: folded, no scale row", scale)
        else if (scale != null) assertTrue("$what: the scale row ($scale) under the row ($fold)", scale.top >= fold.bottom - 0.5f)
        println("Qa18Mirrors $what: cell $r, row-1 cells ${left.map { it.first }}, scale row ${scale != null}")
    }

    // ------------------------------------------------------------------ pictures

    /** A smooth color gradient over the whole of [bmp] (every pixel says where it is). */
    fun gradient(x: Int, y: Int, w: Int, h: Int): Int = Color.rgb(x * 255 / (w - 1), y * 255 / (h - 1), 150)

    /**
     * [layer] seeded in code (one "Seed" step): the [gradient] everywhere, and a black "R" centred
     * on [at] drawn through each of [maps] (the letter where the ruler's copies would have it).
     */
    fun seedLettered(c: EditorController, layer: Layer, maps: List<FloatArray>, at: Vec2, size: Float = 56f) {
        assertTrue(
            "the seed",
            c.editWholeLayer(layer, "Seed") { bmp ->
                val w = bmp.width
                val h = bmp.height
                bmp.setPixels(IntArray(w * h) { gradient(it % w, it / w, w, h) }, 0, w, 0, 0, w, h)
                val cv = Canvas(bmp)
                val p = Paint(Paint.ANTI_ALIAS_FLAG).apply { color = Color.BLACK; textSize = size; typeface = Typeface.DEFAULT_BOLD }
                val tw = p.measureText("R")
                for (m in maps.ifEmpty { listOf(IDENTITY) }) {
                    cv.save()
                    cv.concat(Matrix().apply { setValues(m) })
                    cv.drawText("R", at.x - tw / 2f, at.y + size * 0.36f, p)
                    cv.restore()
                }
            },
        )
        settle()
    }

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** The document flattened over white. */
    fun flat(c: EditorController): Bitmap {
        val d = c.doc
        val bmp = Bitmap.createBitmap(d.width, d.height, Bitmap.Config.ARGB_8888)
        bmp.eraseColor(Color.WHITE)
        val cv = Canvas(bmp)
        val p = Paint(Paint.FILTER_BITMAP_FLAG)
        for (l in d.layers) if (l.visible) {
            p.alpha = (l.opacity * 255).toInt().coerceIn(0, 255)
            cv.drawBitmap(l.bitmap, 0f, 0f, p)
        }
        return bmp
    }

    /** `mirrors-<name>.png` (the document) and `mirrors-<name>-screen.png` (the editor window). */
    fun shoot(s: ChromeScreen, name: String): List<String> {
        val out = mutableListOf<String>()
        png(flat(s.c), name)?.let { out += it }
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
        val f = File(SHOTS, "mirrors-$name.png")
        FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
        println("Qa18Mirrors: wrote ${f.absolutePath}")
        return f.absolutePath
    }

    // ------------------------------------------------------------------ geometry

    val IDENTITY = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    fun isIdentity(m: FloatArray) = m.indices.all { abs(m[it] - IDENTITY[it]) < 1e-5f }

    /** [p] mapped by [m] (row-major 3 × 3), or null at or behind the horizon. */
    fun map(m: FloatArray, x: Float, y: Float): Vec2? {
        val w = m[6] * x + m[7] * y + m[8]
        if (!(w > 1e-6f)) return null
        return Vec2((m[0] * x + m[1] * y + m[2]) / w, (m[3] * x + m[4] * y + m[5]) / w)
    }

    fun map(m: FloatArray, p: Vec2): Vec2? = map(m, p.x, p.y)

    /** The inverse of [m] (row-major 3 × 3). */
    fun inverse(m: FloatArray): FloatArray {
        val a = DoubleArray(9) { m[it].toDouble() }
        val c00 = a[4] * a[8] - a[5] * a[7]
        val c01 = a[5] * a[6] - a[3] * a[8]
        val c02 = a[3] * a[7] - a[4] * a[6]
        val det = a[0] * c00 + a[1] * c01 + a[2] * c02
        val r = doubleArrayOf(
            c00, a[2] * a[7] - a[1] * a[8], a[1] * a[5] - a[2] * a[4],
            c01, a[0] * a[8] - a[2] * a[6], a[2] * a[3] - a[0] * a[5],
            c02, a[1] * a[6] - a[0] * a[7], a[0] * a[4] - a[1] * a[3],
        )
        return FloatArray(9) { (r[it] / det).toFloat() }
    }

    /** Distance from [p] to segment [a]–[b], and where along it (0..1) its foot lies. */
    fun toSegment(p: Vec2, a: Vec2, b: Vec2): Pair<Float, Float> {
        val dx = b.x - a.x
        val dy = b.y - a.y
        val len2 = dx * dx + dy * dy
        val t = if (len2 == 0f) 0f else (((p.x - a.x) * dx + (p.y - a.y) * dy) / len2)
        val tc = t.coerceIn(0f, 1f)
        return hypot(p.x - (a.x + tc * dx), p.y - (a.y + tc * dy)) to t
    }

    /** The smaller of [m]'s two local scales at [p] (how much a copy shrinks there), 0 at the horizon. */
    fun localScale(m: FloatArray, p: Vec2): Float {
        val o = map(m, p) ?: return 0f
        val ex = map(m, p.x + 1f, p.y) ?: return 0f
        val ey = map(m, p.x, p.y + 1f) ?: return 0f
        return minOf(hypot(ex.x - o.x, ex.y - o.y), hypot(ey.x - o.x, ey.y - o.y))
    }

    fun channelsClose(a: Int, b: Int, tol: Int): Boolean =
        abs((a ushr 24) - (b ushr 24)) <= tol && abs(((a shr 16) and 0xFF) - ((b shr 16) and 0xFF)) <= tol &&
            abs(((a shr 8) and 0xFF) - ((b shr 8) and 0xFF)) <= tol && abs((a and 0xFF) - (b and 0xFF)) <= tol

    fun luminance(c: Int): Int = (((c shr 16) and 0xFF) + ((c shr 8) and 0xFF) + (c and 0xFF)) / 3
}
