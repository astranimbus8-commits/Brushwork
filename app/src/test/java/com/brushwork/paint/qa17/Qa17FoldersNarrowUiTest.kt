package com.brushwork.paint.qa17

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.PolishRowText
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerTreeRows
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.abs

/**
 * v1.7 (item 8, §3.8): rows four folders deep in the layer window on the user's 392 dp phone keep
 * their name and every badge whole. The 48 dp indent (where it stops growing) comes out of the
 * thumbnail first (`LayerWindowMetrics.thumbAt`, down to 32 dp; a folder's to its 40 dp target),
 * so a depth-4 row has the room a top-level row has on a 360 dp phone: the number, "100%" over
 * "Normal", the name and the badges, none cut, none over the eye or the ≡ handle.
 *
 * The tree, made with the window's own operations: Folders 1–4 nested, each with a layer under
 * the next; inside Folder 4, bottom first: Folder 5 (locked) holding "inner" (depth 5, past the
 * indent's stop), layer A (clipped onto Folder 5, masked with the mask edited, carrying an array)
 * and the vector layer B (alpha-locked and locked). So the deep rows show every badge: the clip
 * mark, the Array and "V" badges in the thumbnail, MASK, α + lock, the own lock and a folder's
 * dimmed lock. A folder row's "Pass through" may ellipsize where its values are narrow (the
 * values' contract); its name and badges may not. Folder 1 (top level) is the reference row.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.foldersnarrowsandbox"])
class Qa17FoldersNarrowUiTest {

    private class Tree(val f1: Layer, val f4: Layer, val f5: Layer, val inner: Layer, val a: Layer, val b: Layer)

    private lateinit var tree: Tree

    private fun build(c: EditorController) {
        c.selectLayer(c.doc.layers.last())
        val f1 = c.addFolder()!!
        repeat(3) {
            // The open folder's top child, then a folder above it in the same folder.
            c.addLayer()!!
            c.addFolder()!!
        }
        val f4 = c.activeLayer
        val inner = c.addLayer()!!
        val f5 = c.putInNewFolder(inner)!!
        // A closed folder active: the new layers go above it, at its level.
        c.setFolderOpen(f5, false)
        c.selectLayer(f5)
        val a = c.addLayer()!!
        val b = c.addVectorLayer()!!
        c.setFolderOpen(f5, true)
        c.addMask(a, fromSelection = false)
        c.toggleClipping(a)
        a.array = LayerArray(ArraySpec())
        c.toggleAlphaLock(b)
        c.toggleLock(b)
        c.toggleLock(f5)
        c.selectLayer(a)
        c.notifyLayersChanged()
        tree = Tree(f1, f4, f5, inner, a, b)
    }

    private fun placed(s: ChromeScreen, match: (SemanticsNode) -> Boolean): RobolectricUi.Element? =
        s.placed().lastOrNull { match(it.node) }

    private fun tagged(s: ChromeScreen, tag: String) = placed(s) { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun described(s: ChromeScreen, description: String) =
        placed(s) { it.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(description) == true }

    /** Laid out whole: what shows of it (bounds clipped by its parents) is all of it. */
    private fun whole(e: RobolectricUi.Element): Boolean =
        abs(e.bounds.width - e.node.size.width) <= 1f && abs(e.bounds.height - e.node.size.height) <= 1f

    private fun overlap(a: Rect, b: Rect): Boolean =
        a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f

    private fun inside(inner: Rect, outer: Rect): Boolean =
        inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f

    /**
     * [layer]'s row, scrolled into view by selecting it: [depth] levels deep (its thumbnail
     * indented); its number, "100%", [blend] (uncut unless [blendMayEllipsize]) and name uncut and
     * clear of the eye and the ≡ handle; each of [badges] (number-line tags) and [thumbBadges]
     * (descriptions) whole, inside the row (the thumbnail's inside it), clear of the number, the
     * handle and each other.
     */
    private fun checkRow(
        s: ChromeScreen,
        layer: Layer,
        depth: Int,
        blend: String,
        badges: List<String>,
        thumbBadges: List<String>,
        bad: MutableList<String>,
        blendMayEllipsize: Boolean = false,
    ) {
        val c = s.c
        c.selectLayer(layer)
        // The list scrolls the active row into view (an animation).
        Smoke.pump(1_000)
        settle()
        val n = c.doc.indexOf(layer) + 1
        val who = "${layer.name} (layer $n, depth $depth)"
        assertEquals("$who: its depth", depth, LayerTree.depth(c.doc.layers, n - 1))
        val list = s.tagged(LayerWindowTags.LIST) ?: throw AssertionError("no layer list")
        val row = s.tagged(LayerWindowTags.row(layer.id)) ?: run { bad += "$who: no row"; return }
        if (row.height < 79f || row.top < list.top - 0.5f || row.bottom > list.bottom + 0.5f) { bad += "$who: not whole on screen: $row in $list"; return }
        val thumb = s.tagged(LayerWindowTags.thumb(layer.id)) ?: run { bad += "$who: no thumbnail"; return }
        val indent = thumb.left - row.left
        if (indent < LayerTreeRows.indent(depth) - 0.5f) bad += "$who: the thumbnail $indent dp in (indent ${LayerTreeRows.indent(depth)})"
        val values = s.tagged(LayerWindowTags.values(layer.id)) ?: run { bad += "$who: no values"; return }
        val handle = Finger.element(s, LayerLabels.reorder(n)) ?: run { bad += "$who: no ≡"; return }
        val eye = Finger.control(s, LayerLabels.hide(n)) ?: run { bad += "$who: no eye"; return }

        val inValues = PolishRowText.lines(s, values)
        val inRow = PolishRowText.lines(s, row)
        val blendLine = inValues.firstOrNull { it.text == blend }
        val texts = listOf(
            "number" to inRow.firstOrNull { it.text == "$n" && !values.contains(it.bounds.center) },
            "\"100%\"" to inValues.firstOrNull { it.text == "100%" },
            "the name" to inRow.firstOrNull { it.text == layer.name },
        ) + if (blendMayEllipsize) emptyList() else listOf("the blend mode" to blendLine)
        if (blendMayEllipsize && blendLine == null) bad += "$who: no \"$blend\": ${inValues.map { it.text }}"
        for ((what, t) in texts) {
            when {
                t == null -> bad += "$who: no $what; texts ${inRow.map { it.text }}"
                t.cut -> bad += "$who: $what $t is cut"
                !inside(t.bounds, row) -> bad += "$who: $what $t outside its row $row"
                overlap(t.bounds, handle) -> bad += "$who: $what $t over the ≡ $handle"
                overlap(t.bounds, eye) -> bad += "$who: $what $t over the eye $eye"
            }
        }
        val number = texts[0].second

        val shown = mutableListOf<Pair<String, Rect>>()
        for (kind in badges) {
            val e = tagged(s, LayerWindowTags.badge(layer.id, kind)) ?: run { bad += "$who: no $kind badge"; null } ?: continue
            val b = s.dp(e.bounds)
            if (!whole(e)) bad += "$who: the $kind badge is cut: $b of ${e.node.size}"
            if (!inside(b, row)) bad += "$who: the $kind badge $b outside its row $row"
            if (overlap(b, handle)) bad += "$who: the $kind badge $b over the ≡ $handle"
            if (number != null && overlap(b, number.bounds)) bad += "$who: the $kind badge $b over the number $number"
            shown += kind to b
        }
        inRow.firstOrNull { it.text == "MASK" }?.let { if (it.cut) bad += "$who: $it is cut" }
        for (description in thumbBadges) {
            val e = described(s, description) ?: run { bad += "$who: no \"$description\""; null } ?: continue
            val b = s.dp(e.bounds)
            if (!whole(e)) bad += "$who: \"$description\" is cut: $b of ${e.node.size}"
            if (!inside(b, thumb)) bad += "$who: \"$description\" $b outside the thumbnail $thumb"
            shown += description to b
        }
        for (i in shown.indices) for (j in i + 1 until shown.size) {
            if (overlap(shown[i].second, shown[j].second)) bad += "$who: ${shown[i].first} ${shown[i].second} over ${shown[j].first} ${shown[j].second}"
        }
    }

    @Test
    fun depthFourRowsKeepTheirNameAndEveryBadgeWhole() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        val h = ChromeHarness()
        h.section("depth-4 rows at 392 dp") {
            val s = h.editor(Smoke.document(256, 192, layers = 1, whiteBottom = true), ::build)
            val c = s.c
            assertEquals(392f, s.widthDp, 1f)
            click("Open layers (active layer ${c.doc.indexOf(tree.a) + 1})", exact = true)
            Smoke.pump(1_200)
            settle()
            val bad = mutableListOf<String>()
            val t = tree
            fun n(l: Layer) = c.doc.indexOf(l) + 1
            checkRow(
                s, t.a, 4, "Normal",
                badges = listOf(LayerWindowTags.BADGE_MASK),
                thumbBadges = listOf(LayerLabels.badge(n(t.a), ArrayLabels.BUTTON)),
                bad = bad,
            )
            checkRow(
                s, t.b, 4, "Normal",
                badges = listOf(LayerWindowTags.BADGE_ALPHA, LayerWindowTags.BADGE_LOCK),
                thumbBadges = listOf(LayerLabels.badge(n(t.b), LayerLabels.VECTOR_BADGE)),
                bad = bad,
            )
            checkRow(s, t.f5, 4, FolderLabels.PASS_THROUGH, listOf(LayerWindowTags.BADGE_LOCK), emptyList(), bad, blendMayEllipsize = true)
            checkRow(s, t.inner, 5, "Normal", listOf(LayerWindowTags.BADGE_FOLDER_LOCK), emptyList(), bad)
            checkRow(s, t.f4, 3, FolderLabels.PASS_THROUGH, emptyList(), emptyList(), bad, blendMayEllipsize = true)
            checkRow(s, t.f1, 0, FolderLabels.PASS_THROUGH, emptyList(), emptyList(), bad, blendMayEllipsize = true)
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
            // The reference row: is "Pass through" whole at the top level? (Reported, not required.)
            val top = PolishRowText.lines(s, s.tagged(LayerWindowTags.values(t.f1.id))!!).firstOrNull { it.text == FolderLabels.PASS_THROUGH }
            println("Qa17FoldersNarrow: Folder 1's blend at the top level: $top")
        }
        dog.interrupt()
        h.finish()
    }
}
