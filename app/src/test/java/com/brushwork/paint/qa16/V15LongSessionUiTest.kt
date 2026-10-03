package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.Canvas
import android.view.MotionEvent
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.TransparencyDisplay
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.docLength
import com.brushwork.paint.tools.vector.spline.CurveToolTestSupport
import com.brushwork.paint.tools.vector.spline.SplineBezier
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VectorOps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowChoreographer
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.6 final QA through the real app (MainActivity, the user's phone size): a project v1.5.0 wrote
 * opens from the gallery; a tap with the Text tool reopens a v1.5 text and a tap with the Curve
 * tool a v1.5 curve, ✕ leaves both as they were, and going back to the gallery saves nothing
 * (the v1.5 files stay v1.5's). Then a long mixed session on it: Path points pending while the
 * app goes to the background and comes back, the screen rotates and the activity is recreated
 * (✓ / ✕ follow the pending work, never stuck), tools switched in the middle of a stroke and of a
 * frame drag, undo / redo across tools, the v1.6 settings across recreation, and back to the
 * gallery: what was saved opens again with every v1.5 layer and the new curve (I9).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa16.v15sessionsandbox"])
open class V15LongSessionUiTest {
    /** The screenshot written for the lead (null: none). */
    protected open val shotName: String? = "v15-long-session.png"


    private val failures = mutableListOf<Throwable>()
    private lateinit var ctl: org.robolectric.android.controller.ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private val id = V15Fixtures.PLAIN
    private val projectDir: File get() = File(app.filesDir, "projects/$id")

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
            runCatching { if (app.editorSession != null) backToGallery() }
        }
    }

    // ------------------------------------------------------------------ helpers

    private val c: EditorController get() = (app.editorSession!!.state as EditorSession.State.Ready).controller

    private fun canvas(): CanvasView = Smoke.find(activity.window.decorView, CanvasView::class.java) ?: throw AssertionError("no canvas")

    private fun screen(x: Float, y: Float): Pair<Float, Float> {
        val loc = IntArray(2)
        canvas().getLocationInWindow(loc)
        val p = c.viewTransform.docToScreen(x, y)
        return (p.x + loc[0]) to (p.y + loc[1])
    }

    private fun touch() = Smoke.Touch(activity.window.decorView)

    private fun tap(x: Float, y: Float) {
        val t = touch()
        t.idle(300)
        val (sx, sy) = screen(x, y)
        t.tap(sx, sy)
        settle(4)
    }

    private fun open() {
        assertTrue("the gallery lists the v1.5 project", Smoke.pumpUntil { settle(1); has("V15 plain", exact = true) })
        click("V15 plain", exact = true)
        assertTrue("the editor opened", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null &&
                Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0 > 0
        })
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); c.busyMessage == null && !c.vectors.isRendering })
        assertEquals(id, c.doc.id)
    }

    private fun backToGallery() {
        click("Back to gallery", settleAfter = false)
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
    }

    /** The clickable cell labelled [label] (its node, not clipped by the menu). */
    private fun cell(label: String): SemanticsNode? {
        var n = SmokeUi.find(label, exact = true)?.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        return n
    }

    /** Picks [label] in the tool menu, scrolling the menu to it as a finger would. */
    private fun tool(label: String) {
        click("Tools (current:")
        fun inside() = cell(label)?.let { n -> n.boundsInWindow.height >= n.size.height - 1f && n.boundsInWindow.width >= n.size.width - 1f } == true
        if (!inside()) {
            val node = SmokeUi.find(label, exact = true)?.node ?: throw AssertionError("\"$label\" is not in the tool menu")
            var n: SemanticsNode? = node
            while (n != null && n.config.getOrNull(SemanticsActions.ScrollBy) == null) n = n.parent
            val scroll = requireNotNull(n?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "\"$label\" is clipped and the menu does not scroll" }
            var k = 0
            while (!inside() && k++ < 30) {
                scroll.invoke(0f, 60f * activity.resources.displayMetrics.density)
                settle(2)
            }
        }
        click(label, exact = true)
        assertTrue("\"$label\" is the tool", has("Tools (current: $label)", exact = true))
    }

    /** ✓ and ✕ show exactly while the tool has pending work (never stuck, never missing). */
    private fun assertPendingButtons(where: String) {
        settle(2)
        val label = c.currentTool.id.label.lowercase()
        val pending = c.currentTool.hasPendingWork
        assertEquals("$where: ✓ while pending ($pending)", pending, has("Apply $label edit", exact = true))
        assertEquals("$where: ✕ while pending ($pending)", pending, has("Discard $label edit", exact = true))
        if (pending) for (b in listOf("Apply $label edit", "Discard $label edit")) {
            // Whole, on screen and not covered by its own clipping (a finger can reach it).
            val n = cell(b) ?: throw AssertionError("$where: \"$b\" is not clickable")
            val r = n.boundsInWindow
            val root = activity.window.decorView
            assertTrue("$where: \"$b\" whole ($r vs ${n.size})", r.width >= n.size.width - 1f && r.height >= n.size.height - 1f)
            assertTrue("$where: \"$b\" on screen ($r in ${root.width}x${root.height})", r.left >= 0f && r.top >= 0f && r.right <= root.width && r.bottom <= root.height)
        }
    }

    private fun backgroundAndBack() {
        ctl.pause().stop()
        settle(2)
        ctl.start().resume()
        settle()
    }

    private fun rotateAndBack(check: (String) -> Unit) {
        val cfg = activity.resources.configuration
        val (w, h) = cfg.screenWidthDp to cfg.screenHeightDp
        RuntimeEnvironment.setQualifiers("w${h}dp-h${w}dp-land-xxhdpi")
        ctl.configurationChange()
        settle()
        assertTrue("landscape canvas", canvas().width > canvas().height)
        check("landscape")
        RuntimeEnvironment.setQualifiers("w${w}dp-h${h}dp-port-xxhdpi")
        ctl.configurationChange()
        settle()
        check("portrait again")
    }

    private fun recreate() {
        val before = canvas()
        // Robolectric's recreate() needs frames to run by themselves (the new window's view root).
        ShadowChoreographer.setPaused(false)
        ctl.recreate()
        ShadowChoreographer.setPaused(true)
        settle()
        assertTrue("a new canvas view", canvas() !== before)
    }

    private val vectorLayer get() = c.doc.layers.single { it.isVectorLayer }

    /**
     * [n] points of the document where no object of the vector layer is (taps there start a new
     * path), left to right in a zigzag (each a row away from the one before), so the Path curve
     * through them really bends.
     */
    private fun emptySpots(n: Int): List<Pair<Float, Float>> {
        val layer = vectorLayer
        val tol = c.docLength(24f)
        val free = ArrayList<Pair<Float, Float>>()
        for (y in listOf(60f, 120f, 180f, 240f, 300f, 360f)) for (x in listOf(60f, 140f, 220f, 300f, 380f, 460f, 540f)) {
            if (c.vectors.hitTest(layer, Vec2(x, y), tol) == null) free += x to y
        }
        for (start in free) {
            val out = arrayListOf(start)
            while (out.size < n) {
                val (lx, ly) = out.last()
                out += free.firstOrNull { (x, y) -> x >= lx + 80f && kotlin.math.abs(y - ly) >= 60f && (out.size < 2 || (y - ly) * (ly - out[out.size - 2].second) < 0f) } ?: break
            }
            if (out.size == n) return out
        }
        throw AssertionError("no room for a $n-point zigzag: $free")
    }

    private fun files(): Map<String, ByteArray> = projectDir.listFiles()!!.filter { it.name != "thumb.png" }.associate { it.name to it.readBytes() }

    // ------------------------------------------------------------------ the test

    @Test
    fun aV15ProjectThroughALongMixedSession() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 60_000)
        app = RuntimeEnvironment.getApplication() as BrushworkApp
        V15Fixtures.install(app, id)
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()
        section("a v1.5 text and curve reopen, ✕ keeps them, nothing is saved") { reopenAndLeave() }
        section("a long mixed session") { longSession() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun reopenAndLeave() {
        val v15 = files()
        open()
        assertEquals(emptyList<String>(), c.doc.loadWarnings)
        assertEquals(14, c.doc.layers.size)
        val steps = c.undoManager.undoCount
        // The Text tool: a tap on the letters of a v1.5 text reopens it; ✕ leaves it as it was.
        tool("Text")
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        val target = c.doc.layers.filter { it.isTextLayer && it.visible }.firstNotNullOf { l ->
            val item = TextCodec.decode(l.textData)!!
            text.textLayerAt(Vec2(item.cx, item.cy))?.takeIf { it === l }?.let { l to item }
        }
        val (textLayer, item) = target
        val textData = textLayer.textData
        val textPixels = textLayer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        tap(item.cx, item.cy)
        assertSame("the v1.5 text \"${textLayer.name}\" reopened", textLayer, text.editingLayer)
        assertEquals("with its item", item, text.item)
        assertPendingButtons("text reopened")
        click("Discard text edit", exact = true)
        assertEquals(null, text.editingLayer)
        assertEquals("✕: the same text data", textData, textLayer.textData)
        assertTrue("✕: the same pixels", textPixels.sameAs(textLayer.bitmap))
        assertPendingButtons("text discarded")
        // Reopened again and ✓ without a change: nothing is recorded or rewritten (I8).
        tap(item.cx, item.cy)
        assertSame(textLayer, text.editingLayer)
        click("Apply text edit", exact = true)
        assertEquals(null, text.editingLayer)
        assertEquals("unchanged ✓: the same text data", textData, textLayer.textData)
        assertTrue("unchanged ✓: the same pixels", textPixels.sameAs(textLayer.bitmap))
        assertEquals("unchanged ✓: no step", steps, c.undoManager.undoCount)
        assertPendingButtons("text applied unchanged")
        // The Curve tool: a tap on a v1.5 curve of the vector layer reopens it; ✕ leaves it.
        c.selectLayer(vectorLayer)
        settle()
        tool("Curve")
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        val content = vectorLayer.vector!!
        val p = content.objects.filterIsInstance<VPath>().last { it.isCurveEditable && !it.polyline && it.spline == null && it.fill == null && it.subpaths[0].anchors.size >= 2 }
        val (px, py) = CurveToolTestSupport.onLine(p).let { it.x to it.y }
        tap(px, py)
        assertTrue("the v1.5 curve ${p.id} reopened", Smoke.pumpUntil { settle(1); curve.isReopened })
        assertPendingButtons("curve reopened")
        click("Discard curve edit", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); !curve.isReopened && !c.vectors.isRendering })
        assertEquals("✕: the same objects", content.objects, vectorLayer.vector!!.objects)
        assertEquals("nothing is history", steps, c.undoManager.undoCount)
        assertPendingButtons("curve discarded")
        tap(px, py)
        assertTrue(Smoke.pumpUntil { settle(1); curve.isReopened })
        click("Apply curve edit", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); !curve.isReopened && !c.vectors.isRendering })
        assertEquals("unchanged ✓: the same objects", content.objects, vectorLayer.vector!!.objects)
        assertEquals("unchanged ✓: no step", steps, c.undoManager.undoCount)
        assertPendingButtons("curve applied unchanged")
        Smoke.assertQuiet(c, "reopen and ✕")
        backToGallery()
        val after = files()
        assertEquals("the same files", v15.keys.sorted(), after.keys.sorted())
        for ((name, bytes) in v15) assertArrayEquals("$name: nothing was saved", bytes, after.getValue(name))
    }

    private fun longSession() {
        open()
        val v15Layers = c.doc.layers.filter { !it.isVectorLayer }.map { it.id to it.dataSnapshot() }
        val v15Objects = vectorLayer.vector!!.objects
        // The v1.6 settings (app-wide) set in this session, kept across recreation.
        c.increments.update { it.copy(enabled = true, angleDeg = 30f) }
        c.settings.transparencyDisplay = TransparencyDisplay.DARK_CHECKER
        c.settings.fastAdjustPreview = false

        // Path points pending through background, rotation and recreation.
        c.selectLayer(vectorLayer)
        settle()
        tool("Path")
        val path = c.tools.getValue(ToolId.PATH) as CurveTool
        val spots = emptySpots(4)
        for ((x, y) in spots.take(3)) tap(x, y)
        assertTrue("three points pending", path.hasPendingWork)
        assertEquals(3, path.spline?.points?.size)
        assertPendingButtons("points pending")
        backgroundAndBack()
        assertTrue("the points are still pending after the background", path.hasPendingWork)
        assertPendingButtons("back from the background")
        rotateAndBack { where -> assertPendingButtons(where) }
        recreate()
        assertSame("recreation keeps the tool", path, c.currentTool)
        assertPendingButtons("recreated")
        // A fourth point on the recreated canvas, then ✓: one step, a Path curve that passes I9.
        tap(spots[3].first, spots[3].second)
        assertEquals(4, path.spline?.points?.size)
        val steps = c.undoManager.undoCount
        click("Apply path edit", exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); !c.vectors.isRendering })
        assertEquals("✓ is one step", steps + 1, c.undoManager.undoCount)
        val made = vectorLayer.vector!!.objects.filterIsInstance<VPath>().last()
        assertNotNull("the new curve keeps its points", made.spline)
        assertTrue("I9", SplineBezier.matches(made))
        val bends = VectorOps.bounds(made)
        assertTrue("the curve bends through its zigzag points: $bends", bends.height() >= 50f && bends.width() >= 200f)
        println("[qa16] Path curve: spots $spots, bounds $bends")
        assertPendingButtons("applied")

        // A brush stroke whose tool is switched in the middle (another finger on the tool menu).
        tool("Brush")
        val raster = c.doc.layers.first { it.name == "Raster" }
        c.selectLayer(raster)
        settle()
        c.color = 0xFF2266AA.toInt()
        val rasterBefore = raster.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val stepsBeforeStroke = c.undoManager.undoCount
        val t = touch()
        val (ax, ay) = screen(80f, 380f)
        val (bx, by) = screen(260f, 380f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, ax, ay))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay)) }
        tool("Eraser")
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, ax + (bx - ax) * k / 8f, ay)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, bx, by))
        settle()
        assertFalse("no gesture left open", c.isInteracting)
        // The stroke is either kept as one step or dropped: never pixels without a step (I2).
        val kept = c.undoManager.undoCount - stepsBeforeStroke
        println("[qa16] brush stroke with the tool switched mid-stroke: ${if (kept == 1) "kept as one step" else "dropped ($kept steps)"}")
        assertTrue("at most one step ($kept)", kept in 0..1)
        if (kept == 0) assertTrue("dropped: the raster layer untouched", rasterBefore.sameAs(raster.bitmap))
        assertPendingButtons("tool switched mid-stroke")
        Smoke.assertQuiet(c, "tool switched mid-stroke")
        // Then a whole stroke with each (more steps of different tools for undo / redo).
        t.idle(300)
        t.stroke(screen(150f, 360f), screen(150f, 410f))
        settle()
        tool("Brush")
        t.idle(300)
        t.stroke(screen(80f, 400f), screen(300f, 400f))
        settle()
        assertEquals("an eraser and a brush stroke", stepsBeforeStroke + kept + 2, c.undoManager.undoCount)

        // A frame drag in the Text frames tool while the app goes to the background.
        tool("Text frames")
        val frames = c.tools.getValue(ToolId.TEXT_FRAMES) as TextFrameTool
        val layersBefore = c.doc.layers.toList()
        val (fx, fy) = screen(320f, 300f)
        val (gx, gy) = screen(520f, 400f)
        t.idle(300)
        t.send(MotionEvent.ACTION_DOWN, Smoke.P(0, fx, fy))
        for (k in 1..4) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, fx + (gx - fx) * k / 8f, fy + (gy - fy) * k / 8f)) }
        backgroundAndBack()
        for (k in 5..8) { t.idle(16); t.send(MotionEvent.ACTION_MOVE, Smoke.P(0, fx + (gx - fx) * k / 8f, fy + (gy - fy) * k / 8f)) }
        t.send(MotionEvent.ACTION_UP, Smoke.P(0, gx, gy))
        settle()
        println("[qa16] frame drag across the background: story editor open = ${frames.story.isOpen}")
        if (frames.story.isOpen) {
            // The drag finished: its story editor; Cancel makes no frame.
            click("Cancel", exact = true)
            assertFalse(frames.story.isOpen)
        }
        assertEquals("no frame was made", layersBefore, c.doc.layers.toList())
        assertFalse(c.isInteracting)
        assertPendingButtons("frame drag across the background")
        Smoke.assertQuiet(c, "frame drag across the background")

        // Undo and redo across the tools (the top bar's buttons).
        val total = c.undoManager.undoCount
        val afterAll = c.doc.layers.map { it.id to it.dataSnapshot() }
        val rasterAfter = raster.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        repeat(total) { click("Undo", exact = true) }
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null && !c.vectors.isRendering })
        assertEquals("all undone", 0, c.undoManager.undoCount)
        assertEquals("the v1.5 objects", v15Objects, vectorLayer.vector!!.objects)
        assertEquals("the v1.5 layers", v15Layers, c.doc.layers.filter { !it.isVectorLayer }.map { it.id to it.dataSnapshot() })
        repeat(total) { click("Redo", exact = true) }
        assertTrue(Smoke.pumpUntil { settle(1); c.busyMessage == null && !c.vectors.isRendering })
        assertEquals("all redone", afterAll, c.doc.layers.map { it.id to it.dataSnapshot() })
        assertTrue("the raster layer as it was", rasterAfter.sameAs(raster.bitmap))
        Smoke.assertQuiet(c, "undo / redo across tools")

        // The settings survive another recreation and a background.
        recreate()
        backgroundAndBack()
        assertTrue("increments kept", c.increments.enabled && c.increments.state.angleDeg == 30f)
        assertEquals(TransparencyDisplay.DARK_CHECKER, c.settings.transparencyDisplay)
        assertFalse(c.settings.fastAdjustPreview)
        shotName?.let { shot(it) }

        // Back to the gallery: saved; the project opens again with every v1.5 layer and the curve.
        backToGallery()
        val saved = runBlocking { app.repository.load(id) }
        assertEquals(emptyList<String>(), saved.loadWarnings)
        val savedVector = saved.layers.single { it.isVectorLayer }.vector!!.objects
        assertEquals("the v1.5 objects and the new curve", v15Objects + made, savedVector)
        assertTrue("the curve still passes I9 when loaded", SplineBezier.matches(savedVector.last() as VPath))
        for ((lid, data) in v15Layers) {
            if (lid == raster.id) continue
            val item = data.text?.let { TextCodec.decode(it) }
            val loaded = saved.layers.single { it.id == lid }
            if (item != null && item.wrap.isOn && item.wrap.sourceLayerId == raster.id) {
                // A text flowing around the painted picture follows it (v1.5): same text, new outline.
                val now = TextCodec.decode(loaded.textData)!!
                assertEquals("layer $lid: the same words", item.text, now.text)
                assertTrue("layer $lid: still around the picture", now.wrap.isOn && now.wrap.sourceLayerId == raster.id)
                continue
            }
            assertEquals("layer $lid's data", data, loaded.dataSnapshot())
        }
    }

    /** The screen as it is, for the lead (build/qa16-shots, outside the sources). */
    private fun shot(name: String) {
        runCatching {
            val root = activity.window.decorView
            val bmp = Bitmap.createBitmap(root.width, root.height, Bitmap.Config.ARGB_8888)
            root.draw(Canvas(bmp))
            val dir = File("build/qa16-shots").apply { mkdirs() }
            File(dir, name).outputStream().use { bmp.compress(Bitmap.CompressFormat.PNG, 100, it) }
        }
    }
}
