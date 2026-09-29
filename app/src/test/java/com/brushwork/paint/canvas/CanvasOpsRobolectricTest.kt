package com.brushwork.paint.canvas

import android.graphics.Bitmap
import android.graphics.Rect
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CanvasGeometry
import com.brushwork.paint.engine.CanvasOpCancelledException
import com.brushwork.paint.engine.CanvasOpException
import com.brushwork.paint.engine.CanvasOps
import com.brushwork.paint.engine.CanvasRotation
import com.brushwork.paint.engine.CanvasSnapshot
import com.brushwork.paint.engine.Resample
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.Selection
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs

/** Canvas operations on real Skia bitmaps (Robolectric NATIVE graphics). */
@RunWith(RobolectricTestRunner::class)
class CanvasOpsRobolectricTest {

    private val red = 0xFFFF0000.toInt()
    private val blue = 0xFF0000FF.toInt()
    private val green = 0xFF00FF00.toInt()
    private val white = -1

    private fun bitmapOf(w: Int, h: Int, color: (Int, Int) -> Int): Bitmap {
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        val px = IntArray(w * h) { color(it % w, it / w) }
        bmp.setPixels(px, 0, w, 0, 0, w, h)
        return bmp
    }

    private fun docOf(w: Int, h: Int, vararg layers: Bitmap): Document {
        val doc = Document("t", "t", w, h, dpi = 300f)
        layers.forEachIndexed { i, b -> doc.layers += Layer(doc.newLayerId(), "L$i", b) }
        return doc
    }

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun channelNear(expected: Int, actual: Int, tol: Int, what: String) {
        for (shift in intArrayOf(24, 16, 8, 0)) {
            val e = (expected ushr shift) and 0xFF
            val a = (actual ushr shift) and 0xFF
            assertTrue("$what: expected ${Integer.toHexString(expected)} got ${Integer.toHexString(actual)}", abs(e - a) <= tol)
        }
    }

    /** Unique opaque color per pixel. */
    private fun code(x: Int, y: Int) = (0xFF shl 24) or ((x * 37 + 5) shl 16) or ((y * 53 + 7) shl 8) or 0x11

    // ------------------------------------------------------------------ resize image

    @Test
    fun resizeNearestScalesDimensionsAndContent() {
        val doc = docOf(4, 2, bitmapOf(4, 2) { x, _ -> if (x < 2) red else blue })
        val r = CanvasOps.resizeImage(CanvasSnapshot.of(doc), 8, 4, Resample.NEAREST)
        assertEquals(8, r.width); assertEquals(4, r.height)
        val b = r.layers[0].bitmap
        assertEquals(8, b.width); assertEquals(4, b.height)
        for (y in 0 until 4) for (x in 0 until 8) assertEquals("($x,$y)", if (x < 4) red else blue, b.getPixel(x, y))
        // Exact 2x blocks for pixel art
        val src = docOf(3, 3, bitmapOf(3, 3, ::code))
        val big = CanvasOps.resizeImage(CanvasSnapshot.of(src), 6, 6, Resample.NEAREST).layers[0].bitmap
        for (y in 0 until 6) for (x in 0 until 6) assertEquals(code(x / 2, y / 2), big.getPixel(x, y))
        // Geometry scales the guides
        assertEquals(CanvasGeometry.scale(2.0, 2.0), r.geometry)
    }

