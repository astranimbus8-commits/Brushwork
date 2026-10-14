package com.brushwork.paint.v19.a7a

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.9 A7a step 4 (design 2.4.13 and 3.2 A7a, R9): no coroutine of the main sources runs outside
 * `ErrorReporter.handler`, so in a release build an error in any job is kept as a report: in a
 * supervisor scope it is said and survived; in a composable's `rememberCoroutineScope` (no
 * supervisor: the error has ended the window's Recomposer) it is handed on and the app stops with
 * the report instead of leaving a dead screen (`ErrorReporterScopeTest`).
 *
 * Read from the sources (comments left out, a call followed across lines):
 * - every `CoroutineScope(...)` carries the handler and a `SupervisorJob()` (one failed job never
 *   ends the scope: the release handler goes on with it);
 * - every `rememberCoroutineScope` has the handler in its block (`rememberCoroutineScope { ErrorReporter.handler }`);
 * - `MainScope()` only with `+ ErrorReporter.handler`; never `GlobalScope`;
 * - a root `launch` or `async` on `lifecycleScope` or `viewModelScope` only with the handler.
 */
class UnguardedLaunchGateTest {

    @Test
    fun everyCoroutineOfTheMainSourcesRunsUnderTheHandler() {
        val root = mainSources()
        assertNotNull("the main sources are found from ${System.getProperty("user.dir")}", root)
        val found = ArrayList<Site>()
        for (f in root!!.walkTopDown().filter { it.isFile && it.extension == "kt" }.sortedBy { it.path }) {
            found += scan(f.readText()).map { it.copy(where = "${f.relativeTo(root).invariantSeparatorsPath}:${it.line}") }
        }
        val unguarded = found.filter { it.problem != null }.map { "${it.where}: ${it.problem}: ${it.code}" }
        assertTrue("coroutines outside ErrorReporter.handler (design 2.4.13):\n${unguarded.joinToString("\n")}", unguarded.isEmpty())
        // The app, editor and font scopes and the composables' scopes are all found.
        val scopes = found.count { it.kind == Kind.SCOPE }
        val remembered = found.count { it.kind == Kind.REMEMBERED }
        assertTrue("CoroutineScope( sites: $scopes\n$found", scopes >= 3)
        assertTrue("rememberCoroutineScope sites: $remembered\n$found", remembered >= 10)
        for (name in listOf("BrushworkApp.kt", "EditorSession.kt", "fonts/FontStore.kt")) {
            assertTrue("$name's scope is found: $found", found.any { it.kind == Kind.SCOPE && it.where.startsWith(name) })
        }
    }

    /** The scanner itself: what it flags and what it lets through, across lines and around comments. */
    @Test
    fun theGateFindsAnUnguardedScopeWhereverItIsWritten() {
        fun problems(src: String) = scan(src).mapNotNull { it.problem }

        assertEquals(emptyList<String>(), problems("val s = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + ErrorReporter.handler)"))
        assertEquals(
            emptyList<String>(),
            problems("val s = CoroutineScope(\n    SupervisorJob() +\n        Dispatchers.Default +\n        ErrorReporter.handler,\n)"),
        )
        assertEquals(listOf(NO_HANDLER), problems("val s = CoroutineScope(\n    SupervisorJob() +\n    Dispatchers.Default\n)\nval h = ErrorReporter.handler"))
        assertEquals(listOf(NO_SUPERVISOR), problems("val s = CoroutineScope(Job() + ErrorReporter.handler)"))
        assertEquals(listOf(NO_HANDLER), problems("val scope = rememberCoroutineScope()"))
        assertEquals(emptyList<String>(), problems("val scope = rememberCoroutineScope { ErrorReporter.handler }"))
        assertEquals(listOf(NO_HANDLER), problems("val scope = rememberCoroutineScope { Dispatchers.Main }"))
        assertEquals(listOf(GLOBAL), problems("GlobalScope.launch { work() }"))
        assertEquals(listOf(NO_HANDLER), problems("MainScope().launch { work() }"))
        assertEquals(emptyList<String>(), problems("val m = MainScope() + ErrorReporter.handler"))
        assertEquals(listOf(NO_HANDLER), problems("lifecycleScope.launch { work() }"))
        assertEquals(listOf(NO_HANDLER), problems("viewModelScope\n    .async(Dispatchers.IO) { work() }"))
        assertEquals(emptyList<String>(), problems("lifecycleScope.launch(ErrorReporter.handler) { work() }"))
        // Comments and strings are no code.
        assertEquals(emptyList<String>(), problems("// CoroutineScope(Job())\n/* GlobalScope.launch { } */\n/** rememberCoroutineScope() */\nval s = \"GlobalScope\""))
        // Types, imports and extension receivers are no scope.
        assertEquals(
            emptyList<String>(),
            problems("import kotlinx.coroutines.CoroutineScope\nimport androidx.compose.runtime.rememberCoroutineScope\nfun CoroutineScope.go(s: CoroutineScope) = Unit"),
        )
        // The line of a site is where it starts.
        assertEquals(listOf(3), scan("\n\nval s = CoroutineScope(\n Job()\n)").map { it.line })
    }

    // ------------------------------------------------------------------ the scanner

    private enum class Kind { SCOPE, REMEMBERED, MAIN_SCOPE, GLOBAL, LIFECYCLE }

