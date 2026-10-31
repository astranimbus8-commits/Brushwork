package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import android.os.MessageQueue
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.GovernorPolicy
import com.brushwork.paint.engine.GovernorPolicyV19
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.MemoryGovernor
import com.brushwork.paint.engine.budget.LedgerCategory
import com.brushwork.paint.engine.budget.MemoryBudget
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.PixelPin
import com.brushwork.paint.engine.tiles.PixelStore
import com.brushwork.paint.engine.tiles.ResidencyState
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.engine.tiles.TileOps
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.testing.TiledGoldenKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.nio.ByteBuffer
import java.security.MessageDigest
import java.util.IdentityHashMap
import java.util.zip.GZIPInputStream
import java.util.zip.GZIPOutputStream
import kotlin.random.Random

/**
 * v1.10 A10 (design 2.12 and 6.5, step 5): the engine's soak. One seeded run of random edits on a
 * document of many layers at FULL with park chaos (`ChaosResidency(everyTask = true)`: every
 * inactive layer is parked again whenever the main queue goes idle) and a real [SlabSwapStore] (at
 * each checkpoint every painted tile of every parked layer is swapped out, so the next reads of
 * those layers come from the swap file), against a Classic replay of the same seed. The edits:
 * strokes, undo and redo, layers added, duplicated, deleted, moved and merged, folder moves,
 * visibility, layer flips, canvas rotations, and saves with reopens.
 *
 * What holds: at each checkpoint the flattened render and every row of the layer list (its place,
 * id, name, parent, visibility and pixels) equal the replay's bit for bit, and nothing reached a
 * scope; the FULL run parked layers and swapped tiles; after the FULL run is closed the tile
 * ledger is back where it started, the store holds nothing and its directory is gone. v1.10 G2
 * (soak fix): every step also did at the replay what it did at FULL ([Trace]: the edit's kind, the
 * layer and undo counts after it, the message it left), so an edit one level refused, ignored or
 * deferred fails at its own step with its message, not at the next checkpoint.
 *
 * The suite runs a short version ([Plan.SUITE]: a 64 px canvas, up to 24 layers). The nightly soak
 * job (`bw.testSet` soak) runs [Plan.NIGHTLY]: about 500 layers on 256 px canvases for 20 minutes;
 * the FULL run sets the length and the replay follows it step for step. The layer budget is not
 * under test, so both runs use one with room for every layer ([Roomy]; v1.9's cap is 100).
 * Nor is the undo history's byte cap: the levels' caps and step weights differ by design
 * (section 2.6: Classic keeps v1.9's `max(48 MiB, J/4)`, 128 MiB on the T606, and counts every
 * painted layer at its full size, so a canvas rotation of about 500 painted 256 px layers weighs
 * about 125 MiB and a second one drops the first at Classic only, while FULL keeps both and its
 * undo of the older one then has nothing to replay against). Both runs therefore set
 * `MemoryBudget.UNDO_CAP_PROPERTY` to no byte cap ([NO_UNDO_BYTE_CAP]), and after every edit both
 * keep the newest [Plan.undoSteps] steps alone ([keepNewestSteps]): the same history at both
 * levels. The nightly plan keeps 32: with 150 the FULL run's history (its tiles in the ledger)
 * grew to about 1.1 GiB within 150 steps of 2 % rotations, and the FULL run refused a rotation
 * with "Not enough memory to change the canvas with this many layers" that the Classic replay
 * (whose ledger holds no layer) made. 32 steps hold about 4 rotations at most, well under R; an
 * undo chain never comes near 32 steps (8 % of the edits undo).
 *
 * v1.10 G2 (soak fix, nightly run 40819618156): nor is memory admission (`MemoryGovernor.admit`).
 * The app's policy weighs an allocation against this JVM's own heap (`GovernorPolicyV19.heap`,
 * `maxMemory` less 64 MiB), and the test's own use of that heap differs between the runs and from
 * runner to runner: in that run the Classic replay held the FULL run's checkpoints besides its
 * own, the heap crossed the margin near step 180,249, and from there the replay refused every
 * "Merge down" FULL had made ("Not enough memory for Merge down"), so the layer list at step
 * 180,300 differed. Each run therefore sets [Admits] over the app's policy (every allocation
 * admitted, the rest the app's own), and the suite's
 * [aCrowdedJavaHeapAtEitherLevelLeavesTheReplayAsTheFullRun] crowds the heap at one level at a
 * time to hold it.
 *
 * Leaks of tiled stores. A8 (undo on tiles, step 4) and A11 (canvas operations on stores, with
 * pins, step 4) are merged, so [PRE_A8_A11_DROPS] is false: the soak takes no pins of its own, and
 * every tiled store it saw in a layer must be empty once the editor is disposed and the layers
 * recycled ([Drops]), else the run fails; the ledger and swap checks after the close hold as well.
 * With the flag true it would run as against gate-v110-1, whose code let go of a PARKED layer's
 * tiled store without releasing its tiles in three places (the tiles then stayed in the ledger and
 * the swap):
 * (1) A canvas operation takes no pins: the chaos parks a layer again between the operation's
 * snapshot and its commit, which still matches (a park changes no generation) and puts its bitmap
 * over the parked store; with the flag the soak pins the pixel layers itself from the rotation to
 * its landing ([held]). (2) The `Layer.bitmap` and `mask` setters replaced a parked store without
 * releasing it: an undo or redo of a whole-document step (`DocumentBitmapsAction`) over a layer
 * parked since. (3) The undo history let go of a removed layer's parked store when the editor
 * closed. With the flag the soak finds the stores of (2) and (3) ([Drops]), releases them and
 * counts them.
 */
