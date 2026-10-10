package com.brushwork.paint.qa17

import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Paint
import android.view.MotionEvent
import androidx.compose.runtime.snapshots.Snapshot
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.qa16.Qa16Ui
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.ByteArrayOutputStream
import java.io.File
import kotlin.math.roundToInt

/**
 * v1.7 final QA, layers cluster (item 8, design §3.8; device checklist §6.4 row 1): layer folders
 * the way the user meets them on the phone, in the full editor, by fingers and the labels shown.
 *
 * The picture: a blue-grey backdrop (Layer 1), an orange block (Layer 2), a green block (Layer 3)
 * overlapping it on the right, and an empty Layer 4 on top. Points: P1 in orange only, P2 in
 * orange and green, P3 in green only, P4 in the backdrop only.
 *
 * 1. The tree by fingers: Layer 2 ⋮ "Put in new folder" (nothing changes on screen), Layer 4
 *    dragged by its ≡ onto the open folder (its top child), Layer 2 swiped out and back in, the
 *    folder closed and opened by its thumbnail; "Choose blend mode" › Multiply and "Type layer
 *    opacity" 60: exactly 0.6·(A·B) + 0.4·B at P1; "New adjustment layer (Tone)" with Layer 2
 *    active lands inside the folder, "Invert Color" inverts the folder's content only; "Clipping"
 *    on Layer 3 clips it to the folder.
 * 2. With the folder active: Brush, Bucket and a filter are refused with the caption on screen and
 *    leave no step; the eyedropper ("Sample: Layer") picks the composite; a new shape lands in
 *    the folder as its top child.
 * 3. The folder locked: painting its child is refused with "Folder “Folder 1” is locked"; hidden:
 *    its picture goes and Pathfinder picks none of its shapes ("Select all objects").
 * 4. "Layer from folder" (⋮) and "Merge folder" (strip): an isolated folder merges at once, with
 *    the picture unchanged; a pass-through folder over a Multiply child asks first ("Blending with
 *    layers below the folder will change"): Cancel leaves it, Merge merges.
 * 5. Save, back to the gallery (its list), reopen (the window shows the folder), the gallery's PNG
 *    export and the SVG export of the same tree.
 */
internal class Qa17LayersFolders(private val h: ChromeHarness, private val widthDp: Float) {
    private lateinit var s: ChromeScreen
    private lateinit var ui: Qa16Ui
    private val c: EditorController get() = s.c

    // ================================================================== the picture

    private fun document(): Document = Smoke.document(W, H, layers = 4, whiteBottom = false).also { d ->
        fill(d.layers[0], B, 0, 0, W, H)
        fill(d.layers[1], A, 40, 40, 200, 150)
        fill(d.layers[2], D, 150, 20, 240, 170)
    }

