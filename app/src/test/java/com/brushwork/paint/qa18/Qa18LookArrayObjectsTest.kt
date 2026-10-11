package com.brushwork.paint.qa18

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.RectF
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayInstances
import com.brushwork.paint.model.ArrayTint
import com.brushwork.paint.model.CopyLook
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.qa16.IbisShots
import com.brushwork.paint.qa17.Qa17ArrayUi
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.differing
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshRender
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.freshVector
import com.brushwork.paint.qa17.Qa17ArrayUi.Companion.pixels
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.array.ArrayTool
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.ArrayLabels18
import com.brushwork.paint.ui.common.V17Tags
import com.brushwork.paint.ui.common.V18Tags
import com.brushwork.paint.ui.editor.chrome.ChromeHarness
import com.brushwork.paint.ui.editor.chrome.ChromeTags
import com.brushwork.paint.ui.filters.GradientEditing
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
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
import java.io.File
import java.io.FileOutputStream
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.8 final QA, cluster "look" (item 2, design §3.3): the Array look on the three kinds of
 * object layers, each made the way the user makes it on the 392 dp phone: a text typed with the
 * Text tool, a rectangle dragged with the Shape tool, a path tapped out with the Path tool. Each
 * is arrayed from its layer ⋮ "Array…", Count 4 typed, then on the "Look" tab: End opacity 50,
 * End size 60, End turn 30 (typed), Pace "Slows down" (a finger), the "Sunset" tint (one tap).
 * Each change is ONE step "Array look". For each kind:
 * - the source keeps its data and its pixels (I19), its color exact;
 * - each copy shows the source's color tinted toward the stop at its place (ArrayTint), at the
 *   opacity of its place (the curve), the last copy smaller;
 * - the cache is a fresh render of the layer's data (I1);
 * - two-finger undo takes the tint back off (the same pixels as before it), three-finger redo
 *   puts it back.
 * Then: the text layer picked again in the layer window shows its look and is edited (End size
 * 80, one step) without touching the others; a save and reopen gives every spec, source and
 * pixel back; "Apply array" on each (the text asks "Apply turns the text into pixels" first) is
 * one step and keeps the look.
 * PNGs: `look-array-objects.png` (the document, 2x) and `look-array-objects-screen.png`.
 * One test, own sandbox (Compose's frame clock serves the first test of a sandbox only).
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa18.lookarrayobjectssandbox"])
class Qa18LookArrayObjectsTest {

    @Test
    fun textShapeAndPathArraysTakeALook() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog(limitMs = 240_000)
        val h = ChromeHarness()
        h.section("a text, a shape and a path, each arrayed with a look") { flow(Qa17ArrayUi(h)) }
        dog.interrupt()
        ArrayDraw.clearCaches()
        h.finish()
    }

    private lateinit var u: Qa17ArrayUi

    private val sunset: List<VStop> = GradientEditing.presets.first { it.first == SUNSET }.second.map { VStop(it.position, it.color) }

    /** Runs [block] and asserts it recorded ONE step [label]. */
    private fun oneStep(what: String, label: String = ArrayLabels18.HISTORY_LOOK, block: () -> Unit) {
        val n = u.steps()
        block()
        u.settleRenders(what)
        assertEquals("$what: one step", n + 1, u.steps())
        assertEquals(what, label, u.c.undoManager.undoLabel)
    }

    /** The cache as it must be (I1): a fresh render of [layer]'s data. */
    private fun fresh(layer: Layer): Bitmap =
        if (layer.dataSnapshot().vector != null) freshVector(u.c, layer.dataSnapshot()) else freshRender(u.c, layer.dataSnapshot())

    /** The source's box and each drawn instance of [layer]'s array. */
    private fun instances(layer: Layer) = ArrayInstances.of(layer.array!!.spec, requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot())) { "no source" })

    /**
     * The box (document px) copy [k] of [layer] covers: [content] (where the source paints, the
     * source's box when null) under its matrix (the look's size and turn are about the source
     * box's centre, which a symmetric margin keeps).
     */
    private fun copyBox(layer: Layer, k: Int, content: RectF? = null): Rect {
        val src = content ?: requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot()))
        val inst = instances(layer).first { it.k == k }
        val r = RectF(src)
        Matrix().apply { setValues(inst.matrix) }.mapRect(r)
        val out = Rect(r.left.toInt() - 1, r.top.toInt() - 1, r.right.toInt() + 2, r.bottom.toInt() + 2)
        assertTrue("copy $k inside the document: $out", out.left >= 0 && out.top >= 0 && out.right <= u.c.doc.width && out.bottom <= u.c.doc.height)
        return out
    }

    private fun lookOf(layer: Layer, k: Int): CopyLook = requireNotNull(instances(layer).first { it.k == k }.look) { "copy $k has a look" }

    /** What [b] shows in [r]: its highest alpha, the commonest color at that alpha (± 2), and its ink (alpha > 8). */
    private class Ink(val alpha: Int, val color: Int, val area: Int)

    private fun ink(b: Bitmap, r: Rect): Ink {
        val l = r.left.coerceIn(0, b.width); val t = r.top.coerceIn(0, b.height)
        val rr = r.right.coerceIn(0, b.width); val bb = r.bottom.coerceIn(0, b.height)
        val px = IntArray((rr - l) * (bb - t)).also { b.getPixels(it, 0, rr - l, l, t, rr - l, bb - t) }
        val top = px.maxOf { it ushr 24 }
        val common = px.filter { (it ushr 24) >= top - 2 }.groupingBy { it }.eachCount().maxBy { it.value }.key
        return Ink(top, common, px.count { (it ushr 24) > 8 })
    }

    private fun assertRgb(what: String, expected: Int, actual: Int, tol: Int) {
        val ok = listOf(16, 8, 0).all { abs(((expected shr it) and 0xFF) - ((actual shr it) and 0xFF)) <= tol }
        assertTrue("%s: expected #%06X ± %d, got #%08X".format(what, expected and 0xFFFFFF, tol, actual), ok)
    }

    private fun assertNear(what: String, want: Int, got: Int, tol: Int) = assertTrue("$what: want $want ± $tol, got $got", abs(want - got) <= tol)

    /** The Array sheet closed with its "Close". */
    private fun closeSheet() {
        if (u.s.tagged(V17Tags.ARRAY_SHEET) == null) return
        click("Close", exact = true)
        settle()
        assertNull("the Array sheet closed", u.s.tagged(V17Tags.ARRAY_SHEET))
        // The layer window the ⋮ menu was opened from comes back (hidden while the sheet was up): closed too.
        if (u.s.tagged(ChromeTags.LAYER_WINDOW) != null) u.closeLayers()
    }

    /** [layer] picked with a tap on its row in the layer window, the window closed again. */
    private fun pick(layer: Layer) {
        if (u.s.tagged(ChromeTags.LAYER_WINDOW) == null) u.openLayers()
        val row = LayerLabels.selectRow(u.c.doc.indexOf(layer) + 1)
        u.ui.reach(row, 40f)
        click(row, exact = true)
        Smoke.pump(600)
        settle()
        assertSame("${layer.name} is active", layer, u.c.activeLayer)
        if (u.s.tagged(ChromeTags.LAYER_WINDOW) != null) u.closeLayers()
    }

    /** What one kind's array was before its tint and after it (for the later checks). */
    private class Made(val name: String, val layer: Layer, val source: LayerData, val sourcePx: IntArray, val sourceBox: Rect)

    /**
     * [layer] (just made, active) arrayed from its ⋮ menu, Count 4, its look set on the Look tab
     * (each change one step), then checked: the source kept (I19), each copy's color and
     * opacity, the cache (I1), undo and redo of the tint.
     */
    private fun arrayWithLook(name: String, layer: Layer, content: RectF? = null, placement: List<Pair<String, String>> = emptyList()): Made {
        val c = u.c
        val source = layer.dataSnapshot()
        oneStep("$name: Array…", ArrayLabels.BUTTON) { u.layerMenu(ArrayLabels.OPEN) }
        assertNotNull("$name: a live array", layer.array)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        val tool = c.currentTool as ArrayTool
        u.showArraySheet()
        if (u.s.tagged(V18Tags.ARRAY_LOOK) != null) u.fingerPress(ArrayLabels18.PLACEMENT, 44f)
        oneStep("$name: Count", ArrayLabels.EDIT) { u.typeField("Count", "4") }
        assertEquals(4, layer.array!!.spec.count)
        for ((label, v) in placement) oneStep("$name: $label", ArrayLabels.EDIT) { u.typeField(label, v) }
        // Where the source paints: its box, or [content] (a shape's box has room for miter tips).
        val bounds = content ?: requireNotNull(ArrayDraw.sourceBounds(layer.dataSnapshot()))
        println("Qa18LookArrayObjects: $name source ${ArrayDraw.sourceBounds(layer.dataSnapshot())}, paints in $bounds")
        val box = Rect(bounds.left.toInt() + 2, bounds.top.toInt() + 2, bounds.right.toInt() - 2, bounds.bottom.toInt() - 2)
        assertTrue("$name: the source inside the document: $bounds", box.left >= 0 && box.top >= 0 && box.right <= u.c.doc.width && box.bottom <= u.c.doc.height)
        assertFalse("$name: the source has a box", box.isEmpty)
        val sourcePx = crop(layer.bitmap, box)
        val sourceInk = ink(layer.bitmap, box)
        assertEquals("$name: the source is opaque", 255, sourceInk.alpha)
        assertRgb("$name: the source is the drawing color", SRC, sourceInk.color, 0)

        u.fingerPress(ArrayLabels18.LOOK, 44f)
        assertNotNull("$name: the Look tab", u.s.tagged(V18Tags.ARRAY_LOOK))
        oneStep("$name: End opacity") { u.typeField(ArrayLabels18.END_OPACITY, "50") }
        oneStep("$name: End size") { u.typeField(ArrayLabels18.END_SIZE, "60") }
        oneStep("$name: End turn") { u.typeField(ArrayLabels18.END_TURN, "30") }
        oneStep("$name: Slows down") { u.fingerPress(ArrayLabels18.SLOWS_DOWN) }
        val untinted = pixels(layer.bitmap)
        oneStep("$name: Sunset") { u.press(SUNSET) }
        val look = requireNotNull(layer.array!!.spec.look)
        assertEquals(1f, look.opacityStart, 0f); assertEquals(0.5f, look.opacityEnd, 1e-4f)
        assertEquals(1f, look.sizeStart, 0f); assertEquals(0.6f, look.sizeEnd, 1e-4f)
        assertEquals(0f, look.turnStart, 0f); assertEquals(30f, look.turnEnd, 1e-3f)
        assertEquals(ArrayEasing.EASE_OUT, look.easing)
        assertEquals("$name: the Sunset stops", sunset, look.tint)
        assertEquals(1f, look.tintStrength, 0f)
        assertSame("$name: still the Array tool", tool, c.currentTool)
        checkLook(name, layer, source, sourcePx, box, content)

        // A two-finger tap takes the tint back off (the copies as just before it); three fingers put it back.
        val tinted = pixels(layer.bitmap)
        u.ui.twoFingerUndo()
        u.settleRenders("$name: undo")
        assertTrue("$name: undo: no tint", layer.array!!.spec.look!!.tint.isEmpty())
        assertEquals("$name: undo: the rest of the look kept", 0.6f, layer.array!!.spec.look!!.sizeEnd, 1e-4f)
        assertArrayEquals("$name: undo: the pixels before the tint", untinted, pixels(layer.bitmap))
        u.ui.threeFingerRedo()
        u.settleRenders("$name: redo")
        assertEquals("$name: redo", look, layer.array!!.spec.look)
        assertArrayEquals("$name: redo: the tinted pixels", tinted, pixels(layer.bitmap))
        return Made(name, layer, source, sourcePx, box)
    }

    /** [layer]'s look as drawn: the source as it was, each copy tinted and faded, the cache fresh. */
    private fun checkLook(name: String, layer: Layer, source: LayerData, sourcePx: IntArray, box: Rect, content: RectF?) {
        assertEquals("$name: the source's data is kept", source, layer.dataSnapshot().copy(array = null))
        assertArrayEquals("$name: I19: the source's pixels are kept", sourcePx, crop(layer.bitmap, box))
        assertEquals("$name: I1: the cache is a fresh render of the data", 0, differing(layer.bitmap, fresh(layer)))
        // The reference: the same copies with their size and turn only (no opacity, no tint).
        val plain = geometryOnly(layer)
        val area = IntArray(4)
        for (k in 1..3) {
            val cl = lookOf(layer, k)
            val t = (k - 1) / 2f
            val e = ArrayInstances.ease(ArrayEasing.EASE_OUT, t)
            assertEquals("$name copy $k: its opacity on the Slows down curve", 1f - 0.5f * e, cl.opacity, 1e-4f)
            assertEquals("$name copy $k: the Sunset color at its place", ArrayTint.sample(sunset, e), cl.tintColor)
            // Each pixel of the copy: its plain coverage times the copy's opacity; where it is
            // fully covered, the source's color tinted toward the copy's Sunset color.
            val want = ArrayTint.apply(SRC, cl.tintColor, 1f)
            val r = copyBox(layer, k, content)
            var covered = 0
            var full = 0
            val off = ArrayList<String>()
            for (y in r.top until r.bottom) for (x in r.left until r.right) {
                val p = plain.getPixel(x, y)
                val a = p ushr 24
                if (a == 0) continue
                covered++
                val q = layer.bitmap.getPixel(x, y)
                val wantA = (a * cl.opacity).roundToInt()
                if (abs((q ushr 24) - wantA) > 2) off += "($x, $y) alpha %d, want %d".format(q ushr 24, wantA)
                if (a >= 250) {
                    full++
                    if (listOf(16, 8, 0).any { abs(((want shr it) and 0xFF) - ((q shr it) and 0xFF)) > 4 }) off += "($x, $y) #%08X, want #%06X".format(q, want and 0xFFFFFF)
                }
            }
            assertTrue("$name copy $k: drawn ($covered px, $full fully covered)", covered > 40 && full > 10)
            assertTrue("$name copy $k at ${(cl.opacity * 100).roundToInt()} %, tinted ${hex(want)}: ${off.size} px off: ${off.take(6)}", off.isEmpty())
            area[k] = ink(layer.bitmap, r).area
        }
        // At 60 %, the last copy's ink is about 0.36 of the first's (its soft edge aside).
        val ratio = area[3].toFloat() / area[1]
        assertTrue("$name: the last copy at 60 %: ink ${area[3]} px, the first ${area[1]} (ratio $ratio)", ratio in 0.22f..0.55f)
    }

    /** [layer]'s array drawn fresh with its look's size and turn only: each copy's plain coverage. */
    private fun geometryOnly(layer: Layer): Bitmap {
        val d = layer.dataSnapshot()
        val a = d.array!!
        val geo = a.spec.look!!.copy(opacityStart = 1f, opacityEnd = 1f, tint = emptyList(), tintStrength = 0f)
        val d2 = d.copy(array = a.copy(spec = a.spec.copy(look = geo)))
        return if (d.vector != null) freshVector(u.c, d2) else freshRender(u.c, d2)
    }

    /**
     * How far [b] is from [a] per PREMULTIPLIED channel (what shows over any background): the
     * worst off inside (a pixel whose 3×3 neighbourhood in [a] is even) and the worst on an
     * anti-aliased edge of [a].
     */
    private fun offBy(a: Bitmap, b: Bitmap): Pair<Int, Int> {
        val pa = pixels(a)
        val pb = pixels(b)
        val w = a.width
        val h = a.height
        var inside = 0
        var edge = 0
        for (i in pa.indices) {
            val x = pa[i]
            val y = pb[i]
            if (x == y) continue
            val ax = x ushr 24
            val ay = y ushr 24
            var d = abs(ax - ay)
            for (s in intArrayOf(16, 8, 0)) d = maxOf(d, abs(((x shr s) and 0xFF) * ax / 255 - ((y shr s) and 0xFF) * ay / 255))
            val cx = i % w
            val cy = i / w
            var onEdge = false
            for (dy in -1..1) for (dx in -1..1) {
                val nx = cx + dx
                val ny = cy + dy
                if (nx in 0 until w && ny in 0 until h && pa[ny * w + nx] != x) onEdge = true
            }
            if (onEdge) edge = maxOf(edge, d) else inside = maxOf(inside, d)
        }
        return inside to edge
    }

    private fun hex(c: Int) = "#%06X".format(c and 0xFFFFFF)

    private fun crop(b: Bitmap, r: Rect): IntArray = IntArray(r.width() * r.height()).also { b.getPixels(it, 0, r.width(), r.left, r.top, r.width(), r.height()) }

    private fun flow(ui: Qa17ArrayUi) {
        u = ui
        u.editor()
        val c = u.c
        // What the flows start from: the drawing color and an 8 px brush (the shape's and the path's width).
        c.color = SRC
        c.brush = c.brush.copy(size = 8f, opacity = 1f)

        // ------------------------------------------------------------ A text typed with the Text tool
        u.tool("Text")
        val text = c.currentTool as TextTool
        assertTrue(u.onCanvas(70f, 50f))
        u.ui.tap(70f, 50f)
        assertTrue("a tap opens the text editor", text.editorOpen)
        SmokeUi.field("Text").type("Ab")
        settle()
        // Big enough to see the copies: the editor's Size, in pixels.
        u.ui.textSizePx(44)
        settle()
        click("OK", exact = true)
        u.applyEdit("Apply text edit")
        val textLayer = c.activeLayer
        assertTrue("a text layer", textLayer.isTextLayer)
        val madeText = arrayWithLook("text", textLayer)
        closeSheet()

        // ------------------------------------------------------------ A rectangle dragged with the Shape tool
        u.tool("Shape")
        val drag = (0..8).map { i -> (24f + i * 9f) to (118f + i * 7f) }
        for ((x, y) in drag) assertTrue("($x, $y) on the canvas", u.onCanvas(x, y))
        u.ui.stroke(*drag.toTypedArray())
        u.applyEdit("Apply shape edit")
        val shapeLayer = c.activeLayer
        val shape = requireNotNull(ShapeCodec.decode(shapeLayer.shapeData)) { "a shape layer" }
        println("Qa18LookArrayObjects: the shape $shape")
        // The shape's box has room for miter tips (10 strokes): the copies placed 90 px apart instead.
        val paints = RectF(shape.cx - shape.w / 2f, shape.cy - shape.h / 2f, shape.cx + shape.w / 2f, shape.cy + shape.h / 2f).apply { inset(-shape.strokeWidth / 2f - 2f, -shape.strokeWidth / 2f - 2f) }
        val madeShape = arrayWithLook("shape", shapeLayer, paints, listOf("Relative X" to "0", "Constant X" to "90"))
        closeSheet()

        // ------------------------------------------------------------ A path tapped out with the Path tool
        val pathLayer = requireNotNull(c.addVectorLayer("Path"))
        settle()
        u.tool("Path")
        for ((x, y) in listOf(20f to 260f, 55f to 215f, 90f to 260f)) {
            assertTrue("($x, $y) on the canvas", u.onCanvas(x, y))
            u.ui.tap(x, y)
        }
        u.applyEdit("Apply path edit")
        assertSame(pathLayer, c.activeLayer)
        assertEquals("one path", 1, pathLayer.vector!!.objects.count { it is VPath })
        // A gap between the copies (the stroke's caps reach the box's edges).
        val madePath = arrayWithLook("path", pathLayer, placement = listOf("Relative X" to "110"))
        closeSheet()
        val all = listOf(madeText, madeShape, madePath)

        // ------------------------------------------------------------ The PNGs
        settle(4)
        save(IbisShots.capture(), "look-array-objects-screen.png")
        save(document(), "look-array-objects.png")

        // ------------------------------------------------------------ The text's look edited later
        val shapePx = pixels(shapeLayer.bitmap)
        val pathPx = pixels(pathLayer.bitmap)
        pick(textLayer)
        assertEquals(ToolId.ARRAY, c.activeToolId)
        u.showArraySheet()
        if (u.s.tagged(V18Tags.ARRAY_LOOK) == null) u.fingerPress(ArrayLabels18.LOOK, 44f)
        assertTrue("the text's End size shows 60", fieldText(ArrayLabels18.END_SIZE).startsWith("60"))
        assertTrue("the text's Tint strength shows 100", fieldText(ArrayLabels18.TINT_STRENGTH).startsWith("100"))
        oneStep("text: End size 80") { u.typeField(ArrayLabels18.END_SIZE, "80") }
        assertEquals(0.8f, textLayer.array!!.spec.look!!.sizeEnd, 1e-4f)
        assertEquals("the others untouched: the shape", 0.6f, shapeLayer.array!!.spec.look!!.sizeEnd, 1e-4f)
        assertArrayEquals(shapePx, pixels(shapeLayer.bitmap))
        assertArrayEquals("the path", pathPx, pixels(pathLayer.bitmap))
        assertArrayEquals("text: I19 after the edit", madeText.sourcePx, crop(textLayer.bitmap, madeText.sourceBox))
        assertEquals("text: I1 after the edit", 0, differing(textLayer.bitmap, fresh(textLayer)))
        closeSheet()

        // ------------------------------------------------------------ Saved and reopened
        val app = RuntimeEnvironment.getApplication()
        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val loaded = runBlocking { ProjectRepository(app).load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        for (m in all) {
            val twin = loaded.layers[c.doc.indexOf(m.layer)]
            assertEquals("${m.name} reopened: the spec, its look included", m.layer.array!!.spec, twin.array!!.spec)
            assertEquals("${m.name} reopened: the source", m.source, twin.dataSnapshot().copy(array = null))
            assertEquals("${m.name} reopened: the same pixels", 0, differing(m.layer.bitmap, twin.bitmap, tol = 0))
        }

        // ------------------------------------------------------------ Apply array on each: one step, the look kept
        for (m in all) {
            pick(m.layer)
            assertEquals(ToolId.ARRAY, c.activeToolId)
            u.showArraySheet()
            val live = m.layer.bitmap.copy(Bitmap.Config.ARGB_8888, false)
            val n = u.steps()
            u.fingerPress(ArrayLabels.APPLY)
            if (m.layer.isTextLayer) {
                assertTrue("text: \"${ArrayLabels.APPLY_TEXT_ASK}\" first", has(ArrayLabels.APPLY_TEXT_ASK, exact = true))
                assertEquals("nothing applied yet", n, u.steps())
                click("Apply", exact = true)
                settle()
            }
            u.settleRenders("${m.name}: apply")
            assertNull("${m.name}: applied, no live array", m.layer.array)
            assertEquals("${m.name}: apply: one step", n + 1, u.steps())
            assertEquals(ArrayLabels.APPLY, c.undoManager.undoLabel)
            // As it looked: within a premultiplied rounding, and (a shape baked into vector shapes)
            // within anti-aliasing on the turned copies' edges (as ArrayLookApplyTest holds it).
            val (inside, edge) = offBy(live, m.layer.bitmap)
            println("Qa18LookArrayObjects: ${m.name} applied: off by $inside inside, $edge on an edge")
            assertTrue("${m.name}: applied as it looked: off by $inside inside the copies", inside <= 3)
            assertTrue("${m.name}: applied as it looked: an edge off by $edge", edge <= 48)
            if (u.s.tagged(V17Tags.ARRAY_SHEET) != null) closeSheet()
        }
        Smoke.assertQuiet(c, "the object arrays' looks")
    }

    private fun fieldText(label: String): String {
        u.intoView(label) { SmokeUi.field(label).node }
        return SmokeUi.field(label).node.config.getOrNull(SemanticsProperties.EditableText)?.text ?: ""
    }

    /** The document's layers over white, at 2x. */
    private fun document(): Bitmap {
        val c = u.c
        val out = Bitmap.createBitmap(c.doc.width, c.doc.height, Bitmap.Config.ARGB_8888)
        val cv = Canvas(out)
        cv.drawColor(0xFFFFFFFF.toInt())
        for (l in c.doc.layers) if (l.visible) cv.drawBitmap(l.bitmap, 0f, 0f, Paint())
        return Bitmap.createScaledBitmap(out, c.doc.width * 2, c.doc.height * 2, false)
    }

    private companion object {
        /** The drawing color: warm and mid-light, so a tint shows on it. */
        const val SRC = 0xFFD08A3C.toInt()
        const val SUNSET = "Sunset"

        private val SHOTS = File("C:\\Users\\USER\\Documents\\Brushwork\\.wt\\_tools\\v18-qa-shots")

        /** Writes [b] as [file] in the v1.8 QA shots folder when this machine has it; the path, or null. */
        fun save(b: Bitmap, file: String): String? {
            if (SHOTS.parentFile?.isDirectory != true) return null
            SHOTS.mkdirs()
            val f = File(SHOTS, file)
            FileOutputStream(f).use { b.compress(Bitmap.CompressFormat.PNG, 100, it) }
            return f.absolutePath
        }
    }
}
