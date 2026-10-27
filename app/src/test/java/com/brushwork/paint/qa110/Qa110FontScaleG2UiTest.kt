package com.brushwork.paint.qa110

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.key
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.core.Affine2
import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Document
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.LayerWalk
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.DeletingTool
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.PillPositionTool
import com.brushwork.paint.tools.ScaledTool
import com.brushwork.paint.tools.Tool
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.points.PointEditor
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.LocalIncrements
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.PointLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.CoordinatePill
import com.brushwork.paint.ui.tools.FakePillTool
import com.brushwork.paint.ui.tools.INCREMENTS_LABEL
import com.brushwork.paint.ui.tools.formatCoordinate
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.vector.CurveKeys
import com.brushwork.paint.ui.vector.CurveStripUi
import com.brushwork.paint.ui.vector.POINT_THICKNESS_LABEL
import com.brushwork.paint.ui.vector.PaintModeTags
import com.brushwork.paint.ui.vector.curveFacts
import com.brushwork.paint.ui.vector.curveSpec
import com.brushwork.paint.vector.VectorContent
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.10 Gate 2 fonts (F8, I27): the lane's screens at 392 and 360 dp and font scale 1.3 and 2.0,
 * each with no text past its box ([Qa19Ui.assertNoTextOverflow], no exception; the documents'
 * names fit at font scale 1, the Large fonts rule):
 * - F1 the layer window over raster, text ("T" badge) and vector ("V") layers and a saved
 *   selection: every layer's name and the saved selection's whole inside the window;
 * - F2, F3 the Object colors sheet from a Polyline's pill: its title (`BwSheet`'s header) and the
 *   color body ("Previous", "Current");
 * - F4 the X / Y pill alone beside "Select several", a stand-in tool at each unit's worst-case
 *   value ("-1,329.5" px, "101.6 mm", "-12.75 in") at the default step: X and Y show it whole;
 * - F5 a finger's tap on Polyline's Sharp / Smooth cell: the bubble's reason
 *   ([PointLabels110.POLYLINE_ALWAYS_SHARP]) whole, inside the screen;
 * - F6 the raster selection bar: every item's word whole;
 * - F7 Curve and Path open, a point at 150 %: on the More row the thickness slider comes before
 *   "All points 100 %" (its line, its place on it), no hidden item; the settings sheet offers the
 *   paint mode's Fill segment and no "No stroke" chip;
 * - FC7 points selected (Polyline, Curve): the pill's color and corner cells whole on screen, no
 *   two of the pill's cells overlap; a vector object's bar and its More row: every label whole.
 *
 * One test: Compose's frame clock only serves the first test of a Robolectric sandbox. The 360 dp
 * phone comes from `RuntimeEnvironment.setQualifiers` (as `Qa110ObjectColorsUiTest`).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.qa110.fontscaleg2sandbox"])
class Qa110FontScaleG2UiTest {

