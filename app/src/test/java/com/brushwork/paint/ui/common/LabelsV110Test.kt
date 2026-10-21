package com.brushwork.paint.ui.common

import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterParam
import com.brushwork.paint.filters.adjust.ChangeDrawingColorFilter
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.PickerMode
import com.brushwork.paint.ui.filters.newSeedDescription
import com.brushwork.paint.ui.mask.FAST_ADJUST_PREVIEW_LABEL
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.File
import java.lang.reflect.Modifier

/**
 * v1.10 F0 (design 3.2, C18, I10): the labels of `LabelsV110.kt`, with the four rules of
 * [LabelsV19Test] (which this test does not edit):
 * 1. Every value is unique and not blank (the tags too), without outer spaces, and every object of
 *    3.2 is there; `LabelsV110.SHARED` holds v1.10 labels only.
 * 2. No value equals a string literal of the main sources outside `LabelsV110.kt`, except
 *    `LabelsV110.SHARED`, each of which has its twin: a literal elsewhere (v1.9's screens) or its
 *    constant used outside `LabelsV110.kt` (once an area moves a v1.9 row to the constant); nor is
 *    one quoted in a comment.
 * 3. No tag is a label, nor a v1.7, v1.8 or v1.9 label or tag; no v1.10 label outside `SHARED` is a
 *    v1.7, v1.8 or v1.9 pre-declared label.
 * 4. On each screen of 1.10, no handle and no visible text is a substring of another, where one of
 *    the two is a v1.10 text, except the [allowed] pairs and the texts found by tag (per screen).
 *    The screens the areas write (`ui/vector/ObjectColorsSheet.kt`, the files of `ui/engine`) are read once
 *    they exist. A label with `%s` / `%d` is compared as written.
 * JVM: the main sources are read from disk (the module's `src/main/java`). FROZEN for the areas
 * (`$FrozenTests`): a new label, screen or exception is the lead's.
 */
class LabelsV110Test {

    private fun load(name: String): Class<*>? = try {
        Class.forName("$PKG.$name")
    } catch (_: ClassNotFoundException) {
        null
    }

    /** The objects of 3.2 and any other object declared in the main `LabelsV110.kt`. */
    private val objects: List<Class<*>> by lazy { (DESIGN_OBJECTS + objectsInSource()).distinct().mapNotNull(::load) }

    /** "Object.NAME" to value, for every `const val` String of [classes]. */
    private fun constants(classes: List<Class<*>>): Map<String, String> = classes.flatMap { c ->
        c.declaredFields
            .filter { Modifier.isStatic(it.modifiers) && it.type == String::class.java }
            .map { f -> f.isAccessible = true; "${c.simpleName}.${f.name}" to (f.get(null) as String) }
    }.toMap()

    private fun constants(vararg objs: Any): Map<String, String> = constants(objs.map { it.javaClass })

    private val labels: Map<String, String> by lazy { constants(objects.filter { it.simpleName != "LabelsV110" && !it.simpleName.endsWith("Tags") }) }
    private val tags: Map<String, String> by lazy { constants(objects.filter { it.simpleName.endsWith("Tags") }) }

    /** `LabelsV110.SHARED`. */
    private val shared: Set<String> by lazy {
        val c = requireNotNull(load("LabelsV110")) { "LabelsV110 is declared" }
        val f = c.declaredFields.firstOrNull { it.name == "SHARED" }
        assertNotNull("LabelsV110.SHARED is declared", f)
        f!!.isAccessible = true
        @Suppress("UNCHECKED_CAST")
        (f.get(null) as Set<String>)
    }

    /** The values of [fields] of the object [obj] (a renamed field fails: rule 1 lists what exists). */
    private fun pick(obj: String, vararg fields: String): List<String> {
        val c = requireNotNull(objects.firstOrNull { it.simpleName == obj }) { "$obj is declared" }
        return fields.map { name ->
            val f = requireNotNull(c.declaredFields.firstOrNull { it.name == name }) { "$obj.$name is declared" }
            f.isAccessible = true
            f.get(null) as String
        }
    }

