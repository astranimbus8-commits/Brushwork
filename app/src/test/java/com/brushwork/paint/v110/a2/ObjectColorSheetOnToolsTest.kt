package com.brushwork.paint.v110.a2

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.getValue
import androidx.compose.runtime.key
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.dp
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ObjectColorEditor
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextColorsDelegate
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.CurveKind
import com.brushwork.paint.tools.vector.CurvePaint
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.PaintMode
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.ObjectColorsSheet
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.composite
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2, R7, J21; design 4.4 A2 steps 1 and 3-5, review addition): the Object colors sheet on
 * the real editors of the Shape, Polyline and Text tools (the other sheet tests drive a fake
 * editor), on a 360 dp phone at font scale 2.0. What is typed in the sheet shows on the canvas at
 * once; a tab shown again sends nothing; "No fill" and the paint-mode glyphs act on the open
 * object; Cancel puts back what the sheet opened with; OK keeps the colors for the object's ✓,
 * which is ONE undo step (Undo and Redo); the user's drawing color is never changed, and a strip
 * edit made after the sheet stays the tool's setting.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h800dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.ontoolssandbox"])
class ObjectColorSheetOnToolsTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun byTag(tag: String): RobolectricUi.Element? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun tapTag(tag: String) {
        val e = byTag(tag) ?: throw AssertionError("no $tag")
        requireNotNull(e.node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    private fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"
    private fun mode(m: PaintMode) = "${V110Tags.COLORS_SHEET_MODE}.${m.name}"
    private fun selected(t: ObjectColorTarget) = byTag(tab(t))?.node?.config?.getOrNull(SemanticsProperties.Selected)

    private fun type(hex: String) {
        RobolectricUi.textFields().first().type(hex)
        SmokeUi.settle()
    }

    private fun assertOpen(where: String) = assertEquals(where, listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
    private fun assertClosed(where: String) =
        assertTrue("$where: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())

    @Test
    fun theSheetDrivesTheRealEditorsLiveCancelRestoresOkKeepsInOneStep() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)

        val c = rig.controller()
        c.snapping.enabled = false
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        fun inside() = c.composite().getPixel(70, 60)
        assertEquals("as drawn", hex(RED), hex(inside()))

        // As the pill's COLOR cell: the sheet of the open object's editor, one per opening.
        var editor by mutableStateOf<ObjectColorEditor?>(null)
        var opening by mutableIntStateOf(0)
        fun open(e: ObjectColorEditor?) {
            editor = requireNotNull(e) { "an open object has colors" }
            opening++
            SmokeUi.settle()
            assertOpen("opened")
        }
        activity.setContent {
            val base = LocalDensity.current
            CompositionLocalProvider(LocalDensity provides Density(base.density, 2f)) {
                BrushworkTheme {
                    val h = rememberSheetHostState()
                    CompositionLocalProvider(LocalSheetHost provides h) {
                        Box(Modifier.fillMaxSize()) {
                            SheetHost(h, bottomInset = 0.dp)
                            SheetPill(h, Modifier.align(Alignment.BottomCenter))
                        }
                        val e = editor
                        if (e != null) key(opening) { ObjectColorsSheet(e, onClose = { editor = null }) }
                    }
                }
            }
        }
        SmokeUi.settle()

        // ---- A new shape: live previews, the tab rule, "No fill", a glyph, then Cancel.
        open(shape.objectColors)
        assertEquals(true, selected(ObjectColorTarget.FILL))
        type("0000FF")
        assertEquals("the fill on the canvas, live", hex(BLUE), hex(inside()))
        assertEquals("the drawing color stays", hex(RED), hex(c.color))
        tapTag(tab(ObjectColorTarget.STROKE))
        type("00FF00")
        var o = shape.pendingObject()!!
        assertEquals("the stroke, live", hex(GREEN), hex(o.strokeColor))
        assertEquals("the fill is pinned", hex(BLUE), hex(o.fillColor))
        assertEquals(hex(BLUE), hex(inside()))
        tapTag(tab(ObjectColorTarget.FILL))
        assertEquals("a tab shown again sends nothing", hex(BLUE), hex(shape.pendingObject()!!.fillColor))
        SmokeUi.click(ObjectColorLabels110.NO_FILL, exact = true)
        assertEquals("No fill: Stroke only", ShapeStyle.STROKE, shape.settings.style)
        assertEquals("no fill on the canvas", 0, inside() ushr 24)
        tapTag(mode(PaintMode.BOTH))
        assertEquals(ShapeStyle.STROKE_FILL, shape.settings.style)
        assertEquals("the fill is back", hex(BLUE), hex(inside()))
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "Cancel")
        SmokeUi.settle()
        assertClosed("Cancel")
        o = shape.pendingObject()!!
        assertEquals("Cancel: the stroke", hex(RED), hex(o.strokeColor))
        assertTrue("Cancel: the fill follows again", o.fillFollowsColor)
        assertEquals(ShapeStyle.STROKE_FILL, shape.settings.style)
        assertEquals("Cancel: the canvas", hex(RED), hex(inside()))
        assertEquals(hex(RED), hex(c.color))

        // ---- OK keeps the color for the shape's ✓: one step, Undo and Redo; the user's options come back.
        open(shape.objectColors)
        type("0000FF")
        val steps = c.undoManager.undoCount
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "OK")
        SmokeUi.settle()
        assertClosed("OK")
        assertNull("OK closed the sheet", editor)
        assertEquals("OK is no step of its own", steps, c.undoManager.undoCount)
        assertEquals(hex(BLUE), hex(inside()))
        shape.commit()
        frame()
        SmokeUi.settle()
        assertEquals("the shape's one step", steps + 1, c.undoManager.undoCount)
        val placed = requireNotNull(ShapeCodec.decode(c.activeLayer.shapeData)) { "a shape layer" }
        assertEquals(hex(BLUE), hex(placed.fillColor))
        assertEquals(hex(RED), hex(placed.strokeColor))
        assertEquals("the drawing color", hex(RED), hex(c.color))
        assertNull("the user's fill is back (it follows)", shape.settings.fillColor)
        c.undo()
        SmokeUi.settle()
        assertEquals("Undo takes the shape back", 0, inside() ushr 24)
        c.redo()
        SmokeUi.settle()
        assertEquals("Redo brings it back filled", hex(BLUE), hex(inside()))

