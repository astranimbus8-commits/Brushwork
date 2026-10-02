package com.brushwork.paint.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import android.os.SystemClock
import android.view.InputDevice
import android.view.MotionEvent
import android.view.ViewGroup
import androidx.activity.ComponentActivity
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Robolectric
import kotlin.math.abs

/**
 * The vector QA flows' phone: a project made by [ProjectRepository.create] like the New canvas
 * dialog makes it (Background + empty "Layer 1"), opened in a real [EditorController] drawn by a
 * real [CanvasView] in an activity window, driven with MotionEvents (fingers and a stylus), saved
 * and loaded back through the repository. Between user actions [checkpoint] checks what must
 * always hold: no busy overlay or dangling gesture, at most ONE undo step per action (I2), no
 * edit that changed a layer without a step, and every vector layer's pixels equal to a fresh
 * rendering of its objects (I1). [history] keeps the state after every step so undo / redo can be
 * compared with the states the document really had.
 */
internal class VectorQaRig(val w: Int = 600, val h: Int = 400, background: Int? = 0xFFFFFFFF.toInt()) {
    val activity: ComponentActivity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
    val repo = ProjectRepository(activity.applicationContext)
    val projectId: String = runBlocking { repo.create(NewCanvasSpec("QA vectors", w, h, 350f, background)) }

    lateinit var c: EditorController
        private set
    lateinit var view: CanvasView
        private set
    lateinit var touch: QaTouch
        private set

