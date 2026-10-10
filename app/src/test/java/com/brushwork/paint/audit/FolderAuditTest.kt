package com.brushwork.paint.audit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasResult
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.exchange.ImportLayers
import com.brushwork.paint.exchange.NewLayer
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.masks.AdjustmentHistogram
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SelectionMode
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.SelectionEdits
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.common.TransformLabels17
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.ui.layers.LayerOps
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.Shadows.shadowOf
import kotlin.math.abs

/**
 * v1.7 F2 (§3.8 d, foundation rows; risks R1, R1b, 22, 23, 30): whole-document operations on
 * nested folders (canvas operations and their undo, flatten, thumbnail, histogram, snapping,
 * export, save and load, a gallery export) never treat a folder as pixels and keep every
 * folder's `FOLDER_BITMAP` alive; with a folder active, every refused entry point leaves no step
 * and no change; the eyedropper reads the composite; a child of a locked folder behaves like a
 * locked layer for every tool, and a child of a hidden folder is not snapped to.
 *
 * v1.7 (§6.2, after areas A, E, F and G): the same per-operation checks (ONE step, exact undo
 * and redo of the tree, pixels, data and picture, the tree invariant, nothing drawn into
 * `FOLDER_BITMAP`) for arrays made and applied inside the folders, a Free deform of a child (a
 * folder refuses it), saved selections following a canvas rotate / flip / resize, and Pathfinder
 * on shape layers in two folders.
 */
