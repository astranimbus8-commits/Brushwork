package com.brushwork.paint.qa19

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.AnnotatedString
import androidx.compose.ui.unit.toSize
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TextLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.placement.BULLET_DESCRIPTIONS
import com.brushwork.paint.ui.placement.BULLET_GLYPHS
import com.brushwork.paint.ui.placement.LIST_SETTINGS_TAG
import com.brushwork.paint.ui.placement.LIST_TYPE_TAG_PREFIX
import com.brushwork.paint.ui.placement.OTHER_BULLET_TAG
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
 * v1.9 A3 step 6 (R7 a, e; I27): "Lists" in the text sheet of the real editor on the 360 dp
 * phone, at font scale 1 and at each of [Qa19Ui.FONT_SCALES]:
 * - with "No list" the section is one line of chips ("No list", "Bullets", "Numbering"), and no
 *   "List settings";
 * - Bullets: six chips (tags [V19Tags.BULLET_PREFIX] + index) in order, wrapped inside the screen,
 *   each 48 dp wide and a 48 dp target at least, their glyphs drawn at 20 sp at least, their
 *   descriptions ("Bullet: dot" …) each on exactly one of the sheet's clickables and none equal to
 *   ✓'s; "Other bullet…" takes the last character typed;
 * - "List settings" shows collapsed once a list is picked; opened, the marker rows;
 * - Numbering: eight style chips and "First number";
 * - vertical text shows only "Only horizontal text can have bullets or numbers";
 * - ✓ commits the list with the text; no text in the sheet overflows.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.qa19.listssandbox"])
class Qa19ListsUiTest {

