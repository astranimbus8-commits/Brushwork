package com.brushwork.paint.fonts

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextFont
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.placement.TextToolOptions
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * The font picker and the placeholder text controls in the text editor sheet, in a real activity
 * at the user's phone size: built-in and imported fonts, favorites, search, deleting a font (the
 * editor then warns that it is missing), and inserting placeholder text.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.fonts.pickersandbox"])
class FontPickerUiRobolectricTest {

    @Test
    fun pickingFavoritingSearchingAndDeletingFonts() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        val store = tool.fontStore
        val arvoBytes = TestFonts.real("Arvo-Regular.ttf")
        val arvo = runBlocking { store.import(listOf(FontImporter.Source("Arvo-Regular.ttf") { arvoBytes.inputStream() })) }.added.single()
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        tool.startTextAt(300f, 400f)
        tool.setText("Caption")
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Font: Sans", exact = true))
        assertTrue("placeholder controls", SmokeUi.has("Lorem ipsum", exact = true) && SmokeUi.has("Insert", exact = true))
        assertFalse("no box to fill yet", SmokeUi.has("Fill the box", exact = true))

        // The picker: built-in families, the license note, rows written with the text.
        SmokeUi.click("Choose font")
        assertTrue(SmokeUi.has("Fonts", exact = true))
        assertTrue(SmokeUi.has("BUILT-IN", exact = true))
        assertTrue(SmokeUi.has("Check each font's license on dafont before commercial use"))
        assertTrue(SmokeUi.has("Caption", exact = true))
        SmokeUi.assertWindowsLaidOut(3)
        SmokeUi.click("Serif", exact = true)
        assertEquals(TextFont.SERIF, tool.item!!.spec.font)
        assertTrue(SmokeUi.has("Font: Serif", exact = true))
        // Remembered as recent, but the open list doesn't change under the finger.
        assertTrue(Smoke.pumpUntil { store.recent.firstOrNull() == FontIds.keyOf(TextFont.SERIF) })
        SmokeUi.settle()
        assertFalse(SmokeUi.has("RECENT", exact = true))

