package com.brushwork.paint.v19.a6

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.RadialGradient
import android.graphics.Shader
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.live.StrokeBelowCache
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.9 A6 step 9 (P3, I7): a painting stroke's frames draw the layers below its layer from an
 * exact cache ([StrokeBelowCache]), and the canvas stays a full redraw of the document bit for
 * bit: at every frame while the finger moves and after the lift.
 *
 * Each case paints a wave across a 512 x 384 canvas whose display tiles are 128 px (4 x 3 tiles,
 * so the cache spans several tiles), rendering the canvas every second move as the view does, and
 * compares it then with a fresh render of the document with the stroke's override. Each case
 * also checks that the cache was used where it should be ([StrokeBelowCache.hits]), so an
 * exactness check can't pass by the cache staying off. P3 is turned on for these cases, shipped
 * or not.
 */
@RunWith(RobolectricTestRunner::class)
class StrokeBelowCacheTest {

    private val context: Context = ApplicationProvider.getApplicationContext()
    private val made = ArrayList<EditorController>()
    private val strokesWereOn = StrokeBelowCache.strokesOn
    private val eraserWasOn = StrokeBelowCache.eraserOn

    /** P3 is tested on, shipped or not ([StrokeBelowCache.FOR_STROKES]; see [asShippedTheSwitchesDecide]). */
    @Before
    fun setUp() {
        StrokeBelowCache.strokesOn = true
    }

    @After
    fun tearDown() {
        StrokeBelowCache.strokesOn = strokesWereOn
        StrokeBelowCache.eraserOn = eraserWasOn
        AdjustmentStage.safeCompositing = false
        made.forEach { runCatching { it.dispose() } }
    }

    private fun bitmap(draw: (Canvas) -> Unit = {}): Bitmap = BitmapUtils.createLayerBitmap(W, H).also { draw(Canvas(it)) }

