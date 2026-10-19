package com.brushwork.paint.diagnostics

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.util.concurrent.atomic.AtomicReference
import kotlin.coroutines.EmptyCoroutineContext

/**
 * v1.9 (design 2.4.13, F1b): the diagnostics seams. [Breadcrumbs] keeps the last [Breadcrumbs.SIZE]
 * events oldest first; [ErrorReporter.handler] hands an error to the thread's uncaught-exception
 * handler (v1.8's outcome); every coroutine scope of the main sources carries the handler; the
 * memory checks sit at the filter apply, the export flattens and both merges.
 */
class DiagnosticsSeamsTest {

    @Test
    fun breadcrumbsKeepTheLastEventsOldestFirst() {
        val n = Breadcrumbs.SIZE + 8
        for (i in 0 until n) Breadcrumbs.add("seams:$i")
        val snap = Breadcrumbs.snapshot()
        assertEquals(Breadcrumbs.SIZE, snap.size)
        assertEquals((8 until n).map { "seams:$it" }, snap)
        // A snapshot is a copy: later events do not change it.
        Breadcrumbs.add("seams:after")
        assertEquals("seams:${n - 1}", snap.last())
        assertEquals("seams:after", Breadcrumbs.snapshot().last())
        assertEquals(Breadcrumbs.SIZE, Breadcrumbs.snapshot().size)
    }

    @Test
    fun breadcrumbsTakeEventsFromSeveralThreads() {
        val threads = (0 until 4).map { t -> Thread { repeat(500) { Breadcrumbs.add("thread$t:$it") } } }
        threads.forEach { it.start() }
        threads.forEach { it.join() }
        val snap = Breadcrumbs.snapshot()
        assertEquals(Breadcrumbs.SIZE, snap.size)
        assertTrue(snap.toString(), snap.all { it.startsWith("thread") })
        // Each thread's events stay in its own order.
        for (t in 0 until 4) {
            val mine = snap.filter { it.startsWith("thread$t:") }.map { it.substringAfter(':').toInt() }
            assertEquals(mine.sorted(), mine)
        }
    }

    @Test
    fun theHandlerHandsTheErrorToTheThreadsHandler() {
        val seen = AtomicReference<Throwable?>()
        val boom = IllegalStateException("boom")
        val t = Thread { ErrorReporter.handler.handleException(EmptyCoroutineContext, boom) }
        t.uncaughtExceptionHandler = Thread.UncaughtExceptionHandler { _, e -> seen.set(e) }
        t.start()
        t.join()
        assertSame(boom, seen.get())
    }

    @Test
    fun everyCoroutineScopeOfTheMainSourcesCarriesTheHandler() {
        val root = mainSources()
        assertNotNull("the main sources are found from ${System.getProperty("user.dir")}", root)
        // A `: CoroutineScope` return type is no scope. Gate 2 (P2): a composable's scope is
        // `rememberSupervisedScope()`, whose helper (diagnostics/SupervisedScope.kt) carries the handler
        // on its own lines; its declaration is no site.
        val scope = Regex("""(?<!:\s)(?<!:)\b(CoroutineScope|rememberCoroutineScope|MainScope)\s*[({]|\bGlobalScope\b""")
        val supervised = Regex("""(?<!fun )\brememberSupervisedScope\s*\(""")
        val found = mutableListOf<String>()
        val unguarded = mutableListOf<String>()
        for (f in root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }) {
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//")
                if (code.trimStart().startsWith("*") || code.trimStart().startsWith("/*")) return@forEachIndexed
                val isSupervised = supervised.containsMatchIn(code)
                if (!isSupervised && !scope.containsMatchIn(code)) return@forEachIndexed
                val where = "${f.name}:${i + 1}"
                found += where
                if (!isSupervised && !code.contains("ErrorReporter.handler")) unguarded += "$where: ${line.trim()}"
            }
        }
        assertTrue("the scopes are found: $found", found.size >= 13)
        assertTrue("the helper's own scopes are found: $found", found.count { it.startsWith("SupervisedScope.kt:") } >= 2)
        assertTrue("a coroutine scope without ErrorReporter.handler (design 2.4.13): $unguarded", unguarded.isEmpty())
    }

    @Test
    fun theMemoryChecksSitAtTheirCallSites() {
        val root = mainSources()!!
        val sites = mapOf(
            "filters/FilterSession.kt" to 1,
            "engine/LayerMerge.kt" to 1,
            "EditorController.kt" to 1,
            "storage/ImageExport.kt" to 1,
            "ui/editor/EditorActions.kt" to 1,
        )
        for ((path, count) in sites) {
            val text = File(root, path).readText()
            assertEquals(path, count, Regex("""MemoryGovernor\.admit\(""").findAll(text).count())
            assertTrue(path, text.contains("StabilityLabels19.NO_MEMORY_FOR"))
        }
        // The filter check is in the apply region (A7b), the folder check in the mergeFolder region (A4).
        assertTrue(region(File(root, "filters/FilterSession.kt").readText(), "v19-A7b apply").contains("MemoryGovernor.admit("))
        assertTrue(region(File(root, "EditorController.kt").readText(), "v19-A4 mergeFolder").contains("MemoryGovernor.admit("))
    }

    private fun region(text: String, marker: String): String {
        val start = text.indexOf("// region $marker")
        assertTrue(marker, start >= 0)
        val end = text.indexOf("// endregion", start)
        assertTrue(marker, end > start)
        return text.substring(start, end)
    }

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
}
