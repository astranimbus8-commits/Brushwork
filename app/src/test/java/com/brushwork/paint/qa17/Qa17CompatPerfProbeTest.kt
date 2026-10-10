package com.brushwork.paint.qa17

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.RadialGradient
import android.graphics.Rect
import android.graphics.RectF
import android.graphics.Shader
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.UndoManager
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.tools.ToolId
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.lang.management.ManagementFactory
import java.lang.management.MemoryType
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.7 QA (compat cluster), the §6.3 rows no other test measured, at the phone's sizes on the
 * JVM (Robolectric NATIVE, software Skia). The numbers are JVM numbers: each probe prints them,
 * the phone's own are device checks. Locally only, never on CI (T606-size documents).
 *
 * - "Save selection / load selection, full 4000 × 5000 mask: ≤ 300 ms / ≤ 200 ms, on a worker":
 *   the worker's work ([SavedSelection.of], [SavedSelection.toSelection], and the loader's
 *   [SavedSelection.isIntact]) for an anti-aliased lasso over the whole document and for a
 *   feathered (radial) mask, the worst case for Deflate; then the controller's "Save selection"
 *   and "Load selection" end to end. Guards at twice the phone budget.
 * - "Peak heap, 4000 × 5000, 2 layers, 3 folders, 8 saved selections, an open array session:
 *   below maxHeap × 0.85": that session built through the controller with the phone's budgets
 *   (largeHeap 512 MB: history maxHeap / 4, array patches maxHeap / 8), then a save and a saved
 *   selection loaded. Bitmap pixels are native memory here as on Android 13, so it reports the
 *   Java heap (live after a GC at each milestone, and the sampled peak) and the bitmap bytes the
 *   session holds, and guards their sum against 0.85 × 512 MB.
 */
@RunWith(RobolectricTestRunner::class)
class Qa17CompatPerfProbeTest {
    @Before
    fun notOnCi() = assumeTrue("T606-size probes run locally only", System.getenv("CI") == null)

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 4000
    private val h = 5000
    private val mb = 1024.0 * 1024.0

    /** The T606's `maxMemory()` with `android:largeHeap` (4 GB phones: 512 MB; DEVICE to confirm). */
    private val phoneMaxHeap = 512L shl 20

    private fun median(xs: List<Double>): Double = xs.sorted()[xs.size / 2]

    private inline fun timeMs(block: () -> Unit): Double {
        val t0 = System.nanoTime()
        block()
        return (System.nanoTime() - t0) / 1e6
    }

    /** [runs] timed runs after one warm-up run; prints them and returns the median. */
    private inline fun measure(what: String, runs: Int, block: () -> Unit): Double {
        block()
        val times = List(runs) { timeMs(block) }
        val med = median(times)
        println("[qa17 compat perf] $what: median ${"%.1f".format(med)} ms (${times.joinToString { "%.1f".format(it) }})")
        return med
    }

    /** An anti-aliased 40-point star over the whole document with an elliptic hole. */
    private fun lasso(): Selection {
        val p = Path().apply {
            fillType = Path.FillType.EVEN_ODD
            for (i in 0 until 40) {
                val a = i * Math.PI / 20
                val r = if (i % 2 == 0) 1.0 else 0.72
                val x = (w / 2 + r * w / 2 * cos(a)).toFloat().coerceIn(0f, w.toFloat())
                val y = (h / 2 + r * h / 2 * sin(a)).toFloat().coerceIn(0f, h.toFloat())
                if (i == 0) moveTo(x, y) else lineTo(x, y)
            }
            close()
            addOval(RectF(w * 0.3f, h * 0.3f, w * 0.7f, h * 0.7f), Path.Direction.CW)
        }
        return Selection.fromPath(p, w, h, antiAlias = true)
    }

    /** A feathered mask: coverage falls off radially over the whole document (every row differs). */
    private fun feathered(): Selection {
        val mask = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val paint = Paint().apply { shader = RadialGradient(w / 2f, h / 2f, h * 0.75f, 0xFF000000.toInt(), 0x08000000, Shader.TileMode.CLAMP) }
        Canvas(mask).drawRect(0f, 0f, w.toFloat(), h.toFloat(), paint)
        return Selection.wrap(mask, Rect(0, 0, w, h))
    }

