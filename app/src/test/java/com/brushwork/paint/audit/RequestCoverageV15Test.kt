package com.brushwork.paint.audit

import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.exchange.QaExchange
import com.brushwork.paint.filters.FilterCategory
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveStroke
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStroke
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * REQUEST COVERAGE AUDIT (v1.5), controller level: the parts of the request that must hold beyond
 * one sitting — "make them so that it is possible to edit them after as well" (masks), vectors
 * and curves edited with the existing tools after the artwork is closed and opened again, text
 * that keeps wrapping, and the Tone filter's place and sliders. The UI reachability of every
 * clause: [RequestCoverageV15UiTest].
 */
@RunWith(RobolectricTestRunner::class)
class RequestCoverageV15Test {

    private val app get() = RuntimeEnvironment.getApplication()
    private val controllers = ArrayList<EditorController>()

    @After
    fun tearDown() = controllers.forEach { it.dispose() }

    private fun controller(doc: Document): EditorController = Smoke.controller(app, doc).also {
        controllers += it
        it.snapping.enabled = false
    }

    /** A project made by the gallery, opened in an editor. */
    private fun newArtwork(repo: ProjectRepository): EditorController {
        val id = runBlocking { repo.create(NewCanvasSpec("Audit", 400, 300, 300f)) }
        return controller(runBlocking { repo.load(id) })
    }

    /** Saves [c]'s artwork and opens it again in a new editor (as closing and reopening it does). */
    private fun reopen(repo: ProjectRepository, c: EditorController): EditorController {
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertTrue("no load warnings: ${loaded.loadWarnings}", loaded.loadWarnings.isEmpty())
        return controller(loaded)
    }

    private fun drag(c: EditorController, vararg pts: Pair<Float, Float>) = QaExchange.drag(c, *pts)

    private fun pixels(c: EditorController) = IntArray(c.doc.width * c.doc.height).also {
        c.compositor.renderFlattened().getPixels(it, 0, c.doc.width, 0, 0, c.doc.width, c.doc.height)
    }

    @Test
    fun lightroomMasksCanBeEditedAfterTheArtworkIsReopened() {
        val repo = ProjectRepository(app)
        val c = newArtwork(repo)
        val photo = c.doc.layers[1]
        c.editWholeLayer(photo, "Photo") { b -> Canvas(b).drawRect(0f, 0f, 400f, 300f, Paint().apply { color = 0xFF505050.toInt() }) }
        c.selectTool(ToolId.MASK)
        val mt = c.tools.getValue(ToolId.MASK) as MaskTool
        mt.arm(MaskTool.Kind.LINEAR)
        drag(c, 40f to 150f, 200f to 150f, 360f to 150f)
        mt.arm(MaskTool.Kind.RADIAL)
        drag(c, 200f to 150f, 230f to 150f, 260f to 150f)
        val tone = c.activeLayer
        assertTrue(tone.isAdjustmentLayer)
        val spec = tone.maskSpec!!
        assertEquals(2, spec.components.size)

        val c2 = reopen(repo, c)
        val tone2 = c2.doc.layers.single { it.isAdjustmentLayer }
        assertEquals("the mask's components came back", spec, tone2.maskSpec)
        assertEquals(tone.adjustment, tone2.adjustment)
        // Still editable: the Masks tool takes the linear component's end handle.
        c2.selectLayer(tone2)
        c2.selectTool(ToolId.MASK)
        val mt2 = c2.tools.getValue(ToolId.MASK) as MaskTool
        val linear = spec.components.first { it is LinearMask } as LinearMask
        mt2.select(linear.id)
        val steps = c2.undoManager.undoCount
        drag(c2, linear.x1 to linear.y1, linear.x1 - 60f to linear.y1, linear.x1 - 120f to linear.y1)
        val edited = tone2.maskSpec!!
        assertNotEquals(spec, edited)
        assertEquals(steps + 1, c2.undoManager.undoCount)
        // The mask is exactly the edited spec's rendering (I1).
        val w = c2.doc.width
        val h = c2.doc.height
        val expected = IntArray(w * h).also { MaskSpecs.render(edited, w, h, Rect(0, 0, w, h), it, w) }
        assertArrayEquals(expected, IntArray(w * h).also { tone2.mask!!.getPixels(it, 0, w, 0, 0, w, h) })
        assertTrue(edited.components.any { it is RadialMask })
    }

