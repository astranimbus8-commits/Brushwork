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
 * paint-mode controls, the color picker's preview and `ColorUtils.recolor`); F1 adds its part to
 * [PARTS]. The matcher is checked on a made-up source first. JVM (reads the main sources).
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

        /** Every part checked; F1 appends its own. */
        val PARTS: List<Part> = listOf(F0)
    }
}
