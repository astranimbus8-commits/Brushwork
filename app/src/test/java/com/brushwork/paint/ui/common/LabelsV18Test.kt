package com.brushwork.paint.ui.common

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.filters.art.ChromaticAberrationZoomingFilter
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.placement.TransformStripItems
import com.brushwork.paint.ui.selection.modeDescription
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * v1.8 F1 (design §4.8, I10): the labels pre-declared in `LabelsV18.kt`.
 * 1. Every value is unique and not blank (the tags too), and the history names are the design's.
 * 2. No value equals a string literal of the main sources outside `LabelsV18.kt`, except
 *    [LabelsV18.SHARED] (rule L: a label is used through its constant, never retyped).
 * 3. No tag is a label (nor a literal of the main sources).
 * 4. On each screen of §4.8, no handle and no visible text is a substring of another, where one
 *    of the two is a v1.8 text (SmokeUi.matches is a case-sensitive `contains` over both).
 * JVM: the main sources are read from disk (the module's `src/main/java`).
 */
class LabelsV18Test {
    private val labelObjects: List<Any> = listOf(
        MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
        CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
    )

    /** "Object.NAME" to value, for every `const val` String of [objs]. */
    private fun constants(vararg objs: Any): Map<String, String> = objs.flatMap { o ->
        o.javaClass.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { f -> f.isAccessible = true; "${o.javaClass.simpleName}.${f.name}" to (f.get(null) as String) }
    }.toMap()

    private val labels: Map<String, String> by lazy { constants(*labelObjects.toTypedArray()) }
    private val tags: Map<String, String> by lazy { constants(V18Tags) }

    @Test
    fun everyV18LabelIsUniqueAndNotBlank() {
        val all = labels
        // 95 from F1, and three from the integration (v18I): the Color select no-match toast, the
        // Magnetic lasso's out-of-memory toast and the Transform box hint.
        assertEquals("the objects have their labels: ${all.keys}", 99, all.size)
        assertTrue(all.keys.containsAll(listOf("ColorSelectLabels18.NO_MATCH", "MagneticLabels18.OUT_OF_MEMORY", "TransformLabels18.BOX_HINT")))
        val dups = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("labels declared twice: $dups", dups.isEmpty())
        assertTrue("blank: ${all.filterValues { it.isBlank() }}", all.values.none { it.isBlank() || it != it.trim() })
        assertEquals("tags unique", tags.size, tags.values.toSet().size)
        assertTrue("tags not blank", tags.values.none { it.isBlank() })
        assertEquals(8, tags.size)
        // The design's shared set: each a label.
        assertEquals(setOf("Highlights", "Midtones", "Shadows", "Skin tones", "RGB", "Clockwise", "View"), LabelsV18.SHARED)
        assertTrue(all.values.containsAll(LabelsV18.SHARED))
        // The v1.8 history step names (design §4.8).
        assertEquals(listOf(ArrayLabels18.HISTORY_LOOK, MirrorLabels18.DETACH, MirrorLabels18.REMOVE), HistoryLabels.V18)
        assertEquals(listOf("Array look", "Detach mirror copies", "Remove mirror copies"), HistoryLabels.V18)
        assertTrue("no v1.8 step name is a v1.7 one", HistoryLabels.V18.none { it in HistoryLabels.V17 })
    }

    @Test
    fun noLabelOrTagIsRetypedInTheMainSources() {
        val root = mainSources()
        assertNotNull("the main sources (src/main/java) are found from ${System.getProperty("user.dir")}", root)
        val files = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "LabelsV18.kt" }.toList()
        assertTrue("the main sources are read: ${files.size}", files.size > 300)
        val shared = LabelsV18.SHARED
        val labelValues = labels.values.toSet()
        val tagValues = tags.values.toSet()
        val retyped = mutableListOf<String>()
        val quoted = mutableListOf<String>()
        val sharedSeen = mutableSetOf<String>()
        for (f in files) {
            val text = f.readText()
            for (lit in KotlinLiterals.of(text)) {
                if (lit in labelValues) {
                    if (lit in shared) sharedSeen += lit else retyped += "${f.name}: \"$lit\""
                }
                if (lit in tagValues) retyped += "${f.name}: tag \"$lit\""
            }
            // The ownership script's rule L greps `"value"` in the raw text, comments included, and
            // drops a value it finds from the check: a comment refers to the constant instead.
            for (v in labelValues + tagValues) if (v !in shared && text.contains("\"$v\"")) quoted += "${f.name}: \"$v\""
        }
        assertTrue("a v1.8 label or tag typed as a literal outside LabelsV18 (rule L): $retyped", retyped.isEmpty())
        assertTrue("a v1.8 label or tag quoted outside LabelsV18 (a comment hides it from rule L): $quoted", quoted.isEmpty())
        // Each shared label has its v1.7 twin (else it is exempted for nothing).
        assertEquals("shared labels with a v1.7 twin", shared, sharedSeen)
    }

    @Test
    fun theLiteralScannerSkipsCommentsAndReadsEveryStringForm() {
        val src = """
            // "Look" in a line comment
            /* "Pace" in /* a nested */ block comment "Even" */
            val a = "Placement"; val c = '"'; val d = '\''; val e = "esc \" \\ A \${'$'}x"
            val f = ""${'"'}raw "quoted" ""${'"'}; val g = "one ${'$'}{ h("inner") + "x" } two"
            val i = "${'$'}name tail"; val `odd "name"` = 1
        """.trimIndent()
        val lits = KotlinLiterals.of(src)
        assertFalse(lits.toString(), "Look" in lits || "Pace" in lits || "Even" in lits)
        assertTrue(lits.toString(), "Placement" in lits)
        assertTrue(lits.toString(), "esc \" \\ A \$x" in lits)
        assertTrue(lits.toString(), "raw \"quoted\" " in lits)
        assertTrue(lits.toString(), "inner" in lits && "x" in lits)
        assertTrue("a template never equals a label: $lits", lits.none { it.startsWith("one ") && !it.contains('\u0000') })
        assertTrue(lits.toString(), lits.any { it == "\u0000 tail" })
        assertFalse(lits.toString(), lits.any { it.contains("odd") })
    }

    @Test
    fun noTagIsALabel() {
        val all = labels.values.toSet()
        assertTrue("a tag is never a label: ${tags.values.filter { it in all }}", tags.values.none { it in all })
        // Nor a v1.7 label or tag.
        val v17 = LabelsV17Constants.all()
        assertTrue(tags.values.none { it in v17 })
        assertTrue("no v1.8 label equals a v1.7 pre-declared label", all.none { it in v17 })
    }

    // ------------------------------------------------------------------ 4. the screens

    /** Pairs (a, b) of [texts] where a is a substring of b (equal texts at two places count). */
    private fun substringPairs(texts: List<String>): List<Pair<String, String>> {
        val out = mutableListOf<Pair<String, String>>()
        for (i in texts.indices) for (j in texts.indices) {
            if (i != j && texts[j].contains(texts[i])) out += texts[i] to texts[j]
        }
        return out
    }

    private fun assertNoSubstrings(screen: String, v18: List<String>, v17: List<String>, allowed: Set<Pair<String, String>> = emptySet()) {
        val mine = v18.distinct()
        val pairs = substringPairs(mine + v17.distinct()).filter { (a, b) -> (a in mine || b in mine) && (a to b) !in allowed }
        assertTrue("$screen: a v1.8 text inside another (SmokeUi would match the wrong one): $pairs", pairs.isEmpty())
    }

    private fun values(o: Any): List<String> = constants(o).values.toList()

    private val selectionModes: List<String> = SelectionMode.entries.map { modeDescription(it) }

    /** A filter panel: its parameters' labels and their controls' descriptions, and the panel's own buttons. */
    private fun panel(f: Filter, skip: Set<String> = emptySet()): List<String> = f.params.filter { it.key !in skip }.flatMap { p ->
        when (p) {
            is FilterParam.Slider -> listOf(p.label, "Decrease ${p.label}", "Increase ${p.label}", "Reset ${p.label}")
            is FilterParam.Choice -> listOf(p.label) + p.options
            is FilterParam.Seed -> listOf(p.label, "New random ${p.label.lowercase()}")
            is FilterParam.Color -> listOf(p.label, "Choose ${p.label.lowercase()}")
            else -> listOf(p.label)
        }
    } + listOf(
        f.name, "Apply filter", "Cancel filter", "Preview", "Reset to defaults", "Compare with the original (press and hold)",
        "As adjustment layer", "Add as adjustment layer", "Stop", "Filters", "Search filters",
    )

    private val curveStrip = listOf(
        "Stroke kind", "Line width", "Add point", "Delete point", "Closed", "Curve settings", "Sharp corner", "Auto tangent",
        "Point thickness", "Handles", "Select all points", "Deselect all points", "Previous point", "Next point", "Use brush size",
        "Taper ends", "Numbers", "Shapes", "Path shapes", "Cyclic", "Order 4", "Endpoint", "Point weight", "Path settings",
        "Polyline settings", "Undo last point", "Redo point",
    ) + values(CurveLabels17)

    private val shapeStrip = listOf(
        "Shape type", "Shape style", "Line ends", "Stroke width", "Points", "Editable (own layer)", "Stroke with", "Center",
        "Proportional", "Circle", "15°", "Snap to objects", "Numbers", "Shape settings", "Select all points", "Deselect all points",
    ) + ShapeType.entries.map { it.label } + ShapeStyle.entries.map { it.label }

    @Test
    fun theColorSelectStripWithItsPresets() {
        assertNoSubstrings(
            "Color select strip",
            v18 = values(ColorSelectLabels18) + MagneticLabels18.SELECTION_MODE + StripLabels18.MORE_TOGGLE,
            v17 = selectionModes + SampleSource.entries.map { it.label } + listOf("Color select", "Refer", "Anti-alias", "Selection menu", "Deselect"),
        )
    }

    @Test
    fun theMagneticLassoStrip() {
        assertNoSubstrings(
            "Magnetic lasso strip",
            v18 = values(MagneticLabels18) + StripLabels18.MORE_TOGGLE,
            v17 = selectionModes + SampleSource.entries.map { it.label } + listOf("Magnetic lasso", "Delete point", "Refer", "Anti-alias", "Selection menu", "Deselect"),
        )
    }

    @Test
    fun theArraySheetsLookTab() {
        // The history name "Array look" is a step name, not on the sheet.
        val v18 = values(ArrayLabels18) - ArrayLabels18.HISTORY_LOOK
        assertNoSubstrings(
            "Array sheet, Look tab",
            v18 = v18,
            v17 = values(ArrayLabels) + listOf(
                "Array", "Count", "Copy spacing", "Scale per copy", "Rotate copies", "Align to curve", "Turn", "Sweep", "Source",
                "Move X", "Move Y", "Relative X", "Relative Y", "Constant X", "Constant Y", "Offset per copy", "Draw guide", "Use a path",
                // The tint's GradientEditor.
                "Gradient editor", "Reverse gradient", "Delete color stop", "Stop color", "Default", "Tap the bar to add a stop, tap a stop to recolor it.",
            ),
        )
    }

    @Test
    fun theChromaticAberrationAndGlitchPanels() {
        // Area A replaces the v1.7 "Color order" choice with the fringe colors.
        val fringes = values(FringeLabels18)
        for (f in listOf(ChromaticAberrationMovingFilter(), ChromaticAberrationZoomingFilter())) {
            assertNoSubstrings(f.name, v18 = fringes - FringeLabels18.STATIC_COLOR, v17 = panel(f, skip = setOf("order", "fringes")) + "Color order")
        }
        // The static's swatch is named after its label (QA fix: "Choose " + the label in lower case).
        assertNoSubstrings("Glitch", v18 = fringes + "Choose ${FringeLabels18.STATIC_COLOR.lowercase()}", v17 = panel(GlitchFilter(), skip = setOf("fringes", "static_color")))
        // "Static" (v1.7) and "Color of the static" differ in case only where they meet.
        assertTrue(GlitchFilter().params.any { it.label == "Static" })
    }

    @Test
    fun theGeneratorSheetOverTheCurveStrip() {
        assertNoSubstrings("generator sheet", v18 = values(CurveGenLabels18) + StripLabels18.MORE_TOGGLE, v17 = curveStrip)
    }

    @Test
    fun theShapeSettingsSheetForACogAStarAndAPolygon() {
        assertNoSubstrings(
            "Shape settings",
            v18 = values(CogLabels18),
            v17 = shapeStrip + CornerStyle.entries.map { it.label } + listOf(
                "Number of sides", "Number of points", "Inner radius", "Corner radius", "Corner size", "Corners", "Head size",
                "Shape", "Style", "Stroke", "Width", "Height", "Rotation", "Position", "Size", "Draw from the center", "Snap angle",
                "Drawing", "Keep proportions", "Arrowheads",
            ),
        )
    }

    @Test
    fun theCurveAndShapeStripsWithTheMoreRowOpen() {
        val v18 = listOf(MirrorLabels18.DETACH, MirrorLabels18.REMOVE) + values(StripLabels18)
        assertNoSubstrings("Curve strip, More row", v18 = v18, v17 = curveStrip + values(PillLabels))
        assertNoSubstrings("Shape strip, More row", v18 = v18, v17 = shapeStrip + values(PillLabels))
        // The vector object bar with "Detach mirror copies".
        assertNoSubstrings(
            "vector object bar",
            v18 = listOf(MirrorLabels18.DETACH, StripLabels18.MORE_OBJECT),
            v17 = listOf(
                "Array", "Back", "Backward", "Bring forward", "Bring to front", "Delete the selected objects", "Delete", "Deselect the objects",
                "Deselect", "Duplicate the selected objects", "Duplicate", "Forward", "Front", "Recolor the lines only", "Recolor with the main color",
                "Recolor", "Send backward", "Send to back", "Transform the selected objects", "Transform", ArrayLabels.FROM_OBJECTS,
            ),
        )
    }

    @Test
    fun theFreeDeformStripWithItsMeshPopup() {
        assertNoSubstrings(
            "Free deform strip",
            v18 = listOf(TransformLabels18.MESH_SIZE, StripLabels18.MORE_TOGGLE),
            v17 = values(TransformLabels17) + listOf(
                "Fewer mesh columns", "More mesh columns", "Fewer mesh rows", "More mesh rows", "Select all points", "Deselect all points",
                "Delete", "Choose interpolation", "Transform", "3 × 3",
            ),
        )
    }

    @Test
    fun theTransformStripInABoxMode() {
        // v18I: the box hint heads the More row of an active Free or Distort transform.
        val t = TransformStripItems
        assertNoSubstrings(
            "Transform strip, box modes",
            v18 = listOf(TransformLabels18.BOX_HINT, StripLabels18.MORE_TOGGLE),
            v17 = TransformTool.Mode.entries.map { it.label } + TransformTool.Interpolation.entries.flatMap { listOf(it.label, it.description) } +
                listOf(
                    t.keepAspectLabel(true), t.keepAspectLabel(false), t.FROM_CENTER, t.FLIP_HORIZONTALLY, t.FLIP_VERTICALLY,
                    t.DELETE_LABEL, t.DELETE_PICTURE, t.ROTATE_CCW_LABEL, t.ROTATE_CW_LABEL, t.FIT_LABEL, t.RESET_LABEL,
                    "Snap to objects", t.INTERPOLATION_LABEL, t.NUMBERS_LABEL, "Transform", "On", "Off",
                ),
        )
    }

    @Test
    fun theShapeStripInPointsMode() {
        val v18 = values(StripLabels18) + MirrorLabels18.REMOVE
        assertNoSubstrings(
            "Shape strip, Points mode",
            v18 = v18,
            v17 = shapeStrip + values(PillLabels) + values(PointLabels) + listOf(
                "Delete point", "Auto tangent", "Sharp corner", "Smooth", "Handle scale", "Longer handles", "Shorter handles",
                "Previous point", "Next point", "Edit points", "Reset shape", "In and out", "In", "Out", "Corner radius", "Scale",
            ),
        )
        // §3.2: the point-edit undo / redo leave the strip; no v1.8 text names them.
        for (gone in listOf("Undo point edit", "Redo point edit")) {
            assertTrue(gone, labels.values.none { it.contains(gone) || gone.contains(it) })
        }
    }

    @Test
    fun theMoreMenu() {
        assertNoSubstrings(
            "More menu",
            v18 = values(MenuLabels18),
            v17 = listOf(
                "Copy selection", "Copy layer", "Paste", "Import picture", "Import SVG or PDF…", "Export PNG", "Export JPG", "Export SVG…",
                "Export PDF…", "Share", "Canvas…", "Flip view", "Fit to screen", "100% (actual pixels)", "Reset rotation", "Increments…",
                "Save now", "Settings",
            ),
        )
    }

    @Test
    fun theToolMenuWithBrushActive() {
        // The two new tools' names are their ToolId labels (from F3); until then, these texts.
        val newTools = listOf("Magnetic lasso", "Color select")
        val tools = ToolId.entries.map { it.label }.filter { it !in newTools }
        assertNoSubstrings(
            "tool menu",
            v18 = newTools + StripLabels18.MORE_TOGGLE,
            v17 = tools + listOf("Filters", "Canvas", "Settings", "Choose brush", "Brush size", "Opacity", "Pressure changes size"),
        )
        // The one known Pathfinder pair stays the only one (tests click "Path" with exact = true).
        val pairs = substringPairs(tools + newTools).filter { (a, b) -> a == ToolId.PATHFINDER.label || b == ToolId.PATHFINDER.label }
        assertEquals(listOf(ToolId.PATH.label to ToolId.PATHFINDER.label), pairs)
    }

    // ------------------------------------------------------------------ helpers

    /** The module's `src/main/java`, searched upwards from the working directory; null when absent. */
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

    private object LabelsV17Constants {
        fun all(): Set<String> = listOf(
            FolderLabels, ArrayLabels, PointLabels, PillLabels, CurveLabels17, SavedSelectionLabels,
            TransformLabels17, SymmetryLabels, PathfinderLabels, KerningLabels, ExpressionLabels, V17Tags,
        ).flatMap { o ->
            o.javaClass.declaredFields
                .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
                .map { f -> f.isAccessible = true; f.get(null) as String }
        }.toSet()
    }
}

