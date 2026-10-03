package com.brushwork.paint.qa16

import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.text.TextLayoutResult
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerWindowTags
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v16 polish 1: the layer rows' text at ibisPaint's sizes. Measured on the reference (1 px =
 * 1.136 dp), ibisPaint sets the layer number, "100%" / "Normal" and "Selection Layer" / "No
 * Selection" at about 18 sp (digit and cap height ≈ 13 dp); the rows had them at 11–14 sp.
 *
 * In the open layer window (the reference's page with a Text, a Vector and a masked Tone layer),
 * every row on screen shows its number at 18 sp and "100%" over "Normal" at one size, 18 sp on
 * the user's 392 dp phone (the 70 dp the values have there hold them), "Selection Layer" / "No
 * Selection" at 18 sp; nothing is ellipsized or cut, and no text overlaps the eye, the mask square
 * or the ≡ handle. On a 360 dp phone the 52 dp (a clipped row: 46 dp) the values have there scale
 * them down, never under 13 sp, never cut; the number stays at 18 and the Selection row's lines
 * at ≥ 16; that render is saved as `ibis-layer-window-360` beside the reference.
 */
internal object PolishRowText {

    class Line(val text: String, val bounds: Rect, val sp: Float, val cut: Boolean, val why: String = "") {
        override fun toString() = "\"$text\" ${"%.2f".format(sp)} sp at $bounds${if (cut) " CUT ($why)" else ""}"
    }

    /** The texts (unmerged nodes, so also those under a cleared description) centred inside [area] (dp). */
    fun lines(s: ChromeScreen, area: Rect): List<Line> = s.placed().mapNotNull { e ->
        val text = e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } ?: return@mapNotNull null
        val results = mutableListOf<TextLayoutResult>()
        e.node.config.getOrNull(SemanticsActions.GetTextLayoutResult)?.action?.invoke(results)
        val r = results.firstOrNull() ?: return@mapNotNull null
        val b = s.dp(e.bounds)
        if (!area.contains(b.center)) return@mapNotNull null
        // Cut: wrapped, ellipsized, or wider / taller than the room it was laid out in (as
        // ToolMenuLabelsFitTest; hasVisualOverflow is set for every text under Robolectric).
        val c = r.layoutInput.constraints
        val cut = r.lineCount > 1 || (0 until r.lineCount).any { r.isLineEllipsized(it) } || r.multiParagraph.didExceedMaxLines ||
            (0 until r.lineCount).any { r.getLineRight(it) - r.getLineLeft(it) > c.maxWidth + 0.5f } ||
            r.multiParagraph.height > c.maxHeight + 0.5f
        val why = "lines ${r.lineCount}, laid out ${r.multiParagraph.width} × ${r.multiParagraph.height} px in $c"
        Line(text, b, r.layoutInput.style.fontSize.value, cut, why)
    }

    private fun overlap(a: Rect, b: Rect): Boolean =
        a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f

    private fun inside(inner: Rect, outer: Rect): Boolean =
        inner.left >= outer.left - 0.5f && inner.right <= outer.right + 0.5f && inner.top >= outer.top - 0.5f && inner.bottom <= outer.bottom + 0.5f

    /**
     * Checks every layer row fully on screen and the Selection Layer row: the number at
     * ≥ [numberSp], "100%" / "Normal" at one size ≥ [valueSp], the Selection row's two lines at
     * ≥ [selectionSp]; none cut, none over the eye, the mask square or the ≡, all inside the row.
     */
    fun check(s: ChromeScreen, c: EditorController, numberSp: Float, valueSp: Float, selectionSp: Float, minRows: Int) {
        val bad = mutableListOf<String>()
        val list = s.tagged(LayerWindowTags.LIST) ?: throw AssertionError("no layer list")
        var rows = 0
        for (layer in c.doc.layers) {
            val n = c.doc.indexOf(layer) + 1
            val row = s.tagged(LayerWindowTags.row(layer.id)) ?: continue
            if (row.height < 79f || row.top < list.top - 0.5f || row.bottom > list.bottom + 0.5f) continue
            rows++
            val values = s.tagged(LayerWindowTags.values(layer.id)) ?: run { bad += "layer $n: no values"; continue }
            val inValues = lines(s, values)
            val pct = inValues.firstOrNull { it.text == "100%" }
            val blend = inValues.firstOrNull { it.text == "Normal" }
            val number = lines(s, row).firstOrNull { it.text == "$n" && !values.contains(it.bounds.center) }
            if (pct == null || blend == null || number == null) {
                bad += "layer $n: number $number, values $inValues"
                continue
            }
            if (number.sp < numberSp) bad += "layer $n: the number at ${number.sp} sp (ibisPaint ≈ 18)"
            for (v in listOf(pct, blend)) if (v.sp < valueSp) bad += "layer $n: $v under $valueSp sp (ibisPaint ≈ 18)"
            if (Math.abs(pct.sp - blend.sp) > 0.01f) bad += "layer $n: \"100%\" and \"Normal\" at two sizes: $pct / $blend"
            val eye = Finger.control(s, LayerLabels.hide(n)) ?: run { bad += "layer $n: no eye"; continue }
            val handle = Finger.element(s, LayerLabels.reorder(n)) ?: run { bad += "layer $n: no ≡"; continue }
            val mask = if (layer.mask != null) Finger.control(s, LayerLabels.maskOf(n)) else null
            for (t in listOf(number, pct, blend)) {
                if (t.cut) bad += "layer $n: $t is cut"
                if (overlap(t.bounds, eye)) bad += "layer $n: $t over the eye $eye"
                if (overlap(t.bounds, handle)) bad += "layer $n: $t over the ≡ $handle"
                if (mask != null && overlap(t.bounds, mask)) bad += "layer $n: $t over the mask square $mask"
                if (!inside(t.bounds, row)) bad += "layer $n: $t outside its row $row"
            }
        }
        if (rows < minRows) bad += "only $rows rows on screen"
        val sel = s.tagged(LayerWindowTags.SELECTION_ROW)
        if (sel == null) bad += "no Selection Layer row" else {
            val inSel = lines(s, sel)
            for (label in listOf(LayerLabels.SELECTION_ROW, LayerLabels.NO_SELECTION)) {
                val t = inSel.firstOrNull { it.text == label }
                when {
                    t == null -> bad += "no \"$label\" in the Selection Layer row: $inSel"
                    t.sp < selectionSp -> bad += "$t under $selectionSp sp (ibisPaint ≈ 18)"
                    t.cut -> bad += "$t is cut"
                    !inside(t.bounds, sel) -> bad += "$t outside the Selection Layer row $sel"
                }
            }
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishrowtext392sandbox"])
class PolishLayerRowText392Test {
    @Test
    fun layerRowTextAtIbisPaintsSizesOnTheUsersPhone() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("layer rows at 392 dp") {
            val s = h.editor(IbisDocs.page(), IbisDocs::fourKinds)
            click("Open layers (active layer 5)")
            Smoke.pump(1_200)
            settle()
            PolishRowText.check(s, s.c, numberSp = 18f, valueSp = 18f, selectionSp = 18f, minRows = 3)
        }
        dog.interrupt()
        h.finish()
    }
}

@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishrowtext360sandbox"])
class PolishLayerRowText360Test {
    @Test
    fun layerRowTextScalesDownOnA360DpPhone() = IbisShots.shoot(
        "layer-window-360", "ibis-layer-window.png", IbisDocs.page(),
        setup = { c ->
            IbisDocs.fourKinds(c)
            // Layer 4 clipped, locked and alpha-locked: the narrowest values (the clip gutter) and
            // the fullest number line; layer 5 is the masked Tone layer (eye over mask square).
            c.doc.layers[3].apply { clipping = true; locked = true; alphaLocked = true }
            c.notifyLayersChanged()
        },
        drive = { click("Open layers (active layer 5)"); Smoke.pump(1_200); settle() },
        check = { s, _ -> PolishRowText.check(s, s.c, numberSp = 18f, valueSp = 13f, selectionSp = 16f, minRows = 3) },
    )
}