@RunWith(RobolectricTestRunner::class)
class Soak500LayersTest {
    @get:Rule
    val engine = EngineRule(level = EngineLevel.FULL, memory = FakeMemoryProbe(MemoryPreset.T606), storage = FakeStorageProbe())

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp
    private val projects = mutableListOf<String>()

    /** The app's memory policy and the heap it reads, as the test found them (each run puts [Admits] over the policy). */
    private lateinit var savedPolicy: GovernorPolicy
    private lateinit var savedHeap: GovernorPolicyV19.Heap

    @Before
    fun setUp() {
        Smoke.scopeErrors.clear()
        savedPolicy = MemoryGovernor.policy
        savedHeap = GovernorPolicyV19.heap
    }

    @After
    fun tearDown() {
        MemoryGovernor.policy = savedPolicy
        GovernorPolicyV19.heap = savedHeap
        projects.forEach { id -> runCatching { runBlocking { app.repository.delete(id) } } }
        SwapKit.cleanUp()
        Smoke.scopeErrors.clear()
        System.clearProperty(MemoryBudget.UNDO_CAP_PROPERTY)
    }

    @Test
    fun fullWithParkChaosAndSwapPaintsAsTheClassicReplay() {
        val plan = if (System.getProperty("bw.testSet") == "soak") Plan.NIGHTLY else Plan.SUITE
        val full = run(EngineLevel.FULL, plan, steps = null, expected = null)
        assertTrue("the FULL run parked layers", full.parked > 0)
        assertTrue("the FULL run swapped tiles", full.swapped > 0)
        val classic = run(EngineLevel.CLASSIC, plan, steps = full.steps, expected = full)
        assertEquals("the replay met every checkpoint", full.checks, classic.checks)
        println(
            "v110 A10 Soak500LayersTest ($plan): ${full.steps} steps, ${full.checks} checkpoints, " +
                "up to ${full.mostLayers} layers, ${full.parked} parked layers seen at checkpoints, ${full.swapped} tiles swapped out, " +
                "${full.drops} stores the gate dropped, released",
        )
    }

    /**
     * v1.10 G2 (soak fix, nightly run 40819618156): the Java heap does not steer the replay. The
     * test's own memory varies from run to run (what it keeps, the runner's speed), so a level
     * whose heap is crowded (`GovernorPolicyV19.heap` used up to `maxMemory`, as in that run's
     * Classic replay) must still make every edit the other level made; [Admits] holds that. Each
     * way round: the heap crowded only at Classic (the replay would refuse "Merge down"), then
     * only at FULL (FULL would, and the replay would merge). Both runs ask to merge down, so the
     * crowded heap is met.
     */
    @Test
    fun aCrowdedJavaHeapAtEitherLevelLeavesTheReplayAsTheFullRun() {
        for (crowded in listOf(EngineLevel.CLASSIC, EngineLevel.FULL)) {
            GovernorPolicyV19.heap = CrowdedAt(crowded)
            try {
                val full = run(EngineLevel.FULL, Plan.SUITE, steps = null, expected = null)
                val classic = run(EngineLevel.CLASSIC, Plan.SUITE, steps = full.steps, expected = full)
                assertEquals("crowded at $crowded: the replay met every checkpoint", full.checks, classic.checks)
                for (o in listOf(full, classic)) {
                    assertTrue("crowded at $crowded: a merge down was asked (${o.asked})", (o.asked[MERGE_DOWN] ?: 0) > 0)
                }
            } finally {
                GovernorPolicyV19.heap = savedHeap
            }
        }
    }

