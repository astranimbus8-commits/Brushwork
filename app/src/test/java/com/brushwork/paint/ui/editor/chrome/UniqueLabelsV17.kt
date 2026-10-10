package com.brushwork.paint.ui.editor.chrome

import android.graphics.Paint
import android.graphics.Path
import androidx.compose.ui.semantics.ScrollAxisRange
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.SemanticsPropertyKey
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.model.SavedSelection
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.IbisDocs
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa16.StateAudit
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CornerStyle
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.BwSheetTitleKey
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.KerningLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.PillLabels
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.SavedSelectionLabels
import com.brushwork.paint.ui.common.SymmetryLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerWindowTags
import com.brushwork.paint.ui.placement.KERNING_ROW_TAG
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue

/**
 * v1.7 I10 audit (design §4.8, §6.2): the screens the v1.7 areas add, each reached as a finger
 * reaches it (by labels and tags) and checked at every position of what scrolls on it — the
 * options strip sideways, a sheet up and down, the layer list — and as a whole (a control
 * scrolled out of view is still found by its label):
 * - the Path tool with two points picked through "Select several" (the strip and the X / Y pill);
 * - a shape in Points mode with one point picked;
 * - the Array sheet in each of its four modes, with the Array tool's strip;
 * - the Symmetry tool with each ruler chosen, and each ruler's "Label value ▾" chip open;
 * - the Pathfinder with its two operands picked;
 * - Transform in Free deform (with its points picked);
 * - the text editor with the Kerning row;
 * - the layer window with an open folder and two saved selections, a saved selection's menu and
 *   the ⋮ over the folder.
 * Where the v1.6 state audits ([StateAudit]) check finger sizes (the X / Y pill with the chrome
 * around it, the layer window and its menus) the v1.7 screens of the same kind are checked too.
 * Each section collects every repeat it finds before failing, so one run lists them all.
 */
internal object UniqueLabelsV17 {

    /** The repeats a section found (each once), failed together at its end. */
    private class Audit(val where: String) {
        val bad = linkedSetOf<String>()

        fun unique(what: String, items: List<Clickables.Item>, allowed: Set<String> = emptySet()) {
            val d = UniqueLabels.duplicates(items, allowed)
            if (d.isNotEmpty()) bad += "$what: " + d.entries.joinToString("; ") { (label, on) ->
                "\"$label\" on ${on.size}: " + on.joinToString(" | ") { it.labels.take(4).joinToString(", ") }
            }
        }

        fun fingers(s: ChromeScreen, what: String, items: List<Clickables.Item>) {
            try {
                StateAudit.assertFingerSized(s, what, items)
            } catch (e: AssertionError) {
                bad += e.message ?: what
            }
        }

        fun check(what: String, ok: Boolean, message: () -> String) {
            if (!ok) bad += "$what: ${message()}"
        }

        fun done() {
            if (bad.isNotEmpty()) System.err.println("=== $where\n" + bad.joinToString("\n"))
            assertTrue("$where:\n" + bad.joinToString("\n"), bad.isEmpty())
        }
    }

    // ------------------------------------------------------------------ scrolling

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    /**
     * The outermost placed node that scrolls along [axis] (and takes ScrollBy) inside a node
     * [under] accepts, or null when nothing there scrolls (all of it fits).
     */
    private fun scroller(s: ChromeScreen, axis: SemanticsPropertyKey<ScrollAxisRange>, under: (SemanticsNode) -> Boolean): SemanticsNode? {
        val all = s.placed().map { it.node }.filter { n ->
            n.config.getOrNull(axis)?.let { it.maxValue() > 0f } == true &&
                n.config.getOrNull(SemanticsActions.ScrollBy) != null &&
                generateSequence(n) { it.parent }.any(under)
        }
        return all.firstOrNull { n -> all.none { o -> o.id != n.id && generateSequence(n.parent) { it.parent }.any { it.id == o.id } } }
    }

