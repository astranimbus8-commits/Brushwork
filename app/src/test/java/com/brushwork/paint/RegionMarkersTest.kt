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
 * matter. No other `// region v19-` line exists in the three files.
 *
 * v1.10 (F0, design 3.2 and 4.2): the v1.10 markers as well (`ownership-check-v110.ps1`, rule R).
 * A v1.10 name may repeat for one owner (blocks, keyed by owner and name): each block holds its
 * members in file order. No other `// region v110-` line exists in any main source, no v1.10 block
 * sits within three lines of another region, and `docs/ARCHITECTURE.md` holds each area's
 * `<!-- v110-<X> -->` ... `<!-- /v110-<X> -->` block once, under its heading (design 6.1).
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

    /**
     * A v1.10 region: one list of members per block, in file order. A member is a declared name
     * (`fun`, `val` or `var`), `init` (an `init {` block) or `text:<words>` (a line holding those
     * words, for a block around statements or a comment).
     */
    private class Expected110(val file: String, val owner: String, val name: String, val blocks: List<List<String>>)

    private val expected110 = listOf(
        Expected110("EditorController.kt", "A8", "activate", listOf(listOf("selectLayer"))),
        Expected110(
            "EditorController.kt", "A11", "canvasOps",
            listOf(listOf("flipLayerNow"), listOf("clearLayer"), listOf("applyMask")),
        ),
        Expected110("EditorController.kt", "A3", "arrayBehind", listOf(listOf("arrayBehindAvailable", "arrayBehind"))),
        Expected110("EditorController.kt", "A4", "startFilter", listOf(listOf("startFilter"))),
        Expected110("EditorSession.kt", "A10", "session", listOf(listOf("text:swap session directory"), listOf("close"))),
        Expected110("EditorSession.kt", "A11", "open", listOf(listOf("init"))),
        Expected110("EditorSession.kt", "A11", "save", listOf(listOf("save"))),
        Expected110("engine/LayerStructure.kt", "A7", "blank", listOf(listOf("insert", "place", "placed"))),
        Expected110(
            "engine/LayerStructure.kt", "A8", "duplicate",
            listOf(listOf("duplicate", "duplicateLayer"), listOf("duplicateFolder")),
        ),
        Expected110("exchange/export/ExportSceneBuilder.kt", "A3", "arrayExport", listOf(listOf("textCopies"), listOf("arrayObjects"))),
        Expected110(
            "exchange/export/ExportSceneBuilder.kt", "A11", "layerExport",
            listOf(listOf("layerImage", "contentBounds", "pixelImage", "layerMask", "maskOf", "maskBackground", "luma", "compositeImage")),
        ),
        Expected110("tools/text/TextTool.kt", "A2", "colors", listOf(listOf("objectColors"))),
        Expected110("tools/transform/DataLift.kt", "A8", "pins", listOf(listOf("release"), listOf("lift"))),
        Expected110("tools/vector/CurveTool.kt", "A2", "colors", listOf(listOf("objectColors"))),
        Expected110("tools/vector/CurveTool.kt", "A5", "corners", listOf(listOf("pointCorners"))),
        Expected110(
            "tools/vector/CurveTool.kt", "A5", "snap",
            listOf(listOf("groupDown", "groupMove", "groupUp"), listOf("beginSnap", "beginSnapWithout")),
        ),
        Expected110("tools/vector/CurveTool.kt", "A6", "openObject", listOf(listOf("openObject"))),
        Expected110("tools/vector/ShapeTool.kt", "A2", "colors", listOf(listOf("objectColors"))),
        Expected110("tools/vector/ShapeTool.kt", "A5", "corners", listOf(listOf("pointCorners"))),
        Expected110("tools/vector/ShapeTool.kt", "A5", "snap", listOf(listOf("beginSnap"), listOf("dragGroup", "dragGizmo"))),
        Expected110("tools/vector/ShapeTool.kt", "A6", "open", listOf(listOf("editShapeObject"))),
        Expected110("ui/layers/LayerControls.kt", "A3", "arrayMenu", listOf(listOf("text:if (row.hasArray)", "text:ArrayLabels.EDIT"))),
        Expected110(
            "ui/vector/CurveStrips.kt", "A5", "sharpItems",
            listOf(listOf("text:items += sharpItem("), listOf("text:items += sharpItem("), listOf("sharpItem")),
        ),
        Expected110("ui/vector/CurveStrips.kt", "A2", "paintMode", listOf(listOf("strokeKindItem"))),
        Expected110("ui/vector/CurveToolOptions.kt", "A2", "paintMode", listOf(listOf("text:SectionHeader(\"Stroke\")"))),
        Expected110("ui/vector/CurveToolOptions.kt", "A6", "turnIntoShape", listOf(listOf("text:\"Turn into shape\""))),
        Expected110("ui/vector/CurveToolOptions.kt", "A5", "cornerChips", listOf(listOf("text:\"Sharp corner\""))),
        Expected110("ui/vector/ShapeStrip.kt", "A2", "paintMode", listOf(listOf("text:STYLE_LABEL"))),
        Expected110("ui/vector/ShapeStrip.kt", "A5", "sharpItems", listOf(listOf("text:SMOOTH_LABEL", "text:setSelectedSmooth"))),
        Expected110("vector/select/VectorObjectSelection.kt", "A1", "overlay", listOf(listOf("drawOverlay"))),
        Expected110(
            "vector/VectorLayers.kt", "A1", "copies",
            listOf(listOf("hitTest"), listOf("beginEdit"), listOf("planEdit"), listOf("text:MAX_FLOATING"), listOf("renderEdit")),
        ),
    )

    /** The ARCHITECTURE blocks of design 6.1: the area and the title of the heading above its block. */
    private val architecture = listOf(
        "A1" to "Mirror sides: tap cycle, linked isometries, detach, delete one side",
        "A2" to "Object colors: the sheet, the delegates, the pill cell, one paint-mode control",
        "A3" to "Array behind and Recolored arrays",
        "A4" to "Change Drawing Color on objects: `colorMapper` and object mode",
        "A5" to "Group snapping and the sharp / smooth cell",
        "A6" to "Edit and Turn into shape on the bars",
        "A7" to "Budgets J and R, `LayerBudgetV110`, limits and the layers panel",
        "A8" to "Tiles, residency, activation and undo on tiles",
        "A9" to "The compositor on tiles, projections, `PixelReads`, thumbnails",
        "A10" to "Swap, the automatic check, safety nets, \"Layer storage\" settings, `EngineStats`",
        "A11" to "Streamed load and save, format 4, multi-layer operations, import and export",
    )

    /** The ownership checks' own patterns (ownership-check-v19.ps1 and ownership-check-v110.ps1, rule R). */
    private val start = Regex("""^\s*//\s*region\s+(v19|v110)-(\S+)\s+(\S+)""")
    private val end = Regex("""^\s*//\s*endregion\b""")
    private val anyMarker = Regex("""^\s*//\s*(end)?region\b""")
    private val start110 = Regex("""^\s*//\s*region\s+v110-""")
    private val mdOpen = Regex("""^\s*<!--\s*v110-([^\s/]+)\s*-->""")
    private val mdClose = Regex("""^\s*<!--\s*/v110-([^\s/]+)\s*-->""")

    private class Found(
        val version: String,
        val owner: String,
        val name: String,
        val startLine: Int,
        var endLine: Int = 0,
        val body: MutableList<String> = mutableListOf(),
    )

    @Test
    fun everyMarkerExistsOnceIsClosedAndHoldsItsMembers() {
        val root = mainSources()
        assertNotNull("main sources not found from ${System.getProperty("user.dir")}", root)
        for ((file, wanted) in expected.groupBy { it.file }) {
            val lines = File(root, file).readLines()
            val found = parse(file, lines).filter { it.version == "v19" }
            for (e in wanted) {
                val same = found.filter { it.owner == e.owner && it.name == e.name }
                assertEquals("$file: `// region v19-${e.owner} ${e.name}` exists exactly once", 1, same.size)
                val r = same.single()
                assertTrue("$file: region v19-${e.owner} ${e.name} (line ${r.startLine}) is closed", r.endLine > r.startLine)
                for (m in e.members) {
                    assertTrue("$file: region v19-${e.owner} ${e.name} declares $m", holds(r, m))
                }
            }
            val names = wanted.map { "${it.owner} ${it.name}" }.toSet()
            for (r in found) {
                assertTrue("$file: unexpected region v19-${r.owner} ${r.name} at line ${r.startLine}", "${r.owner} ${r.name}" in names)
            }
        }
    }

    @Test
    fun everyV110MarkerHoldsItsMembersBlockByBlock() {
        val root = requireNotNull(mainSources()) { "main sources not found from ${System.getProperty("user.dir")}" }
        for ((file, wanted) in expected110.groupBy { it.file }) {
            val found = parse(file, File(root, file).readLines()).filter { it.version == "v110" }
            for (e in wanted) {
                val blocks = found.filter { it.owner == e.owner && it.name == e.name }
                assertEquals("$file: `// region v110-${e.owner} ${e.name}` has ${e.blocks.size} block(s)", e.blocks.size, blocks.size)
                for ((k, r) in blocks.withIndex()) {
                    assertTrue("$file: region v110-${e.owner} ${e.name} (line ${r.startLine}) is closed", r.endLine > r.startLine)
                    for (m in e.blocks[k]) {
                        assertTrue("$file: block ${k + 1} of region v110-${e.owner} ${e.name} (line ${r.startLine}) holds $m", holds(r, m))
                    }
                }
            }
            val names = wanted.map { "${it.owner} ${it.name}" }.toSet()
            for (r in found) {
                assertTrue("$file: unexpected region v110-${r.owner} ${r.name} at line ${r.startLine}", "${r.owner} ${r.name}" in names)
            }
        }
    }

    @Test
    fun noOtherSourceHasV110MarkersAndBlocksStandApart() {
        val root = requireNotNull(mainSources()) { "main sources not found from ${System.getProperty("user.dir")}" }
        val listed = expected110.map { it.file }.toSet()
        val carrying = root.walkTopDown()
            .filter { it.isFile && it.extension == "kt" && it.readLines().any { l -> start110.containsMatchIn(l) } }
            .map { it.relativeTo(root).invariantSeparatorsPath }
            .toSet()
        assertEquals("the files that carry `// region v110-` markers", listed.sorted(), carrying.sorted())
        for (file in listed) {
            val regions = parse(file, File(root, file).readLines()).sortedBy { it.startLine }
            for ((a, b) in regions.zipWithNext()) {
                if (a.version != "v110" && b.version != "v110") continue
                val between = b.startLine - a.endLine - 1
                assertTrue(
                    "$file: region ${b.version}-${b.owner} ${b.name} (line ${b.startLine}) is $between line(s) from " +
                        "region ${a.version}-${a.owner} ${a.name} (ends line ${a.endLine}); at least 3",
                    between >= 3,
                )
            }
        }
    }

    @Test
    fun architectureHoldsEachAreaBlockOnceUnderItsHeading() {
        val root = requireNotNull(mainSources()) { "main sources not found from ${System.getProperty("user.dir")}" }
        val doc = generateSequence(root) { it.parentFile }.map { File(it, "docs/ARCHITECTURE.md") }.firstOrNull { it.isFile }
        assertNotNull("docs/ARCHITECTURE.md not found above $root", doc)
        val lines = doc!!.readLines()
        val opens = HashMap<String, Int>()
        val closes = HashMap<String, Int>()
        var open: String? = null
        for ((i, t) in lines.withIndex()) {
            val o = mdOpen.find(t)
            val c = mdClose.find(t)
            when {
                o != null -> {
                    val x = o.groupValues[1]
                    assertTrue("ARCHITECTURE.md:${i + 1}: <!-- v110-$x --> inside <!-- v110-$open -->", open == null)
                    assertTrue("ARCHITECTURE.md:${i + 1}: a second <!-- v110-$x -->", x !in opens)
                    opens[x] = i + 1
                    open = x
                }
                c != null -> {
                    val x = c.groupValues[1]
                    assertEquals("ARCHITECTURE.md:${i + 1}: <!-- /v110-$x --> closes the open block", open, x)
                    assertTrue("ARCHITECTURE.md:${i + 1}: a second <!-- /v110-$x -->", x !in closes)
                    closes[x] = i + 1
                    open = null
                }
            }
        }
        assertTrue("ARCHITECTURE.md: <!-- v110-$open --> is never closed", open == null)
        assertEquals("ARCHITECTURE.md: the v1.10 area blocks", architecture.map { it.first }.toSet(), opens.keys)
        var previous = 0
        for ((area, title) in architecture) {
            val at = opens.getValue(area)
            assertTrue("ARCHITECTURE.md: the block of $area follows the block before it", at > previous)
            previous = at
            val heading = "### $title ($area)"
            val above = lines.subList(maxOf(0, at - 4), at - 1).map { it.trim() }
            assertTrue("ARCHITECTURE.md:$at: `$heading` is the heading above <!-- v110-$area -->", heading in above)
        }
    }

    /** [r] declares [m], holds an `init {` block for "init", or holds a line with the words of "text:...". */
    private fun holds(r: Found, m: String): Boolean = when {
        m.startsWith("text:") -> r.body.any { m.removePrefix("text:") in it }
        m == "init" -> r.body.any { Regex("""^\s*init\s*\{""").containsMatchIn(it) }
        else -> {
            val decl = Regex("""\b(fun|val|var)\s+(<[^>]*>\s*)?""" + Regex.escape(m) + """\b""")
            r.body.any { decl.containsMatchIn(it) }
        }
    }

    /** The regions of [lines]; fails on a nested region, a stray end or a marker line the checks would not read. */
    private fun parse(file: String, lines: List<String>): List<Found> {
        val out = ArrayList<Found>()
        var open: Found? = null
        for ((i, t) in lines.withIndex()) {
            val s = start.find(t)
            when {
                s != null -> {
                    assertTrue("$file:${i + 1}: region inside region ${open?.version}-${open?.owner} ${open?.name}", open == null)
                    open = Found(s.groupValues[1], s.groupValues[2], s.groupValues[3], i + 1).also { out += it }
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
        assertTrue("$file: region ${open?.version}-${open?.owner} ${open?.name} is never closed", open == null)
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