    /** A Java heap used up to its maximum while [EngineConfig.level] is [level], empty otherwise. */
    private class CrowdedAt(private val level: EngineLevel) : GovernorPolicyV19.Heap {
        override fun used(): Long = if (EngineConfig.level == level) max() else 0L

        override fun max(): Long = 1536L shl 20

        override fun collect() = Unit
    }

    /**
     * v1.10 G2 (soak fix): the policy each run sets over the app's ([inner]): it admits every
     * allocation (`MemoryGovernor.admit`) and counts what was asked, by purpose ([asked]);
     * everything else is [inner]'s. Admission is not under test (the class KDoc).
     */
    private class Admits(private val inner: GovernorPolicy) : GovernorPolicy by inner {
        val asked = java.util.concurrent.ConcurrentHashMap<String, Int>()

        override fun admit(bytes: Long, purpose: String): Boolean {
            asked.merge(purpose, 1, Int::plus)
            return true
        }
    }

    /**
     * v1.10 G2: the soak's undo rule. On the T606 Classic's own cap (v1.9's `max(48 MiB, J/4)`)
     * is 128 MiB, so a second step of a rotation's weight (about 125 MiB) drops the first;
     * with `MemoryBudget.UNDO_CAP_PROPERTY` set to [NO_UNDO_BYTE_CAP] the cap is that at every
     * level and both are kept; unset, empty or not positive, the formula holds again.
     */
    @Test
    fun theUndoCapPropertyIsTheCapAtEveryLevel() {
        val budget = EngineConfig.budget
        val formula = EngineLevel.entries.associateWith { budget.undoCap(it) }
        assertEquals("Classic's own cap on the T606", 128L shl 20, formula[EngineLevel.CLASSIC])
        val rotation = 125L shl 20
        fun keeps(cap: Long): Int {
            val um = com.brushwork.paint.engine.UndoManager(maxBytes = cap)
            repeat(2) { i -> um.push(Weighed("Rotate $i", rotation)) }
            return um.undoCount
        }
        assertEquals("Classic's own cap keeps one rotation", 1, keeps(budget.undoCap(EngineLevel.CLASSIC)))
        try {
            System.setProperty(MemoryBudget.UNDO_CAP_PROPERTY, NO_UNDO_BYTE_CAP.toString())
            for (lv in EngineLevel.entries) assertEquals("$lv: the property's cap", NO_UNDO_BYTE_CAP, budget.undoCap(lv))
            assertEquals("both rotations kept", 2, keeps(budget.undoCap(EngineLevel.CLASSIC)))
            for (v in listOf("", "0", "-1", "lots")) {
                System.setProperty(MemoryBudget.UNDO_CAP_PROPERTY, v)
                for (lv in EngineLevel.entries) assertEquals("$lv, \"$v\": the formula", formula[lv], budget.undoCap(lv))
            }
        } finally {
            System.clearProperty(MemoryBudget.UNDO_CAP_PROPERTY)
        }
        for (lv in EngineLevel.entries) assertEquals("$lv, unset: the formula", formula[lv], budget.undoCap(lv))
    }

    /** An undo step that only weighs [byteSize]. */
    private class Weighed(override val label: String, override val byteSize: Long) : com.brushwork.paint.engine.UndoAction {
        override fun undo(c: EditorController) = Unit
        override fun redo(c: EditorController) = Unit
    }

    // ------------------------------------------------------------------ one run

    /**
     * What one run did: [checks] checkpoints, kept in [checkpoints] by a run that has none to
     * compare with (the FULL run; the replay compares and keeps none); [trace] what each step did.
     */
    private class Outcome(
        val steps: Int,
        val checks: Int,
        val checkpoints: List<Checkpoint>,
        val trace: Trace,
        val asked: Map<String, Int>,
        val parked: Int,
        val swapped: Int,
        val mostLayers: Int,
        val drops: Int,
    )