/**
 * The string literals of a Kotlin source, as a small state machine: line and (nested) block
 * comments are skipped, character literals and backtick names too; a plain string's escapes are
 * decoded; a raw string is read as written; a template's `$name` / `${…}` becomes U+0000 (so it
 * never equals a label), and the strings inside `${…}` are collected as literals of their own.
 */
internal object KotlinLiterals {
    fun of(src: String): List<String> = Scanner(src).also { it.code(untilBrace = false) }.out

    private class Scanner(val s: String) {
        var i = 0
        val out = mutableListOf<String>()

        private fun at(k: Int): Char = if (i + k < s.length) s[i + k] else '\u0000'

        fun code(untilBrace: Boolean) {
            var depth = 0
            while (i < s.length) {
                val c = s[i]
                when {
                    c == '/' && at(1) == '/' -> while (i < s.length && s[i] != '\n') i++
                    c == '/' && at(1) == '*' -> blockComment()
                    c == '"' && at(1) == '"' && at(2) == '"' -> { i += 3; string(raw = true) }
                    c == '"' -> { i++; string(raw = false) }
                    c == '\'' -> charLiteral()
                    c == '`' -> { i++; while (i < s.length && s[i] != '`' && s[i] != '\n') i++; i++ }
                    c == '{' -> { depth++; i++ }
                    c == '}' -> {
                        i++
                        if (untilBrace && depth == 0) return
                        depth--
                    }
                    else -> i++
                }
            }
        }

