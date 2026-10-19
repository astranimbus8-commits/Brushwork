package com.brushwork.paint.v19.cross

import android.content.ComponentCallbacks2
import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Rect
import android.os.Looper
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.diagnostics.CrashLog
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterTiling
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.ui.common.FilterLabels19
import com.brushwork.paint.ui.common.StabilityLabels19
import com.brushwork.paint.v19.a7b.FakeHeap
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import java.nio.ByteBuffer
import java.security.MessageDigest

/**
 * v1.9 Gate 2 cross test M1 (A2 x A7b, design 3.4): a ten-step Tone session (A2's R8 steps) on a
 * PRINT-shaped artwork while the system asks for memory (A7b's MemoryGovernor at CRITICAL).
 *
 * The artwork has PRINT's three layers and three steps before the session. A CRITICAL trim (the
 * memory in use far over 70 %) comes before the session, after its third and seventh steps,
 * before the apply and while the apply runs in the background: the display tiles go each time
 * and the history keeps its newest step ("Memory is low: older undo steps were cleared", once).
 * The session goes on regardless: each of its ten steps and the ten undos and redos show exactly
 * the values and the preview of their step. The apply first meets a heap that cannot hold its
 * bands: "Not enough memory for Tone", nothing changes and the session stays open with its steps;
 * then it fits and runs in bands (FilterTiling), as one document step that undoes and redoes to
 * the exact bytes. The committed layer is bitwise the one the same session commits with no
 * pressure at all (NORMAL). Nothing crashes or reaches a scope, and every entry the editor
 * registered with the governor is gone once it closed.
 *
 * Robolectric's 1.5 GB test heap holds PRINT itself (3000 x 4000), but slowly: CI runs a scaled
 * stand-in of 384 x 512 (PRINT's 3:4) whose bands are 32 rows, so its apply crosses the same 16
 * bands PRINT's 4000 rows do in bands of 256 rows. PRINT itself is a T606-size probe, local runs
 * only (design 5.5).
 */
@Suppress("DEPRECATION") // the trim levels are what Android still delivers
@RunWith(RobolectricTestRunner::class)
class CrossMemoryFilterSessionTest {
    private val app: Context get() = ApplicationProvider.getApplicationContext()
    private val tone = FilterRegistry.byId("adjust.tone")!!
    private lateinit var savedPolicy: GovernorPolicy
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    /** A heap the apply's bands fit in (2 GB, 256 MB in use): only the trims see the memory as short. */
    private val roomy = FakeHeap(2L shl 30, 256L shl 20)

    /** The ten steps: one control each, every value on its slider's step and new for that control. */
    private val steps = listOf(
        "exposure" to 0.5f, "contrast" to 20f, "highlights" to -30f, "shadows" to 25f, "exposure" to 0.8f,
        "whites" to 15f, "blacks" to -10f, "contrast" to -15f, "shadows" to 40f, "exposure" to -0.25f,
    )

    /** The CRITICAL trims come after these steps (and before the session, the apply, and during it). */
    private val trimAfter = setOf(3, 7)

    private class Outcome(val committed: String, val bands: Long)

    @Before
    fun setUp() {
        savedPolicy = MemoryGovernor.policy
        savedHeap = GovernorPolicyV19.heap
        MemoryGovernor.policy = GovernorPolicyV19
        GovernorPolicyV19.heap = roomy
        CrashLog.deleteAll(app)
        CrashLog.resetRepeats()
        Smoke.scopeErrors.clear()
    }

    @After
    fun tearDown() {
        MemoryGovernor.policy = savedPolicy
        GovernorPolicyV19.heap = savedHeap
        FilterTiling.enabled = true
        FilterTiling.bandPixels = FilterTiling.BAND_PIXELS
        CrashLog.drain()
        CrashLog.deleteAll(app)
    }

    @Test
    fun aTenStepSessionAtCriticalOnAScaledPrintCommitsWhatNormalCommits() {
        // 32-row bands: 512 rows cross 16, as PRINT's 4000 rows do in bands of 256.
        FilterTiling.bandPixels = 384 * 32
        session(384, 512)
    }

    @Test
    fun aTenStepSessionAtCriticalOnPrintCommitsWhatNormalCommits() {
        assumeTrue("a T606-size probe: local runs only", System.getenv("CI") == null)
        assertEquals("PRINT's bands are 256 rows", 256, FilterTiling.bandRows(3000))
        session(3000, 4000)
    }

    private fun session(w: Int, h: Int) {
        val critical = run(w, h, critical = true)
        val normal = run(w, h, critical = false)
        assertEquals("the same 16 bands", normal.bands, critical.bands)
        assertEquals("16 bands", 16L, critical.bands)
        assertEquals("CRITICAL commits NORMAL's bytes", normal.committed, critical.committed)
        CrashLog.drain()
        assertEquals("no crash and no handled report", emptyList<String>(), CrashLog.reports(app).map { "${it.id}: ${it.summary}" })
        assertTrue("scope errors ${Smoke.scopeErrors}", Smoke.scopeErrors.isEmpty())
    }

