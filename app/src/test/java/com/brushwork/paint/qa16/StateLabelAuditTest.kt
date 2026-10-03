package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * I10 (every label names one control) and finger-sized targets (≥ 40 dp) in states the chrome
 * audits don't open: Transform with the X / Y pill, and the layer window with its ⋮ "More layer
 * actions" menu open over each kind of layer (plain, text, vector, masked Tone adjustment) — the
 * menu, the strip, the left column and the rows are on screen together.
 */
internal object StateAudit {
    private val VALUE = Regex("""^[-+]?[\d.,\s]+(%|px|°| px| %)?$""")

    fun assertUnique(where: String, items: List<Clickables.Item>) {
        val dups = Clickables.duplicates(items).filterKeys { !VALUE.matches(it) }
        assertTrue("$where: labels shared by several clickables: $dups", dups.isEmpty())
    }

    /** Every clickable but the tools' own options-strip chips (their areas' Material chips) is ≥ 40 dp. */
    fun assertFingerSized(s: ChromeScreen, where: String, items: List<Clickables.Item>) {
        val strip = s.tagged(ChromeTags.OPTIONS_STRIP)
        val small = items
            .filter { strip == null || !strip.contains(it.bounds.center) }
            .filter { it.width < 39.5f || it.height < 39.5f }
        assertTrue("$where: clickables under 40 dp: ${small.map { "${it.labels} ${it.width} × ${it.height}" }}", small.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.audittransformsandbox"])
class TransformPillLabelAuditTest {
    @Test
    fun transformWithThePillIsUniqueAndFingerSized() = Walk.run("Transform + X / Y pill") { h ->
        val s = h.editor(IbisDocs.page())
        s.c.selectLayer(1); settle()
        Finger.tap(s, "Tools (current: Brush)")
        Finger.tap(s, "Transform")
        assertTrue(Smoke.pumpUntil { settle(1); (s.c.currentTool as TransformTool).transformState != null })
        settle()
        assertTrue("the X cell", SmokeUi.has("X slider"))
        val items = Clickables.onScreen(s)
        StateAudit.assertUnique("Transform", items)
        StateAudit.assertFingerSized(s, "Transform", items)
        (s.c.currentTool as TransformTool).discard()
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "Transform audit")
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.auditlayermenusandbox"])
class LayerMenuLabelAuditTest {
    @Test
    fun theLayerMenuRepeatsNothingOnScreen() = Walk.run("⋮ over each kind") { h ->
        val s = h.editor(IbisDocs.page(), setup = IbisDocs::fourKinds)
        val bad = mutableListOf<String>()
        for ((n, kind) in listOf(2 to "plain", 3 to "text", 4 to "vector", 5 to "Tone adjustment")) {
            try {
                s.c.selectLayer(s.c.doc.layers[n - 1]); settle()
                LayerWalk.open(s)
                val window = s.tagged(ChromeTags.LAYER_WINDOW)!!
                LayerWalk.reveal(s, LayerLabels.selectRow(n))
                // The window alone: unique, finger-sized.
                val inWindow = Clickables.ownLabelsInside(Clickables.onScreen(s), window)
                StateAudit.assertUnique("layer window, layer $n ($kind)", inWindow)
                StateAudit.assertFingerSized(s, "layer window, layer $n ($kind)", inWindow.filter { window.contains(it.bounds.center) })
                // With ⋮ open over it.
                Finger.tap(s, LayerLabels.MORE)
                assertEquals("⋮ drops", 2, SmokeUi.windows().size)
                val items = Clickables.ownLabelsInside(Clickables.onScreen(s), window)
                val menu = items.filter { it.window !== s.activity.window.decorView }
                assertTrue("the menu has entries: ${menu.size}", menu.size >= 5)
                StateAudit.assertUnique("⋮ over layer $n ($kind)", items)
                StateAudit.assertFingerSized(s, "⋮ over layer $n ($kind)", menu)
                Finger.back()
                assertEquals(1, SmokeUi.windows().size)
            } catch (t: Throwable) {
                bad += "layer $n ($kind): ${t.message}"
                while (SmokeUi.windows().size > 1) Finger.back()
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
        Smoke.assertQuiet(s.c, "⋮ audit")
    }
}