    @Test
    fun theLanesScreensAtLargeFontScalesAt392And360() = Qa19Ui.uiTest { h ->
        try {
            for (width in listOf(392, 360)) {
                if (width == 360) RuntimeEnvironment.setQualifiers(Qa19Ui.Q360)
                for (scale in Qa19Ui.FONT_SCALES) {
                    val at = "$width dp, font scale $scale"
                    h.section("$at: F1 the layer window") { layerWindow(h, width, scale, at) }
                    h.section("$at: F2 F3 the Object colors sheet") { colorsSheet(h, width, scale, at) }
                    h.section("$at: F4 the pill's worst-case values") { pillValues(width, scale, at) }
                    h.section("$at: F5 the bubble") { bubble(h, width, scale, at) }
                    h.section("$at: F6 the selection bar") { selectionBar(h, width, scale, at) }
                    for (id in listOf(ToolId.CURVE, ToolId.PATH)) h.section("$at: F7 ${id.label}") { curveMore(h, id, width, scale, at) }
                    h.section("$at: FC7 points selected") { pointsSelected(h, width, scale, at) }
                }
            }
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun editor(h: Qa19Harness, width: Int, scale: Float, doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true), setup: (EditorController) -> Unit = {}): ChromeScreen {
        val s = h.editor(doc, fontScale = scale) { c -> c.snapping.enabled = false; setup(c) }
        assertEquals("the phone", width.toFloat(), s.widthDp, 1f)
        assertEquals("the font scale", scale, s.activity.resources.configuration.fontScale, 0.01f)
        return s
    }

    /** [r] (dp) is shown whole on the screen; [r]. */
    private fun onScreen(s: ChromeScreen, what: String, r: Rect?): Rect {
        val b = r ?: throw AssertionError("$what is not shown; shown: ${Qa19Ui.shown().take(80)}")
        assertTrue(
            "$what whole on screen: $b in ${s.widthDp} x ${s.heightDp} dp",
            b.width > 0f && b.height > 0f && b.left >= -EPS && b.top >= -EPS && b.right <= s.widthDp + EPS && b.bottom <= s.heightDp + EPS,
        )
        return b
    }

    /** The bounds (dp) of the text [text] itself (the last placed one), or null. */
    private fun text(s: ChromeScreen, text: String): Rect? =
        s.placed().lastOrNull { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == text }?.let { s.dp(it.node.boundsInWindow) }

    private fun tapTag(s: ChromeScreen, tag: String) {
        val r = onScreen(s, tag, s.tagged(tag))
        Finger.tapAt(s, r.center.x, r.center.y)
    }

    // ------------------------------------------------------------------ F1

    private fun layerWindow(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val names = listOf("Paper", "Sketch", "Inks")
        val s = editor(h, width, scale, Smoke.document(400, 300, layers = 3, whiteBottom = true)) { c ->
            names.forEachIndexed { i, n -> c.renameLayer(c.doc.layers[i], n) }
        }
        val c = s.c
        val item = TextItem("Hey", spec = TextSpec(sizePx = 40f, color = 0xFF000000.toInt()), cx = 120f, cy = 80f)
        val text = requireNotNull(c.addLayerWithContent(TEXT_LAYER, "Add text", textData = TextCodec.encode(item)) { cv -> TextRenderer.drawItem(cv, item, TextRenderer.prepare(item), null) }) { "the text layer" }
        val vector = requireNotNull(c.addVectorLayer(VECTOR_LAYER)) { "the vector layer" }
        c.selectLayer(c.doc.layers[1])
        c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
        assertTrue("$at: the selection is saved", c.saveSelection())
        assertTrue("$at: the saved selection", Smoke.pumpUntil { Qa19Ui.settle(1); c.doc.savedSelections.isNotEmpty() })
        Qa19Ui.settle()
        LayerWalk.open(s)
        Qa19Ui.settle()
        val window = onScreen(s, "$at: the layer window", s.tagged(ChromeTags.LAYER_WINDOW))
        assertEquals("$at: the layers", names + text.name + vector.name, c.doc.layers.map { it.name })
        // Every row, scrolled into the list's view by a finger as needed: its name whole inside
        // the window, no text of the window cut.
        val saved = c.doc.savedSelections.first().name
        val rows = c.doc.layers.mapIndexed { i, l -> LayerLabels.selectRow(i + 1) to l.name } + (saved to saved)
        for ((row, n) in rows) {
            LayerWalk.reveal(s, row)
            val r = text(s, n) ?: throw AssertionError("$at: \"$n\" is not shown; shown: ${Qa19Ui.shown().take(80)}")
            assertTrue("$at: \"$n\" ($r) inside the layer window ($window)", r.left >= window.left - EPS && r.right <= window.right + EPS && r.width > 0f)
            Qa19Ui.assertNoTextOverflow("$at, the layer window at \"$n\"")
        }
    }

    // ------------------------------------------------------------------ F2, F3

    private fun colorsSheet(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = editor(h, width, scale)
        val tool = polyline(s)
        tool.deselect()
        Qa19Ui.settle()
        tapTag(s, V110Tags.PILL_COLOR)
        assertEquals("$at: the sheet's title", listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
        tapTag(s, "${V110Tags.COLORS_SHEET_TAB}.${ObjectColorTarget.FILL.name}")
        Qa19Ui.settle()
        Qa19Ui.assertNoTextOverflow("$at, the Object colors sheet (its title, Previous and Current)")
        Finger.back()
    }

    /** A closed, filled Polyline of four points, open in its tool on its own layer. */
    private fun polyline(s: ChromeScreen): CurveTool {
        s.c.selectTool(ToolId.POLYLINE)
        Qa19Ui.settle()
        val tool = s.c.currentTool as CurveTool
        tool.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 6f, useBrushSize = false, fill = true, fillColor = 0xFFFF8000.toInt(), closed = true, ownLayer = true) }
        for (p in listOf(Vec2(60f, 80f), Vec2(170f, 80f), Vec2(170f, 160f), Vec2(60f, 160f))) tool.addAnchor(p)
        Qa19Ui.settle()
        return tool
    }

    // ------------------------------------------------------------------ F4

    /** One stand-in object: the unit its pill shows and the value (both axes) at its widest text. */
    private data class Variant(val unit: LengthUnit, val inches: Float?, val px: Float, val shown: String)

