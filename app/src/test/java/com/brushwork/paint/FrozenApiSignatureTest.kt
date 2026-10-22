package com.brushwork.paint

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File

/**
 * v1.10 F0 (design 3.2, 6.3 and `v110-frozen-api.md` 4.3; lead, frozen): the declarations the
 * foundation froze for the areas keep their signatures. Each [Pin] is a declaration as written
 * (whitespace, line breaks and a trailing comma do not matter) that must appear in its file as
 * code, not in a comment or a string, followed by its body, `=` or the next declaration, and
 * inside the blocks it names. Each [Members] lists the non-private declarations of an interface,
 * class or object (annotations left out, bodies and initializers cut), or the entries of an enum,
 * exactly and in order. A body may change; a signature changes only with the lead (a LeadRequest),
 * together with its row here.
 *
 * The rows are in [PARTS]: [F0] here (the pill cells, `ObjectActions`, `CurveTool.openObject` and
 * `kindOf`, the limit seam, the object colors and corners, `TapCycle`, `ObjectColorSlots`, the
 * paint-mode controls, the color picker's preview and `ColorUtils.recolor`) and [F1] (design 3.3:
 * the engine's pixel stores, tiles, residency, swap, probes, ledger and budgets, `EngineConfig`,
 * the notices, `EngineStats`, `EngineStartup`, `CheckStore`, `PixelReads`, the four `ui/engine`
 * composables, the check and state records, and the seams F1 added to `Layer`, `Document`, the
 * governor, `BlendModes`, `ProjectFormat`, the settings, the gallery notices, `NewArtwork` and
 * `EditorController`). The matcher is checked on a made-up source first. JVM (reads the main sources).
 */
class FrozenApiSignatureTest {

    /** [signature] is declared in [file] (relative to `com/brushwork/paint`), inside [inside] (outermost first). */
    class Pin(val file: String, val signature: String, val inside: List<String> = emptyList())

    /** The declaration [header] in [file] has exactly [members] (its non-private declarations, or with [enum] its entries). */
    class Members(val file: String, val header: String, val members: List<String>, val enum: Boolean = false)

    /** One part of the frozen API. */
    class Part(val name: String, val pins: List<Pin>, val members: List<Members>)

