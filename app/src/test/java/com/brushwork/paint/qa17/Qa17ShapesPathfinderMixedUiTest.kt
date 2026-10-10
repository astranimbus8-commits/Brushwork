package com.brushwork.paint.qa17

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA (item 20, design §3.20) by finger on the user's phone (392 dp), Pathfinder with
 * mixed operands:
 * - a vector layer holding a brush stroke and a Path-tool path, under a filled rectangle's shape
 *   layer: a tap on the stroke alone picks nothing and says "Brush strokes are skipped"; "Select
 *   all objects" picks the path and the shape; Unite lands as "Pathfinder 1" above the shape
 *   (its colour), the shape layer goes, the vector layer keeps its stroke, ONE step, one undo;
 * - two circles inside a folder: the result lands IN the folder.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapespathfindermixedsandbox"])
class Qa17ShapesPathfinderMixedUiTest {

    private fun press(s: ChromeScreen, label: String, minDp: Float = 32f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    private fun shapeTool(s: ChromeScreen): ShapeTool {
        QaCurves.tool(s, "Shape")
        val tool = s.c.currentTool as ShapeTool
        QaCurves.snapOff(s.c)
        click("Shape style", exact = true)
        click(ShapeStyle.FILL.label, exact = true)
        assertEquals(ShapeStyle.FILL, tool.settings.style)
        return tool
    }

    private fun draw(s: ChromeScreen, tool: ShapeTool, color: Int, from: Vec2, by: Vec2): Layer {
        s.c.color = color
        settle()
        QaCurves.drag(s, from, by)
        assertNotNull("a pending shape", tool.box)
        click("Apply shape edit")
        assertNull("placed", tool.box)
        val l = s.c.activeLayer
        assertTrue("a shape layer", l.isShapeLayer)
        return l
    }

    private fun pathfinder(s: ChromeScreen): PathfinderTool {
        QaCurves.tool(s, "Pathfinder")
        return (s.c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
    }

    private fun unite(s: ChromeScreen, pf: PathfinderTool) {
        press(s, PathfinderOp.UNITE.description, 40f)
        assertTrue("united", Smoke.pumpUntil(20_000) { settle(1); !pf.busy && !s.c.vectors.isRendering })
        settle()
    }

    @Test
    fun pathfinderWithMixedOperandsAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("a path, a shape and a brush stroke") { mixed(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))) }
        h.section("two circles in a folder") { folder(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))) }
        dog.interrupt()
        h.finish()
    }

    private fun mixed(s: ChromeScreen) {
        val c = s.c
        val ui = Qa16Ui(s)
        val vec = requireNotNull(c.addVectorLayer())
        settle()
        // A brush stroke along the bottom: an object of the vector layer.
        QaCurves.tool(s, "Brush")
        ui.stroke(40f to 260f, 200f to 262f, 360f to 260f)
        assertTrue("a brush stroke object", vec.vector!!.objects.any { it is VStroke })
        // A Path by four taps, applied: an object of the vector layer.
        QaCurves.tool(s, "Path")
        for ((x, y) in listOf(80f to 60f, 230f to 60f, 230f to 210f, 80f to 210f)) ui.tap(x, y)
        click("Apply path edit")
        assertTrue(Smoke.pumpUntil(20_000) { settle(1); !c.vectors.isRendering })
        val path = vec.vector!!.objects.filterIsInstance<VPath>().single()
        // A filled rectangle over both, in its own shape layer above (drawn on a pixel layer: on
        // the active vector layer it would be an object of that layer).
        val raster = requireNotNull(c.addLayer())
        settle()
        val tool = shapeTool(s)
        assertEquals(ShapeType.RECTANGLE, tool.settings.type)
        val shape = draw(s, tool, BLUE, Vec2(160f, 110f), Vec2(150f, 120f))
        assertEquals(listOf("Layer 1", vec.name, raster.name, shape.name), c.doc.layers.map { it.name })

        val pf = pathfinder(s)
        // The stroke alone, tapped: nothing picked, and why.
        QaCurves.tap(s, 60f, 260f)
        assertEquals("the stroke isn't picked", 0, pf.count)
        // (The editor shows the controller's message as its snackbar at once and clears it.)
        assertTrue("the message shows: ${SmokeUi.shown().take(60)}", has(PathfinderLabels.STROKES_SKIPPED, exact = true))
        Qa17Shots.screen(s, "shapes-pf-stroke-skipped")
        // "Select all objects": the path and the shape (the stroke is skipped, and said so: read
        // before the editor takes the message, as the same text is still showing).
        click(PathfinderLabels.SELECT_ALL, exact = true, settleAfter = false)
        assertEquals("select all says so too", PathfinderLabels.STROKES_SKIPPED, c.message)
        settle()
        assertEquals("the path and the shape", 2, pf.count)

        val layers = c.doc.layers.toList()
        val before = c.undoManager.undoCount
        unite(s, pf)
        assertEquals("ONE step", before + 1, c.undoManager.undoCount)
        assertEquals(PathfinderOp.UNITE.historyLabel, c.undoManager.undoLabel)
        assertEquals("above the shape's place; the shape layer is gone", listOf("Layer 1", vec.name, raster.name, PathfinderLabels.resultLayer(1)), c.doc.layers.map { it.name })
        val result = c.doc.layers.last()
        val united = result.vector!!.objects.single() as VPath
        assertEquals("the front object's colour", BLUE, (united.fill as? VPaint.Solid)?.color)
        val left = vec.vector!!.objects
        assertTrue("the vector layer keeps its stroke: $left", left.size == 1 && left.single() is VStroke)
        // The union covers the path's corner and the shape's.
        assertEquals(BLUE, result.bitmap.getPixel(100, 80))
        assertEquals(BLUE, result.bitmap.getPixel(295, 220))
        Qa17Shots.doc(s, "shapes-pf-mixed")

        click("Undo", exact = true)
        assertEquals("one undo", layers, c.doc.layers.toList())
        assertTrue("the shape layer is back", shape.isShapeLayer)
        assertTrue("the path is back", vec.vector!!.objects.any { it.id == path.id })
        Smoke.assertQuiet(c, "pathfinder mixed")
    }

    private fun folder(s: ChromeScreen) {
        val c = s.c
        val folder = requireNotNull(c.addFolder())
        settle()
        val tool = shapeTool(s)
        click("Shape type", exact = true)
        click(ShapeType.ELLIPSE.label, exact = true)
        val a = draw(s, tool, RED, Vec2(60f, 60f), Vec2(160f, 160f))
        val b = draw(s, tool, BLUE, Vec2(170f, 60f), Vec2(160f, 160f))
        assertEquals("both in the folder", listOf(folder.id, folder.id), listOf(a.parentId, b.parentId))

        val pf = pathfinder(s)
        press(s, PathfinderLabels.SELECT_ALL)
        assertEquals(2, pf.count)
        val layers = c.doc.layers.toList()
        val before = c.undoManager.undoCount
        unite(s, pf)
        assertEquals(before + 1, c.undoManager.undoCount)
        val result = c.doc.layers.single { it.name == PathfinderLabels.resultLayer(1) }
        assertEquals("the result lands in the folder", folder.id, result.parentId)
        assertFalse(a in c.doc.layers || b in c.doc.layers)
        assertTrue("under the folder's row", c.doc.indexOf(result) < c.doc.indexOf(folder))
        assertEquals("the top object's colour", BLUE, (result.vector!!.objects.single() as VPath).fill.let { (it as VPaint.Solid).color })
        Qa17Shots.doc(s, "shapes-pf-folder")
        click("Undo", exact = true)
        assertEquals(layers, c.doc.layers.toList())
        Smoke.assertQuiet(c, "pathfinder folder")
    }

    private companion object {
        val RED = 0xFFE53935.toInt()
        val BLUE = 0xFF1E88E5.toInt()
    }
}
