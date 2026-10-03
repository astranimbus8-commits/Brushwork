package com.brushwork.paint.qa16

import androidx.compose.ui.geometry.Rect
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerWindowTags
import com.brushwork.paint.ui.theme.IbisDims
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v16 polish review: the 18 sp layer number must not squeeze the number line's badges. On a
 * 360 dp phone a two-digit row that edits its mask, is clipped, alpha-locked and locked has the
 * fullest number line: the α + lock and lock badges keep their own width, the number takes the
 * room they leave (fitted down from 18 sp, never under the minimum, never cut), and the MASK
 * badge gives way (the mask square still marks the edit target: "Edit content 14"); nothing
 * overlaps. Unlocked, the same row has room for MASK again, whole.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishrowbadgessandbox"])
class PolishRowBadgesTest {
    @Test
    fun twoDigitNumbersLeaveTheBadgesTheirWidthAt360Dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        val h = ChromeHarness()
        h.section("a full number line at 360 dp") {
            val s = h.editor(Smoke.document(300, 430, layers = 14, whiteBottom = true)) { c ->
                val top = c.doc.layers.last()
                c.selectLayer(top)
                c.addMask(top)
                top.apply { clipping = true; locked = true; alphaLocked = true }
                c.notifyLayersChanged()
            }
            val top = s.c.doc.layers.last()
            assertTrue("editing the mask", top.editingMask)
            click("Open layers (active layer 14)")
            Smoke.pump(1_200)
            settle()
            val id = top.id
            fun need(tag: String) = s.tagged(tag) ?: throw AssertionError("no $tag on screen")
            /** The number line's parts: the number, then the badges named in [badges]; [bad] gets what is wrong. */
            fun check(where: String, badges: List<String>, bad: MutableList<String>) {
                val box = need(LayerWindowTags.number(id))
                val row = need(LayerWindowTags.row(id))
                val texts = PolishRowText.lines(s, row)
                val number = texts.firstOrNull { it.text == "14" && box.contains(it.bounds.center) }
                when {
                    number == null -> bad += "$where: no \"14\" in its box $box: $texts"
                    number.cut -> bad += "$where: the number is cut: $number"
                    number.sp < IbisDims.LayerRowTextMin.value -> bad += "$where: the number under the minimum: $number"
                }
                val parts = mutableListOf<Pair<String, Rect>>()
                number?.let { parts += "number" to it.bounds }
                for (kind in listOf(LayerWindowTags.BADGE_MASK, LayerWindowTags.BADGE_ALPHA, LayerWindowTags.BADGE_LOCK)) {
                    val b = s.tagged(LayerWindowTags.badge(id, kind))
                    if (kind !in badges) {
                        if (b != null) bad += "$where: a $kind badge $b"
                        continue
                    }
                    if (b == null) { bad += "$where: no $kind badge"; continue }
                    parts += kind to b
                    when (kind) {
                        LayerWindowTags.BADGE_MASK -> {
                            val t = texts.firstOrNull { it.text == "MASK" }
                            if (t == null) bad += "$where: no MASK text: $texts" else if (t.cut) bad += "$where: MASK is cut: $t"
                        }
                        // α at 10 sp bold beside a 9 dp lock.
                        LayerWindowTags.BADGE_ALPHA -> if (b.width < 13f) bad += "$where: the α + lock badge squeezed to ${b.width} dp"
                        LayerWindowTags.BADGE_LOCK -> if (b.width < IbisDims.LayerLockIcon.value - 0.5f) bad += "$where: the lock squeezed to ${b.width} dp"
                    }
                }
                for (i in parts.indices) for (j in i + 1 until parts.size) {
                    if (overlap(parts[i].second, parts[j].second)) bad += "$where: ${parts[i].first} ${parts[i].second} over ${parts[j].first} ${parts[j].second}"
                }
                for ((name, b) in parts) if (b.left < row.left - 0.5f || b.right > row.right + 0.5f) bad += "$where: $name $b outside its row $row"
                println("[qa16] 360 dp number line, $where: box $box, number $number, ${parts.joinToString { "${it.first} ${it.second}" }}")
            }
            val bad = mutableListOf<String>()
            check("locked", listOf(LayerWindowTags.BADGE_ALPHA, LayerWindowTags.BADGE_LOCK), bad)
            if (!SmokeUi.has(LayerLabels.editContent(14), exact = true)) bad += "locked: the mask square does not say it is edited"
            top.apply { locked = false; alphaLocked = false }
            s.c.notifyLayersChanged()
            Smoke.pump(300)
            settle()
            check("unlocked", listOf(LayerWindowTags.BADGE_MASK), bad)
            assertTrue(bad.joinToString("\n"), bad.isEmpty())
        }
        dog.interrupt()
        h.finish()
    }

    private fun overlap(a: Rect, b: Rect): Boolean =
        a.left < b.right - 0.5f && b.left < a.right - 0.5f && a.top < b.bottom - 0.5f && b.top < a.bottom - 0.5f
}