    private fun stripScroller(s: ChromeScreen) =
        scroller(s, SemanticsProperties.HorizontalScrollAxisRange) { it.tag() == ChromeTags.OPTIONS_STRIP }

    /** The shown sheet's body (a hosted sheet carries its title; the Array sheet its tag). */
    private fun sheetScroller(s: ChromeScreen) =
        scroller(s, SemanticsProperties.VerticalScrollAxisRange) {
            it.config.getOrNull(BwSheetTitleKey) != null || it.tag() == V17Tags.ARRAY_SHEET
        }

    private fun listScroller(s: ChromeScreen) =
        scroller(s, SemanticsProperties.VerticalScrollAxisRange) { it.tag() == LayerWindowTags.LIST }

    /**
     * [check]s the clickables on screen at each position of the scroller [find] gives, from its
     * start to its end ([horizontal]: sideways, else up and down), 60 % of its size at a time.
     * Returns every clickable seen, by node (for a scroller that keeps its nodes: not a lazy list).
     */
    private fun walk(s: ChromeScreen, horizontal: Boolean, find: () -> SemanticsNode?, check: (List<Clickables.Item>) -> Unit): Map<Int, Clickables.Item> {
        val axis = if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange
        val seen = linkedMapOf<Int, Clickables.Item>()
        find()?.let { n ->
            toStart(n, horizontal)
            assertTrue("the walk starts at the start: ${n.config[axis].value()}", n.config[axis].value() <= 0.5f)
        }
        for (step in 0 until 40) {
            val all = Clickables.onScreen(s)
            check(all)
            all.forEach { seen[it.node.id] = it }
            val n = find() ?: return seen
            val r = n.config[axis]
            if (r.value() >= r.maxValue() - 0.5f) return seen
            scrollBy(n, horizontal, (if (horizontal) n.size.width else n.size.height) * 0.6f)
        }
        throw AssertionError("the walk never reached the end")
    }

    /**
     * Scrolls [n] by [d] px and waits until it stops: a scroller's ScrollBy action animates (a
     * few frames), so one frame would leave it partway.
     */
    private fun scrollBy(n: SemanticsNode, horizontal: Boolean, d: Float) {
        val range = n.config[if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange]
        val scroll = requireNotNull(n.config.getOrNull(SemanticsActions.ScrollBy)?.action)
        if (horizontal) scroll(d, 0f) else scroll(0f, d)
        var last = Float.NaN
        var still = 0
        for (i in 0 until 40) {
            settle(2)
            val v = range.value()
            still = if (v == last) still + 1 else 0
            if (still >= 2) break
            last = v
        }
    }

    /** Scrolls [n] back to its start (a lazy list's offset is an estimate: far enough back). */
    private fun toStart(n: SemanticsNode, horizontal: Boolean) {
        val range = n.config[if (horizontal) SemanticsProperties.HorizontalScrollAxisRange else SemanticsProperties.VerticalScrollAxisRange]
        if (range.value() > 0f) scrollBy(n, horizontal, -maxOf(range.value(), range.maxValue()) - 1f)
    }

    /** The screen at each position of the options strip, then the whole strip with the rest (the chrome, a pill, a sheet). */
    private fun walkStrip(s: ChromeScreen, a: Audit, what: String, allowed: Set<String> = emptySet(), fingers: Boolean = false): Map<Int, Clickables.Item> {
        val seen = walk(s, horizontal = true, find = { stripScroller(s) }) { all ->
            a.unique(what, all, allowed)
            if (fingers) a.fingers(s, what, all)
        }
        a.unique("$what, the whole strip", seen.values.toList(), allowed)
        // Back at its start, where a finger finds the strip's first controls again.
        stripScroller(s)?.let { n -> toStart(n, horizontal = true) }
        return seen
    }

