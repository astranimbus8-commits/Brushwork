package com.brushwork.paint.v110.lead

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredHeight
import androidx.compose.foundation.layout.width
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.tools.PillCell.COLOR
import com.brushwork.paint.ui.tools.PillCell.FOLD
import com.brushwork.paint.ui.tools.PillCell.SCALE
import com.brushwork.paint.ui.tools.PillCell.SEVERAL
import com.brushwork.paint.ui.tools.PillCell.SHARP
import com.brushwork.paint.ui.tools.PillCell.STEP
import com.brushwork.paint.ui.tools.PillCell.TRASH
import com.brushwork.paint.ui.tools.PillCell.X
import com.brushwork.paint.ui.tools.PillCell.Y
import com.brushwork.paint.ui.tools.pillRow1
import com.brushwork.paint.ui.tools.pillRow2
import com.brushwork.paint.ui.tools.scaleRowMaxWidth
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 F0 (J20, J28, finding 32; lead, frozen): the pill's budget with the Object colors cell
 * (COLOR) and the Sharp / Smooth cell (SHARP).
 * - Pure, at 392 dp (376 usable) and 360 dp (344 usable): [scaleRowMaxWidth]; unfolded row 1 is
 *   exactly v1.9's with or without the new cells; row 2 is `[color][Scale row][sharp]`, or
 *   `[color][sharp][Scale row]` when row 1 does not end with "Select several", so SHARP is never
 *   under the trash; folded `[✥][color][🗑][sel][sharp]` (216 dp) and no row 2; without the new
 *   cells row 2 is v1.9's.
 * - On screen at 360 dp, font scale 1.0 and 2.0, with 40 dp stand-ins for the two cells (A2 and
 *   A5 draw the real ones): every cell whole inside the 8 dp margins; COLOR under the move icon;
 *   SHARP's centre under "Select several"'s; "Scale increments" at least 40 dp, the Scale cells at
 *   least 72 dp, the Scale row at most [scaleRowMaxWidth]; folded, the five cells of 40 dp in
 *   order. One UI test (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q360, instrumentedPackages = ["com.brushwork.paint.v110.lead.pillbudgetsandbox"])
class PillBudgetTest {

    @Test
    fun rowsAndScaleRowWidthAt392And360AndFolded() {
        // scaleRowMaxWidth: v1.9's 328 less 44 per new cell beside the Scale row.
        assertEquals(328f, scaleRowMaxWidth(U392, sharp = false, color = false), 0f)
        assertEquals(328f, scaleRowMaxWidth(U392, sharp = true, color = false), 0f)
        assertEquals(288f, scaleRowMaxWidth(U392, sharp = true, color = true), 0f)
        assertEquals(328f, scaleRowMaxWidth(U360, sharp = false, color = false), 0f)
        assertEquals(300f, scaleRowMaxWidth(U360, sharp = false, color = true), 0f)
        assertEquals(256f, scaleRowMaxWidth(U360, sharp = true, color = true), 0f)
        for (usable in listOf(U392, U360)) {
            val max = scaleRowMaxWidth(usable, sharp = true, color = true)
            assertTrue("$usable: COLOR, the Scale row and SHARP fit", CELL + GAP + max + GAP + CELL <= usable)
            assertTrue("$usable: the Scale row's least width ($SCALE_ROW_MIN) fits", SCALE_ROW_MIN <= max)
        }

        val flags = listOf(false, true)
        for (trash in flags) for (several in flags) for (color in flags) for (sharp in flags) {
            val what = "trash $trash, several $several, color $color, sharp $sharp"
            // Unfolded and without a position: v1.9's row 1, whatever the new cells.
            assertEquals("$what: unfolded row 1", pillRow1(true, false, trash, several), pillRow1(true, false, trash, several, color, sharp))
            assertEquals("$what: no position", pillRow1(false, false, trash, several), pillRow1(false, false, trash, several, color, sharp))
            // Folded: [✥][color][🗑][sel][sharp], 40 dp each, and no row 2.
            val folded = pillRow1(true, true, trash, several, color, sharp)
            val expected = listOfNotNull(FOLD, COLOR.takeIf { color }, TRASH.takeIf { trash }, SEVERAL.takeIf { several }, SHARP.takeIf { sharp })
            assertEquals("$what: folded row 1", expected, folded)
            assertTrue("$what: folded fits at 360 dp", folded.size * CELL + (folded.size - 1) * GAP <= U360)
            for (scale in flags) {
                assertNull("$what: folded, no row 2", pillRow2(true, true, scale, color, sharp, row1LastIsSeveral = several))
                assertNull("$what: no position, no row 2", pillRow2(false, false, scale, color, sharp, row1LastIsSeveral = several))
                val row1 = pillRow1(true, false, trash, several, color, sharp)
                val row2 = pillRow2(true, false, scale, color, sharp, row1LastIsSeveral = row1.last() == SEVERAL)
                if (!color && !sharp) {
                    assertEquals("$what, scale $scale: v1.9's row 2", if (scale) listOf(SCALE) else null, row2)
                    continue
                }
                val cells = requireNotNull(row2) { "$what, scale $scale: row 2 with a new cell" }
                if (color) assertEquals("$what, scale $scale: COLOR first, under the move icon", COLOR, cells.first())
                assertEquals("$what, scale $scale: the Scale row", scale, SCALE in cells)
                if (sharp && row1.last() == SEVERAL) {
                    assertEquals("$what, scale $scale: SHARP last, under Select several", SHARP, cells.last())
                }
                if (sharp && row1.last() != SEVERAL) {
                    // Never under the trash: right after COLOR (or first), before the Scale row.
                    assertEquals("$what, scale $scale: SHARP after COLOR", if (color) 1 else 0, cells.indexOf(SHARP))
                }
            }
        }
        // The cases of design 3.2 written out.
        assertEquals(listOf(COLOR, SCALE, SHARP), pillRow2(true, false, scaleRow = true, color = true, sharp = true, row1LastIsSeveral = true))
        assertEquals(listOf(COLOR, SHARP, SCALE), pillRow2(true, false, scaleRow = true, color = true, sharp = true, row1LastIsSeveral = false))
        assertEquals(listOf(COLOR), pillRow2(true, false, scaleRow = false, color = true, sharp = false, row1LastIsSeveral = true))
        assertEquals(listOf(FOLD, X, Y, STEP, TRASH, SEVERAL), pillRow1(true, false, trash = true, several = true, color = true, sharp = true))
    }