    /**
     * v1.10 G2 (soak fix): what each step of a run did ([signature]: the edit's kind and the
     * layer and undo counts after it, packed in a Long) and the message it left, if any (a
     * refusal says why). The replay must do the same at every step, so an edit one level refused,
     * ignored or deferred fails at its own step, with its message, and not a checkpoint later.
     * About 8 bytes a step. Main thread.
     */
    private class Trace {
        private var sigs = LongArray(1 shl 12)
        private val messages = HashMap<Int, String>()

        /** The steps recorded. */
        var size = 0
            private set

        fun add(sig: Long, message: String?) {
            if (size == sigs.size) sigs = sigs.copyOf(size * 2)
            sigs[size++] = sig
            if (message != null) messages[size] = message
        }

        /** Step [step] (from 1) as [describe] writes it, with its message. */
        fun at(step: Int): String? = if (step in 1..size) "${describe(sigs[step - 1])}, message ${messages[step]}" else null
    }

    /**
     * A checkpoint: the SHA-256 of the flattened render's pixels and the layer list, one line per
     * row, gzipped ([text] reads it back). v1.10 G2 (soak fix): it kept the render's pixels and the
     * rows as strings, about 330 KiB a checkpoint on the nightly plan, and the replay held the FULL
     * run's and its own, so the test's heap grew with the FULL run's length (set by the clock, so
     * by the runner's speed): about 1.2 GiB of the 1.5 GiB heap near step 160,000 of the replay.
     * Now a checkpoint is a few KiB; a render that differs is still found bit for bit (the digest),
     * though no longer at a pixel: the layer list's per-layer hashes say which layer differs.
     */
    private class Checkpoint(val step: Int, val render: ByteArray, private val rows: ByteArray) {
        fun text(): String = GZIPInputStream(ByteArrayInputStream(rows)).use { String(it.readBytes(), Charsets.UTF_8) }

        companion object {
            fun of(step: Int, render: IntArray, rows: List<String>): Checkpoint {
                val bytes = ByteBuffer.allocate(render.size * 4).apply { asIntBuffer().put(render) }.array()
                val zipped = ByteArrayOutputStream().also { out -> GZIPOutputStream(out).use { it.write(rows.joinToString("\n").toByteArray(Charsets.UTF_8)) } }
                return Checkpoint(step, MessageDigest.getInstance("SHA-256").digest(bytes), zipped.toByteArray())
            }
        }
    }

    /**
     * Runs [plan] at [level] for [steps] steps (null: until the plan's steps or minutes run out),
     * comparing each step and each checkpoint with [expected]'s when given; then closes the editor
     * and the session.
     */
    private fun run(level: EngineLevel, plan: Plan, steps: Int?, expected: Outcome?): Outcome {
        System.setProperty(EngineRule.MODE, level.name.lowercase())
        // Read by each editor this run makes (the reopens too): no byte cap, at both levels (the class KDoc).
        System.setProperty(MemoryBudget.UNDO_CAP_PROPERTY, NO_UNDO_BYTE_CAP.toString())
        EngineConfig.openSession(level, engine.sessionBudgets(), null)
        LayerBudget.current = Roomy
        // Admission reads this JVM's heap, which the test fills as it goes (the class KDoc): not under test.
        val admits = Admits(savedPolicy)
        MemoryGovernor.policy = admits
        val ledger = EngineConfig.budget.ledger
        val tilesBefore = ledger.of(LedgerCategory.TILES)
        val store = if (level == EngineLevel.FULL) SwapKit.store() else null
        if (store != null) {
            EngineConfig.swapStore = store
            EngineConfig.residency = ChaosResidency(everyTask = true)
        }
        val id = "soak500-${level.name.lowercase()}"
        projects += id
        val rnd = Random(plan.seed)
        val end = System.currentTimeMillis() + plan.minutes * 60_000L
        val checkpoints = ArrayList<Checkpoint>()
        val trace = Trace()
        val drops = Drops()
        var editor = open(document(id, plan))
        var step = 0
        var parked = 0
        var swapped = 0
        var most = 0
        var checks = 0
        fun check(where: String) {
            val doc = editor.c.doc
            Smoke.assertQuiet(editor.c, where)
            val cp = checkpoint(doc, step)
            if (expected != null) assertSameAs(expected.checkpoints.getOrNull(checks), cp, where) else checkpoints += cp
            checks++
            most = maxOf(most, doc.pixelLayerCount)
            if (store != null) {
                parked += doc.layers.count { it.residency == ResidencyState.PARKED }
                swapped += swapOut(doc, store)
            }
        }
        try {
            editor.c.selectTool(ToolId.BRUSH)
            settle(editor.c, "$level, start")
            while (if (steps != null) step < steps else step < plan.maxSteps && (plan.minutes == 0 || System.currentTimeMillis() < end)) {
                step++
                val where = "$level, step $step"
                // The UI clears a message once shown: what is there after the edit is the edit's.
                editor.c.message = null
                val k = edit(editor.c, rnd, plan, step)
                settle(editor.c, where)
                trace.add(signature(editor.c, k), editor.c.message)
                if (expected != null) assertEquals("$where: what the step did", expected.trace.at(step), trace.at(step))
                keepNewestSteps(editor.c, plan, where)
                unpin()
                drops.landed(editor.c.doc)
                if (step % plan.reopenEvery == 0) editor = reopen(editor, drops)
                if (step % plan.checkEvery == 0) check(where)
            }
            if (step % plan.checkEvery != 0) check("$level, last step $step")
        } finally {
            unpin()
            editor.close(drops)
        }
        Smoke.pump(100)
        queueIdle()
        if (store != null) {
            assertEquals("$level: the tile ledger after the close", tilesBefore, ledger.of(LedgerCategory.TILES))
            assertEquals("$level: the swap after the close", 0L, store.usedBytes)
        }
        EngineConfig.closeSession()
        MemoryGovernor.policy = savedPolicy
        if (store != null) {
            SwapKit.awaitClosed(store)
            assertFalse("$level: the session's swap directory", store.dir.exists())
        }
        return Outcome(step, checks, checkpoints, trace, HashMap(admits.asked), parked, swapped, most, drops.released)
    }