    /** The screen at each position of the shown sheet's body (the strip at its start), then the sheet and the strip whole. */
    private fun walkSheetAndStrip(s: ChromeScreen, a: Audit, what: String, allowed: Set<String> = emptySet()): Map<Int, Clickables.Item> {
        stripScroller(s)?.let { n -> toStart(n, horizontal = true) }
        val sheet = walk(s, horizontal = false, find = { sheetScroller(s) }) { all -> a.unique("$what, the sheet", all, allowed) }
        val strip = walk(s, horizontal = true, find = { stripScroller(s) }) { all -> a.unique("$what, the strip", all, allowed) }
        val whole = LinkedHashMap(sheet).apply { putAll(strip) }
        a.unique("$what, the whole sheet and strip", whole.values.toList(), allowed)
        stripScroller(s)?.let { n -> toStart(n, horizontal = true) }
        sheetScroller(s)?.let { n -> toStart(n, horizontal = false) }
        return whole
    }

    /** The text of the placed node whose text starts with "[label] " (a "Label value ▾" chip), or null. */
    private fun chipText(s: ChromeScreen, label: String): String? = s.placed().asReversed().firstNotNullOfOrNull { e ->
        e.node.config.getOrNull(SemanticsProperties.Text)?.map { it.text }?.firstOrNull { it.startsWith("$label ") }
    }

    // ------------------------------------------------------------------ the sections

    fun sections(h: ChromeHarness) {
        h.section("v1.7: Path with two points picked") { path(h) }
        h.section("v1.7: a shape's Points with a point picked") { shapePoints(h) }
        h.section("v1.7: the Array sheet in each mode") { array(h) }
        h.section("v1.7: the Symmetry tool with each ruler") { symmetry(h) }
        h.section("v1.7: the Pathfinder with its operands picked") { pathfinder(h) }
        h.section("v1.7: Transform in Free deform") { freeDeform(h) }
        h.section("v1.7: the text editor with the Kerning row") { kerning(h) }
        h.section("v1.7: the layer window with an open folder and two saved selections") { folderAndSaved(h) }
    }

    /** No point at a corner or edge middle of their box (a point there would win the touch). */
    private val FOUR = listOf(Vec2(120f, 150f), Vec2(200f, 90f), Vec2(280f, 150f), Vec2(200f, 210f))

    private fun path(h: ChromeHarness) {
        val s = h.editor { it.snapping.enabled = false }
        val a = Audit("Path, two points picked")
        val ui = Qa16Ui(s)
        QaCurves.tool(s, "Path")
        val tool = s.c.currentTool as CurveTool
        for (p in FOUR) assertTrue(tool.addAnchor(p))
        tool.deselect()
        settle()
        ui.reach(PointLabels.SELECT_SEVERAL, 0f)
        click(PointLabels.SELECT_SEVERAL, exact = true)
        assertTrue("Select several is on", tool.selectSeveral)
        QaCurves.tap(s, FOUR[0])
        QaCurves.tap(s, FOUR[2])
        assertEquals("two points picked", 2, tool.pointSelection.count)
        val seen = walkStrip(s, a, "Path strip + pill", fingers = true)
        val trash = seen.values.count { PillLabels.DELETE_POINTS in it.labels }
        a.check("Path", trash == 1) { "\"${PillLabels.DELETE_POINTS}\" on $trash controls" }
        tool.discard()
        settle()
        Smoke.assertQuiet(s.c, "Path")
        a.done()
    }

