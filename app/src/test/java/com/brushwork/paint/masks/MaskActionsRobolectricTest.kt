package com.brushwork.paint.masks

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.mask.MaskTool
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.math.abs

/**
 * v1.5 A5 (§4.3b, §4.3e): undo of editable masks (data-only re-render, the tile fallback above
 * the estimate, replacing a painted mask), persistence, mask → selection, transforms of specs,
 * "As adjustment layer", the layers window commands and "Apply a filter through this mask".
 */
@RunWith(RobolectricTestRunner::class)
class MaskActionsRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private var w = 160
    private var h = 120

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        scope.cancel()
    }

    private fun setup(width: Int = 160, height: Int = 120): EditorController {
        w = width; h = height
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("a", "a", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(0xFF3366AA.toInt()) }
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c
    }

    private fun maskPixels(l: Layer) = IntArray(w * h).also { l.mask!!.getPixels(it, 0, w, 0, 0, w, h) }
    private fun rendered(spec: MaskSpec) = IntArray(w * h).also { MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), it, w) }

    private fun radial(id: Long = 1, cx: Float = 80f) = RadialMask(id, cx = cx, cy = 60f, rx = 30f, ry = 20f)

    @Test
    fun aSpecChangeIsDataOnlyAndUndoReRendersTheMask() {
        setup()
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), MaskSpec(components = listOf(radial()), nextId = 2))!!
        val before = adj.maskSpec!!
        val after = MaskSpec(components = listOf(radial(cx = 100f)), nextId = 2)
        val steps = c.undoManager.undoCount
        assertTrue(MaskEdits.apply(c, adj, after, "Edit mask"))
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertTrue("no tiles: ${totalBytes()}", totalBytes() < 4096)
        assertArrayEquals(rendered(after), maskPixels(adj))
        c.undo()
        assertEquals(before, adj.maskSpec)
        assertArrayEquals(rendered(before), maskPixels(adj))
        c.redo()
        assertArrayEquals(rendered(after), maskPixels(adj))
        // The same spec again: no step.
        assertTrue(MaskEdits.apply(c, adj, after, "Edit mask"))
        assertEquals(steps + 1, c.undoManager.undoCount)
    }

    private fun totalBytes(): Long {
        // The newest step's retained memory (the history keeps only data for data-only steps).
        val f = com.brushwork.paint.engine.UndoManager::class.java.getDeclaredField("undoStack").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val stack = f.get(c.undoManager) as ArrayDeque<com.brushwork.paint.engine.UndoAction>
        return stack.last().byteSize
    }

    @Test
    fun aBrushHeavySpecRecordsTilesAndUndoSwapsThem() {
        setup(400, 300)
        val strokes = List(400) { i ->
            val y = (i % 30) * 10f
            MaskStroke(false, 300f, 0.5f, 0.3f, PackedPoints(floatArrayOf(0f, 400f), floatArrayOf(y, 300f - y), floatArrayOf(1f, 1f)))
        }
        val heavy = MaskSpec(components = listOf(BrushMask(1, strokes = strokes), radial(2)), nextId = 3)
        val est = MaskSpecs.estimateMillis(heavy, Rect(0, 0, w, h))
        assertTrue("estimate $est ms", est > MaskEdits.TILE_THRESHOLD_MS)
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), heavy)!!
        val before = maskPixels(adj)
        val moved = heavy.copy(components = listOf(heavy.components[0], RadialMask(2, cx = 300f, cy = 200f, rx = 50f, ry = 50f)))
        assertTrue(MaskEdits.apply(c, adj, moved, "Edit mask", Rect(0, 0, w, h)))
        assertTrue("tiles kept: ${totalBytes()}", totalBytes() >= 256L * 256 * 4)
        val after = maskPixels(adj)
        c.undo()
        assertArrayEquals(before, maskPixels(adj))
        assertEquals(heavy, adj.maskSpec)
        c.redo()
        assertArrayEquals(after, maskPixels(adj))
    }

    @Test
    fun replacingAPaintedMaskKeepsItsPixelsForUndo() {
        setup()
        val photo = c.activeLayer
        c.addMask(photo, fromSelection = false)
        Canvas(photo.mask!!).drawRect(0f, 0f, 50f, 50f, Paint().apply { color = 0xFF000000.toInt() })
        val painted = maskPixels(photo)
        val spec = MaskSpec(components = listOf(radial()), nextId = 2)
        assertTrue(MaskEdits.apply(c, photo, spec, "Mask: radial"))
        assertArrayEquals(rendered(spec), maskPixels(photo))
        c.undo()
        assertNull(photo.maskSpec)
        assertArrayEquals(painted, maskPixels(photo))
    }

    @Test
    fun locksAndHiddenLayersRefuse() {
        setup()
        val photo = c.activeLayer
        photo.locked = true
        assertTrue(!MaskEdits.apply(c, photo, MaskSpec(components = listOf(radial())), "x"))
        assertEquals("Layer \"Photo\" is locked", c.message)
        photo.locked = false
        photo.visible = false
        assertTrue(!MaskEdits.apply(c, photo, MaskSpec(components = listOf(radial())), "x"))
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun saveAndLoadKeepTheSpecTheMaskAndTheEffect() = runBlocking {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        setup()
        val repo = ProjectRepository(app)
        val spec = MaskSpec(components = listOf(radial(), BrushMask(2, mode = MaskMode.SUBTRACT, strokes = listOf(MaskStroke(false, 20f, 0.4f, 0.8f, PackedPoints(floatArrayOf(10f, 150f), floatArrayOf(10f, 110f), floatArrayOf(1f, 1f)))))), nextId = 3)
        val invert = AdjustmentEffects.defaultSpec(FilterRegistry.byId("adjust.invert"))
        val adj = c.addAdjustmentLayer(invert, spec)!!
        adj.opacity = 0.6f
        repo.save(c.doc, null)
        val json = ProjectFormat.json.parseToJsonElement(File(app.filesDir, "projects/${c.doc.id}/${ProjectFormat.PROJECT_FILE}").readText()).jsonObject
        assertEquals(2, json.getValue("formatVersion").jsonPrimitive.int)
        val loaded = repo.load(c.doc.id)
        val l = loaded.layers.single { it.isAdjustmentLayer }
        assertEquals(spec, l.maskSpec)
        assertEquals(invert, l.adjustment)
        assertEquals(0.6f, l.opacity, 0f)
        assertArrayEquals(maskPixels(adj), IntArray(w * h).also { l.mask!!.getPixels(it, 0, w, 0, 0, w, h) })
        assertTrue(loaded.loadWarnings.isEmpty())
        // The loaded document composites like the original.
        val a = IntArray(w * h).also { c.compositor.renderFlattened().getPixels(it, 0, w, 0, 0, w, h) }
        val b = IntArray(w * h).also { com.brushwork.paint.engine.Compositor(loaded) { null }.renderFlattened().getPixels(it, 0, w, 0, 0, w, h) }
        assertArrayEquals(a, b)
    }

    @Test
    fun theMaskBecomesASoftSelection() {
        setup()
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), MaskSpec(components = listOf(LinearMask(1, x0 = 20f, y0 = 0f, x1 = 140f, y1 = 0f)), nextId = 2))!!
        MaskLayerOps.useAsSelection(c, adj)
        val sel = c.selection!!
        val bytes = BitmapUtils.alpha8ToBytes(sel.mask)
        val m = maskPixels(adj)
        for (x in 0 until w step 7) {
            val v = bytes[60 * w + x].toInt() and 0xFF
            assertTrue("soft at $x: $v vs ${m[60 * w + x] and 0xFF}", abs(v - (m[60 * w + x] and 0xFF)) <= 1)
        }
        assertTrue("partly selected somewhere", bytes.any { (it.toInt() and 0xFF) in 30..220 })
        assertEquals("Mask to selection", c.undoManager.undoLabel)
    }

    @Test
    fun flipsAndCanvasScalingCarryTheSpec() {
        setup()
        val photo = c.activeLayer
        val spec = MaskSpec(components = listOf(RadialMask(1, cx = 40f, cy = 30f, rx = 20f, ry = 10f, rotationDeg = 30f), LinearMask(2, mode = MaskMode.INTERSECT, x0 = 0f, y0 = 0f, x1 = 160f, y1 = 120f)), nextId = 3)
        assertTrue(MaskEdits.apply(c, photo, spec, "Mask"))
        c.flipLayer(photo, horizontal = true)
        val flipped = photo.maskSpec!!
        assertEquals(120f, (flipped.components[0] as RadialMask).cx, 1e-3f)
        // The flipped pixels are the flipped spec's rendering (I1), up to rounding.
        val want = rendered(flipped)
        val got = maskPixels(photo)
        for (i in want.indices) assertTrue(abs((want[i] and 0xFF) - (got[i] and 0xFF)) <= 1)
        c.undo()
        assertEquals(spec, photo.maskSpec)
        // Canvas scaling maps the spec with the pixels.
        val snap = CanvasSnapshot.of(c.doc)
        CanvasOps.commit(c, "Resize", snap, CanvasOps.resizeImage(snap, 320, 240, com.brushwork.paint.engine.Resample.BILINEAR))
        val scaled = photo.maskSpec
        assertNotNull(scaled)
        assertEquals(80f, ((scaled!!.components[0]) as RadialMask).cx, 1e-3f)
        assertEquals(40f, ((scaled.components[0]) as RadialMask).rx, 1e-3f)
    }

    @Test
    fun asAdjustmentLayerTurnsTheSelectionIntoItsMask() {
        setup()
        val photo = c.activeLayer
        val sel = Selection.fromBytes(ByteArray(w * h) { i -> if (i % w in 10..69 && i / w in 10..59) -1 else 0 }, w, h)
        c.setSelection(sel)
        val steps = c.undoManager.undoCount
        val invert = FilterRegistry.byId("adjust.invert")!!
        val adj = AdjustmentLayerOps.fromFilter(c, invert, invert.defaultValues())!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(AdjustmentLayerOps.LABEL, c.undoManager.undoLabel)
        assertTrue(adj.isAdjustmentLayer)
        assertEquals("adjust.invert", adj.adjustment!!.filterId)
        assertNull("the selection became the mask", c.selection)
        assertNull(adj.maskSpec)
        assertEquals(-1, adj.mask!!.getPixel(30, 30))
        assertEquals(0xFF000000.toInt(), adj.mask!!.getPixel(100, 100))
        val flat = c.compositor.renderFlattened()
        assertEquals(0xFFCC9955.toInt(), flat.getPixel(30, 30))
        assertEquals(0xFF3366AA.toInt(), flat.getPixel(100, 100))
        c.undo()
        assertEquals(-1, c.doc.indexOf(adj))
        assertNotNull(c.selection)
        assertSame(photo, c.activeLayer)
        // Filters that aren't pointwise can't.
        val blur = FilterRegistry.all.first { !it.isAdjustmentCapable }
        assertNull(AdjustmentLayerOps.fromFilter(c, blur, blur.defaultValues()))
    }

    @Test
    fun layersWindowCommandsOpenTheMasksTool() {
        setup()
        AdjustmentLayerOps.createDefault(c)
        val adj = c.activeLayer
        assertTrue(adj.isAdjustmentLayer)
        assertEquals("Tone 1", adj.name)
        assertEquals(ToolId.MASK, c.activeToolId)
        val tool = c.tools.getValue(ToolId.MASK) as MaskTool
        assertTrue(tool.adjustOpen)
        c.selectTool(ToolId.BRUSH)
        AdjustmentLayerOps.editMask(c, adj)
        assertEquals(ToolId.MASK, c.activeToolId)
        assertTrue(!tool.adjustOpen)
        AdjustmentLayerOps.edit(c, adj)
        assertTrue(tool.adjustOpen)
        // Filters on an adjustment layer: refused (the session closes itself too).
        val s = FilterSession(c, FilterRegistry.byId("adjust.invert")!!)
        s.start()
        assertTrue(s.isClosed)
    }

    @Test
    fun aFilterThroughTheMaskSelectsTheLayerBelow() {
        setup()
        val photo = c.activeLayer
        val adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(), MaskSpec(components = listOf(radial()), nextId = 2))!!
        assertTrue(MaskLayerOps.prepareFilterThroughMask(c, adj))
        assertSame(photo, c.activeLayer)
        assertNotNull(c.selection)
        c.startFilter(FilterRegistry.byId("adjust.invert")!!)
        assertNotNull("a filter runs on the photo through the mask", c.filterSession)
        c.filterSession!!.cancel()
        // A mask-less layer has nothing to offer.
        val l2 = c.addLayer()!!
        assertTrue(!MaskLayerOps.prepareFilterThroughMask(c, l2))
        // Converting to a pixel mask keeps the pixels and forgets the spec (one step).
        val px = maskPixels(adj)
        MaskLayerOps.toPixelMask(c, adj)
        assertNull(adj.maskSpec)
        assertArrayEquals(px, maskPixels(adj))
    }
}