        // Star a font: it is listed under Favorites (and saved).
        SmokeUi.field("Search fonts").type("casual")
        SmokeUi.settle()
        assertTrue(SmokeUi.has("RESULTS", exact = true))
        assertFalse(SmokeUi.has("Serif", exact = true))
        SmokeUi.click("Add Casual to favorites", exact = true)
        assertTrue(Smoke.pumpUntil { FontIds.keyOf(TextFont.CASUAL) in store.favorites })
        SmokeUi.click("Clear search", exact = true)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("FAVORITES", exact = true))
        assertTrue(SmokeUi.has("Remove Casual from favorites", exact = true))

        // An imported font, found by searching, drawn in its own font.
        SmokeUi.field("Search fonts").type("arv")
        SmokeUi.settle()
        assertTrue(Smoke.pumpUntil { SmokeUi.has(arvo.name, exact = true) })
        SmokeUi.click(arvo.name, exact = true)
        assertEquals(arvo.id, tool.item!!.spec.fontId)
        assertTrue(SmokeUi.has("Font: ${arvo.name}", exact = true))
        assertTrue(Smoke.pumpUntil { store.recent.firstOrNull() == FontIds.keyOf(arvo.id) })

        // Deleting it (after a question): the text shows its fallback, the editor warns.
        SmokeUi.click("Delete font ${arvo.name}", exact = true)
        assertTrue(SmokeUi.has("Delete font?", exact = true))
        SmokeUi.clickIn("Delete font?", "Delete font")
        assertTrue(Smoke.pumpUntil { store.fonts.isEmpty() })
        SmokeUi.settle()
        assertTrue(SmokeUi.has("isn't on this device"))
        assertTrue(tool.fontMissing)
        SmokeUi.click("Done", exact = true)
        assertFalse(SmokeUi.has("Search fonts"))
        // Next time the picker opens, the fonts used are listed under Recent.
        SmokeUi.click("Choose font")
        assertTrue(SmokeUi.has("RECENT", exact = true))
        SmokeUi.click("Done", exact = true)
        assertFalse(SmokeUi.has("Search fonts"))
        Smoke.assertQuiet(c, "fonts picked")

        // Placeholder text: English, short, replacing the text.
        SmokeUi.click("English", exact = true)
        SmokeUi.click("Short", exact = true)
        SmokeUi.click("Insert", exact = true)
        assertTrue(Smoke.pumpUntil { tool.item?.text == "Your text goes here." })
        // With a fixed box it can fill the box exactly.
        tool.setFixedBox(true)
        tool.setBoxLength(300f)
        SmokeUi.settle()
        assertTrue(SmokeUi.has("Fill the box", exact = true))
        SmokeUi.click("Fill the box", exact = true)
        SmokeUi.click("Lorem ipsum", exact = true)
        SmokeUi.click("Insert", exact = true)
        assertTrue(Smoke.pumpUntil { tool.item?.text?.startsWith("Lorem ipsum dolor") == true })
        assertEquals(225f, tool.item!!.spec.box.minHeight, 0.01f)
        assertTrue(SmokeUi.has("Fixed box height", exact = true))
        SmokeUi.click("OK", exact = true)
        assertTrue(tool.commitItem())
        SmokeUi.settle()
        Smoke.assertQuiet(c, "placeholder committed")
        SmokeUi.assertIdle("text tool")
    }

    /** On a narrow (360dp) phone the search field keeps room and every button stays on screen. */
    @Test
    @Config(qualifiers = "w360dp-h780dp-xxhdpi")
    fun thePickerFitsANarrowPhone() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = Smoke.controller(activity, Smoke.document(600, 800, layers = 1))
        c.selectTool(ToolId.TEXT)
        val tool = c.tools.getValue(ToolId.TEXT) as TextTool
        val bytes = TestFonts.real("ComingSoon.ttf")
        val font = runBlocking { tool.fontStore.import(listOf(FontImporter.Source("ComingSoon.ttf") { bytes.inputStream() })) }.added.single()
        activity.setContent { BrushworkTheme { TextToolOptions(tool) } }
        tool.startTextAt(300f, 400f)
        tool.setText("A fairly long caption to preview")
        SmokeUi.settle()
        SmokeUi.click("Choose font")
        assertTrue(SmokeUi.has("Add Serif to favorites", exact = true))
        val dp = activity.resources.displayMetrics.density
        val screen = activity.resources.displayMetrics.widthPixels / dp
        fun clickableBounds(label: String): android.graphics.Rect {
            var n: SemanticsNode? = requireNotNull(SmokeUi.find(label, exact = true)) { label }.node
            while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
            val b = requireNotNull(n) { "$label is not clickable" }.boundsInWindow
            return android.graphics.Rect(b.left.toInt(), b.top.toInt(), b.right.toInt(), b.bottom.toInt())
        }
        val search = SmokeUi.field("Search fonts").bounds
        assertTrue("search field ${search.width / dp} dp wide", search.width / dp >= 140f)
        val import = clickableBounds("Import fonts")
        assertTrue("import button inside the screen: ${import.right / dp} of $screen dp", import.right / dp <= screen - 12f)
        fun assertTarget(label: String) {
            val b = clickableBounds(label)
            assertTrue("$label: ${b.width() / dp} x ${b.height() / dp} dp", b.width() / dp >= 40f && b.height() / dp >= 40f)
            assertTrue("$label on screen", b.right / dp <= screen)
        }
        assertTarget("Add Serif to favorites")
        // The imported font (found by searching): its star and delete buttons.
        SmokeUi.field("Search fonts").type("coming")
        SmokeUi.settle()
        assertTrue(Smoke.pumpUntil { SmokeUi.has("Delete font ${font.name}", exact = true) })
        assertTarget("Add ${font.name} to favorites")
        assertTarget("Delete font ${font.name}")
        assertTrue("with a query the field still has room", SmokeUi.field("Search fonts").bounds.width / dp >= 140f)
        SmokeUi.click("Done", exact = true)
        tool.discard()
        SmokeUi.settle()
    }
}
