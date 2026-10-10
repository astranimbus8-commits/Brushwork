package com.brushwork.paint.qa17

import android.graphics.Bitmap
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA (item 20, design §3.20) by finger on the user's phone (392 dp), with the Shape
 * tool's DEFAULT style (an 8 px outline, no fill; the shapes tester used filled shapes only): two
 * circles and a star outlined red, blue and green, three shape layers. Unite, Minus front, Divide
 * and Trim are each ONE step from their button; the operands' regions are their outlines'
 * insides and each result piece keeps its source's outline (§3.20b), so the results are outlines
 * only, held pixel by pixel on the edges that must show or vanish:
 * - the red circle's left edge (70, 140): green (Unite: the front object's style), else red;
 * - the red circle's edge inside the blue one (200, 175): gone (Unite, Minus front, Trim) or blue
 *   (Divide: both pieces there are blue's);
 * - the blue circle's edge inside the red one (179, 175): gone (Unite), red (Minus front: the
 *   bite's edge), blue (Divide and Trim: blue's piece drawn over red's).
 * One undo gives the three shape layers back pixel for pixel.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapespathfinderstrokesandbox"])
class Qa17ShapesPathfinderStrokeUiTest {

    private fun press(s: ChromeScreen, label: String, minDp: Float = 32f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    @Test
    fun pathfinderOnOutlinedShapesAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 180_000)
        val h = ChromeHarness()
        h.section("two outlined circles and an outlined star: Unite, Minus front, Divide, Trim") {
            outlines(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true)))
        }
        dog.interrupt()
        h.finish()
    }

    private fun outlines(s: ChromeScreen) {
        val c = s.c
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        assertEquals("the default style: an outline", ShapeStyle.STROKE, tool.settings.style)
        click("Shape type", exact = true)
        click(ShapeType.ELLIPSE.label, exact = true)
        fun draw(color: Int, from: Vec2, by: Vec2): Layer {
            c.color = color
            settle()
            QaCurves.drag(s, from, by)
            assertNotNull("a pending shape", tool.box)
            click("Apply shape edit")
            assertNull("placed", tool.box)
            val l = c.doc.layers.last()
            assertTrue("a shape layer of its own", l.isShapeLayer)
            val o = ShapeCodec.decode(l.shapeData)!!
            assertEquals(ShapeStyle.STROKE, o.style)
            assertEquals("outlined in the colour picked", color, o.strokeColor)
            return l
        }
        val red = draw(RED, Vec2(70f, 70f), Vec2(140f, 140f))
        val blue = draw(BLUE, Vec2(170f, 70f), Vec2(140f, 140f))
        click("Shape type", exact = true)
        click(ShapeType.STAR.label, exact = true)
        val green = draw(GREEN, Vec2(130f, 40f), Vec2(120f, 120f))
        val operands = listOf(red, blue, green)
        val pixels = operands.map { it.bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        // The probes on the operands themselves: each edge where it is.
        assertEquals(RED, red.bitmap.getPixel(70, 140))
        assertEquals(RED, red.bitmap.getPixel(200, 175))
        assertEquals(BLUE, blue.bitmap.getPixel(179, 175))

        QaCurves.tool(s, "Pathfinder")
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        val layersBefore = c.doc.layers.toList()
        // (op, the left edge, red's edge inside blue, blue's edge inside red); null = nothing there.
        val cases = listOf(
            Probe(PathfinderOp.UNITE, GREEN, null, null),
            Probe(PathfinderOp.MINUS_FRONT, RED, null, RED),
            Probe(PathfinderOp.DIVIDE, RED, BLUE, BLUE),
            Probe(PathfinderOp.TRIM, RED, null, BLUE),
        )
        for ((op, left, redInBlue, blueInRed) in cases) {
            press(s, PathfinderLabels.SELECT_ALL)
            assertEquals("$op: three objects picked", 3, pf.count)
            val before = c.undoManager.undoCount
            press(s, op.description, 40f)
            assertTrue("$op: done", Smoke.pumpUntil(20_000) { settle(1); !pf.busy && !c.vectors.isRendering })
            settle()
            assertEquals("$op: ONE step", before + 1, c.undoManager.undoCount)
            assertEquals(op.historyLabel, c.undoManager.undoLabel)
            assertEquals("$op: one result layer", listOf("Layer 1", PathfinderLabels.resultLayer(1)), c.doc.layers.map { it.name })
            val result = c.doc.layers.last()
            val objects = result.vector!!.objects.map { it as VPath }
            assertTrue("$op: something", objects.isNotEmpty())
            for (o in objects) {
                assertNull("$op: outlines only, no fill", o.fill)
                val st = requireNotNull(o.stroke) { "$op: each piece keeps its source's outline" }
                assertTrue("$op: a source's colour", st.color == RED || st.color == BLUE || st.color == GREEN)
            }
            fun at(x: Int, y: Int): Int? = result.bitmap.getPixel(x, y).let { if (it ushr 24 == 0) null else it }
            fun hex(v: Int?) = v?.let { Integer.toHexString(it) }
            assertEquals("$op: the left edge", hex(left), hex(at(70, 140)))
            assertEquals("$op: red's edge inside blue", hex(redInBlue), hex(at(200, 175)))
            assertEquals("$op: blue's edge inside red", hex(blueInRed), hex(at(179, 175)))
            Qa17ShapesShots.doc(s, "shapes-pfstroke-${op.name.lowercase()}")

            click("Undo", exact = true)
            settle()
            assertEquals("$op: one undo", before, c.undoManager.undoCount)
            assertEquals("$op: the three shape layers are back", layersBefore, c.doc.layers.toList())
            for ((i, l) in operands.withIndex()) {
                assertTrue("$op: ${l.name} is a shape layer", l.isShapeLayer)
                assertTrue("$op: ${l.name} shows as before", l.bitmap.sameAs(pixels[i]))
            }
        }
        Smoke.assertQuiet(c, "pathfinder outlines")
    }

    private data class Probe(val op: PathfinderOp, val left: Int?, val redInBlue: Int?, val blueInRed: Int?)

    private companion object {
        val RED = 0xFFE53935.toInt()
        val BLUE = 0xFF1E88E5.toInt()
        val GREEN = 0xFF43A047.toInt()
    }
}
