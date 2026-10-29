package com.brushwork.paint.v110.a2

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.qa19.Qa19Harness
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
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.tools.PillColorCell
import com.brushwork.paint.ui.tools.formatCoordinate
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
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
 * v1.10 (R2, J20, UQ3; design 4.4 A2 step 6): where the Object colors cell (COLOR, tag
 * [V110Tags.PILL_COLOR], the real [PillColorCell]) sits in the X / Y pill, at 392 and 360 dp and
 * font scale 1.0 and 2.0.
 * - The pill alone, with a stand-in tool at the worst-case values of each unit ("-1,329.5" px,
 *   "101.6 mm", "-12.75 in"), with and without "Select several": row 1 is the same with and
 *   without COLOR (v1.9's row, cell for cell, 0.5 dp), no value is cut at the default Length
 *   step without "Select several"; COLOR is the first cell of row 2, 40 dp, under the move icon
 *   (same left), whole inside the 8 dp margins, the Scale row right of it. Beside "Select
 *   several" or with a long Length step (1,234.5 px, the "#" cell at its widest) row 1 can leave
 *   a worst-case value too little room (v1.10 Gate 2 fonts F4 keeps the default step's whole
 *   beside "Select several" at font scale 1.3 and 2.0, `Qa110FontScaleG2UiTest`; at 360 dp and
 *   font scale 1.0 "101.6 mm" is still cut there); COLOR cuts no text more, in every case.
 *   Folded: `[fold][color][trash][sel]`, 40 dp each and
 *   4 dp apart, one row.
 * - The real editor with a shape open in the Shape tool: COLOR under the move icon, its name.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The 360 dp
 * phone comes from `RuntimeEnvironment.setQualifiers` (as `PillSharpPlacementTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v110.a2.pillcolorplacementsandbox"])
class PillColorPlacementTest {

    @Test
    fun colorIsRow2sFirstCellUnderTheMoveIconAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in listOf(1f, 2f)) {
                    h.section("$width dp, font scale $scale: the pill") { pill(width, scale) }
                    h.section("$width dp, font scale $scale: the editor's Shape") { editor(h, width, scale) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    /** One stand-in object: the unit its pill shows and the value (both axes) at its widest text. */
    private data class Variant(val unit: LengthUnit, val inches: Float?, val px: Float, val shown: String)

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
            c.selectTool(ToolId.CURVE)
            val dpi = c.doc.dpi.toDouble()
            val variants = listOf(
                Variant(LengthUnit.PX, null, -1329.5f, "-1,329.5"),
                Variant(LengthUnit.MM, 4f, 0f, "101.6 mm"),
                Variant(LengthUnit.IN, -12.75f, 0f, "-12.75 in"),
            )
            val colors = A2FakeColorEditor(BLUE, RED)
            var color by mutableStateOf(false)
            var shown by mutableIntStateOf(0)
            activity.setContent {
                BrushworkTheme {
                    CompositionLocalProvider(LocalIncrements provides c.increments) {
                        // The editor's slot: the window less the pill's 8 dp margins.
                        Box(Modifier.fillMaxSize().padding(horizontal = IbisDims.PillStart)) {
                            // A new stand-in tool is a new pill (CoordinatePill reads the tool once).
                            key(shown) {
                                val cell: (@Composable () -> Unit)? = remember(color) { if (color) ({ PillColorCell(colors) }) else null }
                                CoordinatePill(c, Modifier.offset(y = 135.dp), colorCell = cell)
                            }
                        }
                    }
                }
            }
            SmokeUi.settle()
            fun dp(r: Rect) = Rect(r.left / density, r.top / density, r.right / density, r.bottom / density)
            fun placed() = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
            fun tagged(tag: String): Rect? =
                placed().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.let { dp(it.bounds) }
            fun described(label: String): Rect? =
                placed().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it == label } == true }
                    ?.let { dp(it.bounds) }
            fun texts(text: String): Int =
                placed().count { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text }
            val right = width - IbisDims.PillStart.value
            fun inside(what: String, r: Rect) =
                assertTrue("$what ($r) whole inside the 8 dp margins", r.left >= IbisDims.PillStart.value - EPS && r.right <= right + EPS && r.width > 0f)
            fun inOrder(what: String, rs: List<Pair<String, Rect>>) {
                for ((n, r) in rs) inside("$what: $n", r)
                for (i in 1 until rs.size) {
                    assertTrue("$what: ${rs[i - 1].first} before ${rs[i].first}", rs[i - 1].second.right <= rs[i].second.left + EPS)
                }
            }

            // The default Length step (10 px), then a long one (the "#" cell at its widest).
            for (longStep in listOf(false, true)) for (several in listOf(false, true)) for (v in variants) {
                c.increments.update { it.copy(lengthPx = if (longStep) 1234.5f else 10f) }
                val step = if (longStep) "a long step" else "the default step"
                val what = "$width dp, font $fontScale, ${v.unit}, ${if (several) "with" else "without"} Select several, $step"
                val at = if (v.inches != null) (v.inches * dpi).toFloat() else v.px
                // The pill's text: U+2212 for the minus, the unit after the number (px: none).
                val text = formatCoordinate(at, v.unit, dpi) + if (v.unit == LengthUnit.PX) "" else " ${v.unit.short}"
                assertEquals("$what: the worst case", v.shown, text.replace(MINUS, '-'))
                val inner = FakePillTool(c, listOf(Vec2(at, at), Vec2(0f, 0f)), pillUnit = v.unit).also { it.select(0) }
                @Suppress("UNCHECKED_CAST")
                (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = if (several) SeveralPillTool(c, inner) else inner
                color = false
                shown++
                SmokeUi.settle()
                fun row1(case: String): List<Pair<String, Rect>> = listOfNotNull(
                    "fold" to requireNotNull(described(FOLD)) { "$what, $case: the move icon" },
                    "X" to requireNotNull(described("X slider")) { "$what, $case: X" },
                    "Y" to requireNotNull(described("Y slider")) { "$what, $case: Y" },
                    "#" to requireNotNull(described(INCREMENTS_LABEL)) { "$what, $case: #" },
                    "trash" to requireNotNull(tagged(V17Tags.PILL_TRASH)) { "$what, $case: the trash" },
                    if (several) "sel" to requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "$what, $case: Select several" } else null,
                )
                val v19 = row1("without COLOR")
                assertNull("$what: no COLOR", tagged(V110Tags.PILL_COLOR))
                assertEquals("$what: Select several", several, tagged(V18Tags.PILL_SEVERAL) != null)
                inOrder("$what: v1.9's row 1", v19)
                assertEquals("$what: X and Y show the value", 2, texts(text))
                // v1.9's row 1 cuts no value at the default step without "Select several".
                // "Select several" or a long step can leave too little room for a worst-case
                // value (the pill then cuts it): COLOR cuts nothing more.
                val cutBefore = Qa19Ui.textOverflows()
                if (!longStep && !several) Qa19Ui.assertNoTextOverflow("$what, without COLOR")

                color = true
                SmokeUi.settle()
                val withColor = row1("with COLOR")
                for ((a, b) in v19.zip(withColor)) {
                    assertEquals("$what: row 1's ${a.first} stays", a.first, b.first)
                    assertEquals("$what: ${a.first}'s left", a.second.left, b.second.left, EPS)
                    assertEquals("$what: ${a.first}'s top", a.second.top, b.second.top, EPS)
                    assertEquals("$what: ${a.first}'s width", a.second.width, b.second.width, EPS)
                }
                assertEquals("$what: X and Y still show the whole value", 2, texts(text))
                val fold = withColor[0].second
                val cell = requireNotNull(tagged(V110Tags.PILL_COLOR)) { "$what: COLOR" }
                inside("$what: COLOR", cell)
                assertEquals("$what: COLOR is 40 dp", CELL, cell.width, EPS)
                assertEquals("$what: COLOR under the move icon", fold.left, cell.left, EPS)
                assertEquals("$what: COLOR in row 2", fold.center.y + ROW, cell.center.y, EPS)
                val scaleRow = requireNotNull(tagged(V17Tags.PILL_SCALE_ROW)) { "$what: the Scale row" }
                inOrder("$what: row 2", listOf("color" to cell, "Scale row" to scaleRow))
                assertEquals("$what: COLOR's name is its description", cell, described(ObjectColorLabels110.CELL))
                assertEquals("$what: COLOR cuts no text", cutBefore, Qa19Ui.textOverflows())

                if (!longStep && v.unit == LengthUnit.PX) {
                    // Folded: [fold][color][trash][sel], 40 dp each, 4 dp apart, no row 2.
                    SmokeUi.click(FOLD, exact = true)
                    assertNull("$what, folded: no Scale row", tagged(V17Tags.PILL_SCALE_ROW))
                    val folded = listOfNotNull(
                        "fold" to requireNotNull(described(UNFOLD)) { "$what, folded: the move icon" },
                        "color" to requireNotNull(tagged(V110Tags.PILL_COLOR)) { "$what, folded: COLOR" },
                        "trash" to requireNotNull(tagged(V17Tags.PILL_TRASH)) { "$what, folded: the trash" },
                        if (several) "sel" to requireNotNull(tagged(V18Tags.PILL_SEVERAL)) { "$what, folded: Select several" } else null,
                    )
                    inOrder("$what, folded", folded)
                    for ((n, r) in folded) {
                        assertEquals("$what, folded: $n is 40 dp", CELL, r.width, EPS)
                        assertEquals("$what, folded: $n in one row", folded[0].second.center.y, r.center.y, EPS)
                    }
                    for (i in 1 until folded.size) {
                        assertEquals("$what, folded: 4 dp before ${folded[i].first}", IbisDims.PillGap.value, folded[i].second.left - folded[i - 1].second.right, EPS)
                    }
                    Qa19Ui.assertNoTextOverflow("$what, folded")
                    SmokeUi.click(UNFOLD, exact = true)
                    assertNotNull("$what: unfolded again", tagged(V17Tags.PILL_SCALE_ROW))
                }
            }
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
        s.c.selectTool(ToolId.SHAPE)
        Qa19Ui.settle()
        assertNull("no shape open: no cell", s.tagged(V110Tags.PILL_COLOR))
        val tool = s.c.currentTool as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f) }
        s.c.drag(120f to 90f, 200f to 150f, 280f to 200f)
        Qa19Ui.settle()
        val cell = requireNotNull(s.tagged(V110Tags.PILL_COLOR)) { "the cell; shown: ${Qa19Ui.shown().take(80)}" }
        val fold = requireNotNull(s.clickable(FOLD)) { "the move icon" }
        assertEquals("COLOR is 40 dp", CELL, cell.width, EPS)
        assertEquals("COLOR under the move icon", fold.left, cell.left, EPS)
        assertEquals("COLOR in row 2", fold.center.y + ROW, cell.center.y, EPS)
        assertTrue("COLOR whole inside the 8 dp margins: $cell", cell.left >= IbisDims.PillStart.value - EPS && cell.right <= width - IbisDims.PillStart.value + EPS)
        assertNotNull("its name", s.clickable(ObjectColorLabels110.CELL))
        Qa19Ui.assertNoTextOverflow("the editor at $width dp, font $fontScale")
        tool.discard()
        Qa19Ui.settle()
        assertNull("no shape open: no cell", s.tagged(V110Tags.PILL_COLOR))
        Smoke.assertQuiet(s.c, "the editor at $width dp, font $fontScale")
    }

    /** [FakePillTool] with points to pick, so "Select several" ends row 1. */
    private class SeveralPillTool(c: EditorController, private val inner: FakePillTool) :
        Tool(c), PillPositionTool by inner, ScaledTool by inner, DeletingTool by inner, PointEditor {
        override val id: ToolId = ToolId.CURVE
        override val pointCount: Int get() = inner.points.size
        override val pointSelection: PointSelection get() = PointSelection.of(pointCount, 0)
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

        /** The pill's minus sign (`formatCoordinate`). */
        const val MINUS = '−'

        /** Row 2's centre under row 1's: the 32 dp row and the 4 dp gap. */
        val ROW = IbisDims.PillHeight.value + IbisDims.PillRowGap.value

        /** The move icon's names (CoordinatePill's `FoldCell` literals; no Labels constant holds them). */
        const val FOLD = "Fold the X / Y strip"
        const val UNFOLD = "Unfold the X / Y strip"
    }
}