    private fun assertSameAs(want: Checkpoint?, got: Checkpoint, where: String) {
        if (want == null) throw AssertionError("$where: the FULL run had no checkpoint here")
        assertEquals("$where: the checkpoint's step", want.step, got.step)
        assertEquals("$where: the layer list", want.text(), got.text())
        assertTrue(
            "$where: the render differs from the FULL run's (SHA-256 FULL ${hex(want.render)}, Classic ${hex(got.render)})",
            want.render.contentEquals(got.render),
        )
    }

    private fun hex(b: ByteArray): String = b.joinToString("") { "%02x".format(it) }

    // ------------------------------------------------------------------ the editor

    private class Editor(val c: EditorController, val scope: CoroutineScope) {
        fun close(drops: Drops) {
            c.dispose()
            scope.cancel()
            c.doc.layers.forEach { it.recycleBitmaps() }
            drops.closed()
        }
    }

    /**
     * Case (2) and (3) of the class KDoc: every layer the soak saw in the document, with the stores
     * it held then. With [PRE_A8_A11_DROPS] (the gate's code), a layer that holds another store now
     * let go of the old one, and a store that still has tiles then was never released, so the soak
     * releases it and counts it; at the close the stores of the layers that left the document (the
     * undo history's) are released the same way. Since A8 (undo on tiles) and A11 (canvas
     * operations, merges and folder lifts moving stores by reference) an undo step may hold a
     * replaced store until the step is disposed, so a replaced store is only remembered; once the
     * editor is disposed (its history with it) and the document's layers are recycled, every store
     * the soak saw must be empty, or the run fails. Main thread.
     */
    private class Drops {
        private val seen = IdentityHashMap<Layer, Array<PixelStore?>>()

        /** The stores a layer let go of while an undo step may still hold them (without [PRE_A8_A11_DROPS]). */
        private val replaced = java.util.Collections.newSetFromMap(IdentityHashMap<PixelStore, Boolean>())

        /** The stores released so far. */
        var released = 0
            private set

        /** After an edit landed. */
        fun landed(doc: Document) {
            for ((l, held) in seen) {
                if (l.pixels !== held[0]) held[0] = dropped(held[0], l.pixels)
                if (l.maskPixels !== held[1]) held[1] = dropped(held[1], l.maskPixels)
            }
            for (l in doc.layers) seen.getOrPut(l) { arrayOf(l.pixels, l.maskPixels) }
        }

        /** After the editor closed (its undo history disposed) and the document's layers were recycled. */
        fun closed() {
            for ((l, held) in seen) {
                leaked(held[0])
                leaked(held[1])
                leaked(l.pixels)
                leaked(l.maskPixels)
            }
            for (p in replaced) leaked(p)
            seen.clear()
            replaced.clear()
        }