    @Test
    fun smoothResamplingKeepsFlatColorsAndAverages() {
        for (mode in listOf(Resample.BILINEAR, Resample.HIGH_QUALITY)) {
            val flat = docOf(16, 16, bitmapOf(16, 16) { _, _ -> 0xFF336699.toInt() })
            val out = CanvasOps.resizeImage(CanvasSnapshot.of(flat), 5, 7, mode).layers[0].bitmap
            assertEquals(5, out.width); assertEquals(7, out.height)
            pixels(out).forEach { channelNear(0xFF336699.toInt(), it, 1, "flat $mode") }

            // 1px checkerboard shrunk 21x (exercises the multi-step path): mid gray.
            val checker = docOf(64, 64, bitmapOf(64, 64) { x, y -> if ((x + y) % 2 == 0) white else 0xFF000000.toInt() })
            val small = CanvasOps.resizeImage(CanvasSnapshot.of(checker), 3, 3, mode).layers[0].bitmap
            pixels(small).forEach { p ->
                val g = (p shr 8) and 0xFF
                assertTrue("checker $mode gives $g", g in 110..145)
                assertEquals(255, p ushr 24)
            }

            // Enlarging keeps the corner colors.
            val quad = docOf(2, 2, bitmapOf(2, 2) { x, y -> if (x == 0 && y == 0) red else if (x == 1 && y == 1) blue else white })
            val up = CanvasOps.resizeImage(CanvasSnapshot.of(quad), 8, 8, mode).layers[0].bitmap
            // (Cubic interpolation may under/overshoot by a few levels next to a white neighbour.)
            channelNear(red, up.getPixel(0, 0), 8, "up $mode")
            channelNear(blue, up.getPixel(7, 7), 8, "up $mode")
            assertEquals(8, up.width)
        }
    }

    @Test
    fun smoothResamplingHasNoDarkFringes() {
        // White on the left, fully transparent (but "black") on the right.
        val doc = docOf(8, 4, bitmapOf(8, 4) { x, _ -> if (x < 4) white else 0x00000000 })
        for (mode in listOf(Resample.BILINEAR, Resample.HIGH_QUALITY)) {
            val out = CanvasOps.resizeImage(CanvasSnapshot.of(doc), 3, 2, mode).layers[0].bitmap
            var sawPartial = false
            for (p in pixels(out)) {
                val a = p ushr 24
                if (a == 0) continue
                if (a < 250) sawPartial = true
                assertTrue("fringe in $mode: ${Integer.toHexString(p)}", ((p shr 16) and 0xFF) >= 250)
            }
            assertTrue(sawPartial)
        }
    }

    @Test
    fun resizeResamplesMasksToo() {
        val doc = docOf(4, 4, bitmapOf(4, 4) { _, _ -> red })
        doc.layers[0].mask = bitmapOf(4, 4) { x, _ -> if (x < 2) 0xFF000000.toInt() else white }
        val r = CanvasOps.resizeImage(CanvasSnapshot.of(doc), 2, 8, Resample.NEAREST, dpi = 150f)
        val m = r.layers[0].mask!!
        assertEquals(2, m.width); assertEquals(8, m.height)
        assertEquals(0xFF000000.toInt(), m.getPixel(0, 5))
        assertEquals(white, m.getPixel(1, 5))
        assertEquals(150f, r.dpi)
    }

    // ------------------------------------------------------------------ canvas size

    @Test
    fun canvasSizeEveryAnchorPlacesImage() {
        val doc = docOf(4, 4, bitmapOf(4, 4) { x, y -> if (x == 0 && y == 0) red else if (x == 3 && y == 3) blue else 0 })
        val snap = CanvasSnapshot.of(doc)
        for (ay in 0..2) for (ax in 0..2) {
            val ox = CanvasGeometry.anchorOffset(4, 8, ax)
            val oy = CanvasGeometry.anchorOffset(4, 6, ay)
            assertEquals(ax * 2, ox)
            assertEquals(ay, oy)
            val r = CanvasOps.resizeCanvas(snap, 8, 6, ox, oy)
            val b = r.layers[0].bitmap
            assertEquals(8, b.width); assertEquals(6, b.height)
            assertEquals("anchor $ax,$ay", red, b.getPixel(ox, oy))
            assertEquals("anchor $ax,$ay", blue, b.getPixel(ox + 3, oy + 3))
            assertEquals(4 - 2, pixels(b).count { it != 0 })
        }
        // Shrinking from the center crops a 1 px border.
        val all = docOf(4, 4, bitmapOf(4, 4, ::code))
        val ox = CanvasGeometry.anchorOffset(4, 2, 1)
        assertEquals(-1, ox)
        val small = CanvasOps.resizeCanvas(CanvasSnapshot.of(all), 2, 2, ox, ox).layers[0].bitmap
        for (y in 0..1) for (x in 0..1) assertEquals(code(x + 1, y + 1), small.getPixel(x, y))
    }

