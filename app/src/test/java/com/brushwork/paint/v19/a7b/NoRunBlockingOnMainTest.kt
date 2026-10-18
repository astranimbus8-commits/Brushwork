package com.brushwork.paint.v19.a7b

import com.brushwork.paint.smoke.Smoke
import kotlinx.coroutines.CompletableDeferred
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.9 A7b step 5 (design 3.2 A7b, R9): no wait on the main thread is unbounded any more. v1.8's
 * three `runBlocking` waits (`VectorLayers.flushPending`, `ArrayRenders.flush`,
 * `EditorController.landSavedSelections`) are gone from the app's code, and the bounded wait
 * that replaced them gives up at its limit: a render that never ends holds the main thread
 * [com.brushwork.paint.EditorController.deferredWaitLimitMs] at most, and not at all while the
 * app leaves the screen (`EditorSession.saveOnLeaving` waits off the main thread instead).
 *
 * v1.9 Gate 2 (M3): the last one, A5's `EraseLanding` (a region erase's cut still running when
 * another step lands it), uses the same bounded wait now.
 */
@RunWith(RobolectricTestRunner::class)
class NoRunBlockingOnMainTest {

    /** A call (not an import or a comment): `runBlocking {`, `runBlocking(` or `runBlocking<`. */
    private val call = Regex("""\brunBlocking\s*[({<]""")

    private fun mainSources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        return null
    }

    private fun calls(lines: List<String>): List<Int> = lines.withIndex().filter { (_, t) ->
        val s = t.trimStart()
        !s.startsWith("*") && !s.startsWith("//") && !s.startsWith("/*") && call.containsMatchIn(t.substringBefore("//"))
    }.map { it.index + 1 }

    @Test
    fun theAppsCodeHasNoRunBlocking() {
        val root = mainSources()
        assertNotNull("main sources not found from ${System.getProperty("user.dir")}", root)
        val found = ArrayList<String>()
        root!!.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.forEach { f ->
            val rel = f.relativeTo(root).invariantSeparatorsPath
            for (line in calls(f.readLines())) found += "$rel:$line"
        }
        assertTrue("runBlocking on the main thread: $found", found.isEmpty())
        for (f in listOf("vector/VectorLayers.kt", "array/ArrayRenders.kt", "vector/draw/EraseLanding.kt")) {
            assertFalse("$f: no runBlocking import left", File(root, f).readLines().any { it.trim() == "import kotlinx.coroutines.runBlocking" })
        }
        // v1.8's three waits are bounded now.
        val controller = File(root, "EditorController.kt").readText()
        assertTrue(controller.contains("awaitDeferredJob(p.work, limit)"))
        assertTrue(File(root, "vector/VectorLayers.kt").readText().contains("c.awaitDeferredJob(job)"))
        assertTrue(File(root, "array/ArrayRenders.kt").readText().contains("c.awaitDeferredJob(job)"))
        assertTrue(File(root, "vector/draw/EraseLanding.kt").readText().contains("c.awaitDeferredJob(job)"))
    }

    @Test
    fun theBoundedWaitGivesUpAtItsLimitAndNotAtAllWhileLeaving() {
        val c = Smoke.controller(RuntimeEnvironment.getApplication())
        assertEquals("1.5 s in the app", 1_500L, c.deferredWaitLimitMs)
        // Work that ends only long after the limit (on another thread): the wait gives up first.
        c.deferredWaitLimitMs = 120
        val late = CompletableDeferred<Unit>()
        Thread { Thread.sleep(5_000); late.complete(Unit) }.apply { isDaemon = true }.start()
        assertFalse(c.awaitDeferredJob(late))
        assertFalse("gave up before the work ended", late.isCompleted)
        // Done work: no wait.
        assertTrue(c.awaitDeferredJob(CompletableDeferred(Unit)))
        // Work that ends within the limit: the wait ends with it, done.
        val soon = CompletableDeferred<Unit>()
        Thread { Thread.sleep(30); soon.complete(Unit) }.start()
        c.deferredWaitLimitMs = 20_000
        assertTrue(c.awaitDeferredJob(soon))
        // Leaving: the leaving save waited off the main thread already; none here, whatever the limit.
        val later = CompletableDeferred<Unit>()
        Thread { Thread.sleep(5_000); later.complete(Unit) }.apply { isDaemon = true }.start()
        c.leavingDeferred = true
        assertFalse(c.awaitDeferredJob(later))
        assertFalse("no wait while leaving", later.isCompleted)
        assertTrue("must land then", c.mustLandDeferredWork())
        c.leavingDeferred = false
        assertFalse("the user's own action cancels instead", c.mustLandDeferredWork())
        late.cancel()
        later.cancel()
    }
}
