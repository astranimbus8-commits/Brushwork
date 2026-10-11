package com.brushwork.paint.qa18

import android.app.Application
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import kotlinx.coroutines.runBlocking
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
import java.io.File
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.8 final QA (item 7, design §3.8, D21): the cog in the Shape tool on the phone (392 dp), by
 * finger:
 * - "Shape type" ▸ "Cog"; "Number of teeth" typed 64 in "Shape settings"; a drag draws it; its
 *   "Points" are its teeth; ✓ is one step; Fill, Stroke, Stroke + fill each paint as they say;
 * - "Tooth depth", "Tooth width", "Tooth taper" and round corners: the open cog follows each live;
 * - with a "Hole size", "Points" says "Set the hole size to 0 to edit points" and the More row's
 *   "Turn into path" makes ONE compound path (outline and hole), one step that Undo takes back;
 * - with the Rotation ruler (picked in the Symmetry tool's type menu) a cog is ONE object with its
 *   copies, saved and loaded as it was.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.curvescogsandbox"])
class Qa18CurvesCogUiTest {
    private val app: Application get() = ApplicationProvider.getApplicationContext()

    private fun pickCog(q: Qa18CurvesUi) {
        q.tap("Shape type")
        q.tap(ShapeType.COG.label)
        assertEquals("Cog picked", ShapeType.COG, (q.c.currentTool as ShapeTool).settings.type)
    }

    private fun pickStyle(q: Qa18CurvesUi, s: ShapeStyle) {
        q.tap("Shape style")
        q.tap(s.label)
        assertEquals(s, (q.c.currentTool as ShapeTool).settings.style)
    }

    /** A cog dragged out from corner [a] to corner [b] by a finger. */
    private fun dragOut(q: Qa18CurvesUi, a: Vec2, b: Vec2) = q.strokeDoc(a, a + (b - a) * 0.25f, a + (b - a) * 0.5f, a + (b - a) * 0.75f, b)

    private fun shapes(q: Qa18CurvesUi): List<VShape> = q.c.activeLayer.vector!!.objects.filterIsInstance<VShape>()

    /** Points of the circle of radius [r] about [c] (document px) showing ink on the active layer. */
    private fun inkedRound(q: Qa18CurvesUi, c: Vec2, r: Float, n: Int = 32): Int =
        (0 until n).count { i -> val a = 2.0 * Math.PI * i / n; q.inkAt(q.c.activeLayer, Vec2(c.x + r * cos(a).toFloat(), c.y + r * sin(a).toFloat()), 0) }

    private fun sixtyFour(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        pickCog(q)
        q.tap("Shape settings")
        SmokeUi.typeAndDone(CogLabels18.TEETH, "64")
        assertEquals("64 teeth typed", 64, tool.settings.cogTeeth)
        Finger.back()
        assertFalse("the sheet closed", has(CogLabels18.TEETH, exact = true))
        pickStyle(q, ShapeStyle.STROKE_FILL)
        val steps = q.steps()
        dragOut(q, Vec2(110f, 60f), Vec2(290f, 240f))
        assertTrue("an open cog", tool.ensurePending())

        // Points: the teeth (4 points each at least); Undo leaves Points again.
        q.tap(com.brushwork.paint.ui.vector.ShapeStripItems.POINTS_LABEL)
        assertTrue("Points mode", tool.pointsMode)
        assertTrue("64 teeth: ${tool.pointCount} points", tool.pointCount >= 64 * 4)
        StripTestKit.closeMoreRow()
        q.undo()
        assertFalse("Undo leaves Points", tool.pointsMode)
        assertNull("the cog again", tool.points)

        q.applyEdit("Apply shape edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        val cog = shapes(q).single().shape
        assertEquals(ShapeType.COG, cog.type)
        assertEquals(64, cog.cogTeeth)
        val centre = Vec2(200f, 150f)
        assertTrue("drawn: the ring of teeth", inkedRound(q, centre, 90f * (1f - cog.cogDepth / 2f)) > 8)
        assertTrue("filled (Stroke + fill)", q.inkAt(q.c.activeLayer, centre))
        q.shot(q.c.activeLayer, "curves-ui-cog-64.png")
        q.undo()
        assertTrue("Undo takes the cog off", shapes(q).isEmpty())
        q.redo()
        assertEquals(1, shapes(q).size)

        // Stroke alone leaves the middle empty; Fill alone fills it.
        for (style in listOf(ShapeStyle.STROKE, ShapeStyle.FILL)) {
            q.tap("Shape settings")
            SmokeUi.typeAndDone(CogLabels18.TEETH, "12")
            Finger.back()
            pickStyle(q, style)
            val before = q.steps()
            q.settleRenders("before $style")
            q.strokeDoc(Vec2(20f, 20f), Vec2(30f, 30f), Vec2(40f, 40f), Vec2(60f, 60f)) // (off the first cog: a new one)
            q.applyEdit("Apply shape edit")
            assertEquals("$style: one step", before + 1, q.steps())
            val s = shapes(q).last().shape
            assertEquals(style, s.style)
            val mid = Vec2(s.cx, s.cy)
            assertEquals("$style: the middle ${if (style.fill) "filled" else "empty"}", style.fill, q.inkAt(q.c.activeLayer, mid, 1))
        }
        Smoke.assertQuiet(q.c, "64 teeth")
    }

    /**
     * "Tooth depth", "Tooth width", "Tooth taper" and the corners' roundness ("Round", "Corner
     * radius") dragged by finger in "Shape settings": the open cog follows each live, ✓ keeps them.
     */
    private fun teethAndRoundness(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        pickCog(q)
        pickStyle(q, ShapeStyle.FILL)
        val steps = q.steps()
        dragOut(q, Vec2(130f, 10f), Vec2(270f, 150f))
        assertTrue("an open cog", tool.ensurePending())
        q.tap("Shape settings")
        fun live(what: String, move: () -> Unit) {
            val before = tool.settings
            val screen = q.capture()
            move()
            assertTrue("$what: a setting changed", tool.settings != before)
            val shown = q.changedAboveSheet(screen, q.capture(), 130f, 10f, 270f, 150f)
            assertTrue("$what: the cog follows live ($shown px)", shown > 30)
        }
        live(CogLabels18.TOOTH_DEPTH) { q.dragSlider(CogLabels18.TOOTH_DEPTH, 0.2f) }
        live(CogLabels18.TOOTH_WIDTH) { q.dragSlider(CogLabels18.TOOTH_WIDTH, 0.2f) }
        live(CogLabels18.TOOTH_TAPER) { q.dragSlider(CogLabels18.TOOTH_TAPER, 0.3f) }
        live("Round corners") {
            q.tap(CornerStyle.ROUND.label)
            q.dragSlider("Corner radius", 0.05f)
        }
        val s = tool.settings
        assertEquals(CornerStyle.ROUND, s.corner)
        assertTrue("a corner radius: ${s.cornerRadius}", s.cornerRadius > 0f)
        Finger.back()
        q.applyEdit("Apply shape edit")
        assertEquals("✓ is ONE step", steps + 1, q.steps())
        val cog = shapes(q).single().shape
        assertEquals("the depth kept", s.cogDepth, cog.cogDepth)
        assertEquals("the width kept", s.cogToothWidth, cog.cogToothWidth)
        assertEquals("the taper kept", s.cogTaper, cog.cogTaper)
        assertEquals("the corners kept", CornerStyle.ROUND to s.cornerRadius, cog.corner to cog.cornerRadius)
        q.shot(q.c.activeLayer, "curves-ui-cog-teeth-round.png")
        Smoke.assertQuiet(q.c, "teeth and roundness")
    }

    private fun holed(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        q.tool("Shape")
        val tool = q.c.currentTool as ShapeTool
        pickCog(q)
        q.tap("Shape settings")
        SmokeUi.typeAndDone(CogLabels18.TEETH, "12")
        q.dragSlider(CogLabels18.HOLE_SIZE, 0.3f)
        assertTrue("a hole: ${tool.settings.cogHole}", tool.settings.cogHole > 0.1f)
        Finger.back()
        pickStyle(q, ShapeStyle.STROKE_FILL)
        val steps = q.steps()
        dragOut(q, Vec2(110f, 60f), Vec2(290f, 240f))
        q.tap(com.brushwork.paint.ui.vector.ShapeStripItems.POINTS_LABEL)
        assertTrue("the refusal shows", has(CogLabels18.HOLE_REFUSAL, exact = true))
        assertFalse("Points refused", tool.pointsMode)
        StripTestKit.closeMoreRow()
        q.tap(PointLabels.TO_PATH)
        q.settleRenders("Turn into path")
        assertEquals("the cog, then Turn into path", steps + 2, q.steps())
        val p = q.c.activeLayer.vector!!.objects.single() as? VPath ?: throw AssertionError("not a path: ${q.c.activeLayer.vector!!.objects}")
        assertEquals("the outline and the hole: one compound path", 2, p.subpaths.size)
        assertTrue(p.subpaths.all { it.closed })
        assertTrue("its hint", has(CogLabels18.COMPOUND_HINT, exact = true))
        assertEquals(ToolId.PATH, q.c.activeToolId)
        assertTrue("the hole shows the page", !q.inkAt(q.c.activeLayer, Vec2(200f, 150f), 1))
        assertTrue("the cog round it", q.inkAt(q.c.activeLayer, Vec2(200f, 150f + 90f * (1f - 0.2f) - 6f), 1))
        q.shot(q.c.activeLayer, "curves-ui-cog-hole-path.png")
        q.undo()
        q.settleRenders("undo")
        val back = q.c.activeLayer.vector!!.objects.single()
        assertTrue("Undo: the cog again ($back)", back is VShape && back.shape.type == ShapeType.COG && back.shape.cogHole > 0f)
        Smoke.assertQuiet(q.c, "holed")
    }

    private fun rotation(q: Qa18CurvesUi) {
        q.editor(Qa18CurvesUi.vectorDoc())
        // The Symmetry tool (it starts a Mirror ruler): its type button shows the current type and opens its menu of rulers.
        q.tool("Symmetry")
        Finger.tap(q.s, q.c.symmetry.type.label)
        q.tap(SymmetryType.ROTATION.label)
        assertEquals("the Rotation ruler picked: ${SmokeUi.shown().take(80)}", SymmetryType.ROTATION, q.c.symmetry.type)
        val n = q.c.symmetry.divisions
        q.tool("Shape")
        pickCog(q)
        pickStyle(q, ShapeStyle.STROKE_FILL)
        val steps = q.steps()
        dragOut(q, Vec2(40f, 20f), Vec2(120f, 100f))
        q.applyEdit("Apply shape edit")
        assertEquals("ONE step", steps + 1, q.steps())
        val cog = shapes(q).single()
        assertEquals("ONE object with its $n copies", n, cog.shape.copies.count)
        // Each copy is drawn: the page centre's turn of the cog's centre shows ink.
        val pc = Vec2(200f, 150f)
        val c0 = Vec2(cog.shape.cx, cog.shape.cy)
        for (k in 1 until n) {
            val a = 2.0 * Math.PI * k / n
            val d = c0 - pc
            val ck = Vec2(pc.x + (d.x * cos(a) - d.y * sin(a)).toFloat(), pc.y + (d.x * sin(a) + d.y * cos(a)).toFloat())
            if (ck.x in 5f..395f && ck.y in 5f..295f) assertTrue("copy $k drawn at $ck", q.inkAt(q.c.activeLayer, ck, 2))
        }
        q.shot(q.c.activeLayer, "curves-ui-cog-rotation.png")
        File(app.filesDir, "projects").deleteRecursively()
        val loaded = runBlocking {
            val repo = ProjectRepository(app)
            repo.save(q.c.doc, null)
            repo.load(q.c.doc.id)
        }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val back = loaded.layers.single { it.isVectorLayer }.vector!!.objects.single()
        assertEquals("saved and loaded as it was (cog and copies)", cog, back)
        assertEquals(SymmetryType.ROTATION, loaded.symmetry.type)
        assertNotNull(back)
        Smoke.assertQuiet(q.c, "rotation")
    }

    @Test
    fun aCogByFingerSixtyFourTeethAHoleAndTheRotationRuler() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 300_000)
        val h = ChromeHarness()
        val q = Qa18CurvesUi(h, 392f)
        h.section("64 teeth typed, Points, ✓, fill and stroke") { sixtyFour(q) }
        h.section("tooth depth, width, taper and round corners, live") { teethAndRoundness(q) }
        h.section("a holed cog: Points refused, Turn into path") { holed(q) }
        h.section("the Rotation ruler, saved and loaded") { rotation(q) }
        dog.interrupt()
        h.finish()
    }
}