    /** Every value of the object [obj]. */
    private fun all(obj: String): List<String> = constants(listOf(requireNotNull(objects.firstOrNull { it.simpleName == obj }) { "$obj is declared" })).values.toList()

    @Test
    fun everyV110LabelIsUniqueAndNotBlank() {
        val missing = DESIGN_OBJECTS.filter { load(it) == null }
        assertTrue("the objects of 3.2 are declared: missing $missing", missing.isEmpty())
        val all = labels
        assertTrue("the objects have their labels", all.size >= 70)
        val dups = all.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("labels declared twice: $dups", dups.isEmpty())
        val spaced = all.filter { (_, v) -> v.isBlank() || v != v.trim() }
        assertTrue("blank, or spaces around: $spaced", spaced.isEmpty())
        assertTrue("tags declared", tags.size >= 11)
        val tagDups = tags.entries.groupBy({ it.value }, { it.key }).filterValues { it.size > 1 }
        assertTrue("tags declared twice: $tagDups", tagDups.isEmpty())
        assertTrue("tags not blank", tags.values.none { it.isBlank() || it != it.trim() })
        assertTrue("every shared value is a v1.10 label: ${shared - all.values.toSet()}", all.values.containsAll(shared))
        assertEquals(setOf("Fill color", "Text color", "Outline color"), shared)
        // LA4: a picked side is moved, turned and flipped only; there is no unlink.
        assertTrue(labels.containsKey("MirrorLabels110.SIDE_RESIZE_BLOCKED"))
        assertTrue(labels.keys.none { it.startsWith("MirrorLabels110.") && it.contains("UNLINK") })
        // The functions of 3.2, with their texts.
        assertEquals("1 layer", LayersLabels110.count(1))
        assertEquals("12 layers", LayersLabels110.count(12))
        assertEquals("This canvas is too big for this phone's memory. The largest is about 8000 × 6000.", LayersLabels110.canvasTooBig(8000, 6000))
        assertTrue(EngineLabels110.LMK_NOTICE.endsWith("To change it, ${EngineLabels110.SETTINGS_PATH}."))
    }

