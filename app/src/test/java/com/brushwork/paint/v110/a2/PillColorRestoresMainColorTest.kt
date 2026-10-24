package com.brushwork.paint.v110.a2

import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ColoredObjectTool
import com.brushwork.paint.tools.ObjectColorTarget
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.PaletteStore
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.LocalSheetHost
import com.brushwork.paint.ui.common.ObjectColorLabels110
import com.brushwork.paint.ui.common.SheetHost
import com.brushwork.paint.ui.common.SheetPill
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.common.rememberSheetHostState
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.PillColorCell
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLACK
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.BLUE
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.GREEN
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.RED
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.drag
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.frame
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.hex
import com.brushwork.paint.v110.a2.A2ToolRig.Companion.tap
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.10 (R2 "object-only edit", V19; design 4.4 A2 step 6): the colors picked through the pill's
 * COLOR cell are the object's, and the user's main (drawing) color comes back when the object's
 * session ends, for a pending (new) and a reopened object of the Shape and the Polyline tool.
 * Driven as the user does: a tap on the cell (shown, as in the editor's pill, while the tool's
 * `objectColors` is not null) opens the Object colors sheet, a color is typed, OK, then the tool's
 * ✓ or ✕. A reopened object takes its own color as the drawing color while it is open (v1.9) and
 * gives back the color the user had; ✓ while the sheet is still open keeps the preview and closes
 * the sheet; ✕ takes the object's colors back. The cell is selected while its sheet is open.
 *
 * One test in its own sandbox: Compose's frame clock only serves the first test of a sandbox.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a2.restoresmaincolorsandbox"])
class PillColorRestoresMainColorTest {

    private val rig = A2ToolRig()

    @After
    fun release() = rig.release()

    private fun byTag(tag: String): RobolectricUi.Element? =
        RobolectricUi.elements().lastOrNull { it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }

    private fun tapTag(tag: String) {
        val e = byTag(tag) ?: throw AssertionError("no $tag; shown: ${SmokeUi.shown().take(40)}")
        requireNotNull(e.node.config.getOrNull(SemanticsActions.OnClick)?.action) { "$tag is not clickable" }.invoke()
        SmokeUi.settle()
    }

    private fun settle() {
        frame()
        shadowOf(Looper.getMainLooper()).idle()
        SmokeUi.settle()
    }

    private fun tab(t: ObjectColorTarget) = "${V110Tags.COLORS_SHEET_TAB}.${t.name}"

    /** Taps the pill's cell (the sheet opens, the cell is selected), picks [t]'s tab and types [hex]. */
    private fun pick(where: String, t: ObjectColorTarget, hex: String) {
        tapTag(V110Tags.PILL_COLOR)
        assertEquals("$where: the sheet opened", listOf(ObjectColorLabels110.CELL), SmokeUi.sheetTitles())
        assertEquals("$where: the cell is selected", true, byTag(V110Tags.PILL_COLOR)?.node?.config?.getOrNull(SemanticsProperties.Selected))
        tapTag(tab(t))
        RobolectricUi.textFields().first().type(hex)
        SmokeUi.settle()
    }

    private fun ok(where: String) {
        SmokeUi.clickIn(ObjectColorLabels110.CELL, "OK")
        SmokeUi.settle()
        assertClosed("$where: OK")
    }

