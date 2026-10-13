package com.brushwork.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.9 (D3, design 2.4.16): the region markers the ownership check reads (design 5.4, rule R).
 * Each marker exists exactly once in its file, is closed by the next `// endregion` (regions do
 * not nest) and holds the members listed in 2.4.16, found by name so later line shifts do not
 * matter. No other `// region` or `// endregion` line exists in the three files.
 */
class RegionMarkersTest {

    private class Expected(val file: String, val owner: String, val name: String, val members: List<String>)

    private val expected = listOf(
        Expected("EditorController.kt", "lead", "history", listOf("undo", "redo", "inToolHistory")),
        Expected("EditorController.kt", "A6", "invalidate", listOf("invalidateDoc")),
        Expected(
            "EditorController.kt", "A7b", "layerCap",
            listOf("maxHeap", "undoManager", "maxLayers", "effectiveLayerCount", "layerLimitMessage"),
        ),
        Expected("EditorController.kt", "A7b", "settle", listOf("settleVectorWork", "settleQueuedVectorWork")),
        Expected("EditorController.kt", "A3", "rasterizeMessage", listOf("rasterizeMessage")),
        Expected("EditorController.kt", "A6", "commitEdit", listOf("commitEdit")),
        Expected("EditorController.kt", "A4", "paste", listOf("paste")),
        Expected("EditorController.kt", "A4", "mergeDown", listOf("layerMerge", "mergeDown")),
        Expected("EditorController.kt", "A4", "mergeFolder", listOf("mergeFolder")),
        Expected("EditorController.kt", "A4", "layerFromFolder", listOf("layerFromFolder")),
        Expected("EditorController.kt", "A7b", "addMask", listOf("addMask")),
        Expected("EditorController.kt", "A7a", "savedSelectionWork", listOf("startSavedSelectionWork")),
        Expected("EditorController.kt", "A7b", "landSavedSelections", listOf("landSavedSelections", "landSavedSelection")),
        Expected("EditorController.kt", "A4", "convertToVectorLayer", listOf("convertToVectorLayer")),
        Expected("filters/FilterSession.kt", "A7b", "apply", listOf("apply", "cancelApply", "commitResult")),
        Expected("EditorSession.kt", "A7b", "saveOnLeaving", listOf("saveOnLeaving")),
    )

    /** The ownership check's own patterns (ownership-check-v19.ps1, rule R). */
    private val start = Regex("""^\s*//\s*region\s+v19-(\S+)\s+(\S+)""")
    private val end = Regex("""^\s*//\s*endregion\b""")
    private val anyMarker = Regex("""^\s*//\s*(end)?region\b""")

    private class Found(val owner: String, val name: String, val startLine: Int, var endLine: Int = 0, val body: MutableList<String> = mutableListOf())

    @Test
    fun everyMarkerExistsOnceIsClosedAndHoldsItsMembers() {
        val root = mainSources()
        assertNotNull("main sources not found from ${System.getProperty("user.dir")}", root)
        for ((file, wanted) in expected.groupBy { it.file }) {
            val lines = File(root, file).readLines()
            val found = parse(file, lines)
            for (e in wanted) {
                val same = found.filter { it.owner == e.owner && it.name == e.name }
                assertEquals("$file: `// region v19-${e.owner} ${e.name}` exists exactly once", 1, same.size)
                val r = same.single()
                assertTrue("$file: region v19-${e.owner} ${e.name} (line ${r.startLine}) is closed", r.endLine > r.startLine)
                for (m in e.members) {
                    val decl = Regex("""\b(fun|val|var)\s+(<[^>]*>\s*)?""" + Regex.escape(m) + """\b""")
                    assertTrue(
                        "$file: region v19-${e.owner} ${e.name} declares $m",
                        r.body.any { decl.containsMatchIn(it) },
                    )
                }
            }
            val names = wanted.map { "${it.owner} ${it.name}" }.toSet()
            for (r in found) {
                assertTrue("$file: unexpected region v19-${r.owner} ${r.name} at line ${r.startLine}", "${r.owner} ${r.name}" in names)
            }
        }
    }

    /** The regions of [lines]; fails on a nested region, a stray end or a marker line the check would not read. */
    private fun parse(file: String, lines: List<String>): List<Found> {
        val out = ArrayList<Found>()
        var open: Found? = null
        for ((i, t) in lines.withIndex()) {
            val s = start.find(t)
            when {
                s != null -> {
                    assertTrue("$file:${i + 1}: region inside region v19-${open?.owner} ${open?.name}", open == null)
                    open = Found(s.groupValues[1], s.groupValues[2], i + 1).also { out += it }
                }
                end.containsMatchIn(t) -> {
                    val o = open
                    assertNotNull("$file:${i + 1}: endregion without a region", o)
                    o!!.endLine = i + 1
                    open = null
                }
                anyMarker.containsMatchIn(t) -> throw AssertionError("$file:${i + 1}: a marker line the check does not read: ${t.trim()}")
                else -> open?.body?.add(t)
            }
        }
        assertTrue("$file: region v19-${open?.owner} ${open?.name} is never closed", open == null)
        return out
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