    @Test
    fun canvasSizeFillsOnlyTheNewAreaOfTheBottomLayer() {
        val bottom = bitmapOf(2, 2) { x, _ -> if (x == 0) red else 0 }
        val top = bitmapOf(2, 2) { _, _ -> blue }
        val doc = docOf(2, 2, bottom, top)
        doc.layers[1].mask = bitmapOf(2, 2) { _, _ -> 0xFF000000.toInt() }
        val r = CanvasOps.resizeCanvas(CanvasSnapshot.of(doc), 4, 2, 1, 0, fillBottom = green)
        val b0 = r.layers[0].bitmap
        assertEquals(green, b0.getPixel(0, 0))
        assertEquals(red, b0.getPixel(1, 0))
        assertEquals(0, b0.getPixel(2, 0)) // old transparent pixel stays transparent
        assertEquals(green, b0.getPixel(3, 1))
        val b1 = r.layers[1].bitmap
        assertEquals(0, b1.getPixel(0, 0))
        assertEquals(blue, b1.getPixel(1, 0))
        val m1 = r.layers[1].mask!!
        assertEquals(white, m1.getPixel(0, 0)) // new mask area is visible
        assertEquals(0xFF000000.toInt(), m1.getPixel(1, 0))
    }

    @Test
    fun canvasSizeFillRespectsColorMode() {
        val doc = docOf(1, 1, bitmapOf(1, 1) { _, _ -> 0 })
        doc.colorMode = ColorMode.MONOCHROME
        val r = CanvasOps.resizeCanvas(CanvasSnapshot.of(doc), 2, 1, 0, 0, fillBottom = 0xFFDDDDDD.toInt())
        assertEquals(white, r.layers[0].bitmap.getPixel(1, 0))
    }

    // ------------------------------------------------------------------ trim / crop

    @Test
    fun trimUsesVisibleLayersOnly() {
        val a = bitmapOf(10, 10) { x, y -> if (x in 3..5 && y in 2..4) red else 0 }
        val hidden = bitmapOf(10, 10) { x, y -> if (x == 0 && y == 0) blue else 0 }
        val c = bitmapOf(10, 10) { x, y -> if (x == 8 && y == 7) 0x40FFFFFF else 0 }
        val doc = docOf(10, 10, a, hidden, c)
        doc.layers[1].visible = false
        val snap = CanvasSnapshot.of(doc)
        assertEquals(Rect(3, 2, 9, 8), CanvasOps.opaqueBounds(snap))
        val r = CanvasOps.trimTransparent(snap)
        assertEquals(6, r.width); assertEquals(6, r.height)
        assertEquals(red, r.layers[0].bitmap.getPixel(0, 0))
        assertEquals(0x40, r.layers[2].bitmap.getPixel(5, 5) ushr 24)
        assertEquals(CanvasGeometry.translate(-3.0, -2.0), r.geometry)
    }

    @Test
    fun trimRejectsEmptyAndFullCanvases() {
        val empty = docOf(5, 5, bitmapOf(5, 5) { _, _ -> 0 })
        try { CanvasOps.trimTransparent(CanvasSnapshot.of(empty)); fail() } catch (e: CanvasOpException) { assertNotNull(e.message) }
        val full = docOf(5, 5, bitmapOf(5, 5) { _, _ -> red })
        try { CanvasOps.trimTransparent(CanvasSnapshot.of(full)); fail() } catch (e: CanvasOpException) { assertNotNull(e.message) }
    }

