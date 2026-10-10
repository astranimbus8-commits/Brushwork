package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.item
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextKern
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream

/**
 * v1.7 item 17 (design §3.17) through the text editor dialog on the 392 dp phone, "AVATAR":
 * - The cursor between A and V names the gap ("Between “A” and “V”"); typed −200, the pair is a
 *   fifth of an em closer (the drawn text is narrower by about 0.2 em).
 * - "TA" selected names its one gap ("Between “T” and “A”"); 50 kerns the gap after T alone.
 * - "AVATA" selected covers gaps of −200, 0, 0 and 50: the row says "Mixed"; "+" moves each by
 *   10 and "−" moves them back.
 * - "Font kerning" off sets the letters by their plain advances; the kerns stay.
 * - Applied: a text layer keeping its kerns; its SVG is outlines (`<path>`, no `<text>`).
 * The three looks (unkerned, AV −200, font kerning off) and the placed layer are rendered to
 * chrome-kern-*.png. Kerning across two linked frames is TextKernThreadsRobolectricTest's.
 * One UI test, own sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.chromekerningsandbox"])
class Qa17ChromeKerningUiTest {
    private companion object {
        /** The type size of the saved renders (the measured ones are the text's own). */
        const val SHOT_PX = 72f
    }

    @Test
    fun kerningAvatarInTheTextDialog() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 90_000)
        val h = ChromeHarness()
        h.section("AVATAR") { avatar(h) }
        dog.interrupt()
        h.finish()
    }

    /** Puts the text field's cursor or selection at [start, end), as a finger does. */
    private fun selectText(start: Int, end: Int) {
        SmokeUi.field("Text").focus()
        settle(2)
        val set = SmokeUi.field("Text").node.config.getOrNull(SemanticsActions.SetSelection)?.action
            ?: throw AssertionError("the text field has no selection action")
        set(start, end, false)
        settle()
    }

    /**
     * [item] drawn alone on white, the size of the document; and the bounds of its ink. With
     * [name], a copy at [SHOT_PX] (kerns are in 1/1000 em, so they scale with it) is saved too.
     */
    private fun render(item: TextItem, w: Int, h: Int, name: String? = null): Rect {
        fun draw(it: TextItem): Bitmap = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888).also { b ->
            b.eraseColor(Color.WHITE)
            TextRenderer.drawItem(Canvas(b), it, TextRenderer.prepare(it), null)
        }
        if (name != null) Qa17Shots.save(draw(item.copy(spec = item.spec.copy(sizePx = SHOT_PX))), name)
        return ink(draw(item))
    }

    /** [b]'s [r] (and a margin), [k] times as large, pixels kept sharp. */
    private fun crop(b: Bitmap, r: Rect, k: Int): Bitmap {
        val m = 6
        val src = Rect((r.left - m).coerceAtLeast(0), (r.top - m).coerceAtLeast(0), (r.right + m).coerceAtMost(b.width), (r.bottom + m).coerceAtMost(b.height))
        val out = Bitmap.createBitmap(src.width() * k, src.height() * k, Bitmap.Config.ARGB_8888)
        Canvas(out).drawBitmap(b, src, Rect(0, 0, out.width, out.height), null)
        return out
    }

    private fun ink(b: Bitmap): Rect {
        var l = b.width; var t = b.height; var r = -1; var bt = -1
        for (y in 0 until b.height) for (x in 0 until b.width) {
            val p = b.getPixel(x, y)
            if (Color.alpha(p) > 0 && (Color.red(p) < 128 || Color.green(p) < 128 || Color.blue(p) < 128)) {
                if (x < l) l = x; if (x > r) r = x; if (y < t) t = y; if (y > bt) bt = y
            }
        }
        return Rect(l, t, r + 1, bt + 1)
    }

    private fun avatar(h: ChromeHarness) {
        val s = h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))
        val c = s.c
        val ui = Qa16Ui(s)
        ui.tool("Text")
        val text = c.currentTool as TextTool
        ui.tap(200f, 150f)
        assertTrue("the text editor", text.editorOpen)
        SmokeUi.field("Text").type("AVATAR")
        settle()
        val plain = text.item!!
        val em = plain.spec.sizePx
        val wide = render(plain, c.doc.width, c.doc.height, "kern-1-unkerned")

        // The cursor between A and V: that gap, typed -200.
        selectText(1, 1)
        assertTrue("the gap named: ${SmokeUi.shown().take(80)}", SmokeUi.has(KerningLabels.between("A", "V"), exact = true))
        SmokeUi.typeAndDone(KerningLabels.KERNING, "-200")
        assertEquals(listOf(TextKern(0, -200)), text.item!!.kerns)
        val tight = render(text.item!!, c.doc.width, c.doc.height, "kern-2-av-minus200")
        println("AVATAR at $em px: unkerned ${wide.width()} px wide, AV -200 ${tight.width()} px")
        assertEquals("a fifth of an em closer", 0.2f * em, (wide.width() - tight.width()).toFloat(), 0.05f * em)

        // "TA": its one gap, 50.
        selectText(3, 5)
        assertTrue("the gap named: ${SmokeUi.shown().take(80)}", SmokeUi.has(KerningLabels.between("T", "A"), exact = true))
        SmokeUi.typeAndDone(KerningLabels.KERNING, "50")
        assertEquals(listOf(TextKern(0, -200), TextKern(3, 50)), text.item!!.kerns)

        // "AVATA": four gaps that differ, "Mixed"; + and - move each by 10.
        selectText(0, 5)
        assertTrue("Mixed: ${SmokeUi.shown().take(80)}", SmokeUi.has(PointLabels.MIXED, exact = true))
        click("Increase ${KerningLabels.KERNING}", exact = true)
        assertEquals(listOf(TextKern(0, -190), TextKern(1, 10), TextKern(2, 10), TextKern(3, 60)), text.item!!.kerns)
        click("Decrease ${KerningLabels.KERNING}", exact = true)
        assertEquals("back", listOf(TextKern(0, -200), TextKern(3, 50)), text.item!!.kerns)

        // Font kerning off: plain advances, the kerns stay.
        assertTrue(text.item!!.spec.fontKerning)
        click(KerningLabels.FONT_KERNING, exact = true)
        assertFalse(text.item!!.spec.fontKerning)
        assertEquals(listOf(TextKern(0, -200), TextKern(3, 50)), text.item!!.kerns)
        val off = render(plain.copy(spec = plain.spec.copy(fontKerning = false)), c.doc.width, c.doc.height, "kern-3-font-kerning-off")
        println("AVATAR font kerning off: ${off.width()} px wide (on: ${wide.width()} px)")
        assertTrue("no narrower without the font's kerning (${off.width()} vs ${wide.width()})", off.width() >= wide.width())

        // Applied: a text layer with its kerns; its SVG is outlines.
        click("OK", exact = true)
        click("Apply text edit")
        assertTrue(Smoke.pumpUntil(10_000) { settle(1); !text.hasPendingWork && c.busyMessage == null })
        val layer = c.activeLayer
        assertTrue("a text layer", layer.isTextLayer)
        assertEquals(listOf(TextKern(0, -200), TextKern(3, 50)), layer.item().kerns)
        assertFalse(layer.item().spec.fontKerning)
        val shot = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        shot.eraseColor(Color.WHITE)
        Canvas(shot).drawBitmap(layer.bitmap, 0f, 0f, null)
        Qa17Shots.save(crop(shot, ink(shot), 5), "kern-4-placed-layer-x5")
        val scene = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = out.toString("UTF-8")
        assertTrue("outlines: a <path>", svg.contains("<path"))
        assertFalse("no <text>", svg.contains("<text"))
        Smoke.assertQuiet(c, "kerning")
    }
}