        /** A layer let go of [old] for [now]: released at once with [PRE_A8_A11_DROPS], else remembered for [closed]; returns [now]. */
        private fun dropped(old: PixelStore?, now: PixelStore?): PixelStore? {
            if (PRE_A8_A11_DROPS) leaked(old) else if (old is TiledPixels) replaced += old
            return now
        }

        /** [p] still has tiles when nothing should hold it: released and counted with [PRE_A8_A11_DROPS], else a failure. */
        private fun leaked(p: PixelStore?) {
            if (p is TiledPixels && p.occupied() > 0) {
                if (!PRE_A8_A11_DROPS) throw AssertionError("a ${p.width}x${p.height} tiled store was let go of with ${p.occupied()} tiles")
                TileOps.releaseStore(p)
                released++
            }
        }
    }

    /** Case (1) of the class KDoc: the pins the soak holds on the pixel layers from a canvas rotation to its landing. */
    private var held: List<PixelPin> = emptyList()

    private fun unpin() {
        held.forEach { it.close() }
        held = emptyList()
    }

    /** An editor of [doc]; its active layer set on main, as a tap does (the residency then parks the others). */
    private fun open(doc: Document): Editor {
        val scope = Smoke.newScope()
        val c = Smoke.controller(app, doc, scope)
        doc.activeLayerIndex = doc.layers.lastIndex
        return Editor(c, scope)
    }

    /** Saves as the editor does, closes, loads the files again and opens a new editor of them. */
    private fun reopen(e: Editor, drops: Drops): Editor {
        val doc = e.c.doc
        runBlocking { app.repository.save(doc, null) }
        e.close(drops)
        return open(runBlocking { app.repository.load(doc.id) })
    }

    /** [plan]'s first document: a third of the layers a translucent fill, a third a box, a third empty. */
    private fun document(id: String, plan: Plan): Document {
        val rnd = Random(plan.seed + 1)
        val n = plan.size
        val doc = Document(id, "Soak 500", n, n)
        repeat(plan.startLayers) { i ->
            val b = BitmapUtils.createLayerBitmap(n, n)
            when (i % 3) {
                0 -> b.eraseColor(COLORS[rnd.nextInt(COLORS.size)] and 0x00FFFFFF or (0x20 shl 24))
                1 -> {
                    val x = rnd.nextInt(n / 2).toFloat()
                    val y = rnd.nextInt(n / 2).toFloat()
                    Canvas(b).drawRect(x, y, x + n / 3, y + n / 4, Paint().apply { color = COLORS[rnd.nextInt(COLORS.size)] })
                }
                else -> Unit
            }
            doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", b)
        }
        doc.activeLayerIndex = doc.layers.lastIndex
        return doc
    }

    // ------------------------------------------------------------------ the edits

    /**
     * One random edit; every choice comes from [rnd] and the layer list, so the replay makes the
     * same. Returns its kind (the draw that chose it).
     */
    private fun edit(c: EditorController, rnd: Random, plan: Plan, step: Int): Int {
        val doc = c.doc
        val pixel = doc.layers.filter { !it.isFolder }
        fun anyPixel(): Layer = pixel[rnd.nextInt(pixel.size)]
        fun anyLayer(): Layer = doc.layers[rnd.nextInt(doc.layers.size)]
        val k = rnd.nextInt(100)
        when {
            k < 44 -> {
                c.selectLayer(anyPixel())
                c.color = COLORS[rnd.nextInt(COLORS.size)]
                stroke(c, rnd)
            }
            k < 52 -> if (c.canUndo) c.undo()
            k < 58 -> if (c.canRedo) c.redo()
            k < 65 -> if (pixel.size < plan.maxLayers) c.addLayer("Soak $step")
            k < 69 -> if (pixel.size < plan.maxLayers) c.duplicateLayer(anyPixel())
            k < 72 -> if (pixel.size > 2) c.deleteLayer(anyPixel())
            k < 77 -> c.moveLayer(anyLayer(), rnd.nextInt(doc.layers.size))
            k < 80 -> if (pixel.size > 2) c.mergeDown(anyPixel())
            k < 84 -> when (rnd.nextInt(3)) {
                0 -> c.addFolder()
                1 -> c.putIntoFolderAbove(anyLayer())
                else -> c.takeOutOfFolder(anyLayer())
            }
            k < 88 -> c.toggleVisibility(anyLayer())
            k < 92 -> c.flipLayer(anyPixel(), horizontal = rnd.nextBoolean())
            k < 94 -> {
                if (PRE_A8_A11_DROPS) held = pixel.map { it.pin() }
                CanvasOps.applyRotate(c, if (rnd.nextBoolean()) CanvasRotation.CW_90 else CanvasRotation.R_180)
            }
            else -> c.selectLayer(anyLayer())
        }
        return k
    }

