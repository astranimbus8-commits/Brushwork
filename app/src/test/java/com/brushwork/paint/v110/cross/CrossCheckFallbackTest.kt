package com.brushwork.paint.v110.cross

import android.graphics.Rect
import android.os.Build
import android.os.Handler
import android.os.Looper
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerStorageMode
import com.brushwork.paint.engine.budget.LayerBudgetV110
import com.brushwork.paint.engine.check.AutoCheckRunner
import com.brushwork.paint.engine.check.CheckEnv
import com.brushwork.paint.engine.check.CheckStore
import com.brushwork.paint.engine.check.M5Result
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.engine.tiles.NoSwap
import com.brushwork.paint.engine.tiles.SlabSwapStore
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.CanvasLimits
import com.brushwork.paint.storage.engine.CheckReasons
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeExitInfo
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.FakeStorageProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.v110.a10.A10Kit
import com.brushwork.paint.v110.a10.SwapKit
import com.brushwork.paint.v110.a11.A11Kit
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.concurrent.CountDownLatch
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit

/**
 * v1.10 G2 (compat, C4; A10 x J13 x I31): the level is fixed when an editor opens and never
 * changes while it is open (I31). On the T606, with the setting at AUTOMATIC and a stored FULL
 * result of this model at another memory size (J13: another device key, so the check is due as
 * DEVICE and has no previous result whose FULL a slow M5 would keep), the automatic check starts
 * in the gallery and reaches M5; an artwork opens meanwhile, at FULL (the stored result) with its
 * swap store and no fixed layer limit. M5 then ends with a slow number (as a bench finishing just
 * as the editor opens: nothing between M5 and the result asks about the editor again), so the
 * check stores CLASSIC ("slow compositing") and posts the gallery's budget to the main thread.
 *
 * While the artwork stays open nothing of it changes: the level, the budgets, the layer limit, the
 * swap store, the residency, Classic's stroke switch (unset), the editor, the document and its
 * history. At the editor's close the gallery's budget is the result's, and at the next open the
 * level reads CLASSIC with v1.9's limits: a fixed layer limit never below v1.9's cap, v1.9's
 * "Layer limit reached (N) for this canvas size", no swap store, every painted layer in a bitmap
 * (no tiles) with the pixels as saved, and the result's stroke switch.
 */
@RunWith(RobolectricTestRunner::class)
class CrossCheckFallbackTest {
    private val memory = FakeMemoryProbe(MemoryPreset.T606)

    @get:Rule
    val engine = EngineRule(EngineLevel.FULL, memory = memory, storage = FakeStorageProbe(), exits = FakeExitInfo())

    private val app get() = RuntimeEnvironment.getApplication() as BrushworkApp

    /** Lets the check's M5 end. */
    private val go = CountDownLatch(1)
    private val pool = Executors.newSingleThreadExecutor()

    @After
    fun tearDown() {
        go.countDown()
        pool.shutdown()
        pool.awaitTermination(10, TimeUnit.SECONDS)
        var closed = false
        app.closeEditor { closed = true }
        A10Kit.pumpUntil { closed && EngineConfig.swapStore === NoSwap }
        SlabSwapStore.installUnderRobolectric = false
        SwapKit.cleanUp()
        CheckStore.file(app).delete()
    }

    /** What the check must not change in an open artwork: the layers, their pixels, the active layer, the history. */
    private class Doc(c: EditorController) {
        val size = c.doc.width to c.doc.height
        val ids = c.doc.layers.map { it.id }
        val names = c.doc.layers.map { it.name }
        val pixels = c.doc.layers.map { A11Kit.bytesOf(it.pixels, c.doc.width, c.doc.height) }
        val active = c.activeLayer.id
        val undo = c.undoManager.undoCount
        val redo = c.undoManager.canRedo

        fun assertSame(what: String, c: EditorController) {
            val now = Doc(c)
            assertEquals("$what: the canvas", size, now.size)
            assertEquals("$what: the layers", ids, now.ids)
            assertEquals("$what: their names", names, now.names)
            for (i in pixels.indices) assertArrayEquals("$what: the pixels of layer ${ids[i]}", pixels[i], now.pixels[i])
            assertEquals("$what: the active layer", active, now.active)
            assertEquals("$what: the undo steps", undo, now.undo)
            assertEquals("$what: a step to redo", redo, now.redo)
        }
    }