    /** One artwork, its session and its apply; [critical] delivers the trims. */
    private fun run(w: Int, h: Int, critical: Boolean): Outcome {
        val where = if (critical) "CRITICAL" else "NORMAL"
        val before = MemoryGovernor.entries()
        val scope = Smoke.newScope()
        val doc = Document("cross-m1", "Cross memory", w, h)
        val paint = Layer(doc.newLayerId(), "Paint", BitmapUtils.createLayerBitmap(w, h))
        paint.bitmap.setPixels(Fx.paintPixels(w, h), 0, w, 0, 0, w, h)
        doc.layers += paint
        doc.layers += Layer(doc.newLayerId(), "Line art", BitmapUtils.createLayerBitmap(w, h)).also { Fx.drawLineArt(it.bitmap) }
        doc.layers += Layer(doc.newLayerId(), "Layer 3", BitmapUtils.createLayerBitmap(w, h))
        doc.activeLayerIndex = 0
        val c = Smoke.controller(app, doc, scope)
        val outcome = try {
            sessionOn(c, paint, critical, where)
        } finally {
            c.filterSession?.cancel()
            c.dispose()
            scope.cancel()
            shadowOf(Looper.getMainLooper()).idle()
        }
        assertTrue("$where: the editor's scope ended", scope.coroutineContext[Job]!!.isCompleted)
        val left = MemoryGovernor.entries().filter { e -> before.none { it === e } && e.name in EDITOR_ENTRIES }
        assertEquals("$where: the editor's governor entries went with it", emptyList<String>(), left.map { it.name })
        return outcome
    }

    private fun sessionOn(c: EditorController, paint: Layer, critical: Boolean, where: String): Outcome {
        val w = c.doc.width
        val h = c.doc.height
        // Three document steps before the session (the trim drops the two oldest).
        for (k in 0 until 3) {
            val r = Rect(w * k / 6, h * k / 6, w * (k + 2) / 6, h * (k + 2) / 6)
            val rec = c.beginEdit(paint)
            rec.touch(r)
            Canvas(paint.bitmap).drawRect(r, Paint().apply { color = 0xFF305080.toInt() + k * 0x203010 })
            assertTrue(c.commitEdit(rec, "Paint ${k + 1}"))
        }
        assertEquals(3, c.undoManager.undoCount)
        c.tiles.update(c.compositor, null)
        if (critical) {
            trim(c, "$where: before the session")
            assertEquals("$where: the history keeps its newest step", GovernorPolicyV19.KEEP_UNDO_STEPS, c.undoManager.undoCount)
        }
        val docSteps = c.undoManager.undoCount

        c.startFilter(tone)
        val s = c.filterSession ?: throw AssertionError("$where: no session (${c.message})")
        s.debounceMs = 0
        Fx.awaitPreview(s, "$where: opening")
        val values = ArrayList<FilterValues>()
        val previews = ArrayList<String>()
        values += s.values
        previews += preview(c, "$where: opening")
        for ((i, step) in steps.withIndex()) {
            s.update(step.first, step.second)
            s.commitStep(step.first)
            Fx.awaitPreview(s, "$where: step ${i + 1}")
            values += s.values
            previews += preview(c, "$where: step ${i + 1}")
            if (critical && (i + 1) in trimAfter) trim(c, "$where: after step ${i + 1}")
        }
        assertEquals("$where: ten steps", 10, s.history.depth)
        assertEquals("$where: ten different previews", 11, previews.toSet().size)
        Fx.assertNoErrorMessage(c, where)

        // Back to the opening state, one step at a time; the last one says so.
        c.message = null
        for (k in 9 downTo 0) {
            c.undo()
            assertSame(s, c, "$where: undo to $k")
            Fx.awaitPreview(s, "$where: undo to $k")
            assertValues(values[k], s.values, "$where: undo to $k")
            assertEquals("$where: undo to $k shows its preview", previews[k], preview(c, "$where: undo to $k"))
        }
        assertEquals(FilterLabels19.SESSION_AT_START, c.message)
        assertFalse(s.history.canUndo)
        c.message = null
        for (k in 1..10) {
            c.redo()
            assertSame(s, c, "$where: redo to $k")
            Fx.awaitPreview(s, "$where: redo to $k")
            assertValues(values[k], s.values, "$where: redo to $k")
            assertEquals("$where: redo to $k shows its preview", previews[k], preview(c, "$where: redo to $k"))
        }
        assertFalse(s.history.canRedo)
        assertEquals("$where: the session steps are not document steps", docSteps, c.undoManager.undoCount)
        assertNotNull("$where: Tone is local: its apply runs in bands", FilterTiling.mapperFor(tone, s.values))

        val original = digest(paint.bitmap)
        if (critical) {
            trim(c, "$where: before the apply")
            // The bands do not fit: the designed refusal, nothing changes, the session stays open.
            GovernorPolicyV19.heap = FakeHeap(1_000L, 950L)
            try {
                s.apply()
            } finally {
                GovernorPolicyV19.heap = roomy
            }
            assertEquals(String.format(StabilityLabels19.NO_MEMORY_FOR, tone.name), c.message)
            assertFalse(s.isApplying)
            assertNull(c.busyMessage)
            assertSame(s, c, "$where: refused")
            assertEquals("$where: no step", docSteps, c.undoManager.undoCount)
            assertEquals("$where: the layer as it was", original, digest(paint.bitmap))
            assertTrue("$where: the session keeps its steps", s.history.canUndo)
            assertValues(values[10], s.values, "$where: refused")
            c.message = null
        }
        val bands = FilterTiling.bandsRun
        s.apply()
        if (critical) {
            assertTrue("$where: the apply runs in the background", s.isApplying)
            trim(c, "$where: during the apply")
        }
        Fx.waitUntil("$where: the apply") { !s.isApplying && c.busyMessage == null }
        Fx.assertNoErrorMessage(c, "$where: apply")
        assertTrue("$where: the session closed", s.isClosed)
        assertNull(c.filterSession)
        assertEquals("$where: one document step", docSteps + 1, c.undoManager.undoCount)
        assertEquals("$where: the step is the filter's", tone.name, c.undoManager.undoLabel)
        val committed = digest(paint.bitmap)
        assertTrue("$where: Tone changed the layer", committed != original)

        c.undo()
        assertEquals("$where: undo gives the layer back", original, digest(paint.bitmap))
        c.redo()
        assertEquals("$where: redo gives the result back", committed, digest(paint.bitmap))
        c.tiles.update(c.compositor, null)
        Smoke.assertQuiet(c, where)
        return Outcome(committed, FilterTiling.bandsRun - bands)
    }

