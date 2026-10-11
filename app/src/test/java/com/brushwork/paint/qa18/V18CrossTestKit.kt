package com.brushwork.paint.qa18

import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.view.KeyEvent
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.material3.DropdownMenu
import androidx.compose.material3.DropdownMenuItem
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.points.PointSelection
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.frames.FrameFixtures
import com.brushwork.paint.tools.text.frames.TextFrameTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeSettings
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.BwDialog
import com.brushwork.paint.ui.common.BwSheet
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels18
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.tools.strip.LocalStripContext
import com.brushwork.paint.ui.tools.strip.StripInventory
import com.brushwork.paint.ui.tools.strip.StripItem
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.ui.tools.strip.StripRig
import com.brushwork.paint.ui.tools.strip.StripRow
import com.brushwork.paint.ui.tools.strip.StripSpec
import com.brushwork.paint.ui.vector.ShapeStripKit
import com.brushwork.paint.vector.CurveGenKind
import java.lang.reflect.Modifier as Reflect
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.8 integration (design §6.2 item 2, scenarios e and f): what the V18CrossTestStrips* and
 * V18CrossTestOverlays classes share. A [StripRig] (`panel = false`: the host as `ToolOptionsBar`
 * calls it) walks [states]: every tool on the raster and on the vector layer, then the states
 * whose More rows carry the most controls, v1.8's first (a generated rose on Curve and Path, the
 * curve tools open with mirror copies, a holed cog, a cog with copies in Points mode, an open
 * text (on the vector layer too, where the wrap button is on the More row), a selected text frame, a brush part of a mask, the Transform box and Free deform, an Array
 * layer). Each state is set up from the one before by [restore]: what is open is put away and
 * the document's steps are undone.
 */
internal object V18CrossTestKit {

    /** One strip state: [setup] brings the rig to it from any other state. */
    class State(val name: String, val setup: (StripRig) -> Unit)

    private fun at(vector: Boolean) = if (vector) "@vector" else ""

    private fun strings(o: Any): List<String> = o.javaClass.declaredFields
        .filter { Reflect.isStatic(it.modifiers) && it.type == String::class.java }
        .map { f -> f.isAccessible = true; f.get(null) as String }

    /**
     * The v1.8 texts: LabelsV18Test's objects, plus the two v1.8 tool names and the cog's shape
     * name (ShapesLabelsTest's set).
     */
    val V18: Set<String> = listOf(
        MirrorLabels18, ArrayLabels18, MagneticLabels18, ColorSelectLabels18, FringeLabels18,
        CurveGenLabels18, CogLabels18, TransformLabels18, StripLabels18, MenuLabels18,
    ).flatMap(::strings).toSet() + ToolId.MAGNETIC_LASSO.label + ToolId.COLOR_SELECT.label + ShapeType.COG.label

    /** CurvesLabelsTest's one known pair (R15): the Curves dropdown's visible text holds the tool's name. */
    private val ALLOWED = setOf("Curve" to CurveGenLabels18.CURVES)

    /** ShapesLabelsTest's derived handles of a control labelled X (the same control's parts). */
    private val DERIVED = listOf("Decrease ", "Increase ", "Type ", "Type a value for ", "Reset ")

    // ------------------------------------------------------------------ the states

    private fun cog(hole: Float): (ShapeSettings) -> ShapeSettings = {
        it.copy(type = ShapeType.COG, style = ShapeStyle.STROKE_FILL, useBrushSize = false, strokeWidth = 4f, editable = true, cogTeeth = 12, cogHole = hole)
    }

    private fun curve(rig: StripRig, id: ToolId) = rig.c.tools.getValue(id) as CurveTool

    private fun tap(rig: StripRig, x: Float, y: Float) {
        StripTestKit.closeMoreRow()
        rig.c.pointerDown(ToolPoint(x, y))
        rig.c.pointerUp(ToolPoint(x, y))
        rig.settle()
    }