    private fun circle(cx: Float, cy: Float, r: Float, color: Int): (Canvas) -> Unit = { cv ->
        cv.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color })
    }

    /** Layer [name] of [doc]: a translucent disc unless [draw] says otherwise. */
    private fun layer(doc: Document, name: String, draw: (Canvas) -> Unit = circle(120f + name.length * 37f % 300f, 190f, 120f, 0x90306090.toInt() + name.length * 0x1F2D), block: (Layer) -> Unit = {}): Layer =
        Layer(doc.newLayerId(), name, bitmap(draw)).also(block)

    private fun background(doc: Document): Layer = layer(doc, "Background", { cv ->
        cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
            shader = LinearGradient(0f, 0f, W.toFloat(), H.toFloat(), 0xFFF0E0B0.toInt(), 0xFF3050A0.toInt(), Shader.TileMode.CLAMP)
        })
    })

    /**
     * An editor on [layers] (built on a new document; [active] is painted) whose display tiles are
     * 128 px. The brush is a 30 px hard round.
     */
    private fun editor(active: String, colorMode: ColorMode = ColorMode.RGB, layers: (Document) -> List<Layer>): EditorController {
        val doc = Document("t", "t", W, H)
        doc.colorMode = colorMode
        doc.layers += layers(doc)
        doc.activeLayerIndex = doc.layers.indexOfFirst { it.name == active }
        val c = EditorController(context, doc, CoroutineScope(Dispatchers.Unconfined), AppSettings(context))
        made += c
        EditorController::class.java.getDeclaredField("tiles").apply { isAccessible = true }.set(c, DisplayTiles(W, H, TILE))
        c.invalidateDoc(null)
        c.tiles.update(c.compositor, null)
        c.tools
        c.color = 0xFFC03060.toInt()
        c.brush = BrushLibrary.byId("hardround")!!.copy(size = 30f, pressureSize = false)
        c.eraser = BrushLibrary.byId("softeraser")!!.copy(size = 50f)
        return c
    }

    /** A stack with clipping, blend modes, a mask and an adjustment layer below and above "Paint". */
    private fun rich(doc: Document): List<Layer> = listOf(
        background(doc),
        layer(doc, "Multiply") { it.blendMode = LayerBlendMode.MULTIPLY; it.opacity = 0.8f },
        layer(doc, "Clipped", circle(300f, 120f, 150f, 0xB0E04010.toInt())) { it.clipping = true },
        layer(doc, "Masked", circle(380f, 260f, 140f, 0xC020A060.toInt())) { l ->
            l.mask = bitmap { cv ->
                cv.drawRect(0f, 0f, W.toFloat(), H.toFloat(), Paint().apply {
                    shader = RadialGradient(256f, 192f, 260f, -1, 0xFF000000.toInt(), Shader.TileMode.CLAMP)
                })
            }
        },
        layer(doc, "Invert", {}) { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.5f },
        layer(doc, "Paint", circle(200f, 200f, 100f, 0x8040C060.toInt())),
        layer(doc, "Screen", circle(420f, 100f, 110f, 0x70D0D020)) { it.blendMode = LayerBlendMode.SCREEN; it.opacity = 0.7f },
        layer(doc, "Top", circle(100f, 300f, 90f, 0x60D04020)),
    )

    private fun DisplayTiles.pixels(): IntArray {
        val bmp = Bitmap.createBitmap(W, H, Bitmap.Config.ARGB_8888)
        draw(Canvas(bmp), null, smooth = false)
        return IntArray(W * H).also { bmp.getPixels(it, 0, W, 0, 0, W, H) }
    }

    /** The canvas equals a fresh render of the document (with the stroke's override), bit for bit. */
    private fun assertExact(c: EditorController, what: String) {
        val fresh = DisplayTiles(W, H).also { it.update(c.compositor, null) }.pixels()
        val shown = c.tiles.pixels()
        var diff = 0
        var first = -1
        for (i in fresh.indices) if (fresh[i] != shown[i]) { diff++; if (first < 0) first = i }
        val where = if (first < 0) "" else " first at (${first % W}, ${first / W}): ${Integer.toHexString(shown[first])} shown, ${Integer.toHexString(fresh[first])} drawn"
        assertEquals("$what: pixels unlike a full redraw$where", 0, diff)
    }

    private val wave: List<ToolPoint> = List(N) { k ->
        val t = k / (N - 1f)
        ToolPoint(30f + 450f * t, 192f + 130f * sin(t * 9f), 1f, k * 8L)
    }

    /**
     * Paints the wave with [id] on [c]: a frame (and a check) after every second move, [mid] after
     * each move (k = the move). Returns the cache's hits during the stroke.
     */
    private fun stroke(c: EditorController, id: ToolId, mid: (Int) -> Unit = {}): Int {
        val cache = c.liveAdjust.strokeBelow
        val before = cache.hits
        c.selectTool(id)
        c.pointerDown(wave[0])
        for (k in 1 until N - 1) {
            c.pointerMove(wave[k])
            mid(k)
            if (k % 2 == 0) {
                c.tiles.update(c.compositor, null)
                assertExact(c, "$id, frame after move $k")
                assertTrue("the cache holds at most its cap", cache.bytes <= cache.memoryCapBytes)
            }
        }
        val hits = cache.hits - before
        c.pointerUp(wave[N - 1])
        c.tiles.update(c.compositor, null)
        assertExact(c, "$id, after the lift")
        assertFalse("the cache goes with the lift", cache.isActive)
        assertEquals("nothing held after the lift", 0L, cache.bytes)
        assertNull(c.tiles.belowRenderer)
        return hits
    }

    private fun EditorController.split(): Int = liveAdjust.strokeBelow.splitFor(activeLayer)

    @Test
    fun aBrushStrokeOnAMiddleLayerIsDrawnFromTheCacheExactly() {
        val c = editor("Paint", layers = ::rich)
        assertEquals("split at the stroke's layer", c.doc.layers.indexOfFirst { it.name == "Paint" }, c.split())
        val builds = c.liveAdjust.strokeBelow.builds
        val hits = stroke(c, ToolId.BRUSH)
        assertTrue("frames were drawn from the cache ($hits)", hits > 10)
        assertTrue("the cache was built", c.liveAdjust.strokeBelow.builds > builds)
    }

    @Test
    fun everySplitPosition() {
        // Right above the background, and the top layer.
        for (name in listOf("Multiply", "Top")) {
            val c = editor(name) { d -> rich(d).map { l -> l.also { if (it.name == "Clipped") it.clipping = false } } }
            assertTrue("$name: drawn from the cache", stroke(c, ToolId.BRUSH) > 0)
        }
        // The bottom layer: nothing below, no cache, the same pixels.
        val bottom = editor("Background", layers = ::rich)
        assertEquals(0, stroke(bottom, ToolId.BRUSH))
    }

    @Test
    fun clippingGroups() {
        // A clipped layer splits at its group's base.
        val clipped = editor("Clipped", layers = ::rich)
        assertEquals(1, clipped.split())
        assertTrue(stroke(clipped, ToolId.BRUSH) > 0)
        // A clip base with layers clipped to it splits at itself.
        val base = editor("Paint") { d -> rich(d).also { ls -> ls.first { it.name == "Screen" }.clipping = true } }
        assertEquals(base.doc.layers.indexOfFirst { it.name == "Paint" }, base.split())
        assertTrue(stroke(base, ToolId.BRUSH) > 0)
        // A layer marked clipping right above an adjustment layer draws unclipped: it is a group of its own.
        val afterAdjust = editor("Paint") { d -> rich(d).also { ls -> ls.first { it.name == "Paint" }.clipping = true } }
        assertEquals(afterAdjust.doc.layers.indexOfFirst { it.name == "Paint" }, afterAdjust.split())
        assertTrue(stroke(afterAdjust, ToolId.BRUSH) > 0)
        // The base of a clipped stroke layer is the bottom layer: nothing below it to cache.
        val onBottom = editor("Second") { d -> listOf(background(d), layer(d, "Second") { it.clipping = true }, layer(d, "Third")) }
        assertEquals(0, onBottom.split())
        assertEquals(0, stroke(onBottom, ToolId.BRUSH))
    }

    @Test
    fun blendModesAndOpacityAboveAndBelow() {
        for (mode in listOf(LayerBlendMode.OVERLAY, LayerBlendMode.DIFFERENCE, LayerBlendMode.HUE, LayerBlendMode.ADD, LayerBlendMode.COLOR_BURN)) {
            val c = editor("Paint") { d ->
                rich(d).also { ls ->
                    ls.first { it.name == "Multiply" }.blendMode = mode
                    ls.first { it.name == "Screen" }.blendMode = mode
                    ls.first { it.name == "Paint" }.apply { blendMode = mode; opacity = 0.6f }
                }
            }
            assertTrue("$mode", stroke(c, ToolId.BRUSH) > 0)
        }
    }

    @Test
    fun adjustmentLayersAndMasks() {
        // The adjustment right below the stroke's layer is cached; one above is drawn live.
        val c = editor("Paint") { d -> rich(d) + layer(d, "Invert above", {}) { it.adjustment = AdjustmentSpec(filterId = "adjust.invert"); it.opacity = 0.4f } }
        assertTrue(stroke(c, ToolId.BRUSH) > 0)
        // Painting the layer's own mask.
        val m = editor("Paint", layers = ::rich)
        m.addMask(m.activeLayer, fromSelection = false)
        m.color = 0xFF000000.toInt()
        assertTrue(stroke(m, ToolId.BRUSH) > 0)
        // Painting an adjustment layer's mask: the split is the adjustment layer.
        val a = editor("Invert", layers = ::rich)
        a.addMask(a.activeLayer, fromSelection = false)
        a.color = 0xFF000000.toInt()
        assertEquals(a.doc.layers.indexOfFirst { it.name == "Invert" }, a.split())
        assertTrue(stroke(a, ToolId.BRUSH) > 0)
        // "Safe compositing" (adjustments draw nothing on the canvas) is part of the key. Set after
        // the editor starts: its Masks tool reads the saved setting when the tools are created.
        val s = editor("Paint", layers = ::rich)
        AdjustmentStage.safeCompositing = true
        s.invalidateDoc(null)
        s.tiles.update(s.compositor, null)
        assertTrue(stroke(s, ToolId.BRUSH) > 0)
    }

    @Test
    fun folders() {
        fun inFolder(spec: FolderSpec, opacity: Float = 1f): EditorController = editor("Paint") { d ->
            val ls = rich(d).toMutableList()
            val f = Layer.newFolder(d.newLayerId(), "Group", spec).also { it.opacity = opacity }
            val p = ls.indexOfFirst { it.name == "Paint" }
            ls[p].parentId = f.id
            ls[p - 1].parentId = f.id
            ls.add(p + 1, f)
            ls
        }
        // A pass-through folder at 100 % draws its children as is: the cache splits inside it.
        assertTrue(stroke(inFolder(FolderSpec(passThrough = true)), ToolId.BRUSH) > 0)
        // An isolated folder, or a pass-through one below 100 %: no cache, the same pixels.
        assertEquals(0, stroke(inFolder(FolderSpec(passThrough = false)), ToolId.BRUSH))
        assertEquals(0, stroke(inFolder(FolderSpec(passThrough = true), opacity = 0.6f), ToolId.BRUSH))
        // An isolated folder below the stroke's layer is cached whole.
        val below = editor("Paint") { d ->
            val ls = rich(d).toMutableList()
            val f = Layer.newFolder(d.newLayerId(), "Below", FolderSpec(passThrough = false)).also { it.opacity = 0.7f; it.blendMode = LayerBlendMode.MULTIPLY }
            ls[1].parentId = f.id
            ls[2].parentId = f.id
            ls.add(3, f)
            ls
        }
        assertTrue(stroke(below, ToolId.BRUSH) > 0)
    }

    @Test
    fun selectionSymmetryAndDirectStrokes() {
        val s = editor("Paint", layers = ::rich)
        s.setSelection(Selection.fromBytes(ByteArray(W * H) { i -> if ((i % W) in 100 until 400) -1 else 0 }, W, H))
        assertTrue(stroke(s, ToolId.BRUSH) > 0)
        val m = editor("Paint", layers = ::rich)
        m.updateSymmetry(SymmetrySettings(SymmetryType.MIRROR))
        assertTrue(stroke(m, ToolId.BRUSH) > 0)
        for (id in listOf(ToolId.SMUDGE, ToolId.BLUR)) assertTrue("$id", stroke(editor("Paint", layers = ::rich), id) > 0)
        // A grayscale document (the commit constrains the stroke; the frames are drawn alike).
        assertTrue(stroke(editor("Paint", ColorMode.GRAYSCALE, ::rich), ToolId.BRUSH) > 0)
    }

    @Test
    fun aChangeBelowDuringTheStrokeIsShownExactly() {
        val c = editor("Paint", layers = ::rich)
        val multiply = c.doc.layers.first { it.name == "Multiply" }
        val masked = c.doc.layers.first { it.name == "Masked" }
        val hits = stroke(c, ToolId.BRUSH) { k ->
            when (k) {
                // New pixels below, recorded (the key sees the content version).
                10 -> {
                    Canvas(multiply.bitmap).drawRect(200f, 100f, 330f, 300f, Paint().apply { color = 0xFF2080F0.toInt() })
                    multiply.markChanged()
                    c.invalidateDoc(android.graphics.Rect(200, 100, 330, 300))
                }
                // New pixels below, not recorded: only the invalidation tells (the cells there are dropped).
                20 -> {
                    Canvas(masked.bitmap).drawRect(300f, 50f, 420f, 360f, Paint().apply { color = 0xFFF0F020.toInt() })
                    c.invalidateDoc(android.graphics.Rect(300, 50, 420, 360))
                }
                // A property below.
                30 -> {
                    multiply.opacity = 0.35f
                    c.invalidateDoc(null)
                }
                40 -> {
                    masked.visible = false
                    c.invalidateDoc(null)
                }
            }
        }
        assertTrue(hits > 0)
    }

    @Test
    fun theMemoryCapEvictsTheOldestTileOrComposesAsBefore() {
        val c = editor("Paint", layers = ::rich)
        c.liveAdjust.strokeBelow.memoryCapBytes = 2L * TILE * TILE * 4
        assertTrue("two tiles: the oldest goes", stroke(c, ToolId.BRUSH) > 0)
        val none = editor("Paint", layers = ::rich)
        none.liveAdjust.strokeBelow.memoryCapBytes = 0L
        assertEquals("no room: as before", 0, stroke(none, ToolId.BRUSH))
    }

    @Test
    fun theRasterEraserComposesAsBeforeUntilR11a() {
        val c = editor("Paint", layers = ::rich)
        assertEquals(StrokeBelowCache.eraserOn, stroke(c, ToolId.ERASER) > 0)
    }

    /** As shipped each switch is its constant, and a stroke uses the cache only when it is on. */
    @Test
    fun asShippedTheSwitchesDecide() {
        assertEquals(StrokeBelowCache.FOR_STROKES, strokesWereOn)
        assertEquals(StrokeBelowCache.FOR_ERASER, eraserWasOn)
        StrokeBelowCache.strokesOn = StrokeBelowCache.FOR_STROKES
        assertEquals(StrokeBelowCache.FOR_STROKES, stroke(editor("Paint", layers = ::rich), ToolId.BRUSH) > 0)
        assertEquals(StrokeBelowCache.FOR_ERASER, stroke(editor("Paint", layers = ::rich), ToolId.ERASER) > 0)
    }

    @Test
    fun aCancelledStrokeDropsTheCacheAndTheSwitchTurnsItOff() {
        val c = editor("Paint", layers = ::rich)
        c.selectTool(ToolId.BRUSH)
        c.pointerDown(wave[0])
        for (k in 1 until 12) c.pointerMove(wave[k])
        c.tiles.update(c.compositor, null)
        assertTrue(c.liveAdjust.strokeBelow.isActive)
        assertTrue(c.liveAdjust.strokeBelow.bytes > 0L)
        c.pointerCancel()
        assertFalse(c.liveAdjust.strokeBelow.isActive)
        assertEquals(0L, c.liveAdjust.strokeBelow.bytes)
        c.tiles.update(c.compositor, null)
        assertExact(c, "after the cancel")
        // Off: every frame composites the whole stack, as v1.8.
        c.liveAdjust.strokeBelow.enabled = false
        assertEquals(0, stroke(c, ToolId.BRUSH))
    }

    private companion object {
        const val W = 512
        const val H = 384
        const val TILE = 128
        const val N = 64
    }
}