    @Test
    fun listsSectionAt360AndLargeFonts() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f) + Qa19Ui.FONT_SCALES) h.section("at font scale $scale") {
            val where = "360 dp at font scale $scale"
            val s = h.editor(fontScale = scale) { it.selectTool(ToolId.TEXT) }
            assertEquals("the phone is 360 dp wide", 360f, s.widthDp, 1f)
            val t = s.c.tools.getValue(ToolId.TEXT) as TextTool
            t.startTextAt(200f, 150f)
            t.setText("Brushes\nLayers\nExport")
            Qa19Ui.settle(20)
            assertTrue("$where: the text sheet: ${SmokeUi.sheetTitles()}", SHEET in SmokeUi.sheetTitles())

            // No list: one line of chips.
            assertTrue("$where: the \"Lists\" header", Qa19Ui.has(TextLabels19.LISTS.uppercase(), exact = true))
            assertTypeChips(s, where, selected = 0)
            assertFalse("$where: no \"List settings\" without a list", Qa19Ui.has(TextLabels19.LIST_SETTINGS, exact = true))
            assertNull(Qa19Ui.tagged(V19Tags.BULLET_PREFIX + 0))
            assertFalse(Qa19Ui.has(TextLabels19.FIRST_NUMBER, exact = true))
            assertSheetFits("with no list, $where")

            // Bullets: the six chips, "Other bullet…", "List settings" collapsed.
            clickTag(LIST_TYPE_TAG_PREFIX + TextListType.BULLETS.name)
            assertEquals(TextListType.BULLETS, t.item!!.spec.list.type)
            assertTypeChips(s, where, selected = 1)
            assertBullets(s, where, selected = 0)
            assertDescriptionsUnique(where)
            assertTrue(Qa19Ui.has(TextLabels19.OTHER_BULLET, exact = true))
            assertSettingsCollapsed(where)
            assertSheetFits("with bullets, $where")
            clickTag(V19Tags.BULLET_PREFIX + 4)
            assertEquals("★", t.item!!.spec.list.bullet)
            assertBullets(s, where, selected = 4)
            // Other bullet: the last character typed.
            typeInto(OTHER_BULLET_TAG, "→")
            assertEquals("→", t.item!!.spec.list.bullet)
            typeInto(OTHER_BULLET_TAG, "→♥")
            assertEquals("♥", t.item!!.spec.list.bullet)
            assertBullets(s, where, selected = -1)
            clickTag(V19Tags.BULLET_PREFIX + 0)
            assertEquals("•", t.item!!.spec.list.bullet)

            // List settings opened: the marker rows.
            clickTag(LIST_SETTINGS_TAG)
            assertEquals(StripLabels18.EXPANDED, Qa19Ui.tagged(LIST_SETTINGS_TAG)!!.config.getOrNull(SemanticsProperties.StateDescription))
            for (label in listOf(TextLabels19.MARKER_SIZE, TextLabels19.MARKER_THICKNESS, TextLabels19.MARKER_GAP, TextLabels19.MARKER_FONT, TextLabels19.SAME_AS_TEXT)) {
                assertTrue("$where: \"$label\" in List settings", Qa19Ui.has(label, exact = true))
            }
            assertSheetFits("with List settings open, $where")

            // Numbering: eight style chips and "First number".
            clickTag(LIST_TYPE_TAG_PREFIX + TextListType.NUMBERS.name)
            assertEquals(TextListType.NUMBERS, t.item!!.spec.list.type)
            assertNull("$where: no bullets with numbering", Qa19Ui.tagged(V19Tags.BULLET_PREFIX + 0))
            assertNumbering(s, where, selected = TextNumbering.DECIMAL_DOT)
            assertTrue(Qa19Ui.has(TextLabels19.FIRST_NUMBER, exact = true))
            clickTag(V19Tags.NUMBERING_PREFIX + TextNumbering.LOWER_ROMAN.name)
            assertEquals(TextNumbering.LOWER_ROMAN, t.item!!.spec.list.numbering)
            assertNumbering(s, where, selected = TextNumbering.LOWER_ROMAN)
            val first = Qa19Ui.find(TextLabels19.FIRST_NUMBER, exact = true)
            assertNotNull(first)
            setTextOf(editableAround(first!!.node), "3")
            assertEquals("$where: \"First number\" typed", 3, t.item!!.spec.list.start)
            assertSheetFits("with numbering, $where")

            // No list again: one line.
            clickTag(LIST_TYPE_TAG_PREFIX + TextListType.NONE.name)
            assertEquals(TextListType.NONE, t.item!!.spec.list.type)
            assertFalse(Qa19Ui.has(TextLabels19.LIST_SETTINGS, exact = true))
            assertFalse(Qa19Ui.has(TextLabels19.FIRST_NUMBER, exact = true))
            assertNull(Qa19Ui.tagged(V19Tags.NUMBERING_PREFIX + TextNumbering.DECIMAL_DOT.name))

            // Vertical text: only the note.
            clickTag(LIST_TYPE_TAG_PREFIX + TextListType.BULLETS.name)
            t.updateSpec { it.copy(vertical = true) }
            Qa19Ui.settle()
            assertTrue("$where: vertical text says why", Qa19Ui.has(TextLabels19.LISTS_HORIZONTAL_ONLY, exact = true))
            assertNull(Qa19Ui.tagged(LIST_TYPE_TAG_PREFIX + TextListType.BULLETS.name))
            assertNull(Qa19Ui.tagged(V19Tags.BULLET_PREFIX + 0))
            assertSheetFits("vertical text, $where")
            t.updateSpec { it.copy(vertical = false) }
            Qa19Ui.settle()

            // OK keeps the edit, ✓ commits the list with the text.
            clickTag(V19Tags.BULLET_PREFIX + 5)
            t.confirmEditor()
            Qa19Ui.settle()
            t.commit()
            Qa19Ui.settle()
            val layer = s.c.doc.layers.last { it.textData != null }
            val saved = TextCodec.decodeAll(layer.textData).single()
            assertEquals("$where: the list is committed", TextListType.BULLETS, saved.spec.list.type)
            assertEquals("✓", saved.spec.list.bullet)
            Smoke.assertQuiet(s.c, where)
        }
    }

    /** "No list", "Bullets" and "Numbering" in order inside the screen, [selected] chosen. */
    private fun assertTypeChips(s: ChromeScreen, where: String, selected: Int) {
        val chips = TextListType.entries.map { tagged(LIST_TYPE_TAG_PREFIX + it.name) }
        assertInOrder(s, where, chips, TextListType.entries.map { it.name }, minWidth = 40f)
        for ((i, c) in chips.withIndex()) assertEquals("$where: ${TextListType.entries[i]} chosen", i == selected, c.config.getOrNull(SemanticsProperties.Selected) == true)
    }

    /** The six bullet chips: glyph, description, 20 sp, 48 dp, in order inside the screen; [selected] chosen (-1: none). */
    private fun assertBullets(s: ChromeScreen, where: String, selected: Int) {
        val chips = BULLET_GLYPHS.indices.map { tagged(V19Tags.BULLET_PREFIX + it) }
        assertInOrder(s, where, chips, BULLET_DESCRIPTIONS, minWidth = 47.5f)
        for ((i, c) in chips.withIndex()) {
            val desc = BULLET_DESCRIPTIONS[i]
            assertEquals("$where: the chip's description", listOf(desc), c.config.getOrNull(SemanticsProperties.ContentDescription))
            val glyph = textNodeIn(c) ?: throw AssertionError("$where: \"$desc\" shows no glyph")
            val r = Qa19Ui.textLayout(glyph) ?: throw AssertionError("$where: \"$desc\" glyph not laid out")
            assertEquals("$where: \"$desc\" glyph", BULLET_GLYPHS[i], r.layoutInput.text.text)
            assertTrue("$where: \"$desc\" drawn at 20 sp at least: ${r.layoutInput.style.fontSize}", r.layoutInput.style.fontSize.value >= 20f)
            assertEquals("$where: \"$desc\" chosen", i == selected, c.config.getOrNull(SemanticsProperties.Selected) == true)
        }
    }

    /** The eight numbering chips (their samples), in order inside the screen; [selected] chosen. */
    private fun assertNumbering(s: ChromeScreen, where: String, selected: TextNumbering) {
        val chips = TextNumbering.entries.map { tagged(V19Tags.NUMBERING_PREFIX + it.name) }
        assertInOrder(s, where, chips, TextNumbering.entries.map { it.sample }, minWidth = 47.5f)
        for ((i, c) in chips.withIndex()) {
            val style = TextNumbering.entries[i]
            assertEquals("$where: the chip shows ${style.sample}", style.sample, textNodeIn(c)?.let { Qa19Ui.textLayout(it)?.layoutInput?.text?.text })
            assertEquals("$where: $style chosen", style == selected, c.config.getOrNull(SemanticsProperties.Selected) == true)
        }
    }

    /** [chips] wrapped inside the screen in their order, each [minWidth] dp wide and a 48 dp target at least. */
    private fun assertInOrder(s: ChromeScreen, where: String, chips: List<SemanticsNode>, names: List<String>, minWidth: Float) {
        val rows = chips.map { s.dp(box(it)) }
        for ((i, r) in rows.withIndex()) {
            assertTrue("$where: \"${names[i]}\" inside the screen: $r", r.left >= -0.5f && r.right <= s.widthDp + 0.5f)
            assertTrue("$where: \"${names[i]}\" $minWidth dp wide at least: $r", r.width >= minWidth)
            val target = s.dp(Rect(0f, 0f, chips[i].layoutInfo.width.toFloat(), chips[i].layoutInfo.height.toFloat()))
            assertTrue("$where: \"${names[i]}\" a 48 dp target: $target", target.height >= 47.5f)
        }
        for (k in 1 until rows.size) {
            val a = rows[k - 1]
            val b = rows[k]
            val sameRow = b.top < a.bottom - 0.5f && a.top < b.bottom - 0.5f
            assertTrue("$where: \"${names[k]}\" after \"${names[k - 1]}\": $a, $b", if (sameRow) b.left > a.right else b.top >= a.bottom - 0.5f)
        }
    }

    /**
     * R7 e: each bullet description names exactly one of the sheet's clickables (by description
     * or text), and none is ✓'s ("Apply text edit", the editor's commit button).
     */
    private fun assertDescriptionsUnique(where: String) {
        val clickables = RobolectricUi.elements().filter { inSheet(it.node) && it.node.config.getOrNull(SemanticsActions.OnClick) != null && it.node.layoutInfo.isPlaced }
        fun labels(n: SemanticsNode): List<String> =
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
                n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
                n.children.filter { it.config.getOrNull(SemanticsActions.OnClick) == null }.flatMap { labels(it) }
        val all = clickables.map { labels(it.node) }
        for (d in BULLET_DESCRIPTIONS) assertEquals("$where: \"$d\" names one clickable", 1, all.count { d in it })
        val check = Qa19Ui.find(CHECK, exact = true)
        assertNotNull("$where: ✓ is on screen", check)
        val checkLabels = check!!.node.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty()
        for (d in BULLET_DESCRIPTIONS) assertFalse("$where: \"$d\" is not ✓'s", d in checkLabels)
    }

    /** "List settings" shows, collapsed, without its rows. */
    private fun assertSettingsCollapsed(where: String) {
        val row = Qa19Ui.tagged(LIST_SETTINGS_TAG) ?: throw AssertionError("$where: no \"List settings\" row")
        assertEquals("$where: \"List settings\" collapsed", StripLabels18.COLLAPSED, row.config.getOrNull(SemanticsProperties.StateDescription))
        assertTrue(Qa19Ui.has(TextLabels19.LIST_SETTINGS, exact = true))
        assertFalse("$where: the marker rows wait", Qa19Ui.has(TextLabels19.MARKER_SIZE, exact = true))
    }

    private fun tagged(tag: String): SemanticsNode = Qa19Ui.tagged(tag) ?: throw AssertionError("no $tag; shown: ${Qa19Ui.shown().take(100)}")

    /** Clicks the node tagged [tag] and lets the sheet settle. */
    private fun clickTag(tag: String) {
        val action = tagged(tag).config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("$tag is not clickable")
        action()
        Qa19Ui.settle()
    }

    /** Types [text] into the field tagged [tag] (replacing what it holds). */
    private fun typeInto(tag: String, text: String) = setTextOf(editableIn(tagged(tag)) ?: throw AssertionError("$tag has no text field"), text)

    private fun setTextOf(n: SemanticsNode, text: String) {
        val set = n.config.getOrNull(SemanticsActions.SetText)?.action ?: throw AssertionError("not a text field")
        set(AnnotatedString(text))
        Qa19Ui.settle()
    }

    /** The text field in [n]'s subtree. */
    private fun editableIn(n: SemanticsNode): SemanticsNode? {
        if (n.config.getOrNull(SemanticsActions.SetText) != null) return n
        for (c in n.children) editableIn(c)?.let { return it }
        return null
    }

    /** The text field [label] (its floating label) belongs to: the nearest ancestor's field. */
    private fun editableAround(label: SemanticsNode): SemanticsNode {
        var p: SemanticsNode? = label
        while (p != null) {
            editableIn(p)?.let { return it }
            p = p.parent
        }
        throw AssertionError("no field around ${label.config}")
    }

    /** The first node with text in [n]'s subtree. */
    private fun textNodeIn(n: SemanticsNode): SemanticsNode? {
        if (n.config.getOrNull(SemanticsActions.GetTextLayoutResult) != null && n.config.getOrNull(SemanticsProperties.Text) != null) return n
        for (c in n.children) textNodeIn(c)?.let { return it }
        return null
    }

    /** I27 for the text sheet: no text in it overflows its box. */
    private fun assertSheetFits(where: String) {
        val bad = Qa19Ui.textOverflows { e -> inSheet(e.node) }
        if (bad.isNotEmpty()) throw AssertionError("texts overflow their box in the text sheet $where:\n" + bad.joinToString("\n"))
    }

    /** Whether [n] lies in the "Add text" sheet's panel. */
    private fun inSheet(n: SemanticsNode): Boolean {
        var p: SemanticsNode? = n
        while (p != null) {
            if (p.config.getOrNull(BwSheetTitleKey) == SHEET) return true
            p = p.parent
        }
        return false
    }

    /** [n]'s bounds in its window (px), not clipped (the sheet scrolls). */
    private fun box(n: SemanticsNode): Rect = Rect(n.positionInWindow, n.size.toSize())

    private companion object {
        const val SHEET = "Add text"

        /** ✓'s description while the Text tool edits (EditorChrome: "Apply <tool> edit"). */
        val CHECK = "Apply ${ToolId.TEXT.label.lowercase()} edit"
    }
}
