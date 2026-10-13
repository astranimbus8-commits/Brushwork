package com.brushwork.paint.qa19

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.Text
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.compose.ui.window.Popup
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.color.RobolectricUi
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.9 F0: [Qa19Ui] does what the areas rely on (I27): a clipped or ellipsized text fails
 * [Qa19Ui.assertNoTextOverflow] and is named, a wrapped one passes, and font scale 2.0 takes
 * effect through [Qa19Ui.withFontScale], through the device's font scale in a fresh activity and
 * in a popup window, and in the editor of [Qa19Harness.editor].
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.uiselftestsandbox"])
class Qa19UiSelfTest {

    /** The layout of the text node on screen showing exactly [text]. */
    private fun layoutOf(text: String): TextLayoutResult {
        val windows = RobolectricUi.windowRoots()
        val e = RobolectricUi.elements().lastOrNull { x ->
            windows.indexOf(x.window) >= SmokeUi.baseline && x.node.layoutInfo.isPlaced &&
                x.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text
        }
        val n = need("\"$text\" is on screen", e).node
        return need("\"$text\" has a text layout", Qa19Ui.textLayout(n))
    }

    private fun <T> need(message: String, value: T?): T {
        org.junit.Assert.assertNotNull(message, value)
        return value!!
    }

    @Test
    fun theKitChecksWhatTheAreasRelyOn() = Qa19Ui.uiTest { h ->
        val clipped = "A clipped label far wider than its box"
        val ellipsized = "An ellipsized label far wider than its box"
        val wrapped = "A wrapped label far wider than one line"

        h.section("a clipped text fails and is named") {
            h.show {
                Box(Modifier.width(48.dp)) { Text(clipped, maxLines = 1, softWrap = false, overflow = TextOverflow.Clip) }
            }
            val bad = Qa19Ui.textOverflows()
            assertEquals("one overflow: $bad", 1, bad.size)
            assertTrue(bad.single(), bad.single().startsWith("\"$clipped\""))
            val e = runCatching { Qa19Ui.assertNoTextOverflow("in the self-test") }.exceptionOrNull()
            assertTrue("assertNoTextOverflow fails: $e", e is AssertionError && e.message!!.contains(clipped) && e.message!!.contains("in the self-test"))
            Qa19Ui.assertNoTextOverflow(except = { it == clipped })
        }

        h.section("an ellipsized text fails") {
            h.show {
                Box(Modifier.width(48.dp)) { Text(ellipsized, maxLines = 1, overflow = TextOverflow.Ellipsis) }
            }
            val e = runCatching { Qa19Ui.assertNoTextOverflow() }.exceptionOrNull()
            assertTrue("assertNoTextOverflow fails: $e", e is AssertionError && e.message!!.contains(ellipsized))
        }

        h.section("a wrapped text passes") {
            h.show {
                Box(Modifier.width(48.dp)) { Text(wrapped) }
            }
            assertTrue("it wraps", layoutOf(wrapped).lineCount > 1)
            assertEquals(emptyList<String>(), Qa19Ui.textOverflows())
            Qa19Ui.assertNoTextOverflow()
        }

        h.section("a short one-line text with room to spare passes (F1b)") {
            h.show {
                Row(Modifier.width(300.dp)) {
                    Text("Short", maxLines = 1)
                    Box(Modifier.widthIn(min = 40.dp)) { Text("1", maxLines = 1) }
                }
            }
            assertEquals(emptyList<String>(), Qa19Ui.textOverflows())
        }

        var base = 0f
        h.section("font scale 1.0") {
            h.show { Text("Scale 1", fontSize = 12.sp) }
            val r = layoutOf("Scale 1")
            assertEquals(1f, r.layoutInput.density.fontScale, 0f)
            base = r.size.height.toFloat()
        }

        h.section("withFontScale(2.0) takes effect") {
            h.show { Qa19Ui.withFontScale(2f) { Text("Scale 2 local", fontSize = 12.sp) } }
            val r = layoutOf("Scale 2 local")
            assertEquals(2f, r.layoutInput.density.fontScale, 0f)
            assertTrue("twice as tall: ${r.size.height} vs $base", r.size.height >= 1.8f * base && r.size.height <= 2.2f * base)
        }

        h.section("the device's font scale 2.0 takes effect, in popups too") {
            h.show(fontScale = 2f) {
                Text("Scale 2 device", fontSize = 12.sp)
                Popup { Text("Scale 2 popup", fontSize = 12.sp) }
            }
            SmokeUi.settle()
            for (t in listOf("Scale 2 device", "Scale 2 popup")) {
                val r = layoutOf(t)
                assertEquals(t, 2f, r.layoutInput.density.fontScale, 0f)
                assertTrue("$t: much taller: ${r.size.height} vs $base", r.size.height >= 1.6f * base)
            }
            assertTrue("a popup window", SmokeUi.windows().size >= 2)
        }

        h.section("the device's font scale is 1 again after a section") {
            h.show { Popup { Text("Scale 1 again", fontSize = 12.sp) } }
            SmokeUi.settle()
            assertEquals(1f, layoutOf("Scale 1 again").layoutInput.density.fontScale, 0f)
        }

        h.section("the editor at font scale 2.0") {
            val s = h.editor(fontScale = 2f)
            val scales = RobolectricUi.elements().filter { it.window === s.activity.window.decorView }
                .mapNotNull { Qa19Ui.textLayout(it.node)?.layoutInput?.density?.fontScale }
            assertTrue("the editor shows texts", scales.isNotEmpty())
            assertTrue("every text at 2.0: $scales", scales.all { it == 2f })
        }
    }
}
