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

    @Test
    fun withoutAnObjectProviderVectorLayersTransformPixels() {
        val c = setup()
        val l = vectorLayer(c)
        c.selectTool(ToolId.TRANSFORM)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertSame(RefusingLiftProvider, c.vectors.liftProvider)
        tool.start()
        assertNotNull(tool.transformState)
        tool.moveBy(10f, 0f)
        tool.commit()
        assertNull("a pixel edit turns it into a raster layer", l.vector)
        assertEquals(0xFFCC2200.toInt(), l.bitmap.getPixel(65, 30))
        c.undo()
        assertNotNull(l.vector)
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