    /** Shows [doc] in a new editor (the previous one is disposed). */
    fun open(doc: Document) {
        if (this::c.isInitialized) c.dispose()
        c = Smoke.controller(activity, doc)
        view = CanvasView(activity, c)
        activity.setContentView(view, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        Smoke.pump(100)
        check(view.width > 0 && view.height > 0) { "canvas view not laid out" }
        touch = QaTouch(view)
        c.tools // the painting tools load their stored presets now: set test presets after this
        c.snapping.enabled = false
        lastSteps = c.undoManager.undoCount
        val s = snapshot()
        last = s
        history.clear()
        history[lastSteps] = s
    }

    /** Saves the document and opens what the repository loads back in a new editor. */
    fun saveAndReopen(): Document {
        runBlocking { repo.save(c.doc, null) }
        val loaded = runBlocking { repo.load(projectId) }
        open(loaded)
        return loaded
    }

    fun close() {
        runCatching { c.dispose() }
        runCatching { runBlocking { repo.delete(projectId) } }
    }

    // ------------------------------------------------------------------ screen <-> document

    fun screen(x: Float, y: Float): Pair<Float, Float> = c.viewTransform.docToScreen(x, y).let { it.x to it.y }

    /** A finger stroke through document points (screen events). */
    fun stroke(vararg pts: Pair<Float, Float>) {
        touch.idle(250) // never part of the previous gesture
        touch.stroke(pts.map { screen(it.first, it.second) }, stylus = false)
    }

    /** A stylus stroke through document points with pressure ramping 0.25 -> 1 -> 0.4. */
    fun stylusStroke(vararg pts: Pair<Float, Float>) {
        touch.idle(250)
        touch.stroke(pts.map { screen(it.first, it.second) }, stylus = true)
    }

    fun tap(x: Float, y: Float) {
        touch.idle(250)
        val (sx, sy) = screen(x, y)
        touch.tap(sx, sy)
    }

    fun twoFingerUndo() {
        touch.idle(250)
        touch.twoFingerTap(screen(w * 0.3f, h * 0.5f), screen(w * 0.6f, h * 0.5f))
    }

    fun threeFingerRedo() {
        touch.idle(250)
        touch.threeFingerTap(screen(w * 0.3f, h * 0.5f), screen(w * 0.45f, h * 0.5f), screen(w * 0.6f, h * 0.5f))
    }

    fun tool(id: ToolId) {
        c.selectTool(id)
        Smoke.pump(40)
    }

    // ------------------------------------------------------------------ pixels and renders

    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** A fresh render of [content] over the whole document, as the cache must be. */
    fun render(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(c.doc.width, c.doc.height)
        val doc = Rect(0, 0, c.doc.width, c.doc.height)
        VectorLayerRenderer.render(Canvas(b), content, doc, tips = TipCache(), document = doc)
        return pixels(b).also { b.recycle() }
    }

    /**
     * [layer]'s cache equals a fresh render of its objects: exactly, or (shifted caches, merged
     * caches) all but [maxOffPermille] per mille of the painted pixels within [tolerance] levels.
     */
    fun assertCacheFresh(where: String, layer: Layer, tolerance: Int = 0, maxOffPermille: Int = 0, near: Boolean = false) {
        val v = layer.vector ?: throw AssertionError("$where: \"${layer.name}\" is not a vector layer")
        val fresh = render(v)
        val cache = pixels(layer.bitmap)
        if (tolerance == 0 && maxOffPermille == 0) {
            if (fresh.contentEquals(cache)) return
        }
        val w = c.doc.width
        val h = c.doc.height
        var off = 0
        var worst = 0
        var at = -1
        var painted = 0
        for (i in fresh.indices) {
            if (fresh[i] != 0 || cache[i] != 0) painted++
            if (fresh[i] == cache[i]) continue
            var d = 0
            for (s in intArrayOf(24, 16, 8, 0)) {
                val cv = (cache[i] ushr s) and 0xFF
                if (!near) {
                    d = maxOf(d, abs(((fresh[i] ushr s) and 0xFF) - cv))
                    continue
                }
                // [near]: an anti-aliased edge a fraction of a pixel away (a cache whose pixels were
                // mirrored or turned) lies between the values of the fresh rendering's 3 x 3
                // neighbourhood; a missing or stale object does not.
                val x = i % w
                val y = i / w
                var lo = 255
                var hi = 0
                for (yy in maxOf(0, y - 1)..minOf(h - 1, y + 1)) for (xx in maxOf(0, x - 1)..minOf(w - 1, x + 1)) {
                    val fv = (fresh[yy * w + xx] ushr s) and 0xFF
                    if (fv < lo) lo = fv
                    if (fv > hi) hi = fv
                }
                d = maxOf(d, lo - cv, cv - hi)
            }
            if (d > tolerance) {
                off++
                if (d > worst) { worst = d; at = i }
            }
        }
        val allowed = painted.toLong() * maxOffPermille / 1000
        if (off > allowed) {
            throw AssertionError(
                "$where: the cache of \"${layer.name}\" is not its objects' rendering: $off pixels off by more than $tolerance " +
                    "(allowed $allowed of $painted painted), worst $worst at ${at % c.doc.width},${at / c.doc.width}",
            )
        }
    }

    // ------------------------------------------------------------------ states and checkpoints

    /** One layer as the user can see and the history must restore it. */
    data class LayerState(val id: Long, val name: String, val vector: VectorContent?, val text: String?, val shape: String?, val bands: List<Int>, val mask: List<Int>? = null)

    data class State(val layers: List<LayerState>) {
        fun layer(id: Long) = layers.first { it.id == id }
    }

    /** Pixel fingerprint of [b]: one hash per 16-row band (a mismatch names the band). */
    private fun bands(b: Bitmap): List<Int> {
        val row = IntArray(b.width * 16)
        val out = ArrayList<Int>()
        var y = 0
        while (y < b.height) {
            val n = minOf(16, b.height - y)
            b.getPixels(row, 0, b.width, 0, y, b.width, n)
            out += if (n == 16) row.contentHashCode() else row.copyOf(b.width * n).contentHashCode()
            y += n
        }
        return out
    }

    fun snapshot(): State = State(c.doc.layers.map { LayerState(it.id, it.name, it.vector, it.textData, it.shapeData, bands(it.bitmap), it.mask?.let { m -> maskBands(m) }) })

    /** [bands] of an ALPHA_8 mask (its alpha bytes as ints). */
    private fun maskBands(m: Bitmap): List<Int> {
        if (m.config != Bitmap.Config.ALPHA_8) return bands(m)
        val bytes = BitmapUtils.alpha8ToBytes(m)
        val out = ArrayList<Int>()
        var y = 0
        while (y < m.height) {
            val n = minOf(16, m.height - y)
            out += bytes.copyOfRange(y * m.width, (y + n) * m.width).contentHashCode()
            y += n
        }
        return out
    }

    fun assertState(where: String, expected: State, actual: State = snapshot()) {
        assertEquals("$where: layers", expected.layers.map { it.id to it.name }, actual.layers.map { it.id to it.name })
        for ((e, a) in expected.layers.zip(actual.layers)) {
            assertEquals("$where: objects of \"${e.name}\"", e.vector, a.vector)
            assertEquals("$where: text of \"${e.name}\"", e.text, a.text)
            assertEquals("$where: shape of \"${e.name}\"", e.shape, a.shape)
            if (e.bands != a.bands) {
                val bad = e.bands.indices.filter { e.bands[it] != a.bands.getOrNull(it) }
                throw AssertionError("$where: pixels of \"${e.name}\" differ in rows ${bad.map { "${it * 16}..${it * 16 + 15}" }.take(6)}")
            }
            if (e.mask != a.mask) {
                val bad = (e.mask ?: emptyList()).indices.filter { e.mask!![it] != a.mask?.getOrNull(it) }
                throw AssertionError("$where: mask of \"${e.name}\" ${if (a.mask == null || e.mask == null) "present / missing" else "differs in rows ${bad.map { "${it * 16}..${it * 16 + 15}" }.take(6)}"}")
            }
        }
    }

    /** The state after k undo steps of the flow (k = undoCount); a later state with the same count replaces it. */
    val history = HashMap<Int, State>()
    private var lastSteps = 0
    private var last: State? = null
    val labels = HashMap<Int, String>()

    /**
     * After every user action: the editor is quiet, the action recorded [steps] undo steps
     * (default: exactly one; null = 0 or 1), nothing changed without a step, every vector layer's
     * cache is its rendering (exactly unless [tolerance] / [maxOffPermille] say otherwise for this
     * point, e.g. after a pixel-shifted move).
     */
    fun checkpoint(where: String, steps: Int? = 1, tolerance: Int = 0, maxOffPermille: Int = 0, near: Boolean = false): State {
        Smoke.step(where)
        Smoke.pump(40)
        assertTrue("$where: still rendering", Smoke.pumpUntil(20_000) { !c.vectors.isRendering && c.busyMessage == null })
        Smoke.pump(20)
        Smoke.assertQuiet(c, where)
        val now = c.undoManager.undoCount
        val grew = now - lastSteps
        if (steps != null) assertEquals("$where: undo steps recorded (now \"${c.undoManager.undoLabel}\")", steps, grew)
        else assertTrue("$where: $grew undo steps recorded", grew in 0..1)
        val s = snapshot()
        if (grew == 0) last?.let { assertState("$where: changed without an undo step", it, s) }
        for (l in c.doc.layers) if (l.isVectorLayer) assertCacheFresh(where, l, tolerance, maxOffPermille, near)
        // Redo was cleared by the new step: states above it are gone.
        if (grew > 0) history.keys.filter { it > now }.forEach { history.remove(it) }
        history[now] = s
        if (grew > 0) labels[now] = c.undoManager.undoLabel ?: ""
        lastSteps = now
        last = s
        return s
    }

    /** Re-reads the reference after an action that is checked by hand (no step bookkeeping). */
    fun resync() {
        lastSteps = c.undoManager.undoCount
        last = snapshot()
        history[lastSteps] = last!!
    }

    /** Undo one step; the document must be exactly as it was after that many steps. */
    fun undoAndCheck(where: String, viaGesture: Boolean = false) {
        val n = c.undoManager.undoCount
        if (viaGesture) twoFingerUndo() else c.undo()
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        assertEquals("$where: undo from $n", n - 1, c.undoManager.undoCount)
        val expected = history[n - 1] ?: throw AssertionError("$where: no state recorded at ${n - 1}")
        assertState("$where: after undo to ${n - 1} (undoing \"${labels[n]}\")", expected)
        lastSteps = n - 1
        last = expected
    }

    fun redoAndCheck(where: String, viaGesture: Boolean = false) {
        val n = c.undoManager.undoCount
        if (viaGesture) threeFingerRedo() else c.redo()
        Smoke.pumpUntil(10_000) { !c.vectors.isRendering }
        assertEquals("$where: redo from $n", n + 1, c.undoManager.undoCount)
        val expected = history[n + 1] ?: throw AssertionError("$where: no state recorded at ${n + 1}")
        assertState("$where: after redo to ${n + 1} (\"${labels[n + 1]}\")", expected)
        lastSteps = n + 1
        last = expected
    }

    fun vectorLayer(name: String): Layer = c.doc.layers.firstOrNull { it.name == name } ?: throw AssertionError("no layer \"$name\" in ${c.doc.layers.map { it.name }}")

    init {
        Smoke.scopeErrors.clear()
        open(runBlocking { repo.load(projectId) })
    }
}

/**
 * MotionEvents for a view like [Smoke.Touch], plus a stylus whose pressure changes along the
 * stroke (fingers report a constant 0.6).
 */
internal class QaTouch(private val view: android.view.View) {
    private var down = 0L

