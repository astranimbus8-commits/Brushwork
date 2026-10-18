package com.brushwork.paint.v19.a7b

import android.content.ComponentCallbacks2
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.diagnostics.FreezeWatchdog
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.ColorSelectSettings
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.ui.common.StabilityLabels19
import kotlinx.coroutines.Job
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.shadows.ShadowLog
import kotlin.math.max
import kotlin.random.Random

/**
 * v1.9 A7b step 7 (design 3.2 A7b, 2.2, 5.5): the soak harness, the JVM side of the soak checks
 * Q10 (Magnetic lasso) and Q11 (Color select) that run on the phone. Two artworks of the phone's
 * and of PRINT's (3:4) proportions (scaled to 512 px) go round [CYCLES] times: open (from the files the
 * last round saved), [STROKES] strokes (brush, eraser, on a layer mask), the system's LOW trim
 * every 50 strokes and CRITICAL every 100 (the undo history then drops all but its newest step,
 * and Undo and Redo still work), two filters, a Magnetic lasso round (activation, a 12-anchor
 * outline, an anchor drag, the commit, tool switches), a Color select round (activation, "Reds",
 * fuzziness drags, refined by samples, "Select colors"), the smart guides scanning while a removed
 * layer is recycled by a CRITICAL trim (risk H4), a save and a close.
 *
 * What holds: no crash and no handled report (CrashLog), nothing reaches a scope; every layer
 * reopens as it was saved; no live layer or mask is ever recycled; a closed editor leaves no
 * governor entry behind (its undo history's entry goes with its scope) and the registry does not
 * grow; no call holds the main thread as
 * long as a FREEZE report ([FreezeWatchdog.FREEZE_MS]); the tools' activations leave their work
 * to other threads.
 *
 * Runs in the soak job only (`-PsoakOnly`: nightly and on demand, `.github/workflows/soak.yml`);
 * elsewhere it skips itself.
 */
@Category(Timing::class)
@Suppress("DEPRECATION") // the trim levels are what Android still delivers
@RunWith(RobolectricTestRunner::class)
class SoakHarnessTest {
    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val ids = ArrayList<String>()
    private var savedHeap: GovernorPolicyV19.Heap? = null
    private var savedPolicy: GovernorPolicy? = null

    /** The longest main-thread call of each kind (ms). */
    private val longest = LinkedHashMap<String, Double>()

    /** Each activation's main-thread time (ms), per tool. */
    private val activations = LinkedHashMap<String, MutableList<Double>>()

    /** The H4 rounds whose layer was recycled while the guides' scan of its copy still ran. */
    private var scansInFlight = 0

    @Before
    fun setUp() {
        assumeTrue("the soak job only (-PsoakOnly)", System.getProperty("bw.testSet") == "soak")
        ShadowLog.stream = null
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
        savedHeap = GovernorPolicyV19.heap
        savedPolicy = MemoryGovernor.policy
        MemoryGovernor.policy = GovernorPolicyV19
    }

    @After
    fun tearDown() {
        savedHeap?.let { GovernorPolicyV19.heap = it }
        savedPolicy?.let { MemoryGovernor.policy = it }
        if (savedHeap == null) return
        CrashLog.drain()
        CrashLog.deleteAll(app)
        ids.forEach { runCatching { runBlocking { app.repository.delete(it) } } }
    }

