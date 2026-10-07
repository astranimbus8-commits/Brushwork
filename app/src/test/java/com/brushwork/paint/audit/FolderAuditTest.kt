package com.brushwork.paint.audit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.graphics.RectF
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasResult
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.PayloadKind
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentHistogram
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.ExportFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.tools.select.SelectionEdits
import com.brushwork.paint.ui.layers.LayerOps
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

/**
 * v1.7 F2 (§3.8 d, foundation rows; risks R1, R1b, 22, 23, 30): whole-document operations on
 * nested folders (canvas operations and their undo, flatten, thumbnail, histogram, snapping,
 * export, save and load, a gallery export) never treat a folder as pixels and keep every
 * folder's `FOLDER_BITMAP` alive; with a folder active, every refused entry point leaves no step
 * and no change; the eyedropper reads the composite; a child of a locked folder behaves like a
 * locked layer for every tool, and a child of a hidden folder is not snapped to.
 */
@RunWith(RobolectricTestRunner::class)
class FolderAuditTest {
    private val job = SupervisorJob()
    private val scope = CoroutineScope(Dispatchers.Unconfined + job)

    @After
    fun tearDown() = scope.cancel()

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
    fun exportWritesTheTreeAndTheFoldersLayersFlat() {
        val c = nested()
        fun build(includeHidden: Boolean) = runBlocking {
            ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, includeHidden = includeHidden), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
        }
        val scene = build(includeHidden = false)
        assertEquals(listOf("Background", "A", "B", "Top"), scene.layers.map { it.name })
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
        assertEquals(listOf("Background", "A", "Top"), build(includeHidden = false).layers.map { it.name })
        val withHidden = build(includeHidden = true)
        assertTrue(withHidden.layers.first { it.name == "B" }.hidden)
        assertFalse(withHidden.layers.first { it.name == "A" }.hidden)
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
}
