package com.brushwork.paint.testing

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.File

/**
 * v1.8 F0 (design §8.5): every test class that times anything is in the [Timing] category, so CI
 * can run the timing tests on their own, serially, while the rest runs in parallel forks.
 *
 * A test file "times" when its text has one of [CLOCK] (the retrying guards of `PerfBudget`, or a
 * raw clock read), or names a timing helper of another file ([TIMING_HELPERS]). Every test class
 * of such a file (a top-level class with `@RunWith` or an `@Test`) carries
 * `@Category(Timing::class)`. A file with no test class that times is a helper and is listed in
 * [TIMING_HELPERS] with the names its users call. A file that reads the clock for another reason
 * (a fresh name, a wait's timeout, a printed note) is on [NOT_TIMING] with its reason. An entry of
 * either list that no longer applies fails too, so both lists stay true.
 */
class TimingTaggedTest {

    /** The suite's sources: `src/test/java/com/brushwork/paint`, from the working directory up. */
    private fun testRoot(): File {
        var dir: File? = File(System.getProperty("user.dir")).absoluteFile
        while (dir != null) {
            for (candidate in listOf(File(dir, "src/test/java/com/brushwork/paint"), File(dir, "app/src/test/java/com/brushwork/paint"))) {
                if (candidate.isDirectory) return candidate
            }
            dir = dir.parentFile
        }
        throw AssertionError("src/test/java/com/brushwork/paint not found from ${System.getProperty("user.dir")}")
    }

