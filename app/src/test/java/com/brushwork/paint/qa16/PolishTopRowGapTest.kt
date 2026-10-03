package com.brushwork.paint.qa16

import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v16 polish 4: like ibisPaint, the top row leaves a gap after Redo, so undo / redo read as a
 * pair apart from the mode circles. On the user's 392 dp phone the centres are 24, 72, then
 * 128 + 48·(i − 2) (an 8 dp gap; More's circle ends at 388, its target at 392). On narrower
 * screens the gap is min(8, w − n·pitch) and never negative, so the row still fits: at 360 dp
 * 22, 66, then 118 + 44·(i − 2), More's target ending at 360; at the 336 dp fold and below it
 * nothing passes the screen's edge. Landscape and tablets stay left-aligned at the 48 dp pitch
 * with the same gap.
 */
class PolishTopRowGapTest {

    private val labels = listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options")

    @Test
    fun centresLeaveAGapAfterRedo() {
        val r392 = ChromeLayout.topRow(392f)
        val want392 = listOf(24f, 72f, 128f, 176f, 224f, 272f, 320f, 368f)
        for (i in 0 until 8) assertEquals("392 dp, ${labels[i]}", want392[i], r392.centerX(i), 0.01f)
        val r360 = ChromeLayout.topRow(360f)
        val want360 = listOf(22f, 66f, 118f, 162f, 206f, 250f, 294f, 338f)
        for (i in 0 until 8) assertEquals("360 dp, ${labels[i]}", want360[i], r360.centerX(i), 0.01f)
        for (w in listOf(600f, 873f, 1280f)) {
            val r = ChromeLayout.topRow(w)
            for (i in 0 until 8) assertEquals("$w dp: left-aligned at 48 with the gap, ${labels[i]}", 24f + 48f * i + (if (i >= 2) 8f else 0f), r.centerX(i), 0.01f)
        }
    }

    @Test
    fun theGapNeverPushesTheRowOffTheScreen() {
        var w = 200f
        while (w <= 1000f) {
            val r = ChromeLayout.topRow(w)
            val n = r.slots.size
            val gap = r.centerX(2) - r.centerX(1) - r.pitch
            assertTrue("$w dp: the gap after Redo $gap is 0..8", gap >= -0.01f && gap <= 8.01f)
            assertEquals("$w dp: the gap is min(8, w − n·pitch)", minOf(8f, maxOf(0f, w - n * r.pitch)), gap, 0.01f)
            assertTrue("$w dp: undo and redo touch", Math.abs(r.centerX(1) - r.centerX(0) - r.pitch) < 0.01f)
            for (i in 3 until n) assertEquals("$w dp: slot $i at the pitch", r.pitch, r.centerX(i) - r.centerX(i - 1), 0.01f)
            val end = r.centerX(n - 1) + r.pitch / 2f
            assertTrue("$w dp: the last target ends at $end", end <= w + 0.01f)
            w += 1f
        }
    }
}

/** The placed top row at 392 dp: the circles' targets at the new centres. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishgap392sandbox"])
class PolishTopRowGap392UiTest {
    @Test
    fun theTopRowHasTheGapOnTheUsersPhone() = polishTopRowPlaced(392f, listOf(24f, 72f, 128f, 176f, 224f, 272f, 320f, 368f), 48f)
}

/** The placed top row at 360 dp: the gap, and More's target ends at the screen's edge. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.polishgap360sandbox"])
class PolishTopRowGap360UiTest {
    @Test
    fun theTopRowHasTheGapAndFitsAt360Dp() = polishTopRowPlaced(360f, listOf(22f, 66f, 118f, 162f, 206f, 250f, 294f, 338f), 44f)
}

private fun polishTopRowPlaced(width: Float, centres: List<Float>, pitch: Float) {
    ShadowLog.stream = null
    SmokeUi.installTestRecomposer()
    val dog = Smoke.watchdog()
    val h = ChromeHarness()
    h.section("top row at $width dp") {
        val s = h.editor()
        assertEquals("screen width", width, s.widthDp, 0.5f)
        val bad = mutableListOf<String>()
        listOf("Undo", "Redo", "Vector", "Selection", "Stabilizer", "Grid", "Ruler", "More options").forEachIndexed { i, label ->
            val b = s.clickable(label) ?: run { bad += "no \"$label\""; return@forEachIndexed }
            if (Math.abs(b.center.x - centres[i]) > 1f) bad += "$label centre ${b.center.x}, expected ${centres[i]}"
            if (Math.abs(b.width - pitch) > 1f) bad += "$label target ${b.width} wide, expected $pitch"
            if (b.right > width + 0.5f) bad += "$label ends at ${b.right}, past the screen's $width"
        }
        assertTrue(bad.joinToString("\n"), bad.isEmpty())
    }
    dog.interrupt()
    h.finish()
}
