package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Color
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.state.ToggleableState
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.theme.IbisColors
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v16 polish 3: the blend row's three toggles are icons only, like ibisPaint's (the captions
 * "Clipping", "α lock" and "Lock" no longer show under them), each icon centred in its 48 dp
 * cell. What they say does not change (I10): each toggle is still one 48 × 48 switch known by
 * the same strings as before (Clipping: "Clipping"; alpha lock: "Alpha lock" and "α lock"; lock:
 * "Lock layer" and "Lock"), with the same description and on / off state; the on state still
 * fills the cell with the accent.
 *
 * Rendered with the active layer alpha-locked (set before the window opens): no white ink in
 * the band 34–42 dp down each cell (where the 9 sp caption sat; the icons end above it), the
 * ink's top and bottom about the cell's middle, the α toggle's cell in the accent.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishblendsandbox"])
class PolishBlendRowIconsTest {

    private fun white(c: Int) = Color.red(c) >= 0xC0 && Color.green(c) >= 0xC0 && Color.blue(c) >= 0xC0

    private class Toggle(val label: String, val description: List<String>, val strings: Set<String>, val on: Boolean)

    @Test
    fun theBlendTogglesAreCentredIconsThatSayTheSame() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("blend row icons") {
            val s = h.editor(IbisDocs.page()) { c ->
                IbisDocs.fourKinds(c)
                c.doc.layers.last().alphaLocked = true
                c.notifyLayersChanged()
            }
            click("Open layers (active layer 5)")
            Smoke.pump(1_200)
            settle()
            val shot = IbisShots.capture()
            val bad = mutableListOf<String>()
            val toggles = listOf(
                Toggle(LayerLabels.CLIPPING, emptyList(), setOf("Clipping"), on = false),
                Toggle(LayerLabels.ALPHA_LOCK, listOf("Alpha lock"), setOf("Alpha lock", "α lock"), on = true),
                Toggle(LayerLabels.LOCK, listOf("Lock layer"), setOf("Lock layer", "Lock"), on = false),
            )
            for (t in toggles) {
                val node = switchNode(s, t.label) ?: run { bad += "no switch \"${t.label}\""; null } ?: continue
                // ---- what it says: unchanged
                val cd = node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
                if (cd != t.description) bad += "${t.label}: description $cd, expected ${t.description}"
                val strings = mergedStrings(node)
                for (want in t.strings) if (want !in strings) bad += "${t.label}: \"$want\" no longer said ($strings)"
                if (node.config.getOrNull(SemanticsProperties.Role) != Role.Switch) bad += "${t.label}: not a switch"
                val state = node.config.getOrNull(SemanticsProperties.ToggleableState)
                if (state != (if (t.on) ToggleableState.On else ToggleableState.Off)) bad += "${t.label}: state $state, expected on=${t.on}"
                for (want in t.strings) if (!SmokeUi.has(want, exact = true)) bad += "${t.label}: SmokeUi no longer finds \"$want\""
                // ---- what it shows: an icon centred in the 48 × 48 cell, no caption
                val cell = s.dp(node.boundsInWindow)
                if (Math.abs(cell.width - 48f) > 0.6f || Math.abs(cell.height - 48f) > 0.6f) bad += "${t.label}: cell $cell is not 48 × 48"
                var top = Float.NaN
                var bottom = Float.NaN
                var y = 2f
                while (y <= 46f) {
                    var x = 4f
                    var ink = false
                    while (x <= 44f) {
                        if (white(sample(s, shot, cell.left + x, cell.top + y))) { ink = true; break }
                        x += 0.34f
                    }
                    if (ink) {
                        if (top.isNaN()) top = y
                        bottom = y
                        if (y in 34f..42f) bad += "${t.label}: ink at ${"%.1f".format(y)} dp down the cell (the caption's band)"
                    }
                    y += 0.34f
                }
                if (top.isNaN()) bad += "${t.label}: no icon" else {
                    val mid = (top + bottom) / 2f
                    if (Math.abs(mid - 24f) > 2f) bad += "${t.label}: the ink spans ${"%.1f".format(top)}–${"%.1f".format(bottom)} dp, centred at ${"%.1f".format(mid)}, not 24"
                }
                if (t.on) {
                    val fill = sample(s, shot, cell.left + 24f, cell.top + 3f)
                    if (!IbisShots.close(IbisColors.Accent.toArgb(), fill, 10)) bad += "${t.label}: on, but the cell is ${IbisShots.hex(fill)}, not the accent"
                }
            }
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }

    /** The switch (a clickable with the Switch role) that says [label], itself or through a child. */
    private fun switchNode(s: ChromeScreen, label: String): SemanticsNode? =
        s.placed().map { it.node }.lastOrNull { n ->
            n.config.getOrNull(SemanticsProperties.Role) == Role.Switch && label in mergedStrings(n)
        }

    /** Texts, descriptions and click label of [n] and of its children that are not clickables (what a screen reader reads). */
    private fun mergedStrings(n: SemanticsNode): Set<String> {
        val out = linkedSetOf<String>()
        fun own(m: SemanticsNode) {
            out += m.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text }
            out += m.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
            m.config.getOrNull(SemanticsActions.OnClick)?.label?.let { out += it }
        }
        fun walk(m: SemanticsNode) {
            for (c in m.children) {
                if (c.config.getOrNull(SemanticsActions.OnClick) != null) continue
                own(c)
                walk(c)
            }
        }
        own(n)
        walk(n)
        return out
    }

    /** The render's colour at ([x], [y]) dp of the editor's root. */
    private fun sample(s: ChromeScreen, shot: Bitmap, x: Float, y: Float): Int =
        IbisShots.at(shot, s.density, x + s.root.left / s.density, y + s.root.top / s.density)
}