    private fun stroke(c: EditorController, rnd: Random) {
        val w = c.doc.width
        val h = c.doc.height
        var x = rnd.nextFloat() * w
        var y = rnd.nextFloat() * h
        val dx = (rnd.nextFloat() - 0.5f) * w / 8
        val dy = (rnd.nextFloat() - 0.5f) * h / 8
        c.pointerDown(ToolPoint(x, y, 1f, 0L))
        val n = 4 + rnd.nextInt(8)
        for (i in 1..n) {
            x = (x + dx).coerceIn(0f, w - 1f)
            y = (y + dy).coerceIn(0f, h - 1f)
            c.pointerMove(ToolPoint(x, y, 0.4f + 0.6f * rnd.nextFloat(), 8L * i))
        }
        c.pointerUp(ToolPoint(x, y, 1f, 8L * (n + 1)))
    }

    /** Lets the edit land (work that went to other threads, a canvas rotation), then the main queue goes idle (park chaos). */
    private fun settle(c: EditorController, where: String) {
        assertTrue("$where: the edit landed", Smoke.pumpUntil(SETTLE_MS) { c.busyMessage == null && !c.isInteracting })
        queueIdle()
    }

    /**
     * v1.10 G2: the history both runs keep (the class KDoc): the newest [Plan.undoSteps] steps, the
     * older ones dropped as the memory governor drops them (`UndoManager.trimOldest`, which
     * disposes them). After the edit landed no grouping holds the trim, so a trim skipped at one
     * level fails here, at its step.
     */
    private fun keepNewestSteps(c: EditorController, plan: Plan, where: String) {
        c.undoManager.trimOldest(plan.undoSteps) { false }
        assertTrue("$where: the undo history keeps the newest ${plan.undoSteps} steps", c.undoManager.undoCount <= plan.undoSteps)
    }

    /**
     * The main queue going idle: its idle handlers run once, as `MessageQueue.next` runs them (the
     * paused looper of Robolectric does not run them itself); one that answers false is removed.
     */
    private fun queueIdle() {
        val q = Looper.getMainLooper().queue
        val f = MessageQueue::class.java.getDeclaredField("mIdleHandlers").apply { isAccessible = true }
        @Suppress("UNCHECKED_CAST")
        val handlers = synchronized(q) { ArrayList(f.get(q) as ArrayList<MessageQueue.IdleHandler>) }
        for (h in handlers) if (!h.queueIdle()) q.removeIdleHandler(h)
    }

    // ------------------------------------------------------------------ checkpoints and swap

    private fun checkpoint(doc: Document, step: Int): Checkpoint {
        val r = TiledGoldenKit.render(doc)
        val px = IntArray(r.width * r.height).also { r.getPixels(it, 0, r.width, 0, 0, r.width, r.height) }
        val rows = doc.layers.mapIndexed { i, l ->
            "$i id ${l.id} '${l.name}' parent ${l.parentId} visible ${l.visible} folder ${l.isFolder} pixels ${hashOf(l)}"
        }
        return Checkpoint.of(step, px, rows)
    }

    /** A hash of [l]'s pixels, read without changing where they are (a parked layer's copy, recycled after). */
    private fun hashOf(l: Layer): Int = when (val p = l.pixels) {
        is FlatPixels -> hashOf(p.bitmap)
        is TiledPixels -> p.materialize().let { b -> hashOf(b).also { b.recycle() } }
        is BlankPixels -> IntArray(p.width * p.height).contentHashCode()
        FolderPixels -> 0
    }

