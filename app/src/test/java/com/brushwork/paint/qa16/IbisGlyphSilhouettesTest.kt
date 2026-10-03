package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Color
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * The main screen's glyphs drawn with ibisPaint's silhouettes (v1.6 §3.7: "The top row follows
 * ibisPaint's icon silhouettes"), sampled on a real Skia render at the points that tell them
 * apart from the Material stand-ins they replaced:
 * - Stabilizer: the pointing hand's fingertip with a tap mark beside it;
 * - Grid: the square at the upper left, the circle at the lower right;
 * - Ruler: one diagonal ruler from lower left to upper right, its upper-left corner empty;
 * - More options: the picture with a solid sun;
 * - the layers glyph: the number tile at the upper left, the stacked squares at its lower right
 *   (the lower-left corner is empty).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.glyphsandbox"])
class IbisGlyphSilhouettesTest {

    private fun white(c: Int) = Color.red(c) >= 0xE8 && Color.green(c) >= 0xE8 && Color.blue(c) >= 0xE8
    private fun dark(c: Int) = Color.red(c) <= 0xC0 && Color.green(c) <= 0xC0 && Color.blue(c) <= 0xC0

    @Test
    fun theMainScreenGlyphsHaveIbisPaintsSilhouettes() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("glyph silhouettes") {
            val s = h.editor(Smoke.document(300, 430, layers = 2, whiteBottom = true))
            val shot = IbisShots.capture()
            val bad = mutableListOf<String>()

            /** Checks the colour at ([u], [v]) of the 24-unit glyph grid of a [sizeDp] glyph centred in the control [label]. */
            fun glyph(label: String, sizeDp: Float, vararg points: Triple<Float, Float, Boolean>) {
                val r = Finger.control(s, label, exact = false) ?: run { bad += "no \"$label\""; return }
                val k = sizeDp / 24f
                for ((u, v, ink) in points) {
                    val c = sample(s, shot, r.center.x + (u - 12f) * k, r.center.y + (v - 12f) * k)
                    if (ink && !white(c)) bad += "$label ($u, $v): ink expected, ${IbisShots.hex(c)}"
                    if (!ink && !dark(c)) bad += "$label ($u, $v): background expected, ${IbisShots.hex(c)}"
                }
            }

            val top = 22f // IbisDims.TopGlyph
            glyph(
                "Stabilizer", top,
                Triple(8.25f, 2.75f, true), // the fingertip
                Triple(3.7f, 4.5f, true), // the tap mark at its left
                Triple(18.5f, 5f, false), // nothing above the folded fingers
            )
            glyph(
                "Grid", top,
                Triple(3.5f, 6f, true), // the square's left side, upper half
                Triple(20.5f, 15f, true), // the circle's right side, lower half
                Triple(3.5f, 18.5f, false), // nothing at the lower left
                Triple(20.5f, 6f, false), // nothing at the upper right
            )
            glyph(
                "Ruler", top,
                Triple(12f, 12f, true), // the slot along the ruler's middle
                Triple(6f, 6f, false), // the upper-left corner is empty
                Triple(18f, 18f, false), // the lower-right corner is empty
            )
            glyph(
                "More options", top,
                Triple(8.6f, 8.6f, true), // the solid sun
                Triple(12f, 17.6f, true), // the solid hills
            )
            // The layers glyph (IbisDims.BottomGlyph + 4 = 30 dp box) in dp of its box.
            val layers = Finger.control(s, "Open layers", exact = false)
            if (layers == null) bad += "no layers button" else {
                val x0 = layers.center.x - 15f
                val y0 = layers.center.y - 15f
                val tile = sample(s, shot, x0 + 5f, y0 + 5f)
                if (!white(tile)) bad += "layers: the tile's upper-left corner ${IbisShots.hex(tile)}"
                val lowerLeft = sample(s, shot, x0 + 5f, y0 + 25f)
                if (!dark(lowerLeft)) bad += "layers: the lower-left corner is empty ${IbisShots.hex(lowerLeft)}"
                val squares = sample(s, shot, x0 + 15f, y0 + 26.25f)
                if (!white(squares)) bad += "layers: the back square's lower edge ${IbisShots.hex(squares)}"
            }
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }

    /** The render's colour at ([x], [y]) dp of the editor's root. */
    private fun sample(s: ChromeScreen, shot: Bitmap, x: Float, y: Float): Int =
        IbisShots.at(shot, s.density, x + s.root.left / s.density, y + s.root.top / s.density)
}