    /** A picture on the raster layer, lifted by the Transform tool, in Free (box) or Free deform. */
    private fun transform(rig: StripRig, mesh: Boolean) {
        rig.select(ToolId.BRUSH)
        check(rig.c.editWholeLayer(rig.doc.layers[0], "Seed") { b -> Canvas(b).drawRect(100f, 60f, 300f, 240f, Paint().apply { color = 0xFF2266CC.toInt() }) })
        rig.select(ToolId.TRANSFORM)
        val t = rig.c.currentTool as TransformTool
        check(Smoke.pumpUntil(10_000) { rig.settle(); t.transformState != null }) { "nothing lifted" }
        t.mode = if (mesh) TransformTool.Mode.MESH else TransformTool.Mode.FREE
        rig.settle()
        check(t.isMeshShown == mesh) { "mesh shown: ${t.isMeshShown}" }
    }

    /** A raster array from a gray square of the raster layer (made unless one is there), the Array tool on it. */
    private fun array(rig: StripRig) {
        val c = rig.c
        val existing = c.doc.layers.firstOrNull { it.array != null }
        if (existing == null) {
            rig.select(ToolId.BRUSH)
            val src = rig.doc.layers[0]
            Canvas(src.bitmap).drawRect(20f, 30f, 60f, 70f, Paint().apply { color = ArrayLookKit.GRAY })
            src.markChanged()
            c.setSelection(ArrayLookKit.rectSelection(c, 20f, 30f, 60f, 70f), recordUndo = false)
            check(c.arrayFromSelection()) { "no array" }
            c.setSelection(null, recordUndo = false)
            ArrayLookKit.settle(c)
        } else {
            c.selectLayer(existing)
        }
        check(c.activeLayer.array != null) { "the array layer is not active" }
        if (c.activeToolId != ToolId.ARRAY) c.selectTool(ToolId.ARRAY)
        val tool = c.tools.getValue(ToolId.ARRAY) as ArrayTool
        if (tool.sheetOpen) tool.closeSheet()
        rig.settle()
    }

