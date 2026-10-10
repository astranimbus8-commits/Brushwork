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
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog

/**
 * v1.7 final QA (item 20, design §3.20) by finger on the user's phone (392 dp): two circles and
 * a star drawn with the Shape tool (filled red, blue and green: three shape layers, the star on
 * top), then each of the ten Pathfinder operations from its button after "Select all objects",
 * each ONE step "Pathfinder: …" that one undo takes back (the top row's Undo; a two-finger tap
 * for the last). Each result is held against what Illustrator gives, pixel by pixel: sampled
 * away from every edge, its layer shows the colour Illustrator's rule gives there (Unite,
 * Intersect, Exclude and Minus back the front object's; Minus front the back one's; Divide,
 * Trim and Merge each region in the colour of the frontmost object over it; Crop the lower
 * objects' colours inside the star, nothing elsewhere; Outline no fill at all), or nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.shapespathfindersandbox"])
class Qa17ShapesPathfinderUiTest {

    private fun press(s: ChromeScreen, label: String, minDp: Float = 32f) {
        Qa16Ui(s).reach(label, minDp)
        settle()
        Finger.tap(s, label)
    }

    /** Inside (true), outside (false) or on the edge (null) of the opaque pixels of [b] at ([x], [y]), 3 px around. */
    private fun inside(b: Bitmap, x: Int, y: Int): Boolean? {
        var all = true
        var none = true
        for (dy in -3..3 step 3) for (dx in -3..3 step 3) {
            val a = b.getPixel(x + dx, y + dy) ushr 24
            if (a != 255) all = false
            if (a != 0) none = false
        }
        return if (all) true else if (none) false else null
    }

    /** The colour Illustrator's [op] shows where the red circle [a], blue circle [b] and green star [s] overlap so; null = nothing. */
    private fun expected(op: PathfinderOp, a: Boolean, b: Boolean, s: Boolean): Int? = when (op) {
        PathfinderOp.UNITE -> if (a || b || s) GREEN else null
        PathfinderOp.MINUS_FRONT -> if (a && !b && !s) RED else null
        PathfinderOp.MINUS_BACK -> if (s && !a && !b) GREEN else null
        PathfinderOp.INTERSECT -> if (a && b && s) GREEN else null
        PathfinderOp.EXCLUDE -> if (listOf(a, b, s).count { it } % 2 == 1) GREEN else null
        PathfinderOp.DIVIDE, PathfinderOp.TRIM, PathfinderOp.MERGE -> when { s -> GREEN; b -> BLUE; a -> RED; else -> null }
        PathfinderOp.CROP -> if (!s) null else when { b -> BLUE; a -> RED; else -> null }
        PathfinderOp.OUTLINE -> null
    }

    @Test
    fun theTenPathfinderOperationsAt392dp() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("two circles and a star: the ten operations, one undo each") { ten(h.editor(Smoke.document(400, 300, layers = 1, whiteBottom = true))) }
        dog.interrupt()
        h.finish()
    }

    private fun ten(s: ChromeScreen) {
        val c = s.c
        QaCurves.tool(s, "Shape")
        val tool = c.currentTool as ShapeTool
        QaCurves.snapOff(c)
        click("Shape type", exact = true)
        click(ShapeType.ELLIPSE.label, exact = true)
        click("Shape style", exact = true)
        click(ShapeStyle.FILL.label, exact = true)
        assertEquals(ShapeStyle.FILL, tool.settings.style)
        fun draw(color: Int, from: Vec2, by: Vec2): Layer {
            c.color = color
            settle()
            QaCurves.drag(s, from, by)
            assertNotNull("a pending shape", tool.box)
            click("Apply shape edit")
            assertNull("placed", tool.box)
            val l = c.doc.layers.last()
            assertTrue("a shape layer of its own", l.isShapeLayer)
            assertEquals("filled with the colour picked", color, ShapeCodec.decode(l.shapeData)!!.fillColor)
            return l
        }
        val red = draw(RED, Vec2(70f, 70f), Vec2(140f, 140f))
        val blue = draw(BLUE, Vec2(170f, 70f), Vec2(140f, 140f))
        click("Shape type", exact = true)
        click(ShapeType.STAR.label, exact = true)
        val green = draw(GREEN, Vec2(130f, 40f), Vec2(120f, 120f))
        val operands = listOf(red, blue, green)
        assertEquals(listOf("Layer 1") + operands.map { it.name }, c.doc.layers.map { it.name })
        val pixels = operands.map { it.bitmap.copy(Bitmap.Config.ARGB_8888, false) }
        // Where the three overlap and where each is alone, away from every edge.
        val samples = ArrayList<Triple<Int, Int, BooleanArray>>()
        for (y in 6 until c.doc.height - 6 step 4) for (x in 6 until c.doc.width - 6 step 4) {
            val m = pixels.map { inside(it, x, y) }
            if (m.any { it == null }) continue
            samples += Triple(x, y, BooleanArray(3) { m[it]!! })
        }
        assertTrue("the three overlap somewhere", samples.any { (_, _, m) -> m.all { it } })

        QaCurves.tool(s, "Pathfinder")
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        val layersBefore = c.doc.layers.toList()
        val ops = PathfinderOp.entries
        assertEquals(10, ops.size)
        for (op in ops) {
            press(s, PathfinderLabels.SELECT_ALL)
            assertEquals("$op: three objects picked", 3, pf.count)
            assertTrue(has(PathfinderLabels.picked(3), exact = true))
            if (op == ops.first()) Qa17ShapesShots.screen(s, "shapes-pf-picked")
            val before = c.undoManager.undoCount
            press(s, op.description, 40f)
            assertTrue("$op: done", Smoke.pumpUntil(20_000) { settle(1); !pf.busy && !c.vectors.isRendering })
            settle()
            assertEquals("$op: ONE step", before + 1, c.undoManager.undoCount)
            assertEquals(op.historyLabel, c.undoManager.undoLabel)
            assertEquals("$op: the shape layers became the result", listOf("Layer 1", PathfinderLabels.resultLayer(1)), c.doc.layers.map { it.name })
            val result = c.doc.layers.last()
            assertSame("$op: the result is active", result, c.activeLayer)
            assertTrue(result.isVectorLayer)
            val objects = result.vector!!.objects.map { it as VPath }
            checkObjects(op, objects)

            // Pixel by pixel against Illustrator's rule.
            val bad = ArrayList<String>()
            for ((x, y, m) in samples) {
                val want = expected(op, m[0], m[1], m[2])
                val got = result.bitmap.getPixel(x, y)
                val ok = if (want == null) got ushr 24 == 0 else got == want
                if (!ok) bad += "($x, $y) a=${m[0]} b=${m[1]} s=${m[2]}: want ${want?.let { Integer.toHexString(it) }} got ${Integer.toHexString(got)}"
            }
            assertTrue("$op: ${bad.size} of ${samples.size} samples differ: ${bad.take(6)}", bad.isEmpty())
            if (op == PathfinderOp.OUTLINE) {
                val px = IntArray(c.doc.width * c.doc.height).also { result.bitmap.getPixels(it, 0, c.doc.width, 0, 0, c.doc.width, c.doc.height) }
                assertTrue("$op: the edges are drawn", px.count { it ushr 24 != 0 } > 500)
            }
            Qa17ShapesShots.doc(s, "shapes-pf-${op.name.lowercase()}")

            // One undo: the three shapes as they were.
            if (op == ops.last()) Qa16Ui(s).twoFingerUndo() else click("Undo", exact = true)
            settle()
            assertEquals("$op: one undo", before, c.undoManager.undoCount)
            assertEquals("$op: the three shape layers are back", layersBefore, c.doc.layers.toList())
            for ((i, l) in operands.withIndex()) {
                assertTrue("$op: ${l.name} is a shape layer", l.isShapeLayer)
                assertTrue("$op: ${l.name} shows as before", l.bitmap.sameAs(pixels[i]))
            }
            assertEquals("$op: nothing picked", 0, pf.count)
        }
        Smoke.assertQuiet(c, "pathfinder ten")
    }

    private fun checkObjects(op: PathfinderOp, objects: List<VPath>) {
        fun fills() = objects.map { (it.fill as? VPaint.Solid)?.color }
        when (op) {
            PathfinderOp.UNITE, PathfinderOp.INTERSECT, PathfinderOp.EXCLUDE, PathfinderOp.MINUS_BACK ->
                assertEquals("$op: one object in the front object's colour", listOf(GREEN), fills())
            PathfinderOp.MINUS_FRONT -> assertEquals("$op: one object in the back object's colour", listOf(RED), fills())
            PathfinderOp.TRIM, PathfinderOp.MERGE -> assertEquals("$op: each object's visible part", listOf(RED, BLUE, GREEN), fills())
            PathfinderOp.CROP -> assertEquals("$op: the lower objects inside the star", listOf(RED, BLUE), fills())
            PathfinderOp.DIVIDE -> {
                assertTrue("$op: every region its own piece: ${objects.size}", objects.size >= 7)
                assertTrue("$op: ${fills()}", fills().all { it == RED || it == BLUE || it == GREEN })
            }
            PathfinderOp.OUTLINE -> {
                assertTrue("$op: edges: ${objects.size}", objects.size >= 7)
                for (o in objects) {
                    assertNull("$op: no fill", o.fill)
                    val st = requireNotNull(o.stroke) { "$op: a line" }
                    assertEquals(VStrokeKind.PLAIN, st.kind)
                    assertEquals(1f, st.width, 0f)
                    assertTrue("$op: the piece's colour", st.color == RED || st.color == BLUE || st.color == GREEN)
                    assertTrue("$op: open", o.subpaths.none { it.closed })
                }
            }
        }
        if (op != PathfinderOp.OUTLINE) for (o in objects) assertNull("$op: filled shapes have no outline", o.stroke)
    }

    private companion object {
        val RED = 0xFFE53935.toInt()
        val BLUE = 0xFF1E88E5.toInt()
        val GREEN = 0xFF43A047.toInt()
    }
}
