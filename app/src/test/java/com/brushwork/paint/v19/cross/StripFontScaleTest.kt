package com.brushwork.paint.v19.cross

import android.view.KeyEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa18.V18CrossTestKit
import com.brushwork.paint.qa19.Qa19Harness
import com.brushwork.paint.qa19.Qa19Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.EraseLabels19
import com.brushwork.paint.ui.common.PointLabels19
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.vector.ShapeStripItems
import com.brushwork.paint.ui.vector.ShapeStripKit
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config

/**
 * v1.9 Gate 2 (design 3.4, I27): with every area merged, no strip text overflows its box at font
 * scale 1.3 and 2.0, on the 392 dp and the 360 dp phone ([Qa19Ui.assertNoTextOverflow], no texts
 * left out). One section per width and scale walks every strip state of [V18CrossTestKit.states]
 * (every tool on the raster and the vector layer, then v1.8's fullest More rows) and the v1.9
 * worst cases ([v19States]: the Eraser's "Partial" hint, the four tools erasing with their mode
 * cell, Shape's Points mode while erasing, a text of a layer of several) in a [StripRig], each
 * with its More row open; where a state shows them it also opens the erase-mode cell's menu and
 * the bevel popup. The mode cell stays in row 1 of Curve, Polyline, Path and Shape at both widths
 * (G5). Then the real editor at each width and scale: the vector object bar and its extra row,
 * the X / Y pill of an open shape, the Shape settings sheet (its length fields) and the bevel
 * popup over the strip.
 *
 * A section collects every overflow it finds and fails once at its end, so one run names them all.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = Qa19Ui.Q392, instrumentedPackages = ["com.brushwork.paint.v19.cross.stripfontscalesandbox"])
class StripFontScaleTest {
    private val widths = listOf(392 to Qa19Ui.Q392, 360 to Qa19Ui.Q360)
    private val curves = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)

    @Test
    fun everyStripKeepsItsTextsInsideItsCellsAtLargeFonts() = Qa19Ui.uiTest { h ->
        for ((dp, q) in widths) for (scale in Qa19Ui.FONT_SCALES) h.section("the strips at $dp dp, font scale $scale") {
            atWidth(q) { timed("strips $dp dp at $scale") { strips(dp, scale) } }
        }
        for ((dp, q) in widths) for (scale in Qa19Ui.FONT_SCALES) h.section("the editor's bars at $dp dp, font scale $scale") {
            atWidth(q) { timed("editor $dp dp at $scale") { editor(h, dp, scale) } }
        }
    }

    private fun atWidth(q: String, block: () -> Unit) {
        RuntimeEnvironment.setQualifiers(q)
        try {
            block()
        } finally {
            RuntimeEnvironment.setQualifiers(Qa19Ui.Q392)
        }
    }

    private fun timed(what: String, block: () -> Unit) {
        val t0 = System.nanoTime()
        try {
            block()
        } finally {
            System.err.println("[strip font scale] $what: ${(System.nanoTime() - t0) / 1_000_000} ms")
        }
    }

    /** The overflow check, its failure kept in [problems] (the section fails once, at its end). */
    private fun check(problems: MutableList<String>, where: String) {
        try {
            Qa19Ui.assertNoTextOverflow(where)
        } catch (e: AssertionError) {
            problems += e.message.orEmpty()
        }
    }

    private fun failOn(problems: List<String>, what: String) {
        if (problems.isNotEmpty()) throw AssertionError("$what: ${problems.size} checks failed:\n" + problems.joinToString("\n"))
    }

    // ------------------------------------------------------------------ the strips

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    /** The first node at or under [n] (breadth first) that takes a click. */
    private fun clickableIn(n: SemanticsNode): SemanticsNode? {
        var level = listOf(n)
        while (level.isNotEmpty()) {
            level.firstOrNull { it.config.getOrNull(SemanticsActions.OnClick) != null }?.let { return it }
            level = level.flatMap { it.children }
        }
        return null
    }

    private fun click(n: SemanticsNode, rig: StripRig) {
        clickableIn(n)?.config?.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
        rig.settle()
    }

    /** Back on the top window (a popup or a menu over the rig closes). */
    private fun back(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    private fun curve(rig: StripRig, id: ToolId) = rig.c.tools.getValue(id) as CurveTool

    private fun tap(rig: StripRig, x: Float, y: Float) {
        StripTestKit.closeMoreRow()
        rig.c.pointerDown(ToolPoint(x, y))
        rig.c.pointerUp(ToolPoint(x, y))
        rig.settle()
    }

    private fun drag(rig: StripRig, vararg pts: Pair<Float, Float>) {
        StripTestKit.closeMoreRow()
        rig.c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) rig.c.pointerMove(ToolPoint(pts[i].first, pts[i].second))
        rig.c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
        rig.settle()
    }

    /** The v1.9 states whose strips are the fullest: what [V18CrossTestKit.states] does not reach. */
    private fun v19States(): List<V18CrossTestKit.State> = buildList {
        add(V18CrossTestKit.State("ERASER@vector, Partial") { rig ->
            rig.select(ToolId.ERASER, vector = true)
            VectorEraserModes.setMode(rig.c, VectorEraseMode.PARTIAL)
            rig.settle()
            check(rig.spec.hint == EraseLabels19.PARTIAL_NOTE) { "the hint: ${rig.spec.hint}" }
        })
        for (id in curves) {
            add(V18CrossTestKit.State("${id.name}@vector, erasing") { rig ->
                rig.select(id, vector = true)
                curve(rig, id).setErase(true)
                rig.settle()
                check(curve(rig, id).eraseOn) { "not erasing" }
            })
            add(V18CrossTestKit.State("${id.name}@vector, erasing, open with mirror copies, a point selected") { rig ->
                rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
                rig.select(id, vector = true)
                val t = curve(rig, id)
                t.setErase(true)
                tap(rig, 60f, 70f)
                tap(rig, 110f, 100f)
                tap(rig, 150f, 80f)
                t.select(1)
                rig.settle()
                check(t.eraseOn) { "not erasing" }
            })
        }
        add(V18CrossTestKit.State("SHAPE@vector, erasing with a box and its copy") { rig ->
            val t = ShapeStripKit.fresh(rig, vector = true)
            rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
            t.setErase(true)
            t.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
            drag(rig, 30f to 30f, 80f to 60f, 110f to 90f)
            check(t.eraseSession != null) { "not erasing live" }
        })
        add(V18CrossTestKit.State("SHAPE@vector, erasing, the fullest Points mode") { rig ->
            val t = ShapeStripKit.fresh(rig, vector = true)
            rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
            t.setErase(true)
            t.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
            drag(rig, 30f to 30f, 80f to 60f, 110f to 90f)
            t.setPointEditing(true)
            t.selectPoints(PointSelection.of(t.pointCount, 0))
            t.setSelectedSmooth(true)
            t.scaleHandles(1.5f)
            t.selectPoints(PointSelection.of(t.pointCount, 0, 1))
            rig.settle()
            check(t.pointsMode && t.eraseSession != null) { "not erasing in Points mode" }
        })
        add(V18CrossTestKit.State("TEXT, a text of a layer of several") { rig ->
            rig.select(ToolId.TEXT)
            val t = rig.c.currentTool as TextTool
            t.newLayerEachTime = false
            t.startTextAt(200f, 150f)
            t.setText("Strip")
            t.confirmEditor()
            check(t.commitItem()) { "not stored" }
            val layer = rig.c.activeLayer
            val a = TextCodec.decodeAll(layer.textData).single()
            val items = listOf(a, a.copy(id = 0, text = "Two", cx = a.cx + 40f, cy = a.cy + 30f))
            check(rig.c.updateTextLayer(layer, TextCodec.encodeAll(items), "Setup", null) { cv -> for (i in items) TextRenderer.drawItem(cv, i, TextRenderer.prepare(i), null) })
            check(t.editLayer(layer, index = 0)) { "not reopened" }
            rig.settle()
            check(t.arrangeShown) { "no arrange items" }
        })
    }

    /**
     * Every strip state at the device's [scale] on the phone [dp] wide, in a fresh [StripRig]
     * (`panel = false`, the host as the editor calls it): row 1 and the More row open, the mode
     * cell's menu and the bevel popup where the state shows them.
     */
    private fun strips(dp: Int, scale: Float) {
        Qa19Ui.deviceFontScale(scale)
        SmokeUi.markBaseline()
        val rig = StripRig(panel = false).show()
        val problems = mutableListOf<String>()
        val cells = sortedSetOf<String>()
        val bevels = sortedSetOf<String>()
        var walked = 0
        try {
            val config = rig.activity.resources.configuration
            assertEquals("the phone is $dp dp wide", dp, config.screenWidthDp)
            assertEquals("the font scale", scale, config.fontScale, 0.01f)
            val steps = rig.c.undoManager.undoCount
            for (state in V18CrossTestKit.states() + v19States()) {
                V18CrossTestKit.reset(rig)
                VectorEraserModes.setMode(rig.c, VectorEraseMode.OBJECT)
                while (rig.c.undoManager.undoCount > steps) rig.c.undo()
                rig.settle()
                try {
                    state.setup(rig)
                } catch (e: Throwable) {
                    problems += "${state.name}: not reached: ${e.message}"
                    continue
                }
                val where = "${state.name}, $dp dp at font scale $scale"
                walked++
                if (StripTestKit.toggle() != null && !StripTestKit.moreRowShown()) StripTestKit.openMoreRow()
                check(problems, "$where, ${if (StripTestKit.moreRowShown()) "the More row open" else "row 1"}")

                // The erase-mode cell (G5): in row 1, and its menu.
                StripTestKit.tagged(V19Tags.ERASE_MODE_CELL)?.let { cell ->
                    cells += rig.c.activeToolId.name
                    if (!StripTestKit.under(cell, V18Tags.STRIP_ROW1)) problems += "$where: the erase-mode cell is not in row 1"
                    click(cell, rig)
                    if (StripTestKit.tagged(V19Tags.ERASE_MODE_PREFIX + VectorEraseMode.PARTIAL.name) == null) {
                        problems += "$where: the erase-mode cell's menu did not open"
                    } else {
                        check(problems, "$where, the erase-mode menu open")
                    }
                    back(rig)
                }
                // The bevel popup.
                if (Qa19Ui.has(PointLabels19.BEVEL, exact = true)) {
                    val bevel = RobolectricUi.elements().map { it.node }.lastOrNull { n ->
                        n.layoutInfo.isPlaced && n.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(PointLabels19.BEVEL) == true
                    } ?: RobolectricUi.byText(PointLabels19.BEVEL).node
                    click(bevel, rig)
                    if (StripTestKit.tagged(V19Tags.BEVEL_POPUP) != null) {
                        bevels += rig.c.activeToolId.name
                        check(problems, "$where, the bevel popup open")
                    }
                    back(rig)
                }
                StripTestKit.closeMoreRow()
            }
            V18CrossTestKit.reset(rig)
        } finally {
            rig.close()
            dispose(rig)
        }
        System.err.println("[strip font scale] $dp dp at $scale: $walked states, mode cells $cells, bevel popups $bevels")
        if (walked < 70) problems += "only $walked states walked"
        for (t in curves + ToolId.SHAPE) if (t.name !in cells) problems += "${t.name}: no erase-mode cell shown at $dp dp"
        for (t in listOf(ToolId.POLYLINE, ToolId.SHAPE)) if (t.name !in bevels) problems += "${t.name}: no bevel popup opened at $dp dp"
        failOn(problems, "the strips at $dp dp, font scale $scale")
    }

    /** Takes the rig's window away, so a later section's windows are the only ones searched. */
    private fun dispose(rig: StripRig) {
        runCatching { rig.activity.windowManager.removeViewImmediate(rig.activity.window.decorView) }
        Qa19Ui.settle(2)
    }

    // ------------------------------------------------------------------ the editor

    /** A filled, stroked box on the vector layer (index 1), selected, under the Shape tool. */
    private fun selectedBox(c: EditorController) {
        val layer = c.doc.layers[1]
        layer.vector = VectorContent.EMPTY
        c.selectLayer(layer)
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(120f, 100f, true), VAnchor(280f, 100f, true), VAnchor(280f, 200f, true), VAnchor(120f, 200f, true)), closed = true)),
            fill = VPaint.Solid(0xFF3070C0.toInt()), stroke = VStrokeStyle(color = 0xFF802000.toInt(), width = 3f),
        )
        val ids = c.vectors.addObjects(layer, listOf(box), "Add")
        c.selectTool(ToolId.SHAPE)
        c.vectors.setSelection(layer, ids.toSet())
    }

    /**
     * The editor at the device's [scale] on the phone [dp] wide: the object bar and its extra row,
     * then an open shape's X / Y pill, the Shape settings sheet and the bevel popup.
     */
    private fun editor(h: Qa19Harness, dp: Int, scale: Float) {
        val problems = mutableListOf<String>()
        val s = h.editor(fontScale = scale) { selectedBox(it) }
        val where = "$dp dp at font scale $scale"
        assertEquals("the phone is $dp dp wide", dp.toFloat(), s.widthDp, 1f)
        val c = s.c

        // The object bar, then its extra row.
        assertNotNull("$where: the object bar", s.tagged(ChromeTags.SELECTION_BAR))
        check(problems, "the object bar, $where")
        assertTrue("$where: the object bar's row opens", Qa19Ui.openObjectRow())
        check(problems, "the object bar's row open, $where")
        Qa19Ui.closeObjectRow()
        c.vectors.setSelection(null, emptySet())
        Qa19Ui.settle()

        // An open shape: the X / Y pill, then the Shape settings sheet.
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE, useBrushSize = false, strokeWidth = 4f) }
        assertTrue("$where: a shape opens", tool.ensurePending())
        Qa19Ui.settle()
        assertNotNull("$where: the open shape shows the X / Y pill", s.tagged(ChromeTags.PILL_SLOT)?.takeIf { it.height > 0f })
        check(problems, "an open shape with its X / Y pill, $where")
        Qa19Ui.click(ShapeStripItems.SETTINGS_LABEL, exact = true)
        Smoke.pumpUntil(5_000) { Qa19Ui.has("Corner size", exact = true) }
        for (field in listOf("Stroke width", "Corner size")) {
            assertTrue("$where: the Shape settings sheet's $field; shown: ${Qa19Ui.shown().take(60)}", Qa19Ui.has(field, exact = true))
        }
        check(problems, "the Shape settings sheet, $where")
        Qa19Ui.click(SHEET_CLOSE, exact = true)
        Qa19Ui.settle()
        assertTrue("$where: the sheet closed", !Qa19Ui.has("Stroke width", exact = true))

        // Points mode: the bevel popup over the strip, the pill on screen.
        tool.setPointEditing(true)
        tool.selectPoints(PointSelection.all(tool.pointCount))
        Qa19Ui.settle()
        Qa19Ui.click(PointLabels19.BEVEL, exact = true)
        assertNotNull("$where: the bevel popup", Qa19Ui.tagged(V19Tags.BEVEL_POPUP))
        check(problems, "the bevel popup, $where")
        tool.discard()
        Qa19Ui.settle()
        failOn(problems, "the editor at $where")
    }

    private companion object {
        /** The sheet's close button (`BwSheet`, "Close"). */
        const val SHEET_CLOSE = "Close"
    }
}