    private fun fill(l: Layer, color: Int, left: Int, top: Int, right: Int, bottom: Int) {
        Canvas(l.bitmap).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Paint().apply { this.color = color })
        l.markChanged()
    }

    private fun editor(setup: (EditorController) -> Unit = {}): ChromeScreen {
        s = h.editor(document()) { it.snapping.enabled = false; setup(it) }
        ui = Qa16Ui(s)
        captions?.dispose()
        captions = Captions(s.c)
        settle()
        assertEquals("the phone is ${widthDp.roundToInt()} dp wide", widthDp, s.widthDp, 1f)
        return s
    }

    /**
     * The tree section 1 makes, made by the controller for the later sections:
     * [Layer 1, Layer 2 ‹F›, Invert Color 1 ‹F›, Layer 4 ‹F›, Folder 1 (Multiply 60 %), Layer 3 (clipped)].
     */
    private fun tree(c: EditorController): Layer {
        val (l1, l2, l3, l4) = c.doc.layers
        val f = c.putInNewFolder(l2)!!
        c.selectLayer(f)
        // The active open folder: a new layer goes in as its top child; Layer 4's content (none) moves there.
        val top = c.addLayer()!!
        top.name = l4.name
        c.deleteLayer(l4)
        c.selectLayer(l2)
        c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(FilterRegistry.byId(INVERT)!!), null)!!
        c.setFolderPassThrough(f, false)
        f.blendMode = LayerBlendMode.MULTIPLY
        f.opacity = 0.6f
        c.toggleClipping(l3)
        c.selectLayer(f)
        c.notifyLayersChanged()
        assertEquals(listOf("Layer 1", "Layer 2", "Invert Color 1", "Layer 4", "Folder 1", "Layer 3"), c.doc.layers.map { it.name })
        assertEquals(listOf(0L, f.id, f.id, f.id, 0L, 0L), c.doc.layers.map { it.parentId })
        assertSame(l1, c.doc.layers[0])
        return f
    }

    // ================================================================== fingers and checks

    private fun steps(): Int = c.undoManager.undoCount

    /** [block] adds exactly one undo step named [label]. */
    private fun oneStep(what: String, label: String, block: () -> Unit) {
        val before = steps()
        block()
        settle()
        assertEquals("$what: one undo step", before + 1, steps())
        assertEquals("$what: the step's name", label, c.undoManager.undoLabel)
    }

    /** [block] adds no step and leaves the picture as it was. */
    private fun noStep(what: String, block: () -> Unit) {
        val before = steps()
        val picture = pixels()
        block()
        settle()
        assertEquals("$what: no step", before, steps())
        assertArrayEquals("$what: the picture is unchanged", picture, pixels())
    }

    private fun pixels(): IntArray = Qa17LayersShots.flat(c).let { b ->
        try { IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) } } finally { b.recycle() }
    }

    private fun at(p: Pair<Int, Int>): Int = Qa17LayersShots.pixel(c, p.first, p.second)

    private fun expectAt(what: String, p: Pair<Int, Int>, want: Triple<Double, Double, Double>, tol: Int = 2) {
        val got = at(p)
        val r = want.first.roundToInt(); val g = want.second.roundToInt(); val b = want.third.roundToInt()
        assertTrue("$what at $p: ${Qa17LayersShots.rgb(got)}, want ($r, $g, $b) ± $tol", Qa17LayersShots.near(got, r, g, b, tol))
    }

    private fun expectAt(what: String, p: Pair<Int, Int>, want: Int, tol: Int = 2) {
        val got = at(p)
        assertTrue("$what at $p: ${Qa17LayersShots.rgb(got)}, want ${Qa17LayersShots.rgb(want)} ± $tol", Qa17LayersShots.near(got, want, tol))
    }

    private fun row(l: Layer): String = LayerLabels.selectRow(c.doc.indexOf(l) + 1)

    private fun openLayers() {
        if (s.tagged(ChromeTags.LAYER_WINDOW) != null) return
        click("Open layers (active layer")
        Smoke.pump(600)
        settle()
        assertNotNull("the layer window is open", s.tagged(ChromeTags.LAYER_WINDOW))
    }

    private fun closeLayers() {
        if (s.tagged(ChromeTags.LAYER_WINDOW) == null) return
        click(LayerLabels.CLOSE, exact = true)
        Smoke.pump(400)
        settle()
    }

    /** Selects [l]'s row with a tap (the list first scrolled to it, as a finger would). */
    private fun pick(l: Layer) {
        openLayers()
        Smoke.pump(600)
        reachRow(l)
        click(row(l), exact = true)
        Smoke.pump(600)
        settle()
        assertSame("${l.name} is active", l, c.activeLayer)
    }

    /** A finger on [layer]'s ≡ handle: [dx] dp across (a swipe) or [dy] dp down (a drag), in 8 dp moves. */
    private fun handle(layer: Layer, dx: Float = 0f, dy: Float = 0f) {
        // The rows' placement animations end first (the list finds the row under the finger by its
        // layout): the handle stays put over two settles.
        reachRow(layer)
        val label = LayerLabels.reorder(c.doc.indexOf(layer) + 1)
        var e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"")
        for (i in 0 until 20) {
            Smoke.pump(100)
            settle()
            val now = SmokeUi.find(label, exact = true)!!
            if (now.bounds == e.bounds) break
            e = now
        }
        val hb = e.bounds
        val touch = Smoke.Touch(e.window)
        val n = (maxOf(kotlin.math.abs(dx), kotlin.math.abs(dy)) / 8f).toInt()
        val sx = kotlin.math.sign(dx) * 8f * s.density
        val sy = kotlin.math.sign(dy) * 8f * s.density
        touch.send(MotionEvent.ACTION_DOWN, Smoke.P(0, hb.center.x, hb.center.y))
        for (i in 1..n) {
            touch.idle(16)
            touch.send(MotionEvent.ACTION_MOVE, Smoke.P(0, hb.center.x + i * sx, hb.center.y + i * sy))
        }
        touch.idle(16)
        touch.send(MotionEvent.ACTION_UP, Smoke.P(0, hb.center.x + n * sx, hb.center.y + n * sy))
        settle()
        Smoke.pump(600)
        settle()
    }

    /**
     * [block] is refused: no step, the picture unchanged, and exactly the caption [text] handed to
     * the snackbar (once), which shows it.
     */
    private fun refused(what: String, text: String, block: () -> Unit) {
        val log = captions!!
        log.take()
        noStep(what, block)
        settle()
        assertEquals("$what: the caption", listOf(text), log.take())
        assertTrue("$what: \"$text\" is shown; shown: ${SmokeUi.shown().take(60)}", has(text, exact = true))
    }

    private var captions: Captions? = null

    /** Stops recording captions (the end of the run). */
    fun release() {
        captions?.dispose()
        captions = null
    }

    /**
     * Every caption the editor hands to its snackbar, in order: the editor clears
     * [EditorController.message] as soon as its snackbar takes it, so it is read when the change
     * is applied (before the screen recomposes), each toast once.
     */
    private class Captions(private val c: EditorController) {
        private val seen = mutableListOf<String>()
        private var last: String? = null
        private val handle = Snapshot.registerApplyObserver { _, _ ->
            val m = c.message
            if (m != null && m != last) seen += m
            last = m
        }

        fun take(): List<String> = seen.toList().also { seen.clear() }

        fun dispose() = handle.dispose()
    }

    /**
     * Brings [l]'s row into the layer list as a finger does: the list scrolled toward it (60 dp
     * at a time) until the row is there, then wholly into view.
     */
    private fun reachRow(l: Layer) {
        openLayers()
        val label = row(l)
        val n = c.doc.indexOf(l) + 1
        repeat(40) {
            settle(2)
            if (SmokeUi.find(label, exact = true) != null) {
                ui.reach(label, 40f)
                return
            }
            val shown = SmokeUi.shown().mapNotNull { ROW.find(it)?.groupValues?.get(1)?.toInt() }
            if (shown.isEmpty()) throw AssertionError("no layer rows; shown: ${SmokeUi.shown().take(60)}")
            var p: SemanticsNode? = SmokeUi.find(LayerLabels.selectRow(shown.first()), exact = true)!!.node
            while (p != null && p.config.getOrNull(SemanticsActions.ScrollBy) == null) p = p.parent
            val scroll = requireNotNull(p?.config?.getOrNull(SemanticsActions.ScrollBy)?.action) { "the layer list does not scroll" }
            // The top row is the highest layer: lower layers are further down.
            scroll.invoke(0f, (if (n < shown.min()) 60f else -60f) * s.density)
        }
        throw AssertionError("\"$label\" never came into the layer list")
    }

    private fun parentNames(): List<String> = c.doc.layers.map { l -> "${l.name}<${c.doc.layerById(l.parentId)?.name ?: "root"}>" }

    // ================================================================== 1. the tree by fingers

    fun treeByFingers() {
        editor()
        val (l1, l2, l3, l4) = c.doc.layers
        val start = Qa17LayersShots.shoot(c, "folders-1-start")
        openLayers()

        // Layer 2 ⋮ "Put in new folder": a folder right above it, holding it; nothing changes on screen.
        pick(l2)
        click(LayerLabels.MORE, exact = true)
        oneStep("Put in new folder", HistoryLabels.PUT_IN_NEW_FOLDER) { click(FolderLabels.PUT_IN_NEW, exact = true) }
        val f = c.doc.layers.single { it.isFolder }
        assertEquals("Folder 1", f.name)
        assertEquals(listOf(l1, l2, f, l3, l4), c.doc.layers)
        assertEquals(f.id, l2.parentId)
        assertTrue("pass-through by default", f.folder!!.passThrough)
        assertEquals(1f, f.opacity, 0f)
        assertArrayEquals("\"Put in new folder\" changes nothing on screen", start, pixels())

        // Layer 4 dragged by its ≡ down onto the open folder's row: the folder's top child.
        oneStep("Layer 4 dragged onto the folder", HistoryLabels.MOVE_LAYER) { handle(l4, dy = 168f) }
        assertEquals(listOf("Layer 1<root>", "Layer 2<Folder 1>", "Layer 4<Folder 1>", "Folder 1<root>", "Layer 3<root>"), parentNames())

        // Swipe left on the folder's bottom child: out (below its block); swipe right: back in.
        oneStep("Swipe left", HistoryLabels.MOVE_OUT_OF_FOLDER) { handle(l2, dx = -48f) }
        assertEquals(Layer.ROOT_ID, l2.parentId)
        oneStep("Swipe right", HistoryLabels.MOVE_INTO_FOLDER) { handle(l2, dx = 48f) }
        assertEquals(f.id, l2.parentId)
        assertEquals(listOf(l1, l2, l4, f, l3), c.doc.layers)

        // The thumbnail closes and opens it: the rows go and come back, no step.
        val beforeToggle = steps()
        Smoke.pump(600)
        Finger.tap(s, FolderLabels.close(f.name))
        assertTrue(has(FolderLabels.open(f.name), exact = true))
        assertFalse("a closed folder's rows are not listed", has(row(l2), exact = true))
        Finger.tap(s, FolderLabels.open(f.name))
        assertTrue(has(row(l2), exact = true))
        assertEquals("no step", beforeToggle, steps())
        assertArrayEquals(start, pixels())

        // Isolated Multiply at 60 %: the blend list, then the typed opacity.
        pick(f)
        click(LayerLabels.BLEND, exact = true)
        assertTrue("\"Pass through\" is listed", has(FolderLabels.PASS_THROUGH, exact = true))
        oneStep("Multiply", LayerOps.BLEND_STEP) { click(LayerBlendMode.MULTIPLY.label, exact = true) }
        assertFalse(f.folder!!.passThrough)
        assertEquals(LayerBlendMode.MULTIPLY, f.blendMode)
        click(LayerLabels.TYPE_OPACITY, exact = true)
        val beforeOpacity = steps()
        SmokeUi.typeAndDone(LayerLabels.OPACITY, "60")
        assertEquals("opacity 60 %", 0.6f, f.opacity, 0.001f)
        assertEquals("one step", beforeOpacity + 1, steps())
        assertFalse("the dialog closed", has("0 – 100 %", exact = true))
        expectAt("Multiply 60 % over the backdrop", P1, mul60(A, B))
        expectAt("the backdrop", P4, B, tol = 0)
        expectAt("Layer 3 above the folder", P3, D, tol = 0)
        Qa17LayersShots.shoot(c, "folders-2-multiply60")

        // "New adjustment layer (Tone)" with Layer 2 active: inside the folder, right above it.
        pick(l2)
        click(LayerLabels.SPECIAL, exact = true)
        oneStep("New adjustment layer", "New adjustment layer") { click(LayerLabels.NEW_ADJUSTMENT, exact = true) }
        val tone = c.activeLayer
        assertTrue(tone.isAdjustmentLayer)
        assertEquals("inside the folder", f.id, tone.parentId)
        assertEquals("right above Layer 2", c.doc.indexOf(l2) + 1, c.doc.indexOf(tone))
        assertTrue("the Adjust sheet; ${SmokeUi.sheetTitles()}", SmokeUi.sheetTitles().any { it == "Adjust: ${tone.name}" })
        click("Choose the effect", exact = true)
        click(INVERT_NAME, exact = true)
        Finger.back()
        settle()
        assertEquals(INVERT, tone.adjustment?.filterId)
        // Inverted inside the isolated folder only: its content, then Multiply 60 %; the backdrop
        // outside the folder's content stays as it was (transparent stays transparent).
        expectAt("Invert inside the folder, Multiply 60 %", P1, mul60(inv(A), B))
        expectAt("the backdrop", P4, B, tol = 0)
        expectAt("Layer 3", P3, D, tol = 0)

        // "Clipping" on Layer 3 (right above the folder): clipped to the folder's content.
        pick(l3)
        oneStep("Clipping", "Clipping") { click(LayerLabels.CLIPPING, exact = true) }
        assertTrue(l3.clipping)
        expectAt("Layer 3 outside the folder's content is clipped away", P3, B, tol = 0)
        // A clip group as in v1.6 (`Compositor.drawGroup`): the base's blend and opacity apply to
        // the group, so Layer 3 over the folder's content is multiplied at 60 % too.
        expectAt("Layer 3 over the folder's content, in the folder's group", P2, mul60(D, B))
        println("Qa17LayersFolders: P1 ${Qa17LayersShots.rgb(at(P1))} P2 ${Qa17LayersShots.rgb(at(P2))} P3 ${Qa17LayersShots.rgb(at(P3))} P4 ${Qa17LayersShots.rgb(at(P4))}")
        Qa17LayersShots.shoot(c, "folders-3-invert-clip")
        closeLayers()
        Smoke.assertQuiet(c, "the tree by fingers")
    }

    // ================================================================== 2. refused tools and samplers

    fun withTheFolderActive() {
        lateinit var f: Layer
        editor { f = tree(it) }
        assertSame(f, c.activeLayer)
        assertEquals(ToolId.BRUSH, c.activeToolId)

        // Brush: a finger stroke over the canvas is refused with the caption.
        refused("Brush on the folder", FolderLabels.PAINT_REFUSAL) { ui.stroke(60f to 60f, 120f to 90f, 180f to 120f) }
        // Bucket.
        ui.tool("Bucket")
        refused("Bucket on the folder", FolderLabels.PAINT_REFUSAL) { ui.tap(100f, 100f) }
        // A filter: picked in the filter browser, refused before anything opens.
        ui.tool("Filters")
        refused("Gaussian Blur on the folder", FolderLabels.PAINT_REFUSAL) { click("Gaussian Blur", exact = true) }
        assertNull("no filter session", c.filterSession)
        if (SmokeUi.sheetTitles().isNotEmpty()) Finger.back()

        // The eyedropper with "Sample: Layer": a folder has no pixels, it reads the composite.
        ui.tool("Eyedropper")
        Finger.tap(s, "Sample: Canvas")
        click("Layer", exact = true)
        assertTrue(has("Sample: Layer", exact = true))
        c.color = 0xFF000000.toInt()
        ui.tap(P1.first + 0.5f, P1.second + 0.5f)
        val want = at(P1)
        assertEquals("the composite colour ${Qa17LayersShots.rgb(want)}", want or 0xFF000000.toInt(), c.color)
        assertEquals("back to the brush", ToolId.BRUSH, c.activeToolId)
        assertSame(f, c.activeLayer)

        // A new shape with the open folder active: its own layer, the folder's top child, one step.
        ui.tool("Shape")
        val tool = c.currentTool as ShapeTool
        tool.update { it.copy(type = ShapeType.RECTANGLE, style = ShapeStyle.FILL, fillColor = SHAPE, keepProportions = false, fromCenter = false) }
        val layers = c.doc.layers.size
        val before = steps()
        ui.stroke(60f to 60f, 90f to 90f, 120f to 120f)
        assertTrue("a pending rectangle", tool.hasPendingWork)
        ui.tool("Brush")
        assertFalse(tool.hasPendingWork)
        assertEquals("one step", before + 1, steps())
        assertEquals(layers + 1, c.doc.layers.size)
        val shape = c.activeLayer
        assertTrue(shape.isShapeLayer)
        assertEquals("in the folder", f.id, shape.parentId)
        assertEquals("its top child", c.doc.indexOf(f) - 1, c.doc.indexOf(shape))
        Qa17LayersShots.shoot(c, "folders-4-shape-in-folder")
        Smoke.assertQuiet(c, "with the folder active")
    }

    // ================================================================== 3. locked and hidden

    fun lockedAndHidden() {
        lateinit var f: Layer
        lateinit var shape: Layer
        editor {
            f = tree(it)
            // A shape inside the folder (its top child) for Pathfinder.
            val o = ShapeObject(ShapeType.RECTANGLE, cx = 90f, cy = 90f, w = 60f, h = 60f, style = ShapeStyle.FILL, fillColor = SHAPE)
            shape = it.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o)) { cv ->
                cv.drawRect(60f, 60f, 120f, 120f, Paint().apply { color = SHAPE })
            }!!
            it.selectLayer(f)
        }
        assertEquals(f.id, shape.parentId)
        val l4 = c.doc.layers.single { it.name == "Layer 4" }

        // Locked: a child is locked through it; painting it is refused, with the folder named.
        pick(f)
        oneStep("Lock the folder", "Lock layer") { click(LayerLabels.LOCK, exact = true) }
        assertTrue(f.locked)
        pick(l4)
        closeLayers()
        refused("Brush on a child of the locked folder", FolderLabels.locked(f.name)) { ui.stroke(60f to 60f, 120f to 90f, 180f to 120f) }
        pick(f)
        click(LayerLabels.LOCK, exact = true)
        assertFalse(f.locked)

        // Hidden (the folder row's eye): its picture goes, Layer 3 (clipped to it) with it.
        val shown = pixels()
        val eye = LayerLabels.hide(c.doc.indexOf(f) + 1)
        Smoke.pump(600)
        Finger.tap(s, eye)
        assertFalse(f.visible)
        expectAt("hidden: the backdrop at P1", P1, B, tol = 0)
        expectAt("hidden: Layer 3 clipped to it goes too", P2, B, tol = 0)
        Qa17LayersShots.shoot(c, "folders-5-hidden")
        closeLayers()

        // Pathfinder cannot pick the hidden folder's shape.
        ui.tool("Pathfinder")
        val pf = c.currentTool as PathfinderTool
        pf.computeDispatcher = Dispatchers.Unconfined
        ui.tap(90f, 90f)
        assertEquals("a tap on the hidden shape picks nothing", 0, pf.count)
        ui.reach(PathfinderLabels.SELECT_ALL, 32f)
        click(PathfinderLabels.SELECT_ALL, exact = true)
        assertEquals("\"Select all objects\" skips the hidden folder's shape", 0, pf.count)
        // Shown again: it is picked.
        ui.tool("Brush")
        openLayers()
        Smoke.pump(600)
        Finger.tap(s, LayerLabels.show(c.doc.indexOf(f) + 1))
        assertTrue(f.visible)
        assertArrayEquals("shown again: the picture is back", shown, pixels())
        closeLayers()
        ui.tool("Pathfinder")
        val pf2 = c.currentTool as PathfinderTool
        pf2.computeDispatcher = Dispatchers.Unconfined
        ui.tap(90f, 90f)
        assertEquals("shown: a tap picks it", 1, pf2.count)
        ui.tool("Brush")
        Smoke.assertQuiet(c, "locked and hidden")
    }

    // ================================================================== 4. merge and layer from folder

    fun mergeAndLayerFromFolder() {
        lateinit var f: Layer
        editor { f = tree(it) }
        val picture = pixels()

        // ⋮ "Layer from folder": a layer right above the folder with its composite, Multiply 60 %.
        pick(f)
        click(LayerLabels.MORE, exact = true)
        oneStep("Layer from folder", HistoryLabels.LAYER_FROM_FOLDER) { click(FolderLabels.FROM_FOLDER, exact = true) }
        val made = c.activeLayer
        assertFalse(made.isFolder)
        assertEquals(c.doc.indexOf(f) + 1, c.doc.indexOf(made))
        assertEquals(Layer.ROOT_ID, made.parentId)
        assertEquals(LayerBlendMode.MULTIPLY, made.blendMode)
        assertEquals(0.6f, made.opacity, 0.001f)
        assertTrue("the folder is kept", f in c.doc.layers)
        c.undoManager.undo(c)
        settle()
        assertArrayEquals(picture, pixels())

        // "Merge folder" on the isolated folder: no question, one step, the picture as it was.
        pick(f)
        ui.reach(FolderLabels.MERGE, 32f)
        oneStep("Merge folder (isolated)", HistoryLabels.MERGE_FOLDER) { click(FolderLabels.MERGE, exact = true) }
        assertFalse("no question", has(FolderLabels.MERGE_BLEND_WARNING, exact = true))
        assertTrue("no folder left", c.doc.layers.none { it.isFolder })
        val merged = c.activeLayer
        assertEquals("Folder 1", merged.name)
        assertEquals(LayerBlendMode.MULTIPLY, merged.blendMode)
        assertEquals(listOf("Layer 1", "Folder 1", "Layer 3"), c.doc.layers.map { it.name })
        assertTrue("Layer 3 stays clipped", c.doc.layers.last().clipping)
        val after = pixels()
        var worst = 0
        for (i in picture.indices) for (sh in intArrayOf(0, 8, 16, 24)) worst = maxOf(worst, kotlin.math.abs(((picture[i] shr sh) and 0xFF) - ((after[i] shr sh) and 0xFF)))
        assertTrue("the picture is unchanged (worst channel off by $worst)", worst <= 1)
        Qa17LayersShots.shoot(c, "folders-6-merged")
        c.undoManager.undo(c)
        settle()
        assertTrue(f in c.doc.layers)

        // A clip base is composited isolated whatever its blend, so Layer 3 is unclipped first.
        val l3 = c.doc.layers.single { it.name == "Layer 3" }
        pick(l3)
        oneStep("Clipping off", "Clipping") { click(LayerLabels.CLIPPING, exact = true) }
        assertFalse(l3.clipping)
        // A pass-through folder over a Multiply child: "Merge folder" asks first.
        pick(f)
        click(LayerLabels.BLEND, exact = true)
        oneStep("Pass through", HistoryLabels.PASS_THROUGH) { click(FolderLabels.PASS_THROUGH, exact = true) }
        val l2 = c.doc.layers.single { it.name == "Layer 2" }
        pick(l2)
        click(LayerLabels.BLEND, exact = true)
        oneStep("Multiply on the child", LayerOps.BLEND_STEP) { click(LayerBlendMode.MULTIPLY.label, exact = true) }
        val passThrough = pixels()
        pick(f)
        ui.reach(FolderLabels.MERGE, 32f)
        val before = steps()
        click(FolderLabels.MERGE, exact = true)
        assertTrue("the question", has(FolderLabels.MERGE_BLEND_WARNING, exact = true))
        SmokeUi.clickIn(FolderLabels.MERGE_BLEND_WARNING, "Cancel")
        assertEquals("Cancel: no step", before, steps())
        assertTrue(f in c.doc.layers)
        assertArrayEquals(passThrough, pixels())
        click(FolderLabels.MERGE, exact = true)
        oneStep("Merge folder (pass-through, confirmed)", HistoryLabels.MERGE_FOLDER) { SmokeUi.clickIn(FolderLabels.MERGE_BLEND_WARNING, "Merge") }
        assertTrue(c.doc.layers.none { it.isFolder })
        assertEquals("a pass-through folder merges as Normal", LayerBlendMode.NORMAL, c.activeLayer.blendMode)
        Qa17LayersShots.shoot(c, "folders-7-merged-pass-through")
        closeLayers()
        Smoke.assertQuiet(c, "merge and layer from folder")
    }

    // ================================================================== 5. save, gallery, reopen, export

    fun saveReopenAndExport() {
        val app = RuntimeEnvironment.getApplication()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        lateinit var f: Layer
        editor { f = tree(it) }
        val picture = Qa17LayersShots.shoot(c, "folders-8-saved")
        val tree = c.doc.layers.map { listOf(it.id, it.parentId, it.name, it.folder, it.blendMode, it.opacity, it.clipping, it.adjustment?.filterId) }
        runBlocking { repo.save(c.doc, null) }
        // Back to the gallery: the artwork is listed.
        val listed = runBlocking { repo.list() }
        assertTrue("the gallery lists it", listed.any { it.id == c.doc.id })

        // Reopened in a new editor: the same tree, the same picture, the folder in the window.
        val loaded = runBlocking { repo.load(c.doc.id) }
        assertEquals(tree, loaded.layers.map { listOf(it.id, it.parentId, it.name, it.folder, it.blendMode, it.opacity, it.clipping, it.adjustment?.filterId) })
        s = h.editor(loaded)
        ui = Qa16Ui(s)
        settle()
        assertArrayEquals("reopened: the same picture", picture, pixels())
        openLayers()
        assertTrue("the folder's row", has(FolderLabels.close(f.name), exact = true) || has(FolderLabels.open(f.name), exact = true))
        closeLayers()
        Smoke.assertQuiet(c, "reopened")

        // The gallery's PNG export (share): the flattened picture.
        runBlocking { repo.shareProject(loaded.id, ExportFormat.PNG) }
        val png = File(app.cacheDir, "exports/${loaded.name}.png")
        assertTrue("the PNG is written", png.isFile)
        val img = BitmapFactory.decodeFile(png.path)
        val got = IntArray(img.width * img.height).also { img.getPixels(it, 0, img.width, 0, 0, img.width, img.height) }
        assertArrayEquals("the PNG is the picture", picture, got)

        // SVG: with Layer 3 clipped to it, the clip group is one picture (said in the export notes).
        fun scene() = runBlocking { ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val clipped = scene()
        assertTrue("notes: ${clipped.notes}", clipped.notes.any { it.contains("Clipping groups are exported as pictures") })
        // Unclipped (by its "Clipping" toggle): the folder is an isolated group (Multiply, 60 %) holding its layers.
        val l3 = c.doc.layers.single { it.name == "Layer 3" }
        pick(l3)
        click(LayerLabels.CLIPPING, exact = true)
        assertFalse(l3.clipping)
        closeLayers()
        val scene = scene()
        val folder = scene.layers.single { it.name == f.name }
        assertTrue(folder.isolated)
        // (An adjustment layer is exported as one picture with what it adjusts; empty Layer 4 is left out.)
        assertEquals(listOf(MERGED_INVERT), folder.children.map { it.name })
        val svg = ByteArrayOutputStream().also { runBlocking { SvgWriter(scene).write(it) } }.toString("UTF-8")
        val g = Regex("<g [^>]*inkscape:label=\"${f.name}\"[^>]*>").find(svg)?.value ?: throw AssertionError("no folder group in the SVG")
        assertTrue("the folder group: $g", g.contains("opacity:0.6") && g.contains("mix-blend-mode:multiply") && g.contains("isolation:isolate"))
        assertTrue("its picture is inside it", svg.indexOf("inkscape:label=\"$MERGED_INVERT\"") > svg.indexOf(g))
        assertTrue("Layer 3 is outside it, above", svg.indexOf("inkscape:label=\"Layer 3\"") > svg.indexOf("inkscape:label=\"$MERGED_INVERT\""))
        File(app.cacheDir, "qa17-folders.svg").writeText(svg)
        println("Qa17LayersFolders: SVG folder group $g")
    }

    companion object {
        fun run(widthDp: Float, all: Boolean = true) {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 60_000)
            val h = ChromeHarness()
            val t = Qa17LayersFolders(h, widthDp)
            val at = "at ${widthDp.roundToInt()} dp"
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                h.section("$name $at", block)
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            timed("1 the tree by fingers") { t.treeByFingers() }
            if (all) timed("2 with the folder active") { t.withTheFolderActive() }
            if (all) timed("3 locked and hidden") { t.lockedAndHidden() }
            timed("4 merge and layer from folder") { t.mergeAndLayerFromFolder() }
            if (all) timed("5 save, gallery, reopen, export") { t.saveReopenAndExport() }
            println("Qa17LayersFolders times: $times")
            t.release()
            dog.interrupt()
            h.finish()
        }

        const val W = 256
        const val H = 192
        /** Backdrop, orange block, green block, the shape's fill. */
        val B = 0xFF8CB4DC.toInt()
        val A = 0xFFE07830.toInt()
        val D = 0xFF30A050.toInt()
        val SHAPE = 0xFF6040C0.toInt()
        val P1 = 80 to 100
        val P2 = 175 to 100
        val P3 = 220 to 160
        val P4 = 20 to 20
        private val ROW = Regex("^Select layer (\\d+)$")
        const val INVERT = "adjust.invert"
        const val INVERT_NAME = "Invert Color"
        const val MERGED_INVERT = "Invert Color 1 (merged with the layers below)"

        private fun ch(c: Int, sh: Int) = ((c shr sh) and 0xFF).toDouble()

        /** Isolated Multiply of opaque [top] over opaque [back] at 60 %: 0.6·(top·back) + 0.4·back. */
        fun mul60(top: Int, back: Int): Triple<Double, Double, Double> {
            fun one(sh: Int) = 0.6 * (ch(top, sh) * ch(back, sh) / 255.0) + 0.4 * ch(back, sh)
            return Triple(one(16), one(8), one(0))
        }

        fun inv(c: Int): Int = (c and 0xFF000000.toInt()) or (c.inv() and 0xFFFFFF)

    }
}

/** Item 8 on the user's phone (392 dp): folders in the full editor, by fingers. */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersfolderssandbox"])
class Qa17LayersFoldersUiTest {
    @Test
    fun foldersWorkByFingersInTheEditorAt392dp() = Qa17LayersFolders.run(392f)
}

/**
 * Item 8 on a 360 dp phone: the layer-window parts (the tree by fingers, Merge folder and Layer
 * from folder), where the window is narrowest.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersfolders360sandbox"])
class Qa17LayersFolders360UiTest {
    @Test
    fun foldersWorkByFingersInTheLayerWindowAt360dp() = Qa17LayersFolders.run(360f, all = false)
}