    /** Puts away what a state left open: the More row, windows over the strip, open curves and shapes, the mirror, the tool. */
    fun reset(rig: StripRig) {
        StripTestKit.closeMoreRow()
        repeat(3) { back(rig) }
        for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) curve(rig, id).discard()
        (rig.c.tools.getValue(ToolId.SHAPE) as ShapeTool).discard()
        rig.c.updateSymmetry(SymmetrySettings())
        // A lifted transform is let go (or committed) before the next state.
        rig.select(ToolId.BRUSH)
        rig.settle()
    }

    /**
     * Every tool on the raster layer and on the vector layer (its state as picked), then the v1.8
     * states.
     */
    fun states(): List<State> = buildList {
        for (vector in listOf(false, true)) for (id in ToolId.entries) {
            add(State(id.name + at(vector)) { rig ->
                rig.select(id, vector)
                check(rig.c.activeToolId == id) { "the tool was not selected" }
            })
        }
        for (vector in listOf(false, true)) for (id in listOf(ToolId.CURVE, ToolId.PATH)) {
            add(State("${id.name}${at(vector)}, a generated rose") { rig ->
                rig.select(id, vector)
                check(curve(rig, id).startGenerator(CurveGenKind.ROSE, null)) { "no rose" }
                rig.settle()
            })
        }
        for (vector in listOf(false, true)) for (id in listOf(ToolId.CURVE, ToolId.POLYLINE, ToolId.PATH)) {
            add(State("${id.name}${at(vector)}, open with mirror copies, a point selected") { rig ->
                rig.c.updateSymmetry(SymmetrySettings(type = SymmetryType.MIRROR))
                rig.select(id, vector)
                val t = curve(rig, id)
                tap(rig, 60f, 70f)
                tap(rig, 110f, 100f)
                tap(rig, 150f, 80f)
                t.select(1)
                rig.settle()
                check(t.hasCopies) { "the open path has no copies" }
            })
        }
        for (vector in listOf(false, true)) {
            add(State("SHAPE${at(vector)}, a holed cog open") { rig ->
                ShapeStripKit.open(rig, vector, cog(0.4f))
            })
            add(State("SHAPE${at(vector)}, a cog with mirror copies in Points mode") { rig ->
                val t = ShapeStripKit.withCopies(rig, vector, cog(0f))
                t.setPointEditing(true)
                check(t.pointsMode) { "not in Points mode" }
                t.selectPoints(PointSelection.all(t.pointCount))
                rig.settle()
            })
        }
        // On the vector layer at 360 dp the wrap button (AUTO 1) leaves row 1: its sheet opens from the More row.
        for (vector in listOf(false, true)) {
            add(State("TEXT${at(vector)}, an open text") { rig ->
                rig.select(ToolId.TEXT, vector)
                val t = rig.c.currentTool as TextTool
                t.startTextAt(200f, 150f)
                t.setText("Strip")
                t.confirmEditor()
                rig.settle()
                check(t.item != null) { "no open text" }
            })
        }
        add(State("TEXT_FRAMES, a frame selected") { rig ->
            rig.select(ToolId.TEXT_FRAMES)
            val c = rig.c
            val t = c.currentTool as TextFrameTool
            c.viewTransform.set(Matrix())
            t.storyPreviewMs = 0L
            t.dragPreviewMs = 0L
            FrameFixtures.drag(c, 40f, 40f, 200f, 120f)
            rig.settle()
            check(t.story.isOpen) { "no story editor for the new frame" }
            t.story.setText(FrameFixtures.STORY)
            t.story.setSizePx(16f)
            t.story.confirmEditor()
            rig.settle()
            check(t.selected != null) { "no frame selected" }
        })
        add(State("MASK, a brush part on an adjustment layer") { rig ->
            rig.select(ToolId.MASK)
            val c = rig.c
            val t = c.currentTool as MaskTool
            t.arm(MaskTool.Kind.LINEAR)
            FrameFixtures.drag(c, 120f, 150f, 280f, 150f)
            rig.settle()
            check(c.activeLayer.isAdjustmentLayer) { "no adjustment layer" }
            t.arm(MaskTool.Kind.BRUSH)
            FrameFixtures.drag(c, 150f, 100f, 250f, 200f)
            check(Smoke.pumpUntil(10_000) { rig.settle(); t.selected is BrushMask }) { "no brush part selected" }
            rig.settle()
        })
        add(State("TRANSFORM, box modes") { rig -> transform(rig, mesh = false) })
        add(State("TRANSFORM, Free deform") { rig -> transform(rig, mesh = true) })
        add(State("ARRAY, a raster array") { rig -> array(rig) })
    }

    // ------------------------------------------------------------------ the strip's nodes

    private fun SemanticsNode.tag(): String? = config.getOrNull(SemanticsProperties.TestTag)

    /** [n] sits on the strip: row 1 (the panel, VECTOR cell included) or the More row. */
    fun onStrip(n: SemanticsNode): Boolean =
        generateSequence(n) { it.parent }.any { it.tag() == ChromeTags.OPTIONS_STRIP || it.tag() == V18Tags.STRIP_MORE_ROW }

    private fun underMoreRow(n: SemanticsNode): Boolean = generateSequence(n) { it.parent }.any { it.tag() == V18Tags.STRIP_MORE_ROW }

    private fun labels(n: SemanticsNode): List<String> =
        n.config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } +
            n.config.getOrNull(SemanticsProperties.ContentDescription).orEmpty() +
            listOfNotNull(n.config.getOrNull(SemanticsActions.OnClick)?.label)

    /** A text on the strip: [unit] the control it belongs to (its nearest control, else its own node). */
    private class Shown(val unit: Int, val control: Boolean, val text: String)

    /** Every label on the strip now; values (texts with a digit: "5 px", "Order 4") are left out, as CurvesLabelsTest does. */
    private fun shown(): List<Shown> = RobolectricUi.elements().map { it.node }
        .filter { it.layoutInfo.isPlaced && onStrip(it) }
        .flatMap { n ->
            val control = generateSequence(n) { it.parent }.firstOrNull { StripInventory.isControl(it) }
            labels(n).filter { it.isNotBlank() && it.none(Char::isDigit) }.distinct().map { Shown(control?.id ?: n.id, control != null, it) }
        }

    /** The v1.8 texts the strip shows now. */
    fun v18Shown(): Set<String> = shown().map { it.text }.filter { it in V18 }.toSet()

    /**
     * The substring rule (LabelsV18Test 4, SmokeUi matches by case-sensitive `contains`): pairs
     * (a, b) of two texts on the strip, a inside b, one of them a v1.8 text. Equal texts count when
     * two different controls carry them (CurvesLabelsTest); the same text twice on one control (a
     * StripValueButton's label and its description) or a label repeated beside its control
     * (ShapesLabelsTest: one text), the derived handles and R15's pair do not. A text strictly
     * inside another counts even on one control.
     */
    fun clashes(): List<String> {
        val s = shown()
        val out = linkedSetOf<String>()
        for (a in s) for (b in s) {
            if (a === b || !b.text.contains(a.text)) continue
            if (a.text == b.text && (a.unit == b.unit || !(a.control && b.control))) continue
            if (a.text !in V18 && b.text !in V18) continue
            if (a.text to b.text in ALLOWED) continue
            if (DERIVED.any { b.text == it + a.text }) continue
            out += if (a.text == b.text) "\"${a.text}\" on two controls" else "\"${a.text}\" inside \"${b.text}\""
        }
        return out.toList()
    }

    /**
     * Nothing clipped: every control on row 1 and the More row shows whole (its layout width is
     * what shows), inside the screen, on a line that does not scroll; no text cut by its item
     * ([ShapeStripKit.clippedTexts]). The spec's [hint] is left to the host, which ends a hint alone
     * on its line with "…" (TextFrameStripTest's exemption for v1.7's 70-character frame hint).
     */
    fun clipped(widthPx: Int, hint: String? = null): List<String> {
        val out = mutableListOf<String>()
        val controls = RobolectricUi.elements().map { it.node }.filter { n ->
            n.layoutInfo.isPlaced && StripTestKit.inConvertedStrip(n) && StripInventory.isControl(n)
        }
        for (n in controls) {
            val name = StripInventory.handle(n) ?: "?"
            val b = n.boundsInWindow
            if (abs(b.width - n.size.width) > 1f) out += "\"$name\" clipped: ${b.width.roundToInt()} of ${n.size.width} px show"
            if (b.left < -1f || b.right > widthPx + 1f) out += "\"$name\" off the ${widthPx} px screen: $b"
            if (StripTestKit.horizontalScroller(n) != null) out += "\"$name\" on a line that scrolls"
        }
        out += ShapeStripKit.clippedTexts { n -> onStrip(n) && !statedWhole(n) && !isHint(n, hint) }.map { "text cut: $it" }
        return out
    }

    /** [n] is the More row's hint text (the spec's [hint]). */
    private fun isHint(n: SemanticsNode, hint: String?): Boolean =
        hint != null && underMoreRow(n) && n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text } == hint

    /**
     * [n]'s text is a value its control says whole as its state: a `fixed` StripValueButton keeps
     * its width and ellipsizes the value by design (ARCHITECTURE, "StripValueButton"), as Color
     * select's preset chip does with "Sampled colors".
     */
    private fun statedWhole(n: SemanticsNode): Boolean {
        val text = n.config.getOrNull(SemanticsProperties.Text).orEmpty().joinToString("") { it.text }
        if (text.isEmpty()) return false
        val state = generateSequence(n.parent) { it.parent }.firstNotNullOfOrNull { it.config.getOrNull(SemanticsProperties.StateDescription) }
        return state == text
    }

    /**
     * The open More row: [StripLayout.MAX_MORE_LINES] lines at most, its height that of a whole
     * number of lines, its controls on that many lines. Empty when it is closed.
     */
    fun moreLines(density: Float): List<String> {
        val more = StripTestKit.tagged(V18Tags.STRIP_MORE_ROW) ?: return emptyList()
        val out = mutableListOf<String>()
        val h = more.size.height / density
        val step = StripLayout.LINE_DP + StripLayout.GAP_DP
        val lines = ((h + StripLayout.GAP_DP) / step).roundToInt()
        if (lines !in 1..StripLayout.MAX_MORE_LINES) out += "the More row takes $lines lines ($h dp), over ${StripLayout.MAX_MORE_LINES}"
        if (abs(h - StripLayout.moreRowDp(lines)) > 0.5f) out += "the More row is $h dp, not ${StripLayout.moreRowDp(lines)} ($lines lines)"
        val top = more.boundsInWindow.top
        val used = RobolectricUi.elements().map { it.node }
            .filter { it.layoutInfo.isPlaced && StripInventory.isControl(it) && underMoreRow(it) }
            .map { ((it.boundsInWindow.center.y - top) / density / step).toInt() }.toSet()
        if (used.size > StripLayout.MAX_MORE_LINES) out += "the More row's controls sit on ${used.size} lines"
        return out
    }

    /** Back on the top window when it is not the rig's (a sheet, a dialog or a popup closes). */
    fun back(rig: StripRig) {
        val top = RobolectricUi.windowRoots().lastOrNull() ?: return
        if (top === rig.activity.window.decorView) return
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_DOWN, KeyEvent.KEYCODE_BACK))
        top.dispatchKeyEvent(KeyEvent(KeyEvent.ACTION_UP, KeyEvent.KEYCODE_BACK))
        rig.settle()
    }

    // ------------------------------------------------------------------ the machinery, on fake specs

    @Composable
    private fun Probe(label: String, widthDp: Int) {
        Box(Modifier.size(widthDp.dp, 40.dp).clickable {}.semantics { contentDescription = label })
    }

    /**
     * The checks catch what they look for, on fake specs shown instead of the tool's: a v1.8
     * text inside another control's handle, a text cut by its item, and a More row too full for
     * [StripLayout.MAX_MORE_LINES] lines (its last line scrolls). Returns what went unseen.
     */
    fun machineryCatches(rig: StripRig): List<String> {
        val out = mutableListOf<String>()
        val widthPx = rig.activity.resources.displayMetrics.widthPixels
        try {
            rig.override = StripSpec(
                listOf(
                    StripItem("a", MagneticLabels18.SELECTION_MODE, 120.dp) { Probe(MagneticLabels18.SELECTION_MODE, 120) },
                    StripItem("b", "Probe ${MagneticLabels18.SELECTION_MODE}", 120.dp) { Probe("Probe ${MagneticLabels18.SELECTION_MODE}", 120) },
                ),
            )
            rig.settle()
            if (clashes().isEmpty()) out += "a v1.8 text inside another control's handle went unseen"
            rig.override = StripSpec(
                listOf(
                    StripItem("c", "Probe cut", 40.dp) {
                        Text("A probe text far too long for its item", maxLines = 1, overflow = TextOverflow.Ellipsis, modifier = Modifier.width(40.dp))
                    },
                ),
            )
            rig.settle()
            if (clipped(widthPx).none { it.startsWith("text cut") }) out += "a text cut by its item went unseen"
            rig.override = StripSpec(
                listOf(StripItem("r", "Probe row", 40.dp) { Probe("Probe row", 40) }) +
                    ('a'..'n').map { k -> StripItem("m$k", "Probe more $k", 100.dp, row = StripRow.MORE) { Probe("Probe more $k", 100) } },
            )
            rig.settle()
            StripTestKit.openMoreRow()
            if (clipped(widthPx).none { it.endsWith("on a line that scrolls") }) out += "a More row past ${StripLayout.MAX_MORE_LINES} lines went unseen"
            StripTestKit.closeMoreRow()
        } finally {
            rig.override = null
            rig.settle()
        }
        return out
    }

    // ------------------------------------------------------------------ scenario e

    /**
     * Scenario e on the rig's phone: each state with its More row open (row 1 alone when it has
     * no toggle). Returns the problems; [seen] collects the v1.8 texts the strips showed and
     * [walked] the states checked with the More row open.
     */
    fun stripsKeepTheRules(rig: StripRig, seen: MutableSet<String>, walked: MutableList<String>): List<String> {
        val problems = mutableListOf<String>()
        val widthPx = rig.activity.resources.displayMetrics.widthPixels
        val density = rig.activity.resources.displayMetrics.density
        val steps = rig.c.undoManager.undoCount
        for (state in states()) {
            restore(rig, steps)
            try {
                state.setup(rig)
            } catch (e: Throwable) {
                problems += "${state.name}: not reached: ${e.message}"
                continue
            }
            if (rig.spec.legacyContent != null) {
                problems += "${state.name}: a legacy strip"
                continue
            }
            val toggle = StripTestKit.toggle() != null
            if (toggle) {
                StripTestKit.openMoreRow()
                if (!StripTestKit.moreRowShown()) problems += "${state.name}: the toggle opens no More row"
                walked += state.name
            }
            seen += v18Shown()
            problems += clashes().map { "${state.name}: $it" }
            problems += clipped(widthPx, rig.spec.hint).map { "${state.name}: $it" }
            problems += moreLines(density).map { "${state.name}: $it" }
            StripTestKit.closeMoreRow()
        }
        restore(rig, steps)
        return problems
    }

    // ------------------------------------------------------------------ scenario f

    /** The controls the open More row shows that take a click, in reading order (as [ShapeStripKit.moreHandles]). */
    private fun moreClickables(): List<SemanticsNode> = RobolectricUi.elements().map { it.node }
        .filter { n -> n.layoutInfo.isPlaced && n.config.getOrNull(SemanticsActions.OnClick) != null && underMoreRow(n) }
        .sortedWith(compareBy({ it.positionInWindow.y.toInt() / 8 }, { it.positionInWindow.x }))

    /**
     * A disabled control keeps its click action in the semantics, which a tap never reaches. It is
     * skipped where it is clicked, not left out of [moreClickables]: whether a control is enabled
     * can follow a tool setting an earlier click changed, and the walk counts the row's controls by
     * index across its runs.
     */
    private fun disabled(n: SemanticsNode) = n.config.getOrNull(SemanticsProperties.Disabled) != null

    /**
     * The More-row controls ("state: \"handle\"") that close the row and open nothing, by design:
     * the Clone stamp's hint arms Set source, and the next canvas touch must reach the canvas (an
     * open row takes it, D25); the Text tool's wrap button with no text yet only toasts "Tap the
     * canvas to add a text, then wrap it around a picture".
     */
    private val CLOSES_WITH_NOTHING_OPEN = setOf(
        "CLONE: \"${CloneTool.HINT}\"",
        "CLONE@vector: \"${CloneTool.HINT}\"",
        "TEXT@vector: \"Wrap around picture\"",
    )

    /** Puts the rig back: [reset], then the document's steps undone down to [steps]. */
    private fun restore(rig: StripRig, steps: Int) {
        reset(rig)
        while (rig.c.undoManager.undoCount > steps) rig.c.undo()
        rig.settle()
    }

    /** [state] reached and its More row open; false (with the problem noted) when it is not reached. */
    private fun enter(rig: StripRig, state: State, problems: MutableList<String>): Boolean {
        try {
            state.setup(rig)
        } catch (e: Throwable) {
            problems += "${state.name}: not reached: ${e.message}"
            return false
        }
        return StripTestKit.openMoreRow() && StripTestKit.moreRowShown()
    }

    /**
     * Scenario f (MoreRowOverlayTest's rule on the real strips, D25): in each state, each control
     * of the More row is clicked in turn (the state set up anew for each, the document's steps
     * undone after it). A click that opens a window over the strip is checked:
     * - a sheet or a dialog: the More row has closed (the item called `closeMore()` first) and the
     *   window still shows once the UI settled twice more (it lives in the spec's overlays, not in
     *   the item the closed row took away);
     * - an anchored popup (a dropdown): the More row stays open while it shows; Back closes it and
     *   the row is still open.
     * A click that opens no window must leave the row open, unless it changed the tool, left the
     * strip without a More row, or is one of [CLOSES_WITH_NOTHING_OPEN]: an item that closes the
     * row for a sheet composed inside it shows nothing at all.
     * [opened] collects "state: \"handle\" (kind)" for each opener found. Returns the problems.
     */
    fun overlaysOutliveTheRow(rig: StripRig, opened: MutableList<String>, walk: List<State> = states()): List<String> {
        val problems = mutableListOf<String>()
        val steps = rig.c.undoManager.undoCount
        for (state in walk) {
            restore(rig, steps)
            if (!enter(rig, state, problems)) continue
            val count = moreClickables().size
            for (i in 0 until count) {
                if (i > 0) {
                    restore(rig, steps)
                    if (!enter(rig, state, problems)) break
                }
                val controls = moreClickables()
                if (controls.size != count) {
                    problems += "${state.name}: the More row shows ${controls.size} controls, then ${count}"
                    break
                }
                val node = controls[i]
                if (disabled(node)) continue
                val handle = StripInventory.handle(node) ?: "control ${i + 1}"
                val what = "${state.name}: \"$handle\""
                val before = RobolectricUi.windowRoots()
                val tool = rig.c.activeToolId
                node.config.getOrNull(SemanticsActions.OnClick)?.action?.invoke()
                rig.settle()
                val top = RobolectricUi.windowRoots().lastOrNull { it !in before }
                if (top == null) {
                    // Nothing stays open. A sheet or dialog composed inside its item, which closed the
                    // row first, died with the row and never showed: the row closes with nothing open
                    // only for a tool change, a strip left without a More row, or a known reason.
                    val explained = rig.c.activeToolId != tool || StripTestKit.toggle() == null || what in CLOSES_WITH_NOTHING_OPEN
                    if (!StripTestKit.moreRowShown() && !explained) problems += "$what: the More row closed and nothing stayed open (an overlay that died with the row?)"
                    continue
                }
                if (top.javaClass.name.contains("Popup")) {
                    opened += "$what (popup)"
                    if (!StripTestKit.moreRowShown()) problems += "$what: its popup closed the More row (an anchored popup keeps it open)"
                    rig.settle()
                    if (top !in RobolectricUi.windowRoots()) problems += "$what: its popup did not stay open"
                    back(rig)
                    if (top in RobolectricUi.windowRoots()) problems += "$what: Back left its popup open"
                    if (!StripTestKit.moreRowShown()) problems += "$what: the More row closed with its popup"
                } else {
                    opened += "$what (sheet or dialog)"
                    if (StripTestKit.moreRowShown()) problems += "$what: the More row stayed open under its sheet or dialog (closeMore() first)"
                    rig.settle()
                    rig.settle()
                    if (top !in RobolectricUi.windowRoots()) problems += "$what: its sheet or dialog closed after the row"
                }
            }
        }
        restore(rig, steps)
        return problems
    }

    /**
     * The overlay walk on fake specs: a sheet and a dialog from the overlays (their items close
     * the row first) and an anchored dropdown pass and are found as their kinds; a dialog whose
     * item leaves the row open, a dropdown composed in the overlays behind a closed row, and a
     * dialog composed inside its item (which dies with the row it closed) are caught. Returns what
     * went wrong.
     */
    fun overlayMachineryCatches(rig: StripRig): List<String> {
        val out = mutableListOf<String>()
        var sheet by mutableStateOf(false)
        var dialog by mutableStateOf(false)
        var menu by mutableStateOf(false)
        var late by mutableStateOf(false)
        var loose by mutableStateOf(false)
        var inner by mutableStateOf(false)
        val row = StripItem("row", "Probe row", 100.dp) { Probe("Probe row", 100) }
        val good = StripSpec(
            items = listOf(
                row,
                StripItem("sheet", "Probe sheet", 100.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    Box(Modifier.size(100.dp, 40.dp).clickable { ctx.closeMore(); sheet = true }.semantics { contentDescription = "Probe sheet" })
                },
                StripItem("dialog", "Probe dialog", 100.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    Box(Modifier.size(100.dp, 40.dp).clickable { ctx.closeMore(); dialog = true }.semantics { contentDescription = "Probe dialog" })
                },
                StripItem("menu", "Probe menu", 100.dp, row = StripRow.MORE) {
                    Box {
                        Box(Modifier.size(100.dp, 40.dp).clickable { menu = true }.semantics { contentDescription = "Probe menu" })
                        DropdownMenu(expanded = menu, onDismissRequest = { menu = false }) {
                            DropdownMenuItem(text = { Text("Probe entry") }, onClick = { menu = false })
                        }
                    }
                },
            ),
            overlays = {
                if (sheet) BwSheet(title = "Probe sheet", onDismiss = { sheet = false }) { Text("Probe sheet body") }
                if (dialog) BwDialog(title = "Probe dialog", onDismiss = { dialog = false }) { Text("Probe dialog body") }
            },
        )
        val bad = StripSpec(
            items = listOf(
                row,
                StripItem("late", "Probe late", 100.dp, row = StripRow.MORE) {
                    Box(Modifier.size(100.dp, 40.dp).clickable { late = true }.semantics { contentDescription = "Probe late" })
                },
                StripItem("loose", "Probe loose", 100.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    Box(Modifier.size(100.dp, 40.dp).clickable { ctx.closeMore(); loose = true }.semantics { contentDescription = "Probe loose" })
                },
                // A dialog composed inside its item (not in the overlays): the row it closes takes it along.
                StripItem("inner", "Probe inner", 100.dp, row = StripRow.MORE) {
                    val ctx = LocalStripContext.current
                    Box(Modifier.size(100.dp, 40.dp).clickable { ctx.closeMore(); inner = true }.semantics { contentDescription = "Probe inner" })
                    if (inner) BwDialog(title = "Probe inner", onDismiss = { inner = false }) { Text("Probe inner body") }
                },
            ),
            overlays = {
                if (late) BwDialog(title = "Probe late", onDismiss = { late = false }) { Text("Probe late body") }
                DropdownMenu(expanded = loose, onDismissRequest = { loose = false }) {
                    DropdownMenuItem(text = { Text("Probe loose entry") }, onClick = { loose = false })
                }
            },
        )
        try {
            val opened = mutableListOf<String>()
            val goodProblems = overlaysOutliveTheRow(rig, opened, listOf(State("good") { it.override = good; it.settle() }))
            if (goodProblems.isNotEmpty()) out += "the good fake: $goodProblems"
            val expected = listOf("good: \"Probe sheet\" (sheet or dialog)", "good: \"Probe dialog\" (sheet or dialog)", "good: \"Probe menu\" (popup)")
            if (opened != expected) out += "the good fake's openers: $opened"
            val badProblems = overlaysOutliveTheRow(rig, mutableListOf(), listOf(State("bad") { it.override = bad; it.settle() }))
            if (badProblems.none { it.startsWith("bad: \"Probe late\": the More row stayed open") }) out += "a dialog over an open row went unseen: $badProblems"
            if (badProblems.none { it.startsWith("bad: \"Probe loose\": its popup closed the More row") }) out += "a popup behind a closed row went unseen: $badProblems"
            if (badProblems.none { it.startsWith("bad: \"Probe inner\": the More row closed and nothing stayed open") }) out += "a dialog that died with the row went unseen: $badProblems"
        } finally {
            reset(rig)
            rig.override = null
            rig.settle()
        }
        return out
    }
}