    @Test
    fun aClassicResultWhileAnArtworkIsOpenAppliesAtTheNextOpen() {
        // The level comes from the check from here on, not from the rule's test level.
        System.clearProperty(EngineRule.MODE)
        app.settings.layerStorageMode = LayerStorageMode.AUTOMATIC
        SlabSwapStore.installUnderRobolectric = true
        val saved = saveArtwork()
        val model = Build.MODEL.orEmpty()
        val phoneKey = EngineConfig.deviceKey(model, memory.snapshot().totalMem)
        val otherKey = EngineConfig.deviceKey(model, OTHER_MEM)
        assertNotEquals("another memory size is another device (J13)", phoneKey, otherKey)
        CheckStore.write(app, A10Kit.result(EngineLevel.FULL, totalMem = OTHER_MEM).copy(deviceKey = otherKey))

        // The check, as the app runs it (its own editor flag, layer storage choice and main thread),
        // with a slow M5 that waits at its start until the artwork is open.
        val main = Handler(Looper.getMainLooper())
        val env = CheckEnv(
            versionCode = A10Kit.VERSION,
            model = model,
            fingerprint = A10Kit.FINGERPRINT,
            editorOpen = { app.editorSession != null },
            mode = { app.settings.layerStorageMode },
            onMain = { main.post(it) },
        )
        val atM5 = CountDownLatch(1)
        val steps = A10Kit.Steps(app, m5 = SLOW_M5)
        steps.before = { step ->
            if (step == "m5") {
                atM5.countDown()
                go.await(30, TimeUnit.SECONDS)
            }
        }
        val run = pool.submit<AutoCheckRunner.Outcome> { AutoCheckRunner(app, env, steps).runIfDue { false } }
        assertTrue("the check reaches M5 in the gallery", atM5.await(20, TimeUnit.SECONDS))

        // The artwork opens: FULL, with its swap store and no fixed layer limit.
        val session = app.openEditor(ID)
        val c = ready(session)
        assertEquals("the stored result's level", EngineLevel.FULL, EngineConfig.level)
        val store = EngineConfig.swapStore
        assertTrue("the session's swap store, not $store", store is SlabSwapStore)
        SwapKit.track(store as SlabSwapStore)
        val budget = LayerBudget.current as LayerBudgetV110
        assertTrue("no fixed layer limit", budget.noFixedLimit)
        assertEquals(LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
        assertNull("Classic's stroke switch is unset", EngineConfig.strokeBelowMinLayers)
        val memoryBudget = EngineConfig.budget
        val residency = EngineConfig.residency
        val before = Doc(c)

        // M5 ends: the check stores CLASSIC and posts the gallery's budget, which runs now.
        go.countDown()
        assertTrue("the check ends", A10Kit.pumpUntil { run.isDone })
        assertEquals(AutoCheckRunner.Outcome.DONE, run.get())
        val result = CheckStore.readStored(app) ?: throw AssertionError("no result stored")
        assertEquals(EngineLevel.CLASSIC, result.level)
        assertEquals(CheckReasons.SLOW_COMPOSITING, result.reason)
        assertEquals("this phone's result", phoneKey, result.deviceKey)
        var posted = false
        main.post { posted = true }
        assertTrue("the main thread ran what the check posted", A10Kit.pumpUntil { posted })

        // Nothing of the open artwork changed.
        assertEquals("open: the level", EngineLevel.FULL, EngineConfig.level)
        assertSame("open: the layer budget", budget, LayerBudget.current)
        assertSame("open: the memory budget", memoryBudget, EngineConfig.budget)
        assertSame("open: the swap store", store, EngineConfig.swapStore)
        assertSame("open: the residency", residency, EngineConfig.residency)
        assertTrue("open: no fixed layer limit", budget.noFixedLimit)
        assertEquals("open: the layer limit", LayerBudget.MAX_LAYERS_GUARD, c.maxLayers)
        assertNull("open: Classic's stroke switch", EngineConfig.strokeBelowMinLayers)
        assertSame("open: the session", session, app.editorSession)
        assertEquals("open: the editor", EditorSession.State.Ready(c), session.state)
        before.assertSame("open", c)

        // The editor closes: the gallery's budget is the result's (Classic, a fixed limit).
        var closed = false
        app.closeEditor { closed = true }
        assertTrue(A10Kit.pumpUntil { closed && EngineConfig.swapStore === NoSwap && LayerBudget.current !== budget })
        SwapKit.awaitClosed(store)
        val gallery = LayerBudget.current as LayerBudgetV110
        assertFalse("the gallery: a fixed limit", gallery.noFixedLimit)
        val per = CanvasLimits.layerBytes(SIDE, SIDE)
        assertEquals(
            "the gallery: Classic's layers",
            LayerBudgetV110.classicMaxLayers(EngineConfig.budget.nativeBudget, EngineConfig.budget.javaMax, per),
            gallery.capacity(SIDE, SIDE),
        )

        // The next open: CLASSIC, with v1.9's limits.
        val again = app.openEditor(ID)
        val c2 = ready(again)
        assertEquals("the level reads Classic", EngineLevel.CLASSIC, EngineConfig.level)
        assertSame("no swap store", NoSwap, EngineConfig.swapStore)
        val classic = LayerBudget.current as LayerBudgetV110
        assertFalse("a fixed layer limit", classic.noFixedLimit)
        val j = EngineConfig.budget.javaMax
        val n = LayerBudgetV110.classicMaxLayers(EngineConfig.budget.nativeBudget, j, c2.doc.layerBytes)
        assertEquals("Classic's layer limit", n, c2.maxLayers)
        assertTrue("never below v1.9's cap ($n)", n >= LayerBudgetV110.v19Cap(j, c2.doc.layerBytes))
        assertTrue("below the guard ($n)", n < LayerBudget.MAX_LAYERS_GUARD)
        assertEquals("v1.9's refusal", "Layer limit reached ($n) for this canvas size", classic.refusal(c2.doc, null))
        assertEquals("the result's stroke switch", SLOW_M5.belowCacheMinLayers, EngineConfig.strokeBelowMinLayers)
        assertEquals(listOf(1L, 2L, 3L), c2.doc.layers.map { it.id })
        for ((i, l) in c2.doc.layers.withIndex()) {
            if (i == 1) {
                assertTrue("layer ${l.id}: never painted, blank (${l.pixels})", l.pixels is BlankPixels)
            } else {
                assertTrue("layer ${l.id}: a bitmap at Classic, not ${l.pixels}", l.pixels is FlatPixels)
            }
            assertArrayEquals("layer ${l.id}: the pixels as saved", saved[i], A11Kit.bytesOf(l.pixels, SIDE, SIDE))
        }
    }

    /** The open artwork's controller once [session] has loaded. */
    private fun ready(session: EditorSession): EditorController {
        assertTrue("the artwork loads", A10Kit.pumpUntil { session.state !is EditorSession.State.Loading })
        return (session.state as? EditorSession.State.Ready)?.controller ?: throw AssertionError("not opened: ${session.state}")
    }

    /** Saves the artwork (two painted layers around a never-painted one); returns each layer's raw pixels. */
    private fun saveArtwork(): List<ByteArray> {
        val doc = Document(ID, "Fallback", SIDE, SIDE)
        val paper = BitmapUtils.createLayerBitmap(SIDE, SIDE).also {
            A11Kit.fill(it, Rect(40, 60, 300, 280), 0xFFE04020.toInt())
            A11Kit.fill(it, Rect(120, 20, 200, 400), 0x802E7DD7.toInt())
        }
        val ink = BitmapUtils.createLayerBitmap(SIDE, SIDE).also {
            A11Kit.fill(it, Rect(200, 100, 480, 400), 0xFF2E7DD7.toInt())
        }
        val raws = listOf(A11Kit.raw(paper), ByteArray(SIDE * SIDE * 4), A11Kit.raw(ink))
        doc.layers += Layer(1, "Paper", paper)
        doc.layers += Layer(2, "Blank", BlankPixels(SIDE, SIDE))
        doc.layers += Layer(3, "Ink", ink)
        doc.activeLayerIndex = 2
        val repo = A11Kit.freshRepo()
        try {
            runBlocking { repo.save(doc, null) }
        } finally {
            A11Kit.dispose(doc)
        }
        return raws
    }

    private companion object {
        const val ID = "00000000-0000-0000-0000-00000000c4c4"
        const val SIDE = 512

        /** A 4 GiB phone of the same model: another device key than the T606's 3.7 GiB. */
        const val OTHER_MEM = 4096L shl 20

        /** Above the 60 ms of rule 2: CLASSIC, "slow compositing", with no previous result to keep. */
        val SLOW_M5 = M5Result(compositeMs = 80f, belowCacheMinLayers = 32)
    }
}