    @Test
    fun everyPinnedSignatureIsDeclared() {
        val failures = mutableListOf<String>()
        for (part in PARTS) for (p in part.pins) {
            val src = source(p.file)
            val found = Matcher.find(src, p.signature)
            when {
                found.isEmpty() -> failures += "${part.name} ${p.file}: not declared: ${p.signature}"
                p.inside.isNotEmpty() && found.none { Matcher.isInside(src, it, p.inside) } ->
                    failures += "${part.name} ${p.file}: ${p.signature} is not inside ${p.inside.joinToString(" > ")}"
            }
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun everyPinnedTypeHasExactlyItsMembers() {
        val failures = mutableListOf<String>()
        for (part in PARTS) for (m in part.members) {
            val src = source(m.file)
            val bodies = Matcher.blocks(src, m.header)
            if (bodies.size != 1) {
                failures += "${part.name} ${m.file}: '${m.header}' found ${bodies.size} times"
                continue
            }
            val actual = Matcher.members(src, bodies.single(), m.enum)
            val expected = m.members.map { Matcher.normalize(it).first }
            if (actual != expected) failures += "${part.name} ${m.file} ${m.header}:\n  expected $expected\n  actual   $actual"
        }
        assertTrue(failures.joinToString("\n"), failures.isEmpty())
    }

    @Test
    fun theF0PartNamesEveryFileF0Froze() {
        val files = (F0.pins.map { it.file } + F0.members.map { it.file }).toSet()
        assertEquals(
            setOf(
                "ui/tools/PillColorCell.kt", "ui/tools/PillSharpCell.kt", "vector/select/ObjectActions.kt",
                "tools/vector/CurveTool.kt", "engine/LayerBudget.kt", "engine/LayerBudgetV19.kt",
                "tools/ObjectColorEditor.kt", "tools/points/PointCornerEditor.kt", "vector/select/TapCycle.kt",
                "vector/ObjectColorSlots.kt", "ui/common/PaintModeControls.kt", "ui/color/ColorPickerDialog.kt",
                "core/ColorUtils.kt",
            ),
            files,
        )
    }

    @Test
    fun theF1PartNamesEveryFileF1Froze() {
        val files = (F1.pins.map { it.file } + F1.members.map { it.file }).toSet()
        assertEquals(
            setOf(
                "engine/tiles/Tile.kt", "engine/tiles/PixelStore.kt", "engine/tiles/PixelPin.kt",
                "engine/tiles/Residency.kt", "engine/tiles/Projection.kt", "engine/tiles/SwapStore.kt",
                "engine/tiles/PixelReads.kt", "engine/budget/MemoryProbe.kt", "engine/budget/AndroidMemoryProbe.kt",
                "engine/budget/AndroidStorageProbe.kt", "engine/budget/AndroidExitInfo.kt", "engine/budget/PixelLedger.kt",
                "engine/budget/MemoryBudget.kt", "engine/budget/LayerBudgetV110.kt", "engine/EngineConfig.kt",
                "engine/EngineNotice.kt", "engine/EngineStats.kt", "engine/EngineStartup.kt", "engine/check/CheckStore.kt",
                "ui/engine/EngineCheckHost.kt", "ui/engine/EngineNoticeCard.kt", "ui/engine/LayerStorageSection.kt",
                "ui/engine/EngineEditorChrome.kt", "storage/engine/EngineCheckResult.kt", "storage/engine/EngineState.kt",
                "model/Document.kt", "engine/MemoryGovernor.kt", "engine/BlendModes.kt", "storage/ProjectFile.kt",
                "AppSettings.kt", "UiPrefs.kt", "ui/gallery/GalleryNotices.kt", "exchange/NewArtwork.kt",
                "EditorController.kt",
            ),
            files,
        )
    }

    @Test
    fun theMatcherFindsDeclarationsAndNoticesChanges() {
        val q = "\""
        val src = Matcher.Source(
            listOf(
                "package x",
                "// fun commented(a: Int): Int",
                "val s = ${q}fun inString(a: Int): Int$q",
                "/** A comment with a { brace /* nested */ and a string $q */",
                "object Box {",
                "    @Composable",
                "    fun widget(",
                "        a: Int,",
                "        b: (Int) -> Unit = { },",
                "    ) {",
                "        val t = $q\${a} } { \\$q $q",
                "        val c = '}'",
                "    }",
                "    fun plain(a: Int): Int =",
                "        a + 1",
                "    private fun hidden() = Unit",
                "    val p: Int get() = 2",
                "    fun set(a: Int): Int = a",
                "    companion object { const val K = 1 }",
                "}",
                "enum class E { A, B, C, }",
                "interface I {",
                "    val v: Int",
                "    fun f(x: Int): String?",
                "}",
            ).joinToString("\n"),
        )
        val widget = "@Composable fun widget(a: Int, b: (Int) -> Unit = { })"
        assertEquals(1, Matcher.find(src, widget).size)
        assertEquals("line breaks and the trailing comma do not matter", 1, Matcher.find(src, "fun widget(\n a: Int,\n b: (Int) -> Unit = { },\n)").size)
        assertTrue(Matcher.isInside(src, Matcher.find(src, widget).single(), listOf("object Box")))
        assertTrue(Matcher.find(src, "fun plain(a: Int): Int").isNotEmpty())
        // Changes are noticed.
        for (changed in listOf("fun plain(a: Int): In", "fun plain(a: Int): Int?", "un plain(a: Int): Int", "fun plain(a: Long): Int", "fun widget(a: Int)")) {
            assertTrue(changed, Matcher.find(src, changed).isEmpty())
        }
        // Comments and strings are not code.
        assertTrue(Matcher.find(src, "fun commented(a: Int): Int").isEmpty())
        assertTrue(Matcher.find(src, "fun inString(a: Int): Int").isEmpty())
        // Members: annotations left out, bodies, initializers and getters cut, private ones skipped.
        assertEquals(
            listOf("fun widget(a: Int, b: (Int) -> Unit = { })", "fun plain(a: Int): Int", "val p: Int", "fun set(a: Int): Int", "companion object"),
            Matcher.members(src, Matcher.blocks(src, "object Box").single(), enum = false),
        )
        assertEquals(listOf("A", "B", "C"), Matcher.members(src, Matcher.blocks(src, "enum class E").single(), enum = true))
        assertEquals(listOf("val v: Int", "fun f(x: Int): String?"), Matcher.members(src, Matcher.blocks(src, "interface I").single(), enum = false))
        // Blocks nest.
        val k = Matcher.find(src, "const val K = 1").single()
        assertTrue(Matcher.isInside(src, k, listOf("object Box", "companion object")))
        assertTrue(!Matcher.isInside(src, k, listOf("interface I")))
        assertTrue("a header is a whole name", Matcher.blocks(src, "object Bo").isEmpty())
    }

    /** Reads Kotlin source text: comments, string contents, declarations and their blocks. */
    internal object Matcher {
        private const val CODE = 0
        private const val STRING = 1
        private const val RAW = 2

        private val MODIFIERS = setOf(
            "public", "internal", "protected", "private", "override", "open", "abstract", "final", "sealed", "data",
            "enum", "inner", "companion", "const", "lateinit", "inline", "operator", "infix", "suspend", "tailrec",
            "external", "value", "annotation", "expect", "actual",
        )
        private val KEYWORDS = setOf("fun", "val", "var", "class", "interface", "object", "typealias", "constructor", "init")
        private val ANNOTATION = Regex("""^@[\w.:]+(\([^()]*(\([^()]*\)[^()]*)*\))?\s*""")

        /** [raw] with [code] (comments blanked) and [bare] (string and char contents blanked too), index for index; [norm] is [code] normalized, [map] its indices in [raw]. */
        class Source(val raw: String) {
            val code: String
            val bare: String
            val norm: String
            val map: IntArray

            init {
                val (c, b) = scan(raw)
                code = c
                bare = b
                val (n, m) = normalize(c)
                norm = n
                map = m
            }
        }

        /** Collapses whitespace; no space after `(` / `[` or before `)` / `]` / `,`; no comma before `)` / `]`. */
        fun normalize(s: String): Pair<String, IntArray> {
            val out = StringBuilder()
            val map = ArrayList<Int>()
            var space = false
            for (k in s.indices) {
                val ch = s[k]
                if (ch.isWhitespace()) {
                    space = out.isNotEmpty()
                    continue
                }
                if (space) {
                    space = false
                    val last = out.last()
                    if (last != '(' && last != '[' && ch != ')' && ch != ']' && ch != ',') {
                        out.append(' ')
                        map += k - 1
                    }
                }
                if ((ch == ')' || ch == ']') && out.isNotEmpty() && out.last() == ',') {
                    out.setLength(out.length - 1)
                    map.removeAt(map.lastIndex)
                }
                out.append(ch)
                map += k
            }
            return out.toString() to map.toIntArray()
        }

        /** The indices in [Source.raw] where [signature] is declared (as code, a whole declaration). */
        fun find(src: Source, signature: String): List<Int> {
            val pin = normalize(signature).first
            return occurrences(src, pin) { after -> after == null || after == ' ' || after == '{' || after == '=' || after == ';' || after == '}' }.map { src.map[it] }
        }

        /** The bodies (between the braces) of the declarations [header] (a whole name; constructor parameters skipped) within [within]. */
        fun blocks(src: Source, header: String, within: IntRange = src.raw.indices): List<IntRange> {
            val pin = normalize(header).first
            val out = mutableListOf<IntRange>()
            for (at in occurrences(src, pin) { after -> after == null || !(after.isLetterOrDigit() || after == '_') }) {
                val start = src.map[at]
                if (start !in within) continue
                var k = src.map[at + pin.length - 1] + 1
                var paren = 0
                while (k < src.bare.length) {
                    val c = src.bare[k]
                    if (c == '(') paren++ else if (c == ')') paren-- else if (c == '{' && paren == 0) break
                    k++
                }
                if (k >= src.bare.length) continue
                val open = k
                var depth = 0
                while (k < src.bare.length) {
                    val c = src.bare[k]
                    if (c == '{') depth++ else if (c == '}') { depth--; if (depth == 0) break }
                    k++
                }
                if (k < src.bare.length) out += (open + 1) until k
            }
            return out
        }

        /** True when [at] lies in a chain of blocks [headers] (outermost first). */
        fun isInside(src: Source, at: Int, headers: List<String>, within: IntRange = src.raw.indices): Boolean {
            if (headers.isEmpty()) return true
            return blocks(src, headers.first(), within).any { at in it && isInside(src, at, headers.drop(1), it) }
        }

        /** The non-private declarations at the top of [body] (heads only), or with [enum] its entries; normalized. */
        fun members(src: Source, body: IntRange, enum: Boolean): List<String> {
            val lines = mutableListOf<String>()
            var depth = 0
            var from = body.first
            for (k in body) {
                when (src.bare[k]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    '\n', ';' -> if (depth == 0) {
                        lines += src.code.substring(from, k)
                        if (enum && src.bare[k] == ';') { from = body.last + 1; break }
                        from = k + 1
                    }
                }
            }
            if (from <= body.last) lines += src.code.substring(from, body.last + 1)
            if (enum) {
                val text = lines.joinToString(" ")
                return splitTop(text, ',').map { cut(normalize(it).first, '{') }.filter { it.isNotEmpty() }
            }
            // Declarations; a line that starts none continues the one before; annotations are left out.
            val decls = mutableListOf<String>()
            for (line in lines) {
                var t = normalize(line).first
                while (true) {
                    val m = ANNOTATION.find(t) ?: break
                    t = t.substring(m.range.last + 1)
                }
                if (t.isEmpty()) continue
                if (startsDeclaration(t) || decls.isEmpty()) decls += t else decls[decls.lastIndex] = decls.last() + " " + t
            }
            return decls.filter { startsDeclaration(it) && !it.split(' ').takeWhile { w -> w in MODIFIERS }.contains("private") }
                .map { head(it) }
        }

        private fun startsDeclaration(t: String): Boolean {
            val words = t.split(' ')
            val first = words.firstOrNull { it !in MODIFIERS } ?: return false
            return first in KEYWORDS || KEYWORDS.any { first.startsWith("$it(") || first.startsWith("$it<") }
        }

        /** A declaration up to its body, initializer or accessor. */
        private fun head(t: String): String {
            val property = t.split(' ').firstOrNull { it !in MODIFIERS }.let { it == "val" || it == "var" }
            var depth = 0
            for (k in t.indices) {
                val c = t[k]
                when {
                    c == '(' || c == '[' || c == '<' && k > 0 && t[k - 1] != '-' -> depth++
                    c == ')' || c == ']' || c == '>' && k > 0 && t[k - 1] != '-' -> depth--
                    depth == 0 && c == '{' -> return t.substring(0, k).trim()
                    depth == 0 && c == '=' && t.getOrNull(k + 1) != '=' && t.getOrNull(k - 1).let { it != '!' && it != '<' && it != '>' && it != '=' } ->
                        return t.substring(0, k).trim()
                }
                if (property && depth == 0) {
                    if (t.startsWith(" get(", k) || t.startsWith(" set(", k) || t.startsWith(" get (", k)) return t.substring(0, k).trim()
                }
            }
            return t.trim()
        }

        private fun cut(t: String, at: Char): String {
            var depth = 0
            for (k in t.indices) {
                when (t[k]) {
                    '(', '[' -> depth++
                    ')', ']' -> depth--
                    at -> if (depth == 0) return t.substring(0, k).trim()
                }
            }
            return t.trim()
        }

        private fun splitTop(t: String, sep: Char): List<String> {
            val out = mutableListOf<String>()
            var depth = 0
            var from = 0
            for (k in t.indices) {
                when (t[k]) {
                    '(', '[', '{' -> depth++
                    ')', ']', '}' -> depth--
                    sep -> if (depth == 0) { out += t.substring(from, k); from = k + 1 }
                }
            }
            out += t.substring(from)
            return out
        }

        /** Indices in [Source.norm] of [pin]: a whole word before, [afterOk] after, and as code (not in a string). */
        private fun occurrences(src: Source, pin: String, afterOk: (Char?) -> Boolean): List<Int> {
            val out = mutableListOf<Int>()
            if (pin.isEmpty()) return out
            var at = src.norm.indexOf(pin)
            while (at >= 0) {
                val before = if (at == 0) null else src.norm[at - 1]
                val end = at + pin.length
                val after = if (end >= src.norm.length) null else src.norm[end]
                val start = src.map[at]
                val wordBefore = before != null && (before.isLetterOrDigit() || before == '_' || before == '.' || before == '`')
                if (!wordBefore && afterOk(after) && src.bare[start] == src.code[start]) out += at
                at = src.norm.indexOf(pin, at + 1)
            }
            return out
        }

        /** Blanks comments (both texts) and string and char contents ([bare] only), keeping every index and line break. */
        private fun scan(src: String): Pair<String, String> {
            val code = StringBuilder(src)
            val bare = StringBuilder(src)
            fun blankBoth(from: Int, to: Int) {
                for (k in from until minOf(to, src.length)) if (src[k] != '\n') { code[k] = ' '; bare[k] = ' ' }
            }
            fun blankBare(k: Int) {
                if (k < src.length && src[k] != '\n') bare[k] = ' '
            }
            val kinds = ArrayList<Int>().apply { add(CODE) }
            val depth = ArrayList<Int>().apply { add(0) }
            fun push(kind: Int) { kinds += kind; depth += 0 }
            fun pop() { kinds.removeAt(kinds.lastIndex); depth.removeAt(depth.lastIndex) }
            val n = src.length
            var i = 0
            while (i < n) {
                val ch = src[i]
                when (kinds.last()) {
                    CODE -> when {
                        src.startsWith("//", i) -> {
                            val e = src.indexOf('\n', i).let { if (it < 0) n else it }
                            blankBoth(i, e)
                            i = e
                        }
                        src.startsWith("/*", i) -> {
                            var d = 0
                            var k = i
                            while (k < n) {
                                if (src.startsWith("/*", k)) { d++; k += 2 }
                                else if (src.startsWith("*/", k)) { d--; k += 2; if (d == 0) break }
                                else k++
                            }
                            blankBoth(i, k)
                            i = k
                        }
                        src.startsWith("\"\"\"", i) -> { push(RAW); i += 3 }
                        ch == '"' -> { push(STRING); i++ }
                        ch == '\'' -> {
                            var k = i + 1
                            if (k < n && src[k] == '\\') k += 2 else k++
                            while (k < n && src[k] != '\'') k++
                            for (j in i + 1 until k) blankBare(j)
                            i = k + 1
                        }
                        ch == '{' -> { depth[depth.lastIndex]++; i++ }
                        ch == '}' -> {
                            // The end of a string template returns to its string.
                            if (depth.last() == 0 && kinds.size > 1) pop() else depth[depth.lastIndex]--
                            i++
                        }
                        else -> i++
                    }
                    STRING -> when {
                        ch == '\\' -> { blankBare(i); blankBare(i + 1); i += 2 }
                        ch == '"' -> { pop(); i++ }
                        src.startsWith("\${", i) -> { push(CODE); i += 2 }
                        else -> { blankBare(i); i++ }
                    }
                    else -> when {
                        src.startsWith("\"\"\"", i) -> {
                            var k = i + 3
                            while (k < n && src[k] == '"') k++
                            for (j in i until k - 3) blankBare(j)
                            pop()
                            i = k
                        }
                        src.startsWith("\${", i) -> { push(CODE); i += 2 }
                        else -> { blankBare(i); i++ }
                    }
                }
            }
            return code.toString() to bare.toString()
        }
    }

    private val root: File by lazy {
        var dir: File? = File(System.getProperty("user.dir") ?: ".").absoluteFile
        var found: File? = null
        while (dir != null && found == null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) { found = c; break }
            }
            dir = dir.parentFile
        }
        assertNotNull("the main sources", found)
        found!!
    }