    private fun shapePoints(h: ChromeHarness) {
        val s = h.editor(Smoke.document(480, 320, layers = 2, whiteBottom = true)) { it.snapping.enabled = false }
        val a = Audit("Shape Points, one point picked")
        val ui = Qa16Ui(s)
        QaCurves.tool(s, "Shape")
        val tool = s.c.currentTool as ShapeTool
        tool.update {
            it.copy(
                type = ShapeType.RECTANGLE, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 5f,
                fillColor = 0xFF40B060.toInt(), corner = CornerStyle.SHARP, keepProportions = false, fromCenter = false,
            )
        }
        ui.stroke(120f to 80f, 200f to 140f, 300f to 220f)
        assertTrue("a pending shape", tool.hasPendingWork)
        ui.reach("Points", 0f)
        click("Points", exact = true)
        assertTrue(tool.pointsMode)
        val anchors = requireNotNull(tool.docAnchors())
        QaCurves.tap(s, anchors[0].pos)
        assertEquals("one point picked", 1, tool.pointSelection.count)
        walkStrip(s, a, "Shape Points, one point", fingers = true)
        // Two of its four points (the strip's group controls and the pill's "Delete selected points").
        ui.reach(PointLabels.SELECT_SEVERAL, 0f)
        click(PointLabels.SELECT_SEVERAL, exact = true)
        assertTrue("Select several is on", tool.selectSeveral)
        QaCurves.tap(s, anchors[2].pos)
        assertEquals("two points picked", 2, tool.pointSelection.count)
        walkStrip(s, a, "Shape Points, two points", fingers = true)
        tool.discard()
        settle()
        Smoke.assertQuiet(s.c, "Shape Points")
        a.done()
    }

    private fun array(h: ChromeHarness) {
        val s = h.editor()
        val a = Audit("Array sheet")
        val ui = Qa16Ui(s)
        val c = s.c
        val src = c.doc.layers[1]
        android.graphics.Canvas(src.bitmap).drawRect(60f, 60f, 100f, 100f, Paint().apply { color = 0xFFDD2211.toInt() })
        src.markChanged()
        c.setSelection(Selection.fromPath(Path().apply { addRect(50f, 50f, 110f, 110f, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false), recordUndo = false)
        settle(4)
        ui.reach(ArrayLabels.FROM_SELECTION)
        click(ArrayLabels.FROM_SELECTION, exact = true)
        c.setSelection(null, recordUndo = false)
        settle(4)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        assertNotNull("the Array sheet", s.tagged(V17Tags.ARRAY_SHEET))
        for (mode in listOf(ArrayLabels.LINE, ArrayLabels.CIRCLE, ArrayLabels.CURVE, ArrayLabels.TRANSFORM)) {
            ui.reach(mode, 0f)
            click(mode, exact = true)
            val whole = walkSheetAndStrip(s, a, mode)
            for (label in listOf(ArrayLabels.LINE, ArrayLabels.CIRCLE, ArrayLabels.CURVE, ArrayLabels.TRANSFORM, ArrayLabels.APPLY, ArrayLabels.REMOVE)) {
                val n = whole.values.count { label in it.labels }
                a.check(mode, n == 1) { "\"$label\" on $n controls" }
            }
        }
        click(ArrayLabels.REMOVE, exact = true)
        Smoke.assertQuiet(c, "Array sheet")
        a.done()
    }

    private fun symmetry(h: ChromeHarness) {
        val s = h.editor()
        val a = Audit("Symmetry")
        val ui = Qa16Ui(s)
        QaCurves.tool(s, "Symmetry")
        assertEquals(ToolId.SYMMETRY, s.c.activeToolId)
        for (type in SymmetryType.entries) {
            ui.reach(type.label, 0f)
            click(type.label, exact = true)
            assertEquals(type, s.c.symmetry.type)
            val seen = walkStrip(s, a, type.label)
            for (label in SymmetryType.entries.map { it.label } + SymmetryLabels.RESET) {
                val n = seen.values.count { label in it.labels }
                a.check(type.label, n == 1) { "\"$label\" on $n controls" }
            }
            // Each "Label value ▾" chip's field, open over the strip.
            for (chip in listOf(SymmetryLabels.DIVISIONS, "Angle", "Spacing X", "Spacing Y")) {
                val text = chipText(s, chip) ?: continue
                ui.reach(text, 0f)
                click(text, exact = true)
                a.check("${type.label}, $chip", SmokeUi.windows().size == 2) { "the chip's field did not open" }
                a.unique("${type.label}, \"$text\" open", Clickables.onScreen(s))
                UniqueLabels.closeMenu()
            }
        }
        click("Done", exact = true)
        Smoke.assertQuiet(s.c, "Symmetry")
        a.done()
    }

    private fun shapeLayer(c: EditorController, l: Float, t: Float, r: Float, b: Float, color: Int) {
        val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
        c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { canvas ->
            canvas.drawRect(l, t, r, b, Paint().apply { this.color = color })
        }!!
    }

    private fun pathfinder(h: ChromeHarness) {
        val s = h.editor(Smoke.document(300, 200, layers = 1)) { c ->
            shapeLayer(c, 20f, 20f, 100f, 100f, 0xFFDD2211.toInt())
            shapeLayer(c, 70f, 20f, 150f, 100f, 0xFF2244CC.toInt())
        }
        val a = Audit("Pathfinder, two operands")
        val ui = Qa16Ui(s)
        QaCurves.tool(s, "Pathfinder")
        val tool = s.c.currentTool as PathfinderTool
        ui.reach(PathfinderLabels.SELECT_ALL, 0f)
        click(PathfinderLabels.SELECT_ALL, exact = true)
        assertEquals("both picked", 2, tool.count)
        walkStrip(s, a, "Pathfinder strip")
        Smoke.assertQuiet(s.c, "Pathfinder")
        a.done()
    }

    private fun freeDeform(h: ChromeHarness) {
        val s = h.editor(IbisDocs.page())
        val a = Audit("Free deform")
        val ui = Qa16Ui(s)
        s.c.selectLayer(1)
        settle()
        QaCurves.tool(s, "Transform")
        val tool = s.c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tool.transformState != null })
        settle()
        ui.reach(TransformLabels17.FREE_DEFORM, 0f)
        click(TransformLabels17.FREE_DEFORM, exact = true)
        assertTrue(Smoke.pumpUntil { settle(1); tool.mode == TransformTool.Mode.MESH })
        settle()
        walkStrip(s, a, "Free deform", fingers = true)
        // Its points picked: the pill's point controls join in.
        ui.reach(PointLabels.SELECT_SEVERAL, 0f)
        click(PointLabels.SELECT_SEVERAL, exact = true)
        ui.reach(PointLabels.SELECT_ALL, 0f)
        click(PointLabels.SELECT_ALL, exact = true)
        assertTrue("every point picked", tool.allPointsSelected)
        walkStrip(s, a, "Free deform, all points", fingers = true)
        tool.discard()
        s.c.selectTool(ToolId.BRUSH)
        settle()
        Smoke.assertQuiet(s.c, "Free deform")
        a.done()
    }

