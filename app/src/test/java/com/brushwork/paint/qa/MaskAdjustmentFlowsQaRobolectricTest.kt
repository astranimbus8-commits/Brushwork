package com.brushwork.paint.qa

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.filters.FilterValues
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentLayerOps
import com.brushwork.paint.masks.BrushMask
import com.brushwork.paint.masks.LinearMask
import com.brushwork.paint.masks.MaskLayerOps
import com.brushwork.paint.masks.MaskMode
import com.brushwork.paint.masks.MaskSpec
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.masks.RadialMask
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.AdjustmentEdit
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.ui.filters.FilterSearch
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
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
import org.robolectric.Shadows.shadowOf
import java.io.File
import java.time.Duration
import kotlin.math.abs

/**
 * v1.5 final QA (masks, adjustment layers, filters, Tone): complete multi-step flows a user goes
 * through on the phone, driven through the real controller and tools (§7 checklist item 2): a
 * photo-like layer → Masks → components → Adjust → painting under the adjustment → editing mask
 * and effect again later → undo / redo granularity → filters through the mask → "As adjustment
 * layer" → merge down → save / reload → thumbnails → Safe compositing.
 */
@RunWith(RobolectricTestRunner::class)
class MaskAdjustmentFlowsQaRobolectricTest {
    private val w = 200
    private val h = 150
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private val app get() = RuntimeEnvironment.getApplication()

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    // ------------------------------------------------------------------ setup & helpers

    /** Background (white) + "Photo" (vertical stripes of two colors, opaque), Photo active, identity view. */
    private fun setup(id: String = "qa-masks"): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document(id, "QA", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also { paintPhoto(it.bitmap) }
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        return c
    }

    private fun paintPhoto(b: Bitmap) {
        val cv = Canvas(b)
        val p = Paint()
        var x = 0
        while (x < w) {
            p.color = if ((x / 4) % 2 == 0) STRIPE_A else STRIPE_B
            cv.drawRect(x.toFloat(), 0f, (x + 4).toFloat(), h.toFloat(), p)
            x += 4
        }
    }

    private fun photoAt(x: Int) = if ((x / 4) % 2 == 0) STRIPE_A else STRIPE_B

    private val tool get() = c.tools.getValue(ToolId.MASK) as MaskTool
    private val tone get() = FilterRegistry.byId("adjust.tone")!!

    private fun toneValues(exposure: Float): FilterValues = tone.defaultValues().set("exposure", exposure)

    private fun toneOf(color: Int, exposure: Float): Int {
        val px = intArrayOf(color)
        tone.pixelMapper(toneValues(exposure))!!.map(px, 0, 1)
        return px[0]
    }