    @Test
    fun vectorsAndCurvesStayEditableWithTheUsualToolsAfterReopening() {
        val repo = ProjectRepository(app)
        val c = newArtwork(repo)
        c.toggleVectorMode()
        val layer = c.activeLayer
        assertTrue(layer.isVectorLayer)
        QaExchange.brush(c, 0xFF203060.toInt(), 40f to 60f, 200f to 80f, size = 12f)
        // A plain curve line at the brush size, one point at 250 %.
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = CurveStroke.PLAIN, useBrushSize = true) }
        c.updatePreset(ToolId.BRUSH, c.presetFor(ToolId.BRUSH)!!.copy(size = 16f))
        curve.addAnchor(Vec2(60f, 200f)); curve.addAnchor(Vec2(200f, 160f)); curve.addAnchor(Vec2(340f, 200f))
        curve.select(1)
        curve.setWidth(1, 2.5f)
        assertEquals(16f, curve.lineWidth, 1e-3f)
        curve.commit()
        val content = layer.vector!!
        val path = content.objects.filterIsInstance<VPath>().single()
        assertEquals("the line is the brush size", 16f, path.stroke!!.width, 1e-3f)
        assertEquals(2.5f, path.subpaths.single().anchors[1].width, 1e-3f)

        val c2 = reopen(repo, c)
        val layer2 = c2.doc.layers.single { it.isVectorLayer }
        assertEquals("objects, widths and all", content, layer2.vector)
        assertArrayEquals(pixels(c), pixels(c2))
        // The eraser cuts the reloaded stroke; Lasso then selects objects; Transform moves them.
        c2.selectLayer(layer2)
        c2.selectTool(ToolId.ERASER)
        c2.presetFor(ToolId.ERASER)?.let { c2.updatePreset(ToolId.ERASER, it.copy(size = 20f)) }
        drag(c2, 120f to 30f, 120f to 120f)
        assertTrue(layer2.isVectorLayer)
        assertNotEquals(content.objects.filterIsInstance<VStroke>(), layer2.vector!!.objects.filterIsInstance<VStroke>())
        c2.selectTool(ToolId.LASSO)
        drag(c2, 30f to 140f, 370f to 140f, 370f to 260f, 30f to 260f, 30f to 140f)
        assertTrue("the curve is selected", Smoke.pumpUntil { c2.vectors.selectedIds.isNotEmpty() })
        c2.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c2.currentTool.hasPendingWork })
        val tt = c2.currentTool as TransformTool
        tt.moveBy(0f, -40f)
        tt.commit()
        Smoke.pumpUntil { c2.busyMessage == null }
        val moved = layer2.vector!!.objects.filterIsInstance<VPath>().single()
        assertEquals(160f, moved.subpaths.single().anchors[0].y, 0.5f)
        assertEquals("the thickness stays with the point", 2.5f, moved.subpaths.single().anchors[1].width, 1e-3f)
    }

    @Test
    fun wrappedTextKeepsFollowingItsPictureAfterReopening() {
        val repo = ProjectRepository(app)
        val c = newArtwork(repo)
        val picture = c.doc.layers[1]
        c.editWholeLayer(picture, "Picture") { b -> Canvas(b).drawCircle(200f, 150f, 50f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFF2266CC.toInt() }) }
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        text.startTextAt(200f, 150f)
        text.setText("Lorem ipsum dolor sit amet, consectetur adipiscing elit, sed do eiusmod tempor incididunt ut labore et dolore magna aliqua.")
        text.updateSpec { it.copy(sizePx = 16f, box = it.box.copy(width = 360f)) }
        text.confirmEditor()
        text.setWrapSource(picture)
        assertTrue(text.commitItem())
        val textLayer = c.activeLayer
        val before = TextCodec.decode(textLayer.textData)!!

        val c2 = reopen(repo, c)
        val text2 = c2.doc.layers.single { it.isTextLayer }
        val picture2 = c2.doc.layers.single { it.id == picture.id }
        assertEquals(before, TextCodec.decode(text2.textData))
        val reflows = c2.textWrap.reflowCount
        c2.selectLayer(picture2)
        c2.selectTool(ToolId.TRANSFORM)
        assertTrue(Smoke.pumpUntil { c2.currentTool.hasPendingWork })
        val tt = c2.currentTool as TransformTool
        tt.moveBy(-100f, 0f)
        tt.commit()
        assertEquals("the text re-flowed around the moved picture", reflows + 1, c2.textWrap.reflowCount)
        assertNotEquals(before, TextCodec.decode(text2.textData))
    }

    @Test
    fun toneIsTheFirstColorAdjustmentWithItsSixSlidersAndWorksAsALiveAdjustment() {
        val adjust = FilterRegistry.byCategory().entries.first { it.key == FilterCategory.ADJUST }.value
        val tone = adjust.first()
        assertEquals("Tone", tone.name)
        val sliders = tone.params.filterIsInstance<FilterParam.Slider>().map { it.label }
        assertEquals(listOf("Exposure", "Contrast", "Highlights", "Shadows", "Whites", "Blacks"), sliders)
        assertNotNull("pointwise: usable as an adjustment layer (and with masks)", tone.pixelMapper(tone.defaultValues()))
        assertTrue(tone.isAdjustmentCapable)
    }
}
