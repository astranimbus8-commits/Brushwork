package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Color
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v16 polish 2: the four flips of the layer window draw ibisPaint's glyph, two solid triangles
 * pointing at a bar (▸|◂; the vertical flips the same turned 90°: ▾ over a bar over ▴), instead
 * of Material's "Flip" (an outlined half, a bar and a dotted half). Sampled on a real Skia render
 * of the open window, on the 24-unit grid of each 24 dp glyph centred in its cell:
 * - horizontal: ink in both triangles and along the bar; nothing at the upper corners, where
 *   Material's outlined and dotted halves have their corners;
 * - vertical: ink in both triangles and along the bar; nothing at the left corners.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishflipsandbox"])
class PolishFlipGlyphsTest {

    private fun white(c: Int) = Color.red(c) >= 0xE8 && Color.green(c) >= 0xE8 && Color.blue(c) >= 0xE8
    private fun dark(c: Int) = Color.red(c) <= 0x60 && Color.green(c) <= 0x60 && Color.blue(c) <= 0x60

    @Test
    fun theFourFlipsShowIbisPaintsTrianglesAndBar() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("flip glyphs") {
            val s = h.editor(IbisDocs.page(), IbisDocs::fourKinds)
            click("Open layers (active layer 5)")
            Smoke.pump(1_200)
            settle()
            val shot = IbisShots.capture()
            val bad = mutableListOf<String>()

            fun glyph(label: String, vararg points: Triple<Float, Float, Boolean>) {
                val r = Finger.control(s, label) ?: run { bad += "no \"$label\""; return }
                for ((u, v, ink) in points) {
                    val c = sample(s, shot, r.center.x + (u - 12f), r.center.y + (v - 12f))
                    if (ink && !white(c)) bad += "$label ($u, $v): ink expected, ${IbisShots.hex(c)}"
                    if (!ink && !dark(c)) bad += "$label ($u, $v): background expected, ${IbisShots.hex(c)}"
                }
            }

            val horizontal = arrayOf(
                Triple(5.5f, 12f, true), // the left triangle (▸)
                Triple(18.5f, 12f, true), // the right triangle (◂)
                Triple(12f, 4f, true), // the bar, top
                Triple(12f, 20f, true), // the bar, bottom
                Triple(4f, 3.5f, false), // above the left triangle
                Triple(20f, 3.5f, false), // above the right triangle
                Triple(4f, 20.5f, false), // under the left triangle
            )
            val vertical = arrayOf(
                Triple(12f, 5.5f, true), // the upper triangle (▾)
                Triple(12f, 18.5f, true), // the lower triangle (▴)
                Triple(4f, 12f, true), // the bar, left
                Triple(20f, 12f, true), // the bar, right
                Triple(3.5f, 4f, false), // left of the upper triangle
                Triple(3.5f, 20f, false), // left of the lower triangle
                Triple(20.5f, 4f, false), // right of the upper triangle
            )
            glyph(LayerLabels.FLIP_CANVAS_H, *horizontal)
            glyph(LayerLabels.FLIP_H, *horizontal)
            glyph(LayerLabels.FLIP_CANVAS_V, *vertical)
            glyph(LayerLabels.FLIP_V, *vertical)
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }

    /** The render's colour at ([x], [y]) dp of the editor's root. */
    private fun sample(s: ChromeScreen, shot: Bitmap, x: Float, y: Float): Int =
        IbisShots.at(shot, s.density, x + s.root.left / s.density, y + s.root.top / s.density)
}
