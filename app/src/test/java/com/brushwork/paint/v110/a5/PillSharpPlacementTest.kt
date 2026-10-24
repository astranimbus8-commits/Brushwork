package com.brushwork.paint.v110.a5

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
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectScale
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.CornerState
import com.brushwork.paint.tools.points.CornerTool
import com.brushwork.paint.tools.points.PointCornerEditor
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.tools.PillSharpCell
import com.brushwork.paint.ui.vector.ShapeStripItems
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 (A5, R12, J28): where the Sharp / Smooth cell (SHARP, tag [V110Tags.PILL_SHARP]) sits in
 * the X / Y pill, at 392 and 360 dp and font scale 1.0 and 2.0.
 * - The pill alone, with a stand-in tool whose Scale row and Object colors cell (COLOR, a 40 dp
 *   stand-in: A2 draws the real one) come and go, and the real cell: SHARP ends row 2, its centre
 *   under "Select several"'s (0.5 dp), 40 dp, whole inside the 8 dp margins, right of the trash;
 *   row 1 keeps v1.9's cells in v1.9's order; the Scale row (when shown) between COLOR and SHARP.
 *   Folded: `[fold][color][trash][sel][sharp]` and no row 2. Every face (smooth, sharp, mixed,
 *   greyed) in the same place; no text overflows.
 * - The real editor's Curve with a selected point: the same place.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The 360 dp
 * phone comes from `RuntimeEnvironment.setQualifiers` (as `MoreRowOffsetsTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a5.pillsharpplacementsandbox"])
class PillSharpPlacementTest {

    @Test
    fun sharpEndsRow2UnderSelectSeveralAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in listOf(1f, 2f)) {
                    h.section("$width dp, font scale $scale: the pill") { pill(width, scale) }
                    h.section("$width dp, font scale $scale: the editor's Curve") { editor(h, width, scale) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun pill(width: Int, fontScale: Float) {
        Qa19Ui.deviceFontScale(fontScale)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            val density = activity.resources.displayMetrics.density
            assertEquals("the phone", width, activity.resources.configuration.screenWidthDp)
            assertEquals("the font scale", fontScale, activity.resources.configuration.fontScale, 0.01f)
            val c = Smoke.controller(activity, Smoke.document(256, 256, layers = 1))
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            c.settings.coordinateStripFolded = false
            val tool = SharpPillTool(c, FakePillTool(c))
            @Suppress("UNCHECKED_CAST")
            (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = tool
            c.selectTool(ToolId.CURVE)
            // Long steps: both "#" cells at their widest.
            c.increments.update { it.copy(lengthPx = 1234.5f, scalePercent = 123.5f) }
            var color by mutableStateOf(false)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        // The editor's slot: the window less the pill's 8 dp margins.
                        Box(Modifier.fillMaxSize().padding(horizontal = IbisDims.PillStart)) {
                            CoordinatePill(
                                c,
                                Modifier.offset(y = 135.dp),
                                colorCell = if (color) ({ ColorStandIn() }) else null,
                                sharpCell = { PillSharpCell(tool.corners) },
                            )
                        }
                    }
                }
            }
            SmokeUi.settle()
            fun dp(r: Rect) = Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)
            fun tagged(tag: String): Rect? = RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.TestTag) == tag
            }?.let { dp(it.bounds) }
            fun described(label: String): Rect? = RobolectricUi.elements().lastOrNull { e ->
                e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true
            }?.let { dp(it.bounds) }
            val right = width - IbisDims.PillStart.value
            fun inside(what: String, r: Rect) =
                assertTrue("$what ($r) whole inside the 8 dp margins", r.left >= IbisDims.PillStart.value - EPS && r.right <= right + EPS && r.width > 0f)
            fun inOrder(what: String, rs: List<Pair<String, Rect>>) {
                for ((n, r) in rs) inside("$what: $n", r)
                for (i in 1 until rs.size) {
                    assertTrue("$what: ${rs[i - 1].first} before ${rs[i].first}", rs[i - 1].second.right <= rs[i].second.left + EPS)
                }
            }

            for (withColor in listOf(false, true)) for (withScale in listOf(true, false)) {
                val what = "$width dp, font $fontScale, ${if (withColor) "with" else "without"} COLOR, ${if (withScale) "with" else "without"} the Scale row"
                color = withColor
                tool.scaleShown = withScale
                tool.corners.corner = CornerState.SMOOTH
                SmokeUi.settle()
                val fold = requireNotNull(described(FOLD)) { "$what: the move icon" }
                val steps = requireNotNull(described(INCREMENTS_LABEL)) { "$what: #" }
                val trash = requireNotNull(tagged(V17Tags.PILL_TRASH)) { "$what: the trash" }
                val several = requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "$what: Select several" }
                // Row 1: v1.9's cells, in order, "Select several" last.
                inOrder("$what: row 1", listOf("fold" to fold, "#" to steps, "trash" to trash, "sel" to several))
                val sharp = requireNotNull(tagged(V110Tags.PILL_SHARP)) { "$what: SHARP" }
                inside("$what: SHARP", sharp)
                assertEquals("$what: SHARP is 40 dp", CELL, sharp.width, EPS)
                assertEquals("$what: SHARP's centre under Select several's", several.center.x, sharp.center.x, EPS)
                assertEquals("$what: SHARP in row 2", several.center.y + ROW, sharp.center.y, EPS)
                assertTrue("$what: SHARP right of the trash ($sharp, $trash)", sharp.left >= trash.right - EPS)
                val scaleRow = tagged(V17Tags.PILL_SCALE_ROW)
                assertEquals("$what: the Scale row", withScale, scaleRow != null)
                val colorCell = tagged(COLOR_TAG)
                assertEquals("$what: COLOR", withColor, colorCell != null)
                val row2 = listOfNotNull(colorCell?.let { "color" to it }, scaleRow?.let { "Scale row" to it }, "sharp" to sharp)
                inOrder("$what: row 2", row2)
                if (colorCell != null) assertEquals("$what: COLOR under the move icon", fold.left, colorCell.left, EPS)
                // Every face in the same place.
                for (state in CornerState.entries) {
                    tool.corners.corner = state
                    SmokeUi.settle()
                    val r = requireNotNull(tagged(V110Tags.PILL_SHARP)) { "$what, $state: SHARP" }
                    assertEquals("$what, $state: same left", sharp.left, r.left, EPS)
                    assertEquals("$what, $state: same top", sharp.top, r.top, EPS)
                    assertEquals("$what, $state: same width", sharp.width, r.width, EPS)
                }
                Qa19Ui.assertNoTextOverflow(what)
            }
            // The name and a tap (the stand-in editor counts the taps).
            color = true
            tool.scaleShown = true
            tool.corners.corner = CornerState.MIXED
            SmokeUi.settle()
            assertEquals(PointLabels110.MIXED_MAKES_SHARP, PillTestKit.sharpName())
            PillTestKit.tapSharp()
            assertEquals("a Mixed tap asks for sharp", listOf(true), tool.corners.taps)
            assertEquals(ShapeStripItems.SMOOTH_LABEL, PillTestKit.sharpName())

            // Folded: [fold][color][trash][sel][sharp], 40 dp each, no row 2.
            SmokeUi.click(FOLD, exact = true)
            assertNull("folded, no Scale row", tagged(V17Tags.PILL_SCALE_ROW))
            val folded = listOf(
                "fold" to requireNotNull(described(UNFOLD)) { "folded: the move icon" },
                "color" to requireNotNull(tagged(COLOR_TAG)) { "folded COLOR" },
                "trash" to requireNotNull(tagged(V17Tags.PILL_TRASH)) { "folded trash" },
                "sel" to requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "folded Select several" },
                "sharp" to requireNotNull(tagged(V110Tags.PILL_SHARP)) { "folded SHARP" },
            )
            inOrder("folded", folded)
            for ((n, r) in folded) assertEquals("folded: $n is 40 dp", CELL, r.width, EPS)
            assertEquals("folded: one row", folded[0].second.center.y, folded[4].second.center.y, EPS)
            color = false
            SmokeUi.settle()
            assertNotNull("folded without COLOR: SHARP still last", tagged(V110Tags.PILL_SHARP))
            assertTrue(requireNotNull(tagged(V110Tags.PILL_SHARP)).left >= requireNotNull(tagged(V18Tags.PILL_SEVERAL)).right - EPS)
            Qa19Ui.assertNoTextOverflow("$width dp, font $fontScale, folded")
            SmokeUi.click(UNFOLD, exact = true)
            Smoke.assertQuiet(c, "the pill at $width dp, font scale $fontScale")
        } finally {
            runCatching { ctl.pause().stop().destroy() }
            Qa19Ui.deviceFontScale(1f)
        }
    }

    private fun editor(h: Qa19Harness, width: Int, fontScale: Float) {
        val s = h.editor(fontScale = fontScale)
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        s.c.snapping.enabled = false
        s.c.selectTool(ToolId.CURVE)
        Qa19Ui.settle()
        val tool = s.c.currentTool as CurveTool
        for (p in listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))) assertTrue(tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.none(3))
        Qa19Ui.settle()
        assertNull("no point selected: no cell", s.tagged(V110Tags.PILL_SHARP))
        tool.selectPoints(PointSelection.of(3, 1))
        Qa19Ui.settle()
        val sharp = requireNotNull(s.tagged(V110Tags.PILL_SHARP)) { "the cell; shown: ${Qa19Ui.shown().take(80)}" }
        val several = requireNotNull(s.tagged(V18Tags.PILL_SEVERAL)) { "Select several" }
        val trash = requireNotNull(s.tagged(V17Tags.PILL_TRASH)) { "the trash" }
        assertEquals("SHARP's centre under Select several's", several.center.x, sharp.center.x, EPS)
        assertEquals("SHARP in row 2", several.center.y + ROW, sharp.center.y, EPS)
        assertTrue("SHARP right of the trash", sharp.left >= trash.right - EPS)
        assertTrue("SHARP whole inside the 8 dp margins: $sharp", sharp.left >= IbisDims.PillStart.value - EPS && sharp.right <= width - IbisDims.PillStart.value + EPS)
        assertEquals(ShapeStripItems.SHARP_LABEL, PillTestKit.sharpName())
        Qa19Ui.assertNoTextOverflow("the editor at $width dp, font $fontScale")
        tool.discard()
        Qa19Ui.settle()
        Smoke.assertQuiet(s.c, "the editor at $width dp, font $fontScale")
    }

    /** A 40 dp stand-in for the Object colors cell (A2's). */
    @Composable
    private fun ColorStandIn() {
        Box(Modifier.width(IbisDims.PillIconTouch).requiredHeight(IbisDims.PillCellTouch).testTag(COLOR_TAG))
    }

    /** A corner editor whose state the test sets; a tap flips it as a real one would and is counted. */
    private class StubCorners : PointCornerEditor {
        override var corner: CornerState? by mutableStateOf(CornerState.SMOOTH)
        override val cornerLocked: Boolean = false
        val taps = mutableListOf<Boolean>()
        override fun setSelectedSharp(sharp: Boolean): Boolean {
            taps += sharp
            corner = if (sharp) CornerState.SHARP else CornerState.SMOOTH
            return true
        }
    }

    /** [FakePillTool] with points to pick ("Select several" ends row 1), a Scale row that can hide, and [corners]. */
    private class SharpPillTool(c: EditorController, private val inner: FakePillTool) :
        Tool(c), PillPositionTool by inner, ScaledTool, DeletingTool by inner, PointEditor, CornerTool {
        override val id: ToolId = ToolId.CURVE
        val corners = StubCorners()
        override val pointCorners: PointCornerEditor get() = corners
        var scaleShown: Boolean by mutableStateOf(true)
        override val objectScale: ObjectScale? get() = if (scaleShown) inner.objectScale else null
        override val pointCount: Int get() = inner.points.size
        override val pointSelection: PointSelection get() = PointSelection.of(pointCount, 1)
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
        const val CELL = 40f
        const val EPS = 0.5f

        /** Row 2's centre under row 1's: the 32 dp row and the 4 dp gap. */
        val ROW = IbisDims.PillHeight.value + IbisDims.PillRowGap.value
        const val COLOR_TAG = "test.a5.pill.color"

        /** The move icon's names (CoordinatePill's `FoldCell` literals; no Labels constant holds them). */
        const val FOLD = "Fold the X / Y strip"
        const val UNFOLD = "Unfold the X / Y strip"
    }
}