    private class SeveralPillTool(c: EditorController, private val inner: FakePillTool) :
        Tool(c), PillPositionTool by inner, ScaledTool by inner, DeletingTool by inner, PointEditor {
        override val id: ToolId = ToolId.CURVE
        override val pointCount: Int get() = inner.points.size
        override val pointSelection: PointSelection get() = PointSelection.of(pointCount, 0)
        override fun selectPoints(s: PointSelection) {}
        override var selectSeveral: Boolean = false
        override fun pointAt(i: Int): Vec2 = inner.points[i]
        override fun beginGroupEdit(label: String) {}
        override fun setGroupTransform(m: Affine2) {}
        override fun endGroupEdit() {}
        override fun deleteSelectedPoints(): Boolean = false
        override val minPoints: Int = 2
    }

    /** The pill alone, as in the editor's slot (`PillColorPlacementTest`), beside "Select several", at the default Length step. */
    private fun pillValues(width: Int, scale: Float, at: String) {
        Qa19Ui.deviceFontScale(scale)
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(androidx.activity.ComponentActivity::class.java).setup()
        try {
            val activity = ctl.get()
            assertEquals("the phone", width, activity.resources.configuration.screenWidthDp)
            assertEquals("the font scale", scale, activity.resources.configuration.fontScale, 0.01f)
            val c = Smoke.controller(activity, Smoke.document(256, 256, layers = 1))
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            c.settings.coordinateStripFolded = false
            c.selectTool(ToolId.CURVE)
            c.increments.update { it.copy(lengthPx = 10f) }
            val dpi = c.doc.dpi.toDouble()
            for (v in listOf(Variant(LengthUnit.PX, null, -1329.5f, "-1,329.5"), Variant(LengthUnit.MM, 4f, 0f, "101.6 mm"), Variant(LengthUnit.IN, -12.75f, 0f, "-12.75 in"))) {
                val what = "$at, ${v.unit}"
                val value = if (v.inches != null) (v.inches * dpi).toFloat() else v.px
                val shown = formatCoordinate(value, v.unit, dpi) + if (v.unit == LengthUnit.PX) "" else " ${v.unit.short}"
                assertEquals("$what: the worst case", v.shown, shown.replace(MINUS, '-'))
                val inner = FakePillTool(c, listOf(Vec2(value, value), Vec2(0f, 0f)), pillUnit = v.unit).also { it.select(0) }
                @Suppress("UNCHECKED_CAST")
                (c.tools as MutableMap<ToolId, Tool>)[ToolId.CURVE] = SeveralPillTool(c, inner)
                activity.setContent {
                    BrushworkTheme {
                        CompositionLocalProvider(LocalIncrements provides c.increments) {
                            // The editor's slot: the window less the pill's 8 dp margins.
                            Box(Modifier.fillMaxSize().padding(horizontal = IbisDims.PillStart)) {
                                // A new stand-in tool is a new pill (CoordinatePill reads the tool once).
                                key(v.unit) { CoordinatePill(c, Modifier.offset(y = 135.dp)) }
                            }
                        }
                    }
                }
                SmokeUi.settle()
                val placed = com.brushwork.paint.ui.color.RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
                assertTrue("$what: Select several", placed.any { it.node.config.getOrNull(SemanticsProperties.TestTag) == V18Tags.PILL_SEVERAL })
                assertTrue("$what: the step cell", placed.any { it.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(INCREMENTS_LABEL) == true })
                assertEquals("$what: X and Y show the value", 2, placed.count { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.joinToString("") { it.text } == shown })
                Qa19Ui.assertNoTextOverflow("$what, the pill's row 1 beside Select several")
            }
        } finally {
            runCatching { ctl.pause().stop().destroy() }
            Qa19Ui.deviceFontScale(1f)
        }
    }

    // ------------------------------------------------------------------ F5