    @Test
    fun saveAndLoadAFull4000x5000SavedSelection() {
        for ((name, make) in listOf("lasso" to ::lasso, "feathered" to ::feathered)) {
            val sel = make()
            assertTrue("$name: covers the document", sel.bounds.width() >= w - 2 && sel.bounds.height() >= h - 2)
            var saved: SavedSelection? = null
            val save = measure("save selection, $name 4000 × 5000 (SavedSelection.of)", 5) { saved = SavedSelection.of(1L, "Selection 1", sel, 1L) }
            val s = saved!!
            println("[qa17 compat perf] $name: ${"%.2f".format(s.bytes / mb)} MB packed of ${"%.1f".format(w.toLong() * h / mb)} MB")
            var back: Selection? = null
            val load = measure("load selection, $name (toSelection)", 5) { back?.mask?.recycle(); back = s.toSelection(w, h) }
            val check = measure("open a project: $name file check (isIntact)", 3) { assertTrue(s.isIntact()) }
            assertEquals("$name: the same bounds", sel.bounds, back!!.bounds)
            assertTrue("$name: the same coverage", sel.mask.sameAs(back!!.mask))
            back!!.mask.recycle()
            sel.mask.recycle()
            assertTrue("$name: save $save ms (phone budget 300)", save <= PerfBudget.ms(600.0))
            assertTrue("$name: load $load ms (phone budget 200)", load <= PerfBudget.ms(400.0))
            assertTrue("$name: file check $check ms", check <= PerfBudget.ms(400.0))
        }

        // Through the controller: "Save selection" until it lands, "Load selection" until it is the selection.
        val c = Smoke.controller(app, Smoke.document(w, h, layers = 2, whiteBottom = true))
        try {
            c.viewTransform.set(Matrix())
            c.setSelection(lasso(), recordUndo = false)
            val t0 = System.nanoTime()
            assertTrue(c.saveSelection())
            assertTrue("landed", Smoke.pumpUntil(30_000) { c.pendingSavedSelections.isEmpty() && c.doc.savedSelections.size == 1 })
            val saveMs = (System.nanoTime() - t0) / 1e6
            c.setSelection(null, recordUndo = false)
            val t1 = System.nanoTime()
            c.loadSavedSelection(c.doc.savedSelections.single().id, SelectionMode.REPLACE)
            assertTrue("loaded", Smoke.pumpUntil(30_000) { c.selection != null })
            val loadMs = (System.nanoTime() - t1) / 1e6
            println("[qa17 compat perf] controller: \"Save selection\" landed in ${"%.0f".format(saveMs)} ms, \"Load selection\" in ${"%.0f".format(loadMs)} ms (main looper pumped in 20 ms steps)")
        } finally {
            c.dispose()
        }
    }

    // ------------------------------------------------------------------ peak heap

    private val heap = ManagementFactory.getMemoryMXBean()

    private fun liveHeap(): Long {
        repeat(2) { System.gc(); Thread.sleep(30) }
        return heap.heapMemoryUsage.used
    }

    /** Samples the Java heap every 2 ms on its own thread (garbage included: an upper bound). */
    private class Sampler : Thread("qa17-heap-sampler") {
        @Volatile var running = true
        @Volatile var peak = 0L
        private val bean = ManagementFactory.getMemoryMXBean()
        init { isDaemon = true }
        override fun run() {
            while (running) {
                peak = maxOf(peak, bean.heapMemoryUsage.used)
                try { sleep(2) } catch (e: InterruptedException) { return }
            }
        }
    }

    private fun undoBytes(c: EditorController): Long {
        val m = UndoManager::class.java.getDeclaredMethod("totalBytes").apply { isAccessible = true }
        return m.invoke(c.undoManager) as Long
    }

    /** Bitmap bytes the session holds: layers, array sources, the selection and the history (shared masks counted twice). */
    private fun bitmapBytes(c: EditorController): Long {
        val seen = java.util.IdentityHashMap<Bitmap, Unit>()
        var n = 0L
        for (l in c.doc.layers) {
            if (!l.isFolder && seen.put(l.bitmap, Unit) == null) n += l.bitmap.allocationByteCount
            l.mask?.let { if (seen.put(it, Unit) == null) n += it.allocationByteCount }
            n += l.array?.pixels?.bytes ?: 0L
        }
        c.selection?.mask?.let { if (seen.put(it, Unit) == null) n += it.allocationByteCount }
        return n + undoBytes(c)
    }