    @Test
    fun scaleIncrementsStaysWholeAt360AndFontScale2() = Qa19Ui.uiTest { h ->
        for (scale in listOf(1f, 2f)) h.section("360 dp, font scale $scale") { pill(scale) }
    }

    private fun pill(fontScale: Float) {
        Qa19Ui.deviceFontScale(fontScale)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val density = activity.resources.displayMetrics.density
            val config = activity.resources.configuration
            assertEquals("the phone is 360 dp wide", 360, config.screenWidthDp)
            assertEquals("the font scale", fontScale, config.fontScale, 0.01f)
            val c = Smoke.controller(activity, Smoke.document(256, 256, layers = 1))
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            c.settings.coordinateStripFolded = false
            val fake = FakePillTool(c)
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = SeveralTool(c, fake)
            c.selectTool(ToolId.CURVE)
            // Long steps: both "#" cells at their widest.
            c.increments.update { it.copy(lengthPx = 1234.5f, scalePercent = 123.5f) }
            var cells by mutableStateOf(true)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        // The editor's slot: the window less the pill's 8 dp margins.
                        Box(Modifier.fillMaxSize().padding(horizontal = IbisDims.PillStart)) {
                            CoordinatePill(
                                c,
                                Modifier.offset(y = 135.dp),
                                colorCell = if (cells) ({ StandIn(COLOR_TAG) }) else null,
                                sharpCell = if (cells) ({ StandIn(SHARP_TAG) }) else null,
                            )
                        }
                    }
                }
            }
            SmokeUi.settle()
            fun dp(r: Rect) = Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)
            fun described(label: String): Rect = dp(
                requireNotNull(RobolectricUi.elements().lastOrNull { e ->
                    e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
                }) { "no \"$label\"; shown: ${SmokeUi.shown().take(60)}" }.bounds,
            )
            fun tagged(tag: String): Rect? = RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
            }?.let { dp(it.bounds) }
            fun inside(what: String, r: Rect) =
                assertTrue("$what ($r) whole inside the 8 dp margins", r.left >= 8f - EPS && r.right <= 352f + EPS && r.width > 0f)
            fun inOrder(what: String, rs: List<Pair<String, Rect>>) {
                for ((n, r) in rs) inside("$what: $n", r)
                for (i in 1 until rs.size) {
                    assertTrue("$what: ${rs[i - 1].first} before ${rs[i].first}", rs[i - 1].second.right <= rs[i].second.left + EPS)
                }
            }

            // ------------------------------------------------------------ unfolded
            val fold = described("Fold the X / Y strip")
            val several = requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "Select several" }
            inOrder(
                "row 1",
                listOf(
                    "fold" to fold, "X" to described("X slider"), "Y" to described("Y slider"), "#" to described(INCREMENTS_LABEL),
                    "trash" to requireNotNull(tagged(V17Tags.PILL_TRASH)) { "the trash cell" }, "sel" to several,
                ),
            )
            val color = requireNotNull(tagged(COLOR_TAG)) { "COLOR" }
            val sharp = requireNotNull(tagged(SHARP_TAG)) { "SHARP" }
            val keep = described(PillLabels.KEEP_PROPORTIONS)
            val scaleX = described(PillLabels.SCALE_X)
            val scaleY = described(PillLabels.SCALE_Y)
            val increments = described(PillLabels.SCALE_INCREMENTS)
            inOrder(
                "row 2",
                listOf("color" to color, "keep" to keep, "Scale X" to scaleX, "Scale Y" to scaleY, "Scale increments" to increments, "sharp" to sharp),
            )
            assertEquals("row 2 starts 4 dp under row 1", fold.center.y + CELL_ROW, color.center.y, EPS)
            assertEquals("COLOR under the move icon", fold.left, color.left, EPS)
            assertEquals("COLOR is 40 dp", CELL, color.width, EPS)
            assertEquals("SHARP is 40 dp", CELL, sharp.width, EPS)
            assertEquals("SHARP's centre under Select several's", several.center.x, sharp.center.x, EPS)
            assertTrue("\"Scale increments\" whole: ${increments.width}", increments.width >= 40f - EPS)
            assertEquals("Keep is 40 dp", CELL, keep.width, EPS)
            for ((n, r) in listOf("Scale X" to scaleX, "Scale Y" to scaleY)) {
                assertTrue("$n a cell's least width: ${r.width}", r.width >= 72f - EPS)
            }
            val scaleRow = requireNotNull(tagged(V17Tags.PILL_SCALE_ROW)) { "the Scale row" }
            assertTrue("the Scale row ${scaleRow.width} at most ${scaleRowMaxWidth(U360, true, true)}", scaleRow.width <= scaleRowMaxWidth(U360, sharp = true, color = true) + EPS)

            // Without the cells: v1.9's pill.
            cells = false
            SmokeUi.settle()
            assertNull("no COLOR", tagged(COLOR_TAG))
            assertNull("no SHARP", tagged(SHARP_TAG))
            assertEquals("v1.9's Scale row under the move icon", fold.left, requireNotNull(tagged(V17Tags.PILL_SCALE_ROW)).left, EPS)
            cells = true
            SmokeUi.settle()

            // ------------------------------------------------------------ folded: [✥][color][🗑][sel][sharp]
            SmokeUi.click("Fold the X / Y strip", exact = true)
            assertNull("folded, no Scale row", tagged(V17Tags.PILL_SCALE_ROW))
            val folded = listOf(
                "fold" to described("Unfold the X / Y strip"),
                "color" to requireNotNull(tagged(COLOR_TAG)) { "folded COLOR" },
                "trash" to requireNotNull(tagged(V17Tags.PILL_TRASH)) { "folded trash" },
                "sel" to requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "folded Select several" },
                "sharp" to requireNotNull(tagged(SHARP_TAG)) { "folded SHARP" },
            )
            inOrder("folded", folded)
            for ((n, r) in folded) assertEquals("folded: $n is 40 dp", CELL, r.width, EPS)
            assertEquals("folded: 216 dp", 216f, described("Center").width, EPS)
            SmokeUi.click("Unfold the X / Y strip", exact = true)
            Smoke.assertQuiet(c, "the pill at font scale $fontScale")
        } finally {
            runCatching { ctl.pause().stop().destroy() }
        }
    }

    /** A 40 dp stand-in for a new cell (the pill's icon cells: 40 wide, 40 dp touch). */
    @Composable
    private fun StandIn(tag: String) {
        Box(Modifier.width(IbisDims.PillIconTouch).requiredHeight(IbisDims.PillCellTouch).testTag(tag))
    }

    /** [FakePillTool] with points to pick, so "Select several" ends row 1 (as for every tool with SHARP). */
    private class SeveralTool(c: EditorController, private val inner: FakePillTool) :
        Tool(c), PillPositionTool by inner, ScaledTool by inner, DeletingTool by inner, PointEditor {
        override val id: ToolId = ToolId.CURVE
        override val pointCount: Int get() = inner.points.size
        override val pointSelection: PointSelection get() = PointSelection.none(pointCount)
        override fun selectPoints(s: PointSelection) {}
        override var selectSeveral: Boolean by mutableStateOf(false)
        override fun pointAt(i: Int): Vec2 = inner.points[i]
        override fun beginGroupEdit(label: String) {}
        override fun setGroupTransform(m: Affine2) {}
        override fun endGroupEdit() {}
        override fun deleteSelectedPoints(): Boolean = false
        override val minPoints: Int = 2
    }

    private companion object {
        const val U392 = 376f
        const val U360 = 344f
        const val CELL = 40f
        const val GAP = 4f

        /** Row 2's centre under row 1's: the 32 dp row and the 4 dp gap. */
        const val CELL_ROW = 36f

        /** Keep 40, two Scale cells of 72, the narrowest "Scale increments" 40, three gaps. */
        const val SCALE_ROW_MIN = 40f + 72f + 72f + 40f + 3 * 4f
        const val EPS = 0.75f
        const val COLOR_TAG = "test.pill.color"
        const val SHARP_TAG = "test.pill.sharp"
    }
}