    private fun drag(vararg pts: Pair<Float, Float>) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (p in pts.drop(1)) c.pointerMove(ToolPoint(p.first, p.second))
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 16) {
        c.pointerDown(ToolPoint(x0, y0))
        for (i in 1..n) c.pointerMove(ToolPoint(x0 + (x1 - x0) * i / n, y0 + (y1 - y0) * i / n))
        c.pointerUp(ToolPoint(x1, y1))
    }

    private fun tap(x: Float, y: Float) {
        c.pointerDown(ToolPoint(x, y)); c.pointerUp(ToolPoint(x, y))
    }

    private fun maskPixels(l: Layer) = IntArray(w * h).also { l.mask!!.getPixels(it, 0, w, 0, 0, w, h) }
    private fun rendered(spec: MaskSpec) = IntArray(w * h).also { MaskSpecs.render(spec, w, h, Rect(0, 0, w, h), it, w) }
    private fun pixels(b: Bitmap) = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** What the canvas shows: the display tiles brought up to date (live tool previews included). */
    private fun shown(): Bitmap {
        c.tiles.update(c.compositor, null)
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        c.tiles.draw(Canvas(out), null, smooth = false)
        return out
    }

    private fun near(want: Int, got: Int, tol: Int = 2): Boolean {
        for (sh in 0..24 step 8) if (abs((want shr sh and 0xFF) - (got shr sh and 0xFF)) > tol) return false
        return true
    }

    private fun assertNear(msg: String, want: Int, got: Int, tol: Int = 2) =
        assertTrue("$msg: want ${Integer.toHexString(want)}, got ${Integer.toHexString(got)}", near(want, got, tol))

    private fun hardBrush(color: Int) {
        c.brush = c.brush.copy(size = 10f, opacity = 1f, flow = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f, pressureSize = false, pressureOpacity = false, minSizeRatio = 1f)
        c.color = color
    }

    /** A radial component made with the Masks tool on the active pixel layer: a new "Tone 1". */
    private fun radialTone(): Layer {
        c.selectTool(ToolId.MASK)
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 110f to 75f, 140f to 75f)
        return c.activeLayer.also { assertTrue(it.isAdjustmentLayer) }
    }

    private fun pump(ms: Long = 200) {
        var left = ms
        while (left > 0) { shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20)); left -= 20 }
    }

    private fun pumpUntil(timeoutMs: Long = 20_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(android.os.Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    // ------------------------------------------------------------------ §7 item 2: the whole flow

    @Test
    fun radialToneThenPaintUnderneathThenEditMaskAndEffectLaterWithExactUndoSteps() {
        setup()
        val photo = c.activeLayer
        val before = c.undoManager.undoCount
        val adj = radialTone()
        assertEquals("Mask: radial", c.undoManager.undoLabel)
        assertEquals(before + 1, c.undoManager.undoCount)
        assertEquals(c.doc.indexOf(photo) + 1, c.doc.indexOf(adj))

        // Adjust: Exposure +1 previews at once (no step), only inside the radial.
        tool.openAdjust()
        assertTrue(tool.adjustOpen)
        val edit = tool.adjustmentEdit(adj)
        edit.preview(AdjustmentEffects.spec(tone, toneValues(1f)))
        assertEquals("live: no step", before + 1, c.undoManager.undoCount)
        var s = shown()
        assertNear("centre is adjusted at once", toneOf(photoAt(101), 1f), s.getPixel(101, 75))
        assertEquals("far outside the radial: untouched", STRIPE_A, s.getPixel(1, 5))
        tool.flushAdjustment()
        assertEquals(before + 2, c.undoManager.undoCount)
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)

        // Paint UNDER the adjustment: the live stroke is adjusted in the same frame.
        c.selectLayer(photo)
        c.selectTool(ToolId.BRUSH)
        hardBrush(GRAY)
        c.pointerDown(ToolPoint(90f, 75f))
        for (i in 1..10) c.pointerMove(ToolPoint(90f + i * 2f, 75f))
        s = shown()
        assertNear("live stroke under the adjustment is adjusted", toneOf(GRAY, 1f), s.getPixel(100, 75))
        c.pointerUp(ToolPoint(110f, 75f))
        assertEquals(before + 3, c.undoManager.undoCount)
        s = shown()
        assertNear("committed stroke is adjusted", toneOf(GRAY, 1f), s.getPixel(100, 75))
        assertNear("flattened (export) is adjusted too", toneOf(GRAY, 1f), c.compositor.renderFlattened().getPixel(100, 75))
        assertEquals("the photo itself holds the plain gray", GRAY, photo.bitmap.getPixel(100, 75))

        // Edit the mask again later: Tone 1, Masks, tap its pin, drag the side handle.
        c.selectLayer(adj)
        c.selectTool(ToolId.MASK)
        assertNull("nothing selected after coming back", tool.selectedId)
        tap(100f, 75f)
        assertNotNull("a tap on the R pin selects the radial", tool.selectedId)
        drag(140f to 75f, 150f to 75f, 160f to 75f)
        assertEquals("Edit mask", c.undoManager.undoLabel)
        assertEquals(before + 4, c.undoManager.undoCount)
        assertEquals(60f, (adj.maskSpec!!.components.single() as RadialMask).rx, 1e-3f)
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))

        // Edit the effect again later (layers menu "Edit adjustment").
        LayerOps.editAdjustment(c, adj)
        assertTrue(tool.adjustOpen)
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.spec(tone, toneValues(-1f)))
        tool.flushAdjustment()
        assertEquals(before + 5, c.undoManager.undoCount)
        assertNear("darker now", toneOf(GRAY, -1f), shown().getPixel(100, 75))

        // Undo, one user action at a time.
        val finalSpec = adj.maskSpec
        val finalAdj = adj.adjustment
        c.undo()
        assertNear("effect back to +1", toneOf(GRAY, 1f), shown().getPixel(100, 75))
        c.undo()
        assertEquals(40f, (adj.maskSpec!!.components.single() as RadialMask).rx, 1e-3f)
        assertArrayEquals("undo re-renders the mask", rendered(adj.maskSpec!!), maskPixels(adj))
        c.undo()
        assertEquals("the stroke is gone", photoAt(100), photo.bitmap.getPixel(100, 75))
        c.undo()
        assertTrue("Tone back to its defaults", AdjustmentEffects.isIdentity(adj.adjustment!!))
        c.undo()
        assertEquals(-1, c.doc.indexOf(adj))
        assertEquals(before, c.undoManager.undoCount)
        assertArrayEquals("the document is the photo again", pixels(Compositor(c.doc) { null }.renderFlattened()), pixels(c.compositor.renderFlattened()))
        // …and redo all of it.
        repeat(5) { c.redo() }
        assertEquals(finalSpec, adj.maskSpec)
        assertEquals(finalAdj, adj.adjustment)
        assertArrayEquals(rendered(finalSpec!!), maskPixels(adj))
        assertNear("redone", toneOf(GRAY, -1f), shown().getPixel(100, 75))
        assertNull(c.renderOverride)
    }

    @Test
    fun everyComponentEditIsOneStepAndTheMaskAlwaysMatchesItsSpec() {
        setup()
        c.selectTool(ToolId.MASK)
        val start = c.undoManager.undoCount
        tool.arm(MaskTool.Kind.LINEAR)
        drag(20f to 75f, 60f to 75f, 180f to 75f)
        val adj = c.activeLayer
        var n = 1
        fun check(label: String) {
            assertEquals("$label: one step", start + n, c.undoManager.undoCount)
            assertArrayEquals("$label: mask = spec", rendered(adj.maskSpec!!), maskPixels(adj))
            assertNull("$label: no preview left", c.renderOverride)
        }
        check("linear")
        // A subtracting radial.
        tool.select(null)
        tool.setMode(MaskMode.SUBTRACT)
        tool.arm(MaskTool.Kind.RADIAL)
        drag(60f to 75f, 70f to 75f, 90f to 75f)
        n++; check("subtract radial")
        assertEquals(MaskMode.SUBTRACT, adj.maskSpec!!.components.last().mode)
        // A brush part (ADD), then erase into it. (The mode chips act on the selected part: the
        // new radial is selected after it was made, so the mode for the next part is chosen with
        // nothing selected.)
        tool.select(null)
        tool.setMode(MaskMode.ADD)
        c.updatePreset(ToolId.MASK, c.maskBrush.copy(size = 20f, hardness = 0.8f, opacity = 1f))
        tool.arm(MaskTool.Kind.BRUSH)
        assertEquals(MaskMode.ADD, tool.newMode)
        c.pointerDown(ToolPoint(150f, 20f)); for (i in 1..10) c.pointerMove(ToolPoint(150f + i * 3f, 20f + i * 2f)); c.pointerUp(ToolPoint(180f, 40f))
        n++; check("brush")
        val brushId = tool.selectedId!!
        assertTrue(adj.maskSpec!!.components.first { it.id == brushId } is BrushMask)
        tool.brushErase = true
        stroke(160f, 30f, 175f, 35f)
        n++; check("erase")
        assertEquals(2, (adj.maskSpec!!.components.first { it.id == brushId } as BrushMask).strokes.size)
        tool.brushErase = false
        // Mode and invert of the selected part.
        tool.setMode(MaskMode.INTERSECT)
        n++; check("intersect")
        tool.toggleInvertSelected()
        n++; check("invert part")
        // Whole mask: invert, density (a slider drag = one step), start from visible.
        val spec0 = adj.maskSpec!!
        tool.commitSpec(spec0.copy(invert = true), "Invert mask")
        n++; check("invert mask")
        for (d in listOf(0.9f, 0.7f, 0.5f, 0.45f)) tool.previewSpec(adj.maskSpec!!.copy(density = d))
        assertEquals("a slider drag is live", start + n, c.undoManager.undoCount)
        tool.commitSpec(tool.displaySpec!!, "Mask density")
        n++; check("density")
        assertEquals(0.45f, adj.maskSpec!!.density, 0f)
        // Duplicate and delete parts.
        tool.duplicateComponent(adj.maskSpec!!.components.first().id)
        n++; check("duplicate")
        tool.deleteComponent(tool.selectedId!!)
        n++; check("delete")
        // Undo every step back to the photo alone; redo them all.
        val last = adj.maskSpec
        repeat(n) { c.undo() }
        assertEquals(-1, c.doc.indexOf(adj))
        assertEquals(start, c.undoManager.undoCount)
        repeat(n) { c.redo() }
        assertEquals(last, adj.maskSpec)
        assertArrayEquals(rendered(last!!), maskPixels(adj))
    }

    // ------------------------------------------------------------------ filters through the mask

    @Test
    fun applyAFilterThroughTheMaskBlursOnlyWhereTheGradientLetsItThrough() {
        setup()
        val photo = c.activeLayer
        c.selectTool(ToolId.MASK)
        tool.arm(MaskTool.Kind.LINEAR)
        // 100 % at x = 40, 0 % at x = 120.
        drag(40f to 75f, 80f to 75f, 120f to 75f)
        val adj = c.activeLayer
        val original = pixels(photo.bitmap)
        assertTrue(MaskLayerOps.prepareFilterThroughMask(c, adj))
        assertSame("the layer below is the one filtered", photo, c.activeLayer)
        val sel = c.selection!!
        assertEquals("selection = mask, full at the 100 % end", 255, sel.alphaAt(20, 75))
        assertEquals("selection = mask, empty at the 0 % end", 0, sel.alphaAt(150, 75))
        val mid = sel.alphaAt(80, 75)
        assertTrue("soft in between: $mid", mid in 60..200)
        val steps = c.undoManager.undoCount
        c.startFilter(FilterRegistry.byId("blur.gaussian")!!)
        val session = c.filterSession!!
        session.debounceMs = 0
        val radiusKey = session.filter.params.first().key
        session.update(radiusKey, 6f)
        session.apply()
        assertTrue("applied", pumpUntil { !session.isApplying && c.busyMessage == null })
        assertNull("the session closed", c.filterSession)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val after = pixels(photo.bitmap)
        for (x in 130 until w) for (y in 0 until h) assertEquals("unchanged where the mask is 0 ($x,$y)", original[y * w + x], after[y * w + x])
        var changedFull = 0
        for (x in 10 until 30) if (after[75 * w + x] != original[75 * w + x]) changedFull++
        assertTrue("blurred where the mask is 100 % ($changedFull)", changedFull >= 15)
        // The blur is lerped by the soft selection: less change mid-gradient than at the full end.
        fun change(x: Int) = (0..16 step 8).sumOf { sh -> abs((after[75 * w + x] shr sh and 0xFF) - (original[75 * w + x] shr sh and 0xFF)) }
        val full = (20..27).sumOf { change(it) }
        val half = (84..91).sumOf { change(it) }
        assertTrue("softer in the middle: full $full, half $half", half in 1 until full)
        c.undo()
        assertArrayEquals("undo restores the photo", original, pixels(photo.bitmap))
    }

    @Test
    fun filterThroughTheMaskWithALockedLayerBelowSaysSoUpFront() {
        setup()
        val photo = c.activeLayer
        val adj = radialTone()
        c.toggleLock(photo)
        val steps = c.undoManager.undoCount
        c.message = null
        val ok = MaskLayerOps.prepareFilterThroughMask(c, adj)
        assertFalse("nothing to filter: the layer below is locked", ok)
        assertTrue("says why: ${c.message}", c.message?.contains("locked") == true)
        assertNull("no selection left behind", c.selection)
        assertSame("still on the adjustment layer", adj, c.activeLayer)
        assertEquals(steps, c.undoManager.undoCount)
    }

    @Test
    fun asAdjustmentLayerWithoutAndWithAFeatheredSelection() {
        setup()
        val photo = c.activeLayer
        // Without a selection: the effect changes everything below, one step.
        c.startFilter(tone)
        var session = c.filterSession!!
        session.update("exposure", 1f)
        var steps = c.undoManager.undoCount
        val values = session.values.copy()
        session.cancel()
        val a1 = AdjustmentLayerOps.fromFilter(c, tone, values)!!
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(a1.mask)
        assertEquals(c.doc.indexOf(photo) + 1, c.doc.indexOf(a1))
        assertNear("everywhere", toneOf(STRIPE_B, 1f), c.compositor.renderFlattened().getPixel(5, 5))
        c.undo()
        assertEquals(-1, c.doc.indexOf(a1))

        // With a feathered selection: it becomes a soft mask, and the selection is dropped, one step.
        c.selectLayer(photo)
        val ramp = FloatArray(w * h) { i -> ((i % w) / (w - 1f)) }
        c.setSelection(Selection.fromFloats(ramp, w, h))
        c.startFilter(tone)
        session = c.filterSession!!
        session.update("exposure", 1f)
        steps = c.undoManager.undoCount
        val v2 = session.values.copy()
        session.cancel()
        val a2 = AdjustmentLayerOps.fromFilter(c, tone, v2)!!
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertNull("the selection became the mask", c.selection)
        val m = a2.mask!!
        val mid = m.getPixel(100, 75) and 0xFF
        assertTrue("soft mask in the middle: $mid", mid in 100..156)
        assertEquals(0, m.getPixel(0, 75) and 0xFF)
        assertTrue(m.getPixel(w - 1, 75) and 0xFF >= 250)
        assertEquals("a painted mask (no spec)", null, a2.maskSpec)
        c.undo()
        assertEquals(-1, c.doc.indexOf(a2))
        assertNotNull("undo brings the selection back", c.selection)
    }

    @Test
    fun asAdjustmentLayerIsRefusedForSettingsThatCantBeLive() {
        setup()
        val levels = FilterRegistry.byId("adjust.levels")!!
        val bw = FilterRegistry.byId("adjust.black_white")!!
        val layers = c.doc.layers.size
        val steps = c.undoManager.undoCount
        // "Auto levels" and a smoothed / anti-aliased Black & White look at the whole picture: a
        // layer made with them would silently show nothing.
        c.message = null
        assertNull(AdjustmentLayerOps.fromFilter(c, levels, levels.defaultValues().set("auto", true)))
        assertTrue("says why: ${c.message}", c.message?.contains("can't be live") == true)
        assertNull(AdjustmentLayerOps.fromFilter(c, bw, bw.defaultValues().set("smoothing", 2f)))
        assertNull(AdjustmentLayerOps.fromFilter(c, bw, bw.defaultValues().set("antialias", true)))
        assertEquals(layers, c.doc.layers.size)
        assertEquals(steps, c.undoManager.undoCount)
        // Manual levels are fine, and they do something.
        val l = AdjustmentLayerOps.fromFilter(c, levels, levels.defaultValues().set("gamma", 2f))
        assertNotNull(l)
        assertNotNull(AdjustmentEffects.mapperOf(l!!.adjustment!!))
    }

    // ------------------------------------------------------------------ merge down

    @Test
    fun mergeDownAppliesTheEffectToAMaskedHalfOpaqueLayerBelow() {
        setup()
        val photo = c.activeLayer
        // The photo has its own painted mask (left half hidden) and 50 % opacity.
        c.addMask(photo, fromSelection = false)
        Canvas(photo.mask!!).drawRect(0f, 0f, 50f, h.toFloat(), Paint().apply { color = 0xFF000000.toInt() })
        photo.editingMask = false
        c.setLayerProps(photo, photo.props().copy(opacity = 0.5f))
        val adj = radialTone()
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.spec(tone, toneValues(1.5f)))
        tool.flushAdjustment()
        val shownBefore = pixels(c.compositor.renderFlattened())
        // What the pair looks like on its own (the merged layer must look exactly like that).
        val pair = Document("p", "p", w, h)
        pair.layers += Layer(-1, "l", photo.bitmap).also { it.copyPropsFrom(photo.props()); it.mask = photo.mask }
        pair.layers += Layer(-2, "a", adj.bitmap).also { it.copyPropsFrom(adj.props()); it.mask = adj.mask; it.maskSpec = adj.maskSpec; it.adjustment = adj.adjustment }
        val pairPixels = pixels(Compositor(pair) { null }.renderFlattened())
        val steps = c.undoManager.undoCount
        LayerOps.mergeDown(c, adj)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertEquals(-1, c.doc.indexOf(adj))
        assertSame(photo, c.activeLayer)
        assertNull("the mask is baked in", photo.mask)
        assertEquals(1f, photo.opacity, 0f)
        assertNull(photo.adjustment)
        val merged = pixels(photo.bitmap)
        var worst = 0
        for (i in merged.indices) for (sh in 0..24 step 8) worst = maxOf(worst, abs((merged[i] shr sh and 0xFF) - (pairPixels[i] shr sh and 0xFF)))
        assertTrue("merged = the pair as shown (worst $worst)", worst <= 2)
        // Outside the radial the picture is the same. (Inside it, the effect now applies to the
        // half-transparent photo alone instead of the composite with the white background below:
        // as in Photoshop, merging an adjustment down changes what it applies to.)
        val shownAfter = pixels(c.compositor.renderFlattened())
        for (y in 0 until h) for (x in 0 until w) {
            if ((x - 100) * (x - 100) + (y - 75) * (y - 75) <= 42 * 42) continue
            assertTrue("outside the radial ($x,$y)", near(shownBefore[y * w + x], shownAfter[y * w + x], 1))
        }
        c.undo()
        assertNotNull(photo.mask)
        assertEquals(0.5f, photo.opacity, 0f)
        assertEquals(c.doc.indexOf(photo) + 1, c.doc.indexOf(adj))
        assertArrayEquals(rendered(adj.maskSpec!!), maskPixels(adj))
    }

    // ------------------------------------------------------------------ save / reload

    @Test
    fun saveAndReloadKeepSpecMaskAndEffectAndEditingGoesOn() = runBlocking {
        File(app.filesDir, "projects").deleteRecursively()
        setup("qa-save")
        val adj = radialTone()
        tool.select(null)
        tool.setMode(MaskMode.SUBTRACT)
        tool.arm(MaskTool.Kind.LINEAR)
        drag(100f to 20f, 100f to 40f, 100f to 60f)
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.spec(tone, toneValues(0.8f)), 0.75f)
        tool.flushAdjustment()
        val repo = ProjectRepository(app)
        val shownBefore = pixels(c.compositor.renderFlattened())
        val thumb = c.compositor.renderThumbnail(64)
        repo.save(c.doc, thumb)
        val dir = File(app.filesDir, "projects/qa-save")
        assertEquals("version 2 with an adjustment layer", 2, ProjectFormat.read(dir).formatVersion)
        val loaded = repo.load("qa-save")
        assertTrue(loaded.loadWarnings.isEmpty())
        val l2 = loaded.layers[2]
        assertEquals(adj.dataSnapshot(), l2.dataSnapshot())
        assertEquals(0.75f, l2.opacity, 0f)
        assertArrayEquals("the saved mask is its spec's rendering", rendered(l2.maskSpec!!), IntArray(w * h).also { l2.mask!!.getPixels(it, 0, w, 0, 0, w, h) })
        assertArrayEquals("the reloaded picture is the same", shownBefore, pixels(Compositor(loaded) { null }.renderFlattened()))
        // A thumbnail shows the effect (it differs from the document without the adjustment).
        val without = Document("x", "x", w, h).also { d -> d.layers += loaded.layers[0]; d.layers += loaded.layers[1] }
        assertFalse("the thumbnail includes the adjustment", pixels(Compositor(without) { null }.renderThumbnail(64)).contentEquals(pixels(thumb)))

        // Continue editing in a new editor: the mask and effect are still editable.
        c.dispose()
        val settings = AppSettings(app)
        c = EditorController(app, loaded, scope, settings).also { it.viewTransform.set(Matrix()) }
        c.selectLayer(l2)
        c.selectTool(ToolId.MASK)
        tap(100f, 75f)
        val sel = tool.selected
        assertTrue("the radial's pin is there after reload: $sel", sel is RadialMask)
        drag(140f to 75f, 150f to 75f, 160f to 75f)
        assertEquals("Edit mask", c.undoManager.undoLabel)
        assertArrayEquals(rendered(l2.maskSpec!!), IntArray(w * h).also { l2.mask!!.getPixels(it, 0, w, 0, 0, w, h) })
        tool.openAdjust()
        tool.adjustmentEdit(l2).preview(AdjustmentEffects.spec(tone, toneValues(-1f)))
        tool.flushAdjustment()
        assertEquals(AdjustmentEdit.LABEL, c.undoManager.undoLabel)
        // Without the adjustment layer the project is version 1 again (a v1.4 build can open it).
        c.deleteLayer(l2)
        repo.save(c.doc, null)
        assertEquals(1, ProjectFormat.read(dir).formatVersion)
    }

    // ------------------------------------------------------------------ Safe compositing

    @Test
    fun safeCompositingShowsAdjustmentsAsPassThroughOnTheCanvasOnly() {
        setup()
        val adj = radialTone()
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.spec(tone, toneValues(1f)))
        tool.flushAdjustment()
        assertNear("on", toneOf(photoAt(101), 1f), shown().getPixel(101, 75))
        AdjustmentStage.safeCompositing = true
        c.settings.safeCompositing = true
        c.invalidateDoc(null)
        assertEquals("canvas: pass-through", photoAt(101), shown().getPixel(101, 75))
        assertNear("export keeps the effect", toneOf(photoAt(101), 1f), c.compositor.renderFlattened().getPixel(101, 75))
        assertNear("thumbnail keeps the effect", toneOf(photoAt(101), 1f), c.compositor.renderThumbnail(w).getPixel(101, 75))
        // A new editor reads the switch from the settings.
        AdjustmentStage.safeCompositing = false
        val c2 = EditorController(app, c.doc, scope, c.settings)
        assertNotNull(c2.tools[ToolId.BRUSH])
        assertTrue(AdjustmentStage.safeCompositing)
        c2.dispose()
    }

    // ------------------------------------------------------------------ masks on normal layers

    @Test
    fun aGradientMaskOnANormalLayerBecomesAPixelMaskThatTheBrushPaints() {
        setup()
        val photo = c.activeLayer
        LayerOps.addGradientMask(c, photo)
        assertEquals(MaskTool.Target.ThisLayer, tool.target)
        drag(20f to 75f, 100f to 75f, 180f to 75f)
        assertSame(photo, c.activeLayer)
        val spec = photo.maskSpec!!
        assertArrayEquals(rendered(spec), maskPixels(photo))
        val steps = c.undoManager.undoCount
        // Convert to pixel mask: the pixels stay, the spec goes (one step).
        LayerOps.toPixelMask(c, photo)
        assertEquals(steps + 1, c.undoManager.undoCount)
        assertNull(photo.maskSpec)
        assertArrayEquals(rendered(spec), maskPixels(photo))
        // The brush paints the mask now ("Edit mask" in the layers window).
        LayerOps.editTarget(c, photo, mask = true)
        c.selectTool(ToolId.BRUSH)
        hardBrush(0xFF000000.toInt())
        stroke(20f, 75f, 40f, 75f)
        assertEquals(steps + 2, c.undoManager.undoCount)
        assertEquals("painted black into the mask", 0, photo.mask!!.getPixel(30, 75) and 0xFF)
        assertEquals("the pixels are untouched", photoAt(30), photo.bitmap.getPixel(30, 75))
        c.undo(); c.undo()
        assertEquals(spec, photo.maskSpec)
        assertArrayEquals(rendered(spec), maskPixels(photo))
    }

    @Test
    fun aBrushOnAnAdjustmentLayerPaintsItsMaskAndTheSpecComesBackOnUndo() {
        setup()
        val adj = radialTone()
        val spec = adj.maskSpec!!
        c.selectTool(ToolId.BRUSH)
        assertNull("brush allowed on an adjustment layer with a mask", LayerToolRules.refusal(ToolId.BRUSH, adj))
        hardBrush(-1)
        val steps = c.undoManager.undoCount
        stroke(10f, 10f, 40f, 10f)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        assertNull("the editable mask became a painted one", adj.maskSpec)
        assertTrue("told so: ${c.message}", c.message?.contains("painted mask") == true)
        assertEquals("painted white into the mask", 255, adj.mask!!.getPixel(25, 10) and 0xFF)
        assertTrue("the adjustment layer's own pixels stay empty", pixels(adj.bitmap).all { it == 0 })
        c.undo()
        assertEquals(spec, adj.maskSpec)
        assertArrayEquals(rendered(spec), maskPixels(adj))
        // Without a mask the brush is refused (it has no pixels).
        c.selectTool(ToolId.MASK)
        MaskEditsHelper.deleteMask(c, adj)
        c.selectTool(ToolId.BRUSH)
        val n = c.undoManager.undoCount
        stroke(10f, 10f, 40f, 10f)
        assertEquals(n, c.undoManager.undoCount)
        assertEquals(LayerToolRules.ADJUSTMENT_MESSAGE, c.message)
    }

    // ------------------------------------------------------------------ the layers window's mask page

    @Test
    fun applyMaskInTheLayersWindowNeverMakesAnAdjustmentGlobal() {
        setup()
        val adj = radialTone()
        tool.adjustmentEdit(adj).preview(AdjustmentEffects.spec(tone, toneValues(1f)))
        tool.flushAdjustment()
        val before = pixels(c.compositor.renderFlattened())
        val steps = c.undoManager.undoCount
        LayerOps.applyMask(c, adj)
        assertNotNull("the adjustment keeps its mask", adj.mask)
        assertNotNull("…and its editable spec", adj.maskSpec)
        assertEquals("no step", steps, c.undoManager.undoCount)
        assertArrayEquals("the picture is unchanged (the effect stays inside the radial)", before, pixels(c.compositor.renderFlattened()))
        assertTrue(pixels(adj.bitmap).all { it == 0 })
    }

    @Test
    fun invertMaskInTheLayersWindowKeepsAnEditableMaskEditable() {
        setup()
        val adj = radialTone()
        val spec = adj.maskSpec!!
        val steps = c.undoManager.undoCount
        LayerOps.invertMask(c, adj)
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val now = adj.maskSpec
        assertNotNull("still editable", now)
        assertTrue(now!!.invert)
        assertEquals(spec.components, now.components)
        assertArrayEquals(rendered(now), maskPixels(adj))
        c.undo()
        assertEquals(spec, adj.maskSpec)
        assertArrayEquals(rendered(spec), maskPixels(adj))
        // A painted mask still inverts its pixels.
        val photo = c.doc.layers[1]
        c.addMask(photo, fromSelection = false)
        LayerOps.invertMask(c, photo)
        assertEquals(0, photo.mask!!.getPixel(5, 5) and 0xFF)
    }

    @Test
    fun replacingAPaintedMaskWithAnEditableOneLeavesNothingOfThePaintedOne() {
        setup()
        val photo = c.activeLayer
        c.addMask(photo, fromSelection = false) // white: everything visible
        photo.editingMask = false
        c.selectTool(ToolId.MASK)
        tool.chooseTarget(MaskTool.Target.ThisLayer)
        assertSame(photo, tool.replacePrompt)
        tool.answerReplace(true)
        tool.arm(MaskTool.Kind.RADIAL)
        drag(100f to 75f, 120f to 75f, 130f to 75f)
        assertNotNull(photo.maskSpec)
        assertEquals("far away it's black now", 0xFF000000.toInt(), photo.mask!!.getPixel(2, 2))
        assertArrayEquals(rendered(photo.maskSpec!!), maskPixels(photo))
        c.undo()
        assertTrue("the painted (white) mask is back", maskPixels(photo).all { it == -1 })
        // The same with a first brush part.
        tool.chooseTarget(MaskTool.Target.ThisLayer)
        tool.answerReplace(true)
        tool.arm(MaskTool.Kind.BRUSH)
        stroke(20f, 20f, 60f, 20f)
        assertNotNull(photo.maskSpec)
        assertEquals(0xFF000000.toInt(), photo.mask!!.getPixel(150, 120))
        assertArrayEquals(rendered(photo.maskSpec!!), maskPixels(photo))
    }

    // ------------------------------------------------------------------ Tone in the Filters panel

    @Test
    fun toneAloneInTheFiltersPanelFoundByExposurePreviewedAppliedAndUndone() {
        setup()
        val photo = c.activeLayer
        val found = FilterSearch.search(FilterRegistry.all, "exposure")
        assertEquals("Tone is found by \"exposure\" first", "adjust.tone", found.first().id)
        assertTrue(FilterSearch.search(FilterRegistry.all, "shadows").any { it.id == "adjust.tone" })
        val original = pixels(photo.bitmap)
        c.startFilter(found.first())
        val session = c.filterSession!!
        session.debounceMs = 0
        session.update("exposure", 1f)
        session.update("shadows", 30f)
        assertTrue("previewed", pumpUntil { session.hasPreview && !session.isRendering })
        assertNotNull("the preview is shown through an override", c.renderOverride)
        assertArrayEquals("preview doesn't touch the pixels", original, pixels(photo.bitmap))
        val steps = c.undoManager.undoCount
        session.apply()
        assertTrue(pumpUntil { !session.isApplying && c.busyMessage == null })
        assertEquals(steps + 1, c.undoManager.undoCount)
        val want = original.copyOf().also { tone.pixelMapper(tone.defaultValues().set("exposure", 1f).set("shadows", 30f))!!.map(it, 0, it.size) }
        assertArrayEquals("applied = the mapper", want, pixels(photo.bitmap))
        c.undo()
        assertArrayEquals(original, pixels(photo.bitmap))
    }

    // ------------------------------------------------------------------ performance guards

    @Test
    fun anAdjustSliderDragCoalescesIntoOneRenderPerFrame() {
        setup()
        val adj = radialTone()
        c.tiles.update(c.compositor, null)
        assertFalse(c.tiles.hasDirty)
        val edit = tool.adjustmentEdit(adj)
        for (i in 1..30) edit.preview(AdjustmentEffects.spec(tone, toneValues(i / 30f)))
        assertTrue(c.tiles.hasDirty)
        assertTrue("one render for the newest value", c.tiles.update(c.compositor, null))
        assertFalse("nothing left to render", c.tiles.update(c.compositor, null))
        assertNear("the newest value shows", toneOf(photoAt(101), 1f), shown().getPixel(101, 75))
        // Only the effect's area was invalidated (the radial's bounds), not the whole canvas.
        val region = com.brushwork.paint.masks.MaskEdits.effectRegion(c, adj)!!
        assertTrue("effect region is the radial's: $region", region.width() <= 82 && region.height() <= 82)
        edit.flush()
        assertEquals("30 slider moves = one step", AdjustmentEdit.LABEL, c.undoManager.undoLabel)
    }

    @Test
    fun maskRendersOfAPhoneSizedCanvasStayWithinTheBudget() {
        // Pure renderer at the user's 1080 x 2408 (no bitmap): a linear + radial + brush spec.
        val pw = 1080; val ph = 2408
        val pts = com.brushwork.paint.core.PackedPoints(FloatArray(60) { 100f + it * 10f }, FloatArray(60) { 400f + it * 5f }, FloatArray(60) { 1f })
        val spec = MaskSpec(
            components = listOf(
                LinearMask(1, x0 = 0f, y0 = 0f, x1 = 0f, y1 = 2408f),
                RadialMask(2, mode = MaskMode.SUBTRACT, cx = 540f, cy = 1200f, rx = 400f, ry = 600f, feather = 0.6f),
                BrushMask(3, strokes = listOf(com.brushwork.paint.masks.MaskStroke(false, 80f, 0.5f, 0.8f, pts))),
            ),
            nextId = 4,
        )
        val band = IntArray(pw * 64)
        // Warm up, then time a full render.
        var y = 0
        while (y < ph) { val rows = minOf(64, ph - y); MaskSpecs.render(spec, pw, ph, Rect(0, y, pw, y + rows), band, pw); y += rows }
        val t0 = System.nanoTime()
        y = 0
        while (y < ph) { val rows = minOf(64, ph - y); MaskSpecs.render(spec, pw, ph, Rect(0, y, pw, y + rows), band, pw); y += rows }
        val ms = (System.nanoTime() - t0) / 1e6
        println("[qa] full mask render 1080x2408: ${"%.1f".format(ms)} ms")
        // The design budget is ~8 ms on the T606 with 4 threads; a loaded test machine gets 40x slack.
        assertTrue("full render took $ms ms", ms < 1500)
        assertTrue("the estimate stays below the tile fallback for this spec", MaskSpecs.estimateMillis(spec, Rect(0, 0, pw, ph)) < 150.0)
    }

    private object MaskEditsHelper {
        fun deleteMask(c: EditorController, l: Layer) = com.brushwork.paint.masks.MaskEdits.deleteMask(c, l)
    }

    private companion object {
        const val STRIPE_A = 0xFF3366AA.toInt()
        const val STRIPE_B = 0xFFDDAA44.toInt()
        const val GRAY = 0xFF808080.toInt()
    }
}
