package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Rect
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File
import kotlin.random.Random

/**
 * v1.5 A1 (§4.9e, §6 A1): 50 random vector operations — adds, deletes, moves (some as pure
 * whole-pixel shifts), recolors, z-order changes, edit-session commits, data appends after live
 * pixels, layer operations — keep every cache equal to a fresh rendering of its content; undo
 * all returns to the start and redo all to the end, exactly, data and pixels; a save and load in
 * the middle restores the same content and pixels, and work continues on the loaded document.
 * (The A2 / A3 seams are replaced by direct `vectors` calls.)
 */
@RunWith(RobolectricTestRunner::class)
class VectorPropertyRobolectricTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)

    @After
    fun tearDown() = scope.cancel()

    private val app get() = RuntimeEnvironment.getApplication()
    private val w = 512
    private val h = 384

    private fun controller(doc: Document): EditorController {
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        return EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
    }

    private fun newDoc(): Document {
        val doc = Document("prop-" + System.nanoTime(), "Property", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also { it.bitmap.eraseColor(-1) }
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.layers += Layer(doc.newLayerId(), "Vector 2", BitmapUtils.createLayerBitmap(w, h)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        return doc
    }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun fresh(content: VectorContent): IntArray {
        val b = BitmapUtils.createLayerBitmap(w, h)
        VectorLayerRenderer.render(Canvas(b), content, Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
        return px(b)
    }

    private class State(val names: List<String>, val data: List<Any?>, val pixels: List<IntArray>)

    private fun state(c: EditorController) = State(
        c.doc.layers.map { it.name },
        c.doc.layers.map { it.vector },
        c.doc.layers.map { px(it.bitmap) },
    )

    private fun assertState(what: String, a: State, b: State) {
        assertEquals("$what: layers", a.names, b.names)
        assertEquals("$what: data", a.data, b.data)
        for (i in a.pixels.indices) assertArrayEquals("$what: pixels of ${a.names[i]}", a.pixels[i], b.pixels[i])
    }

    /**
     * Every cache equals a fresh render of its content: exactly, or — once a pure move shifted
     * cache pixels ([exact] false) — but for anti-aliased pixels where the renderer's tile grid
     * cut a path (a handful per moved object).
     */
    private fun assertCachesFresh(what: String, c: EditorController, exact: Boolean) {
        for (l in c.doc.layers) {
            val v = l.vector ?: continue
            val f = fresh(v)
            val p = px(l.bitmap)
            if (exact) { assertArrayEquals("$what: cache of ${l.name}", f, p); continue }
            var off = 0
            for (i in f.indices) {
                var d = 0
                for (s in intArrayOf(24, 16, 8, 0)) d = maxOf(d, kotlin.math.abs(((f[i] ushr s) and 0xFF) - ((p[i] ushr s) and 0xFF)))
                if (d > 2) off++
            }
            if (off >= f.size / 100) throw AssertionError("$what: $off pixels of ${l.name} differ from a fresh render")
        }
    }

    private fun randomObject(r: Random): VObject {
        val x = r.nextFloat() * w
        val y = r.nextFloat() * h
        return when (r.nextInt(4)) {
            0 -> {
                val n = 3 + r.nextInt(8)
                val id = listOf("gpen", "softround", "pencil", "chalk", "airbrush")[r.nextInt(5)]
                VStroke(
                    0, preset = BrushLibrary.byId(id)!!.copy(size = 4f + r.nextFloat() * 24f), color = (0xFF000000.toInt() or r.nextInt(0xFFFFFF)), seed = r.nextLong(), stylus = r.nextBoolean(),
                    points = PackedPoints(FloatArray(n) { x + r.nextFloat() * 160f - 80f }, FloatArray(n) { y + r.nextFloat() * 160f - 80f }, FloatArray(n) { 0.2f + r.nextFloat() * 0.8f }),
                )
            }
            1 -> VPath(
                0, opacity = if (r.nextInt(4) == 0) 0.6f else 1f,
                subpaths = listOf(VSubpath(List(3 + r.nextInt(3)) { VAnchor(x + r.nextFloat() * 200f - 100f, y + r.nextFloat() * 200f - 100f, width = if (r.nextInt(3) == 0) 0.5f + r.nextFloat() * 2f else 1f) }, closed = r.nextBoolean())),
                tension = r.nextFloat() * 0.5f,
                fill = if (r.nextBoolean()) VPaint.Solid(0xFF000000.toInt() or r.nextInt(0xFFFFFF)) else null,
                stroke = VStrokeStyle(color = 0xFF000000.toInt() or r.nextInt(0xFFFFFF), width = 1f + r.nextFloat() * 8f),
            )
            else -> VShape(
                0, opacity = if (r.nextInt(5) == 0) 0.5f else 1f,
                shape = ShapeObject(
                    listOf(ShapeType.RECTANGLE, ShapeType.ELLIPSE, ShapeType.STAR, ShapeType.POLYGON)[r.nextInt(4)],
                    cx = x, cy = y, w = 20f + r.nextFloat() * 140f, h = 20f + r.nextFloat() * 100f, rotation = r.nextFloat() * 90f,
                    style = ShapeStyle.STROKE_FILL, strokeWidth = 1f + r.nextFloat() * 6f, strokeColor = 0xFF000000.toInt() or r.nextInt(0xFFFFFF), fillColor = 0xFF000000.toInt() or r.nextInt(0xFFFFFF),
                ),
                seed = r.nextLong(),
            )
        }
    }

    /** One random operation (one undo step, or none when it changes nothing). */
    private fun step(c: EditorController, r: Random) {
        val layer = c.doc.layers.filter { it.isVectorLayer }.let { it[r.nextInt(it.size)] }
        val content = layer.vector!!
        val objs = content.objects
        when (if (objs.isEmpty()) 0 else r.nextInt(9)) {
            0 -> c.vectors.addObjects(layer, List(1 + r.nextInt(3)) { randomObject(r) }, "Add")
            1 -> c.vectors.update(layer, content.without(setOf(objs[r.nextInt(objs.size)].id)), "Delete")
            2 -> {
                val o = objs[r.nextInt(objs.size)]
                val m = Matrix().apply { setRotate(r.nextFloat() * 60f - 30f, w / 2f, h / 2f); postScale(0.7f + r.nextFloat() * 0.6f, 0.7f + r.nextFloat() * 0.6f, w / 2f, h / 2f); postTranslate(r.nextFloat() * 60f - 30f, r.nextFloat() * 60f - 30f) }
                val v = FloatArray(9).also { m.getValues(it) }
                c.vectors.update(layer, content.replaced(mapOf(o.id to listOf(VectorOps.transformed(o, v)))), "Transform objects")
            }
            3 -> {
                // A whole-pixel move with the shift hint (fast path or re-render).
                val o = objs[r.nextInt(objs.size)]
                val dx = r.nextInt(81) - 40; val dy = r.nextInt(81) - 40
                val moved = content.replaced(mapOf(o.id to listOf(VectorOps.transformed(o, floatArrayOf(1f, 0f, dx.toFloat(), 0f, 1f, dy.toFloat(), 0f, 0f, 1f)))))
                c.vectors.update(layer, moved, "Move", shift = VectorLayers.ShiftHint(setOf(o.id), dx, dy))
            }
            4 -> {
                val i = r.nextInt(objs.size)
                val reordered = objs.toMutableList().also { val o = it.removeAt(i); it.add(r.nextInt(it.size + 1), o) }
                c.vectors.update(layer, content.copy(objects = reordered), "Arrange")
            }
            5 -> {
                val o = objs[r.nextInt(objs.size)]
                val recolored = when (o) {
                    is VStroke -> o.copy(color = 0xFF000000.toInt() or r.nextInt(0xFFFFFF))
                    is VPath -> o.copy(fill = VPaint.Solid(0xFF000000.toInt() or r.nextInt(0xFFFFFF)))
                    is VShape -> o.copy(opacity = 0.3f + r.nextFloat() * 0.7f)
                }
                c.vectors.update(layer, content.replaced(mapOf(o.id to listOf(recolored))), "Recolor")
            }
            6 -> {
                // An edit-session commit (reopen + replace).
                val ids = objs.shuffled(r).take(1 + r.nextInt(minOf(2, objs.size))).map { it.id }.toSet()
                var session: com.brushwork.paint.vector.edit.VectorEditSession? = null
                c.vectors.beginEdit(layer, ids) { session = it }
                val m = floatArrayOf(1f, 0f, r.nextFloat() * 40f - 20f, 0f, 1f, r.nextFloat() * 40f - 20f, 0f, 0f, 1f)
                session!!.commit(ids.map { VectorOps.transformed(content.byId(it)!!, m) }, "Edit")
            }
            7 -> {
                // A live stroke's pattern: pixels committed (kept as data), then the data appended.
                val s = randomObject(r).let { if (it is VStroke) it else VStroke(0, preset = BrushLibrary.defaultBrush, color = -16777216, seed = 1, stylus = false, points = PackedPoints(floatArrayOf(10f, 200f), floatArrayOf(10f, 120f), floatArrayOf(1f, 1f))) }
                c.groupUndo("Brush") {
                    c.keepLayerData(layer) {
                        val rec = c.beginEdit(layer)
                        rec.touch(Rect(0, 0, w, h))
                        VectorLayerRenderer.render(Canvas(layer.bitmap), VectorContent(objects = listOf(s)), Rect(0, 0, w, h), tips = TipCache(), document = Rect(0, 0, w, h))
                        c.commitEdit(rec, "Brush")
                    }
                    c.vectors.appendData(layer, listOf(s), "Brush")
                }
            }
            else -> {
                // Clear (no selection: every object). (Flips are covered by VectorLayerOpsRobolectricTest:
                // they mirror the cache exactly, so grain would not match a re-render.)
                c.clearLayer(layer)
            }
        }
    }

    @Test
    fun fiftyRandomOperationsUndoRedoSaveAndLoad() = runBlocking {
        val r = Random(2024)
        val doc = newDoc()
        val c = controller(doc)
        val start = state(c)
        repeat(50) { k ->
            val before = c.undoManager.undoCount
            step(c, r)
            assertEquals("op $k is at most one step", true, c.undoManager.undoCount - before in 0..1)
            assertCachesFresh("after op $k", c, exact = c.vectors.shiftCount == 0)
        }
        println("property test: ${c.vectors.shiftCount} moves took the pixel-shift path")
        val end = state(c)
        val steps = c.undoManager.undoCount
        repeat(steps) { c.undo() }
        assertState("undo all", start, state(c))
        repeat(steps) { c.redo() }
        assertState("redo all", end, state(c))

        // Save and load in the middle: back to half way, save, load, continue on the loaded copy.
        repeat(steps / 2) { c.undo() }
        val half = state(c)
        val repo = ProjectRepository(app)
        repo.save(doc, null)
        val loaded = repo.load(doc.id)
        val c2 = controller(loaded)
        assertState("loaded", half, state(c2))
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val r2 = Random(77)
        repeat(10) { k ->
            step(c2, r2)
            assertCachesFresh("loaded op $k", c2, exact = c.vectors.shiftCount == 0 && c2.vectors.shiftCount == 0)
        }
        val end2 = state(c2)
        val n2 = c2.undoManager.undoCount
        repeat(n2) { c2.undo() }
        assertState("undo on the loaded copy", half, state(c2))
        repeat(n2) { c2.redo() }
        assertState("redo on the loaded copy", end2, state(c2))
        File(app.filesDir, "projects/${doc.id}").deleteRecursively()
        Unit
    }
}
