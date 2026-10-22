package com.brushwork.paint.v110.a3

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.10 (R4, A3 step 1, design R4 (e)): a reader of an array's source that forgets
 * `ArraySpec.hideSource` draws a ghost of the source on the copies-only layer. Every use of
 * `drawSource(`, `v.objects` and `ArrayInstances.of` in `array/`, `engine/ArrayDraw.kt`,
 * `tools/array/` and `exchange/export/` (code, not comments) must carry a `// hideSource:` note
 * that says how it treats copy 0, and the number of such lines per file is the allow-list below:
 * a new reader fails here until it is checked and listed.
 */
class HideSourceReadersAuditTest {

    /** The reviewed reader lines per file (paths under the main sources). */
    private val allowList = mapOf(
        "array/ArrayOps.kt" to 4, // applyVector's limit and ids (effectiveVector leaves the source out), applyShape
        "array/ArrayRenders.kt" to 1, // unitsOf: a cost estimate
        "engine/ArrayDraw.kt" to 9, // drawWithArray, effectiveVector, instancesOf
        "tools/array/ArrayTool.kt" to 1, // the preview frame
        "exchange/export/ExportSceneBuilder.kt" to 2, // textCopies, arrayObjects (region v110-A3 arrayExport)
    )

    private val reader = Regex("""drawSource\(|\bv\.objects\b|\bArrayInstances\.of\b""")
    private val comment = Regex("""^\s*(\*|//|/\*)""")

    @Test
    fun everyReaderOfTheSourceIsReviewed() {
        val root = mainSources()
        assertNotNull("main sources not found from ${System.getProperty("user.dir")}", root)
        val files = listOf("array", "tools/array", "exchange/export").flatMap { dir ->
            File(root, dir).walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        } + File(root, "engine/ArrayDraw.kt")
        val found = LinkedHashMap<String, Int>()
        val unmarked = ArrayList<String>()
        for (f in files) {
            val rel = f.relativeTo(root!!).invariantSeparatorsPath
            f.readLines().forEachIndexed { i, line ->
                if (comment.containsMatchIn(line) || !reader.containsMatchIn(line)) return@forEachIndexed
                found[rel] = (found[rel] ?: 0) + 1
                if (!line.contains("// hideSource:")) unmarked += "$rel:${i + 1}: ${line.trim()}"
            }
        }
        assertTrue("readers without a '// hideSource:' note:\n" + unmarked.joinToString("\n"), unmarked.isEmpty())
        assertEquals("reader lines per file (review each new one, then list it)", allowList, found)
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
