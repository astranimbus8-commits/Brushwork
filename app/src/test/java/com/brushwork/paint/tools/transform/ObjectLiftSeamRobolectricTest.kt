package com.brushwork.paint.tools.transform

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.SelectionJobs
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.5 foundation (§5.6, §5.10 item 8): the Transform tool's object-lift seam and the selection
 * funnel that turns lasso / select-shape areas into object selections on vector layers.
 */
@RunWith(RobolectricTestRunner::class)
class ObjectLiftSeamRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 200
    private val h = 120

    private fun setup(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", w, h)
        repeat(2) { i -> doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", BitmapUtils.createLayerBitmap(w, h)) }
        doc.activeLayerIndex = 1
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun vectorLayer(c: EditorController): Layer {
        val l = c.activeLayer
        Canvas(l.bitmap).drawRect(20f, 20f, 60f, 50f, Paint().apply { color = 0xFFCC2200.toInt() })
        l.markChanged()
        l.vector = VectorContent.EMPTY
        return l
    }

    private class FakeLift(override val layer: Layer, override val floating: Bitmap, override val sourceRect: Rect) : ObjectLift {
        override val floatingScale: Float = 1f
        var bases = 0
        val commits = ArrayList<Pair<TransformState, String>>()
        val deletes = ArrayList<String>()
        var releases = 0

        override fun drawBase(canvas: Canvas) { bases++ }
        override fun commit(state: TransformState, label: String): Boolean { commits += state to label; return true }
        override fun delete(label: String): Boolean { deletes += label; return true }
        override fun release() { releases++ }
    }

    private class FakeProvider(val make: () -> ObjectLift) : ObjectLiftProvider {
        var lifts = 0
        val taps = ArrayList<Vec2>()
        var tapResult = false
        val made = ArrayList<ObjectLift>()

        override fun lift(layer: Layer, onReady: (ObjectLift?) -> Unit): Boolean {
            lifts++
            onReady(make().also { made += it })
            return true
        }

        override fun tapped(p: Vec2): Boolean { taps += p; return tapResult }
    }

    private fun fakeFor(l: Layer) = FakeProvider {
        val floating = BitmapUtils.createLayerBitmap(40, 30).also { it.eraseColor(0xFF00AA00.toInt()) }
        FakeLift(l, floating, Rect(20, 20, 60, 50))
    }

    @Test
    fun aProviderDrivesLiftCommitCancelAndDelete() {
        val c = setup()
        val l = vectorLayer(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val provider = fakeFor(l)
        tool.objectLiftProvider = { provider }
        tool.start()
        assertEquals(1, provider.lifts)
        val st = tool.transformState
        assertNotNull(st)
        assertEquals(40, st!!.srcW)
        // The preview: the base (the lift's hole) and the floating objects.
        val preview = BitmapUtils.createLayerBitmap(w, h)
        c.compositor.drawDocument(Canvas(preview), null, target = null)
        val lift1 = provider.made[0] as FakeLift
        assertTrue(lift1.bases > 0)
        assertEquals(0xFF00AA00.toInt(), preview.getPixel(30, 30))
        assertEquals("nothing outside the floating", 0, preview.getPixel(100, 100))
        // ✓ maps the geometry through the lift (one call), then releases it.
        tool.moveBy(10f, 0f)
        tool.commit()
        assertEquals(1, lift1.commits.size)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, lift1.commits[0].second)
        assertEquals(st.cx + 10f, lift1.commits[0].first.cx, 1e-4f)
        assertEquals(1, lift1.releases)
        assertNull(tool.transformState)
        assertNull(c.renderOverride)
        assertNotNull("the pixels were not touched by the tool", l.vector)
        // ✕ releases without committing.
        tool.start()
        val lift2 = provider.made[1] as FakeLift
        tool.moveBy(5f, 5f)
        tool.discard()
        assertEquals(0, lift2.commits.size)
        assertEquals(1, lift2.releases)
        // Delete goes through the lift.
        tool.start()
        val lift3 = provider.made[2] as FakeLift
        assertTrue(tool.deleteContent())
        assertEquals(listOf(TransformTool.DELETE_LABEL), lift3.deletes)
        assertEquals(1, lift3.releases)
        // An unchanged lift commits nothing.
        tool.start()
        tool.commit()
        assertEquals(0, (provider.made[3] as FakeLift).commits.size)
    }

    @Test
    fun aTapOutsideTheBoxGoesToTheProvider() {
        val c = setup()
        val l = vectorLayer(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        val provider = fakeFor(l)
        tool.objectLiftProvider = { provider }
        tool.start()
        tool.onDown(ToolPoint(150f, 90f))
        tool.onUp(ToolPoint(150f, 90f))
        assertEquals(listOf(Vec2(150f, 90f)), provider.taps)
        assertEquals("not taken: nothing changes", 1, provider.lifts)
        provider.tapResult = true
        tool.onDown(ToolPoint(150f, 90f))
        tool.onUp(ToolPoint(150f, 90f))
        assertEquals("taken: the objects are lifted again", 2, provider.lifts)
        assertEquals(1, (provider.made[0] as FakeLift).releases)
        assertNotNull(tool.transformState)
        // A tap inside the box is not a selection tap.
        tool.onDown(ToolPoint(30f, 30f))
        tool.onUp(ToolPoint(30f, 30f))
        assertEquals(2, provider.taps.size)
        tool.discard()
    }

    /** A vector layer whose cache is the rendering of one filled box (20..60, 20..50). */
    private fun boxLayer(c: EditorController): Layer {
        val l = c.activeLayer
        l.vector = VectorContent.EMPTY
        val box = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(20f, 20f, true), VAnchor(60f, 20f, true), VAnchor(60f, 50f, true), VAnchor(20f, 50f, true)), closed = true)),
            fill = VPaint.Solid(0xFFCC2200.toInt()),
        )
        assertEquals(1, c.vectors.addObjects(l, listOf(box), "Add").size)
        return l
    }

    /**
     * The seam's fallback (lead decision, v1.5 integration): a provider that refuses makes the
     * Transform tool lift PIXELS, as on a raster layer; committing them turns the layer into a
     * raster layer, undoably. The real provider never refuses a layer that has objects (see the
     * tests below), so this only happens through the seam.
     */
    @Test
    fun aRefusingProviderFallsBackToPixels() {
        val c = setup()
        val l = boxLayer(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.discard()
        tool.objectLiftProvider = { RefusingLiftProvider }
        tool.start()
        assertNotNull(tool.transformState)
        tool.moveBy(10f, 0f)
        tool.commit()
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertNull("a pixel edit turns it into a raster layer", l.vector)
        assertEquals(0xFFCC2200.toInt(), l.bitmap.getPixel(65, 30))
        c.undo()
        assertNotNull(l.vector)
        assertEquals(0, l.bitmap.getPixel(65, 30))
    }

    /** The real provider: a vector layer's objects are transformed as objects, never as pixels. */
    @Test
    fun vectorLayersTransformTheirObjects() {
        val c = setup()
        val l = boxLayer(c)
        assertTrue(c.vectors.liftProvider !== RefusingLiftProvider)
        c.selectTool(ToolId.TRANSFORM)
        shadowOf(Looper.getMainLooper()).idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.start()
        assertNotNull(tool.transformState)
        val steps = c.undoManager.undoCount
        tool.moveBy(10f, 0f)
        tool.commit()
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_OBJECTS_LABEL, c.undoManager.undoLabel)
        val moved = l.vector!!.objects.single() as VPath
        assertEquals(70f, moved.subpaths[0].anchors.maxOf { it.x }, 1e-3f)
        assertEquals(0xFFCC2200.toInt(), l.bitmap.getPixel(65, 30))
        assertEquals(0, l.bitmap.getPixel(25, 30))
        c.undo()
        assertEquals(60f, (l.vector!!.objects.single() as VPath).subpaths[0].anchors.maxOf { it.x }, 1e-3f)
    }

    /**
     * An empty vector layer has nothing to transform, with or without a pixel selection: nothing
     * is lifted, no step is recorded and the layer stays a vector layer (transparent pixels are
     * never lifted and committed, which would rasterize it).
     */
    @Test
    fun anEmptyVectorLayerHasNothingToTransformAndStaysAVectorLayer() {
        val c = setup()
        val l = c.activeLayer
        l.vector = VectorContent.EMPTY
        c.selectTool(ToolId.TRANSFORM)
        shadowOf(Looper.getMainLooper()).idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.discard()
        assertSame("no objects: the pixel path, which finds nothing either", RefusingLiftProvider, c.vectors.liftProvider)
        c.message = null
        tool.start()
        assertNull(tool.transformState)
        assertEquals("Nothing to transform on this layer", c.message)
        assertSame(VectorContent.EMPTY, l.vector)
        assertEquals(0, c.undoManager.undoCount)
        // With a pixel selection the object provider answers (and finds nothing either).
        c.setSelection(Selection.all(w, h), recordUndo = false)
        assertTrue(c.vectors.liftProvider !== RefusingLiftProvider)
        c.message = null
        tool.start()
        assertNull(tool.transformState)
        assertEquals("Nothing to transform on this layer", c.message)
        tool.commit()
        assertSame(VectorContent.EMPTY, l.vector)
        assertEquals(0, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ selection funnel

    private fun await(job: Job) {
        repeat(400) {
            if (!job.isActive) return
            shadowOf(Looper.getMainLooper()).idle()
            Thread.sleep(5)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    @Test
    fun areaSelectionsBecomeObjectSelectionsOnlyOnVectorLayers() {
        val c = setup()
        val calls = ArrayList<SelectionMode>()
        var answer = true
        c.vectors.selectObjectsHook = { _, mode -> calls += mode; answer }
        // A raster layer: pixels as before.
        await(SelectionJobs.applyAsync(c, "Lasso", SelectionMode.REPLACE, "Selecting…", toObjects = true) { Selection.all(w, h) })
        assertEquals(0, calls.size)
        assertNotNull(c.selection)
        c.deselect()
        // A vector layer: the funnel takes it, no pixel selection.
        vectorLayer(c)
        await(SelectionJobs.applyAsync(c, "Lasso", SelectionMode.ADD, "Selecting…", toObjects = true) { Selection.all(w, h) })
        assertEquals(listOf(SelectionMode.ADD), calls)
        assertNull(c.selection)
        // Tools that don't pass toObjects (magic wand...) keep selecting pixels.
        await(SelectionJobs.applyAsync(c, "Magic wand", SelectionMode.REPLACE, "Selecting…") { Selection.all(w, h) })
        assertEquals(1, calls.size)
        assertNotNull(c.selection)
        c.deselect()
        // Not taken by the vector service: pixels as before.
        answer = false
        await(SelectionJobs.applyAsync(c, "Lasso", SelectionMode.REPLACE, "Selecting…", toObjects = true) { Selection.all(w, h) })
        assertEquals(2, calls.size)
        assertNotNull(c.selection)
    }
}
