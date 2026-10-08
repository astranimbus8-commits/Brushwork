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
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerTreeRows
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertEquals
import kotlin.math.abs

/**
 * The deep tree of the narrow folder-row tests (Qa17FoldersNarrowUiTest at 392 dp,
 * Qa17FoldersNarrow360UiTest at 360 dp) and their row check.
 *
 * The tree, made with the window's own operations: Folders 1–4 nested, each with a layer under
 * the next; inside Folder 4, bottom first: Folder 5 (locked) holding "inner" (depth 5, past the
 * indent's stop), layer A (clipped onto Folder 5, masked with the mask edited, carrying an array)
 * and the vector layer B (alpha-locked and locked). So the deep rows show every badge: the clip
 * mark, the Array and "V" badges in the thumbnail, MASK, α + lock, the own lock and a folder's
 * dimmed lock. Folder 1 is at the top level, Folder 5 four folders deep.
 */
internal object Qa17FolderRows {

    class Tree(val f1: Layer, val f4: Layer, val f5: Layer, val inner: Layer, val a: Layer, val b: Layer)

    /** How a folder row showed "Pass through". */
    enum class BlendForm { ONE_LINE, SPLIT }

    fun build(c: EditorController): Tree {
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
        return Tree(f1, f4, f5, inner, a, b)
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
     * indented), [rowHeight] dp tall; its number, "100%", [blend] and name uncut, inside the row
     * (the values inside their 40 dp box) and clear of the eye and the ≡ handle; each of [badges]
     * (number-line tags) and [thumbBadges] (descriptions) whole, inside the row (the thumbnail's
     * inside it), clear of the number, the handle and each other. A folder row ([folder]) shows
     * "Pass through" on one line or, where one line would ellipsize, "Pass" over "through" at
     * 11 sp under "100%" (lead decision #7); never ellipsized. Returns the folder row's form.
     */
    fun checkRow(
        s: ChromeScreen,
        layer: Layer,
        depth: Int,
        blend: String,
        badges: List<String>,
        thumbBadges: List<String>,
        bad: MutableList<String>,
        rowHeight: Float = 80f,
        folder: Boolean = false,
    ): BlendForm? {
        val c = s.c
        c.selectLayer(layer)
        // The list scrolls the active row into view (an animation).
        Smoke.pump(1_000)
        settle()
        val n = c.doc.indexOf(layer) + 1
        val who = "${layer.name} (layer $n, depth $depth)"
        assertEquals("$who: its depth", depth, LayerTree.depth(c.doc.layers, n - 1))
        val list = s.tagged(LayerWindowTags.LIST) ?: throw AssertionError("no layer list")
        val row = s.tagged(LayerWindowTags.row(layer.id)) ?: run { bad += "$who: no row"; return null }
        if (row.top < list.top - 0.5f || row.bottom > list.bottom + 0.5f) { bad += "$who: not whole on screen: $row in $list"; return null }
        if (abs(row.height - rowHeight) > 0.5f) bad += "$who: the row ${row.height} dp tall, not $rowHeight"
        val thumb = s.tagged(LayerWindowTags.thumb(layer.id)) ?: run { bad += "$who: no thumbnail"; return null }
        val indent = thumb.left - row.left
        // The row spans the list: 12 dp per level in a 220 dp list (392 dp), 4 in a 188 dp one (360 dp).
        val levels = LayerTreeRows.indent(depth, row.width)
        if (indent < levels - 0.5f) bad += "$who: the thumbnail $indent dp in (indent $levels in a ${row.width} dp list)"
        if (depth > 0 && levels <= 0f) bad += "$who: no indent in a ${row.width} dp list"
        val values = s.tagged(LayerWindowTags.values(layer.id)) ?: run { bad += "$who: no values"; return null }
        val handle = Finger.element(s, LayerLabels.reorder(n)) ?: run { bad += "$who: no ≡"; return null }
        val eye = Finger.control(s, LayerLabels.hide(n)) ?: run { bad += "$who: no eye"; return null }

        val inValues = PolishRowText.lines(s, values)
        val inRow = PolishRowText.lines(s, row)
        val pct = inValues.firstOrNull { it.text == "100%" }
        var form: BlendForm? = null
        val blendLines = run {
            val one = inValues.firstOrNull { it.text == blend }
            if (!folder) return@run listOf("the blend mode" to one)
            val words = blend.split(' ', limit = 2)
            val split = words.map { w -> inValues.firstOrNull { it.text == w } }
            when {
                one != null && split.all { it == null } -> {
                    form = BlendForm.ONE_LINE
                    listOf("the blend mode" to one)
                }
                one == null && split.all { it != null } -> {
                    form = BlendForm.SPLIT
                    for (t in split) if (abs(t!!.sp - 11f) > 0.01f) bad += "$who: $t not at 11 sp"
                    if (pct != null && pct.bounds.bottom > split[0]!!.bounds.top + 0.5f) bad += "$who: \"100%\" $pct not above ${split[0]}"
                    if (split[0]!!.bounds.bottom > split[1]!!.bounds.top + 0.5f) bad += "$who: ${split[0]} not above ${split[1]}"
                    words.mapIndexed { i, w -> "\"$w\"" to split[i] }
                }
                else -> {
                    bad += "$who: neither \"$blend\" nor ${words.joinToString(" / ") { "\"$it\"" }}: ${inValues.map { it.text }}"
                    emptyList()
                }
            }
        }
        val texts = listOf(
            "number" to inRow.firstOrNull { it.text == "$n" && !values.contains(it.bounds.center) },
            "\"100%\"" to pct,
            "the name" to inRow.firstOrNull { it.text == layer.name },
        ) + blendLines
        for ((what, t) in texts) {
            when {
                t == null -> bad += "$who: no $what; texts ${inRow.map { it.text }}"
                t.cut -> bad += "$who: $what $t is cut"
                !inside(t.bounds, row) -> bad += "$who: $what $t outside its row $row"
                overlap(t.bounds, handle) -> bad += "$who: $what $t over the ≡ $handle"
                overlap(t.bounds, eye) -> bad += "$who: $what $t over the eye $eye"
            }
        }
        for ((what, t) in listOf("\"100%\"" to pct) + blendLines) {
            if (t != null && !inside(t.bounds, values)) bad += "$who: $what $t outside its values $values"
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
        println("Qa17FolderRows: $who values $values: ${inValues.joinToString()}${if (form != null) " ($form)" else ""}")
        return form
    }
}