    private val sources = HashMap<String, Matcher.Source>()

    private fun source(file: String): Matcher.Source = sources.getOrPut(file) {
        val f = File(root, file)
        assertTrue("$file exists", f.isFile)
        Matcher.Source(f.readText(Charsets.UTF_8).replace("\r\n", "\n"))
    }

    companion object {
        /** F0 (design 3.2): the signatures the foundation's first stage froze. */
        val F0 = Part(
            "F0",
            pins = listOf(
                Pin("ui/tools/PillColorCell.kt", "@Composable fun PillColorCell(editor: ObjectColorEditor)"),
                Pin("ui/tools/PillSharpCell.kt", "@Composable fun PillSharpCell(editor: PointCornerEditor)"),
                Pin("vector/select/ObjectActions.kt", "fun editRefusal(c: EditorController): String?", listOf("object ObjectActions")),
                Pin("vector/select/ObjectActions.kt", "fun openInEditor(c: EditorController): Boolean", listOf("object ObjectActions")),
                Pin("vector/select/ObjectActions.kt", "fun turnIntoShape(c: EditorController): Boolean", listOf("object ObjectActions")),
                Pin("tools/vector/CurveTool.kt", "fun openObject(layerId: Long, id: Long, kind: CurveKind): Boolean", listOf("class CurveTool")),
                Pin("tools/vector/CurveTool.kt", "fun kindOf(p: VPath): CurveKind", listOf("class CurveTool", "companion object")),
                Pin("engine/LayerBudget.kt", "const val MAX_LAYERS_GUARD = 10_000", listOf("interface LayerBudget", "companion object")),
                Pin("engine/LayerBudget.kt", "@Volatile var current: LayerBudget", listOf("interface LayerBudget", "companion object")),
                Pin("engine/LayerBudgetV19.kt", "object LayerBudgetV19 : LayerBudget"),
                Pin("vector/select/TapCycle.kt", "const val SAME_SPOT_DP = 16f", listOf("class TapCycle", "companion object")),
                Pin("ui/common/PaintModeControls.kt", "@Composable fun PaintModeGlyph(mode: PaintMode, tint: Color, modifier: Modifier = Modifier)"),
                Pin(
                    "ui/common/PaintModeControls.kt",
                    """
                    @Composable
                    fun PaintModeSegments(
                        selected: PaintMode?,
                        enabled: (PaintMode) -> Boolean = { true },
                        tagPrefix: String,
                        onSelect: (PaintMode) -> Unit,
                    )
                    """,
                ),
                Pin(
                    "ui/color/ColorPickerDialog.kt",
                    """
                    @Composable
                    fun ColorPickerDialog(
                        initial: Int,
                        onPick: (Int) -> Unit,
                        onDismiss: () -> Unit,
                        title: String = "Pick a color",
                        showAlpha: Boolean = false,
                        onPreview: ((Int) -> Unit)? = null,
                    )
                    """,
                ),
                Pin(
                    "ui/color/ColorPickerDialog.kt",
                    "@Composable internal fun ColumnScope.ColorDialogBody(model: ColorDialogModel, onPreview: ((Int) -> Unit)? = null, extraAbove: Dp = 0.dp)",
                ),
                Pin("core/ColorUtils.kt", "fun recolor(c: Int, rgb: Int, s: Float): Int", listOf("object ColorUtils")),
                Pin("core/ColorUtils.kt", "fun recolorMatrix(target: Int, s: Float): android.graphics.ColorMatrix", listOf("object ColorUtils")),
            ),
            members = listOf(
                Members("tools/ObjectColorEditor.kt", "enum class ObjectColorTarget", listOf("FILL", "STROKE", "TEXT", "OUTLINE"), enum = true),
                Members(
                    "tools/ObjectColorEditor.kt", "sealed interface ObjectColorFace",
                    listOf(
                        "data class Solid(val argb: Int) : ObjectColorFace",
                        "data class Gradient(val stops: List<Int>) : ObjectColorFace",
                        "data object None : ObjectColorFace",
                    ),
                ),
                Members(
                    "tools/ObjectColorEditor.kt", "interface ObjectColorEditor",
                    listOf(
                        "val targets: List<ObjectColorTarget>",
                        "val paintMode: PaintMode?",
                        "fun face(t: ObjectColorTarget): ObjectColorFace",
                        "fun unavailable(t: ObjectColorTarget): String?",
                        "fun setPaintMode(m: PaintMode)",
                        "fun begin()",
                        "fun preview(t: ObjectColorTarget, argb: Int)",
                        "fun setNone(t: ObjectColorTarget)",
                        "fun commit()",
                        "fun cancel()",
                    ),
                ),
                Members("tools/ObjectColorEditor.kt", "interface ColoredObjectTool", listOf("val objectColors: ObjectColorEditor?")),
                Members("tools/points/PointCornerEditor.kt", "enum class CornerState", listOf("SHARP", "SMOOTH", "MIXED", "ENDS_ONLY", "ALWAYS_SHARP"), enum = true),
                Members(
                    "tools/points/PointCornerEditor.kt", "interface PointCornerEditor",
                    listOf("val corner: CornerState?", "val cornerLocked: Boolean", "fun setSelectedSharp(sharp: Boolean): Boolean"),
                ),
                Members("tools/points/PointCornerEditor.kt", "interface CornerTool", listOf("val pointCorners: PointCornerEditor?")),
                Members(
                    "vector/select/TapCycle.kt", "class TapCycle",
                    listOf("fun next(xDp: Float, yDp: Float, key: Any?): Int", "fun reset()", "companion object"),
                ),
                Members("vector/ObjectColorSlots.kt", "enum class ColorSlot", listOf("STROKE", "FILL", "TEXT", "OUTLINE", "BOX_FILL", "BOX_BORDER"), enum = true),
                Members(
                    "vector/ObjectColorSlots.kt", "object ObjectColorSlots",
                    listOf(
                        "fun slotsOf(o: VObject): List<ColorSlot>",
                        "fun colorOf(o: VObject, slot: ColorSlot): Int?",
                        "fun set(o: VObject, slot: ColorSlot, argb: Int): VObject",
                        "fun mapObject(o: VObject, f: (Int) -> Int): VObject",
                        "fun map(v: VectorContent, ids: Set<Long>?, f: (Int) -> Int): VectorContent",
                        "fun mapShape(s: ShapeObject, f: (Int) -> Int): ShapeObject",
                        "fun mapText(t: TextLayerData, items: Set<Int>?, f: (Int) -> Int): TextLayerData",
                    ),
                ),
                Members("ui/common/PaintModeControls.kt", "enum class PaintMode", listOf("STROKE", "FILL", "BOTH"), enum = true),
                Members(
                    "engine/LayerBudget.kt", "interface LayerBudget",
                    listOf(
                        "fun used(doc: Document): Int",
                        "fun maxLayers(doc: Document): Int",
                        "fun canAdd(doc: Document, layers: Int = 1, extraBytes: Long = 0L): Boolean",
                        "fun room(doc: Document): Int",
                        "fun canAddAdjustment(doc: Document): Boolean",
                        "fun refusal(doc: Document, suffix: String? = null): String",
                        "fun canvasBudgetBytes(): Long",
                        "fun canTransformCanvas(doc: Document, width: Int, height: Int): String?",
                        "fun rawMaxLayers(width: Int, height: Int): Long",
                        "fun canvasFits(width: Int, height: Int, paintedLayers: Int = 1): Boolean",
                        "fun canvasCheck(width: Int, height: Int): CanvasCheck",
                        "fun importMaxSide(width: Int, height: Int): Int",
                        "fun capacity(width: Int, height: Int): Int?",
                        "fun renderCacheCap(): Long",
                        "companion object",
                    ),
                ),
                Members(
                    "engine/LayerBudgetV19.kt", "object LayerBudgetV19",
                    listOf(
                        "override fun used(doc: Document): Int",
                        "override fun maxLayers(doc: Document): Int",
                        "override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean",
                        "override fun room(doc: Document): Int",
                        "override fun canAddAdjustment(doc: Document): Boolean",
                        "override fun refusal(doc: Document, suffix: String?): String",
                        "override fun canvasBudgetBytes(): Long",
                        "override fun canTransformCanvas(doc: Document, width: Int, height: Int): String?",
                        "override fun rawMaxLayers(width: Int, height: Int): Long",
                        "override fun canvasFits(width: Int, height: Int, paintedLayers: Int): Boolean",
                        "override fun canvasCheck(width: Int, height: Int): CanvasCheck",
                        "override fun importMaxSide(width: Int, height: Int): Int",
                        "override fun capacity(width: Int, height: Int): Int?",
                        "override fun renderCacheCap(): Long",
                    ),
                ),
            ),
        )

        /**
         * F1 (design 2.1 and 3.3): the engine contracts. Pins for the files whose bodies the areas
         * write (`PixelReads`, `MemoryBudget`, `LayerBudgetV110`'s constructor, the three probe stubs,
         * `EngineStats`, `EngineStartup`, `CheckStore`, the four `ui/engine` composables) and for the
         * seams F1 added to files it does not freeze whole; [Members] for the lead's frozen types.
         */
        val F1 = Part(
            "F1",
            pins = listOf(
                Pin("engine/tiles/Tile.kt", "const val TILE_SIZE = 256"),
                Pin("engine/tiles/Tile.kt", "class Tile internal constructor(val w: Int, val h: Int)"),
                Pin("engine/tiles/Tile.kt", "fun of(bitmap: Bitmap): Tile", listOf("class Tile", "companion object")),
                Pin("engine/tiles/Tile.kt", "fun solid(color: Int, w: Int, h: Int): Tile", listOf("class Tile", "companion object")),
                Pin("engine/tiles/PixelStore.kt", "class FlatPixels(val bitmap: Bitmap) : PixelStore()"),
                Pin("engine/tiles/PixelStore.kt", "class BlankPixels(override val width: Int, override val height: Int) : PixelStore()"),
                Pin(
                    "engine/tiles/PixelStore.kt",
                    """
                    class TiledPixels internal constructor(
                        override val width: Int,
                        override val height: Int,
                        internal val tiles: Array<Tile?>,
                        val evictedTo: SavedFileRef? = null,
                    ) : PixelStore()
                    """,
                ),
                Pin("engine/tiles/PixelStore.kt", "fun fromBitmap(b: Bitmap, detectBlank: Boolean = true): TiledPixels", listOf("class TiledPixels", "companion object")),
                Pin("engine/tiles/PixelStore.kt", "data class SavedFileRef(val projectDir: File, val file: String, val contentVersion: Long)"),
                Pin("engine/tiles/PixelPin.kt", "class PixelPin internal constructor(val layer: Layer, val resident: Boolean) : AutoCloseable"),
                Pin("engine/tiles/Residency.kt", "class ChaosResidency(private val everyTask: Boolean = false) : ResidencyHooks"),
                Pin("engine/tiles/SwapStore.kt", "data class SwapSlot(val slab: Int, val offset: Long, val length: Int, val crc: Int)"),
                Pin("engine/tiles/PixelReads.kt", "object PixelReads"),
                Pin("engine/tiles/PixelReads.kt", "fun sample(layer: Layer, x: Int, y: Int): Int", listOf("object PixelReads")),
                Pin("engine/tiles/PixelReads.kt", "fun region(layer: Layer, r: Rect, out: Bitmap? = null): Bitmap", listOf("object PixelReads")),
                Pin("engine/tiles/PixelReads.kt", "fun alphaRegion(layer: Layer, r: Rect, out: ByteArray)", listOf("object PixelReads")),
                Pin("engine/tiles/PixelReads.kt", "fun flattenedRegion(doc: Document, r: Rect, out: Bitmap? = null): Bitmap", listOf("object PixelReads")),
                Pin(
                    "engine/budget/MemoryProbe.kt",
                    """
                    data class MemSnapshot(
                        val totalMem: Long,
                        val availMem: Long,
                        val threshold: Long,
                        val lowMemory: Boolean,
                        val isLowRamDevice: Boolean,
                        val is64Bit: Boolean,
                        val javaMax: Long,
                        val javaUsed: Long,
                        val nativeAllocated: Long,
                    )
                    """,
                ),
                Pin("engine/budget/MemoryProbe.kt", "data class ExitRecord(val atMillis: Long, val lowMemory: Boolean, val signaled9: Boolean, val foreground: Boolean)"),
                Pin("engine/budget/AndroidMemoryProbe.kt", "object AndroidMemoryProbe : MemoryProbe"),
                Pin("engine/budget/AndroidMemoryProbe.kt", "override fun snapshot(): MemSnapshot", listOf("object AndroidMemoryProbe")),
                Pin(
                    "engine/budget/AndroidMemoryProbe.kt",
                    "override fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long",
                    listOf("object AndroidMemoryProbe"),
                ),
                Pin("engine/budget/AndroidStorageProbe.kt", "object AndroidStorageProbe : StorageProbe"),
                Pin("engine/budget/AndroidStorageProbe.kt", "override fun freeBytes(dir: File): Long", listOf("object AndroidStorageProbe")),
                Pin("engine/budget/AndroidStorageProbe.kt", "override fun writeRead(dir: File, bytes: Long): Pair<Float, Float>", listOf("object AndroidStorageProbe")),
                Pin("engine/budget/AndroidExitInfo.kt", "object AndroidExitInfo : ExitInfoSource"),
                Pin("engine/budget/AndroidExitInfo.kt", "override fun exits(sinceMillis: Long): List<ExitRecord>", listOf("object AndroidExitInfo")),
                Pin("engine/budget/AndroidExitInfo.kt", "override fun lmkReportsSupported(): Boolean", listOf("object AndroidExitInfo")),
                Pin("engine/budget/MemoryBudget.kt", "data class Budgets(val javaMax: Long, val nativeBudget: Long, val known: Boolean, val stepDown: Int)"),
                Pin("engine/budget/MemoryBudget.kt", "fun v19(javaMax: Long): Budgets", listOf("data class Budgets", "companion object")),
                Pin("engine/budget/MemoryBudget.kt", "fun compute(probe: MemoryProbe, stepDown: Int): Budgets", listOf("data class Budgets", "companion object")),
                Pin("engine/budget/MemoryBudget.kt", "class MemoryBudget(val budgets: Budgets, val ledger: PixelLedger) : NativeAdmission"),
                Pin("engine/budget/MemoryBudget.kt", "val javaMax: Long", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "val nativeBudget: Long", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "fun undoCap(level: EngineLevel): Long", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "fun cloneSnapshotCap(): Long", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "fun tileRam(): Long", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "override fun admitNative(bytes: Long, purpose: NativePurpose): Boolean", listOf("class MemoryBudget")),
                Pin("engine/budget/MemoryBudget.kt", "override fun release(bytes: Long, purpose: NativePurpose)", listOf("class MemoryBudget")),
                Pin("engine/budget/LayerBudgetV110.kt", "class LayerBudgetV110(private val budget: MemoryBudget, private val level: EngineLevel)"),
                Pin("engine/EngineStats.kt", "data class StatSummary(val stat: EngineStat, val n: Int, val p50: Float, val p95: Float)"),
                Pin("engine/EngineStats.kt", "fun record(stat: EngineStat, value: Float)", listOf("object EngineStats")),
                Pin("engine/EngineStats.kt", "fun summary(): List<StatSummary>", listOf("object EngineStats")),
                Pin("engine/EngineStartup.kt", "fun onAppCreate(app: Application)", listOf("object EngineStartup")),
                Pin("engine/check/CheckStore.kt", "fun read(context: Context): EngineCheckResult?", listOf("object CheckStore")),
                Pin("ui/engine/EngineCheckHost.kt", "@Composable fun EngineCheckHost()"),
                Pin("ui/engine/EngineNoticeCard.kt", "@Composable fun EngineNoticeCard(notice: EngineNotice, onOk: () -> Unit)"),
                Pin("ui/engine/LayerStorageSection.kt", "@Composable fun LayerStorageSection()"),
                Pin("ui/engine/EngineEditorChrome.kt", "@Composable fun EngineEditorChrome(controller: EditorController)"),
                Pin(
                    "storage/engine/EngineCheckResult.kt",
                    """
                    @Serializable
                    data class EngineCheckResult(
                        val schema: Int = 1,
                        val appVersionCode: Int,
                        val deviceKey: String,
                        val ranAtMillis: Long,
                        val durationMs: Long,
                        val level: EngineLevel,
                        val reason: String,
                        val totalMem: Long,
                        val availMem: Long,
                        val threshold: Long,
                        val lowRam: Boolean,
                        val is64Bit: Boolean,
                        val javaMax: Long,
                        val nativeProbeBytes: Long,
                        val deflateMBs: Float,
                        val inflateMBs: Float,
                        val ratio: Float,
                        val writeMBs: Float,
                        val readMBs: Float,
                        val freeBytes: Long,
                        val composite64Ms: Float,
                        val belowCacheMinLayers: Int? = null,
                        val aboveProjection: Boolean = false,
                        val stepDown: Int = 0,
                        val lmkDeathsAt: List<Long> = emptyList(),
                        val provisional: Boolean = false,
                        val m2Outcome: String = "",
                        val inconclusiveRuns: Int = 0,
                    )
                    """,
                ),
                Pin(
                    "storage/engine/EngineState.kt",
                    """
                    @Serializable
                    data class EngineState(
                        @EncodeDefault(EncodeDefault.Mode.NEVER) val engineCheckPending: Boolean = false,
                        @EncodeDefault(EncodeDefault.Mode.NEVER) val lastExitSeen: Long = 0L,
                        @EncodeDefault(EncodeDefault.Mode.NEVER) val slowSwapReads: Boolean = false,
                    )
                    """,
                ),
                Pin("model/Document.kt", "class Layer(val id: Long, var name: String, pixels: PixelStore)"),
                Pin("model/Document.kt", "constructor(id: Long, name: String, bitmap: Bitmap) : this(id, name, storeOf(bitmap))", listOf("class Layer")),
                Pin("model/Document.kt", "var pixels: PixelStore", listOf("class Layer")),
                Pin("model/Document.kt", "var maskPixels: PixelStore?", listOf("class Layer")),
                Pin("model/Document.kt", "var bitmap: Bitmap", listOf("class Layer")),
                Pin("model/Document.kt", "var mask: Bitmap?", listOf("class Layer")),
                Pin("model/Document.kt", "val hasMask: Boolean", listOf("class Layer")),
                Pin("model/Document.kt", "var pixelGeneration: Long = 0L", listOf("class Layer")),
                Pin("model/Document.kt", "fun owns(b: Bitmap?): Boolean", listOf("class Layer")),
                Pin("model/Document.kt", "fun pin(): PixelPin", listOf("class Layer")),
                Pin("model/Document.kt", "fun pinResident(): PixelPin", listOf("class Layer")),
                Pin("model/Document.kt", "fun admitWrite(): Boolean", listOf("class Layer")),
                Pin("model/Document.kt", "internal val residency: ResidencyState", listOf("class Layer")),
                Pin("model/Document.kt", "val width: Int", listOf("class Layer")),
                Pin("model/Document.kt", "val height: Int", listOf("class Layer")),
                Pin("model/Document.kt", "fun recycleBitmaps()", listOf("class Layer")),
                Pin("model/Document.kt", "var activeLayerIndex: Int", listOf("class Document")),
                Pin("engine/MemoryGovernor.kt", "fun trimUndo(history: UndoManager): GovernorPolicyV19.UndoTrim", listOf("interface GovernorPolicy")),
                Pin("engine/BlendModes.kt", "fun clearsOnTransparent(mode: LayerBlendMode): Boolean", listOf("object BlendModes")),
                Pin("storage/ProjectFile.kt", "const val VERSION = 4", listOf("object ProjectFormat")),
                Pin("storage/ProjectFile.kt", "const val V19_HEAP = 512L shl 20", listOf("object ProjectFormat")),
                Pin("storage/ProjectFile.kt", "fun writtenVersion(layers: List<Layer>): Int", listOf("object ProjectFormat")),
                Pin("storage/ProjectFile.kt", "fun writtenVersion(layers: List<Layer>, w: Int, h: Int): Int", listOf("object ProjectFormat")),
                Pin("storage/ProjectFile.kt", "fun v19OpenCount(layers: List<Layer>, w: Int, h: Int): Int", listOf("object ProjectFormat")),
                Pin("storage/ProjectFile.kt", "fun v19Cap(w: Int, h: Int): Int", listOf("object ProjectFormat")),
                Pin("AppSettings.kt", "var layerStorageMode: LayerStorageMode", listOf("class AppSettings")),
                Pin("UiPrefs.kt", "var engineNoticeShownAt: Long", listOf("class UiPrefs")),
                Pin("UiPrefs.kt", "var engineLmkNoticeShownAt: Long", listOf("class UiPrefs")),
                Pin("ui/gallery/GalleryNotices.kt", "data class Engine(val notice: EngineNotice) : GalleryNotice", listOf("sealed interface GalleryNotice")),
                Pin(
                    "ui/gallery/GalleryNotices.kt",
                    """
                    fun pickNotice(
                        crash: CrashReport?,
                        backup: BackupStatus,
                        notSetDismissedAtMillis: Long,
                        nowMillis: Long,
                        engine: EngineNotice? = null,
                    ): GalleryNotice?
                    """,
                ),
                Pin("exchange/NewArtwork.kt", "fun forSvg(name: String, svg: SvgDocument): NewArtworkSpec", listOf("object NewArtwork")),
                Pin("exchange/NewArtwork.kt", "fun forPdfPage(name: String, pw: Float, ph: Float): NewArtworkSpec", listOf("object NewArtwork")),
                Pin("exchange/NewArtwork.kt", "fun forPayload(name: String, p: BrushworkPayload): NewArtworkSpec?", listOf("object NewArtwork")),
                Pin("EditorController.kt", "var activating: Layer?", listOf("class EditorController")),
            ),
            members = listOf(
                Members("engine/tiles/Tile.kt", "enum class TileState", listOf("RESIDENT", "PACKED", "SWAPPED"), enum = true),
                Members(
                    "engine/tiles/Tile.kt", "class Tile",
                    listOf(
                        "internal val lock",
                        "internal var bitmap: Bitmap?",
                        "internal var packed: ByteArray?",
                        "internal var slot: SwapSlot?",
                        "internal var refs",
                        "internal var pins",
                        "internal var lastUse",
                        "internal var solid: Int?",
                        "val solidColor: Int?",
                        "val state: TileState",
                        "val bytes: Int",
                        "fun bitmapForRead(): Bitmap",
                        "companion object",
                    ),
                ),
                Members(
                    "engine/tiles/PixelStore.kt", "sealed class PixelStore",
                    listOf(
                        "abstract val width: Int",
                        "abstract val height: Int",
                        "abstract fun residentBytes(): Long",
                        "abstract fun logicalBytes(): Long",
                        "abstract fun isBlank(): Boolean",
                        "abstract fun isEmptyIn(clip: Rect): Boolean",
                        "abstract fun isEmpty(): Boolean",
                    ),
                ),
                Members(
                    "engine/tiles/PixelStore.kt", "class FlatPixels",
                    listOf(
                        "override val width: Int",
                        "override val height: Int",
                        "override fun residentBytes(): Long",
                        "override fun logicalBytes(): Long",
                        "override fun isBlank(): Boolean",
                        "override fun isEmptyIn(clip: Rect): Boolean",
                        "override fun isEmpty(): Boolean",
                    ),
                ),
                Members(
                    "engine/tiles/PixelStore.kt", "class BlankPixels",
                    listOf(
                        "override fun residentBytes(): Long",
                        "override fun logicalBytes(): Long",
                        "override fun isBlank(): Boolean",
                        "override fun isEmptyIn(clip: Rect): Boolean",
                        "override fun isEmpty(): Boolean",
                    ),
                ),
                Members(
                    "engine/tiles/PixelStore.kt", "object FolderPixels",
                    listOf(
                        "override val width: Int",
                        "override val height: Int",
                        "override fun residentBytes(): Long",
                        "override fun logicalBytes(): Long",
                        "override fun isBlank(): Boolean",
                        "override fun isEmptyIn(clip: Rect): Boolean",
                        "override fun isEmpty(): Boolean",
                    ),
                ),
                Members(
                    "engine/tiles/PixelStore.kt", "class TiledPixels",
                    listOf(
                        "val cols: Int",
                        "val rows: Int",
                        "init",
                        "fun tileAt(col: Int, row: Int): Tile?",
                        "fun occupied(): Int",
                        "fun occupiedBounds(out: Rect): Boolean",
                        "fun drawTo(canvas: Canvas, paint: Paint?, clip: Rect)",
                        "fun drawMaskTo(canvas: Canvas, maskPaint: Paint, clip: Rect)",
                        "fun copyRegion(src: Rect, dst: Bitmap, dx: Int, dy: Int)",
                        "fun materialize(): Bitmap",
                        "fun snapshot(): TiledPixels",
                        "fun withTiles(changes: Map<Int, Tile?>): TiledPixels",
                        "internal fun replaceTiles(changes: Map<Int, Tile?>): Map<Int, Tile?>",
                        "override fun residentBytes(): Long",
                        "override fun logicalBytes(): Long",
                        "override fun isBlank(): Boolean",
                        "override fun isEmptyIn(clip: Rect): Boolean",
                        "override fun isEmpty(): Boolean",
                        "companion object",
                    ),
                ),
                Members("engine/tiles/PixelPin.kt", "class PixelPin", listOf("override fun close()")),
                Members("engine/tiles/Residency.kt", "enum class ResidencyState", listOf("FOLDER", "BLANK", "FLAT", "PARKED", "EVICTED"), enum = true),
                Members(
                    "engine/tiles/Residency.kt", "interface ResidencyHooks",
                    listOf(
                        "fun activate(layer: Layer)",
                        "fun deactivated(layer: Layer)",
                        "fun pressure(p: MemoryPressure)",
                        "fun idleTick()",
                        "fun swapUndoTiles(targetBytes: Long): Long",
                    ),
                ),
                Members(
                    "engine/tiles/Residency.kt", "object ClassicResidency",
                    listOf(
                        "override fun activate(layer: Layer)",
                        "override fun deactivated(layer: Layer)",
                        "override fun pressure(p: MemoryPressure)",
                        "override fun idleTick()",
                    ),
                ),
                Members(
                    "engine/tiles/Residency.kt", "class ChaosResidency",
                    listOf(
                        "override fun activate(layer: Layer)",
                        "override fun deactivated(layer: Layer)",
                        "override fun pressure(p: MemoryPressure)",
                        "override fun idleTick()",
                        "fun park(layer: Layer): Boolean",
                        "fun unpark(layer: Layer)",
                        "fun parkInactive(doc: Document): Int",
                        "internal fun settled(doc: Document)",
                    ),
                ),
                Members(
                    "engine/tiles/Projection.kt", "interface Projection",
                    listOf(
                        "fun validFor(doc: Document, active: Layer?): Boolean",
                        "fun drop()",
                        "val heldBytes: Long",
                    ),
                ),
                Members(
                    "engine/tiles/SwapStore.kt", "interface SwapStore",
                    listOf(
                        "val capacityBytes: Long",
                        "val usedBytes: Long",
                        "fun canWrite(bytes: Long): Boolean",
                        "fun write(tile: Tile)",
                        "fun read(tile: Tile): Bitmap",
                        "fun free(slot: SwapSlot)",
                        "fun close()",
                    ),
                ),
                Members(
                    "engine/tiles/SwapStore.kt", "object NoSwap",
                    listOf(
                        "override val capacityBytes: Long",
                        "override val usedBytes: Long",
                        "override fun canWrite(bytes: Long): Boolean",
                        "override fun write(tile: Tile): Unit",
                        "override fun read(tile: Tile): Bitmap",
                        "override fun free(slot: SwapSlot)",
                        "override fun close()",
                    ),
                ),
                Members(
                    "engine/budget/MemoryProbe.kt", "interface MemoryProbe",
                    listOf(
                        "fun snapshot(): MemSnapshot",
                        "fun probeNative(stepBytes: Long, stopBelowAvail: Long, capBytes: Long): Long",
                    ),
                ),
                Members(
                    "engine/budget/MemoryProbe.kt", "interface StorageProbe",
                    listOf(
                        "fun freeBytes(dir: File): Long",
                        "fun writeRead(dir: File, bytes: Long): Pair<Float, Float>",
                    ),
                ),
                Members(
                    "engine/budget/MemoryProbe.kt", "interface ExitInfoSource",
                    listOf(
                        "fun exits(sinceMillis: Long): List<ExitRecord>",
                        "fun lmkReportsSupported(): Boolean",
                    ),
                ),
                Members(
                    "engine/budget/PixelLedger.kt", "enum class LedgerCategory",
                    listOf("RESIDENT_LAYERS", "TILES", "MASKS", "ARRAYS", "DISPLAY", "PROJECTIONS", "UNDO", "TRANSIENT", "THUMBNAILS"),
                    enum = true,
                ),
                Members(
                    "engine/budget/PixelLedger.kt", "enum class NativePurpose",
                    listOf("LAYER", "MASK", "TILE", "TRANSIENT", "PROJECTION", "DISPLAY", "UNDO", "ARRAY", "THUMBNAIL"),
                    enum = true,
                ),
                Members(
                    "engine/budget/PixelLedger.kt", "class PixelLedger",
                    listOf(
                        "fun add(c: LedgerCategory, bytes: Long)",
                        "fun sub(c: LedgerCategory, bytes: Long)",
                        "var limit: Long",
                        "fun tryReserve(c: LedgerCategory, bytes: Long): Boolean",
                        "fun release(c: LedgerCategory, bytes: Long)",
                        "val native: Long",
                        "fun of(c: LedgerCategory): Long",
                    ),
                ),
                Members(
                    "engine/budget/PixelLedger.kt", "interface NativeAdmission",
                    listOf(
                        "fun admitNative(bytes: Long, purpose: NativePurpose): Boolean",
                        "fun release(bytes: Long, purpose: NativePurpose)",
                    ),
                ),
                Members("engine/EngineConfig.kt", "enum class LayerStorageMode", listOf("AUTOMATIC", "FULL", "CLASSIC"), enum = true),
                Members("engine/EngineConfig.kt", "enum class EngineLevel", listOf("CLASSIC", "TILES", "FULL"), enum = true),
                Members(
                    "engine/EngineConfig.kt", "object EngineConfig",
                    listOf(
                        "var level: EngineLevel",
                        "var budget: MemoryBudget",
                        "var strokeBelowMinLayers: Int?",
                        "var aboveProjection: Boolean",
                        "val swap: Boolean",
                        "var residency: ResidencyHooks",
                        "var swapStore: SwapStore",
                        "var memoryProbe: MemoryProbe",
                        "var storageProbe: StorageProbe",
                        "var exitInfo: ExitInfoSource",
                        "fun resolve(mode: LayerStorageMode, check: EngineCheckResult?, deviceKey: String, freeBytes: Long, memoryKnown: Boolean): EngineLevel",
                        "fun deviceKey(model: String, totalMem: Long): String",
                        "fun openSession(level: EngineLevel, budgets: Budgets, check: EngineCheckResult?)",
                        "fun closeSession()",
                        "fun applyIdle(mode: LayerStorageMode, check: EngineCheckResult?, budgets: Budgets)",
                        "internal fun testMode(): EngineLevel?",
                        "internal fun parkChaos(): Boolean",
                        "internal fun activeIndexSet(doc: Document)",
                    ),
                ),
                Members(
                    "engine/EngineNotice.kt", "sealed interface EngineNotice",
                    listOf(
                        "data class CheckChose(val level: EngineLevel, val reason: String) : EngineNotice",
                        "data object LowMemoryClosings : EngineNotice",
                    ),
                ),
                Members("engine/EngineNotice.kt", "object EngineNotices", listOf("var current: EngineNotice?")),
                Members(
                    "engine/EngineStats.kt", "enum class EngineStat",
                    listOf("SWITCH", "OPEN", "SAVE_LAYER", "COMPOSITE", "INPUT_TO_COMPOSITE", "FRAME_STROKE", "FRAME_PANEL", "FRAME_OTHER", "LEDGER_PEAK"),
                    enum = true,
                ),
                Members(
                    "storage/engine/EngineCheckResult.kt", "object CheckReasons",
                    listOf(
                        "const val LOW_MEMORY",
                        "const val SLOW_COMPOSITING",
                        "const val SLOW_STORAGE",
                    ),
                ),
                Members(
                    "storage/engine/EngineState.kt", "object EngineStateStore",
                    listOf(
                        "const val FILE_NAME",
                        "fun file(context: Context): File",
                        "fun read(context: Context): EngineState",
                        "fun write(context: Context, state: EngineState)",
                    ),
                ),
                Members(
                    "model/Document.kt", "object PixelFacade",
                    listOf(
                        "var materializations",
                        "var strict",
                        "internal val depth: ThreadLocal<IntArray>",
                        "internal inline fun <T> allowing(block: () -> T): T",
                        "internal fun check(layer: Layer)",
                    ),
                ),
            ),
        )

        /** Every part checked; F1 appends its own. */
        val PARTS: List<Part> = listOf(F0, F1)
    }
}