    /**
     * The system's CRITICAL trim with the memory in use far over 70 % (the soak's way): the display
     * tiles go, the history keeps its newest step and says so when any went. The canvas then
     * draws again.
     */
    private fun trim(c: EditorController, where: String) {
        val steps = c.undoManager.undoCount
        c.message = null
        GovernorPolicyV19.heap = FakeHeap(1_000L, 1L shl 50)
        try {
            MemoryGovernor.onTrimMemory(ComponentCallbacks2.TRIM_MEMORY_RUNNING_CRITICAL)
        } finally {
            GovernorPolicyV19.heap = roomy
        }
        assertEquals("$where: the display tiles went", 0L, c.tiles.heldBytes)
        if (steps > GovernorPolicyV19.KEEP_UNDO_STEPS) assertEquals(where, StabilityLabels19.UNDO_TRIMMED, c.message)
        else assertNull("$where: nothing to clear, nothing said", c.message)
        c.message = null
        for (l in c.doc.layers) assertFalse("$where: ${l.name}'s bitmap recycled", l.bitmap.isRecycled)
        c.tiles.update(c.compositor, null)
        assertTrue("$where: the canvas draws again", c.tiles.heldBytes > 0L)
    }

    private fun assertSame(s: FilterSession, c: EditorController, where: String) {
        assertTrue("$where: the session is open", !s.isClosed && c.filterSession === s)
    }

    private fun assertValues(expected: FilterValues, actual: FilterValues, where: String) {
        for (p in tone.params) {
            assertTrue("$where: ${p.key} is ${actual.raw(p.key)}, expected ${expected.raw(p.key)}", Fx.sameValue(expected.raw(p.key), actual.raw(p.key)))
        }
    }

    /** The preview as the canvas draws it (SHA-256 of its pixels). */
    private fun preview(c: EditorController, where: String): String {
        val px = Fx.overrideContent(c) ?: throw AssertionError("$where: no preview")
        val bytes = ByteBuffer.allocate(px.size * 4).also { it.asIntBuffer().put(px) }.array()
        return sha(bytes)
    }

    /** The bitmap's stored (premultiplied) bytes, as SHA-256. */
    private fun digest(b: Bitmap): String = sha(ByteBuffer.allocate(b.byteCount).also { b.copyPixelsToBuffer(it) }.array())

    private fun sha(bytes: ByteArray): String = MessageDigest.getInstance("SHA-256").digest(bytes).joinToString("") { "%02x".format(it) }

    private companion object {
        /** What an editor registers with the governor (display tiles, live proxies, below-caches, undo history). */
        val EDITOR_ENTRIES = setOf("display tiles", "live proxies", "below-caches", GovernorPolicyV19.UNDO_ENTRY)
    }
}
