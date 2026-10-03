package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.layers.LayerLabels
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The same finger walk on a 360 dp phone and on the user's phone in landscape (the v1.5 side
 * window fallback): the bottom bar's buttons, the tool menu, the layer window's ✕, the More menu
 * — tapped where they show — and no shared labels or small targets in each state.
 */
internal object OtherPhones {
    fun run(name: String) = Walk.run(name) { h ->
        val s = h.editor(IbisDocs.page(), setup = IbisDocs::fourKinds)
        val main = Clickables.onScreen(s)
        StateAudit.assertUnique("$name main", main)
        StateAudit.assertFingerSized(s, "$name main", main)

        Finger.tap(s, "Switch to eraser")
        assertEquals(ToolId.ERASER, s.c.activeToolId)
        Finger.tap(s, "Eraser on: switch to brush")
        assertEquals(ToolId.BRUSH, s.c.activeToolId)

        Finger.tap(s, "Tools (current: Brush)")
        val menu = s.tagged(ChromeTags.TOOL_MENU) ?: throw AssertionError("no tool menu")
        assertTrue("the tool menu fits the screen: $menu", menu.top >= 0f && menu.bottom <= s.heightDp && menu.right <= s.widthDp)
        val cell = Finger.control(s, "Smudge", menu) ?: throw AssertionError("no Smudge cell")
        Finger.tapAt(s, cell.center.x, cell.center.y)
        assertEquals("the cell's tool", ToolId.SMUDGE, s.c.activeToolId)
        assertNull(s.tagged(ChromeTags.TOOL_MENU))
        s.c.selectTool(ToolId.BRUSH); SmokeUi.settle()

        Finger.tap(s, "Open brush settings"); Walk.panelThenBack("Brush")
        Finger.tap(s, "Open color picker"); Walk.panelThenBack("Color")

        Finger.tap(s, "Open layers (active layer 5)")
        Smoke.pump(300); SmokeUi.settle()
        val lw = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        assertTrue("the layer window fits the screen: $lw", lw.top >= 0f && lw.bottom <= s.heightDp + 0.5f && lw.right <= s.widthDp + 0.5f && lw.left >= -0.5f)
        val open = Clickables.ownLabelsInside(Clickables.onScreen(s), lw)
        StateAudit.assertUnique("$name layer window", open)
        StateAudit.assertFingerSized(s, "$name layer window", open)
        Finger.tap(s, LayerLabels.hide(5))
        assertFalse("the eye", s.c.doc.layers[4].visible)
        Finger.tap(s, LayerLabels.show(5))
        Finger.tap(s, LayerLabels.CLOSE)
        assertNull("✕ closes it", s.tagged(ChromeTags.LAYER_WINDOW))

        Finger.tap(s, "Hide interface")
        Smoke.pump(400); SmokeUi.settle()
        assertFalse(SmokeUi.has("Undo", exact = true))
        Finger.tap(s, "Show interface")
        Smoke.pump(400); SmokeUi.settle()
        assertTrue(SmokeUi.has("Undo", exact = true))

        Finger.tap(s, "More options")
        Smoke.pump(300); SmokeUi.settle()
        assertEquals(2, SmokeUi.windows().size)
        val popup = SmokeUi.windows().last()
        // The menu scrolls on a short screen: a finger pushes it up until the entry shows.
        repeat(4) {
            val e = SmokeUi.find("Fit to screen", exact = true)
            if (e == null || e.bounds.height < 40f) {
                com.brushwork.paint.ui.color.RobolectricUi.drag(popup, popup.width / 2f to popup.height * 0.8f, popup.width / 2f to popup.height * 0.3f)
            }
        }
        val fit = SmokeUi.find("Fit to screen", exact = true) ?: throw AssertionError("no More › Fit to screen")
        assertTrue("More › Fit to screen shows: ${fit.bounds} in ${popup.width} × ${popup.height}", fit.bounds.height > 0f && fit.bounds.bottom <= popup.height)
        SmokeUi.tap("Fit to screen", exact = true)
        assertEquals("an entry closes the menu", 1, SmokeUi.windows().size)
        Smoke.assertQuiet(s.c, name)
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.walk360sandbox"])
class NarrowPhoneFingerWalkTest {
    @Test
    fun theChromeWorksAt360dp() = OtherPhones.run("360 dp")
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w873dp-h392dp-land-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.walklandsandbox"])
class LandscapeFingerWalkTest {
    @Test
    fun theChromeWorksInLandscape() = OtherPhones.run("landscape")
}
