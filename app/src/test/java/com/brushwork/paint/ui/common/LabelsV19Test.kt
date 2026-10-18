package com.brushwork.paint.ui.common

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.art.ChromaticAberrationMovingFilter
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.filters.newSeedDescription
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * v1.9 F0 (design 2.2, 2.3.6, I10): the labels pre-declared in `LabelsV19.kt`, with the four rules
 * of [LabelsV18Test]. The objects are read by reflection, so the test compiles and passes while
 * `LabelsV19` is absent (F0) and checks the file from F1a on without an edit:
 * 1. Every value is unique and not blank (the tags too), without outer spaces (a `*_SUFFIX` or
 *    `*_PREFIX` may have them), and every object of 2.3.6 is there.
 * 2. No value equals a string literal of the main sources outside `LabelsV19.kt`, except
 *    `LabelsV19.SHARED`, each of which has its twin (rule L: a label is used through its constant,
 *    never retyped); nor is one quoted in a comment.
 * 3. No tag is a label, nor a v1.7 or v1.8 label or tag; no v1.9 label outside `SHARED` is a v1.7
 *    or v1.8 pre-declared label.
 * 4. On each screen of 2.3.6, no handle and no visible text is a substring of another, where one
 *    of the two is a v1.9 text, except the [allowed] pairs of 2.3.6 and the texts found by tag
 *    ([byTag]). A label with `%s` / `%d` is compared as written.
 * JVM: the main sources are read from disk (the module's `src/main/java`). FROZEN for the areas
 * (5.4 `$FrozenTests`): a new label or screen is the lead's (`LeadRequest`).
 */
class LabelsV19Test {

    private fun load(name: String): Class<*>? = try {
        Class.forName("$PKG.$name")
    } catch (_: ClassNotFoundException) {
        null
    }

    /** `LabelsV19`, or null before F1a (every rule then passes trivially). */
    private val labelsV19: Class<*>? by lazy { load("LabelsV19") }

    /** The objects of 2.3.6 and any other object declared in the main `LabelsV19.kt`, those that exist. */
    private val objects: List<Class<*>> by lazy { (DESIGN_OBJECTS + objectsInSource()).distinct().mapNotNull(::load) }

    /** "Object.NAME" to value, for every `const val` String of [classes]. */
    private fun constants(classes: List<Class<*>>): Map<String, String> = classes.flatMap { c ->
        c.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { f -> f.isAccessible = true; "${c.simpleName}.${f.name}" to (f.get(null) as String) }
    }.toMap()

    private fun constants(vararg objs: Any): Map<String, String> = constants(objs.map { it.javaClass })

    private val labels: Map<String, String> by lazy { constants(objects.filter { it.simpleName != "LabelsV19" && !it.simpleName.endsWith("Tags") }) }
    private val tags: Map<String, String> by lazy { constants(objects.filter { it.simpleName.endsWith("Tags") }) }

    /** `LabelsV19.SHARED`. */
    private val shared: Set<String> by lazy {
        val c = requireNotNull(labelsV19)
        val f = c.declaredFields.firstOrNull { it.name == "SHARED" }
        assertNotNull("LabelsV19.SHARED is declared", f)
        f!!.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (f.get(null) as Set<String>)
    }

    /** The values of [fields] of the object [obj] that exist (a renamed one drops out, rule 1 catches a missing object). */
    private fun pick(obj: String, vararg fields: String): List<String> {
        val c = objects.firstOrNull { it.simpleName == obj } ?: return emptyList()
        return fields.mapNotNull { name -> c.declaredFields.firstOrNull { it.name == name }?.let { f -> f.isAccessible = true; f.get(null) as? String } }
    }

    /** Every value of the object [obj]. */
    private fun all(obj: String): List<String> = objects.firstOrNull { it.simpleName == obj }?.let { constants(listOf(it)).values.toList() }.orEmpty()

    @Test
    fun everyV19LabelIsUniqueAndNotBlank() {
        if (labelsV19 == null) return
        val missing = DESIGN_OBJECTS.filter { load(it) == null }
        assertTrue("the objects of 2.3.6 are declared: missing $missing", missing.isEmpty())
        val all = labels
        assertTrue("the objects have their labels", all.isNotEmpty())
        val dups = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("labels declared twice: $dups", dups.isEmpty())
        val spaced = all.filter { (k, v) -> v.isBlank() || (v != v.trim() && !k.endsWith("SUFFIX") && !k.endsWith("PREFIX")) }
        assertTrue("blank, or spaces around: $spaced", spaced.isEmpty())
        assertTrue("tags declared", tags.isNotEmpty())
        val tagDups = tags.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("tags declared twice: $tagDups", tagDups.isEmpty())
        assertTrue("tags not blank", tags.values.none { it.isBlank() || it != it.trim() })
        assertTrue("every shared value is a v1.9 label: ${shared - all.values.toSet()}", all.values.containsAll(shared))
    }

    @Test
    fun noLabelOrTagIsRetypedInTheMainSources() {
        if (labelsV19 == null) return
        val root = mainSources()
        assertNotNull("the main sources (src/main/java) are found from ${System.getProperty("user.dir")}", root)
        val files = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name != "LabelsV19.kt" }.toList()
        assertTrue("the main sources are read: ${files.size}", files.size > 300)
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
            // The ownership script's rule L greps `"value"` in the raw text, comments included.
            for (v in labelValues + tagValues) if (v !in shared && text.contains("\"$v\"")) quoted += "${f.name}: \"$v\""
        }
        assertTrue("a v1.9 label or tag typed as a literal outside LabelsV19 (rule L): $retyped", retyped.isEmpty())
        assertTrue("a v1.9 label or tag quoted outside LabelsV19 (a comment hides it from rule L): $quoted", quoted.isEmpty())
        // Each shared label has its twin (else it is exempted for nothing).
        assertEquals("shared labels with a twin in the main sources", shared, sharedSeen)
    }

    @Test
    fun noTagIsALabel() {
        if (labelsV19 == null) return
        val all = labels.values.toSet()
        assertTrue("a tag is never a label: ${tags.values.filter { it in all }}", tags.values.none { it in all })
        val older = LabelsV17Constants.all() + constants(*V18_LABEL_OBJECTS.toTypedArray()).values + constants(V18Tags).values
        assertTrue("a v1.9 tag is a v1.7 or v1.8 label or tag: ${tags.values.filter { it in older }}", tags.values.none { it in older })
        val again = all.filter { it in older && it !in shared }
        assertTrue("a v1.9 label equals a v1.7 or v1.8 pre-declared label: $again", again.isEmpty())
    }

    @Test
    fun theLabelObjectsAreReadByReflection() {
        // Always active: the reflection this test relies on reads the v1.8 objects, and a main
        // LabelsV19.kt is compiled with all of its objects.
        val v18 = constants(listOfNotNull(load("StripLabels18"), load("V18Tags")))
        assertEquals(constants(StripLabels18, V18Tags), v18)
        assertTrue(v18.isNotEmpty())
        val source = mainSources()?.let { File(it, "ui/common/LabelsV19.kt") }
        if (source != null && source.isFile) {
            assertNotNull("LabelsV19.kt in the main sources declares LabelsV19", labelsV19)
            val declared = objectsInSource()
            assertTrue("the objects of LabelsV19.kt load: $declared", declared.all { load(it) != null })
        }
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

    private fun assertNoSubstrings(screen: String, v19: List<String>, older: List<String>, byTag: Set<String> = emptySet()) {
        val mine = v19.distinct()
        val pairs = substringPairs(mine + older.distinct())
            .filter { (a, b) -> (a in mine || b in mine) && (a to b) !in allowed && a !in byTag && b !in byTag }
        assertTrue("$screen: a v1.9 text inside another (SmokeUi would match the wrong one): $pairs", pairs.isEmpty())
    }

    private fun values(o: Any): List<String> = constants(o).values.toList()

    /**
     * The texts a person could read in the main source files [paths] (under `com/brushwork/paint`):
     * their string literals that start with a capital or a digit, hold a letter, and are not
     * templates.
     */
    private fun visible(vararg paths: String): List<String> {
        val root = mainSources() ?: return emptyList()
        return paths.flatMap { p -> File(root, p).takeIf { it.isFile }?.let { KotlinLiterals.of(it.readText()) }.orEmpty() }
            .filter { s -> s.isNotBlank() && '\u0000' !in s && '\n' !in s && s.any { it.isLetter() } && (s[0].isUpperCase() || s[0].isDigit()) }
            .distinct()
    }

    /** The six bullet chips and the eight `TextNumbering` samples (2.3.6): found by tag, never by text. */
    private val byTag: Set<String> by lazy {
        val samples = try {
            val c = Class.forName("com.brushwork.paint.tools.text.TextNumbering")
            (c.enumConstants ?: emptyArray()).map { e -> c.getMethod("getSample").invoke(e) as String }
        } catch (_: ReflectiveOperationException) {
            listOf("1.", "1)", "(1)", "a.", "A.", "i.", "I.", "①")
        }
        (listOf("•", "◦", "▪", "–", "★", "✓") + samples).toSet()
    }

    private val eraseModes: List<String> = VectorEraseMode.entries.flatMap { listOf(it.label, it.description) }

    /**
     * The selection bar's "Cut" item and its description (`SelectionBar.kt`), shown over any tool
     * while a selection exists: no v1.9 erase text may hold it (fact V12, the lead's ruling 3).
     */
    private val selectionBarCut: List<String> = listOf("Cut", "Cut selection")

    /** A filter panel: its parameters' labels and their controls' descriptions, and the panel's own buttons (as [LabelsV18Test]). */
    private fun panel(f: Filter): List<String> = f.params.flatMap { p ->
        when (p) {
            is FilterParam.Slider -> listOf(p.label, "Decrease ${p.label}", "Increase ${p.label}", "Reset ${p.label}")
            is FilterParam.Choice -> listOf(p.label) + p.options
            is FilterParam.Seed -> listOf(p.label, newSeedDescription(p.label))
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
    fun theTextDialogWithJustifyListsAndKerning() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "text dialog",
            v19 = pick(
                "TextLabels19", "JUSTIFY", "JUSTIFY_LAST", "JUSTIFY_COLUMNS", "JUSTIFY_LAST_COLUMN", "LAST_LINE", "LAST_COLUMN", "WORDS",
                "LISTS", "NO_LIST", "BULLETS", "NUMBERING", "LIST_SETTINGS", "FIRST_NUMBER", "MARKER_SIZE", "MARKER_THICKNESS", "MARKER_GAP",
                "MARKER_FONT", "SAME_AS_TEXT", "OTHER_BULLET", "BULLET_DOT", "BULLET_CIRCLE", "BULLET_SQUARE", "BULLET_DASH", "BULLET_STAR",
                "BULLET_CHECK", "LISTS_HORIZONTAL_ONLY",
            ) + all("KernLabels19") + byTag,
            // The Text strip's "Numbers" under the dialog, and the text effects' "Spread".
            older = visible("ui/placement/TextEditorDialog.kt", "ui/placement/TextKerningSection.kt", "ui/placement/TextLetterScaleSection.kt") +
                values(KerningLabels) + listOf("Numbers", "Spread"),
            byTag = byTag,
        )
    }

    @Test
    fun theTextPathSheetWhileEditingItsPoints() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "text path sheet and its node bar",
            v19 = pick(
                "TextLabels19", "ADD_POINTS", "EDIT_AS_PATH", "EDIT_AS_POLYLINE", "DONE_POINTS", "NODES_STARTED", "POINT_COUNT", "NODES_HINT",
                "NODES_MAX",
            ),
            older = visible("ui/placement/TextPathSheet.kt") + listOf("Add point", "Delete point", "Sharp corner"),
        )
    }

    @Test
    fun theTextStripWithItsMoreRow() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "Text strip, More row",
            v19 = pick("PlaceLabels19", "NEW_LAYER_EACH_TIME", "NEW_LAYER_EACH_TIME_DESC") + pick("TextLabels19", "LAYER_FULL", "RASTERIZED_TEXTS"),
            older = visible("ui/placement/TextStripItems.kt") + values(StripLabels18) + listOf("Bring forward", "Send backward"),
        )
    }

    @Test
    fun thePointsModeWithTheBevelPopup() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "Points mode (Curve, Polyline, Path, Shape) with the bevel popup",
            v19 = all("PointLabels19"),
            older = curveStrip + shapeStrip + values(PointLabels) + values(PillLabels) + values(StripLabels18) +
                listOf("Thickness", "Thinner point", "Thicker point", "Type the point thickness", "Point thickness slider", "Mixed"),
        )
    }

    @Test
    fun theCurveAndShapeStripsWhileErasing() {
        if (labelsV19 == null) return
        val erasing = listOf("MODE_CELL", "INSTEAD_OF_PAINT", "TOO_COMPLEX", "COPIES_BAKED")
        assertNoSubstrings(
            "Curve, Polyline and Path strips",
            v19 = all("CurveLabels19") + pick("EraseLabels19", "WITH_CURVE", "WITH_POLYLINE", "WITH_PATH", "ERASING_CURVE", "ERASING_POLYLINE", "ERASING_PATH", *erasing.toTypedArray()),
            older = curveStrip + values(StripLabels18) + values(PillLabels) + listOf(MirrorLabels18.DETACH, MirrorLabels18.REMOVE) + eraseModes + selectionBarCut,
        )
        assertNoSubstrings(
            "Shape strip with the cog",
            v19 = pick("PlaceLabels19", "NEW_LAYER_EACH_TIME", "SHAPE_EDITABLE_DESC") + pick("EraseLabels19", "WITH_SHAPE", "ERASING_SHAPE", *erasing.toTypedArray()),
            older = shapeStrip + values(StripLabels18) + values(PillLabels) + values(CogLabels18) + eraseModes + selectionBarCut,
        )
    }

    @Test
    fun theEraserOnAVectorLayer() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "Eraser on a vector layer, its More row and the pixels dialog",
            v19 = pick("EraseLabels19", "TURN_INTO_PIXELS", "PIXELS_TITLE", "PIXELS_BODY", "PIXELS_CONFIRM", "PARTIAL_NOTE", "TOO_COMPLEX", "COPIES_BAKED"),
            // The selection bar's "Cut" over the eraser while a selection exists (fact V12).
            older = eraseModes + visible("ui/vector/VectorEraserOptions.kt") + values(StripLabels18) + listOf("Cancel", "Opacity") + selectionBarCut,
        )
    }

    @Test
    fun aFilterPanelWithItsResets() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "Chromatic aberration panel",
            v19 = pick("FilterLabels19", "RESET_ONE", "SESSION_AT_START"),
            older = panel(ChromaticAberrationMovingFilter()) + listOf("Blend mode", "Opacity"),
        )
    }

    @Test
    fun theSettingsWithDiagnosticsAndBackup() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "Settings",
            v19 = pick(
                "StabilityLabels19", "DIAGNOSTICS", "CRASH_REPORTS", "DELETE_REPORTS", "COPY_REPORT", "SHARE_REPORT", "SHARE_ALL", "REPORT_COPIED",
                "SHOW_FRAME_TIMES", "DRAWING_PREVIEW", "FASTER", "EXACT_PIXELS", "DRAWING_PREVIEW_NOTE", "FAST_ADJUST_NOTE",
            ) + pick(
                "BackupLabels19", "BACKUP", "FOLDER", "NOW", "RESTORE", "SAVE_ALL", "OPEN_FILE", "BACKED_UP", "NOT_SET", "USES", "CHANGE_FOLDER",
                "PROGRESS", "PICK_HINT", "FOUND", "RESTORE_THEM", "BACK_UP_HERE",
            ),
            older = visible("ui/editor/EditorSettings.kt") + FAST_ADJUST_PREVIEW_LABEL,
        )
    }

    @Test
    fun theGalleryCardsAndTheNewCanvasDialog() {
        if (labelsV19 == null) return
        // The gallery's own texts are on other screens than the cards (the artwork menu's "Share"
        // and "Duplicate" are the twins of SHARED); the cards' texts must not meet each other.
        assertNoSubstrings(
            "gallery with the crash card and the backup banner",
            v19 = pick("StabilityLabels19", "CRASH_TITLE", "CRASH_SAVED", "CRASH_OOM", "COPY", "SHARE", "DISMISS", "REPORT_COPIED") +
                pick("BackupLabels19", "BANNER_NOT_SET", "CHOOSE_FOLDER", "LATER", "UNAVAILABLE", "CHOOSE_AGAIN", "RESTORED_COUNT"),
            older = emptyList(),
        )
        assertNoSubstrings(
            "new canvas dialog",
            v19 = pick("CanvasLabels19", "PAPER", "WHITE_NOTE", "TRANSPARENT_NOTE", "PAPER_NOTE"),
            older = visible("ui/gallery/NewCanvasDialog.kt") + listOf("White", "Transparent", "Color…"),
        )
        assertNoSubstrings(
            "restore list",
            v19 = pick("BackupLabels19", "RESTORE_TITLE", "RESTORE_CONFIRM", "ALSO_SETTINGS", "ALREADY", "DELETED_SECTION"),
            older = listOf("Cancel"),
        )
    }

    @Test
    fun theMergeDialogTheSelectionBarAndTheExportSheet() {
        if (labelsV19 == null) return
        assertNoSubstrings(
            "merge dialog over the layers",
            v19 = pick(
                "LayerLabels19", "MERGE_TITLE", "MERGE_BODY", "MERGE_CONFIRM", "KEEP_EDITABLE", "KEEP_EDITABLE_NOTE", "MERGED_AS_PIXELS",
                "WHY_MASK", "WHY_BLEND", "WHY_CLIP", "WHY_COPIES", "WHY_KINDS", "WHY_FRAMES", "WHY_OVERLAP", "WHY_TOO_MANY", "WHY_OPACITY",
            ),
            older = listOf("Cancel", "Merge down", "Merge visible"),
        )
        // The selection bar: v1.8's items with their click labels ("Duplicate" itself is SHARED,
        // and the button shows it with its own description).
        assertNoSubstrings(
            "selection bar",
            v19 = pick("LayerLabels19", "DUPLICATE_DESC", "DUPLICATED", "NOTHING_TO_DUPLICATE"),
            older = visible("ui/editor/SelectionBar.kt"),
        )
        assertNoSubstrings(
            "SVG and PDF export options",
            v19 = pick("CanvasLabels19", "INCLUDE_PAPER"),
            older = visible("ui/exchange/ExportOptionsSheet.kt"),
        )
    }

    // ------------------------------------------------------------------ helpers

    /** The names of the objects the main `ui/common/LabelsV19.kt` declares (none before F1a). */
    private fun objectsInSource(): List<String> {
        val f = mainSources()?.let { File(it, "ui/common/LabelsV19.kt") }?.takeIf { it.isFile } ?: return emptyList()
        return Regex("""^(?:internal\s+|public\s+)?object\s+(\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { it.groupValues[1] }.toList()
    }

    /** The module's `src/main/java/com/brushwork/paint`, searched upwards from the working directory; null when absent. */
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

    private companion object {
        const val PKG = "com.brushwork.paint.ui.common"

        /** The objects of 2.3.6, in its order. */
        val DESIGN_OBJECTS = listOf(
            "LabelsV19", "PointLabels19", "CurveLabels19", "PlaceLabels19", "TextLabels19", "KernLabels19", "FilterLabels19",
            "StabilityLabels19", "EraseLabels19", "LayerLabels19", "CanvasLabels19", "BackupLabels19", "V19Tags",
        )

        val V18_LABEL_OBJECTS: List<Any> = listOf(
            MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
            CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
        )

        /**
         * The `allowed` pairs of 2.3.6: "Bevel" is the Shape cog's corner style and "Bevel points"
         * shows only in Points mode; "Add points" is on the text path sheet and "Add point" on the
         * node bar that replaces it.
         * F1a adds three, where both texts are controls (the lead's ruling: a button label is never
         * reworded) and tests find the v1.9 one by its tag: the export sheet's hidden-layers chip
         * "Include" and the paper switch; the Settings section "Backup" and its folder row; the
         * kerning chip "Off" (V19Tags.KERN_PREFIX) and the letter-scale row's value "Off" on the
         * text dialog.
         */
        val allowed: Set<Pair<String, String>> = setOf(
            "Bevel" to "Bevel points", "Add point" to "Add points",
            "Include" to "Include paper", "Backup" to "Backup folder…", "Off" to "Off",
        )
    }
}