    private fun bubble(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = editor(h, width, scale)
        s.c.selectTool(ToolId.POLYLINE)
        Qa19Ui.settle()
        val tool = s.c.currentTool as CurveTool
        for (p in listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))) tool.addAnchor(p)
        tool.selectPoints(PointSelection.of(tool.pointCount, 1))
        Qa19Ui.settle()
        tapTag(s, V110Tags.PILL_SHARP)
        onScreen(s, "$at: the bubble", s.tagged(V18Tags.LONG_PRESS_BUBBLE))
        assertTrue("$at: the bubble says why", Qa19Ui.has(PointLabels110.POLYLINE_ALWAYS_SHARP, exact = true))
        Qa19Ui.assertNoTextOverflow("$at, the bubble")
        tool.discard()
        Qa19Ui.settle()
    }

    // ------------------------------------------------------------------ F6

    private fun selectionBar(h: Qa19Harness, width: Int, scale: Float, at: String) {
        val s = editor(h, width, scale)
        val c = s.c
        val layer = c.doc.layers[1]
        Canvas(layer.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = 0xFF2244CC.toInt() })
        c.selectLayer(layer)
        c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
        Qa19Ui.settle()
        onScreen(s, "$at: the selection bar", s.tagged(ChromeTags.SELECTION_BAR))
        val words = s.placed().count { e -> StripTestKit.under(e.node, ChromeTags.SELECTION_BAR) && e.node.config.getOrNull(SemanticsProperties.Text) != null }
        assertTrue("$at: the bar's words show ($words)", words >= 4)
        Qa19Ui.assertNoTextOverflow("$at, the raster selection bar")
    }

    // ------------------------------------------------------------------ F7

    private fun curveMore(h: Qa19Harness, id: ToolId, width: Int, scale: Float, at: String) {
        val what = "$at, ${id.label}"
        val s = editor(h, width, scale)
        s.c.selectTool(id)
        Qa19Ui.settle()
        val tool = s.c.currentTool as CurveTool
        for (p in listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 160f))) tool.addAnchor(p)
        tool.selectPoints(PointSelection.of(tool.pointCount, 1))
        tool.setWidth(1, 1.5f)
        tool.endNumericEdit()
        Qa19Ui.settle()
        // The spec: the thickness item, then "All points 100 %", no item kept hidden.
        val spec = curveSpec(tool, curveFacts(tool), CurveStripUi())
        val keys = spec.items.map { it.key }
        val thickness = keys.indexOf(CurveKeys.THICKNESS)
        val all100 = keys.indexOf(CurveKeys.ALL_100)
        assertTrue("$what: the thickness item and All points 100 %: $keys", thickness >= 0 && all100 >= 0)
        assertTrue("$what: the thickness item first: $keys", thickness < all100)
        assertEquals("$what: no hidden item", emptyList<String>(), spec.items.filter { !it.shown }.map { it.key })
        // The host's More lines at this width: All points 100 % after the slider, on its line or below.
        val content = StripInventory.content(width.toFloat())
        val lines = StripLayout.moreLines(StripLayout.split(spec, content), spec.hint, content)
        val line = lines.indexOfFirst { l -> l.any { it.key == CurveKeys.THICKNESS } }
        val all100Line = lines.indexOfFirst { l -> l.any { it.key == CurveKeys.ALL_100 } }
        assertTrue("$what: All points 100 % after the slider: ${lines.map { l -> l.map { it.key } }}", line >= 0 && all100Line >= line)
        // On the screen, the More row open: both whole, no text cut.
        assertTrue("$what: the More row opens", Qa19Ui.openMoreRow())
        val label100 = spec.items[all100].label
        val r100 = onScreen(s, "$what: \"$label100\"", Finger.element(s, label100))
        val slider = onScreen(s, "$what: the thickness item", Finger.element(s, POINT_THICKNESS_LABEL, exact = false))
        // (The slider's node reaches SLIDER_REACH_DP past its layout on each side, over its neighbours' gap.)
        assertTrue(
            "$what: \"$label100\" ($r100) after the slider ($slider)",
            r100.top >= slider.bottom - EPS || (r100.center.y in slider.top..slider.bottom && r100.left >= slider.right - SLIDER_REACH_DP - EPS),
        )
        Qa19Ui.assertNoTextOverflow("$what, the More row open")
        Qa19Ui.closeMoreRow()
        // The settings sheet: the Fill segment is the stroke-less mode; no "No stroke" chip.
        Qa19Ui.click("${id.label} settings", exact = true)
        Qa19Ui.settle()
        assertNotNull("$what: the sheet's Fill segment", s.tagged("${PaintModeTags.CURVE}.${PaintMode.FILL.name}"))
        assertFalse("$what: no \"${CurveStroke.NONE.label}\" chip", Qa19Ui.has(CurveStroke.NONE.label, exact = true))
        Qa19Ui.assertNoTextOverflow("$what, the settings sheet")
        Finger.back()
        tool.discard()
        Qa19Ui.settle()
    }

    // ------------------------------------------------------------------ FC7

    private fun pointsSelected(h: Qa19Harness, width: Int, scale: Float, at: String) {
        for (id in listOf(ToolId.POLYLINE, ToolId.CURVE)) {
            val what = "$at, ${id.label} with points selected"
            val s = editor(h, width, scale)
            val tool = if (id == ToolId.POLYLINE) {
                polyline(s)
            } else {
                s.c.selectTool(id)
                Qa19Ui.settle()
                (s.c.currentTool as CurveTool).also { t ->
                    t.update { it.copy(fill = true, fillColor = 0xFFFF8000.toInt(), closed = true) }
                    for (p in listOf(Vec2(60f, 80f), Vec2(170f, 80f), Vec2(170f, 160f), Vec2(60f, 160f))) t.addAnchor(p)
                }
            }
            tool.selectPoints(PointSelection.of(tool.pointCount, 1, 2))
            Qa19Ui.settle()
            val color = onScreen(s, "$what: the pill's color cell", s.tagged(V110Tags.PILL_COLOR))
            onScreen(s, "$what: the pill's corner cell", s.tagged(V110Tags.PILL_SHARP))
            val trash = onScreen(s, "$what: the pill's trash", s.tagged(V17Tags.PILL_TRASH))
            assertNoOverlap(s, what, trash.top..color.bottom)
            Qa19Ui.assertNoTextOverflow(what)
            tool.discard()
            Qa19Ui.settle()
        }
        // A vector object's bar, its More row open too: every label whole.
        val s = editor(h, width, scale)
        val c = s.c
        val v = requireNotNull(c.addVectorLayer()) { "the vector layer" }
        c.vectors.update(v, VectorContent.EMPTY.plus(listOf(ArrayLookKit.box(20f, 30f, 60f, 70f), ArrayLookKit.box(200f, 30f, 240f, 70f))).first, "Add")
        assertTrue("$at: the boxes drawn", Smoke.pumpUntil { Qa19Ui.settle(1); !c.vectors.isRendering })
        c.selectLayer(v)
        c.vectors.setSelection(v, setOf(v.vector!!.objects.first().id))
        Qa19Ui.settle()
        Qa19Ui.assertNoTextOverflow("$at, the object bar")
        assertTrue("$at: the object bar's More row opens", Qa19Ui.openObjectRow())
        Qa19Ui.assertNoTextOverflow("$at, the object bar's More row")
    }

    /**
     * No two of the pill's controls (those whose centre lies in [band], dp: its two rows) overlap:
     * two in the same row (centres less than a cell's 28 dp apart) share no x; rows lie a cell
     * apart (each cell's 40 dp touch overhangs its 28 dp, by design).
     */
    private fun assertNoOverlap(s: ChromeScreen, what: String, band: ClosedFloatingPointRange<Float>) {
        val cells = s.placed().filter { e ->
            e.node.config.getOrNull(SemanticsActions.OnClick) != null || e.node.config.getOrNull(SemanticsProperties.ProgressBarRangeInfo) != null
        }.map { e -> e.node to s.dp(e.node.boundsInWindow) }.filter { (_, r) ->
            r.center.y in band && r.height <= IbisDims.PillCellTouch.value + EPS && r.width <= IbisDims.PillCellMaxWidth.value + EPS && r.width > 0f
        }.distinctBy { it.first.id }
        assertTrue("$what: the pill's cells (${cells.size})", cells.size >= 5)
        fun name(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.ContentDescription)?.firstOrNull()
            ?: n.config.getOrNull(SemanticsProperties.TestTag) ?: "node ${n.id}"
        fun related(a: SemanticsNode, b: SemanticsNode): Boolean {
            var p = a.parent
            while (p != null) { if (p.id == b.id) return true; p = p.parent }
            p = b.parent
            while (p != null) { if (p.id == a.id) return true; p = p.parent }
            return false
        }
        val cell = IbisDims.PillCellHeight.value
        for (i in cells.indices) for (j in i + 1 until cells.size) {
            val (a, ra) = cells[i]
            val (b, rb) = cells[j]
            if (related(a, b)) continue
            if (kotlin.math.abs(ra.center.y - rb.center.y) < cell - EPS) {
                assertTrue("$what: \"${name(a)}\" ($ra) and \"${name(b)}\" ($rb) overlap", ra.right <= rb.left + EPS || rb.right <= ra.left + EPS)
            }
        }
    }

    private companion object {
        const val EPS = 0.5f
        /** How far the thickness slider's semantics node reaches past its layout on each side (as `ThicknessDragKeepsItsPlaceUiTest`). */
        const val SLIDER_REACH_DP = 10f
        const val MINUS = '−'
        const val TEXT_LAYER = "Words"
        const val VECTOR_LAYER = "Lines"
    }
}
