package com.brushwork.paint.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.8 F3 (design §4.4): the guides seam. [SymmetryGuides.showsFor] names the nine tools the
 * rulers repeat; `EditorController.drawOverlays` calls [SymmetryGuides.drawFor] for the active
 * tool, which draws the v1.7 guides of the brush, smudge and blur, and of the eraser except on a
 * vector layer's content (as `BrushTool.drawOverlay` did in v1.7). Tools that don't repeat show
 * none.
 */
@RunWith(RobolectricTestRunner::class)
class SymmetryGuidesForTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    private fun setup(vector: Boolean): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("sym", "Sym", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(w, h)).also { if (vector) it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.selectTool(ToolId.BRUSH)
            it.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
        }
    }

    private fun Bitmap.pixels(): IntArray = IntArray(width * height).also { getPixels(it, 0, width, 0, 0, width, height) }

    private fun EditorController.overlay(): IntArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        drawOverlays(Canvas(b), 0f)
        return b.pixels()
    }

    private fun guidesAlone(c: EditorController): IntArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        SymmetryGuides.drawGuides(Canvas(b), c.viewTransform, c.doc)
        return b.pixels()
    }

    @Test
    fun theNineToolsShowTheGuides() {
        val nine = setOf(
            ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR,
            ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE, ToolId.CLONE,
        )
        for (id in ToolId.entries) assertEquals("$id", id in nine, SymmetryGuides.showsFor(id))
    }

    @Test
    fun theBrushToolsDrawTheV17GuidesThroughDrawFor() {
        val c = setup(vector = false)
        val guides = guidesAlone(c)
        assertTrue("the mirror axis is drawn", guides.count { it != 0 } > 100)
        for (id in listOf(ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR)) {
            c.selectTool(id)
            assertArrayEquals("$id: the overlay is the guides", guides, c.overlay())
            val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
            SymmetryGuides.drawFor(Canvas(b), c.viewTransform, c.doc, id)
            assertArrayEquals("$id: drawFor draws them", guides, b.pixels())
        }
        // Tools that don't repeat show none (the Symmetry tool's own handles aside).
        for (id in listOf(ToolId.FILL, ToolId.TEXT, ToolId.MAGIC_WAND, ToolId.MAGNETIC_LASSO, ToolId.COLOR_SELECT)) {
            assertFalse(SymmetryGuides.showsFor(id))
            c.selectTool(id)
            assertEquals("$id shows no guides", 0, c.overlay().count { it != 0 })
        }
        // Off: nothing.
        c.selectTool(ToolId.BRUSH)
        c.updateSymmetry(SymmetrySettings())
        assertEquals(0, c.overlay().count { it != 0 })
    }

    @Test
    fun theVectorEraserShowsNoGuides() {
        val c = setup(vector = true)
        c.selectTool(ToolId.ERASER)
        assertEquals("the vector eraser removes whole objects: no guides", 0, c.overlay().count { it != 0 })
        c.selectTool(ToolId.BRUSH)
        assertTrue(c.overlay().count { it != 0 } > 100)
    }
}