    private fun send(action: Int, pts: List<Triple<Float, Float, Float>>, index: Int, tool: Int) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = i; toolType = tool } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].first; y = pts[i].second; pressure = pts[i].third; size = 0.1f } }
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val src = if (tool == MotionEvent.TOOL_TYPE_STYLUS) InputDevice.SOURCE_STYLUS else InputDevice.SOURCE_TOUCHSCREEN
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), masked, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, src, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    fun idle(ms: Long) = org.robolectric.Shadows.shadowOf(android.os.Looper.getMainLooper()).idleFor(java.time.Duration.ofMillis(ms))

    /** One pointer through [points] (screen px), a sample every 16 ms, 6 samples per segment. */
    fun stroke(points: List<Pair<Float, Float>>, stylus: Boolean) {
        val tool = if (stylus) MotionEvent.TOOL_TYPE_STYLUS else MotionEvent.TOOL_TYPE_FINGER
        val total = (points.size - 1) * 6
        fun pressure(k: Int): Float {
            if (!stylus) return 0.6f
            val t = k / total.toFloat()
            return if (t < 0.5f) 0.25f + 1.5f * t else 1f - 1.2f * (t - 0.5f)
        }
        send(MotionEvent.ACTION_DOWN, listOf(Triple(points[0].first, points[0].second, pressure(0))), 0, tool)
        var k = 0
        for (i in 1 until points.size) {
            val (ax, ay) = points[i - 1]
            val (bx, by) = points[i]
            for (s in 1..6) {
                idle(16)
                k++
                send(MotionEvent.ACTION_MOVE, listOf(Triple(ax + (bx - ax) * s / 6f, ay + (by - ay) * s / 6f, pressure(k))), 0, tool)
            }
        }
        idle(16)
        send(MotionEvent.ACTION_UP, listOf(Triple(points.last().first, points.last().second, pressure(total))), 0, tool)
        idle(50)
    }

    /** Raw events with per-pointer ids and tool types (palm + pen gestures). */
    fun raw(action: Int, pts: List<RawPointer>, index: Int = 0) {
        if (action == MotionEvent.ACTION_DOWN) down = SystemClock.uptimeMillis()
        val props = Array(pts.size) { i -> MotionEvent.PointerProperties().apply { id = pts[i].id; toolType = pts[i].tool } }
        val coords = Array(pts.size) { i -> MotionEvent.PointerCoords().apply { x = pts[i].x; y = pts[i].y; pressure = pts[i].pressure; size = 0.1f } }
        val masked = action or (index shl MotionEvent.ACTION_POINTER_INDEX_SHIFT)
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), masked, pts.size, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    data class RawPointer(val id: Int, val x: Float, val y: Float, val tool: Int = MotionEvent.TOOL_TYPE_FINGER, val pressure: Float = 0.6f)

    fun tap(x: Float, y: Float) {
        send(MotionEvent.ACTION_DOWN, listOf(Triple(x, y, 0.6f)), 0, MotionEvent.TOOL_TYPE_FINGER)
        idle(40)
        send(MotionEvent.ACTION_UP, listOf(Triple(x, y, 0.6f)), 0, MotionEvent.TOOL_TYPE_FINGER)
        idle(50)
    }

    fun twoFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>) {
        val f = MotionEvent.TOOL_TYPE_FINGER
        val pa = Triple(a.first, a.second, 0.6f)
        val pb = Triple(b.first, b.second, 0.6f)
        send(MotionEvent.ACTION_DOWN, listOf(pa), 0, f)
        idle(30)
        send(MotionEvent.ACTION_POINTER_DOWN, listOf(pa, pb), 1, f)
        idle(70)
        send(MotionEvent.ACTION_POINTER_UP, listOf(pa, pb), 0, f)
        idle(20)
        sendSecond(MotionEvent.ACTION_UP, pb)
        idle(50)
    }

    /** The second pointer (id 1) alone. */
    private fun sendSecond(action: Int, p: Triple<Float, Float, Float>) {
        val props = arrayOf(MotionEvent.PointerProperties().apply { id = 1; toolType = MotionEvent.TOOL_TYPE_FINGER })
        val coords = arrayOf(MotionEvent.PointerCoords().apply { x = p.first; y = p.second; pressure = p.third; size = 0.1f })
        val e = MotionEvent.obtain(down, SystemClock.uptimeMillis(), action, 1, props, coords, 0, 0, 1f, 1f, 0, 0, InputDevice.SOURCE_TOUCHSCREEN, 0)
        view.dispatchTouchEvent(e)
        e.recycle()
    }

    fun threeFingerTap(a: Pair<Float, Float>, b: Pair<Float, Float>, d: Pair<Float, Float>) {
        val t = Smoke.Touch(view)
        t.threeFingerTap(a, b, d)
    }

    /** Two fingers from (a0, b0) to (a1, b1), slowly (never a tap). */
    fun pinch(a0: Pair<Float, Float>, b0: Pair<Float, Float>, a1: Pair<Float, Float>, b1: Pair<Float, Float>) {
        Smoke.Touch(view).pinch(a0, b0, a1, b1)
    }
}