    private fun assertClosed(where: String) {
        assertTrue("$where: ${SmokeUi.sheetTitles()} ${SmokeUi.pillTitles()}", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
        byTag(V110Tags.PILL_COLOR)?.let { assertEquals("$where: the cell is not selected", false, it.node.config.getOrNull(SemanticsProperties.Selected)) }
    }

    private fun assertColor(what: String, expected: Int, actual: Int) = assertEquals(what, hex(expected), hex(actual))

    @Test
    fun theUsersMainColorComesBackForPendingAndReopenedShapesAndPaths() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        PaletteStore.get(activity).setPickerMode(PickerMode.WHEEL)
        val c = rig.controller()
        c.snapping.enabled = false
        activity.setContent {
            BrushworkTheme {
                val h = rememberSheetHostState()
                CompositionLocalProvider(LocalSheetHost provides h) {
                    Box(Modifier.fillMaxSize()) {
                        SheetHost(h, bottomInset = 0.dp)
                        SheetPill(h, Modifier.align(Alignment.BottomCenter))
                        Box(Modifier.align(Alignment.TopStart).padding(top = 120.dp, start = 8.dp)) { Cell(c) }
                    }
                }
            }
        }
        SmokeUi.settle()

        // ---- A new shape: the stroke picked in the sheet, ✓; the user's red comes back.
        c.selectTool(ToolId.SHAPE)
        val shape = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        shape.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, fillColor = null, useBrushSize = false, strokeWidth = 6f) }
        assertNull("no shape open: no cell", byTag(V110Tags.PILL_COLOR))
        c.drag(30f to 30f, 80f to 60f, 110f to 90f)
        settle()
        pick("a new shape", ObjectColorTarget.STROKE, "0000FF")
        ok("a new shape")
        shape.commit()
        settle()
        assertNull("✓: no shape open, no cell", byTag(V110Tags.PILL_COLOR))
        val layer = c.activeLayer
        var placed = requireNotNull(ShapeCodec.decode(layer.shapeData)) { "a shape layer" }
        assertColor("a new shape: its stroke", BLUE, placed.strokeColor)
        assertColor("a new shape: the fill keeps its color", RED, placed.fillColor)
        assertColor("a new shape: the user's main color", RED, c.color)

        // ---- The shape reopened: its color while open, the user's green after ✓.
        c.color = GREEN
        c.tap(30f, 60f)
        settle()
        assertSame("reopened", layer, shape.editingLayer)
        assertColor("reopened: the shape's color is the drawing color", BLUE, c.color)
        pick("a reopened shape", ObjectColorTarget.STROKE, "000000")
        ok("a reopened shape")
        shape.commit()
        settle()
        placed = requireNotNull(ShapeCodec.decode(layer.shapeData))
        assertColor("a reopened shape: its stroke", BLACK, placed.strokeColor)
        assertColor("a reopened shape: the user's main color is back", GREEN, c.color)

        // ---- Reopened again, a fill picked, then ✕: the shape's colors and the user's green.
        c.tap(30f, 60f)
        settle()
        assertColor("reopened again", BLACK, c.color)
        pick("✕", ObjectColorTarget.FILL, "00FF00")
        ok("✕")
        assertColor("the fill, pending", GREEN, requireNotNull(shape.pendingObject()).fillColor)
        shape.discard()
        settle()
        placed = requireNotNull(ShapeCodec.decode(layer.shapeData))
        assertColor("✕: the fill as it was", RED, placed.fillColor)
        assertColor("✕: the stroke as it was", BLACK, placed.strokeColor)
        assertColor("✕: the user's main color", GREEN, c.color)

        // ---- A new path: the line color picked in the sheet, ✓; the user's green comes back.
        // (On the pixel layer: a path gets a vector layer of its own only above a pixel layer.)
        c.selectLayer(0)
        c.selectTool(ToolId.POLYLINE)
        val line = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        line.update { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = true, fillColor = null, closed = true, ownLayer = true) }
        for (p in listOf(Vec2(130f, 120f), Vec2(190f, 120f), Vec2(190f, 190f), Vec2(130f, 190f))) assertTrue(line.addAnchor(p))
        line.deselect()
        settle()
        pick("a new path", ObjectColorTarget.STROKE, "0000FF")
        ok("a new path")
        line.commit()
        settle()
        assertNull("✓: no path open, no cell", byTag(V110Tags.PILL_COLOR))
        val vlayer = c.doc.activeLayer
        assertTrue("a vector layer of its own", vlayer.isVectorLayer)
        var path = vlayer.vector!!.objects.single() as VPath
        assertColor("a new path: its line", BLUE, path.stroke!!.color)
        assertEquals("a new path: the fill keeps the drawing color", VPaint.Solid(GREEN), path.fill)
        assertColor("a new path: the user's main color", GREEN, c.color)

        // ---- The path reopened: ✓ while the sheet is still open keeps the preview and closes it.
        c.color = RED
        assertTrue(line.reopen(path))
        settle()
        assertColor("reopened: the path's line color is the drawing color", BLUE, c.color)
        pick("a reopened path", ObjectColorTarget.FILL, "000000")
        line.commit()
        settle()
        assertClosed("✓ with the sheet open")
        path = vlayer.vector!!.objects.single() as VPath
        assertEquals("a reopened path: the previewed fill is kept", VPaint.Solid(BLACK), path.fill)
        assertColor("a reopened path: its line", BLUE, path.stroke!!.color)
        assertColor("a reopened path: the user's main color is back", RED, c.color)

        // ---- Reopened again, a line color picked, then ✕: the path's colors and the user's red.
        assertTrue(line.reopen(path))
        settle()
        pick("✕ on the path", ObjectColorTarget.STROKE, "00FF00")
        ok("✕ on the path")
        line.discard()
        settle()
        path = vlayer.vector!!.objects.single() as VPath
        assertColor("✕: the line as it was", BLUE, path.stroke!!.color)
        assertEquals("✕: the fill as it was", VPaint.Solid(BLACK), path.fill)
        assertColor("✕: the user's main color", RED, c.color)
    }

    /** The pill's COLOR cell as the editor's pill shows it: while the tool has an open object's colors. */
    @Composable
    private fun Cell(c: EditorController) {
        val e = (c.tools[c.activeToolId] as? ColoredObjectTool)?.objectColors ?: return
        PillColorCell(e)
    }
}