        private fun blockComment() {
            var depth = 0
            while (i < s.length) {
                when {
                    s.startsWith("/*", i) -> { depth++; i += 2 }
                    s.startsWith("*/", i) -> { depth--; i += 2; if (depth == 0) return }
                    else -> i++
                }
            }
        }

        private fun charLiteral() {
            i++ // the opening quote
            if (at(0) == '\\') i += 2 else i++
            while (i < s.length && s[i] != '\'' && s[i] != '\n') i++
            i++ // the closing quote
        }

        private fun string(raw: Boolean) {
            val sb = StringBuilder()
            while (i < s.length) {
                val c = s[i]
                if (raw && c == '"' && at(1) == '"' && at(2) == '"') {
                    // A raw string ends at the LAST three quotes of a run.
                    var j = i
                    while (j < s.length && s[j] == '"') j++
                    repeat(j - i - 3) { sb.append('"') }
                    i = j
                    out += sb.toString()
                    return
                }
                if (!raw && c == '"') { i++; out += sb.toString(); return }
                if (!raw && c == '\n') { out += sb.toString(); return } // unterminated: stop at the line's end
                if (!raw && c == '\\') { sb.append(escape()); continue }
                if (c == '$' && at(1) == '{') {
                    i += 2
                    code(untilBrace = true)
                    sb.append('\u0000')
                    continue
                }
                if (c == '$' && (at(1).isLetter() || at(1) == '_')) {
                    i++
                    while (i < s.length && (s[i].isLetterOrDigit() || s[i] == '_')) i++
                    sb.append('\u0000')
                    continue
                }
                sb.append(c)
                i++
            }
            out += sb.toString()
        }

        private fun escape(): String {
            val n = at(1)
            i += 2
            return when (n) {
                't' -> "\t"; 'b' -> "\b"; 'n' -> "\n"; 'r' -> "\r"
                'u' -> {
                    val hex = s.substring(i, minOf(i + 4, s.length))
                    i += hex.length
                    hex.toIntOrNull(16)?.toChar()?.toString() ?: ""
                }
                else -> n.toString() // \" \' \\ \$
            }
        }
    }
}
