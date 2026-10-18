package com.brushwork.paint.v19.a1

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RectF
import android.os.Looper
import androidx.compose.runtime.snapshots.Snapshot
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.vector.CurveSettings
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.common.CurveLabels19
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.9 A1 (R14, D22; design 3.2 A1 step 10): "Editable (own layer)" in the Curve, Polyline and
 * Path settings sheets, off by default. Off, a path on a pixel layer is painted into it, as in
 * v1.8. On, ✓ puts it into a new vector layer "Curves N" above the pixel layer (the layer and the
 * object ONE undo step named after the tool, the pixel layer untouched), and the next paths, of
 * any of the three kinds, join that layer while it is active. It is ignored while erasing, on a
 * vector layer (paths join it, as in v1.8), on a mask, on a text layer and for a brush that needs
 * pixels; at the layer limit the path stays pending.
 */
@RunWith(RobolectricTestRunner::class)
class CurveOwnLayerTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val w = 300
    private val h = 220
    private val red = 0xFFD02020.toInt()

    /** "Background" and "Layer 2" (a red square at 20..60, active), two pixel layers; "text" makes Layer 2 a text layer. */
    private fun setup(kind: String = "raster"): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h))
        doc.layers += Layer(doc.newLayerId(), "Layer 2", BitmapUtils.createLayerBitmap(w, h)).also { l ->
            Canvas(l.bitmap).drawRect(20f, 20f, 60f, 60f, Paint().apply { color = red })
            when (kind) {
                "text" -> l.textData = "{}"
                "vector" -> { l.vector = VectorContent.EMPTY; l.name = "Vector 1" }
            }
        }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
            it.color = 0xFF203080.toInt()
            it.snapping.enabled = false
        }
    }

    /** A plain line 8 px wide, no fill, open; [own] is "Editable (own layer)". */
    private fun plain(own: Boolean): (CurveSettings) -> CurveSettings =
        { it.copy(stroke = CurveStroke.PLAIN, plainWidth = 8f, useBrushSize = false, fill = false, closed = false, ownLayer = own) }

    /** Tool [id] set up by [look], with the points [pts] (none selected). */
    private fun EditorController.drawing(id: ToolId, vararg pts: Vec2, look: (CurveSettings) -> CurveSettings = plain(true)): CurveTool {
        selectTool(id)
        val t = tools.getValue(id) as CurveTool
        t.update(look)
        for (p in pts) assertTrue("$id adds $p", t.addAnchor(p))
        t.deselect()
        return t
    }

    private val line = arrayOf(Vec2(80f, 100f), Vec2(160f, 140f), Vec2(240f, 100f))

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun inked(b: Bitmap): Int = pixels(b).count { it ushr 24 != 0 }

    private fun settle() {
        Snapshot.sendApplyNotifications()
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun label(id: ToolId) = when (id) {
        ToolId.CURVE -> "Curve"
        ToolId.POLYLINE -> "Polyline"
        else -> CurveTool.PATH_LABEL
    }

    // ------------------------------------------------------------------ off: v1.8

    @Test
    fun offByDefaultEveryToolPaintsThePixelLayerAsInV18() {
        for (id in A1Tools.CURVES) {
            val c = setup()
            val raster = c.doc.activeLayer
            c.selectTool(id)
            val t = c.tools.getValue(id) as CurveTool
            assertFalse("$id: off by default", t.settings.ownLayer)
            assertFalse("$id: the default is off", CurveSettings().ownLayer)
            val before = inked(raster.bitmap)
            val steps = c.undoManager.undoCount
            c.drawing(id, *line, look = plain(false)).commit()
            assertEquals("$id: no new layer", 2, c.doc.layers.size)
            assertSame(raster, c.doc.activeLayer)
            assertTrue("$id: painted into the pixel layer", inked(raster.bitmap) > before)
            assertEquals(steps + 1, c.undoManager.undoCount)
            assertEquals(label(id), c.undoManager.undoLabel)
            Smoke.assertQuiet(c, "$id off")
        }
    }

    // ------------------------------------------------------------------ on: "Curves N"

    @Test
    fun onTheFirstPathGoesIntoANewCurvesLayerAsOneStepAndUndoRestoresTheDocument() {
        for (id in A1Tools.CURVES) {
            val c = setup()
            val raster = c.doc.activeLayer
            val before = pixels(raster.bitmap)
            val steps = c.undoManager.undoCount
            val vectorsBefore = c.doc.layers.count { it.isVectorLayer }
            val t = c.drawing(id, *line)
            assertTrue("$id: on", t.settings.ownLayer)
            assertArrayEquals("$id: the preview leaves the pixel layer as it is", before, pixels(raster.bitmap))
            t.commit()
            assertFalse("$id: nothing pending", t.hasPendingWork)
            assertEquals("$id: one more layer", 3, c.doc.layers.size)
            val v = c.doc.activeLayer
            assertTrue("$id: the new layer is active and a vector layer", v.isVectorLayer)
            assertEquals("$id: named after the curves", "${CurveLabels19.LAYER_BASE} ${vectorsBefore + 1}", v.name)
            assertEquals("$id: right above the pixel layer", c.doc.indexOf(raster) + 1, c.doc.indexOf(v))
            val o = v.vector!!.objects.single()
            assertTrue("$id: the path is an object: $o", o is VPath)
            assertEquals(VStrokeKind.PLAIN, (o as VPath).stroke?.kind)
            assertTrue("$id: the layer draws it", inked(v.bitmap) > 0)
            assertArrayEquals("$id: the pixel layer untouched", before, pixels(raster.bitmap))
            assertEquals("$id: ONE step", steps + 1, c.undoManager.undoCount)
            assertEquals(label(id), c.undoManager.undoLabel)
            val drawn = pixels(v.bitmap)
            Smoke.assertQuiet(c, "$id on")

            c.undo()
            assertEquals("$id: undo removes the layer", 2, c.doc.layers.size)
            assertSame(raster, c.doc.activeLayer)
            assertArrayEquals(before, pixels(raster.bitmap))
            c.redo()
            assertEquals(3, c.doc.layers.size)
            val back = c.doc.layers[c.doc.indexOf(raster) + 1]
            assertSame(v, back)
            assertEquals(1, back.vector!!.objects.size)
            assertArrayEquals("$id: redo brings both back", drawn, pixels(back.bitmap))
            assertArrayEquals(before, pixels(raster.bitmap))
        }
    }

    @Test
    fun theNextPathsOfEveryKindJoinTheActiveCurvesLayer() {
        val c = setup()
        c.drawing(ToolId.CURVE, *line).commit()
        val v = c.doc.activeLayer
        assertTrue(v.isVectorLayer)
        var steps = c.undoManager.undoCount
        for ((i, id) in listOf(ToolId.POLYLINE, ToolId.PATH, ToolId.CURVE).withIndex()) {
            val y = 40f + 30f * i
            c.drawing(id, Vec2(40f, y), Vec2(150f, y + 20f), Vec2(260f, y)).commit()
            assertEquals("$id: no other layer", 3, c.doc.layers.size)
            assertSame(v, c.doc.activeLayer)
            assertEquals("$id joins it", i + 2, v.vector!!.objects.size)
            assertEquals("$id: one step", ++steps, c.undoManager.undoCount)
        }
        Smoke.assertQuiet(c, "joined")
    }

    @Test
    fun aBrushLineGoesIntoItsOwnLayerAndThePixelLayerGetsItsPixelsBack() {
        val c = setup()
        val raster = c.doc.activeLayer
        val before = pixels(raster.bitmap)
        val t = c.drawing(ToolId.CURVE, *line, look = { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false, ownLayer = true) })
        settle()
        t.commit()
        settle()
        val v = c.doc.activeLayer
        assertTrue(v.isVectorLayer)
        assertEquals(VStrokeKind.BRUSH, (v.vector!!.objects.single() as VPath).stroke?.kind)
        assertTrue("the layer replays the brush line", inked(v.bitmap) > 0)
        assertArrayEquals("the live stroke on the pixel layer is gone", before, pixels(raster.bitmap))
        assertEquals(1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "brush")
    }

    @Test
    fun aSelectionDoesNotClipTheObjectNorItsPreview() {
        val c = setup()
        c.setSelection(Selection.fromPath(Path().apply { addRect(0f, 0f, 150f, h.toFloat(), Path.Direction.CW) }, w, h, antiAlias = false), recordUndo = false)
        val t = c.drawing(ToolId.POLYLINE, Vec2(40f, 120f), Vec2(260f, 120f))
        assertNotNull("previewed unclipped, as the object will be", c.renderOverride)
        assertEquals("UnclippedPreview", c.renderOverride!!::class.simpleName)
        t.commit()
        val v = c.doc.activeLayer
        assertTrue(v.isVectorLayer)
        assertTrue("drawn outside the selection too", v.bitmap.getPixel(220, 120) ushr 24 != 0)
    }

    @Test
    fun switchingToolOrLayerWithAPendingPathPutsItIntoItsOwnLayerOnce() {
        // Another tool: the pending path is committed while the tool goes (the layer insert
        // pauses the tool again: nothing is committed twice).
        var c = setup()
        c.drawing(ToolId.CURVE, *line)
        c.selectTool(ToolId.POLYLINE)
        assertEquals(3, c.doc.layers.size)
        assertEquals(1, c.doc.activeLayer.vector!!.objects.size)
        assertEquals(1, c.undoManager.undoCount)
        assertFalse((c.tools.getValue(ToolId.CURVE) as CurveTool).hasPendingWork)
        Smoke.assertQuiet(c, "tool switch")
        // Another layer: the path goes into its own layer, then the chosen layer is active.
        c = setup()
        val background = c.doc.layers[0]
        c.drawing(ToolId.PATH, *line)
        c.selectLayer(background)
        assertEquals(3, c.doc.layers.size)
        assertSame(background, c.doc.activeLayer)
        assertEquals(1, c.doc.layers[2].vector!!.objects.size)
        assertEquals(1, c.undoManager.undoCount)
        Smoke.assertQuiet(c, "layer switch")
    }

    // ------------------------------------------------------------------ where it does not apply

    @Test
    fun erasingIgnoresIt() {
        val c = setup()
        val raster = c.doc.activeLayer
        c.selectTool(ToolId.POLYLINE)
        val t = c.tools.getValue(ToolId.POLYLINE) as CurveTool
        t.update(plain(true))
        t.setErase(true)
        for (p in listOf(Vec2(10f, 40f), Vec2(80f, 40f))) assertTrue(t.addAnchor(p))
        val before = inked(raster.bitmap)
        t.commit()
        assertEquals("no new layer", 2, c.doc.layers.size)
        assertSame(raster, c.doc.activeLayer)
        assertTrue("the pixel layer is erased", inked(raster.bitmap) < before)
        assertTrue("the setting stays on", t.settings.ownLayer)
    }

    @Test
    fun onAVectorLayerPathsJoinItAsInV18() {
        val c = setup("vector")
        val v = c.doc.activeLayer
        c.drawing(ToolId.CURVE, *line).commit()
        assertEquals(2, c.doc.layers.size)
        assertSame(v, c.doc.activeLayer)
        assertEquals(1, v.vector!!.objects.size)
    }

    @Test
    fun aMaskATextLayerAndABrushThatNeedsPixelsKeepTheOldBehaviour() {
        // The mask of a pixel layer: painted into the mask.
        var c = setup()
        var raster = c.doc.activeLayer
        c.addMask(raster, fromSelection = false)
        assertEquals(EditTarget.MASK, c.editTargetOf(raster))
        c.drawing(ToolId.CURVE, *line).commit()
        assertEquals("mask: no new layer", 2, c.doc.layers.size)
        assertSame(raster, c.doc.activeLayer)
        // A text layer: as in v1.8.
        c = setup("text")
        c.drawing(ToolId.POLYLINE, *line).commit()
        assertEquals("text: no new layer", 2, c.doc.layers.size)
        assertFalse(c.doc.layers.any { it.isVectorLayer })
        // Smudge: it moves the pixel layer's own pixels, so it paints there.
        c = setup()
        raster = c.doc.activeLayer
        c.selectTool(ToolId.SMUDGE)
        val t = c.drawing(ToolId.CURVE, Vec2(30f, 30f), Vec2(90f, 50f), look = { it.copy(stroke = CurveStroke.BRUSH, fill = false, closed = false, ownLayer = true) })
        settle()
        t.commit()
        settle()
        assertEquals("smudge: no new layer", 2, c.doc.layers.size)
        assertSame(raster, c.doc.activeLayer)
        assertFalse(t.hasPendingWork)
    }

    @Test
    fun aReopenedPathIsEditedInPlace() {
        val c = setup()
        c.drawing(ToolId.CURVE, *line).commit()
        val v = c.doc.activeLayer
        val t = c.tools.getValue(ToolId.CURVE) as CurveTool
        val id = v.vector!!.objects.single().id
        c.pointerDown(ToolPoint(160f, 140f))
        c.pointerUp(ToolPoint(160f, 140f))
        settle()
        assertTrue(t.isReopened)
        t.commit()
        settle()
        assertEquals(3, c.doc.layers.size)
        assertEquals(id, v.vector!!.objects.single().id)
    }

    @Test
    fun atTheLayerLimitThePathStaysPending() {
        val app = RuntimeEnvironment.getApplication()
        val doc = Smoke.document(64, 64, layers = 100)
        val c = Smoke.controller(app, doc, scope)
        assumeTrue("the heap allows the 100-layer cap", c.maxLayers == 100)
        c.viewTransform.set(Matrix())
        c.tools
        c.snapping.enabled = false
        assertFalse(c.canAddLayer)
        val raster = c.doc.activeLayer
        val before = pixels(raster.bitmap)
        val t = c.drawing(ToolId.CURVE, Vec2(10f, 10f), Vec2(30f, 50f), Vec2(54f, 20f))
        c.message = null
        t.commit()
        assertTrue("the limit's message: ${c.message}", c.message?.startsWith("Layer limit reached") == true)
        assertEquals(100, c.doc.layers.size)
        assertEquals(0, c.undoManager.undoCount)
        assertTrue("still pending", t.hasPendingWork)
        assertEquals(3, t.pointCount)
        assertSame(raster, c.doc.activeLayer)
        assertArrayEquals(before, pixels(raster.bitmap))
        assertNotNull("previewed again", c.renderOverride)
        t.discard()
        Smoke.assertQuiet(c, "limit")
    }

    // ------------------------------------------------------------------ the setting

    @Test
    fun theSettingIsEachToolsOwnAndIsSavedAlsoWhileALookShows() {
        val c = setup()
        for ((id, key) in listOf(ToolId.CURVE to "vec.curve", ToolId.POLYLINE to "vec.polyline", ToolId.PATH to "vec.path")) {
            c.selectTool(id)
            val t = c.tools.getValue(id) as CurveTool
            t.update { it.copy(ownLayer = true) }
            assertEquals("$id saved", true, c.settings.getObject(key, CurveSettings.serializer())?.ownLayer)
        }
        // The Path tool's Capsule shows a look of its own while it is pending: own layer stays
        // the user's, and a change of it is kept when the look goes.
        val t = c.tools.getValue(ToolId.PATH) as CurveTool
        c.selectTool(ToolId.PATH)
        assertTrue(t.startShape(CurveTool.PathShape.CAPSULE, RectF(0f, 0f, w.toFloat(), h.toFloat())))
        assertTrue("the look keeps own layer", t.settings.ownLayer)
        t.update { it.copy(ownLayer = false) }
        assertFalse(t.settings.ownLayer)
        assertEquals(false, c.settings.getObject("vec.path", CurveSettings.serializer())?.ownLayer)
        t.discard()
        assertFalse("the user's setting after the look", t.settings.ownLayer)
        assertTrue("Curve keeps its own", (c.tools.getValue(ToolId.CURVE) as CurveTool).settings.ownLayer)
    }
}
