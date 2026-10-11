package com.brushwork.paint.audit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Path
import android.view.View
import android.view.ViewGroup
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.graphics.toArgb
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.filters.Filter
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterSession
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.filters.art.FringeColors
import com.brushwork.paint.filters.art.GlitchFilter
import com.brushwork.paint.fxsmoke.Fx
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.MoreWalk
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.qa16.QaCurves
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa18.Fingers18
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.PillTestKit
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.clone.CloneTool
import com.brushwork.paint.tools.select.colorrange.ColorPreset
import com.brushwork.paint.tools.select.colorrange.ColorRange
import com.brushwork.paint.tools.select.colorrange.ColorSelectTool
import com.brushwork.paint.tools.select.magnetic.MagneticLassoTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeBox
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.CogLabels18
import com.brushwork.paint.ui.common.ColorSelectLabels18
import com.brushwork.paint.ui.common.CurveGenLabels18
import com.brushwork.paint.ui.common.FringeLabels18
import com.brushwork.paint.ui.common.MagneticLabels18
import com.brushwork.paint.ui.common.MenuLabels18
import com.brushwork.paint.ui.common.MirrorLabels18
import com.brushwork.paint.ui.common.PointLabels
import com.brushwork.paint.ui.common.StripLabels18
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeLayout
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.editor.chrome.Clickables
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.theme.IbisColors
import com.brushwork.paint.ui.theme.IbisDims
import com.brushwork.paint.ui.tools.strip.StripLayout
import com.brushwork.paint.vector.CurveGenKind
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * REQUEST COVERAGE AUDIT (v1.8, integration): every item 1–9 of the user's v1.8 request, done the
 * way the user does it on the phone (fingers on the canvas, the tool menu, the top row, the
 * options strip and its More row, the X / Y pill, dropdowns, sheets, the filter panel and the
 * color picker), found by the labels and the V18Tags, the area reports' "how to use" lines as the
 * script, and shown to keep its visible promise in the document. Each item is one section in a
 * fresh editor; the items run in four groups, at 392 dp (the user's ZTE Axon 50 Lite), and the
 * mirrors, the selection tools and the chrome again at 360 dp: seven classes, one UI test each,
 * each its own sandbox.
 *
 * Setting up what an item starts from (pixels to work on, a vector layer, a picture for a filter)
 * is done in code as the area tests do; the item's own action always goes through the screen.
 */
internal class RequestCoverageV18 private constructor(private val h: ChromeHarness, private val widthDp: Float) {

    /** The four item groups. */
    enum class Group { MIRRORS, SELECT, CHROME, LOOKS }

    companion object {
        fun run(group: Group, widthDp: Float) {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 240_000)
            val h = ChromeHarness()
            val t = RequestCoverageV18(h, widthDp)
            val at = "at ${widthDp.roundToInt()} dp"
            when (group) {
                Group.MIRRORS -> {
                    h.section("1 a curve's mirror copy, reopened from the copy, removed $at") { t.curveMirror() }
                    h.section("1 polyline and path mirrors $at") { t.polylineAndPathMirrors() }
                    h.section("1 a shape's mirror copy, reopened from the copy, removed $at") { t.shapeMirror() }
                    h.section("1 the clone stamp with a mirror $at") { t.cloneMirror() }
                    h.section("1 Select several in the X / Y pill: a curve $at") { t.severalOnACurve() }
                    h.section("1 Select several in the X / Y pill: free deform $at") { t.severalInFreeDeform() }
                }
                Group.SELECT -> {
                    h.section("3 the magnetic lasso, tap to tap $at") { t.magneticLasso() }
                    h.section("4 color select: a sample, the fuzziness, the preset chips $at") { t.colorSelect() }
                }
                Group.CHROME -> {
                    h.section("8 the More menu in five sections, an entry acts $at") { t.moreMenu() }
                    h.section("8 and 9 the options strip's More row, its first canvas touch, the chevron's name $at") { t.stripMoreRow() }
                    h.section("8 the More row wraps: the clone stamp's $at") { t.cloneMoreRow() }
                    h.section("9 a long press names each top-row circle and does nothing else $at") { t.topRowLongPress() }
                    h.section("9 the More menu is opaque $at") { t.opaqueMoreMenu() }
                }
                Group.LOOKS -> {
                    h.section("2 an array's look: opacity, size and tint from copy to copy $at") { t.arrayLook() }
                    h.section("5 fringe colors and the static's color $at") { t.fringeColors() }
                    h.section("6 a generated spiral, changed in its sheet $at") { t.generatedSpiral() }
                    h.section("7 the cog $at") { t.cog() }
                }
            }
            dog.interrupt()
            ArrayDraw.clearCaches()
            h.finish()
        }

        private const val RED = 0xFFDD2211.toInt()
        private const val BLUE = 0xFF2244CC.toInt()

        /** A Tint colors preset (four stops). */
        private const val SUNSET = "Sunset"
        private const val WHITE = 0xFFFFFFFF.toInt()
        private const val BLACK = 0xFF000000.toInt()

        /** Color select's halves (as the area test): a red and a blue far apart in hue. */
        private const val SEL_RED = 0xFFE01010.toInt()
        private const val SEL_BLUE = 0xFF1030D0.toInt()

        /** An orange about 49 ΔE from [SEL_RED]: out of its range at the default Fuzziness (40), wholly in at 120. */
        private const val SEL_ORANGE = 0xFFE08820.toInt()

        /** The Fuzziness the test sets with the slider. */
        private const val WIDE_FUZZINESS = 120

        /** How long a render or a computed result may take: 10 s on a desktop, scaled on CI. */
        private val WAIT_MS: Long = PerfBudget.ms(10_000.0).toLong()

        /** Three taps of a small arch on the left half (the mirror puts its copy on the right half). */
        private val ARCH = listOf(Vec2(60f, 100f), Vec2(100f, 60f), Vec2(140f, 100f))

        /** The top row's circles by slot (I10, unchanged since v1.6). */
        private val TOP_LABELS = mapOf(
            ChromeLayout.TopSlot.UNDO to "Undo", ChromeLayout.TopSlot.REDO to "Redo", ChromeLayout.TopSlot.VECTOR to "Vector",
            ChromeLayout.TopSlot.SELECTION to "Selection", ChromeLayout.TopSlot.STABILIZER to "Stabilizer", ChromeLayout.TopSlot.GRID to "Grid",
            ChromeLayout.TopSlot.RULER to "Ruler", ChromeLayout.TopSlot.MORE to "More options",
        )

        /** The More menu's sections and their entries, top to bottom (area H). */
        private val SECTIONS = listOf(
            MenuLabels18.CLIPBOARD to listOf("Copy layer", "Paste"),
            MenuLabels18.BRING_IN to listOf("Import picture", "Import SVG or PDF…"),
            MenuLabels18.SEND_OUT to listOf("Export PNG", "Export JPG", "Export SVG…", "Export PDF…", "Share"),
            MenuLabels18.VIEW to listOf("Flip view", "Fit to screen", "100% (actual pixels)", "Reset rotation"),
            MenuLabels18.THIS_ARTWORK to listOf("Canvas…", "Increments…", "Save now", "Settings"),
        )
    }

    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c: EditorController get() = s.c

    // ================================================================== the editor and fingers

    private fun editor(doc: Document = Smoke.document(400, 300, layers = 2, whiteBottom = true)): ChromeScreen {
        s = h.editor(doc) { it.snapping.enabled = false }
        ui = Qa16Ui(s)
        settle()
        assertEquals("the phone is ${widthDp.roundToInt()} dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    /** A white page and an empty vector layer above it, active. */
    private fun vectorDoc(): Document = Smoke.document(400, 300, layers = 1, whiteBottom = true).also { d ->
        d.layers += Layer(d.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        d.activeLayerIndex = d.layers.lastIndex
    }

    private fun tap(x: Float, y: Float) = ui.tap(x, y)

    private fun tap(p: Vec2) = ui.tap(p.x, p.y)

    /** Picks [label] in the tool menu, scrolling it as a finger would. */
    private fun tool(label: String) {
        ui.tool(label)
        settle()
    }

    /** Brings the control [label] wholly into view (scrolling its strip or sheet), then taps it. */
    private fun press(label: String, minDp: Float = 32f) {
        ui.reach(label, minDp)
        click(label, exact = true)
    }

    private fun steps(): Int = c.undoManager.undoCount

    /** Pixels of [layer] with any alpha in [l, r) × [t, b) (clamped to the canvas). */
    private fun ink(layer: Layer, l: Int, t: Int, r: Int, b: Int): Int {
        val bmp = layer.bitmap
        var n = 0
        for (y in t.coerceAtLeast(0) until b.coerceAtMost(bmp.height)) {
            for (x in l.coerceAtLeast(0) until r.coerceAtMost(bmp.width)) if (bmp.getPixel(x, y) ushr 24 > 0) n++
        }
        return n
    }

    /** Pixels of [layer] with any alpha within [r] px of [p] (a square). */
    private fun ink(layer: Layer, p: Vec2, r: Int): Int {
        val x = p.x.roundToInt()
        val y = p.y.roundToInt()
        return ink(layer, x - r, y - r, x + r + 1, y + r + 1)
    }

    /** Pixels of [layer] in [l, r) × [t, b) that are opaque enough and whose color passes [test] (r, g, b). */
    private fun tinted(layer: Layer, l: Int, t: Int, r: Int, b: Int, test: (Int, Int, Int) -> Boolean): Int {
        var n = 0
        for (y in t until b) for (x in l until r) {
            val p = layer.bitmap.getPixel(x, y)
            if (p ushr 24 >= 200 && test((p shr 16) and 0xFF, (p shr 8) and 0xFF, p and 0xFF)) n++
        }
        return n
    }

    private fun pixels(layer: Layer): IntArray = Fx.pixels(layer.bitmap)

    private fun count(layer: Layer, color: Int): Int = pixels(layer).count { it == color }

    private fun seed(layer: Layer, l: Float, t: Float, r: Float, b: Float, color: Int) {
        assertTrue(c.editWholeLayer(layer, "Seed") { bmp -> Canvas(bmp).drawRect(l, t, r, b, Paint().apply { this.color = color }) })
        settle()
    }

    private fun rectSelection(l: Float, t: Float, r: Float, b: Float): Selection =
        Selection.fromPath(Path().apply { addRect(l, t, r, b, Path.Direction.CW) }, c.doc.width, c.doc.height, antiAlias = false)

    /** The state description of the control whose content description contains [label]. */
    private fun stateOf(label: String): String? = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.any { it.contains(label) } == true
    }?.node?.config?.getOrNull(SemanticsProperties.StateDescription)

    /** Taps the floating ✓ ("Apply <tool> edit") and waits for what it starts. */
    private fun applyEdit(label: String) {
        click(label)
        assertTrue(
            "$label: done",
            Smoke.pumpUntil(WAIT_MS) { settle(1); !c.currentTool.hasPendingWork && c.busyMessage == null && !c.vectors.isRendering && !c.arrayRenders.isPending },
        )
        settle()
    }

    /** Every render on its way has landed (a vector layer, an array cache, a busy overlay). */
    private fun settleRenders(what: String) {
        assertTrue("$what: renders landed", Smoke.pumpUntil(WAIT_MS) { settle(1); !c.vectors.isRendering && !c.arrayRenders.isPending && c.busyMessage == null })
        settle()
    }

    /** The placed node whose text is [label] (the last one). */
    private fun byText(label: String): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true
    } ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown().take(80)}")

    /** The placed node whose content description is [label] (the last one). */
    private fun byDescription(label: String): RobolectricUi.Element = RobolectricUi.elements().lastOrNull { e ->
        e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(label) == true
    } ?: throw AssertionError("nothing described \"$label\"; shown: ${SmokeUi.shown().take(80)}")

    /**
     * The element [find] returns, after scrolling each scrolling ancestor (the panel's column, a
     * chip row) until it lies wholly inside it, as a finger scrolls before it taps.
     */
    private fun visible(find: () -> RobolectricUi.Element): RobolectricUi.Element {
        repeat(12) {
            val e = find()
            val pos = e.node.positionInWindow
            val l = pos.x; val t = pos.y; val r = l + e.node.size.width; val b = t + e.node.size.height
            val scrollers = generateSequence(e.node.parent) { it.parent }.filter { it.config.getOrNull(SemanticsActions.ScrollBy) != null }.toList().asReversed()
            for (n in scrollers) {
                val p = n.positionInWindow
                val vl = p.x; val vt = p.y; val vr = vl + n.size.width; val vb = vt + n.size.height
                val across = n.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange) != null
                val delta = if (across) {
                    when { r > vr -> r - vr + 8f; l < vl -> l - vl - 8f; else -> 0f }
                } else {
                    when { b > vb -> b - vb + 8f; t < vt -> t - vt - 8f; else -> 0f }
                }
                if (delta != 0f) {
                    val scroll = requireNotNull(n.config[SemanticsActions.ScrollBy].action)
                    if (across) scroll(delta, 0f) else scroll(0f, delta)
                    // The scroll animates: a touch while it runs only stops it (as a finger on a moving list).
                    Smoke.pump(400)
                    settle()
                    return@repeat
                }
            }
            return e
        }
        return find()
    }

    /** The mirror ruler on, as the user does it: the Symmetry tool (Mirror is its first ruler), then Done. */
    private fun mirrorOn() {
        tool("Symmetry")
        assertEquals("the mirror is on", SymmetryType.MIRROR, c.symmetry.type)
        click("Done", exact = true)
        assertNotEquals("Done leaves the Symmetry tool", ToolId.SYMMETRY, c.activeToolId)
    }

    /** x ↦ width − x: where the mirror ruler (its axis down the middle) puts [p]. */
    private fun mirrored(p: Vec2) = Vec2(c.doc.width - p.x, p.y)

    /** A point on [p]'s own line (its source, not a copy), half way along. */
    private fun onLine(p: VPath): Vec2 {
        val pts = VectorOps.toVectorPath(ObjectCopies.source(p) as VPath).flatten(0.25f).first().points
        return pts[pts.size / 2]
    }

    // ================================================================== 1. mirrors

    /**
     * Area E: "turn on a Symmetry ruler, then draw with Curve, Polyline or Path. The copies follow
     * live. Confirm with the check mark. On a vector layer the curve keeps its copies; tap any
     * copy's line to edit the whole set again. To drop the copies from the open curve: Show more
     * tool options > Remove mirror copies."
     */
    private fun curveMirror() {
        editor(vectorDoc())
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        mirrorOn()
        tool("Curve")
        val tool = c.currentTool as CurveTool
        for (p in ARCH) tap(p)
        assertEquals("three points by three taps", 3, tool.pointCount)
        assertTrue("the copy follows while the curve is drawn", tool.hasCopies)
        val before = steps()
        applyEdit("Apply curve edit")
        assertEquals("one step", before + 1, steps())
        val layer = c.activeLayer
        val stored = layer.vector!!.objects.single() as VPath
        assertEquals("the curve and its mirror copy, one object", 2, ObjectCopies.of(stored).count)
        val p = onLine(stored)
        assertTrue("the curve is drawn at $p", ink(layer, p, 6) > 0)
        assertTrue("and its mirror copy at ${mirrored(p)}", ink(layer, mirrored(p), 6) > 0)

        // A tap on the copy's line opens the curve itself, with its copies.
        tap(mirrored(p))
        assertTrue("reopened from its copy", tool.isReopened)
        assertTrue("with its copies", tool.hasCopies)
        press(MirrorLabels18.REMOVE)
        assertFalse("the copies removed", tool.hasCopies)
        // The More row (where Remove is) swallows the next canvas touch (D25): closed first.
        StripTestKit.closeMoreRow()
        applyEdit("Apply curve edit")
        val after = layer.vector!!.objects.single() as VPath
        assertEquals("the same curve", stored.id, after.id)
        assertTrue("stored without copies", after.copies.isEmpty)
        assertTrue("the curve stays", ink(layer, p, 6) > 0)
        assertEquals("its mirror copy is gone", 0, ink(layer, mirrored(p), 6))
        Smoke.assertQuiet(c, "curve mirror")
    }

    private fun polylineAndPathMirrors() {
        editor(vectorDoc())
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        mirrorOn()
        val layer = c.activeLayer
        for ((label, dy) in listOf("Polyline" to 0f, "Path" to 130f)) {
            tool(label)
            val tool = c.currentTool as CurveTool
            for (p in ARCH) tap(p.x, p.y + dy)
            assertEquals("$label: three points by three taps", 3, tool.pointCount)
            assertTrue("$label: the copy follows", tool.hasCopies)
            val before = steps()
            applyEdit("Apply ${label.lowercase()} edit")
            assertEquals("$label: one step", before + 1, steps())
            val stored = layer.vector!!.objects.last() as VPath
            assertEquals("$label: the path and its mirror copy, one object", 2, ObjectCopies.of(stored).count)
            // Both ends of every kind of path lie on its first and last points.
            val end = Vec2(ARCH[0].x, ARCH[0].y + dy)
            assertTrue("$label: drawn from its first point", ink(layer, end, 6) > 0)
            assertTrue("$label: and mirrored at ${mirrored(end)}", ink(layer, mirrored(end), 6) > 0)
        }
        assertEquals("two objects on the layer", 2, layer.vector!!.objects.size)
        Smoke.assertQuiet(c, "polyline and path mirrors")
    }

    /**
     * Area F: "turn on a ruler, pick Shape and drag out a shape. The copies show while you draw and
     * are committed with the shape. [...] tap a copy to open the shape. To drop the copies, tap
     * Show more tool options, then Remove mirror copies."
     */
    private fun shapeMirror() {
        editor(vectorDoc())
        mirrorOn()
        tool("Shape")
        val tool = c.currentTool as ShapeTool
        ui.stroke(60f to 80f, 100f to 120f, 150f to 160f)
        assertNotNull("a pending shape", tool.box)
        assertTrue("the copy shows while it is drawn", tool.hasCopies)
        val before = steps()
        applyEdit("Apply shape edit")
        assertEquals("one step", before + 1, steps())
        val layer = c.activeLayer
        val o = layer.vector!!.objects.single() as VShape
        assertEquals("the shape and its mirror copy, one object", 2, o.shape.copies.count)
        val left = Vec2(o.shape.cx - o.shape.w / 2f, o.shape.cy)
        assertTrue("the shape's left side at $left", ink(layer, left, 5) > 0)
        assertTrue("its mirror copy's right side at ${mirrored(left)}", ink(layer, mirrored(left), 5) > 0)

        // A tap on the copy opens the shape; Remove mirror copies drops them.
        tap(mirrored(left))
        assertNotNull("the shape is open again", tool.box)
        assertTrue("with its copies", tool.hasCopies)
        press(MirrorLabels18.REMOVE)
        assertFalse("the copies removed", tool.hasCopies)
        StripTestKit.closeMoreRow()
        applyEdit("Apply shape edit")
        val after = layer.vector!!.objects.single() as VShape
        assertTrue("stored without copies", after.shape.copies.isEmpty)
        assertTrue("the shape stays", ink(layer, left, 5) > 0)
        assertEquals("its mirror copy is gone", 0, ink(layer, mirrored(left), 5))
        Smoke.assertQuiet(c, "shape mirror")
    }

    /**
     * Area D: "turn on a ruler with the Symmetry tool, tap Done, pick Clone stamp, [set] the
     * source, then paint. Each mirrored copy clones from its own mirrored source spot." The source
     * spot is red; its mirror spot is blue: the stroke paints red, its mirror copy blue.
     */
    private fun cloneMirror() {
        editor()
        val layer = c.activeLayer
        seed(layer, 40f, 70f, 120f, 130f, RED)
        seed(layer, 280f, 70f, 360f, 130f, BLUE)
        val pen = BrushLibrary.clones.first { it.id == "clone_pen" }
        c.updatePreset(
            ToolId.CLONE,
            pen.copy(size = 10f, hardness = 1f, pressureSize = false, minSizeRatio = 1f, opacity = 1f, flow = 1f, taperStart = 0f, taperEnd = 0f),
        )
        mirrorOn()
        tool("Clone stamp")
        val clone = c.currentTool as CloneTool
        press("Set source")
        assertTrue("Set source arms the next tap", clone.armed)
        assertTrue("the chip says so: ${SmokeUi.shown().take(60)}", has("Tap the source", exact = true))
        tap(80f, 100f)
        val source = clone.anchor.source ?: throw AssertionError("the tap set no source")
        assertEquals(80f, source.x, 1.5f)
        assertEquals(100f, source.y, 1.5f)
        assertFalse("disarmed", clone.armed)

        val before = steps()
        ui.stroke(80f to 200f, 88f to 200f, 96f to 200f)
        assertEquals("one stroke, one step", before + 1, steps())
        fun reddish(r: Int, g: Int, b: Int) = r > 180 && g < 90 && b < 90
        fun bluish(r: Int, g: Int, b: Int) = b > 160 && r < 90
        val red = tinted(layer, 74, 194, 102, 206, ::reddish)
        val blue = tinted(layer, 298, 194, 326, 206, ::bluish)
        println("RequestCoverageV18 item 1 (clone) at $widthDp dp: $red red px at the stroke, $blue blue px at its mirror")
        assertTrue("the stroke clones the red source ($red px)", red >= 60)
        assertTrue("its mirror copy clones the mirrored (blue) source ($blue px)", blue >= 60)
        assertEquals("no blue at the stroke", 0, tinted(layer, 74, 194, 102, 206, ::bluish))
        assertEquals("no red at its mirror", 0, tinted(layer, 298, 194, 326, 206, ::reddish))
        Smoke.assertQuiet(c, "clone mirror")
    }

    /** The "Select several" cell (dp): on screen, a finger's size, ending the pill's X / Y row. */
    private fun severalCell(what: String): Rect {
        val cell = s.tagged(V18Tags.PILL_SEVERAL) ?: throw AssertionError("$what: no Select several cell; shown: ${SmokeUi.shown().take(80)}")
        assertTrue("$what: the cell is on screen: $cell", cell.left >= 0f && cell.right <= s.widthDp + 0.5f && cell.top >= 0f && cell.bottom <= s.heightDp + 0.5f)
        assertTrue("$what: a finger's size: $cell", cell.width >= 40f - 0.5f && cell.height >= 40f - 0.5f)
        return cell
    }

    private fun tapCell(cell: Rect) = Finger.tapAt(s, cell.center.x, cell.center.y)

    /**
     * Area G: "To pick several points, turn on Select several on the X / Y pill." On a curve: the
     * cell sits at the end of the pill's X / Y row (the strips' chips are gone); its taps pick two
     * points that one typed X moves together.
     */
    private fun severalOnACurve() {
        editor()
        c.brush = c.brush.copy(size = 10f, opacity = 1f)
        tool("Curve")
        val tool = c.currentTool as CurveTool
        tap(100f, 200f)
        tap(200f, 100f)
        tap(300f, 200f)
        assertEquals(3, tool.pointCount)
        val cell = severalCell("curve")
        val x = Finger.control(s, "Type X") ?: throw AssertionError("no X cell in the pill")
        assertTrue("the cell is on the X / Y row: $cell, X $x", x.center.y >= cell.top && x.center.y <= cell.bottom)
        assertTrue("right of X: $cell, X $x", cell.left >= x.right - 0.5f)
        // The pill's cell is the only "Select several" (none on the strip, More row included).
        assertTrue(StripTestKit.openMoreRow() || StripTestKit.moreRowShown() || s.tagged(V18Tags.STRIP_TOGGLE) == null)
        val several = s.placed().count { e -> e.node.config.getOrNull(SemanticsProperties.ContentDescription)?.contains(PointLabels.SELECT_SEVERAL) == true }
        assertEquals("one Select several: the pill's", 1, several)
        StripTestKit.closeMoreRow()

        tapCell(cell)
        assertTrue("the cell turns it on", tool.selectSeveral)
        assertEquals("On", PillTestKit.severalState())
        val start = (0 until 3).map { tool.pointAt(it) }
        tap(start[0])
        tap(start[2])
        assertEquals("two taps pick both ends", listOf(0, 2), tool.pointSelection.indices)
        val cx = tool.pillPosition.position!!.x
        val target = cx.roundToInt() + 30
        click("Type X")
        SmokeUi.typeAndDone("X", "$target")
        val dx = target - cx
        assertEquals("the first end moved", start[0].x + dx, tool.pointAt(0).x, 0.05f)
        assertEquals("the last end moved", start[2].x + dx, tool.pointAt(2).x, 0.05f)
        assertEquals("the middle stays", start[1].x, tool.pointAt(1).x, 0.05f)
        tapCell(severalCell("curve, off"))
        assertFalse("a second tap turns it off", tool.selectSeveral)
        assertEquals("Off", PillTestKit.severalState())
        applyEdit("Apply curve edit")
        val layer = c.activeLayer
        assertTrue("drawn to the moved end", ink(layer, Vec2(start[2].x + dx, start[2].y), 6) > 0)
        assertEquals("nothing at the old first end", 0, ink(layer, start[0], 6))
        Smoke.assertQuiet(c, "Select several on a curve")
    }

    private fun severalInFreeDeform() {
        editor()
        val layer = c.activeLayer
        seed(layer, 100f, 80f, 300f, 220f, RED)
        val original = pixels(layer)
        tool("Transform")
        val tt = c.currentTool as TransformTool
        assertTrue(Smoke.pumpUntil { settle(1); tt.transformState != null })
        // Free deform is an entry of the mode dropdown (its button shows the current mode).
        press(TransformTool.Mode.FREE.label)
        press(TransformLabels17.FREE_DEFORM)
        assertTrue("the mesh", Smoke.pumpUntil { settle(1); tt.isMeshShown })
        settle()
        StripTestKit.closeMoreRow()
        tapCell(severalCell("free deform"))
        assertTrue("the cell turns it on", tt.selectSeveral)
        val stride = tt.meshColumns + 1
        val picked = listOf(stride + 1, stride + 2)
        val start = (0 until tt.pointCount).map { tt.pointAt(it) }
        for (i in picked) QaCurves.tap(s, start[i])
        assertEquals("two vertices picked by two taps", picked, tt.pointSelection.indices)
        QaCurves.drag(s, start[stride + 1], Vec2(0f, 30f))
        val d = tt.pointAt(stride + 1) - start[stride + 1]
        assertTrue("the grabbed vertex moved: $d", d.length > 5f)
        for (i in start.indices) {
            val want = if (i in picked) start[i] + d else start[i]
            val got = tt.pointAt(i)
            assertTrue("vertex $i: $got, wanted $want", abs(got.x - want.x) <= 0.5f && abs(got.y - want.y) <= 0.5f)
        }
        tapCell(severalCell("free deform, off"))
        assertFalse(tt.selectSeveral)
        val before = steps()
        applyEdit("Apply transform edit")
        assertEquals("ONE step", before + 1, steps())
        assertFalse("the red block is deformed", pixels(layer).contentEquals(original))
        Smoke.assertQuiet(c, "Select several in free deform")
    }

    // ================================================================== 3. the magnetic lasso

    /**
     * Area C: "pick Magnetic lasso in the tool menu and tap points around the object. Each segment
     * follows the nearest strong edge. [...] drag an existing point to move it. Tap a point to make
     * it active, then tap Delete point. Undo and Redo in the top row step through the point
     * changes. [...] close the outline by tapping the first point." Four taps on a disc's rim
     * select the disc, not the four-sided figure straight segments would make.
     */
    private fun magneticLasso() {
        editor()
        val layer = c.activeLayer
        assertTrue(
            c.editWholeLayer(layer, "Seed") { bmp ->
                Canvas(bmp).apply {
                    drawColor(WHITE)
                    drawCircle(200f, 150f, 100f, Paint().apply { color = BLACK; isAntiAlias = true })
                }
            },
        )
        settle()
        tool("Magnetic lasso")
        val tool = c.currentTool as MagneticLassoTool
        assertTrue("the edges are found", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.edgeMapReady && tool.idle })
        fun idle() = assertTrue(Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })
        val rim = listOf(Vec2(200f, 50f), Vec2(300f, 150f), Vec2(200f, 250f), Vec2(100f, 150f))
        for (p in rim.take(3)) {
            tap(p)
            idle()
        }
        assertEquals("three points by three taps", 3, tool.anchorCount)
        assertTrue("the close shows the count: ${SmokeUi.shown().take(60)}", has("3 pt", exact = true))
        click("Delete point", exact = true)
        assertEquals("Delete point", 2, tool.anchorCount)
        Finger.tap(s, "Undo")
        idle()
        assertEquals("the top row's Undo brings it back", 3, tool.anchorCount)
        // Clarification 2: "Every point can be dragged and undone": the second point dragged along
        // the rim to 30°, then the top row's Undo puts it back.
        val p1 = Vec2(tool.points.x(1), tool.points.y(1))
        assertTrue("the second point sits at its tap: $p1", (p1 - rim[1]).length <= 6f)
        val to = Vec2(200f + 100f * cos(PI / 6).toFloat(), 150f + 100f * sin(PI / 6).toFloat())
        ui.stroke(p1.x to p1.y, (p1.x + to.x) / 2f to (p1.y + to.y) / 2f, to.x to to.y)
        idle()
        assertEquals("a drag moves a point and adds none", 3, tool.anchorCount)
        val dragged = Vec2(tool.points.x(1), tool.points.y(1))
        assertTrue("the point followed the finger: $dragged, the finger at $to", (dragged - to).length <= 6f)
        Finger.tap(s, "Undo")
        idle()
        assertEquals(3, tool.anchorCount)
        assertEquals("Undo puts the dragged point back (x)", p1.x, tool.points.x(1), 0.5f)
        assertEquals("Undo puts the dragged point back (y)", p1.y, tool.points.y(1), 0.5f)
        tap(rim[3])
        idle()
        assertEquals(4, tool.anchorCount)
        tap(rim[0])
        assertTrue("a tap on the first point closes and selects", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.selection != null && c.busyMessage == null })
        settle()
        assertEquals("the outline is done", 0, tool.anchorCount)
        assertEquals(ToolId.MAGNETIC_LASSO.label, c.undoManager.undoLabel)
        val area = c.selection!!.toBytes().sumOf { (it.toInt() and 0xFF).toLong() } / 255.0
        val disc = PI * 100.0 * 100.0
        println("RequestCoverageV18 item 3 at $widthDp dp: the selection is ${area.roundToInt()} px, the disc ${disc.roundToInt()} px")
        assertTrue("the selection follows the disc's edge: $area px, the disc $disc px, straight segments 20000 px", abs(area - disc) <= 0.04 * disc)
        Smoke.assertQuiet(c, "magnetic lasso")
    }

    // ================================================================== 4. color select

    /**
     * Area C: "pick Color select in the tool menu and tap colors on the canvas to add samples. [...]
     * You can also add a color with Add from the color picker [...]. Tap the Color range chip to
     * pick a preset [...]. Tap Fuzziness to open a one-line slider under the strip and widen or
     * narrow the range. On narrow phones, Fuzziness is in the More row. [...] tap Select colors
     * (✓) to make the selection." The widened Fuzziness shows in the selection: an orange stripe
     * the red sample does not reach at first is selected after it.
     */
    /** CIE ΔE76 between the colors [a] and [b] (as the tool measures a sample's reach). */
    private fun deltaE(a: Int, b: Int): Float {
        val la = FloatArray(3)
        val lb = FloatArray(3)
        ColorRange.lab(a, la)
        ColorRange.lab(b, lb)
        return sqrt((0 until 3).sumOf { ((la[it] - lb[it]) * (la[it] - lb[it])).toDouble() }).toFloat()
    }

    private fun colorSelect() {
        editor()
        val layer = c.activeLayer
        seed(layer, 0f, 0f, 200f, 300f, SEL_RED)
        seed(layer, 200f, 0f, 400f, 300f, SEL_BLUE)
        // An orange stripe over the red half's top: only a wider Fuzziness reaches it from the red.
        seed(layer, 0f, 0f, 200f, 60f, SEL_ORANGE)
        tool("Color select")
        val tool = c.currentTool as ColorSelectTool
        assertTrue(Smoke.pumpUntil(WAIT_MS) { settle(1); tool.proxyReady && tool.idle && tool.preview != null })
        // The colors keep the Fuzziness check honest: the sampled red reaches its full color to F / 2
        // ΔE and nothing from F on.
        val fuzz = tool.settings.fuzziness
        val toOrange = deltaE(SEL_RED, SEL_ORANGE)
        val toBlue = deltaE(SEL_RED, SEL_BLUE)
        assertTrue("the orange is out of the red's reach at Fuzziness $fuzz: ΔE $toOrange", toOrange >= fuzz + 2f)
        assertTrue("and wholly in at $WIDE_FUZZINESS: ΔE $toOrange", toOrange <= WIDE_FUZZINESS / 2f - 2f)
        assertTrue("the blue stays out at $WIDE_FUZZINESS: ΔE $toBlue", toBlue >= WIDE_FUZZINESS + 2f)
        val red = 100 to 150
        val orange = 100 to 30
        val blue = 300 to 150

        /** Taps Select colors (✓) and waits for the new selection it makes. */
        fun select(what: String): Selection {
            val prev = c.selection
            click(ColorSelectLabels18.SELECT_COLORS, exact = true)
            assertTrue("$what: a new selection", Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && c.busyMessage == null && c.selection != null && c.selection !== prev })
            settle()
            return c.selection!!
        }

        fun Selection.at(p: Pair<Int, Int>) = alphaAt(p.first, p.second)

        /** The red preview's strength (0..255, what the overlay tints red) at document point [p]. */
        fun previewAt(p: Pair<Int, Int>): Int {
            val pv = tool.preview!!
            return pv.mask[p.second * pv.height / c.doc.height * pv.width + p.first * pv.width / c.doc.width].toInt() and 0xFF
        }

        // A tap samples the red; the red preview shows it before the check; Select colors selects
        // the red half, not the orange stripe.
        val v0 = tool.preview!!.version
        tap(red.first.toFloat(), red.second.toFloat())
        assertTrue(Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle && tool.preview!!.version > v0 })
        assertEquals("the tap sampled the red", listOf(SEL_RED), tool.sampleColors)
        assertEquals("the preview shows the red half before the check", listOf(255, 0, 0), listOf(red, orange, blue).map { previewAt(it) })
        var sel = select("the red")
        assertEquals("the red half is selected", 255, sel.at(red))
        assertEquals("the orange stripe is not, at Fuzziness $fuzz", 0, sel.at(orange))
        assertEquals("the blue half is not", 0, sel.at(blue))

        // The fuzziness: on row 1 at 392 dp, on the More row on a narrower phone; its panel's slider.
        StripTestKit.closeMoreRow()
        assertEquals("Fuzziness on row 1 at 392 dp only", widthDp >= 391.5f, stateOf(ColorSelectLabels18.FUZZINESS) != null)
        click(ColorSelectLabels18.FUZZINESS, exact = true)
        val v = tool.preview!!.version
        ui.setSlider(ColorSelectLabels18.FUZZINESS, WIDE_FUZZINESS.toFloat())
        assertEquals(WIDE_FUZZINESS, tool.settings.fuzziness)
        assertTrue("a new preview", Smoke.pumpUntil(WAIT_MS) { settle(1); (tool.preview?.version ?: 0) > v && tool.idle })
        Finger.back()
        StripTestKit.closeMoreRow()
        assertEquals("the chip says the value", "$WIDE_FUZZINESS", stateOf(ColorSelectLabels18.FUZZINESS) ?: run {
            StripTestKit.openMoreRow()
            stateOf(ColorSelectLabels18.FUZZINESS).also { StripTestKit.closeMoreRow() }
        })
        // The wider range reaches the orange, still not the blue: first in the preview, then selected.
        assertEquals("the preview widens to the orange stripe", listOf(255, 255, 0), listOf(red, orange, blue).map { previewAt(it) })
        sel = select("Fuzziness $WIDE_FUZZINESS")
        assertEquals("the red half stays selected", 255, sel.at(red))
        assertEquals("the orange stripe now is", 255, sel.at(orange))
        assertEquals("the blue half still is not", 0, sel.at(blue))

        // A color from the color picker is a sample too: the blue, typed in the picker.
        StripTestKit.closeMoreRow()
        click(ColorSelectLabels18.FROM_PICKER, exact = true)
        settle()
        pickHex("1030D0")
        assertEquals("the picker added the blue", listOf(SEL_RED, SEL_BLUE), tool.sampleColors)
        assertTrue(Smoke.pumpUntil(WAIT_MS) { settle(1); tool.idle })
        sel = select("two samples")
        assertEquals("the red half", 255, sel.at(red))
        assertEquals("the orange stripe", 255, sel.at(orange))
        assertEquals("and now the blue half", 255, sel.at(blue))

        // The preset chips: Color range › Blues, then Select colors: the blue half alone.
        StripTestKit.closeMoreRow()
        click(ColorSelectLabels18.COLOR_RANGE, exact = true)
        val v2 = tool.preview!!.version
        click(ColorSelectLabels18.BLUES, exact = true)
        assertEquals(ColorPreset.BLUES, tool.settings.preset)
        assertTrue("a new preview", Smoke.pumpUntil(WAIT_MS) { settle(1); (tool.preview?.version ?: 0) > v2 && tool.idle })
        StripTestKit.closeMoreRow()
        assertEquals("the chip says its preset", ColorSelectLabels18.BLUES, stateOf(ColorSelectLabels18.COLOR_RANGE) ?: run {
            StripTestKit.openMoreRow()
            stateOf(ColorSelectLabels18.COLOR_RANGE).also { StripTestKit.closeMoreRow() }
        })
        sel = select("Blues")
        assertEquals("the blue half is selected", 255, sel.at(blue))
        assertEquals("the red half no longer", 0, sel.at(red))
        assertEquals("nor the orange stripe", 0, sel.at(orange))
        StripTestKit.closeMoreRow()
        Smoke.assertQuiet(c, "color select")
    }

    // ================================================================== 8. the More menu, the More row

    private fun SemanticsNode.subtreeText(): List<String> =
        config.getOrNull(SemanticsProperties.Text).orEmpty().map { it.text } + children.flatMap { it.subtreeText() }

    /** The More menu's section headers in [w], top to bottom (laid out, scrolled out or not). */
    private fun headers(w: View): List<SemanticsNode> = RobolectricUi.elements().filter { it.window === w }.map { it.node }
        .filter { it.config.getOrNull(SemanticsProperties.TestTag)?.startsWith(V18Tags.MENU_SECTION_PREFIX) == true }
        .sortedBy { it.positionInWindow.y }

    /** The laid-out middle y (window px) of the menu entry whose text is [label]. */
    private fun entryY(w: View, label: String): Float {
        val text = RobolectricUi.elements().firstOrNull { e -> e.window === w && e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
            ?: throw AssertionError("no \"$label\" in the More menu")
        var n: SemanticsNode? = text.node
        while (n != null && n.config.getOrNull(SemanticsActions.OnClick) == null) n = n.parent
        val row = n ?: throw AssertionError("\"$label\" is not a menu entry")
        return row.positionInWindow.y + row.size.height / 2f
    }

    /**
     * Area H: "tap the three-dots circle at the right end of the top row. Entries are grouped
     * under Clipboard, Bring in, Send out, View and This artwork." The headers take no tap; an
     * entry under View (Fit to screen) still acts.
     */
    private fun moreMenu() {
        editor()
        val fit = c.viewTransform.zoom
        Fingers18.pinch(s, 200f, 150f)
        assertTrue("two fingers zoomed in: ${c.viewTransform.zoom} from $fit", c.viewTransform.zoom > fit * 1.2f)
        MoreWalk.open(s)
        val w = SmokeUi.windows().last()
        val found = headers(w)
        assertEquals("the section headers, top to bottom", SECTIONS.map { it.first }, found.map { it.subtreeText().joinToString("") })
        for (n in found) {
            val name = n.subtreeText().joinToString("")
            assertEquals("\"$name\" is 24 dp tall", IbisDims.MoreMenuSection.value, n.size.height / s.density, 0.5f)
            assertTrue("\"$name\" is a heading", n.config.contains(SemanticsProperties.Heading))
            assertNull("\"$name\" takes no tap", n.config.getOrNull(SemanticsActions.OnClick))
        }
        val tops = found.map { it.positionInWindow.y }
        var last = Float.NEGATIVE_INFINITY
        SECTIONS.forEachIndexed { i, (name, labels) ->
            for (label in labels) {
                val y = entryY(w, label)
                assertTrue("\"$label\" is under \"$name\"", y > tops[i] && (i == tops.lastIndex || y < tops[i + 1]))
                assertTrue("\"$label\" keeps its order", y > last)
                last = y
            }
        }
        Finger.back()
        assertEquals("Back closed the menu", 1, SmokeUi.windows().size)
        MoreWalk.pick(s, "Fit to screen")
        assertEquals("View › Fit to screen fits the page again", fit, c.viewTransform.zoom, fit * 0.02f)
        Smoke.assertQuiet(c, "More menu")
    }

    /**
     * Area H: "when a tool's options don't fit one row, a chevron (Show more tool options) sits at
     * the end of the options strip. Tap it to drop a second row (up to 3 lines). [...] The More
     * row also closes when you touch the canvas (that first touch only closes it)." Area F: "Long-
     * press any icon or dropdown cell to see its name."
     */
    private fun stripMoreRow() {
        editor()
        tool("Shape")
        val tool = c.currentTool as ShapeTool
        ui.stroke(60f to 70f, 110f to 110f, 170f to 150f)
        assertNotNull("a pending rectangle", tool.box)
        press("Points")
        assertTrue("Points mode: the longest Shape strip", tool.pointsMode)
        StripTestKit.closeMoreRow()
        assertEquals(StripLabels18.COLLAPSED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
        val row1 = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no options strip")
        val toggle = s.tagged(V18Tags.STRIP_TOGGLE) ?: throw AssertionError("no chevron at the strip's end")
        assertEquals("the chevron is 44 dp", StripLayout.TOGGLE_DP, toggle.width, 0.5f)
        assertTrue("the chevron ends row 1: $toggle in $row1", row1.contains(toggle.center) && toggle.right <= s.widthDp + 0.5f)

        // The chevron's name by a long press; it opens nothing.
        var held: String? = null
        Fingers18.longPress(s, StripLabels18.MORE_TOGGLE) { held = Fingers18.bubble() }
        assertEquals("the chevron's name", StripLabels18.MORE_TOGGLE, held)
        assertNull("a long press opens no row", s.tagged(V18Tags.STRIP_MORE_ROW))

        // A tap drops the More row: at most three lines, right under row 1, all on screen; the pill under it.
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); settle()
        assertEquals(StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
        val (more, lines) = moreRowLines("Shape (Points)", row1)
        println("RequestCoverageV18 item 8 at $widthDp dp: the Shape (Points) More row has $lines line(s), ${more.height} dp")
        assertTrue("items on the More row", Clickables.onScreen(s, inside = more).isNotEmpty())
        val pill = s.tagged(ChromeTags.PILL_SLOT)?.takeIf { it.height > 0f } ?: throw AssertionError("an open shape shows the X / Y pill")
        assertTrue("the pill slid down under the More row: $pill", pill.top >= more.bottom - 0.5f)

        // The first canvas touch only closes the row: nothing drawn, moved or picked.
        val before = Fingers18.canvasState(s)
        val points = (0 until tool.pointCount).map { tool.pointAt(it) }
        val box = tool.box
        tap(320f, 230f)
        assertNull("the touch closed the More row", s.tagged(V18Tags.STRIP_MORE_ROW))
        assertEquals(StripLabels18.COLLAPSED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
        assertEquals("…and did nothing else", before, Fingers18.canvasState(s))
        assertTrue("still in Points mode", tool.pointsMode)
        assertEquals(box, tool.box)
        assertEquals(points, (0 until tool.pointCount).map { tool.pointAt(it) })
        tool.discard()
        settle()
        Smoke.assertQuiet(c, "the strip's More row")
    }

    private fun SemanticsNode.under(tag: String): Boolean =
        generateSequence(this) { it.parent }.any { it.config.getOrNull(SemanticsProperties.TestTag) == tag }

    /**
     * The open More row (dp) and its line count, checked as the user sees it (clarification 5:
     * "shows every option at once, wrapping onto a 2nd or 3rd line only when needed. Nothing
     * scrolls or hides."): 4 dp under [row1], whole lines tall (at most three), every line showing
     * something (an item or the hint, by the line its middle falls in), all of it wholly on
     * screen, and no line scrolled sideways.
     */
    private fun moreRowLines(what: String, row1: Rect): Pair<Rect, Int> {
        val more = s.tagged(V18Tags.STRIP_MORE_ROW) ?: throw AssertionError("$what: the chevron opens no More row")
        assertEquals("$what: 4 dp under row 1", row1.bottom + StripLayout.GAP_DP, more.top, 1f)
        val pitch = StripLayout.LINE_DP + StripLayout.GAP_DP
        val lines = ((more.height + StripLayout.GAP_DP) / pitch).roundToInt()
        assertTrue("$what: 1 to ${StripLayout.MAX_MORE_LINES} lines: $lines", lines in 1..StripLayout.MAX_MORE_LINES)
        assertEquals("$what: $lines lines tall", StripLayout.moreRowDp(lines), more.height, 1f)
        assertTrue("$what: on screen: $more", more.left >= -0.5f && more.right <= s.widthDp + 0.5f && more.bottom <= s.heightDp + 0.5f)
        val inRow = s.placed().filter { it.node.under(V18Tags.STRIP_MORE_ROW) }
        val shown = inRow.filter { e ->
            e.node.config.getOrNull(SemanticsActions.OnClick) != null || e.node.config.getOrNull(SemanticsProperties.Text) != null
        }.map { s.dp(it.bounds) }.filter { it.width > 0f && it.height > 0f }
        val used = shown.map { ((it.center.y - more.top) / pitch).toInt() }.toSortedSet().toList()
        assertEquals("$what: each of the $lines lines shows something", (0 until lines).toList(), used)
        for (b in shown) {
            assertTrue("$what: wholly on screen, inside the row: $b in $more", b.left >= -0.5f && b.right <= s.widthDp + 0.5f && b.top >= more.top - 0.5f && b.bottom <= more.bottom + 0.5f)
        }
        val sideways = inRow.filter { e -> (e.node.config.getOrNull(SemanticsProperties.HorizontalScrollAxisRange)?.maxValue?.invoke() ?: 0f) > 0f }
        assertTrue("$what: no line scrolls sideways (${sideways.size})", sideways.isEmpty())
        return more to lines
    }

    /**
     * Clarification 5 with a real wrap: the clone stamp's More row (its hint, "Show source" and the
     * brush's four controls) takes three lines at 360 dp and two at 392 dp (CloneStripTest's
     * layout), every option at once.
     */
    private fun cloneMoreRow() {
        editor()
        tool("Clone stamp")
        StripTestKit.closeMoreRow()
        val row1 = s.tagged(ChromeTags.OPTIONS_STRIP) ?: throw AssertionError("no options strip")
        Finger.tap(s, StripLabels18.MORE_TOGGLE)
        Smoke.pump(300); settle()
        assertEquals(StripLabels18.EXPANDED, Fingers18.state(s, StripLabels18.MORE_TOGGLE))
        val (more, lines) = moreRowLines("Clone stamp", row1)
        // What each item says (its own name or the text it shows: the hint is a text).
        val names = Clickables.onScreen(s, inside = more).flatMap { it.labels }
        println("RequestCoverageV18 item 8 at $widthDp dp: the Clone stamp More row has $lines line(s): $names")
        assertEquals("the More row wraps onto ${if (widthDp < 391.5f) "a 3rd" else "a 2nd"} line", if (widthDp < 391.5f) 3 else 2, lines)
        for (want in listOf(CloneTool.HINT, "Show source", "Choose brush", "Brush size", "Opacity")) {
            assertTrue("\"$want\" shows on the More row: $names", names.any { it.contains(want) })
        }
        StripTestKit.closeMoreRow()
        Smoke.assertQuiet(c, "the clone stamp's More row")
    }

    // ================================================================== 9. long presses, opaque menus

    /**
     * Area H: "What does this button do? Press and hold it. A bubble with its name appears for
     * 1.5 s and nothing else happens. This works on the top-row circles [...]."
     */
    private fun topRowLongPress() {
        editor()
        ui.stroke(80f to 150f, 200f to 160f, 320f to 150f)
        assertTrue("a stroke to undo", c.canUndo)
        val kept = ChromeLayout.topRow(s.widthDp).slots.map { TOP_LABELS.getValue(it) }
        assertTrue("Undo and More options stay on the top row: $kept", "Undo" in kept && "More options" in kept)
        for (label in kept) {
            val before = Fingers18.canvasState(s)
            val vector = c.isVectorMode
            var held: String? = null
            Fingers18.longPress(s, label) { held = Fingers18.bubble() }
            assertEquals("\"$label\": the bubble names it", label, held)
            assertEquals("\"$label\": the bubble stays after the finger lifts", label, Fingers18.bubble())
            assertEquals("\"$label\": no menu", 1, SmokeUi.windows().size)
            assertTrue("\"$label\": no sheet, no panel", SmokeUi.sheetTitles().isEmpty() && SmokeUi.pillTitles().isEmpty())
            assertEquals("\"$label\": nothing else happens", before, Fingers18.canvasState(s))
            assertEquals("\"$label\": vector mode untouched", vector, c.isVectorMode)
        }
        // A tap still acts.
        val n = steps()
        Finger.tap(s, "Undo")
        assertEquals("a tap on Undo undoes", n - 1, steps())
        // The bubble goes by itself after 1.5 s.
        Fingers18.holdRealTime(1800)
        settle()
        assertNull("the bubble went", Fingers18.bubble())
        Smoke.assertQuiet(c, "top row long presses")
    }

    private fun allViews(v: View): List<View> =
        if (v is ViewGroup) (0 until v.childCount).flatMap { allViews(v.getChildAt(it)) } + v else listOf(v)

    /** [v] drawn into a bitmap through software layers (as OpaqueMenusTest: a popup draws nothing with its hardware layer here). */
    private fun render(v: View): Bitmap {
        RobolectricUi.elements()
        val views = allViews(v) - v
        views.forEach { it.setLayerType(View.LAYER_TYPE_SOFTWARE, null); it.invalidate() }
        v.invalidate()
        Smoke.pump(300); settle()
        val b = Bitmap.createBitmap(v.width, v.height, Bitmap.Config.ARGB_8888)
        v.draw(Canvas(b))
        views.forEach { it.setLayerType(View.LAYER_TYPE_NONE, null) }
        return b
    }

    /** D27: the More menu's card is #1E1E1E with full alpha, over the white page. */
    private fun opaqueMoreMenu() {
        editor()
        // The page under the chrome: the view at 3 ×.
        c.viewTransform.set(android.graphics.Matrix().apply { setScale(3f, 3f) })
        Smoke.pump(200); settle()
        MoreWalk.open(s)
        val popup = SmokeUi.windows().last()
        val shot = render(popup)
        val menu = IbisColors.Menu.toArgb()
        val d = s.density
        var sampled = 0
        for (e in RobolectricUi.elements().filter { it.window === popup && it.node.layoutInfo.isPlaced }) {
            val tag = e.node.config.getOrNull(SemanticsProperties.TestTag) ?: continue
            if (!tag.startsWith(V18Tags.MENU_SECTION_PREFIX)) continue
            val r = e.bounds
            if (r.bottom > shot.height || r.top < 0f || r.height < 20f * d) continue
            for (x in listOf(r.right - 12f * d, r.right - 40f * d, r.center.x + 30f * d)) {
                val p = shot.getPixel(x.toInt(), r.center.y.toInt())
                val where = "${tag.removePrefix(V18Tags.MENU_SECTION_PREFIX)} at x ${x.toInt()} px: #%08X".format(p)
                assertEquals("opaque: $where", 255, p ushr 24)
                for (shift in listOf(16, 8, 0)) assertEquals("#1E1E1E: $where", ((menu shr shift) and 0xFF).toFloat(), ((p shr shift) and 0xFF).toFloat(), 1f)
                sampled++
            }
        }
        assertTrue("the card was sampled ($sampled px)", sampled >= 6)
        Finger.back()
        assertEquals(1, SmokeUi.windows().size)
        Smoke.assertQuiet(c, "opaque More menu")
    }

    // ================================================================== 2. an array's look

    /** Runs [block] and asserts it recorded ONE step "Array look". */
    private fun lookStep(what: String, block: () -> Unit) {
        val n = steps()
        block()
        settleRenders(what)
        assertEquals("$what: one step", n + 1, steps())
        assertEquals(what, ArrayLabels18.HISTORY_LOOK, c.undoManager.undoLabel)
    }

    /** The runs [x0, x1) of pixels with any alpha on row [y] of [layer]. */
    private fun runs(layer: Layer, y: Int): List<IntRange> {
        val out = ArrayList<IntRange>()
        var start = -1
        for (x in 0..layer.bitmap.width) {
            val inked = x < layer.bitmap.width && layer.bitmap.getPixel(x, y) ushr 24 > 0
            if (inked && start < 0) start = x
            if (!inked && start >= 0) { out += start until x; start = -1 }
        }
        return out
    }

    /** Pixels of [layer] that are the seed's red at about 40 % opacity (the last copy, faded). */
    private fun faintRed(layer: Layer): Int = pixels(layer).count { p ->
        val a = p ushr 24
        a in 92..112 && (p shr 16 and 0xFF) > 160 && (p shr 8 and 0xFF) < 90
    }

    /**
     * Area B: "With the Array tool, tap the chip in the options strip [...] It opens the Array
     * sheet. At the top of the sheet, switch from Placement to Look. [...] Set Start opacity / End
     * opacity, Start size / End size [...]. Under Tint colors, pick a preset [...]. Lower Tint
     * strength to blend the tint with the copies' own colors. [...] Each change is one undo step
     * named Array look. A two-finger tap undoes it."
     */
    private fun arrayLook() {
        val u = Qa17ArrayUi(h)
        s = u.editor(widthDp = widthDp)
        ui = u.ui
        seed(c.activeLayer, 40f, 40f, 80f, 80f, RED)
        c.setSelection(rectSelection(36f, 36f, 84f, 84f), recordUndo = false)
        settle(4)
        // The selection bar's Array: the selected pixels move into a new layer "Array N" above, the array's source.
        press(ArrayLabels.FROM_SELECTION)
        settleRenders("the array")
        c.setSelection(null, recordUndo = false)
        settle(4)
        val layer = c.activeLayer
        assertNotNull("a live array on the new layer \"${layer.name}\"", layer.array)
        assertEquals("the source and two copies", 3, layer.array!!.spec.count)
        assertEquals("three red squares", 3 * 1600, count(layer, RED))
        // A line array: the copies follow the source edge to edge (one run of three 40 px squares).
        val row = runs(layer, 60)
        assertEquals("three squares edge to edge: $row", listOf(40 until 160), row)
        val first = Vec2(100f, 60f)
        fun at(p: Vec2) = layer.bitmap.getPixel(p.x.roundToInt(), p.y.roundToInt())

        u.showArraySheet()
        u.press(ArrayLabels18.LOOK, 44f)
        assertNotNull("the Look tab", s.tagged(V18Tags.ARRAY_LOOK))
        assertTrue(has(ArrayLabels18.LOOK_CAPTION, exact = true))

        // Opacity: the last copy fades to 40 %; the source and the first copy stay red.
        lookStep(ArrayLabels18.END_OPACITY) { u.typeField(ArrayLabels18.END_OPACITY, "40") }
        assertEquals(0.4f, layer.array!!.spec.look!!.opacityEnd, 1e-4f)
        assertEquals("the source and the first copy keep their red", 2 * 1600, count(layer, RED))
        val faint = faintRed(layer)
        assertTrue("the last copy at 40 %: $faint faint red px", faint in 1500..1700)

        // Size: the last copy at half size.
        lookStep(ArrayLabels18.END_SIZE) { u.typeField(ArrayLabels18.END_SIZE, "50") }
        assertEquals(0.5f, layer.array!!.spec.look!!.sizeEnd, 1e-4f)
        val smaller = faintRed(layer)
        println("RequestCoverageV18 item 2 at $widthDp dp: the last copy at 40 % takes $faint px, at half size $smaller px")
        // Half the size is a quarter of the pixels (its soft edge aside).
        assertTrue("the last copy at half size: $smaller faint px, $faint before (a quarter)", smaller >= faint * 0.18f && smaller <= faint * 0.3f)
        assertEquals(2 * 1600, count(layer, RED))

        // Tint: a preset colors the copies (strength 100 % at once); the source keeps its look.
        lookStep(SUNSET) { u.press(SUNSET) }
        val sunset = GradientEditing.presets.first { it.first == SUNSET }.second
        assertEquals("the preset's stops", sunset.map { it.position to it.color }, layer.array!!.spec.look!!.tint.map { it.offset to it.color })
        assertEquals("shown at 100 %", 1f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        assertEquals("only the source stays red", 1600, count(layer, RED))
        val full = at(first)
        assertNotEquals("the first copy is tinted", RED, full)
        lookStep(ArrayLabels18.TINT_STRENGTH) { u.typeField(ArrayLabels18.TINT_STRENGTH, "60") }
        assertEquals(0.6f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        val blend = at(first)
        fun dist(a: Int, b: Int) = listOf(16, 8, 0).sumOf { abs(((a shr it) and 0xFF) - ((b shr it) and 0xFF)) }
        assertTrue("60 %%: the first copy between red and the tint (#%08X, tint #%08X)".format(blend, full), dist(blend, RED) < dist(full, RED) && blend != full)

        // A two-finger tap undoes the last change.
        u.ui.twoFingerUndo()
        settleRenders("undo")
        assertEquals("one undo: 100 % again", 1f, layer.array!!.spec.look!!.tintStrength, 1e-4f)
        assertEquals(full, at(first))
        Smoke.assertQuiet(c, "the array's look")
    }

    // ================================================================== 5. fringe colors

    /** Opens [f] from the Filters tool: its search, then its name. */
    private fun openFilter(f: Filter, search: String): FilterSession {
        tool("Filters")
        SmokeUi.assertPanelShown("Filters")
        SmokeUi.field("Search filters").let { it.focus(); settle(2); it.type(search) }
        settle(4)
        click(f.name, exact = true)
        val session = c.filterSession ?: throw AssertionError("no session for ${f.name} (${c.message})")
        assertEquals(f.id, session.filter.id)
        Fx.awaitPreview(session, f.name)
        settle()
        return session
    }

    /** In the open color picker: the hex typed, then OK. */
    private fun pickHex(hex: String) {
        assertTrue("the color picker is open; ${SmokeUi.windows().size} windows, shown: ${SmokeUi.shown().take(80)}", has("OK", exact = true))
        SmokeUi.field("Hex").type(hex)
        settle(2)
        click("OK", exact = true)
        settle()
        assertFalse("the color picker closed", has("OK", exact = true))
    }

    /**
     * The clickable on the row of the text [label], right of it (a color swatch), where it is laid
     * out (scrolled out of the panel's view or not: [visible] then brings it in).
     */
    private fun clickableRightOf(label: String): RobolectricUi.Element {
        val t = byText(label)
        val right = t.node.positionInWindow.x + t.node.size.width
        val middle = t.node.positionInWindow.y + t.node.size.height / 2f
        fun dy(e: RobolectricUi.Element) = abs(e.node.positionInWindow.y + e.node.size.height / 2f - middle)
        return RobolectricUi.elements().filter { e ->
            e.window === t.window && e.node.layoutInfo.isPlaced && e.node.config.getOrNull(SemanticsActions.OnClick) != null &&
                e.node.positionInWindow.x >= right && dy(e) < 24f * s.density
        }.minByOrNull { dy(it) } ?: throw AssertionError("nothing clickable right of \"$label\"")
    }

    /** What [f] gives on [src] for [values], read back through a bitmap (as the apply writes it). */
    private fun direct(f: Filter, src: Bitmap, values: FilterValues): IntArray =
        Fx.roundTrip(Fx.direct(f, BitmapUtils.toPixelBuffer(src), values, 1f, c.doc.dpi))

    private fun applyFilter(f: Filter) {
        val before = steps()
        click("Apply filter", exact = true)
        assertTrue("${f.name}: applied", Smoke.pumpUntil(WAIT_MS * 2) { settle(1); c.filterSession == null && c.busyMessage == null })
        settle()
        assertEquals("${f.name}: one step", before + 1, steps())
        assertEquals(f.name, c.undoManager.undoLabel)
    }

    /**
     * Area A: "Chromatic aberration (Filters > Art > ...): under Fringe colors, tap a preset chip
     * [...]. Tap a swatch [...] to open the color picker [...]. Pick a color and tap OK." "Glitch:
     * the same Fringe colors control colors the channel split. Color of the static (a swatch,
     * white by default) tints the static bands." Each apply is the filter's own result.
     */
    private fun fringeColors() {
        editor()
        val layer = c.activeLayer
        assertTrue(c.editWholeLayer(layer, "Seed") { bmp -> bmp.setPixels(Fx.paintPixels(400, 300), 0, 400, 0, 0, 400, 300) })
        settle()

        val moving = FilterRegistry.byId("art.chromatic_aberration_moving") ?: throw AssertionError("Chromatic aberration (Moving) is registered")
        var session = openFilter(moving, "chromatic")
        assertTrue("the Fringe colors control", has(FringeLabels18.FRINGE_COLORS, exact = true))
        assertArrayEquals("RGB at first", FringeColors.rgb, session.values.colors(FringeColors.KEY))
        press(FringeLabels18.BLUE_YELLOW)
        assertArrayEquals(intArrayOf(FringeColors.BLUE, FringeColors.NONE, FringeColors.YELLOW), session.values.colors(FringeColors.KEY))
        // The middle swatch (no color) opens the color picker; a typed orange is picked.
        val swatch = visible { byDescription(FringeLabels18.MIDDLE) }
        swatch.tap()
        settle()
        pickHex("FF8000")
        val custom = intArrayOf(FringeColors.BLUE, 0xFFFF8000.toInt(), FringeColors.YELLOW)
        assertArrayEquals("the middle fringe picked", custom, session.values.colors(FringeColors.KEY))
        Fx.awaitPreview(session, "moving, custom colors")
        val src = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val values = session.values.copy()
        val expected = direct(moving, src, values)
        assertFalse("the colors reach the result", expected.contentEquals(direct(moving, src, values.copy().set(FringeColors.KEY, FringeColors.rgb))))
        applyFilter(moving)
        assertArrayEquals("the layer is Moving's result with the picked fringes", expected, pixels(layer))

        val glitch = FilterRegistry.byId("art.glitch") ?: throw AssertionError("Glitch is registered")
        session = openFilter(glitch, "glitch")
        // Small bands, a fixed seed and full static (setup, as the area test): every kind of band shows.
        session.update("height", 6f); session.update("noise", 100f); session.update("seed", 4242)
        Fx.awaitPreview(session, "glitch")
        settle()
        press(FringeLabels18.MAGENTA_GREEN)
        assertArrayEquals(intArrayOf(FringeColors.MAGENTA, FringeColors.NONE, FringeColors.GREEN), session.values.colors(FringeColors.KEY))
        assertEquals("the static is white at first", FringeColors.WHITE, session.values.color(GlitchFilter.STATIC_COLOR_KEY))
        // The static's swatch (right of its name) scrolled wholly into view, then a finger on it.
        val staticSwatch = visible { clickableRightOf(FringeLabels18.STATIC_COLOR) }
        staticSwatch.tap()
        settle()
        pickHex("FF2040")
        assertEquals("the static's color picked", 0xFFFF2040.toInt(), session.values.color(GlitchFilter.STATIC_COLOR_KEY))
        Fx.awaitPreview(session, "glitch, colors")
        val src2 = layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
        val gv = session.values.copy()
        val expectedGlitch = direct(glitch, src2, gv)
        assertFalse("the fringes reach the result", expectedGlitch.contentEquals(direct(glitch, src2, gv.copy().set(FringeColors.KEY, FringeColors.rgb))))
        assertFalse("the static's color reaches the result", expectedGlitch.contentEquals(direct(glitch, src2, gv.copy().set(GlitchFilter.STATIC_COLOR_KEY, FringeColors.WHITE))))
        applyFilter(glitch)
        assertArrayEquals("the layer is Glitch's result with its colors", expectedGlitch, pixels(layer))
        Smoke.assertQuiet(c, "fringe colors")
    }

    // ================================================================== 6. curve generators

    /** The slider under the text [label] (the next placed node after it that sets a progress). */
    private fun sliderUnder(label: String): RobolectricUi.Element {
        val all = RobolectricUi.elements().filter { it.node.layoutInfo.isPlaced }
        val at = all.indexOfLast { e -> e.node.config.getOrNull(SemanticsProperties.Text)?.any { it.text == label } == true }
        assertTrue("\"$label\" shows; shown: ${SmokeUi.shown().take(60)}", at >= 0)
        return all.drop(at + 1).firstOrNull { it.node.config.getOrNull(SemanticsActions.SetProgress) != null }
            ?: throw AssertionError("no slider under \"$label\"")
    }

    /**
     * Area E: "open the Curve or Path tool with nothing drawn and tap Curves (first item on row
     * 1). Pick Archimedean spiral [...]. Change it: tap the chip that shows the curve's name. [...]
     * The sheet's sliders depend on the kind: Turns, [...]." A stored spiral keeps its settings: a
     * tap on its line reopens it as a spiral, still changeable in the sheet.
     */
    private fun generatedSpiral() {
        editor(vectorDoc())
        c.brush = c.brush.copy(size = 6f, opacity = 1f)
        tool("Curve")
        val tool = c.currentTool as CurveTool
        assertEquals(0, tool.pointCount)
        click(CurveGenLabels18.CURVES_DESC, exact = true)
        click(CurveGenLabels18.ARCHIMEDEAN, exact = true)
        val g = tool.generator ?: throw AssertionError("no spiral")
        assertEquals(CurveGenKind.ARCHIMEDEAN, g.kind)
        assertTrue("an open curve", tool.pointCount > 2)
        val before = steps()
        applyEdit("Apply curve edit")
        assertEquals("one step", before + 1, steps())
        val layer = c.activeLayer
        val stored = layer.vector!!.objects.single() as VPath
        assertEquals("the spiral is stored with its settings", g, stored.generator)
        val drawn = pixels(layer)
        assertTrue("the spiral is drawn", ink(layer, 0, 0, 400, 300) > 200)

        // A tap on its line reopens it; the chip opens its sheet; one drag on Turns.
        tap(onLine(stored))
        assertTrue("reopened", tool.isReopened)
        assertEquals("still the spiral", g, tool.generator)
        click(CurveGenLabels18.CHANGE, exact = true)
        assertTrue("the sheet: ${SmokeUi.shown().take(60)}", has(CurveGenLabels18.TURNS, exact = true))
        val turns = sliderUnder(CurveGenLabels18.TURNS)
        val b = turns.bounds
        val y = b.center.y
        RobolectricUi.drag(turns.window, b.left + b.width * 0.1f to y, b.left + b.width * 0.5f to y, b.left + b.width * 0.85f to y)
        settle()
        val g2 = tool.generator ?: throw AssertionError("the sheet lost the spiral")
        assertNotEquals("the drag changed the turns", g.turns, g2.turns, 0.001f)
        assertEquals("only the turns", g.copy(turns = g2.turns), g2)
        Finger.back()
        StripTestKit.closeMoreRow()
        applyEdit("Apply curve edit")
        val after = layer.vector!!.objects.single() as VPath
        assertEquals("the same object", stored.id, after.id)
        assertEquals("stored with the new turns", g2, after.generator)
        assertFalse("the spiral is redrawn", pixels(layer).contentEquals(drawn))
        Smoke.assertQuiet(c, "a generated spiral")
    }

    // ================================================================== 7. the cog

    /** Teeth of a filled cog in [box] (depth [depth]) on [layer]: inked runs round the circle half way down the teeth. */
    private fun teeth(layer: Layer, box: ShapeBox, depth: Float): Int {
        val f = 1f - depth / 2f
        val n = 720
        val inked = BooleanArray(n) { i ->
            val a = 2.0 * PI * i / n
            val x = (box.cx + box.w / 2f * f * cos(a)).roundToInt().coerceIn(0, layer.bitmap.width - 1)
            val y = (box.cy + box.h / 2f * f * sin(a)).roundToInt().coerceIn(0, layer.bitmap.height - 1)
            layer.bitmap.getPixel(x, y) ushr 24 >= 128
        }
        return (0 until n).count { i -> inked[i] && !inked[(i + n - 1) % n] }
    }

    /**
     * Area F: "tap Shape type, pick Cog (last in the list) and drag out a cog. To change it, open
     * Show more tool options, then Shape settings. The COG section has Number of teeth (slider or
     * typed) [...]." The ✓ places the cog in a new layer of its own (one step "Shape"), and that
     * layer shows nine teeth.
     */
    private fun cog() {
        editor()
        tool("Shape")
        val tool = c.currentTool as ShapeTool
        click("Shape type", exact = true)
        click(ShapeType.COG.label, exact = true)
        assertEquals(ShapeType.COG, tool.settings.type)
        click("Shape settings", exact = true)
        assertTrue("the COG section: ${SmokeUi.shown().take(60)}", has(CogLabels18.TEETH, exact = true))
        SmokeUi.typeAndDone(CogLabels18.TEETH, "9")
        assertEquals(9, tool.settings.cogTeeth)
        Finger.back()
        assertFalse("the sheet closed", has(CogLabels18.TEETH, exact = true))
        StripTestKit.closeMoreRow()
        click("Shape style", exact = true)
        click("Fill", exact = true)
        assertEquals(ShapeStyle.FILL, tool.settings.style)
        StripTestKit.closeMoreRow()
        ui.stroke(100f to 50f, 200f to 150f, 300f to 250f)
        val box = tool.box ?: throw AssertionError("no pending cog")
        assertTrue("a cog of some size: $box", box.w > 100f && box.h > 100f)
        val before = steps()
        val layersBefore = c.doc.layers.toList()
        applyEdit("Apply shape edit")
        // An editable shape (on by default) goes into a new layer of its own: ONE step "Shape".
        assertEquals("one step", before + 1, steps())
        assertEquals("Shape", c.undoManager.undoLabel)
        assertEquals("one new layer", layersBefore.size + 1, c.doc.layers.size)
        val layer = c.doc.layers.single { it !in layersBefore }
        assertTrue("the new layer is named after the cog: \"${layer.name}\"", layer.name.startsWith(ShapeType.COG.label))
        assertNotNull("the new layer keeps the cog to edit it again", layer.shapeData)
        assertTrue("the earlier layers stay blank but the page", layersBefore.drop(1).all { ink(it, 0, 0, 400, 300) == 0 })
        val n = teeth(layer, box, tool.settings.cogDepth)
        println("RequestCoverageV18 item 7 at $widthDp dp: $n teeth on \"${layer.name}\" in $box")
        assertEquals("nine teeth drawn", 9, n)
        Smoke.assertQuiet(c, "the cog")
    }
}

// ====================================================================== the seven classes

/** Item 1 (mirrors for Curve, Polyline, Path, Shape and the clone stamp; Select several in the pill) on the user's phone (392 dp). */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18mirrorssandbox"])
class RequestCoverageV18UiTest {
    @Test
    fun mirrorsKeepTheirPromiseAt392dp() = RequestCoverageV18.run(RequestCoverageV18.Group.MIRRORS, 392f)
}

/** Item 1 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18mirrors360sandbox"])
class RequestCoverageV18Mirrors360UiTest {
    @Test
    fun mirrorsKeepTheirPromiseAt360dp() = RequestCoverageV18.run(RequestCoverageV18.Group.MIRRORS, 360f)
}

/** Items 3 and 4 (the magnetic lasso, color select) at 392 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18selectsandbox"])
class RequestCoverageV18SelectUiTest {
    @Test
    fun selectionToolsKeepTheirPromiseAt392dp() = RequestCoverageV18.run(RequestCoverageV18.Group.SELECT, 392f)
}

/** Items 3 and 4 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18select360sandbox"])
class RequestCoverageV18Select360UiTest {
    @Test
    fun selectionToolsKeepTheirPromiseAt360dp() = RequestCoverageV18.run(RequestCoverageV18.Group.SELECT, 360f)
}

/** Items 8 and 9 (the top menus, the More row, long presses, opaque menus) at 392 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18chromesandbox"])
class RequestCoverageV18ChromeUiTest {
    @Test
    fun chromeKeepsItsPromiseAt392dp() = RequestCoverageV18.run(RequestCoverageV18.Group.CHROME, 392f)
}

/** Items 8 and 9 at 360 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18chrome360sandbox"])
class RequestCoverageV18Chrome360UiTest {
    @Test
    fun chromeKeepsItsPromiseAt360dp() = RequestCoverageV18.run(RequestCoverageV18.Group.CHROME, 360f)
}

/** Items 2, 5, 6 and 7 (an array's look, fringe colors, curve generators, the cog) at 392 dp. */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.audit.requestv18lookssandbox"])
class RequestCoverageV18LooksUiTest {
    @Test
    fun looksKeepTheirPromiseAt392dp() = RequestCoverageV18.run(RequestCoverageV18.Group.LOOKS, 392f)
}