    @Test
    fun noLabelOrTagIsRetypedInTheMainSources() {
        val root = mainSources()
        assertNotNull("the main sources (src/main/java) are found from ${System.getProperty("user.dir")}", root)
        val files = root!!.walkTopDown().filter { it.isFile && it.extension == "kt" && it.name !in setOf("LabelsV110.kt", "V110Tags.kt") }.toList()
        assertTrue("the main sources are read: ${files.size}", files.size > 300)
        val labelValues = labels.values.toSet()
        val tagValues = tags.values.toSet()
        val sharedNames = labels.filterValues { it in shared }
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
            // A shared label's twin may also be its constant, used outside LabelsV110.kt.
            for ((name, v) in sharedNames) if (Regex("""\b${Regex.escape(name)}\b""").containsMatchIn(text)) sharedSeen += v
            // The ownership script's rule L greps `"value"` in the raw text, comments included.
            for (v in labelValues + tagValues) if (v !in shared && text.contains("\"$v\"")) quoted += "${f.name}: \"$v\""
        }
        assertTrue("a v1.10 label or tag typed as a literal outside LabelsV110.kt and V110Tags.kt (rule L): $retyped", retyped.isEmpty())
        assertTrue("a v1.10 label or tag quoted outside LabelsV110 (a comment hides it from rule L): $quoted", quoted.isEmpty())
        assertEquals("shared labels with a twin in the main sources", shared, sharedSeen)
    }

    @Test
    fun noTagIsALabel() {
        val all = labels.values.toSet()
        assertTrue("a tag is never a label: ${tags.values.filter { it in all }}", tags.values.none { it in all })
        val older = LabelsV17Constants.all() + constants(*V18_LABEL_OBJECTS.toTypedArray()).values + constants(V18Tags).values +
            constants(V19_OBJECTS.mapNotNull(::load)).values
        assertTrue("v1.9's objects are read: ${V19_OBJECTS.filter { load(it) == null }}", V19_OBJECTS.all { load(it) != null })
        assertTrue("a v1.10 tag is a v1.7, v1.8 or v1.9 label or tag: ${tags.values.filter { it in older }}", tags.values.none { it in older })
        val again = all.filter { it in older && it !in shared }
        assertTrue("a v1.10 label equals a v1.7, v1.8 or v1.9 pre-declared label: $again", again.isEmpty())
    }

    @Test
    fun theLabelObjectsAreReadByReflection() {
        // The reflection this test relies on, against the objects themselves.
        assertEquals(constants(MirrorLabels110, ObjectColorLabels110, V110Tags), constants(listOfNotNull(load("MirrorLabels110"), load("ObjectColorLabels110"), load("V110Tags"))))
        assertEquals("Fill color", labels["ObjectColorLabels110.FILL"])
        assertEquals("v110.pill.color", tags["V110Tags.PILL_COLOR"])
        val declared = objectsInSource()
        assertTrue("LabelsV110.kt declares the objects of 3.2: $declared", declared.containsAll(DESIGN_OBJECTS - "V110Tags"))
        assertTrue("the objects of LabelsV110.kt load: $declared", declared.all { load(it) != null })
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

    private fun assertNoSubstrings(screen: String, v110: List<String>, older: List<String>, byTag: Set<String> = emptySet()) {
        val mine = v110.distinct()
        assertTrue("$screen: the v1.10 texts are listed", mine.isNotEmpty())
        val pairs = substringPairs(mine + (older.distinct() - mine.toSet()))
            .filter { (a, b) -> (a in mine || b in mine) && (a to b) !in allowed && a !in byTag && b !in byTag }
        assertTrue("$screen: a v1.10 text inside another (SmokeUi would match the wrong one): $pairs", pairs.isEmpty())
    }

    private fun values(o: Any): List<String> = constants(o).values.toList()

    /**
     * The texts a person could read in the main source files [paths] (under `com/brushwork/paint`):
     * their string literals that start with a capital or a digit, hold a letter, and are not
     * templates. A file an area has not written yet gives nothing.
     */
    private fun visible(vararg paths: String): List<String> {
        val root = mainSources() ?: return emptyList()
        return paths.flatMap { p -> File(root, p).takeIf { it.isFile }?.let { KotlinLiterals.of(it.readText()) }.orEmpty() }
            .filter { s -> s.isNotBlank() && '\u0000' !in s && '\n' !in s && s.any { it.isLetter() } && (s[0].isUpperCase() || s[0].isDigit()) }
            .distinct()
    }

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

    private val curveStrip by lazy {
        listOf(
            "Stroke kind", "Line width", "Add point", "Delete point", "Closed", "Curve settings", "Sharp corner", "Auto tangent",
            "Point thickness", "Handles", "Select all points", "Deselect all points", "Previous point", "Next point", "Use brush size",
            "Taper ends", "Numbers", "Shapes", "Path shapes", "Cyclic", "Order 4", "Endpoint", "Point weight", "Path settings",
            "Polyline settings", "Undo last point", "Redo point",
        ) + values(CurveLabels17)
    }

    private val shapeStrip by lazy {
        listOf(
            "Shape type", "Shape style", "Line ends", "Stroke width", "Points", "Editable (own layer)", "Stroke with", "Center",
            "Proportional", "Circle", "15°", "Snap to objects", "Numbers", "Shape settings", "Select all points", "Deselect all points",
        ) + ShapeType.entries.map { it.label } + ShapeStyle.entries.map { it.label }
    }

    /** R3: the paint-mode segments' names, CurveLabels17's trio. */
    private val paintModes = listOf(CurveLabels17.STROKE_ONLY, CurveLabels17.FILL_ONLY, CurveLabels17.BOTH)

    /** The color body under the Object colors sheet's row (v1.9's picker: comparison, hex, modes, palette and recents). */
    private val colorBody by lazy {
        visible("ui/color/ColorPickerDialog.kt", "ui/color/ColorControls.kt", "ui/color/PaletteSection.kt") +
            PickerMode.entries.map { it.label } + listOf("Cancel", "OK")
    }

    @Test
    fun theTransformPillWithOneSidePicked() {
        assertNoSubstrings(
            "Transform pill with one side picked",
            v110 = all("MirrorLabels110"),
            older = visible("ui/placement/TransformStripItems.kt") + values(PillLabels) + values(TransformLabels17) + values(TransformLabels18) +
                values(MirrorLabels18) + values(StripLabels18) + values(SymmetryLabels),
        )
    }

    @Test
    fun theObjectColorsSheet() {
        // The tabs and the paint-mode glyphs are icons, found by tag (V110Tags.COLORS_SHEET_TAB,
        // COLORS_SHEET_MODE); their names are the shared texts and CurveLabels17's trio.
        val tabsAndModes = setOf(ObjectColorLabels110.FILL, ObjectColorLabels110.TEXT, ObjectColorLabels110.OUTLINE) + paintModes
        assertEquals(setOf("Fill color", "Text color", "Outline color", "Stroke only", "Fill only", "Stroke and fill"), tabsAndModes)
        assertNoSubstrings(
            "Object colors sheet over the color body",
            v110 = all("ObjectColorLabels110") - ObjectColorLabels110.CELL + paintModes,
            older = colorBody + visible("ui/vector/ObjectColorsSheet.kt"),
            byTag = tabsAndModes,
        )
        assertNoSubstrings(
            "the pill's Object colors cell",
            v110 = pick("ObjectColorLabels110", "CELL"),
            older = values(PillLabels) + values(StripLabels18) + values(TransformLabels17) + values(TransformLabels18),
        )
    }

    @Test
    fun theSelectionBarAndTheObjectBar() {
        assertNoSubstrings(
            "selection bar",
            v110 = pick("ArrayLabels110", "BEHIND", "BEHIND_DESC") + pick("ObjectBarLabels110", "EDIT", "EDIT_DESC"),
            older = visible("ui/editor/SelectionBar.kt") + listOf(ArrayLabels.BUTTON, ArrayLabels.FROM_SELECTION) +
                pick19("LayerLabels19", "DUPLICATE_DESC") + listOf("Duplicate", "Cut", "Cut selection"),
        )
        assertNoSubstrings(
            "object bar with its More row",
            v110 = pick("ObjectBarLabels110", "EDIT", "EDIT_DESC", "TO_SHAPE") + pick("ArrayLabels110", "BEHIND", "BEHIND_DESC"),
            older = visible("ui/vector/VectorObjectBar.kt") + listOf(ArrayLabels.BUTTON, ArrayLabels.FROM_OBJECTS, PointLabels.TO_PATH) +
                values(StripLabels18),
        )
        assertNoSubstrings(
            "object bar messages",
            v110 = pick(
                "ObjectBarLabels110", "NO_EDITOR_PARTS", "NO_EDITOR_SMALL", "NOTE_GRADIENT", "NOTE_WIDTH", "NOTE_GENERATED", "NOTE_OPEN_FILL",
                "REFUSE_PIECES", "REFUSE_SMALL", "REFUSE_POINTS",
            ),
            older = listOf(PointLabels.ARROW_REFUSAL, PointLabels.ROUND_REFUSAL, "Undo"),
        )
    }

    @Test
    fun theArraySheetAndTheLayerMenu() {
        assertNoSubstrings(
            "Array sheet, Look tab",
            v110 = pick("ArrayLabels110", "TINTED", "RECOLORED", "RECOLOR_STRENGTH"),
            older = visible("ui/array/ArrayLookTab.kt", "ui/array/ArraySheet.kt") + values(ArrayLabels18) + values(ArrayLabels),
        )
        assertNoSubstrings(
            "Array sheet with the Perspective subtitle and the behind hint",
            v110 = pick("ArrayLabels110", "BEHIND_HINT") + pick("MirrorLabels110", "PERSPECTIVE_SEPARATE"),
            older = visible("ui/array/ArraySheet.kt", "ui/array/ArrayStrip.kt") + values(ArrayLabels18) + values(ArrayLabels),
        )
        assertNoSubstrings(
            "layer menu of an array layer",
            v110 = pick("ArrayLabels110", "LAYER_MENU", "REMOVE_WITH_LAYER"),
            older = visible("ui/layers/LayerControls.kt") + listOf(
                ArrayLabels.EDIT, ArrayLabels.FINISH_SOURCE, ArrayLabels.EDIT_SOURCE, ArrayLabels.APPLY, ArrayLabels.REMOVE, ArrayLabels.OPEN,
            ),
        )
    }

    @Test
    fun theChangeDrawingColorPanelOverObjects() {
        assertNoSubstrings(
            "Change Drawing Color panel",
            v110 = all("CdcLabels110"),
            older = panel(ChangeDrawingColorFilter()) + listOf("Blend mode", "Opacity"),
        )
    }

    @Test
    fun thePointsModeWithTheSharpCell() {
        assertNoSubstrings(
            "Points mode (Curve, Polyline, Path, Shape)",
            v110 = all("PointLabels110"),
            older = curveStrip + shapeStrip + values(PointLabels) + values(PillLabels) + values(StripLabels18) + all19("PointLabels19"),
        )
    }

    @Test
    fun theSettingsRowAndTheLayerStorageSheet() {
        val row = pick("EngineLabels110", "LAYER_STORAGE", "FOR_THIS_PHONE", "SUB_FULL", "SUB_TILES", "SUB_CLASSIC", "SUB_NO_CHECK", "SUB_FULL_CHOSEN", "SUB_CLASSIC_CHOSEN", "CHECK_PENDING")
        assertNoSubstrings(
            "Settings with the Layer storage row",
            v110 = row,
            older = visible("ui/editor/EditorSettings.kt", "ui/engine/LayerStorageSection.kt") + FAST_ADJUST_PREVIEW_LABEL +
                all19("StabilityLabels19") + all19("BackupLabels19"),
        )
        // Checked as texts: the sheet's layout at 392 and 360 dp and fontScale 2.0 is A10's (LayerStorageSheetUiTest).
        assertNoSubstrings(
            "Layer storage sheet",
            v110 = row + pick("EngineLabels110", "MODE_AUTOMATIC", "MODE_FULL", "MODE_CLASSIC", "APPLIES_NEXT", "CHECK_AGAIN", "DETAILS", "COPY_DETAILS"),
            older = visible("ui/engine/LayerStorageSheet.kt") + listOf("Cancel", "OK"),
        )
    }

    @Test
    fun theGalleryNoticesTheOpenRefusalAndTheNewCanvasDialog() {
        assertNoSubstrings(
            "gallery with the engine notices, the crash card and the backup banner",
            v110 = pick("EngineLabels110", "CHECK_CLASSIC_MEMORY", "CHECK_CLASSIC_SLOW", "CHECK_TILES", "LMK_NOTICE"),
            older = visible("ui/gallery/GalleryNotices.kt", "ui/gallery/BackupBanner.kt", "ui/engine/EngineNoticeCard.kt") +
                pick19("StabilityLabels19", "CRASH_TITLE", "CRASH_SAVED", "CRASH_OOM", "COPY", "SHARE", "DISMISS") +
                pick19("BackupLabels19", "BANNER_NOT_SET", "CHOOSE_FOLDER", "LATER", "UNAVAILABLE", "CHOOSE_AGAIN") + listOf("OK"),
        )
        assertNoSubstrings(
            "Classic refuses to open a big artwork",
            v110 = pick("EngineLabels110", "NEEDS_FULL_STORAGE", "NEEDS_FULL_NO_MEMORY", "OPEN_WITH_FULL"),
            older = listOf("Cancel", "OK"),
        )
        assertNoSubstrings(
            "new canvas dialog",
            v110 = pick("LayersLabels110", "NO_FIXED_LIMIT", "GUARD") + LayersLabels110.canvasTooBig(8000, 6000),
            older = visible("ui/gallery/NewCanvasDialog.kt") + all19("CanvasLabels19") + listOf("White", "Transparent", "Color…"),
        )
    }

    @Test
    fun theEditorMessages() {
        // Toasts, snackbars and progress lines over the canvas; one at a time, but SmokeUi's
        // hasText matches by substring, so none may hold another.
        assertNoSubstrings(
            "editor messages",
            v110 = pick(
                "EngineLabels110", "PREPARING_LAYER", "OPENING_LAYERS", "CANVAS_OP_TOO_BIG", "STORAGE_FULL", "LOW_STORAGE", "SWAP_READ_FAILED",
                "PAINT_REFUSED",
            ) + pick("LayersLabels110", "GUARD", "PAYLOAD_TOO_MANY") + pick("MirrorLabels110", "SIDE_HINT", "SIDE_RESIZE_BLOCKED", "SIDE_REFUSED") +
                pick("ArrayLabels110", "ARRAY_BEHIND_STEP") + pick("ObjectColorLabels110", "GRADIENT_REPLACED"),
            older = listOf("Undo", "Redo"),
        )
    }

    // ------------------------------------------------------------------ helpers

    private fun pick19(obj: String, vararg fields: String): List<String> {
        val c = requireNotNull(load(obj)) { "$obj is declared" }
        return fields.map { name -> c.getDeclaredField(name).let { f -> f.isAccessible = true; f.get(null) as String } }
    }

    private fun all19(obj: String): List<String> = constants(listOf(requireNotNull(load(obj)) { "$obj is declared" })).values.toList()

    /** The names of the objects the main `ui/common/LabelsV110.kt` and `ui/common/V110Tags.kt` declare. */
    private fun objectsInSource(): List<String> {
        val root = mainSources() ?: return emptyList()
        return listOf("ui/common/LabelsV110.kt", "ui/common/V110Tags.kt").mapNotNull { File(root, it).takeIf { f -> f.isFile } }.flatMap { f ->
            Regex("""^(?:internal\s+|public\s+)?object\s+(\w+)""", RegexOption.MULTILINE).findAll(f.readText()).map { it.groupValues[1] }.toList()
        }
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

        /** The objects of 3.2, in its order. */
        val DESIGN_OBJECTS = listOf(
            "LabelsV110", "MirrorLabels110", "ObjectColorLabels110", "ArrayLabels110", "CdcLabels110", "PointLabels110",
            "ObjectBarLabels110", "LayersLabels110", "EngineLabels110", "V110Tags",
        )

        val V18_LABEL_OBJECTS: List<Any> = listOf(
            MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
            CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
        )

        /** v1.9's objects (LabelsV19Test's 2.3.6), read by name. */
        val V19_OBJECTS = listOf(
            "PointLabels19", "CurveLabels19", "PlaceLabels19", "TextLabels19", "KernLabels19", "FilterLabels19",
            "StabilityLabels19", "EraseLabels19", "LayerLabels19", "CanvasLabels19", "BackupLabels19", "V19Tags",
        )

        /**
         * The exceptions of rule 4 (the lead's, F0). The texts are 3.2's, frozen; each pair is one
         * that never shows at once, or whose v1.10 side is found by tag:
         * - the Layer storage row shows one subtitle at a time ("Automatic: Full" or the TILES one);
         * - "Remove array and its layer" replaces "Remove array" on a copies-only layer (UQ11);
         * - while a side is picked the pill's trash cell is named "Delete one side" (LA4); the
         *   Transform More row's "Delete" stays, found by text (exact), the trash by its pill tag;
         * - Transform's toggles say "On" as a state description (not a text or a name), and the
         *   side hint is a toast;
         * - "Mixed" (v1.7, the roundness value) and the SHARP cell's name while corners are mixed:
         *   the cell is found by tag ([V110Tags.PILL_SHARP]);
         * - the Edit button and its own description; the Behind button (found by tag,
         *   [V110Tags.SEL_ARRAY_BEHIND]) and its description beside the v1.7 "Array" button.
         */
        val allowed: Set<Pair<String, String>> = setOf(
            EngineLabels110.SUB_FULL to EngineLabels110.SUB_TILES,
            ArrayLabels.REMOVE to ArrayLabels110.REMOVE_WITH_LAYER,
            "Delete" to MirrorLabels110.DELETE_SIDE,
            "On" to MirrorLabels110.SIDE_HINT,
            PointLabels.MIXED to PointLabels110.MIXED_MAKES_SHARP,
            ObjectBarLabels110.EDIT to ObjectBarLabels110.EDIT_DESC,
            ArrayLabels.BUTTON to ArrayLabels110.BEHIND_DESC,
        )
    }
}
