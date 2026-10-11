package com.brushwork.paint.assist

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
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
 * v1.8 D (design §3.1 "Guides", §4.4): while any of the nine tools the rulers repeat is active and
 * symmetry is on, the ruler's guides are drawn faintly, as the brush draws them: Brush, Eraser,
 * Smudge, Blur, and now Curve, Polyline, Path, Shape and Clone stamp, with every ruler kind. They
 * draw under the tool's own overlay, never the Symmetry tool's handles, nothing with symmetry off,
 * and nothing for the vector eraser (it removes whole objects and is not replicated).
 */
@RunWith(RobolectricTestRunner::class)
class MirrorGuidesForToolsTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 320
    private val h = 240

    private val nine = listOf(
        ToolId.BRUSH, ToolId.ERASER, ToolId.SMUDGE, ToolId.BLUR,
        ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE, ToolId.CLONE,
    )
    private val newFive = listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE, ToolId.CLONE)

    /** One of each ruler kind, sized so that each draws lines inside the 320 × 240 canvas. */
    private val rulers = listOf(
        SymmetrySettings(type = SymmetryType.MIRROR),
        SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 6),
        SymmetrySettings(type = SymmetryType.ROTATION, divisions = 8),
        SymmetrySettings(type = SymmetryType.ARRAY, spacingX = 80f, spacingY = 60f),
        SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY),
    )

    private fun setup(vector: Boolean = false): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("guides", "Guides", w, h)
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

    private fun draw(block: (Canvas) -> Unit): IntArray {
        val b = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        block(Canvas(b))
        return b.pixels()
    }

    private fun EditorController.overlay(): IntArray = draw { drawOverlays(it, 0f) }

    private fun guidesAlone(c: EditorController): IntArray = draw { SymmetryGuides.drawGuides(it, c.viewTransform, c.doc) }

    private fun IntArray.drawn(): Int = count { it != 0 }

    @Test
    fun drawForDrawsTheGuidesForTheNineToolsWithEveryRuler() {
        val c = setup()
        for (ruler in rulers) {
            c.updateSymmetry(ruler)
            val guides = guidesAlone(c)
            assertTrue("${ruler.type}: the guides draw lines", guides.drawn() > 100)
            for (id in nine) {
                assertTrue("$id shows guides", SymmetryGuides.showsFor(id))
                val got = draw { SymmetryGuides.drawFor(it, c.viewTransform, c.doc, id) }
                assertArrayEquals("${ruler.type}, $id: drawFor draws the guides", guides, got)
            }
        }
    }

    @Test
    fun theFiveNewToolsShowTheGuidesWhileActive() {
        val c = setup()
        for (ruler in rulers) {
            c.updateSymmetry(ruler)
            val guides = guidesAlone(c)
            for (id in newFive) {
                c.selectTool(id)
                assertEquals(id, c.activeToolId)
                assertArrayEquals("${ruler.type}, $id: the idle overlay is the guides", guides, c.overlay())
            }
        }
    }

    @Test
    fun theGuidesAreNotTheSymmetryToolsHandles() {
        val c = setup()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY))
        val handles = draw { SymmetryGuides.draw(it, c.viewTransform, c.doc, editing = true) }
        val guides = guidesAlone(c)
        assertFalse("the Symmetry tool draws its handles too", handles.contentEquals(guides))
        for (id in newFive) {
            c.selectTool(id)
            assertArrayEquals("$id: guides only, no handles", guides, c.overlay())
        }
    }

    @Test
    fun symmetryOffShowsNothingForAnyOfTheNine() {
        val c = setup()
        c.updateSymmetry(SymmetrySettings())
        for (id in nine) {
            c.selectTool(id)
            assertEquals("$id with symmetry off", 0, c.overlay().drawn())
            assertEquals("$id: drawFor with symmetry off", 0, draw { SymmetryGuides.drawFor(it, c.viewTransform, c.doc, id) }.drawn())
        }
    }

    @Test
    fun onAVectorLayerTheCurveToolsAndTheShapeShowTheGuidesButNotTheVectorEraser() {
        val c = setup(vector = true)
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.ROTATION, divisions = 5))
        val guides = guidesAlone(c)
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH, ToolId.SHAPE, ToolId.BRUSH)) {
            c.selectTool(id)
            assertArrayEquals("$id on a vector layer", guides, c.overlay())
        }
        assertEquals("the vector eraser: no guides", 0, draw { SymmetryGuides.drawFor(it, c.viewTransform, c.doc, ToolId.ERASER) }.drawn())
        // The clone stamp is refused on a vector layer with its own message, as smudge and blur
        // are; its guides still show while it is active, as theirs do.
        for (id in listOf(ToolId.CLONE, ToolId.SMUDGE, ToolId.BLUR)) {
            assertArrayEquals("$id on a vector layer", guides, draw { SymmetryGuides.drawFor(it, c.viewTransform, c.doc, id) })
        }
    }

    @Test
    fun theCloneCrosshairDrawsOverTheGuides() {
        val c = setup()
        c.updateSymmetry(SymmetrySettings(type = SymmetryType.KALEIDOSCOPE, divisions = 4))
        val guides = guidesAlone(c)
        c.selectTool(ToolId.CLONE)
        val tool = c.tools.getValue(ToolId.CLONE) as CloneTool
        tool.setSource(Vec2(60f, 50f))
        val both = c.overlay()
        val crosshairOnly = draw { tool.drawOverlay(it, c.viewTransform) }
        assertTrue("the crosshair draws", crosshairOnly.drawn() > 20)
        assertTrue("the guides draw under it", both.drawn() > guides.drawn())
        // Where the crosshair draws nothing, the overlay is the guides alone.
        for (i in both.indices) if (crosshairOnly[i] == 0) assertEquals("pixel $i", guides[i], both[i])
        // Non-repeating tools show none, with the same ruler.
        for (id in listOf(ToolId.FILL, ToolId.TEXT, ToolId.MAGIC_WAND, ToolId.LASSO)) {
            c.selectTool(id)
            assertFalse(SymmetryGuides.showsFor(id))
            assertEquals("$id shows no guides", 0, c.overlay().drawn())
        }
    }
}