    @Test
    fun phoneAndPrintArtworksGoRoundOpenStrokesFiltersToolsSavesAndTrims() {
        soak("Soak phone", 230, 512)
        soak("Soak print", 384, 512)

        CrashLog.drain()
        assertEquals("no crash and no handled report", emptyList<String>(), CrashLog.reports(app).map { "${it.id}: ${it.summary}" })
        assertTrue("scope errors ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
        val freeze = PerfBudget.ms(FreezeWatchdog.FREEZE_MS.toDouble())
        val report = longest.entries.joinToString { "${it.key} ${"%.0f".format(it.value)} ms" }
        // The nightly job's log keeps the numbers (the JUnit XML's system-out).
        println("Soak, longest main-thread calls: $report")
        println("Soak, activations (ms): " + activations.entries.joinToString { (tool, t) -> "$tool " + t.joinToString("/") { "%.0f".format(it) } })
        println("Soak, H4 rounds with the scan still running at the trim: $scansInFlight of ${2 * CYCLES}")
        for ((kind, ms) in longest) assertTrue("$kind held the main thread ${"%.0f".format(ms)} ms (a FREEZE report at $freeze): $report", ms < freeze)
        // An activation renders off the main thread: the best of the rounds (the first loads classes).
        for ((tool, times) in activations) {
            val best = times.min()
            assertTrue("$tool activation: best $best ms of $times", best <= PerfBudget.ms(ACTIVATION_MS))
        }
    }

    // ------------------------------------------------------------------ one artwork

    private fun soak(name: String, w: Int, h: Int) {
        val pid = runBlocking { app.repository.create(NewCanvasSpec(name, w, h, 300f)) }
        ids += pid
        var saved: List<String>? = null
        var entriesAfterClose = -1
        repeat(CYCLES) { cycle ->
            val where = "$name, round ${cycle + 1}"
            val (session, c, undoEntry) = open(pid, where)
            if (saved != null) assertEquals("$where: reopens as saved", saved, layerHashes(c.doc))
            if (cycle == 0) setUpArtwork(c)
            val rnd = Random(cycle * 7919 + w)
            strokes(c, rnd, where)
            filters(c, where)
            magneticLasso(c, where)
            colorSelect(c, where)
            smartGuidesWhileTrimming(c, rnd, where)
            assertNothingRecycled(c, where)
            saved = save(session, c, where)
            close(session, where)
            // A closed editor holds no cache memory, its undo history's entry went with its scope
            // (collected or not), and the registry does not grow round by round.
            val cacheBytes = MemoryGovernor.entries().filter { it.name in EDITOR_CACHES }.sumOf { it.bytes() }
            assertEquals("$where: memory held by closed editors' caches", 0L, cacheBytes)
            assertTrue("$where: the closed editor's undo entry", MemoryGovernor.entries().none { it === undoEntry })
            val n = MemoryGovernor.entries().size
            if (entriesAfterClose < 0) entriesAfterClose = n
            assertTrue("$where: governor entries $n, after the first round $entriesAfterClose", n <= entriesAfterClose)
        }
    }

    /** The session, its editor and the editor's undo history entry in the governor. */
    private fun open(pid: String, where: String): Triple<EditorSession, EditorController, MemoryGovernor.Entry> {
        app.settings.autosaveSeconds = 600
        val before = MemoryGovernor.entries()
        val session = EditorSession(app, pid)
        assertTrue("$where: opens", Smoke.pumpUntil { session.state !is EditorSession.State.Loading })
        val ready = session.state as? EditorSession.State.Ready ?: throw AssertionError("$where: ${session.state}")
        val c = ready.controller
        c.viewTransform.set(Matrix())
        c.tools
        val undoEntry = MemoryGovernor.entries().single { e -> e.name == GovernorPolicyV19.UNDO_ENTRY && before.none { it === e } }
        assertEquals(GovernorPolicyV19.PRIORITY_UNDO, undoEntry.priority)
        return Triple(session, c, undoEntry)
    }

    /**
     * A "photo" (a gradient, a red disc, a dark box: edges and reds), a layer to paint on and one
     * with a mask. Saved with the first round, so later rounds open them from the files.
     */
    private fun setUpArtwork(c: EditorController) {
        val w = c.doc.width.toFloat()
        val h = c.doc.height.toFloat()
        val photo = c.doc.layers[0]
        photo.name = PHOTO
        val canvas = Canvas(photo.bitmap)
        canvas.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w, h, 0xFFE8E0D0.toInt(), 0xFF9AB0C8.toInt(), Shader.TileMode.CLAMP) })
        canvas.drawRect(BOX_L * w, BOX_T * h, BOX_R * w, BOX_B * h, Paint().apply { color = 0xFF202830.toInt() })
        canvas.drawCircle(0.5f * w, 0.88f * h, 0.08f * h, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = RED })
        photo.markChanged()
        c.invalidateDoc(null)
        assertNotNull(c.addLayer(PAINT))
        val masked = c.addLayer(MASKED)!!
        c.addMask(masked, fromSelection = false)
        assertNotNull(masked.mask)
        c.selectLayer(c.doc.layers.indexOf(c.doc.layers.first { it.name == PAINT }))
    }

    private fun layer(c: EditorController, name: String): Layer = c.doc.layers.first { it.name == name }

    // ------------------------------------------------------------------ strokes and trims

    private fun strokes(c: EditorController, rnd: Random, where: String) {
        val paint = layer(c, PAINT)
        val masked = layer(c, MASKED)
        for (i in 0 until STROKES) {
            val onMask = i % 10 == 3
            val target = if (i % 2 == 0 || onMask) masked else paint
            c.selectLayer(c.doc.layers.indexOf(target))
            target.editingMask = onMask
            val tool = if (i % 10 == 9) ToolId.ERASER else ToolId.BRUSH
            if (c.activeToolId != tool) c.selectTool(tool)
            c.color = if (onMask) GRAYS[rnd.nextInt(GRAYS.size)] else COLORS[rnd.nextInt(COLORS.size)]
            timed("stroke") { stroke(c, rnd) }
            target.editingMask = false
            if (i % 10 == 0) timed("display tiles") { c.tiles.update(c.compositor, null) }
            if ((i + 1) % 50 == 0) {
                timed("LOW trim") { MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_LOW) }
                timed("display tiles") { c.tiles.update(c.compositor, null) }
            }
            if ((i + 1) % 100 == 0) criticalTrim(c, "$where, stroke ${i + 1}")
        }
        assertTrue("$where: strokes landed", Smoke.pumpUntil { c.busyMessage == null && !c.isInteracting })
        Smoke.assertQuiet(c, "$where: strokes")
    }

    private fun stroke(c: EditorController, rnd: Random) {
        val w = c.doc.width
        val h = c.doc.height
        var x = rnd.nextFloat() * w
        var y = rnd.nextFloat() * h
        val dx = (rnd.nextFloat() - 0.5f) * 24f
        val dy = (rnd.nextFloat() - 0.5f) * 24f
        val t0 = rnd.nextLong(1_000_000L)
        c.pointerDown(ToolPoint(x, y, 1f, t0))
        val n = 6 + rnd.nextInt(10)
        for (k in 1..n) {
            x = (x + dx).coerceIn(0f, w - 1f)
            y = (y + dy).coerceIn(0f, h - 1f)
            c.pointerMove(ToolPoint(x, y, 0.4f + 0.6f * rnd.nextFloat(), t0 + 8L * k))
        }
        c.pointerUp(ToolPoint(x, y, 1f, t0 + 8L * (n + 1)))
    }

    /**
     * The system's CRITICAL trim with the memory in use far over 70 %: every cache goes and the
     * undo history keeps only its newest step, says so, and Undo / Redo still work.
     */
    private fun criticalTrim(c: EditorController, where: String) {
        val steps = c.undoManager.undoCount
        val heap = savedHeap!!
        GovernorPolicyV19.heap = FakeHeap(1_000L, 1L shl 50)
        c.message = null
        try {
            timed("CRITICAL trim") { MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL) }
        } finally {
            GovernorPolicyV19.heap = heap
        }
        if (steps > GovernorPolicyV19.KEEP_UNDO_STEPS) {
            assertEquals("$where: the history keeps its newest step", GovernorPolicyV19.KEEP_UNDO_STEPS, c.undoManager.undoCount)
            assertEquals(where, StabilityLabels19.UNDO_TRIMMED, c.message)
        }
        assertNothingRecycled(c, where)
        if (c.canUndo) {
            timed("undo") { c.undo() }
            timed("redo") { c.redo() }
        }
        timed("display tiles") { c.tiles.update(c.compositor, null) }
        assertTrue("$where: Undo and Redo landed", Smoke.pumpUntil { c.busyMessage == null })
    }

    // ------------------------------------------------------------------ filters

    private fun filters(c: EditorController, where: String) {
        val w = c.doc.width.toFloat()
        val h = c.doc.height.toFloat()
        c.selectTool(ToolId.BRUSH)
        // A Gaussian blur inside a selection (it needs its neighbours: the whole-image path, admitted
        // first), then Invert on a whole layer (a local filter: applied in bands).
        c.selectLayer(c.doc.layers.indexOf(layer(c, PAINT)))
        c.setSelection(Selection.fromPath(Path().apply { addRect(0.1f * w, 0.1f * h, 0.7f * w, 0.6f * h, Path.Direction.CW) }, c.doc.width, c.doc.height))
        applyFilter(c, "blur.gaussian", "$where: blur")
        c.setSelection(null)
        c.selectLayer(c.doc.layers.indexOf(layer(c, MASKED)))
        applyFilter(c, "adjust.invert", "$where: invert")
        Smoke.assertQuiet(c, "$where: filters")
    }

    private fun applyFilter(c: EditorController, id: String, where: String) {
        val steps = c.undoManager.undoCount
        timed("filter start") { c.startFilter(FilterRegistry.byId(id)!!) }
        val s = c.filterSession ?: throw AssertionError("$where: no session (${c.message})")
        s.debounceMs = 0
        assertTrue("$where: preview", Smoke.pumpUntil { s.hasPreview && !s.isRendering })
        timed("filter apply") { s.apply() }
        assertTrue("$where: applied", Smoke.pumpUntil { c.filterSession == null && c.busyMessage == null })
        assertEquals("$where: one step", steps + 1, c.undoManager.undoCount)
    }

    // ------------------------------------------------------------------ Q10: Magnetic lasso

    private fun magneticLasso(c: EditorController, where: String) {
        val w = c.doc.width.toFloat()
        val h = c.doc.height.toFloat()
        val tool = c.tools.getValue(ToolId.MAGNETIC_LASSO) as MagneticLassoTool
        c.selectLayer(c.doc.layers.indexOf(layer(c, PHOTO)))
        activation("Magnetic lasso") { c.selectTool(ToolId.MAGNETIC_LASSO) }
        assertTrue("$where: the edge map", Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        // 12 anchors around the dark box, clockwise from its top left corner.
        val xs = listOf(0f, 1f / 3, 2f / 3, 1f, 1f, 1f, 1f, 2f / 3, 1f / 3, 0f, 0f, 0f)
        val ys = listOf(0f, 0f, 0f, 0f, 1f / 3, 2f / 3, 1f, 1f, 1f, 1f, 2f / 3, 1f / 3)
        val ax = xs.map { (BOX_L + (BOX_R - BOX_L) * it) * w }
        val ay = ys.map { (BOX_T + (BOX_B - BOX_T) * it) * h }
        for (i in 0 until 12) {
            timed("Magnetic lasso tap") { tap(c, ax[i], ay[i]) }
            assertTrue("$where: anchor $i", Smoke.pumpUntil { tool.idle })
        }
        assertEquals("$where: 12 anchors", 12, tool.anchorCount)
        // An anchor dragged a little and let go (it snaps back onto the edge).
        c.pointerDown(ToolPoint(ax[4], ay[4]))
        for (k in 1..6) timed("Magnetic lasso drag") { c.pointerMove(ToolPoint(ax[4] + 2f * k, ay[4] + k)) }
        c.pointerUp(ToolPoint(ax[4] + 12f, ay[4] + 6f))
        assertTrue("$where: the drag traced", Smoke.pumpUntil { tool.idle })
        assertEquals(12, tool.anchorCount)
        val steps = c.undoManager.undoCount
        timed("Magnetic lasso commit") { tool.commit() }
        assertTrue("$where: the outline closed", Smoke.pumpUntil { tool.idle && tool.anchorCount == 0 && c.busyMessage == null && c.selection != null })
        assertEquals("$where: ONE selection step", steps + 1, c.undoManager.undoCount)
        val bytes = c.selection!!.toBytes()
        val inside = ((BOX_T + BOX_B) / 2 * h).toInt() * c.doc.width + ((BOX_L + BOX_R) / 2 * w).toInt()
        assertTrue("$where: the box is selected", bytes[inside].toInt() != 0)
        assertTrue("$where: the corner of the canvas is not", bytes[0].toInt() == 0)
        // Tool switches, and the map is built again on the next activation.
        c.selectTool(ToolId.LASSO)
        activation("Magnetic lasso") { c.selectTool(ToolId.MAGNETIC_LASSO) }
        assertTrue("$where: the edge map again", Smoke.pumpUntil { tool.edgeMapReady && tool.idle })
        c.selectTool(ToolId.BRUSH)
        c.setSelection(null)
        Smoke.assertQuiet(c, "$where: Magnetic lasso")
    }

    private fun tap(c: EditorController, x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y))
        c.pointerUp(ToolPoint(x, y))
    }

    // ------------------------------------------------------------------ Q11: Color select

    private fun colorSelect(c: EditorController, where: String) {
        val w = c.doc.width
        val h = c.doc.height
        val tool = c.tools.getValue(ToolId.COLOR_SELECT) as ColorSelectTool
        c.selectLayer(c.doc.layers.indexOf(layer(c, PHOTO)))
        activation("Color select") { c.selectTool(ToolId.COLOR_SELECT) }
        assertTrue("$where: the proxy", Smoke.pumpUntil { tool.proxyReady && tool.idle })
        timed("Color select preset") { tool.updateSettings(tool.settings.copy(preset = ColorPreset.REDS)) }
        assertTrue("$where: Reds", Smoke.pumpUntil { tool.idle && tool.preview != null })
        // Fuzziness dragged back and forth.
        for (f in listOf(10, 25, 50, 80, 120, 90, 60, 40)) timed("Color select fuzziness") { tool.updateSettings(tool.settings.copy(fuzziness = f)) }
        assertTrue("$where: the preview follows", Smoke.pumpUntil { tool.idle && tool.preview != null })
        // Refined: the sampled colors of the disc instead.
        timed("Color select refine") {
            tool.updateSettings(tool.settings.copy(preset = ColorPreset.SAMPLED))
            tool.addSample(RED)
            tool.addSample(0xFFB01818.toInt())
        }
        assertTrue("$where: refined", Smoke.pumpUntil { tool.idle && tool.preview != null })
        val steps = c.undoManager.undoCount
        timed("Color select commit") { tool.selectColors() }
        assertTrue("$where: selected", Smoke.pumpUntil { tool.idle && c.busyMessage == null && c.selection != null })
        assertEquals("$where: ONE selection step", steps + 1, c.undoManager.undoCount)
        val bytes = c.selection!!.toBytes()
        assertTrue("$where: the red disc", bytes[(0.88f * h).toInt() * w + w / 2].toInt() != 0)
        assertTrue("$where: not the box", bytes[((BOX_T + BOX_B) / 2 * h).toInt() * w + ((BOX_L + BOX_R) / 2 * w).toInt()].toInt() == 0)
        tool.clearSamples()
        tool.updateSettings(ColorSelectSettings())
        c.selectTool(ToolId.BRUSH)
        c.setSelection(null)
        Smoke.assertQuiet(c, "$where: Color select")
    }

    // ------------------------------------------------------------------ H4: smart guides and recycling

    /**
     * A layer drawn on, scanned by the smart guides (in the background), deleted, and recycled by
     * a CRITICAL trim that drops its delete step, while the scan may still run: nothing crashes,
     * and the guides end. The layer goes with a folder: a structural step with folders
     * (`LayerTreeAction`) recycles the layers it removed when it is dropped; v1.6's
     * `RemoveLayerAction` (no folder in the artwork) leaves them to the collector.
     *
     * A diagonal across the layer makes its content too large for its lines to be found on the
     * spot, so they are scanned in the background; only this layer is asked for, and one turn of
     * the main thread lets the worker copy it and start the scan before the trim recycles it.
     * Whether the scan is still running then depends on the threads: `LayerBoundsCacheCopyTest`
     * holds it running for certain.
     */
    private fun smartGuidesWhileTrimming(c: EditorController, rnd: Random, where: String) {
        c.selectTool(ToolId.BRUSH)
        val temp = c.addLayer("Soak temp") ?: throw AssertionError("$where: no room for a layer (${c.message})")
        diagonal(c)
        repeat(2) { stroke(c, rnd) }
        val folder = c.putInNewFolder(temp) ?: throw AssertionError("$where: no folder (${c.message})")
        c.snapping.prepare(exclude = c.doc.layers.filter { it !== temp })
        assertTrue("$where: the guides scan the layer", c.snapping.isBusy)
        shadowOf(Looper.getMainLooper()).idle()
        // Still busy: the copy's scan has not come back to the main thread yet.
        if (c.snapping.isBusy) scansInFlight++
        c.deleteFolder(folder, keepChildren = false)
        assertTrue("$where: the folder and its layer went", c.doc.layers.none { it === temp || it === folder })
        c.selectLayer(c.doc.layers.indexOf(layer(c, PAINT)))
        repeat(2) { stroke(c, rnd) }
        criticalTrim(c, "$where: smart guides")
        assertTrue("$where: the removed layer was recycled", temp.bitmap.isRecycled)
        assertTrue("$where: the guides' scans ended", Smoke.pumpUntil { !c.snapping.isBusy })
        c.snapping.prepare()
        assertTrue(Smoke.pumpUntil { !c.snapping.isBusy })
        Smoke.assertQuiet(c, "$where: smart guides")
    }

    /** One stroke from near the top left corner to near the bottom right one. */
    private fun diagonal(c: EditorController) {
        val w = c.doc.width
        val h = c.doc.height
        fun at(f: Float, k: Int) = ToolPoint(f * w, f * h, 1f, 8L * k)
        c.pointerDown(at(0.1f, 0))
        for (k in 1..20) c.pointerMove(at(0.1f + 0.04f * k, k))
        c.pointerUp(at(0.9f, 21))
    }

    // ------------------------------------------------------------------ save, close, checks

    /** Saves (as the autosave does, on the app scope) and returns what the files must hold. */
    private fun save(session: EditorSession, c: EditorController, where: String): List<String> {
        val job = session.saveNow()
        assertTrue("$where: saved", Smoke.pumpUntil(60_000) { job.isCompleted })
        // (A failed save is a handled report: the test's end finds none.)
        CrashLog.drain()
        assertEquals("$where: reports", emptyList<String>(), CrashLog.reports(app).map { "${it.id}: ${it.summary}" })
        return layerHashes(c.doc)
    }

    /** Closes as the editor's back arrow does; returns once the session's scope has completed. */
    private fun close(session: EditorSession, where: String) {
        var closed = false
        session.close { closed = true }
        assertTrue("$where: closed", Smoke.pumpUntil(60_000) { closed })
        // The cancelled scope completes once its children (the autosave loop) have ended.
        val job = session.scope.coroutineContext[Job]!!
        assertTrue("$where: the editor's scope ended", Smoke.pumpUntil(60_000) { job.isCompleted })
    }

    private fun assertNothingRecycled(c: EditorController, where: String) {
        for (l in c.doc.layers) {
            if (l.isFolder) continue
            assertFalse("$where: ${l.name}'s bitmap recycled", l.bitmap.isRecycled)
            assertFalse("$where: ${l.name}'s mask recycled", l.mask?.isRecycled == true)
        }
    }

    private fun layerHashes(doc: Document): List<String> = doc.layers.map { l ->
        "${l.name}:${hashOf(l.bitmap)}:${l.mask?.let { hashOf(it) }}"
    }

    private fun hashOf(b: Bitmap): Int = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.contentHashCode()

    private fun <T> timed(kind: String, block: () -> T): T {
        val t = PerfBudget.timed(block)
        longest[kind] = max(longest[kind] ?: 0.0, t.ms)
        return t.value
    }

    private fun activation(tool: String, block: () -> Unit) {
        val t = PerfBudget.timed(block)
        activations.getOrPut(tool) { ArrayList() } += t.ms
        longest["$tool activation"] = max(longest["$tool activation"] ?: 0.0, t.ms)
    }

    private companion object {
        /** Rounds per artwork (open ... close). */
        const val CYCLES = 3

        /** Strokes per round. */
        const val STROKES = 200

        /**
         * An activation's main-thread time, desktop: Q11's "no frame over 32 ms at activation" (the
         * edge map and the proxy render off the main thread), for both tools.
         */
        const val ACTIVATION_MS = 32.0

        /** A6's editor caches' governor entries (EditorController, LiveAdjust, StrokeBelowCache). */
        val EDITOR_CACHES = setOf("display tiles", "live proxies", "below-caches")

        const val PHOTO = "Soak photo"
        const val PAINT = "Soak paint"
        const val MASKED = "Soak masked"

        /** The dark box (fractions of the canvas). */
        const val BOX_L = 0.15f
        const val BOX_R = 0.85f
        const val BOX_T = 0.2f
        const val BOX_B = 0.6f

        val RED = 0xFFD02020.toInt()
        val COLORS = intArrayOf(0xFF2060C0.toInt(), 0xFF30A050.toInt(), 0xFFE0A020.toInt(), 0x80602080.toInt(), 0xFF101010.toInt())
        val GRAYS = intArrayOf(0xFF000000.toInt(), 0xFF808080.toInt(), 0xFFFFFFFF.toInt())
    }
}