    private fun kerning(h: ChromeHarness) {
        val s = h.editor { it.snapping.enabled = false }
        val a = Audit("Text editor with Kerning")
        QaCurves.tool(s, "Text")
        val text = s.c.currentTool as TextTool
        QaCurves.tap(s, 200f, 150f)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type("AVATAR")
        settle()
        var row = false
        val whole = walk(s, horizontal = false, find = { sheetScroller(s) }) { all ->
            a.unique("text editor", all)
            if (s.tagged(KERNING_ROW_TAG) != null) row = true
        }
        a.unique("the whole text editor", whole.values.toList())
        a.check("text editor", row) { "the Kerning row never showed" }
        for (label in listOf("Decrease ${KerningLabels.KERNING}", "Increase ${KerningLabels.KERNING}", KerningLabels.FONT_KERNING)) {
            val n = whole.values.count { label in it.labels }
            a.check("text editor", n == 1) { "\"$label\" on $n controls" }
        }
        click("Cancel", exact = true)
        Smoke.assertQuiet(s.c, "text editor")
        a.done()
    }

    private fun folderAndSaved(h: ChromeHarness) {
        lateinit var folder: com.brushwork.paint.model.Layer
        val s = h.editor { c ->
            val d = c.doc
            fun rect(l: Float, t: Float, r: Float, b: Float) =
                Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, d.width, d.height, antiAlias = false)
            d.savedSelections = listOf(
                SavedSelection.of(d.newSelectionId(), "Selection 1", rect(10f, 10f, 120f, 90f), 1L)!!,
                SavedSelection.of(d.newSelectionId(), "Selection 2", rect(150f, 60f, 300f, 200f), 1L)!!,
            )
            c.selectLayer(d.layers.last())
            folder = c.addFolder()!!
            // The open folder's top child, then the folder active (its row and its ⋮).
            c.addLayer()!!
            c.setFolderOpen(folder, true)
            c.selectLayer(folder)
            c.notifyLayersChanged()
        }
        val a = Audit("Layer window, open folder, saved selections")
        val c = s.c
        assertTrue("the folder is open", folder.folderOpen)
        click("Open layers")
        val window = s.tagged(ChromeTags.LAYER_WINDOW) ?: throw AssertionError("no layer window")
        val names = c.doc.savedSelections.map { it.name }
        val closeFolder = FolderLabels.close(folder.name)
        val shown = mutableSetOf<String>()
        // The list from its top to its end (a lazy list: each position on its own).
        listScroller(s)?.let { n -> toStart(n, horizontal = false) }
        for (step in 0 until 30) {
            val all = Clickables.onScreen(s)
            val items = Clickables.ownLabelsInside(all, window)
            a.unique("layer window", items)
            a.fingers(s, "layer window", items.filter { window.contains(it.bounds.center) })
            // The saved rows are known by the names they show; the folder's toggle by its own name.
            for (label in names + closeFolder) {
                val n = all.count { label in it.labels }
                if (n > 0) shown += label
                a.check("layer window", n <= 1) { "\"$label\" on $n controls" }
            }
            val n = listScroller(s) ?: break
            val r = n.config[SemanticsProperties.VerticalScrollAxisRange]
            if (r.value() >= r.maxValue() - 0.5f) break
            scrollBy(n, horizontal = false, n.size.height * 0.6f)
        }
        a.check("layer window", shown.containsAll(names + closeFolder)) { "never shown: ${(names + closeFolder) - shown}" }