    private data class Site(val kind: Kind, val line: Int, val code: String, val problem: String?, val where: String = "")

    private fun scan(source: String): List<Site> {
        val code = stripCommentsAndStrings(source)
        val out = ArrayList<Site>()
        fun lineOf(i: Int) = code.substring(0, i).count { it == '\n' } + 1
        fun excerpt(i: Int, j: Int) = code.substring(i, minOf(j, code.length)).replace(Regex("""\s+"""), " ").take(140)
        fun isImport(i: Int) = code.lastIndexOf('\n', i - 1).let { code.substring(it + 1, i) }.trimStart().startsWith("import ")

        for (m in Regex("""(?<![\w.])CoroutineScope\s*\(""").findAll(code)) {
            val end = closing(code, m.range.last, '(', ')')
            val args = code.substring(m.range.last, end)
            val problem = when {
                !args.contains(HANDLER) -> NO_HANDLER
                !args.contains("SupervisorJob(") -> NO_SUPERVISOR
                else -> null
            }
            out += Site(Kind.SCOPE, lineOf(m.range.first), excerpt(m.range.first, end + 1), problem)
        }
        for (m in Regex("""\brememberCoroutineScope\b""").findAll(code)) {
            if (isImport(m.range.first)) continue
            var i = m.range.last + 1
            while (i < code.length && code[i].isWhitespace()) i++
            if (i < code.length && code[i] == '(') {
                i = closing(code, i, '(', ')') + 1
                while (i < code.length && code[i].isWhitespace()) i++
            }
            val block = if (i < code.length && code[i] == '{') code.substring(i, closing(code, i, '{', '}') + 1) else ""
            out += Site(Kind.REMEMBERED, lineOf(m.range.first), excerpt(m.range.first, i + block.length), if (block.contains(HANDLER)) null else NO_HANDLER)
        }
        for (m in Regex("""(?<![\w.])MainScope\s*\(\s*\)""").findAll(code)) {
            val after = code.substring(m.range.last + 1).trimStart()
            val ok = Regex("""^\+\s*ErrorReporter\.handler\b""").containsMatchIn(after)
            out += Site(Kind.MAIN_SCOPE, lineOf(m.range.first), excerpt(m.range.first, m.range.last + 40), if (ok) null else NO_HANDLER)
        }
        for (m in Regex("""\bGlobalScope\b""").findAll(code)) {
            out += Site(Kind.GLOBAL, lineOf(m.range.first), excerpt(m.range.first, m.range.last + 40), GLOBAL)
        }
        for (m in Regex("""\b(lifecycleScope|viewModelScope)\s*\.\s*(launch|async)\b""").findAll(code)) {
            var i = m.range.last + 1
            while (i < code.length && code[i].isWhitespace()) i++
            val args = if (i < code.length && code[i] == '(') code.substring(i, closing(code, i, '(', ')') + 1) else ""
            out += Site(Kind.LIFECYCLE, lineOf(m.range.first), excerpt(m.range.first, i + args.length), if (args.contains(HANDLER)) null else NO_HANDLER)
        }
        return out.sortedBy { it.line }
    }

    /** The index of the bracket closing the one at [open] (the text's end when it never closes). */
    private fun closing(code: String, open: Int, a: Char, b: Char): Int {
        var depth = 0
        for (i in open until code.length) {
            when (code[i]) {
                a -> depth++
                b -> if (--depth == 0) return i
            }
        }
        return code.length - 1
    }

    /** [source] with comments and string literals blanked (newlines kept, so lines still count). */
    private fun stripCommentsAndStrings(source: String): String {
        val s = StringBuilder(source.length)
        var i = 0
        while (i < source.length) {
            val c = source[i]
            when {
                source.startsWith("//", i) -> {
                    while (i < source.length && source[i] != '\n') i++
                }
                source.startsWith("/*", i) -> {
                    var depth = 0
                    while (i < source.length) {
                        if (source.startsWith("/*", i)) { depth++; i += 2; continue }
                        if (source.startsWith("*/", i)) { depth--; i += 2; if (depth == 0) break; continue }
                        if (source[i] == '\n') s.append('\n')
                        i++
                    }
                }
                source.startsWith("\"\"\"", i) -> {
                    i += 3
                    while (i < source.length && !source.startsWith("\"\"\"", i)) { if (source[i] == '\n') s.append('\n'); i++ }
                    i += 3
                    s.append("\"\"")
                }
                c == '"' -> {
                    i++
                    while (i < source.length && source[i] != '"' && source[i] != '\n') { if (source[i] == '\\') i++; i++ }
                    i++
                    s.append("\"\"")
                }
                c == '\'' && i + 2 < source.length && (source[i + 2] == '\'' || source[i + 1] == '\\') -> {
                    // A char literal ('(' or '\n'): its bracket is no code.
                    i++
                    while (i < source.length && source[i] != '\'') { if (source[i] == '\\') i++; i++ }
                    i++
                    s.append("' '")
                }
                else -> { s.append(c); i++ }
            }
        }
        return s.toString()
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

    private companion object {
        const val HANDLER = "ErrorReporter.handler"
        const val NO_HANDLER = "no ErrorReporter.handler"
        const val NO_SUPERVISOR = "no SupervisorJob()"
        const val GLOBAL = "GlobalScope"
    }
}