    @Test
    fun peakHeapWithFoldersSavedSelectionsAndAnOpenArraySession() {
        val pools = ManagementFactory.getMemoryPoolMXBeans().filter { it.type == MemoryType.HEAP }
        val base = liveHeap()
        val probe = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        val withLayer = liveHeap()
        println("[qa17 compat perf] heap: a 4000 × 5000 layer moves the Java heap by ${"%.1f".format((withLayer - base) / mb)} MB (its pixels are native: ${"%.1f".format(probe.allocationByteCount / mb)} MB)")
        probe.recycle()
        pools.forEach { it.resetPeakUsage() }
        val sampler = Sampler().also { it.start() }
        val milestones = ArrayList<String>()
        var worst = 0L

        val c = Smoke.controller(app, Smoke.document(w, h, layers = 2, whiteBottom = true))
        try {
            c.viewTransform.set(Matrix())
            c.snapping.enabled = false
            // The phone's budgets, not the JVM's 1.5 GB: history maxHeap / 4, array patches maxHeap / 8.
            UndoManager::class.java.getDeclaredField("maxBytes").apply { isAccessible = true }.setLong(c.undoManager, phoneMaxHeap / 4)
            c.arrayRenders.patchBudget = { phoneMaxHeap / 8 }

            fun milestone(what: String) {
                Qa17CompatArtwork.settle(c)
                val live = liveHeap() - base
                val bitmaps = bitmapBytes(c)
                worst = maxOf(worst, live + bitmaps)
                milestones += "$what: Java heap +${"%.1f".format(live / mb)} MB, bitmaps ${"%.1f".format(bitmaps / mb)} MB (history ${"%.1f".format(undoBytes(c) / mb)} MB)"
            }

            // Layer 2: a painted 1000 × 1000 source, arrayed in a circle.
            val l2 = c.doc.layers[1]
            Canvas(l2.bitmap).apply {
                drawColor(0x403366AA, android.graphics.PorterDuff.Mode.SRC)
                drawCircle(2000f, 1500f, 450f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFEE8800.toInt() })
            }
            c.selectLayer(l2)
            c.setSelection(Selection.fromPath(Path().apply { addRect(1500f, 1000f, 2500f, 2000f, Path.Direction.CW) }, w, h, antiAlias = false), recordUndo = false)
            assertTrue(c.arrayFromSelection())
            c.setSelection(null, recordUndo = false)
            val arrayed = c.activeLayer
            assertNotNull(arrayed.array)
            assertTrue(ArrayOps.edit(c, arrayed, ArraySpec(mode = ArrayMode.CIRCLE, count = 12)))
            milestone("2 layers and an array of 12")

            // Three isolated folders: Outer > Inner > the array, and one round Layer 2 at 60 %.
            val inner = c.putInNewFolder(arrayed)!!
            val outer = c.putInNewFolder(inner)!!
            val third = c.putInNewFolder(l2)!!
            for (f in listOf(inner, outer, third)) c.setFolderPassThrough(f, false)
            c.setLayerProps(third, third.props().copy(opacity = 0.6f))
            assertEquals(3, c.doc.layers.count { it.isFolder })
            milestone("3 isolated folders")

            // Eight saved selections, each a different anti-aliased lasso made the active selection first.
            for (k in 0 until 8) {
                val p = Path().apply { addOval(RectF(100f + k * 60f, 200f + k * 50f, w - 300f + k * 20f, h - 100f - k * 70f), Path.Direction.CW) }
                c.setSelection(Selection.fromPath(p, w, h, antiAlias = true))
                assertTrue(c.saveSelection())
                assertTrue(Smoke.pumpUntil(30_000) { c.pendingSavedSelections.isEmpty() })
            }
            assertEquals(8, c.doc.savedSelections.size)
            c.setSelection(null)
            milestone("8 saved selections (${"%.2f".format(c.doc.savedSelections.sumOf { it.bytes } / mb)} MB packed)")

            // The array session open: the Array tool on the array, an edit to 24 copies rendering.
            c.selectLayer(arrayed)
            c.selectTool(ToolId.ARRAY)
            Smoke.pump(40)
            assertTrue(ArrayOps.edit(c, arrayed, ArraySpec(mode = ArrayMode.CIRCLE, count = 24)))
            milestone("the Array tool open, 24 copies")

            // A save (the incremental writer's buffers) and a saved selection loaded as the selection.
            runBlocking { ProjectRepository(app).save(c.doc, c.compositor.renderThumbnail(512)) }
            c.loadSavedSelection(c.doc.savedSelections.first().id, SelectionMode.REPLACE)
            assertTrue(Smoke.pumpUntil(30_000) { c.selection != null })
            milestone("saved, a selection loaded")
        } finally {
            sampler.running = false
            sampler.join(1000)
            c.dispose()
        }
        val poolPeak = pools.sumOf { it.peakUsage.used }
        for (m in milestones) println("[qa17 compat perf] peak heap row: $m")
        println("[qa17 compat perf] peak heap row: sampled Java heap peak +${"%.1f".format((sampler.peak - base) / mb)} MB (garbage included; pools' peaks summed ${"%.1f".format(poolPeak / mb)} MB); worst live Java heap + bitmaps ${"%.1f".format(worst / mb)} MB; phone budget 0.85 × 512 MB = ${"%.1f".format(0.85 * phoneMaxHeap / mb)} MB")
        assertTrue("live Java heap + bitmaps ${worst / mb} MB over 0.85 × 512 MB", worst < 0.85 * phoneMaxHeap)
    }
}