@RunWith(RobolectricTestRunner::class)
class FolderAuditTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() {
        scope.cancel()
        ArrayDraw.clearCaches()
    }

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 96
    private val h = 64

    private val red = 0xFFDD2211.toInt()
    private val green = 0xFF22AA44.toInt()
    private val blue = 0xFF2244CC.toInt()

    /**
     * Bottom first: Background (white), [A (red), [B (green)] F2] F1, Top (blue); F2 is isolated
     * (pass-through off). Top is active.
     */
    private fun nested(): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("audit", "audit", w, h)
        fun pixel(name: String, parent: Long, draw: (Bitmap) -> Unit) =
            Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = parent; draw(it.bitmap) }
        fun rect(color: Int, r: Rect): (Bitmap) -> Unit = { b -> Canvas(b).drawRect(r, Paint().apply { this.color = color }) }
        val bg = pixel("Background", Layer.ROOT_ID) { it.eraseColor(-1) }
        val f1 = Layer.newFolder(doc.newLayerId(), "F1")
        val f2 = Layer.newFolder(doc.newLayerId(), "F2", FolderSpec(passThrough = false)).also { it.parentId = f1.id }
        val a = pixel("A", f1.id, rect(red, Rect(10, 10, 40, 30)))
        val b = pixel("B", f2.id, rect(green, Rect(30, 20, 60, 50)))
        val top = pixel("Top", Layer.ROOT_ID, rect(blue, Rect(70, 5, 90, 25)))
        doc.layers += listOf(bg, a, b, f2, f1, top)
        doc.activeLayerIndex = 5
        assertNull(LayerTree.check(doc.layers))
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    private fun rectSelection(r: Rect): Selection =
        Selection.fromPath(Path().apply { addRect(RectF(r), Path.Direction.CW) }, w, h, antiAlias = false)

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Order, parents and every pixel layer's pixels. */
    private fun picture(c: EditorController): List<Any> =
        c.doc.layers.map { l -> Triple(l.id, l.parentId, if (l.isFolder) "folder" else px(l.bitmap).contentHashCode()) }

    private fun flat(c: EditorController): IntArray = c.compositor.renderFlattened().let { b -> try { px(b) } finally { b.recycle() } }

    private fun assertFoldersIntact(c: EditorController, what: String) {
        assertNull(what, LayerTree.check(c.doc.layers))
        assertFalse("$what: FOLDER_BITMAP alive", Layer.FOLDER_BITMAP.isRecycled)
        assertEquals("$what: FOLDER_BITMAP is 1 x 1", 1, Layer.FOLDER_BITMAP.width)
        assertEquals("$what: nothing was drawn into FOLDER_BITMAP", 0, Layer.FOLDER_BITMAP.getPixel(0, 0))
        for (l in c.doc.layers) {
            if (l.isFolder) {
                assertSame("$what: ${l.name} keeps FOLDER_BITMAP", Layer.FOLDER_BITMAP, l.bitmap)
                assertNull("$what: ${l.name} has no mask", l.mask)
            } else {
                assertEquals("$what: ${l.name} is document-sized", c.doc.width, l.bitmap.width)
                assertEquals("$what: ${l.name} is document-sized", c.doc.height, l.bitmap.height)
            }
        }
    }

    /**
     * Runs [action], then waits for the background jobs it started (not long-lived ones started
     * before it), letting the main looper run meanwhile.
     */
    private fun settled(what: String, action: () -> Unit) {
        val baseline = job.children.toSet()
        action()
        val until = System.currentTimeMillis() + 15_000
        while (true) {
            shadowOf(Looper.getMainLooper()).idle()
            val busy = job.children.filter { it.isActive && it !in baseline }.toList()
            if (busy.isEmpty()) break
            check(System.currentTimeMillis() < until) { "$what: ${busy.size} background jobs did not finish: $busy" }
            Thread.sleep(2)
        }
        shadowOf(Looper.getMainLooper()).idle()
    }

    private fun EditorController.drag(vararg pts: Pair<Float, Float>) {
        pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) pointerMove(ToolPoint(pts[i].first, pts[i].second))
        pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun EditorController.gesture() = drag(12f to 12f, 20f to 16f, 28f to 22f, 36f to 26f)

    // ------------------------------------------------------------------ whole-document operations

    @Test
    fun canvasOperationsKeepTheFoldersAndUndoRestoresThePicture() {
        val ops: List<Pair<String, (CanvasSnapshot) -> CanvasResult>> = listOf(
            "Resize image" to { s -> CanvasOps.resizeImage(s, 48, 32, Resample.BILINEAR) },
            "Canvas size" to { s -> CanvasOps.resizeCanvas(s, 120, 80, 10, 6) },
            "Crop" to { s -> CanvasOps.cropTo(s, Rect(5, 5, 70, 50)) },
            "Rotate" to { s -> CanvasOps.rotate(s, CanvasRotation.CW_90) },
            "Flip" to { s -> CanvasOps.flip(s, horizontal = true) },
            "Trim" to { s -> CanvasOps.trimTransparent(s) },
        )
        for ((label, op) in ops) {
            val c = nested()
            // Trim needs transparent edges: the opaque background is hidden (trim reads what shows).
            if (label == "Trim") c.byName("Background").visible = false
            val before = picture(c)
            val flatBefore = flat(c)
            val snap = CanvasSnapshot.of(c.doc)
            assertEquals("$label: the snapshot holds pixel layers only", 4, snap.layers.size)
            CanvasOps.commit(c, label, snap, op(snap))
            assertFoldersIntact(c, label)
            val after = picture(c)
            flat(c) // the composite draws
            c.undo()
            assertFoldersIntact(c, "$label undone")
            assertEquals("$label undone: the tree and pixels", before, picture(c))
            assertTrue("$label undone: the picture", flatBefore.contentEquals(flat(c)))
            c.redo()
            assertFoldersIntact(c, "$label redone")
            assertEquals("$label redone", after, picture(c))
        }
    }

    @Test
    fun flattenThumbnailHistogramAndSnappingSkipFolders() {
        val c = nested()
        flat(c)
        c.compositor.renderThumbnail(32).recycle()
        assertNotNull(AdjustmentHistogram.below(c, c.byName("Top")))
        val candidates = c.snapping.candidateLayers()
        assertTrue("no folder is snapped to", candidates.none { it.isFolder })
        assertEquals(listOf("Background", "A", "B", "Top"), candidates.map { it.name })
        // A child of a hidden folder is not snapped to.
        c.byName("F2").visible = false
        assertEquals(listOf("Background", "A", "Top"), c.snapping.candidateLayers().map { it.name })
        c.byName("F1").visible = false
        assertEquals(listOf("Background", "Top"), c.snapping.candidateLayers().map { it.name })
        assertFoldersIntact(c, "renders")
    }

    @Test
    fun toggleClippingOnAFolderReadsTheUnitBelowItsBlock() {
        val c = nested()
        // F2's block is [B, F2]; the unit below it is A, not its own child B.
        c.byName("B").adjustment = AdjustmentSpec(filterId = "adjust.invert")
        c.toggleClipping(c.byName("F2"))
        assertTrue("an adjustment child does not block clipping", c.byName("F2").clipping)
        c.undo()
        c.byName("B").adjustment = null
        c.byName("A").adjustment = AdjustmentSpec(filterId = "adjust.invert")
        val steps = c.undoManager.undoCount
        c.toggleClipping(c.byName("F2"))
        assertFalse("never clipped onto an adjustment layer", c.byName("F2").clipping)
        assertEquals(steps, c.undoManager.undoCount)
        c.byName("A").adjustment = null
        // F1's unit below is Background.
        c.byName("Background").adjustment = AdjustmentSpec(filterId = "adjust.invert")
        c.toggleClipping(c.byName("F1"))
        assertFalse(c.byName("F1").clipping)
        assertFoldersIntact(c, "clipping")
    }

    @Test
    fun exportWritesTheTreeAndNestsTheFolders() {
        val c = nested()
        fun build(includeHidden: Boolean) = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, includeHidden = includeHidden), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val scene = build(includeHidden = false)
        assertEquals(listOf("Background", "F1", "Top"), scene.layers.map { it.name })
        assertEquals(listOf("A", "F2"), scene.layers[1].children.map { it.name })
        assertEquals(listOf("B"), scene.layers[1].children[1].children.map { it.name })
        assertFalse(scene.layers[1].isolated)
        assertTrue(scene.layers[1].children[1].isolated)
        val payload = scene.payload!!
        assertEquals(c.doc.layers.map { it.id }, payload.layers.map { it.id })
        assertEquals(c.doc.layers.map { it.parentId }, payload.layers.map { it.parentId })
        for ((l, p) in c.doc.layers.zip(payload.layers)) {
            if (l.isFolder) {
                assertEquals(PayloadKind.FOLDER, p.kind)
                assertEquals(l.folder, p.folder)
                assertNull("a folder has no picture", p.imageRef)
            } else {
                assertTrue(p.kind != PayloadKind.FOLDER)
            }
        }
        // A hidden folder hides its layers.
        c.byName("F2").visible = false
        assertEquals(listOf("A"), build(includeHidden = false).layers[1].children.map { it.name })
        val withHidden = build(includeHidden = true).layers[1].children
        assertTrue(withHidden.first { it.name == "F2" }.hidden)
        assertFalse(withHidden.first { it.name == "F2" }.children.single { it.name == "B" }.hidden)
        assertFalse(withHidden.first { it.name == "A" }.hidden)
        assertFoldersIntact(c, "export")
    }

    @Test
    fun saveLoadAndAGalleryExportKeepTheSharedFolderBitmap() = runBlocking<Unit> {
        val c = nested()
        val repo = ProjectRepository(app)
        repo.save(c.doc, null)
        val loaded = repo.load(c.doc.id)
        assertEquals(c.doc.layers.map { Triple(it.id, it.parentId, it.folder) }, loaded.layers.map { Triple(it.id, it.parentId, it.folder) })
        for (l in loaded.layers) if (l.isFolder) assertSame(Layer.FOLDER_BITMAP, l.bitmap)
        // The gallery export loads its own copy and recycles its bitmaps (rule B).
        repo.exportProject(c.doc.id, ExportFormat.PNG)
        assertFalse("FOLDER_BITMAP survives the export's recycling", Layer.FOLDER_BITMAP.isRecycled)
        flat(c) // drawing the folders afterwards does not throw
        assertFoldersIntact(c, "after the export")
    }

    // ------------------------------------------------------------------ a folder active

    @Test
    fun withAFolderActiveEveryRefusedEntryPointLeavesNoStepAndNoChange() {
        val c = nested()
        val f1 = c.byName("F1")
        c.selectLayer(f1)
        assertSame(f1, c.activeLayer)
        c.setSelection(rectSelection(Rect(5, 5, 50, 40)), label = "Select")
        val steps = c.undoManager.undoCount
        val before = picture(c)
        fun refused(what: String, action: () -> Unit) {
            settled(what, action)
            assertEquals("$what: no step", steps, c.undoManager.undoCount)
            assertEquals("$what: no change", before, picture(c))
            assertSame("$what: the folder stays active", f1, c.activeLayer)
            assertFoldersIntact(c, what)
        }
        for (id in LayerToolRules.FOLDER_REFUSED) {
            c.selectTool(id) // the tool's own observers live until it is put away
            refused("tool $id") { c.gesture() }
            refused("tool $id put away") { c.selectTool(ToolId.LASSO) }
        }
        refused("filter") { c.startFilter(FilterRegistry.byId("adjust.invert")!!) }
        assertNull("no filter session", c.filterSession)
        refused("clear") { LayerOps.clear(c, f1) }
        refused("fill") { LayerOps.fill(c, f1) }
        refused("flip layer") { LayerOps.flip(c, f1, horizontal = true) }
        refused("add mask") { LayerOps.addMask(c, f1, fromSelection = false) }
        refused("add mask (controller)") { c.addMask(f1) }
        refused("alpha lock") { c.toggleAlphaLock(f1) }
        refused("cut") { c.cutSelection() }
        refused("fill selection") { SelectionEdits.fillSelection(c, red) }
        refused("clear selection") { SelectionEdits.clearSelection(c) }
        refused("cut to new layer") { SelectionEdits.cutToNewLayer(c) }
        refused("copy (no step)") { assertTrue(c.copySelection()) }
    }

    @Test
    fun withAFolderActiveTheSamplersReadTheComposite() {
        val c = nested()
        val f1 = c.byName("F1")
        c.selectLayer(f1)
        c.selectTool(ToolId.EYEDROPPER)
        val dropper = c.currentTool as EyedropperTool
        dropper.settings = dropper.settings.copy(source = SampleSource.LAYER, sampleSize = 1)
        assertEquals("the composite colour (the folder's red child)", red, dropper.sample(15f, 15f))
        // "Copy to new layer" copies the composite of the folder's layers.
        c.setSelection(rectSelection(Rect(10, 10, 40, 30)), label = "Select")
        val copy = SelectionEdits.copyToNewLayer(c)
        assertNotNull(copy)
        assertEquals(red, copy!!.bitmap.getPixel(15, 15))
        assertFoldersIntact(c, "samplers")
    }

    // ------------------------------------------------------------------ effective lock and visibility

    /** What a gesture of [id] did: steps added, whether any pixels or the layer list changed. */
    private fun outcome(c: EditorController, id: ToolId): Pair<Int, Boolean> {
        val steps = c.undoManager.undoCount
        val before = c.doc.layers.map { if (it.isFolder) 0 else px(it.bitmap).contentHashCode() }
        // The tool's own observers (started when it is chosen) live until it is put away.
        c.selectTool(id)
        settled("$id") { c.gesture() }
        // Putting the tool away commits whatever it still holds.
        settled("$id put away") { c.selectTool(if (id == ToolId.MARQUEE) ToolId.LASSO else ToolId.MARQUEE) }
        val after = c.doc.layers.map { if (it.isFolder) 0 else px(it.bitmap).contentHashCode() }
        return (c.undoManager.undoCount - steps) to (before != after)
    }

    @Test
    fun theGestureReachesAnUnlockedLayer() {
        // The control for the two tests below: the same gesture on the same layer, free, paints.
        val free = nested().also { c -> c.selectLayer(c.byName("A")) }
        assertEquals(1 to true, outcome(free, ToolId.BRUSH))
    }

    @Test
    fun aChildOfALockedFolderBehavesLikeALockedLayerForEveryTool() {
        for (id in ToolId.entries) {
            val inFolder = nested().also { c -> c.byName("F1").locked = true; c.selectLayer(c.byName("A")) }
            val plain = nested().also { c -> c.byName("A").locked = true; c.selectLayer(c.byName("A")) }
            val a = outcome(inFolder, id)
            val b = outcome(plain, id)
            assertEquals("$id: a child of a locked folder = a locked layer (steps, pixel change)", b, a)
            assertFoldersIntact(inFolder, "$id")
        }
    }

    @Test
    fun aChildOfAHiddenFolderBehavesLikeAHiddenLayerForEveryTool() {
        for (id in ToolId.entries) {
            val inFolder = nested().also { c -> c.byName("F1").visible = false; c.selectLayer(c.byName("A")) }
            val plain = nested().also { c -> c.byName("A").visible = false; c.selectLayer(c.byName("A")) }
            val a = outcome(inFolder, id)
            val b = outcome(plain, id)
            assertEquals("$id: a child of a hidden folder = a hidden layer (steps, pixel change)", b, a)
            assertFoldersIntact(inFolder, "$id")
        }
    }

    // ------------------------------------------------------------------ every entry point, a folder active

    /** A fresh [nested] document with a selection, A's pixels on the clipboard, and F1 active. */
    private fun folderActive(): EditorController = nested().also { c ->
        c.selectLayer(c.byName("A"))
        c.setSelection(rectSelection(Rect(5, 5, 50, 40)), label = "Select")
        assertTrue(c.copySelection())
        c.selectLayer(c.byName("F1"))
        assertTrue(c.activeLayer.isFolder)
    }

    /**
     * Runs [action] on a fresh [folderActive] document (the tool it chose put away afterwards):
     * no exception, the tree and every folder intact, and the composite draws. [refused]: no step
     * and no change. Otherwise the steps it pushed are undone to the very tree, pixels and
     * picture, and redone to what it made.
     */
    private fun audit(what: String, refused: Boolean, tool: ToolId? = null, action: (EditorController, Layer) -> Unit): Int {
        val c = folderActive()
        val f1 = c.byName("F1")
        // The tool's own observers (started when it is chosen) live until it is put away.
        tool?.let { c.selectTool(it) }
        val steps = c.undoManager.undoCount
        val before = picture(c)
        val flatBefore = flat(c)
        settled(what) { action(c, f1) }
        settled("$what put away") {
            c.filterSession?.cancel()
            c.selectTool(if (c.currentTool.id == ToolId.LASSO) ToolId.MARQUEE else ToolId.LASSO)
        }
        assertFoldersIntact(c, what)
        flat(c)
        val added = c.undoManager.undoCount - steps
        if (refused) {
            assertEquals("$what: no step", 0, added)
            assertEquals("$what: no change", before, picture(c))
            return 0
        }
        assertTrue("$what: no step was lost", added >= 0)
        val after = picture(c)
        repeat(added) { settled("$what undone") { c.undo() } }
        assertFoldersIntact(c, "$what undone")
        assertEquals("$what undone: the tree and pixels", before, picture(c))
        assertTrue("$what undone: the picture", flatBefore.contentEquals(flat(c)))
        repeat(added) { settled("$what redone") { c.redo() } }
        assertFoldersIntact(c, "$what redone")
        assertEquals("$what redone", after, picture(c))
        return added
    }

    @Test
    fun withAFolderActiveEveryLayerAndMaskOperationIsRefusedOrKeepsTheTree() {
        // Pixel and mask operations: a folder has neither.
        val refused = listOf<Pair<String, (EditorController, Layer) -> Unit>>(
            "convert to vector" to { c, f -> LayerOps.convertToVector(c, f) },
            "rasterize vector" to { c, f -> LayerOps.rasterizeVector(c, f) },
            "edit objects" to { c, f -> LayerOps.editObjects(c, f) },
            "edit adjustment" to { c, f -> LayerOps.editAdjustment(c, f) },
            "edit adjustment mask" to { c, f -> LayerOps.editAdjustmentMask(c, f) },
            "gradient mask" to { c, f -> LayerOps.addGradientMask(c, f) },
            "to pixel mask" to { c, f -> LayerOps.toPixelMask(c, f) },
            "mask to selection" to { c, f -> LayerOps.maskToSelection(c, f) },
            "flip horizontal" to { c, f -> LayerOps.flip(c, f, horizontal = true) },
            "flip vertical" to { c, f -> LayerOps.flip(c, f, horizontal = false) },
            "clear" to { c, f -> LayerOps.clear(c, f) },
            "fill" to { c, f -> LayerOps.fill(c, f) },
            "add mask" to { c, f -> LayerOps.addMask(c, f, fromSelection = false) },
            "add mask from selection" to { c, f -> LayerOps.addMask(c, f, fromSelection = true) },
            "delete mask" to { c, f -> LayerOps.deleteMask(c, f) },
            "apply mask" to { c, f -> LayerOps.applyMask(c, f) },
            "invert mask" to { c, f -> LayerOps.invertMask(c, f) },
            "mask off" to { c, f -> LayerOps.setMaskEnabled(c, f, enabled = false) },
            "edit text" to { c, f -> LayerOps.editText(c, f) },
            "edit shape" to { c, f -> LayerOps.editShape(c, f) },
            "edit the mask" to { c, f -> LayerOps.editTarget(c, f, mask = true) },
            "edit the content" to { c, f -> LayerOps.editTarget(c, f, mask = false) },
            "alpha lock" to { c, f -> c.toggleAlphaLock(f) },
            "bar: clear" to { c, _ -> c.clearLayer() },
            "bar: cut" to { c, _ -> c.cutSelection() },
            "fill selection" to { c, _ -> SelectionEdits.fillSelection(c, red) },
            "clear selection" to { c, _ -> SelectionEdits.clearSelection(c) },
            "cut to new layer" to { c, _ -> SelectionEdits.cutToNewLayer(c) },
        ) + FilterRegistry.all.map { filter -> "filter ${filter.id}" to { c: EditorController, _: Layer -> c.startFilter(filter) } }
        for ((what, action) in refused) audit(what, refused = true, action = action)
        // Structure, selection and document operations: allowed, one undo away from the start.
        val allowed = listOf<Pair<String, (EditorController, Layer) -> Unit>>(
            "duplicate" to { c, f -> LayerOps.duplicate(c, f) },
            "merge down (merge folder)" to { c, f -> LayerOps.mergeDown(c, f) },
            "add layer" to { c, _ -> LayerOps.addLayer(c) },
            "add vector layer" to { c, _ -> LayerOps.addVectorLayer(c) },
            "add adjustment layer" to { c, _ -> LayerOps.addAdjustmentLayer(c) },
            "add folder" to { c, _ -> c.addFolder() },
            "put in new folder" to { c, f -> c.putInNewFolder(f) },
            "take the bottom child out" to { c, _ -> c.takeOutOfFolder(c.byName("A")) },
            "move up" to { c, f -> c.moveLayerUp(f) },
            "move down" to { c, f -> c.moveLayerDown(f) },
            "close" to { c, f -> c.setFolderOpen(f, open = false) },
            "pass through off" to { c, f -> c.setFolderPassThrough(f, on = false) },
            "merge folder" to { c, f -> c.mergeFolder(f) },
            "layer from folder" to { c, f -> c.layerFromFolder(f) },
            "ungroup" to { c, f -> c.ungroupFolder(f) },
            "delete folder, keep its layers" to { c, f -> c.deleteFolder(f, keepChildren = true) },
            "delete folder and its layers" to { c, f -> c.deleteFolder(f, keepChildren = false) },
            "rename" to { c, f -> c.renameLayer(f, "Renamed") },
            "flip canvas" to { c, _ -> LayerOps.flipCanvas(c, horizontal = true) },
            "transform layer" to { c, f -> LayerOps.transform(c, f); c.gesture() },
            "bar: copy" to { c, _ -> c.copySelection() },
            "bar: paste" to { c, _ -> c.paste() },
            "bar: deselect" to { c, _ -> c.deselect() },
            "bar: invert selection" to { c, _ -> c.invertSelection() },
            "copy to new layer" to { c, _ -> SelectionEdits.copyToNewLayer(c) },
            "select layer opacity" to { c, _ -> SelectionEdits.selectLayerOpacity(c, SelectionMode.REPLACE) },
            "grow selection" to { c, _ -> SelectionEdits.growOrShrink(c, 3) },
            "shrink selection" to { c, _ -> SelectionEdits.growOrShrink(c, -3) },
            "feather selection" to { c, _ -> SelectionEdits.feather(c, 2f) },
        )
        // Opening or closing a folder is a view change (no step); copying is no step either. The
        // Transform tool lifts a folder's layers together (area F, FolderLiftProvider): one step.
        val noStep = setOf("close", "bar: copy")
        for ((what, action) in allowed) {
            val added = audit(what, refused = false, action = action)
            if (what !in noStep) assertTrue("$what: one undo away (pushed $added steps)", added >= 1)
        }
    }

    @Test
    fun withAFolderActiveAGestureOfEveryToolKeepsTheTree() {
        for (id in ToolId.entries) {
            audit("tool $id", refused = id in LayerToolRules.FOLDER_REFUSED, tool = id) { c, _ -> c.gesture() }
        }
    }

    /** Where [add] put its new layer: (flat index, parent's name or "root"). */
    private fun landing(c: EditorController, add: (EditorController) -> Layer?): Pair<Int, String> {
        val layer = add(c)
        assertNotNull(layer)
        assertFoldersIntact(c, "added ${layer!!.name}")
        assertSame("the new layer is active", layer, c.activeLayer)
        val parent = c.doc.layers.firstOrNull { it.id == layer.parentId }?.name ?: "root"
        return c.doc.indexOf(layer) to parent
    }

    @Test
    fun newLayersGoAboveTheActiveRowOrToTheTopOfAnOpenActiveFolder() {
        // §3.8: bottom first [Background, A, B, F2, F1, Top]; A and F2 in F1, B in F2.
        val adds = listOf<Pair<String, (EditorController) -> Layer?>>(
            "layer" to { c -> c.addLayer() },
            "vector layer" to { c -> c.addVectorLayer() },
            "adjustment layer" to { c -> c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(drawingColor = red), null) },
            "paste" to { c -> c.paste() },
        )
        for ((what, add) in adds) {
            fun doc(active: String, open: Boolean = true) = nested().also { c ->
                c.selectLayer(c.byName("A"))
                c.setSelection(rectSelection(Rect(5, 5, 50, 40)), label = "Select")
                assertTrue(c.copySelection())
                c.byName("F1").folderOpen = open
                c.selectLayer(c.byName(active))
            }
            assertEquals("$what, the open folder F1 active: its top child", 4 to "F1", landing(doc("F1"), add))
            assertEquals("$what, F1 closed: above it at its level", 5 to "root", landing(doc("F1", open = false), add))
            assertEquals("$what, A active: above it in its folder", 2 to "F1", landing(doc("A"), add))
            assertEquals("$what, Top active: above it", 6 to "root", landing(doc("Top"), add))
        }
        // "New folder" goes directly above the active row at its level, even an open folder.
        assertEquals(5 to "root", landing(nested().also { c -> c.selectLayer(c.byName("F1")) }) { c -> c.addFolder() })
        assertEquals(2 to "F1", landing(nested().also { c -> c.selectLayer(c.byName("A")) }) { c -> c.addFolder() })
    }

    @Test
    fun aDiscardedPasteIntoAnOpenFolderLeavesNoTrace() {
        val c = nested()
        c.selectLayer(c.byName("A"))
        c.setSelection(rectSelection(Rect(5, 5, 50, 40)), label = "Select")
        assertTrue(c.copySelection())
        val f1 = c.byName("F1")
        c.selectLayer(f1)
        val before = picture(c)
        val steps = c.undoManager.undoCount
        val pasted = c.paste()
        assertNotNull(pasted)
        assertEquals("the paste goes into the open folder", f1.id, pasted!!.parentId)
        // The placement is discarded before anything was drawn: its empty layer goes with its step.
        settled("discard") { (c.currentTool as TransformTool).discard() }
        assertEquals("the empty layer is gone", -1, c.doc.indexOf(pasted))
        assertEquals("no step is left", steps, c.undoManager.undoCount)
        assertFalse("nothing to redo", c.undoManager.canRedo)
        assertEquals(before, picture(c))
        assertSame("the folder is active again", f1, c.activeLayer)
        assertFoldersIntact(c, "discarded paste")
    }

    @Test
    fun anImportInReplaceModeKeepsTheFolderAndItsLayers() {
        val c = nested()
        val f1 = c.byName("F1")
        val kept = listOf("Background", "A", "B", "F2", "F1").map { c.byName(it) }
        val before = picture(c)
        val steps = c.undoManager.undoCount
        val picture = BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(red) }
        // Named like the folder; the replace list holds the folder, a layer in it and a top-level layer.
        val created = ImportLayers.insert(c, listOf(NewLayer("F1", picture)), "Import", replace = listOf(f1, c.byName("A"), c.byName("Top")))
        assertEquals(1, created.size)
        assertEquals("one undo step", steps + 1, c.undoManager.undoCount)
        for (l in kept) assertTrue("${l.name} is kept", c.doc.indexOf(l) >= 0)
        assertEquals("the folder keeps its block", listOf(f1.id, f1.id), c.doc.layers.filter { it.parentId == f1.id }.map { it.parentId })
        assertTrue("the top-level layer is replaced", c.doc.layers.none { it.name == "Top" })
        assertSame(created.single(), c.activeLayer)
        assertFoldersIntact(c, "import")
        c.undo()
        assertEquals("one undo restores the tree", before, picture(c))
        assertFoldersIntact(c, "import undone")
    }

    // ------------------------------------------------------------------ v1.7 areas (§6.2: after A, E, F and G merge)

    /** Every pixel layer's data (text, shape, vector, array), by id. */
    private fun data(c: EditorController): Map<Long, LayerData> = c.doc.layers.filter { !it.isFolder }.associate { it.id to it.dataSnapshot() }

    private fun bytes(s: Selection): ByteArray = BitmapUtils.alpha8ToBytes(s.mask)

    /**
     * Runs [action], one whole-document operation, with the audit's checks: ONE step named
     * [label]; the tree and every folder intact; undo restores the very tree, pixels, data and
     * picture, redo what it made (the state it leaves). Returns the layers it changed (pixels or
     * data, by name).
     */
    private fun oneStep(c: EditorController, what: String, label: String, action: () -> Unit): List<String> {
        val steps = c.undoManager.undoCount
        val before = picture(c)
        val dataBefore = data(c)
        val flatBefore = flat(c)
        settled(what, action)
        assertEquals("$what: ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(what, label, c.undoManager.undoLabel)
        assertFoldersIntact(c, what)
        val after = picture(c)
        val dataAfter = data(c)
        val flatAfter = flat(c)
        settled("$what undone") { c.undo() }
        assertFoldersIntact(c, "$what undone")
        assertEquals("$what undone: the tree and pixels", before, picture(c))
        assertEquals("$what undone: the data", dataBefore, data(c))
        assertTrue("$what undone: the picture", flatBefore.contentEquals(flat(c)))
        settled("$what redone") { c.redo() }
        assertFoldersIntact(c, "$what redone")
        assertEquals("$what redone: the tree and pixels", after, picture(c))
        assertEquals("$what redone: the data", dataAfter, data(c))
        assertTrue("$what redone: the picture", flatAfter.contentEquals(flat(c)))
        val was = before.associateBy { (it as Triple<*, *, *>).first }
        val now = after.associateBy { (it as Triple<*, *, *>).first }
        return c.doc.layers.filter { was[it.id] != now[it.id] || dataBefore[it.id] != dataAfter[it.id] }.map { it.name }
    }

    @Test
    fun arraysInsideTheFoldersAreMadeAndAppliedInOneStepEach() {
        // A raster array: A's red rectangle (in F1) moves into "Array 1", directly above A in F1.
        val c = nested()
        val a = c.byName("A")
        c.selectLayer(a)
        c.setSelection(rectSelection(Rect(5, 5, 45, 35)), recordUndo = false)
        oneStep(c, "array from selection", ArrayLabels.BUTTON) { assertTrue(c.arrayFromSelection()) }
        val arrayed = c.activeLayer
        assertEquals("in A's folder, directly above A", listOf("Background", "A", arrayed.name, "B", "F2", "F1", "Top"), c.doc.layers.map { it.name })
        assertEquals(a.parentId, arrayed.parentId)
        c.setSelection(null, recordUndo = false)
        val source = arrayed.array!!.pixels!!
        val copies = px(arrayed.bitmap)
        assertEquals(listOf(arrayed.name), oneStep(c, "apply array (pixels)", ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(c, arrayed)) })
        assertNull("plain pixels now", arrayed.array)
        assertTrue("the copies stay", copies.contentEquals(px(arrayed.bitmap)))
        settled("apply undone") { c.undo() }
        assertSame("undo: the very source", source, arrayed.array?.pixels)

        // A shape array in the isolated F2: the shape layer becomes a vector layer, one shape per copy.
        val d = nested()
        d.selectLayer(d.byName("B"))
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 50f, cy = 40f, w = 12f, h = 8f, style = ShapeStyle.FILL, fillColor = blue)
        val shape = d.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        assertEquals("added in F2", d.byName("F2").id, shape.parentId)
        oneStep(d, "array a whole shape layer", ArrayLabels.BUTTON) { assertTrue(d.arrayWholeLayer(shape)) }
        assertNotNull(shape.array)
        assertEquals(listOf("Shape"), oneStep(d, "apply array (shape)", ArrayLabels.APPLY) { assertTrue(ArrayOps.apply(d, shape)) })
        assertNull(shape.array)
        assertNull(shape.shapeData)
        assertEquals("one shape per copy", ArraySpec().count, shape.vector!!.objects.size)
        assertEquals(d.byName("F2").id, shape.parentId)
    }

    @Test
    fun freeDeformOnALayerInsideTheFoldersIsOneStepAndAFolderRefusesIt() {
        val c = nested()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        // F1 active: the folder is lifted whole and Free deform is refused, no step, no change.
        c.selectLayer(c.byName("F1"))
        val before = picture(c)
        settled("lift F1") { c.selectTool(ToolId.TRANSFORM) }
        tool.snapToObjects = false
        if (!tool.hasPendingWork) settled("start") { tool.start() }
        assertEquals(TransformTool.Lifted.FOLDER, tool.lifted)
        assertEquals(TransformLabels17.ONE_LAYER, tool.modeRefusal(TransformTool.Mode.MESH))
        tool.mode = TransformTool.Mode.MESH
        assertEquals("refused", TransformTool.Mode.FREE, tool.mode)
        settled("discarded") { tool.discard(); c.selectTool(ToolId.LASSO) }
        assertEquals("no step", 0, c.undoManager.undoCount)
        assertEquals("no change", before, picture(c))
        assertFoldersIntact(c, "free deform refused")

        // B (in the isolated F2, inside F1): a mesh vertex dragged, applied as ONE "Free deform" step.
        c.selectLayer(c.byName("B"))
        val changed = oneStep(c, "free deform", TransformTool.FREE_DEFORM_LABEL) {
            c.selectTool(ToolId.TRANSFORM)
            shadowOf(Looper.getMainLooper()).idle()
            if (!tool.hasPendingWork) tool.start()
            shadowOf(Looper.getMainLooper()).idle()
            assertEquals(TransformTool.Lifted.PIXELS, tool.lifted)
            tool.mode = TransformTool.Mode.MESH
            assertEquals(TransformTool.Mode.MESH, tool.mode)
            tool.setMeshCells(2, 2)
            val v = tool.pointAt(4)
            assertEquals("the centre vertex", 45f, v.x, 0.5f)
            c.drag(v.x to v.y, v.x + 4f to v.y + 2f, v.x + 8f to v.y + 4f)
            assertTrue(tool.isMeshChanged)
            tool.commit()
            c.selectTool(ToolId.LASSO)
        }
        assertEquals("only B", listOf("B"), changed)
    }

    @Test
    fun savedSelectionsFollowACanvasRotateFlipAndResizeWithFoldersPresent() {
        val ops = listOf<Triple<String, String, (EditorController) -> Boolean>>(
            Triple("rotate", CanvasRotation.CW_90.label) { c -> CanvasOps.applyRotate(c, CanvasRotation.CW_90) },
            Triple("flip", "Flip canvas horizontally") { c -> CanvasOps.applyFlip(c, horizontal = true) },
            Triple("resize", "Resize image") { c -> CanvasOps.applyResizeImage(c, w * 2, h * 2, c.doc.dpi, Resample.BILINEAR) },
        )
        for ((what, label, op) in ops) {
            val c = nested()
            val shapes = listOf(
                rectSelection(Rect(4, 6, 30, 20)),
                Selection.fromPath(Path().apply { addCircle(60f, 40f, 12.5f, Path.Direction.CW) }, w, h, antiAlias = true),
            )
            for (s in shapes) settled("$what: save") { c.setSelection(s, recordUndo = false); assertTrue(c.saveSelection()) }
            c.setSelection(null, recordUndo = false)
            val saved = c.doc.savedSelections
            assertEquals(2, saved.size)
            val masks = saved.map { bytes(it.toSelection(w, h)) }

            oneStep(c, what, label) { assertTrue(op(c)) }
            val now = c.doc.savedSelections
            assertEquals("$what: every saved selection is kept", saved.map { it.id }, now.map { it.id })
            val nw = c.doc.width
            val nh = c.doc.height
            for (i in saved.indices) {
                val got = bytes(now[i].toSelection(nw, nh))
                when (what) {
                    "rotate" -> {
                        // A quarter turn clockwise: new (x, y) is old (y, h - 1 - x).
                        val expected = ByteArray(nw * nh) { k -> masks[i][(h - 1 - k % nw) * w + k / nw] }
                        assertTrue("saved selection $i is turned", expected.contentEquals(got))
                    }
                    "flip" -> {
                        val expected = ByteArray(nw * nh) { k -> masks[i][(k / w) * w + (w - 1 - k % w)] }
                        assertTrue("saved selection $i is flipped", expected.contentEquals(got))
                    }
                    else -> {
                        val b = saved[i].bounds
                        val e = Rect(b.left * 2, b.top * 2, b.right * 2, b.bottom * 2)
                        val g = now[i].bounds
                        for ((x, y) in listOf(e.left to g.left, e.top to g.top, e.right to g.right, e.bottom to g.bottom)) {
                            assertTrue("saved selection $i is scaled: $g for $e", abs(x - y) <= 2)
                        }
                    }
                }
            }
            settled("$what undone") { c.undo() }
            assertSame("$what: one undo restores the very list", saved, c.doc.savedSelections)
            settled("$what redone") { c.redo() }
            assertEquals(now.map { it.id }, c.doc.savedSelections.map { it.id })
            for (i in now.indices) assertTrue("$what redone: $i", bytes(now[i].toSelection(nw, nh)).contentEquals(bytes(c.doc.savedSelections[i].toSelection(nw, nh))))
            assertFoldersIntact(c, "$what redone")
        }
    }

    @Test
    fun pathfinderOnShapeLayersInsideTheFoldersIsOneStepAndTheResultStaysInTheTree() {
        val c = nested()
        fun shapeLayer(above: String, name: String, r: RectF, color: Int): Layer {
            c.selectLayer(c.byName(above))
            val o = ShapeObject(ShapeType.RECTANGLE, cx = r.centerX(), cy = r.centerY(), w = r.width(), h = r.height(), style = ShapeStyle.FILL, fillColor = color)
            return c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        }
        // S1 in F1 (above A), S2 in the isolated F2 (above B): S2 is the top operand.
        val s1 = shapeLayer("A", "S1", RectF(8f, 36f, 30f, 58f), red)
        val s2 = shapeLayer("B", "S2", RectF(22f, 44f, 50f, 62f), blue)
        val f1 = c.byName("F1")
        val f2 = c.byName("F2")
        assertEquals(listOf(f1.id, f2.id), listOf(s1.parentId, s2.parentId))
        assertEquals(listOf("Background", "A", "S1", "B", "S2", "F2", "F1", "Top"), c.doc.layers.map { it.name })

        oneStep(c, "pathfinder unite", HistoryLabels.pathfinder("Unite")) {
            c.selectTool(ToolId.PATHFINDER)
            val t = (c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
            for ((x, y) in listOf(12f to 40f, 45f to 60f)) c.drag(x to y)
            assertEquals(listOf(s1, s2), t.operands.map { it.layer })
            t.apply(PathfinderOp.UNITE)
            c.selectTool(ToolId.LASSO)
        }
        val result = c.byName(PathfinderLabels.resultLayer(1))
        assertTrue(result.isVectorLayer)
        assertEquals("in the top operand's folder", f2.id, result.parentId)
        assertEquals("both operands went, the result took the top one's place", listOf("Background", "A", "B", result.name, "F2", "F1", "Top"), c.doc.layers.map { it.name })
        assertEquals(255, Color.alpha(result.bitmap.getPixel(12, 40)))
        assertEquals(255, Color.alpha(result.bitmap.getPixel(45, 60)))
        settled("pathfinder undone again") { c.undo() }
        assertSame(s1, c.byName("S1"))
        assertSame(s2, c.byName("S2"))
        assertEquals(listOf(f1.id, f2.id), listOf(s1.parentId, s2.parentId))
    }
}