        // A saved selection's menu over the window.
        listScroller(s)?.let { n -> toStart(n, horizontal = false) }
        click(names.first(), exact = true)
        a.check("saved selection menu", SmokeUi.windows().size == 2) { "the menu did not open" }
        menuOverWindow(s, a, window, "saved selection menu", listOf(SavedSelectionLabels.LOAD, SavedSelectionLabels.RENAME, SavedSelectionLabels.DELETE))
        UniqueLabels.closeMenu()

        // The ⋮ over the open folder.
        click(LayerLabels.MORE, exact = true)
        a.check("folder ⋮", SmokeUi.windows().size == 2) { "the menu did not open" }
        menuOverWindow(s, a, window, "folder ⋮", listOf(FolderLabels.RENAME, FolderLabels.UNGROUP))
        UniqueLabels.closeMenu()
        click("Close layers", exact = true)
        Smoke.assertQuiet(c, "folder and saved selections")
        a.done()
    }

    /** The menu open over the layer window: unique at each position, finger-sized, none of its entries repeating a window control. */
    private fun menuOverWindow(s: ChromeScreen, a: Audit, window: androidx.compose.ui.geometry.Rect, what: String, expected: List<String>) {
        val decor = s.activity.window.decorView
        val whole = UniqueLabels.walkMenu(s) { all ->
            val menu = all.filter { it.window !== decor }
            a.unique(what, menu)
            a.fingers(s, what, menu)
            val windowNames = Clickables.ownLabelsInside(all.filter { it.window === decor && window.contains(it.bounds.center) }, window)
                .flatMap { it.labels }.toSet()
            val clash = menu.flatMap { it.labels }.filter { it in windowNames }
            a.check(what, clash.isEmpty()) { "entries repeating a layer window control: $clash" }
            a.unique("$what and the chrome around the window", menu + all.filter { it.window === decor && !window.contains(it.bounds.center) })
        }
        a.unique("the whole $what", whole.values.toList())
        for (entry in expected) {
            val n = whole.values.count { entry in it.labels }
            a.check(what, n == 1) { "\"$entry\" on $n entries: ${whole.values.map { it.labels }}" }
        }
    }
}