        // ---- A new polyline: the fill, then the strip's width; ✓ keeps the fill, the width stays the tool's.
        c.selectTool(ToolId.POLYLINE)
        val line = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        line.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = false) }
        for (p in listOf(Vec2(130f, 120f), Vec2(190f, 120f), Vec2(190f, 190f), Vec2(130f, 190f))) assertTrue(line.addAnchor(p))
        line.deselect()
        SmokeUi.settle()
        open(line.objectColors)
        assertEquals(true, selected(ObjectColorTarget.FILL))
        type("00FF00")
        assertEquals("the path's fill, live", hex(GREEN), hex(c.composite().getPixel(160, 155)))
        tapTag(mode(PaintMode.FILL))
        assertEquals(CurvePaint.FILL, line.paintMode)
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "OK")
        SmokeUi.settle()
        assertClosed("OK on the path")
        line.update { it.copy(plainWidth = 12f) }
        line.commit()
        frame()
        SmokeUi.settle()
        assertEquals("painted with its fill", hex(GREEN), hex(c.composite().getPixel(160, 155)))
        assertEquals("the user's paint mode is back", CurvePaint.BOTH, line.paintMode)
        assertNull("the user's fill is back", line.settings.fillColor)
        assertEquals("the strip's width stays the tool's", 12f, CurveTool(c, CurveKind.POLYLINE).settings.plainWidth, 0f)
        assertEquals(hex(RED), hex(c.color))

        // ---- A text: Text and Outline tabs, no paint mode; "No outline"; Cancel restores.
        c.viewTransform.set(Matrix())
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        c.tap(60f, 150f)
        assertTrue("the editor opened for a new text", text.editorOpen && text.editingNew)
        text.setText("HH")
        text.updateSpec { it.copy(sizePx = 40f, color = BLACK, strokeWidthPx = 0f) }
        text.confirmEditor()
        open(text.objectColors)
        assertEquals(true, selected(ObjectColorTarget.TEXT))
        assertNull("a text has no paint mode", byTag(mode(PaintMode.BOTH)))
        type("0000FF")
        assertEquals("the text color, live", hex(BLUE), hex(text.item!!.spec.color))
        tapTag(tab(ObjectColorTarget.OUTLINE))
        val none = requireNotNull(SmokeUi.find(ObjectColorLabels110.NO_OUTLINE, exact = true)) { "no \"No outline\"" }
        assertEquals("no outline yet", true, none.node.config.getOrNull(SemanticsProperties.Selected))
        type("00FF00")
        assertEquals(hex(GREEN), hex(text.item!!.spec.strokeColor))
        assertEquals("the default outline width", TextColorsDelegate.defaultOutlineWidth(40f), text.item!!.spec.strokeWidthPx, 1e-4f)
        SmokeUi.click(ObjectColorLabels110.NO_OUTLINE, exact = true)
        assertEquals("No outline: width 0", 0f, text.item!!.spec.strokeWidthPx, 0f)
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "Cancel")
        SmokeUi.settle()
        assertClosed("Cancel on the text")
        assertEquals("Cancel: the text color", hex(BLACK), hex(text.item!!.spec.color))
        assertEquals("Cancel: no outline", 0f, text.item!!.spec.strokeWidthPx, 0f)
        assertEquals("the drawing color never changed", hex(RED), hex(c.color))
    }
}