    private fun hashOf(b: Bitmap): Int = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }.contentHashCode()

    /**
     * Swaps out every painted resident tile of every parked layer of [doc] as main's commit after
     * the worker's write does (the bitmap recycled and let go by the ledger); returns how many.
     */
    private fun swapOut(doc: Document, store: SlabSwapStore): Int {
        var n = 0
        for (l in doc.layers) {
            val s = l.pixels as? TiledPixels ?: continue
            for (row in 0 until s.rows) for (col in 0 until s.cols) {
                val t = s.tileAt(col, row) ?: continue
                if (t.pins > 0 || t.solidColor != null || t.slot != null) continue
                val b = synchronized(t.lock) { t.bitmap } ?: continue
                if (!store.canWrite(t.bytes.toLong())) return n
                store.write(t)
                synchronized(t.lock) {
                    b.recycle()
                    t.bitmap = null
                    t.packed = null
                }
                EngineConfig.budget.ledger.sub(LedgerCategory.TILES, t.bytes.toLong())
                n++
            }
        }
        return n
    }

    // ------------------------------------------------------------------ plans

    /**
     * How long and how large: the canvas side, the layers at the start and at most, the steps at
     * most and the minutes at most (0: no time limit), a checkpoint every [checkEvery] steps, a
     * save with a reopen every [reopenEvery], and the undo steps both runs keep ([undoSteps]; 150
     * is the history's own limit, so the suite keeps every step it may).
     */
    private enum class Plan(
        val size: Int,
        val startLayers: Int,
        val maxLayers: Int,
        val maxSteps: Int,
        val minutes: Int,
        val checkEvery: Int,
        val reopenEvery: Int,
        val seed: Long,
        val undoSteps: Int,
    ) {
        SUITE(64, 8, 24, 160, 0, 20, 80, 1_105L, 150),
        NIGHTLY(256, 480, 500, 1_000_000, 20, 100, 1_000, 500L, 32),
    }

    /** A layer budget with room for every layer of the soak (the budget is not under test; v1.9's cap is 100). */
    private object Roomy : LayerBudget by LayerBudgetV19 {
        override fun maxLayers(doc: Document): Int = ROOM

        override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean = true

        override fun room(doc: Document): Int = (ROOM - used(doc)).coerceAtLeast(0)

        override fun canAddAdjustment(doc: Document): Boolean = true
    }

    private companion object {
        /** False since A8 and A11 are merged; true ran the soak against gate-v110-1's code, releasing the stores it let go of (the class KDoc). */
        const val PRE_A8_A11_DROPS = false

        const val ROOM = 10_000

        /** The purpose `LayerMerge` asks `MemoryGovernor.admit` for. */
        const val MERGE_DOWN = "Merge down"

        /** What [signature] packs, high bits first, and the bits each takes. */
        val SIG_FIELDS = listOf("kind" to 7, "layers" to 13, "pixel layers" to 13, "undo steps" to 9, "canUndo" to 1, "canRedo" to 1, "active" to 13)

        /** What one step left in [c] after an edit of kind [k] ([Trace]): the counts the replay must match. */
        fun signature(c: EditorController, k: Int): Long {
            val d = c.doc
            val values = longArrayOf(
                k.toLong(), d.layers.size.toLong(), d.pixelLayerCount.toLong(), c.undoManager.undoCount.toLong(),
                if (c.canUndo) 1L else 0L, if (c.canRedo) 1L else 0L, d.activeLayerIndex + 1L,
            )
            var sig = 0L
            for ((i, f) in SIG_FIELDS.withIndex()) {
                require(values[i] in 0 until (1L shl f.second)) { "${f.first} ${values[i]} does not fit in ${f.second} bits" }
                sig = (sig shl f.second) or values[i]
            }
            return sig
        }

        /** [sig] read back: "kind 44, layers 31, ..." ("active" is the active layer's index plus one). */
        fun describe(sig: Long): String {
            var rest = sig
            val parts = ArrayList<String>()
            for ((name, bits) in SIG_FIELDS.reversed()) {
                parts += "$name ${rest and ((1L shl bits) - 1)}"
                rest = rest ushr bits
            }
            return parts.reversed().joinToString(", ")
        }

        /** The undo cap both runs set (`MemoryBudget.UNDO_CAP_PROPERTY`): none in bytes, so only steps bound the history ([Plan.undoSteps]). */
        const val NO_UNDO_BYTE_CAP = Long.MAX_VALUE

        /** How long one edit may take to land (real ms; a rotation of 500 layers on the nightly run). */
        const val SETTLE_MS = 120_000L

        val COLORS = intArrayOf(0xFF2060C0.toInt(), 0xFF30A050.toInt(), 0xFFE0A020.toInt(), 0x80602080.toInt(), 0xFF101010.toInt(), 0xC0D02020.toInt())
    }
}
