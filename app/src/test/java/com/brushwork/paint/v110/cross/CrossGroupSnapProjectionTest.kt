package com.brushwork.paint.v110.cross

import android.app.Activity
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import android.view.View
import android.widget.FrameLayout
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.v110.a8.A8Kit
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.ParameterizedRobolectricTestRunner
import org.robolectric.Robolectric
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.10 Gate 2 cross test (R8 x A9): a group of Path points dragged near a guide while the
 * compositor's projections run. The document (400 x 300) is a deep stack: 8 translucent fills over
 * the whole canvas (their bounds are the canvas, so they add no snap target), a "Guide" layer
 * whose box (120..360 x 20..100) gives the guide lines, and the vector layer on top; at FULL every
 * layer but the active one is parked (the F1 hook). Points 0 and 2 of a pending path are selected
 * and point 2 is dragged 57 px right, ending 3 px from the guide x = 360 (nothing else within
 * reach): the group snaps as one, both points moving 60 px, point 1 staying.
 *
 * Editor A draws a frame of a real [CanvasView] before the press, after every move and after the
 * lift: at FULL its display builds and uses the below projection (9 units under the split), at
 * CLASSIC none. Every one of its frames shows exactly a fresh render of its compositor. Editor B,
 * over the same document, makes the same drag with its frames composited by its display tiles
 * with no projection installed (only the canvas view installs one). The result is the same: the
 * snapped points, the path ✓ makes (one step each; the stroke's random seed aside), the vector
 * layer's pixels and the display. The snapped move is one in-tool step and ✓ one step, each
 * undone and redone exactly. CLASSIC and FULL.
 */
@RunWith(ParameterizedRobolectricTestRunner::class)
class CrossGroupSnapProjectionTest(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val controllers = ArrayList<EditorController>()

    @After
    fun tearDown() {
        for (c in controllers) {
            c.dispose()
            A8Kit.drain()
            for (l in c.doc.layers) l.recycleBitmaps()
        }
        scope.cancel()
    }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    /** Runs the main looper (and lets background work finish) until [done] or ~5 s. */
    private fun idleUntil(done: () -> Boolean): Boolean {
        repeat(1000) {
            idle()
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    /** The deep stack: an empty layer, [FILLS] translucent fills, the guide's box, the vector layer (active). */
    private fun document(): Document {
        val doc = Document("t", "t", W, H)
        doc.layers += Layer(doc.newLayerId(), "Layer 1", BitmapUtils.createLayerBitmap(W, H))
        for (k in 0 until FILLS) {
            val b = BitmapUtils.createLayerBitmap(W, H)
            Canvas(b).drawColor(Color.argb(40, 30 * k, 255 - 25 * k, 120))
            doc.layers += Layer(doc.newLayerId(), "Fill ${k + 1}", b)
        }
        val g = BitmapUtils.createLayerBitmap(W, H)
        Canvas(g).drawRect(GUIDE.left.toFloat(), GUIDE.top.toFloat(), GUIDE.right.toFloat(), GUIDE.bottom.toFloat(), Paint().apply { color = 0xFF3060C0.toInt() })
        doc.layers += Layer(doc.newLayerId(), "Guide", g)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(W, H)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = doc.layers.lastIndex
        return doc
    }

    /** An editor over [document] (identity view, snapping on, the guide's bounds known); at FULL the inactive layers parked. */
    private fun editor(): EditorController {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val c = EditorController(app, document(), scope, settings).also {
            controllers += it
            it.color = 0xFF203080.toInt()
            it.viewTransform.set(Matrix())
            it.tools
        }
        if (level == EngineLevel.FULL) {
            for (l in c.doc.layers) {
                if (l === c.doc.activeLayer) continue
                if (l.residency != ResidencyState.PARKED) ChaosResidency().park(l)
                assertEquals("${l.name} parked", ResidencyState.PARKED, l.residency)
            }
        }
        c.snapping.enabled = true
        val guide = c.doc.layers.first { it.name == "Guide" }
        c.snapping.prepare(listOf(c.doc.activeLayer))
        assertTrue("the guide's bounds are known", idleUntil { !c.snapping.isBusy && c.snapping.bounds(guide) != null })
        assertEquals(GUIDE, c.snapping.bounds(guide))
        return c
    }

    /** An attached canvas view of [c] (800 x 600: the whole document at the identity view). */
    private fun attach(c: EditorController): CanvasView {
        val activity = Robolectric.buildActivity(Activity::class.java).setup().get()
        val view = CanvasView(activity, c)
        activity.setContentView(FrameLayout(activity).apply { addView(view, FrameLayout.LayoutParams(800, 600)) })
        view.measure(View.MeasureSpec.makeMeasureSpec(800, View.MeasureSpec.EXACTLY), View.MeasureSpec.makeMeasureSpec(600, View.MeasureSpec.EXACTLY))
        view.layout(0, 0, 800, 600)
        assertTrue(view.isAttachedToWindow)
        return view
    }

    /** What display tiles [t] show, over the whole document. */
    private fun shown(t: DisplayTiles): IntArray {
        val b = BitmapUtils.createLayerBitmap(W, H)
        val cv = Canvas(b)
        for (i in 0 until t.tileCount) t.drawTile(cv, i, smooth = false)
        return IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H); b.recycle() }
    }

    /** A fresh render of [c]'s compositor with no projection (reading on main what is not resident). */
    private fun fresh(c: EditorController): IntArray {
        val t = DisplayTiles(W, H)
        t.waitingEnabled = false
        t.update(c.compositor, Rect(0, 0, W, H))
        return shown(t).also { t.freeBitmaps() }
    }

    /** A frame of [view], then its display checked against a fresh render (the projection is exact). */
    private fun frameA(c: EditorController, view: CanvasView, what: String) {
        val b = Bitmap.createBitmap(800, 600, Bitmap.Config.ARGB_8888)
        try {
            view.draw(Canvas(b))
        } finally {
            b.recycle()
        }
        assertArrayEquals("A, $what: the frame shows exactly the composite", fresh(c), shown(c.tiles))
    }

    /** A frame of [c] through its display tiles, with no projection installed. */
    private fun frameB(c: EditorController) {
        c.tiles.update(c.compositor, Rect(0, 0, W, H))
    }

    /** The Path tool with the pending path [POINTS], points 0 and 2 selected. */
    private fun path(c: EditorController): CurveTool {
        c.selectTool(ToolId.PATH)
        val tool = c.tools.getValue(ToolId.PATH) as CurveTool
        for (p in POINTS) assertTrue("adds $p", tool.addAnchor(p))
        tool.deselect()
        tool.selectPoints(PointSelection.of(3, 0, 2))
        assertEquals(POINTS, points(tool))
        return tool
    }

    private fun points(tool: CurveTool): List<Vec2> = (0 until 3).map { tool.pointAt(it) }

    /** Point 2 dragged by [MOVE] (past the touch slop first), a frame before the press, after every move and after the lift. */
    private fun drag(c: EditorController, frame: (String) -> Unit) {
        val from = POINTS[2]
        val pts = listOf(from, from + Vec2(10f, 0f), from + MOVE * 0.5f, from + MOVE)
        c.pointerDown(ToolPoint(pts[0].x, pts[0].y))
        frame("press")
        for (i in 1 until pts.size) {
            c.pointerMove(ToolPoint(pts[i].x, pts[i].y))
            frame("move $i")
        }
        c.pointerUp(ToolPoint(pts.last().x, pts.last().y))
        idle()
        frame("lift")
    }

    /** [o] with its stroke's random seed (new for every path) set to 0. */
    private fun seedless(o: VObject): VObject = (o as VPath).let { it.copy(stroke = it.stroke?.copy(seed = 0L)) }

    private fun pixels(c: EditorController): IntArray = A8Kit.pixelsOf(c.doc.activeLayer).first

    @Test
    fun aGroupSnapsToTheGuideUnderProjectionsAsWithoutThem() {
        // A: frames of a canvas view (the projection at FULL).
        val a = editor()
        val view = attach(a)
        frameA(a, view, "first")
        val toolA = path(a)
        val stepsA = a.undoManager.undoCount
        drag(a) { frameA(a, view, it) }
        val movedA = points(toolA)
        assertEquals("snapped as a group: both moved 60 px, point 1 stays", listOf(POINTS[0] + SNAPPED, POINTS[1], POINTS[2] + SNAPPED), movedA)
        val projection = a.liveAdjust.belowProjection
        if (level == EngineLevel.FULL) {
            assertTrue("the projection was built (${projection.builds})", projection.builds > 0)
            assertTrue("and used (${projection.hits})", projection.hits > 0)
        } else {
            assertEquals("no projection at CLASSIC", 0L, projection.builds)
        }

        // The snapped move is one in-tool step.
        a.undo()
        assertEquals("undo: the points back exactly", POINTS, points(toolA))
        a.redo()
        assertEquals("redo: the snapped points exactly", movedA, points(toolA))
        assertEquals("no document step yet", stepsA, a.undoManager.undoCount)

        // B: the same drag, frames with no projection.
        val b = editor()
        val toolB = path(b)
        drag(b) { frameB(b) }
        assertEquals("no projection in B", 0L, b.liveAdjust.belowProjection.builds)
        assertEquals("the same snapped points", movedA, points(toolB))

        // ✓ in both: one step each, the same path, pixels and display.
        toolA.commit()
        toolB.commit()
        assertTrue(idleUntil { !a.vectors.isRendering && !b.vectors.isRendering })
        frameA(a, view, "after ✓")
        frameB(b)
        assertEquals("A: ✓ is one step", stepsA + 1, a.undoManager.undoCount)
        assertEquals("B: ✓ is one step", 1, b.undoManager.undoCount)
        val pathA = a.doc.activeLayer.vector!!
        val pathB = b.doc.activeLayer.vector!!
        assertEquals("one path each", 1, pathA.objects.size)
        assertEquals("the same path", pathB.objects.map { seedless(it) }, pathA.objects.map { seedless(it) })
        val pixelsA = pixels(a)
        assertArrayEquals("the same layer pixels", pixels(b), pixelsA)
        assertArrayEquals("the same display", shown(b.tiles), shown(a.tiles))

        // ✓ undone and redone in A: data, pixels and display exact.
        a.undo()
        assertTrue(idleUntil { !a.vectors.isRendering })
        frameA(a, view, "undo")
        assertEquals("undo: one step", stepsA, a.undoManager.undoCount)
        assertEquals("undo: no path", emptyList<VObject>(), a.doc.activeLayer.vector!!.objects)
        assertNotEquals("undo: the pixels changed back", pixelsA.toList(), pixels(a).toList())
        assertTrue("undo: the layer is empty", pixels(a).all { it == 0 })
        a.redo()
        assertTrue(idleUntil { !a.vectors.isRendering })
        frameA(a, view, "redo")
        assertEquals("redo: one step", stepsA + 1, a.undoManager.undoCount)
        assertEquals("redo: the path", pathA.objects, a.doc.activeLayer.vector!!.objects)
        assertArrayEquals("redo: the pixels", pixelsA, pixels(a))
        assertArrayEquals("redo: the display", shown(b.tiles), shown(a.tiles))
    }

    companion object {
        private const val W = 400
        private const val H = 300
        private const val FILLS = 8

        /** The guide's box: lines x 120, 240, 360 and y 20, 60, 100. */
        private val GUIDE = Rect(120, 20, 360, 100)

        /** Point 1 (unselected) is a target at x 250, y 260; points 0 and 2 move. */
        private val POINTS = listOf(Vec2(40f, 200f), Vec2(250f, 260f), Vec2(300f, 220f))

        /**
         * Point 2 ends at x 357, 3 px from the guide x 360. Point 0 ends at x 97 (23 px from x 120),
         * the moving box's centre at 227 (13 px from x 240, 23 from point 1's 250); on y nothing is
         * within reach (200, 210, 220 against 150, 260, 300 and the guide's 20, 60, 100).
         */
        private val MOVE = Vec2(57f, 0f)

        /** The group's move once snapped: 60 px. */
        private val SNAPPED = Vec2(60f, 0f)

        @JvmStatic
        @ParameterizedRobolectricTestRunner.Parameters(name = "{0}")
        fun levels(): List<Array<Any>> = listOf(arrayOf(EngineLevel.CLASSIC), arrayOf(EngineLevel.FULL))
    }
}