    private fun sources(root: File): Map<String, String> =
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }
            .associate { it.relativeTo(root).invariantSeparatorsPath to it.readText() }

    @Test
    fun everyTestClassThatTimesIsInTheTimingCategory() {
        val files = sources(testRoot())
        assertTrue("the suite's sources were found (${files.size} files)", files.size > 500)
        val problems = check(files)
        if (problems.isNotEmpty()) fail("${problems.size} timing tag problems:\n" + problems.joinToString("\n") { "  $it" })
    }

    @Test
    fun theScannerReadsTestClassesAndTheirTags() {
        val text = """
            package x

            /** A test. */
            @RunWith(RobolectricTestRunner::class)
            @Config(
                sdk = [34],
            )
            @Category(Timing::class)
            class Tagged {
                fun helper() = 1
            }

            /**
             * Not tagged; its tests are below.
             */
            internal class Untagged {
                @Test
                fun a() {}
            }

            @Category(Timing::class)
            private object NotAClass

            class Plain {
                class Nested { @Test fun n() {} }
            }
        """.trimIndent()
        val classes = testClasses(text)
        assertEquals(listOf("Tagged" to true, "Untagged" to false, "Plain" to false), classes.map { it.name to it.tagged })
        // Nested classes are not top-level: Plain counts as a test class only through its text.
        assertEquals(listOf(true, true, true), classes.map { it.isTest })
        assertTrue(times("val t = System" + ".nanoTime()"))
        assertTrue(times("PerfBudget" + ".within(1.0) { }"))
        assertTrue(!times("import com.brushwork.paint.testing.PerfBudget\nval x = 1"))
    }

    @Test
    fun theCategoryNameIsTheOneCiFiltersOn() {
        // CI2's Gradle filter names the category by this string (design §8.5).
        assertEquals("com.brushwork.paint.testing.Timing", Timing::class.java.name)
    }

    /** A top-level class of a file: its name, whether it holds tests, whether it is tagged. */
    data class TestClass(val name: String, val isTest: Boolean, val tagged: Boolean)

    companion object {
        /** The marks of a file that times (split so this file does not match them). */
        val CLOCK: List<String> = listOf("PerfBudget" + ".", "System" + ".nanoTime", "measure" + "NanoTime", "measure" + "TimeMillis")

        /**
         * Helper files that time for their users (no test class of their own): path (under
         * `com/brushwork/paint`) to the names whose users time through them.
         */
        val TIMING_HELPERS: Map<String, List<String>> = mapOf(
            "qa16/LiveQa.kt" to listOf("exactFrameMs", "v15StageMs", "drawWithoutOverlays"),
            "qa16/MasksLiveFlow.kt" to listOf("assertCheap"),
            "tools/vector/VectorPerfHarness.kt" to listOf("VectorPerfHarness"),
        )

        /** Files that read the clock for a reason other than timing: path to the reason. */
        val NOT_TIMING: Map<String, String> = mapOf(
            "audit/RequestCoverageV17UiTest.kt" to "PerfBudget scales a UI wait's timeout; no time is asserted",
            "audit/RequestCoverageV18UiTest.kt" to "PerfBudget scales a UI wait's timeout (WAIT_MS); no time is asserted",
            "filters/art/ArtFiltersBenchmark.kt" to "an @Ignore'd manual benchmark: prints times, asserts none",
            "filters/style/StyleVisualDump.kt" to "a visual dump: prints the filters' times, asserts none",
            "fonts/FontImportRobolectricTest.kt" to "the clock names a fresh fonts directory",
            "qa/VectorAsyncQaTest.kt" to "PerfBudget scales the slowed render worker's delay; no time is asserted",
            "qa17/Qa17ArrayUi.kt" to "PerfBudget scales the UI wait's timeout (WAIT_MS)",
            "qa17/Qa17PointsKit.kt" to "PerfBudget scales the UI wait's timeout (WAIT_MS)",
            "qa17/Qa17CompatPendingWorkOnStopUiTest.kt" to "notes the sections' and onStop's times, asserts none",
            "qa17/Qa17CompatRoundTripRobolectricTest.kt" to "prints the round trips' times, asserts none",
            "qa17/Qa17CompatSessionUiTest.kt" to "notes the sections' and the restore's times, asserts none",
            "qa17/Qa17CompatV16GoldensRobolectricTest.kt" to "prints each v1.6 golden's time, asserts none",
            "qa17/Qa17LayersFolderClippingUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersFoldersUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersGalleryUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersSavedSelectionsUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersSelectionsSymmetryUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersSymmetryUiTest.kt" to "notes the sections' times, asserts none",
            "qa17/Qa17LayersSymmetryVectorUiTest.kt" to "notes the sections' times, asserts none",
            "testing/PerfBudgetTest.kt" to "tests the guards on a scripted clock; its real-clock checks use budgets no run misses (1e9 ms) or meets (-1 ms)",
            "vector/VectorPropertyRobolectricTest.kt" to "the clock names the generated document",
        )

        private val CLASS = Regex("""^(?:(?:public|internal|private|abstract|open|data|sealed|inner)\s+)*class\s+(\w+)""")
        private val TOP = Regex("""^(?:(?:public|internal|private|protected|abstract|open|data|sealed|enum|annotation|const|inline|tailrec|suspend|operator|infix)\s+)*(?:class|object|interface|fun|val|var|typealias)\b""")
        private const val TAG = "@Category(Timing::class)"
        private const val SELF = "testing/TimingTaggedTest.kt"

        /** Whether [text] has one of [CLOCK]. */
        fun times(text: String): Boolean = CLOCK.any { it in text }

        /** The top-level classes of [text], each with whether it holds tests and is tagged. */
        fun testClasses(text: String): List<TestClass> {
            val lines = text.lines()
            val out = ArrayList<TestClass>()
            for ((i, line) in lines.withIndex()) {
                val name = CLASS.find(line)?.groupValues?.get(1) ?: continue
                // The annotations right above it (a multi-line one included), up to a KDoc or a blank line.
                val annotations = ArrayList<String>()
                var j = i - 1
                while (j >= 0) {
                    val l = lines[j]
                    val t = l.trim()
                    if (t.isEmpty() || t.startsWith("*") || t.endsWith("*/") || t.startsWith("//") || t.startsWith("/*")) break
                    if (!(l.startsWith("@") || l.startsWith(" ") || l.startsWith(")") || l.startsWith("]"))) break
                    annotations += l
                    j--
                }
                // Its text, up to the next top-level declaration.
                var k = i + 1
                while (k < lines.size && !TOP.containsMatchIn(lines[k]) && !lines[k].startsWith("@")) k++
                val body = lines.subList(i, k).joinToString("\n")
                val isTest = annotations.any { it.startsWith("@RunWith") } || Regex("""@Test\b""").containsMatchIn(body)
                out += TestClass(name, isTest, annotations.any { it.trim() == TAG })
            }
            return out
        }

        /** Every problem of [files] (path under `com/brushwork/paint` to its text); empty when all is well. */
        fun check(files: Map<String, String>): List<String> {
            val problems = ArrayList<String>()
            // The lists themselves: every entry names a file that still reads the clock.
            for ((path, reason) in NOT_TIMING) {
                val text = files[path]
                when {
                    reason.isBlank() -> problems += "NOT_TIMING $path: no reason"
                    text == null -> problems += "NOT_TIMING $path: no such file"
                    !times(text) -> problems += "NOT_TIMING $path: reads no clock any more (remove the entry)"
                    testClasses(text).any { it.tagged } -> problems += "NOT_TIMING $path: tagged Timing as well (one or the other)"
                }
            }
            for ((path, names) in TIMING_HELPERS) {
                val text = files[path]
                when {
                    text == null -> problems += "TIMING_HELPERS $path: no such file"
                    path in NOT_TIMING -> problems += "TIMING_HELPERS $path: on NOT_TIMING as well"
                    testClasses(text).any { it.isTest } -> problems += "TIMING_HELPERS $path: has a test class (tag it instead)"
                    !times(text) -> problems += "TIMING_HELPERS $path: reads no clock any more (remove the entry)"
                    else -> for (n in names) {
                        if (!Regex("""\b(?:fun|class|object|val|var)\s+(?:\w+\.)?$n\b""").containsMatchIn(text)) problems += "TIMING_HELPERS $path: declares no $n"
                    }
                }
            }
            // Every file that times: its test classes tagged, or it is a listed helper.
            val helperNames = TIMING_HELPERS.flatMap { (path, names) -> names.map { path to Regex("""\b$it\b""") } }
            for ((path, text) in files.toSortedMap()) {
                if (path in NOT_TIMING) continue
                // This file names the helpers in its list without timing through them.
                val via = if (path == SELF) emptyList() else helperNames.filter { (helper, name) -> helper != path && name.containsMatchIn(text) }.map { it.first }.distinct()
                if (!times(text) && via.isEmpty()) continue
                val why = if (times(text)) "reads the clock" else "times through ${via.joinToString()}"
                val classes = testClasses(text).filter { it.isTest }
                if (classes.isEmpty()) {
                    if (path !in TIMING_HELPERS) problems += "$path $why but has no test class: add it to TIMING_HELPERS (or NOT_TIMING with a reason)"
                    continue
                }
                for (c in classes) if (!c.tagged) problems += "$path: ${c.name} $why but is not $TAG (or put the file on NOT_TIMING with a reason)"
            }
            return problems
        }
    }
}
