package com.brushwork.paint.qa

import android.net.Uri
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.exchange.ExchangeUiState
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.draw.VectorEraserModes
import com.brushwork.paint.vector.select.ObjectActions
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import java.io.File

/**
 * QA (§4.11 + §4.9): a drawing from another app (an SVG as Illustrator / Inkscape write it: a
 * group transform, a curve, a rectangle, a circle, a polyline) imported into the artwork is
 * worked on with the tools the user already has — Transform places it, the Curve tool reopens the
 * curve's points, the bucket recolors the rectangle, the partial eraser cuts the polyline, Lasso
 * + Delete removes the circle — every step undoable back to the import, saved and reloaded.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h857dp-xxhdpi")
class VectorImportEditQaTest {
    private lateinit var r: VectorQaRig
    private val c get() = r.c

    @After
    fun tearDown() { if (this::r.isInitialized) r.close() }

    private val svg = """<?xml version="1.0" encoding="UTF-8"?>
<svg xmlns="http://www.w3.org/2000/svg" width="480" height="320" viewBox="0 0 480 320">
  <g transform="translate(10,10)">
    <path d="M 30 50 C 110 10 190 90 270 50" fill="none" stroke="#1a2a6c" stroke-width="6"/>
    <rect x="290" y="30" width="120" height="80" fill="#40b060" stroke="#000000" stroke-width="3"/>
    <circle cx="110" cy="210" r="50" fill="#f08020"/>
    <polyline points="210,170 290,250 370,170 450,250" fill="none" stroke="#c03030" stroke-width="5"/>
  </g>
</svg>"""

    private fun near(layer: Layer, x: Float, y: Float): VObject? = layer.vector!!.objects.firstOrNull { VectorOps.bounds(it).contains(x, y) }

    @Test
    fun anImportedDrawingIsEditedWithTheToolsAndUndoesBackToTheImport() {
        r = VectorQaRig(480, 320)
        val f = File(r.activity.cacheDir, "qa-foreign.svg").apply { writeText(svg) }
        val state = ExchangeUiState(c)
        state.context = r.activity
        val layers = c.doc.layers.size
        state.importUri(Uri.fromFile(f))
        assertTrue("imported", Smoke.pumpUntil(30_000) { c.doc.layers.size > layers && c.busyMessage == null && !c.vectors.isRendering })
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("Transform opens on it", Smoke.pumpUntil(10_000) { t.transformState != null })
        r.checkpoint("import")
        val vec = c.activeLayer
        assertTrue(vec.isVectorLayer)
        assertTrue(c.isVectorMode)
        val imported = vec.vector!!
        assertEquals("four objects: ${imported.objects.map { it::class.simpleName }}", 4, imported.objects.size)
        // Placed as it is: ✓ changes nothing.
        t.commit()
        r.checkpoint("place as is", steps = 0)
        val start = r.snapshot()

        // The Curve tool reopens the curve (the group's translate applied: it starts at 40, 60).
        r.tool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        val path = near(vec, 40f, 60f) as VPath
        r.tap(40f, 60f)
        assertTrue("the imported curve reopens", Smoke.pumpUntil(10_000) { curve.isReopened })
        assertEquals(path.subpaths.single().anchors.size, curve.anchors.size)
        curve.moveAnchor(0, Vec2(40f, 90f))
        curve.endNumericEdit()
        curve.commit()
        r.checkpoint("edit the imported curve")
        assertEquals(CurveTool.EDIT_PATH_LABEL, c.undoManager.undoLabel)
        assertEquals(90f, (vec.vector!!.byId(path.id) as VPath).subpaths.single().anchors[0].y, 1e-3f)

        // The bucket recolors the rectangle's fill.
        r.tool(ToolId.FILL)
        c.color = 0xFF3060C0.toInt()
        val rect = near(vec, 360f, 80f)!!
        r.tap(360f, 80f)
        r.checkpoint("bucket on the imported rectangle")
        val rc = vec.vector!!.byId(rect.id)!!
        val fill = when (rc) { is VPath -> rc.fill; is VShape -> VPaint.Solid(rc.shape.fillColor); else -> null }
        assertEquals(VPaint.Solid(0xFF3060C0.toInt()), fill)

        // The partial eraser cuts the polyline in two.
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.PARTIAL)
        c.eraser = c.eraser.copy(size = 12f)
        val n = vec.vector!!.objects.size
        r.stroke(340f to 195f, 340f to 220f, 340f to 245f)
        r.checkpoint("partial erase of the imported polyline")
        assertEquals("cut in two", n + 1, vec.vector!!.objects.size)

        // Lasso the circle, Object bar Delete.
        // (The imported objects are still selected from the import's Transform.)
        r.tool(ToolId.LASSO)
        val circle = near(vec, 120f, 220f)!!
        r.stroke(50f to 160f, 180f to 160f, 180f to 285f, 50f to 285f, 50f to 162f)
        assertTrue(Smoke.pumpUntil(10_000) { c.vectors.selectedIds == setOf(circle.id) })
        r.checkpoint("lasso the circle", steps = 0)
        assertTrue(ObjectActions.delete(c))
        r.checkpoint("delete the circle")
        assertEquals(n, vec.vector!!.objects.size)

        // Saved and reloaded: the edits are there and still editable.
        val edited = r.snapshot()
        r.saveAndReopen()
        r.assertState("reloaded", edited)
        val back = c.doc.layers.first { it.name == vec.name }
        c.selectLayer(back)
        r.resync()
        // Undo history is per session: in this session, a new edit and its undo.
        r.tool(ToolId.CURVE)
        r.tap(40f, 90f)
        assertTrue("reopens after reload", Smoke.pumpUntil(10_000) { curve.isReopened || (c.tools.getValue(ToolId.CURVE) as CurveTool).isReopened })
        val cv = c.tools.getValue(ToolId.CURVE) as CurveTool
        cv.discard()
        r.checkpoint("discard", steps = 0)
        assertNotNull(back.vector)
        assertTrue(start.layers.isNotEmpty())
    }

    @Test
    fun undoingEveryEditOfTheImportedDrawingGivesTheImportBackExactly() {
        r = VectorQaRig(480, 320)
        val f = File(r.activity.cacheDir, "qa-foreign2.svg").apply { writeText(svg) }
        val state = ExchangeUiState(c)
        state.context = r.activity
        state.importUri(Uri.fromFile(f))
        val t = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue(Smoke.pumpUntil(30_000) { t.transformState != null && c.busyMessage == null && !c.vectors.isRendering })
        // Placed smaller and turned: one step with the import? No: the import is its own step.
        r.checkpoint("import")
        t.setScalePercent(80.0); t.endNumericEdit()
        t.setRotation(10.0); t.endNumericEdit()
        t.commit()
        r.checkpoint("place smaller, turned")
        val vec = c.activeLayer
        r.tool(ToolId.ERASER)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.eraser = c.eraser.copy(size = 16f)
        val box = VectorOps.bounds(vec.vector!!.objects[2])
        r.stroke(box.centerX() - 10f to box.centerY(), box.centerX() + 10f to box.centerY())
        r.checkpoint("erase a whole object")
        r.tool(ToolId.BRUSH)
        r.stroke(40f to 300f, 440f to 300f)
        r.checkpoint("a stroke over it")
        repeat(4) { r.undoAndCheck("undo ${it + 1}") }
        assertEquals(0, c.undoManager.undoCount)
        repeat(4) { r.redoAndCheck("redo ${it + 1}") }
    }
}