    @Test
    fun cropToRectangleClampsToCanvas() {
        val doc = docOf(6, 6, bitmapOf(6, 6, ::code))
        val r = CanvasOps.cropTo(CanvasSnapshot.of(doc), Rect(4, 3, 20, 20))
        assertEquals(2, r.width); assertEquals(3, r.height)
        assertEquals(code(4, 3), r.layers[0].bitmap.getPixel(0, 0))
        assertEquals(code(5, 5), r.layers[0].bitmap.getPixel(1, 2))
    }

    // ------------------------------------------------------------------ rotate / flip

    @Test
    fun rotateMapsEveryPixel() {
        val w = 3; val h = 2
        val doc = docOf(w, h, bitmapOf(w, h, ::code))
        doc.layers[0].mask = bitmapOf(w, h) { x, y -> if (x == 2 && y == 0) white else 0xFF000000.toInt() }
        val snap = CanvasSnapshot.of(doc)
        val cw = CanvasOps.rotate(snap, CanvasRotation.CW_90)
        val ccw = CanvasOps.rotate(snap, CanvasRotation.CCW_90)
        val r180 = CanvasOps.rotate(snap, CanvasRotation.R_180)
        assertEquals(h, cw.width); assertEquals(w, cw.height)
        assertEquals(h, ccw.width); assertEquals(w, ccw.height)
        assertEquals(w, r180.width); assertEquals(h, r180.height)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(code(x, y), cw.layers[0].bitmap.getPixel(h - 1 - y, x))
            assertEquals(code(x, y), ccw.layers[0].bitmap.getPixel(y, w - 1 - x))
            assertEquals(code(x, y), r180.layers[0].bitmap.getPixel(w - 1 - x, h - 1 - y))
        }
        // The mask's white pixel at (2,0) follows the content.
        assertEquals(white, cw.layers[0].mask!!.getPixel(h - 1, 2))
        assertEquals(white, ccw.layers[0].mask!!.getPixel(0, 0))
        // Geometry agrees with the pixel mapping (pixel centers).
        val g = cw.geometry
        assertEquals(h - 1 + 0.5, g.mapX(0.5, 0.5), 1e-9)
        assertEquals(0.5, g.mapY(0.5, 0.5), 1e-9)
    }

    @Test
    fun flipMirrorsLayersAndMasks() {
        val w = 4; val h = 3
        val doc = docOf(w, h, bitmapOf(w, h, ::code))
        doc.layers[0].mask = bitmapOf(w, h) { x, _ -> if (x == 0) white else 0xFF000000.toInt() }
        val snap = CanvasSnapshot.of(doc)
        val fh = CanvasOps.flip(snap, horizontal = true)
        val fv = CanvasOps.flip(snap, horizontal = false)
        for (y in 0 until h) for (x in 0 until w) {
            assertEquals(code(x, y), fh.layers[0].bitmap.getPixel(w - 1 - x, y))
            assertEquals(code(x, y), fv.layers[0].bitmap.getPixel(x, h - 1 - y))
        }
        assertEquals(white, fh.layers[0].mask!!.getPixel(w - 1, 1))
        // Sources are untouched.
        assertEquals(code(0, 0), doc.layers[0].bitmap.getPixel(0, 0))
    }

    // ------------------------------------------------------------------ color mode

    @Test
    fun monochromeOutputsOnlyBlackWhiteAndFullAlpha() {
        val w = 64; val h = 16
        val doc = docOf(w, h, bitmapOf(w, h) { x, y ->
            val v = x * 4
            val a = if (y == 15) 100 else 255
            (a shl 24) or (v shl 16) or (v shl 8) or v
        })
        doc.layers[0].mask = bitmapOf(w, h) { _, _ -> 0xFF808080.toInt() }
        for (dither in listOf(false, true)) {
            val r = CanvasOps.convertColorMode(CanvasSnapshot.of(doc), ColorMode.MONOCHROME, 128, dither)
            assertEquals(ColorMode.MONOCHROME, r.colorMode)
            val px = pixels(r.layers[0].bitmap)
            for (p in px) {
                for (shift in intArrayOf(24, 16, 8, 0)) {
                    val ch = (p ushr shift) and 0xFF
                    assertTrue("dither=$dither ${Integer.toHexString(p)}", ch == 0 || ch == 255)
                }
            }
            // Row 15 had alpha 100 -> transparent.
            for (x in 0 until w) assertEquals(0, px[15 * w + x])
            // Masks are not converted.
            assertSame(doc.layers[0].mask, r.layers[0].mask)
            if (dither) {
                // Error diffusion keeps the average brightness of each column band.
                val leftWhite = (0 until 15).sumOf { y -> (0 until 16).count { x -> px[y * w + x] == white } }
                val rightWhite = (0 until 15).sumOf { y -> (48 until 64).count { x -> px[y * w + x] == white } }
                assertTrue(leftWhite in 1 until rightWhite)
                assertTrue(rightWhite < 15 * 16)
            } else {
                assertEquals(0xFF000000.toInt(), px[31]) // lum 124 < 128
                assertEquals(white, px[32])               // lum 128
            }
        }
        // Threshold moves the cut.
        val low = CanvasOps.convertColorMode(CanvasSnapshot.of(doc), ColorMode.MONOCHROME, 90, false)
        assertEquals(white, low.layers[0].bitmap.getPixel(25, 0)) // lum 100 >= 90
    }

    @Test
    fun grayscaleKeepsAlphaAndUsesLuminance() {
        val doc = docOf(2, 1, bitmapOf(2, 1) { x, _ -> if (x == 0) 0xC8FF0000.toInt() else 0 })
        val r = CanvasOps.convertColorMode(CanvasSnapshot.of(doc), ColorMode.GRAYSCALE)
        val p = r.layers[0].bitmap.getPixel(0, 0)
        channelNear(0xC84C4C4C.toInt(), p, 2, "gray")
        assertEquals(0, r.layers[0].bitmap.getPixel(1, 0))
        // Back to RGB only changes the mode.
        val gray = docOf(1, 1, bitmapOf(1, 1) { _, _ -> red }).also { it.colorMode = ColorMode.GRAYSCALE }
        val back = CanvasOps.convertColorMode(CanvasSnapshot.of(gray), ColorMode.RGB)
        assertTrue(back.layers.isEmpty())
        assertEquals(ColorMode.RGB, back.colorMode)
    }

    // ------------------------------------------------------------------ undo / redo

    private fun controllerFor(doc: Document, job: Job = SupervisorJob()): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<android.app.Application>()
        return EditorController(ctx, doc, CoroutineScope(Dispatchers.Unconfined + job), AppSettings(ctx))
    }

    @Test
    fun undoRedoRestoresSizesPixelsAndGuides() {
        val doc = docOf(4, 3, bitmapOf(4, 3, ::code), bitmapOf(4, 3) { _, _ -> 0 })
        doc.layers[1].mask = bitmapOf(4, 3) { _, _ -> white }
        val c = controllerFor(doc)
        c.updateRuler(c.ruler.copy(centerX = 1f, centerY = 2f, angleDeg = 0f))
        c.updateGrid(c.grid.copy(spacingPx = 10f))
        val before = pixels(doc.layers[0].bitmap)
        val beforeBitmap = doc.layers[0].bitmap

        val snap = CanvasSnapshot.of(doc)
        val result = CanvasOps.rotate(snap, CanvasRotation.CW_90)
        val rotatedPixels = pixels(result.layers[0].bitmap)
        CanvasOps.commit(c, "Rotate", snap, result)
        assertEquals(3, doc.width); assertEquals(4, doc.height)
        assertEquals(3, doc.layers[0].bitmap.width)
        assertEquals(4, doc.layers[1].mask!!.height)
        // Ruler center (1,2) -> (H - y, x) = (1, 1); angle 0 -> 90.
        assertEquals(1f, c.ruler.centerX, 1e-4f); assertEquals(1f, c.ruler.centerY, 1e-4f)
        assertEquals(90f, c.ruler.angleDeg, 1e-3f)
        assertTrue(c.canUndo)

        assertTrue(c.undoManager.undo(c))
        assertEquals(4, doc.width); assertEquals(3, doc.height)
        assertSame(beforeBitmap, doc.layers[0].bitmap)
        assertTrue(before.contentEquals(pixels(doc.layers[0].bitmap)))
        assertEquals(1f, c.ruler.centerX, 1e-4f); assertEquals(2f, c.ruler.centerY, 1e-4f)
        assertEquals(0f, c.ruler.angleDeg, 1e-3f)
        assertEquals(10f, c.grid.spacingPx, 1e-4f)

        assertTrue(c.undoManager.redo(c))
        assertEquals(3, doc.width); assertEquals(4, doc.height)
        assertTrue(rotatedPixels.contentEquals(pixels(doc.layers[0].bitmap)))
    }

    @Test
    fun resizeUndoAndMetadataActions() {
        val doc = docOf(4, 4, bitmapOf(4, 4, ::code))
        val c = controllerFor(doc)
        c.updateRuler(c.ruler.copy(centerX = 2f, centerY = 1f, radius = 10f))
        val snap = CanvasSnapshot.of(doc)
        CanvasOps.commit(c, "Resize image", snap, CanvasOps.resizeImage(snap, 8, 12, Resample.NEAREST, dpi = 600f))
        assertEquals(8, doc.width); assertEquals(12, doc.height); assertEquals(600f, doc.dpi)
        assertEquals(4f, c.ruler.centerX, 1e-4f); assertEquals(3f, c.ruler.centerY, 1e-4f)
        c.undoManager.undo(c)
        assertEquals(4, doc.width); assertEquals(300f, doc.dpi)
        assertEquals(2f, c.ruler.centerX, 1e-4f); assertEquals(10f, c.ruler.radius, 1e-3f)

        // Resolution only: no bitmap changes, selection kept.
        c.setSelection(Selection.all(4, 4), recordUndo = false)
        val s2 = CanvasSnapshot.of(doc)
        val bmp = doc.layers[0].bitmap
        CanvasOps.commit(c, "Resolution", s2, CanvasOps.setDpi(s2, 72f))
        assertEquals(72f, doc.dpi)
        assertSame(bmp, doc.layers[0].bitmap)
        assertNotNull(c.selection)
        c.undoManager.undo(c)
        assertEquals(300f, doc.dpi)

        // Color mode conversion keeps the selection and restores pixels on undo.
        val s3 = CanvasSnapshot.of(doc)
        CanvasOps.commit(c, "Color mode", s3, CanvasOps.convertColorMode(s3, ColorMode.GRAYSCALE))
        assertEquals(ColorMode.GRAYSCALE, doc.colorMode)
        assertNotNull(c.selection)
        val p = doc.layers[0].bitmap.getPixel(1, 1)
        assertEquals((p shr 16) and 0xFF, p and 0xFF)
        c.undoManager.undo(c)
        assertEquals(ColorMode.RGB, doc.colorMode)
        assertEquals(code(1, 1), doc.layers[0].bitmap.getPixel(1, 1))
        assertNotNull(c.selection)
    }

    @Test
    fun snapshotDetectsDocumentChanges() {
        val doc = docOf(2, 2, bitmapOf(2, 2) { _, _ -> red })
        val snap = CanvasSnapshot.of(doc)
        assertTrue(snap.matches(doc))
        doc.layers[0].bitmap = bitmapOf(2, 2) { _, _ -> blue }
        assertFalse(snap.matches(doc))
    }

    @Test
    fun snapshotDetectsPixelEditsAndMetadataChanges() {
        val doc = docOf(2, 2, bitmapOf(2, 2) { _, _ -> red })
        val snap = CanvasSnapshot.of(doc)
        doc.layers[0].markChanged() // e.g. a stroke committed while the operation ran
        assertFalse(snap.matches(doc))
        val snap2 = CanvasSnapshot.of(doc)
        assertTrue(snap2.matches(doc))
        doc.dpi = 72f
        assertFalse(snap2.matches(doc))
    }

    @Test
    fun smoothResizeOfMonochromeStaysOneBit() {
        val black = 0xFF000000.toInt()
        val doc = docOf(9, 9, bitmapOf(9, 9) { x, y -> if (x == 4) 0 else if ((x + y) % 2 == 0) white else black })
        doc.colorMode = ColorMode.MONOCHROME
        doc.layers[0].mask = bitmapOf(9, 9) { x, _ -> if (x < 4) white else black }
        for (mode in listOf(Resample.BILINEAR, Resample.HIGH_QUALITY)) {
            val r = CanvasOps.resizeImage(CanvasSnapshot.of(doc), 20, 13, mode)
            assertEquals(ColorMode.MONOCHROME, r.colorMode)
            for (p in pixels(r.layers[0].bitmap)) assertTrue("$mode ${Integer.toHexString(p)}", p == 0 || p == white || p == black)
            // Masks aren't layer pixels: they stay smooth.
            assertTrue(pixels(r.layers[0].mask!!).any { (it and 0xFF) in 1..254 })
        }
    }

    @Test
    fun progressIsMonotonicAndThrowingAbandonsTheOperation() {
        val doc = docOf(64, 64, bitmapOf(64, 64, ::code), bitmapOf(64, 64) { _, _ -> red })
        doc.layers[1].mask = bitmapOf(64, 64) { _, _ -> white }
        val seen = ArrayList<Float>()
        CanvasOps.resizeImage(CanvasSnapshot.of(doc), 200, 150, Resample.HIGH_QUALITY) { seen += it }
        assertEquals(0f, seen.first(), 0f)
        assertEquals(1f, seen.last(), 1e-6f)
        for (i in 1 until seen.size) assertTrue(seen[i] >= seen[i - 1])
        assertTrue(seen.size > 6) // reports inside each bitmap, not only per layer

        var calls = 0
        try {
            CanvasOps.resizeImage(CanvasSnapshot.of(doc), 200, 150, Resample.BILINEAR) { if (++calls == 3) throw CanvasOpCancelledException() }
            fail("expected the operation to stop")
        } catch (e: CanvasOpCancelledException) {
            assertEquals(3, calls)
        }
        assertEquals(64, doc.layers[0].bitmap.width)
        assertEquals(code(5, 5), doc.layers[0].bitmap.getPixel(5, 5))
    }

    @Test
    fun undoRestoresSelectionAndExactGuides() {
        val doc = docOf(10, 8, bitmapOf(10, 8, ::code))
        val c = controllerFor(doc)
        c.updateRuler(c.ruler.copy(centerX = 9f, centerY = 7f, angleDeg = 30f))
        val rulerBefore = c.ruler
        val gridBefore = c.grid
        val sel = Selection.fromBytes(ByteArray(80) { i -> if (i % 10 in 2..5 && i / 10 in 1..3) -1 else 0 }, 10, 8)
        c.setSelection(sel, recordUndo = false)
        val snap = CanvasSnapshot.of(doc)
        CanvasOps.commit(c, "Crop to selection", snap, CanvasOps.cropTo(snap, sel.bounds))
        assertEquals(4, doc.width); assertEquals(3, doc.height)
        assertEquals(code(2, 1), doc.layers[0].bitmap.getPixel(0, 0))
        assertEquals(null, c.selection)
        // The ruler center (9,7) - (2,1) = (7,6) is clamped to the new canvas and saved with it.
        assertEquals(4f, c.ruler.centerX, 1e-4f); assertEquals(3f, c.ruler.centerY, 1e-4f)
        assertEquals(c.ruler, doc.ruler)

        c.undoManager.undo(c)
        assertEquals(10, doc.width); assertEquals(8, doc.height)
        assertSame(sel, c.selection)
        // Exactly the old ruler, not the clamped center mapped back.
        assertEquals(rulerBefore, c.ruler)
        assertEquals(rulerBefore, doc.ruler)
        assertEquals(gridBefore, c.grid)

        c.undoManager.redo(c)
        assertEquals(4, doc.width)
        assertEquals(null, c.selection)
        assertEquals(4f, c.ruler.centerX, 1e-4f)
        assertEquals(code(2, 1), doc.layers[0].bitmap.getPixel(0, 0))
    }

    @Test
    fun metadataChangesApplyImmediately() {
        val doc = docOf(3, 3, bitmapOf(3, 3, ::code))
        val c = controllerFor(doc)
        assertTrue(CanvasOps.applyDpi(c, 72f))
        assertEquals(72f, doc.dpi)
        assertEquals(null, c.busyMessage)
        assertFalse(CanvasOps.applyDpi(c, 72f))
        c.undo()
        assertEquals(300f, doc.dpi)

        doc.colorMode = ColorMode.GRAYSCALE
        val bmp = doc.layers[0].bitmap
        assertTrue(CanvasOps.applyColorMode(c, ColorMode.RGB, 128, false))
        assertEquals(ColorMode.RGB, doc.colorMode)
        assertSame(bmp, doc.layers[0].bitmap)
        c.undo()
        assertEquals(ColorMode.GRAYSCALE, doc.colorMode)
    }

    private fun awaitIdle(scopeJob: Job) = runBlocking {
        withTimeout(60_000) {
            while (true) {
                val active = scopeJob.children.filter { it.isActive }.toList()
                if (active.isEmpty()) break
                active.forEach { it.join() }
            }
        }
    }

    @Test
    fun backgroundRunCommitsOneUndoStep() {
        val doc = docOf(5, 3, bitmapOf(5, 3, ::code))
        val job = SupervisorJob()
        val c = controllerFor(doc, job)
        assertTrue(CanvasOps.applyRotate(c, CanvasRotation.CW_90))
        awaitIdle(job)
        assertEquals(null, c.busyMessage)
        assertEquals(3, doc.width); assertEquals(5, doc.height)
        assertEquals(code(0, 0), doc.layers[0].bitmap.getPixel(2, 0))
        assertTrue(c.canUndo)
        c.undo()
        assertEquals(5, doc.width); assertEquals(3, doc.height)
        assertEquals(code(0, 0), doc.layers[0].bitmap.getPixel(0, 0))
        assertFalse(c.canUndo)
    }

    @Test
    fun stopAbandonsARunningOperation() {
        val n = 1200
        val doc = docOf(n, n, bitmapOf(n, n) { x, y -> if ((x / 8 + y / 8) % 2 == 0) red else blue })
        val original = doc.layers[0].bitmap
        val job = SupervisorJob()
        val c = controllerFor(doc, job)
        assertTrue(CanvasOps.applyResizeImage(c, 1100, 1100, 300f, Resample.HIGH_QUALITY))
        val stop = c.busyCancel
        assertNotNull(stop)
        stop!!.invoke()
        awaitIdle(job)
        assertEquals(n, doc.width)
        assertSame(original, doc.layers[0].bitmap)
        assertFalse(c.canUndo)
        assertTrue(c.message.orEmpty().startsWith("Stopped"))
        assertEquals(null, c.busyMessage)
    }

    @Test
    fun validationRejectsHugeSizes() {
        assertNotNull(CanvasOps.validateSize(10_001, 10, 1))
        assertNotNull(CanvasOps.validateSize(0, 10, 1))
        assertNotNull(CanvasOps.validateSize(1000, 1000, 10, budget = 10L * 1000 * 1000 * 4 - 1))
        assertEquals(null, CanvasOps.validateSize(1000, 1000, 10, budget = 10L * 1000 * 1000 * 4))
    }
}
