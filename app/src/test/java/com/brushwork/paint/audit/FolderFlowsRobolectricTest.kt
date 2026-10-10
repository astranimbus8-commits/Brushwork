package com.brushwork.paint.audit

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Rect
import android.os.Looper
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayOps
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.ArrayMode
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.model.Selection
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.pathfinder.PathfinderTool
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.ui.common.ArrayLabels
import com.brushwork.paint.ui.common.PathfinderLabels
import com.brushwork.paint.ui.editor.HistoryLabels
import com.brushwork.paint.vector.pathfinder.PathfinderOp
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * v1.7 (§6.2 cross-area flows, §6.4 rows 1, 6, 9, 12, 13): folders meet the other areas end to
 * end, through the controller and the real tools, with the pixels, data and undo checked exactly.
 *  1. A selection array and a shape-layer array inside an ISOLATED folder; the folder moved with
 *     the Transform tool (FolderLift): both arrays stay live with the mapped spec and source, ONE
 *     step; saved and reopened, the tree, the arrays and the composite are the same and both
 *     arrays are still live (the same edit gives the same pixels); one undo of the transform
 *     restores every layer's pixels and data and the composite exactly.
 *  2. A Rotation x 4 brush stroke on a layer inside an isolated folder at 60 %: one step, four
 *     copies on the layer, the folder's composite shows all four at 60 %, one undo restores the
 *     layer and the composite exactly.
 *  3. Two shape layers inside an isolated folder united by Pathfinder: "Pathfinder 1" lands in the
 *     folder (rule S), one undo restores both operands (tree, ids, data, pixels, composite).
 */
@RunWith(RobolectricTestRunner::class)
class FolderFlowsRobolectricTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
        Smoke.scopeErrors.clear()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private val red = 0xFFDD2211.toInt()
    private val blue = 0xFF2244CC.toInt()
    private val ink = 0xFF2060C0.toInt()

    /**
     * Bottom first: Background (white), [[children] Group]; Group isolated (pass-through off) at
     * [folderOpacity]. The last child is active.
     */
    private fun folderDoc(w: Int, h: Int, folderOpacity: Float = 1f, vararg children: String): EditorController {
        val doc = Document("flows", "Flows", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(Color.WHITE) })
        val folder = Layer.newFolder(doc.newLayerId(), "Group", FolderSpec(passThrough = false)).also { it.opacity = folderOpacity }
        for (name in children) doc.layers += Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.parentId = folder.id }
        doc.layers += folder
        doc.activeLayerIndex = doc.layers.size - 2
        assertNull(LayerTree.check(doc.layers))
        return Smoke.controller(app, doc).also { it.viewTransform.set(Matrix()) }
    }

    private fun EditorController.byName(name: String): Layer = doc.layers.first { it.name == name }

    private fun px(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** Order, parents and every pixel layer's pixels. */
    private fun picture(c: EditorController): List<Any> =
        c.doc.layers.map { l -> Triple(l.id, l.parentId, if (l.isFolder) "folder" else px(l.bitmap).contentHashCode()) }

    /** Every pixel layer's data (text, shape, vector, array), by id. */
    private fun data(c: EditorController): Map<Long, LayerData> = c.doc.layers.filter { !it.isFolder }.associate { it.id to it.dataSnapshot() }

    private fun flat(c: EditorController): IntArray = c.compositor.renderFlattened().let { b -> try { px(b) } finally { b.recycle() } }

    private fun idle() = shadowOf(Looper.getMainLooper()).idle()

    private fun settle(c: EditorController) {
        Smoke.pumpUntil { !c.vectors.isRendering && c.busyMessage == null }
        idle()
    }

    private fun assertFoldersIntact(c: EditorController, what: String) {
        assertNull(what, LayerTree.check(c.doc.layers))
        assertFalse("$what: FOLDER_BITMAP alive", Layer.FOLDER_BITMAP.isRecycled)
        assertEquals("$what: FOLDER_BITMAP untouched", 0, Layer.FOLDER_BITMAP.getPixel(0, 0))
        for (l in c.doc.layers) if (l.isFolder) assertSame("$what: ${l.name} keeps FOLDER_BITMAP", Layer.FOLDER_BITMAP, l.bitmap)
    }

    /** [p] moved by whole pixels ([dx], [dy]) on a [w] x [h] layer; what moves off the edge is lost. */
    private fun shifted(p: IntArray, w: Int, h: Int, dx: Int, dy: Int): IntArray {
        val out = IntArray(p.size)
        for (y in 0 until h) for (x in 0 until w) {
            val sx = x - dx
            val sy = y - dy
            if (sx in 0 until w && sy in 0 until h) out[y * w + x] = p[sy * w + sx]
        }
        return out
    }

    // ------------------------------------------------------------------ 1. arrays, transform, save, reopen

    private data class Arrays(val c: EditorController, val folder: Layer, val pixelArray: Layer, val shape: Layer)

    /**
     * A [w] x [h] document whose isolated folder "Group" holds "Paint" (emptied by the array),
     * "Array 1" (a selection array of a red square: by transform, three copies 30 px apart) and
     * "Shape" (a whole shape layer arrayed round a circle, four copies), made the user's way.
     */
    private fun arraysInFolder(w: Int, h: Int): Arrays {
        val c = folderDoc(w, h, 1f, "Paint")
        val folder = c.byName("Group")
        val paint = c.byName("Paint")
        Canvas(paint.bitmap).drawRect(Rect(12, 12, 28, 28), Paint().apply { color = red })

        // The selection array: the red square moves into "Array 1", directly above Paint in Group.
        c.selectLayer(paint)
        c.setSelection(Selection.fromPath(Path().apply { addRect(8f, 8f, 32f, 32f, Path.Direction.CW) }, w, h, antiAlias = false), recordUndo = false)
        assertTrue(c.arrayFromSelection())
        c.setSelection(null, recordUndo = false)
        val pixelArray = c.activeLayer
        assertNotNull(pixelArray.array?.pixels)
        assertEquals("the array layer lands in the source's folder", folder.id, pixelArray.parentId)
        assertTrue(ArrayOps.edit(c, pixelArray, ArraySpec(mode = ArrayMode.TRANSFORM, count = 3, moveX = 30f, moveY = 6f, turnDeg = 0f, scale = 1f, pivotX = 20f, pivotY = 20f)))

        // The shape-layer array: a whole shape layer (added above the active row, in Group) round a circle.
        val o = ShapeObject(ShapeType.RECTANGLE, cx = 40f, cy = 75f, w = 16f, h = 10f, style = ShapeStyle.FILL, fillColor = blue)
        val shape = c.addLayerWithContent("Shape", "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, w, h))!!
        assertEquals(folder.id, shape.parentId)
        assertTrue(c.arrayWholeLayer(shape))
        assertTrue(ArrayOps.edit(c, shape, ArraySpec(mode = ArrayMode.CIRCLE, count = 4, centerX = 60f, centerY = 75f)))
        settle(c)
        assertNull(LayerTree.check(c.doc.layers))
        assertEquals(listOf("Background", "Paint", pixelArray.name, "Shape", "Group"), c.doc.layers.map { it.name })
        return Arrays(c, folder, pixelArray, shape)
    }

    /** [folder] lifted whole by the Transform tool, changed by [edit], applied: ONE step (the tool put away). */
    private fun transformFolder(c: EditorController, folder: Layer, edit: (TransformTool) -> Unit) {
        c.selectLayer(folder)
        c.selectTool(ToolId.TRANSFORM)
        idle()
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        tool.snapToObjects = false
        tool.start()
        idle()
        assertEquals("the folder is lifted", TransformTool.Lifted.FOLDER, tool.lifted)
        val steps = c.undoManager.undoCount
        edit(tool)
        tool.commit()
        c.selectTool(ToolId.LASSO)
        settle(c)
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(TransformTool.TRANSFORM_LABEL, c.undoManager.undoLabel)
        assertFoldersIntact(c, "transformed")
    }

    /** [c]'s document saved and loaded again (no warnings, format 3), in a controller of its own. */
    private fun reopened(c: EditorController): EditorController {
        runBlocking { ProjectRepository(app).save(c.doc, null) }
        val json = ProjectFormat.json.parseToJsonElement(File(File(root, c.doc.id), ProjectFormat.PROJECT_FILE).readText()).jsonObject
        assertEquals("a folder: format 3", 3, json.getValue("formatVersion").jsonPrimitive.int)
        val loaded = runBlocking { ProjectRepository(app).load(c.doc.id) }
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val c2 = Smoke.controller(app, loaded).also { it.viewTransform.set(Matrix()) }
        settle(c2)
        assertFoldersIntact(c2, "reopened")
        assertEquals("the tree", c.doc.layers.map { Triple(it.id, it.parentId, it.name) }, loaded.layers.map { Triple(it.id, it.parentId, it.name) })
        assertEquals("the folders", c.doc.layers.map { it.folder }, loaded.layers.map { it.folder })
        return c2
    }

    @Test
    fun arraysInsideAnIsolatedFolderFollowItsTransformSurviveReopenAndOneUndoRestoresAll() {
        val w = 160
        val h = 120
        val (c, folder, pixelArray, shape) = arraysInFolder(w, h)

        val pixelSpec = pixelArray.array!!.spec
        val pixelSource = pixelArray.array!!.pixels!!
        val shapeSpec = shape.array!!.spec
        val before = picture(c)
        val dataBefore = data(c)
        val flatBefore = flat(c)
        val pixelArrayBefore = px(pixelArray.bitmap)

        // The folder moved by (10, 4) with the Transform tool: ONE step.
        transformFolder(c, folder) { it.moveBy(10f, 4f) }

        // Both arrays are still live; the spec is the spec under the move, the source moved with it.
        val move = floatArrayOf(1f, 0f, 10f, 0f, 1f, 4f, 0f, 0f, 1f)
        val pa = pixelArray.array
        assertNotNull("the selection array is live", pa)
        assertEquals(pixelSpec.mapped(move), pa!!.spec)
        assertEquals("its pivot moved", 30f, pa.spec.pivotX!!, 1e-4f)
        assertEquals(24f, pa.spec.pivotY!!, 1e-4f)
        assertEquals("its source moved by whole pixels", pixelSource.left + 10, pa.pixels!!.left)
        assertEquals(pixelSource.top + 4, pa.pixels.top)
        assertArrayEquals("the same source pixels", px(pixelSource.bitmap), px(pa.pixels.bitmap))
        assertArrayEquals("the copies moved with it", shifted(pixelArrayBefore, w, h, 10, 4), px(pixelArray.bitmap))
        val sa = shape.array
        assertNotNull("the shape array is live", sa)
        assertEquals(shapeSpec.mapped(move), sa!!.spec)
        assertEquals("its centre moved", 70f, sa.spec.centerX!!, 1e-4f)
        assertEquals(79f, sa.spec.centerY!!, 1e-4f)
        val moved = ShapeCodec.decode(shape.shapeData!!)!!
        assertEquals("still a shape, moved", 50f, moved.cx, 1e-3f)
        assertEquals(79f, moved.cy, 1e-3f)
        val after = picture(c)
        val dataAfter = data(c)
        val flatAfter = flat(c)
        assertFalse("the picture moved", flatBefore.contentEquals(flatAfter))

        // Saved and reopened: the same tree, arrays and composite.
        val c2 = reopened(c)
        val loaded = c2.doc
        assertEquals("still isolated", folder.folder, loaded.layers.single { it.id == folder.id }.folder)
        fun twin(l: Layer) = loaded.layers.single { it.id == l.id }
        for (l in listOf(pixelArray, shape)) {
            val back = twin(l)
            assertEquals("${l.name}: the spec", l.array!!.spec, back.array!!.spec)
            assertEquals("${l.name}: the shape", l.shapeData, back.shapeData)
            assertArrayEquals("${l.name}: the cache", px(l.bitmap), px(back.bitmap))
        }
        val px2 = twin(pixelArray).array!!.pixels!!
        assertEquals(pa.pixels.left, px2.left)
        assertEquals(pa.pixels.top, px2.top)
        assertArrayEquals("the source pixels", px(pa.pixels.bitmap), px(px2.bitmap))
        assertArrayEquals("the composite after reopening", flatAfter, flat(c2))

        // ONE undo of the transform restores every layer's pixels and data, and the composite.
        c.undo()
        settle(c)
        assertFoldersIntact(c, "undone")
        assertEquals("undone: the tree and pixels", before, picture(c))
        assertEquals("undone: the data", dataBefore, data(c))
        assertSame("undone: the very source", pixelSource, pixelArray.array!!.pixels)
        assertArrayEquals("undone: the composite", flatBefore, flat(c))
        c.redo()
        settle(c)
        assertFoldersIntact(c, "redone")
        assertEquals("redone", after, picture(c))
        assertEquals("redone: the data", dataAfter, data(c))
        assertArrayEquals("redone: the composite", flatAfter, flat(c))

        // Live after reopening: the same edit gives the same pixels in both.
        for (ctrl in listOf(c, c2)) {
            for (l in listOf(pixelArray, shape)) {
                val layer = ctrl.doc.layers.single { it.id == l.id }
                val s = layer.array!!.spec
                assertTrue(ArrayOps.edit(ctrl, layer, s.copy(count = s.count + 1)))
            }
            settle(ctrl)
        }
        for (l in listOf(pixelArray, shape)) {
            assertEquals(l.array!!.spec, twin(l).array!!.spec)
            assertArrayEquals("${l.name}: edited after reopening", px(l.bitmap), px(twin(l).bitmap))
        }
        assertArrayEquals("the same composite", flat(c), flat(c2))
        Smoke.assertQuiet(c2, "reopened")
    }

    @Test
    fun aScaledFolderScalesItsArraysAsLiveArraysAndOneUndoRestoresThem() {
        val (c, folder, pixelArray, shape) = arraysInFolder(160, 120)
        val pixelSource = pixelArray.array!!.pixels!!
        val pixelSpec = pixelArray.array!!.spec
        val before = picture(c)
        val dataBefore = data(c)
        val flatBefore = flat(c)

        transformFolder(c, folder) { tool ->
            tool.setScalePercent(125.0)
            tool.endNumericEdit()
        }

        // The raster array: its offsets scale, its source is resampled once; still live.
        val pa = pixelArray.array
        assertNotNull("the selection array is live", pa)
        assertEquals(ArrayMode.TRANSFORM, pa!!.spec.mode)
        assertEquals("the copy offset scales", 37.5f, pa.spec.moveX, 0.01f)
        assertEquals(7.5f, pa.spec.moveY, 0.01f)
        assertEquals("the copies keep their own scale", pixelSpec.scale, pa.spec.scale, 1e-4f)
        assertTrue("the source is resampled (${pa.pixels!!.bitmap.width} px)", abs(pa.pixels.bitmap.width - 20) <= 2)
        // The shape array: still a shape, scaled, at the scaled distance from the scaled centre.
        val sa = shape.array
        assertNotNull("the shape array is live", sa)
        val o = ShapeCodec.decode(shape.shapeData!!)!!
        assertEquals(20f, o.w, 0.01f)
        assertEquals(12.5f, o.h, 0.01f)
        val r = kotlin.math.hypot(sa!!.spec.centerX!! - o.cx, sa.spec.centerY!! - o.cy)
        assertEquals("the circle's radius scales", 25f, r, 0.01f)
        val after = picture(c)
        val dataAfter = data(c)
        val flatAfter = flat(c)

        val c2 = reopened(c)
        for (l in listOf(pixelArray, shape)) {
            val back = c2.doc.layers.single { it.id == l.id }
            assertEquals("${l.name}: the spec", l.array!!.spec, back.array!!.spec)
            assertArrayEquals("${l.name}: the cache", px(l.bitmap), px(back.bitmap))
        }
        assertArrayEquals("the composite after reopening", flatAfter, flat(c2))

        c.undo()
        settle(c)
        assertFoldersIntact(c, "undone")
        assertEquals("undone: the tree and pixels", before, picture(c))
        assertEquals("undone: the data", dataBefore, data(c))
        assertSame("undone: the very source", pixelSource, pixelArray.array!!.pixels)
        assertArrayEquals("undone: the composite", flatBefore, flat(c))
        c.redo()
        settle(c)
        assertEquals("redone", after, picture(c))
        assertEquals("redone: the data", dataAfter, data(c))
        assertArrayEquals("redone: the composite", flatAfter, flat(c))
        Smoke.assertQuiet(c, "scaled")
    }

    // ------------------------------------------------------------------ 2. symmetry in an isolated folder at 60 %

    @Test
    fun aRotationFourStrokeInAnIsolatedFolderAtSixtyPercentIsOneStepAndTheFolderShowsTheCopies() {
        val w = 200
        val h = 160
        val opacity = 0.6f
        val c = folderDoc(w, h, opacity, "Layer 1")
        val layer = c.byName("Layer 1")
        assertSame(layer, c.activeLayer)
        c.color = ink
        c.selectTool(ToolId.BRUSH)
        val s = SymmetrySettings(SymmetryType.ROTATION, divisions = 4)
        c.updateSymmetry(s)
        val steps = c.undoManager.undoCount
        val before = px(layer.bitmap)
        val flatBefore = flat(c)
        assertTrue("white before", flatBefore.all { it == Color.WHITE })

        val pts = List(16) { k ->
            val t = k / 15f
            ToolPoint(30f + 40f * t, 30f + 10f * sin(t * 6f), 1f, k.toLong())
        }
        c.pointerDown(pts.first())
        for (p in pts.subList(1, pts.size - 1)) c.pointerMove(p)
        c.pointerUp(pts.last())
        idle()
        assertEquals("one step", steps + 1, c.undoManager.undoCount)
        val after = px(layer.bitmap)

        // Four copies on the layer: every point of the stroke is painted under each of the four maps.
        val maps = SymmetryMaps.transforms(s, w, h, pts[0].x, pts[0].y)
        assertEquals(4, maps.size)
        for ((i, m) in maps.withIndex()) for (p in pts.subList(2, pts.size - 2)) {
            val x = (m[0] * p.x + m[1] * p.y + m[2]).roundToInt()
            val y = (m[3] * p.x + m[4] * p.y + m[5]).roundToInt()
            assertTrue("copy $i painted at ($x, $y)", Color.alpha(after[y * w + x]) > 0)
        }
        var changed = 0
        val quadrants = IntArray(4)
        for (y in 0 until h) for (x in 0 until w) if (before[y * w + x] != after[y * w + x]) {
            changed++
            quadrants[(if (x < w / 2) 0 else 1) + (if (y < h / 2) 0 else 2)]++
        }
        for (q in quadrants) assertTrue("each quadrant holds one copy (${quadrants.toList()})", q > changed / 6)

        // The folder's composite: white under the layer at 60 %, the four copies included (±2).
        val flatAfter = flat(c)
        var worst = 0
        for (i in after.indices) {
            val p = after[i]
            val a = Color.alpha(p) / 255f * opacity
            fun ch(v: Int) = (v * a + 255 * (1 - a)).roundToInt()
            val e = Color.rgb(ch(Color.red(p)), ch(Color.green(p)), ch(Color.blue(p)))
            val f = flatAfter[i]
            worst = maxOf(worst, abs(Color.red(e) - Color.red(f)), abs(Color.green(e) - Color.green(f)), abs(Color.blue(e) - Color.blue(f)))
        }
        assertTrue("the composite is the layer at 60 % over white (worst $worst)", worst <= 2)
        for ((i, m) in maps.withIndex()) {
            val p = pts[pts.size / 2]
            val x = (m[0] * p.x + m[1] * p.y + m[2]).roundToInt()
            val y = (m[3] * p.x + m[4] * p.y + m[5]).roundToInt()
            assertNotEquals("the composite shows copy $i at ($x, $y)", Color.WHITE, flatAfter[y * w + x])
        }

        c.undo()
        assertArrayEquals("one undo takes back all four copies", before, px(layer.bitmap))
        assertArrayEquals("and the composite", flatBefore, flat(c))
        assertEquals("the folder stays", c.byName("Group").id, layer.parentId)
        assertFoldersIntact(c, "undone")
        c.redo()
        assertArrayEquals("redo brings the four back", after, px(layer.bitmap))
        assertArrayEquals(flatAfter, flat(c))
        Smoke.assertQuiet(c, "symmetry in a folder")
    }

    // ------------------------------------------------------------------ 3. Pathfinder in a folder

    @Test
    fun twoShapeLayersInAFolderUniteIntoPathfinderOneInThatFolderAndOneUndoRestoresThem() {
        val c = folderDoc(300, 200, 0.8f, "Base")
        val folder = c.byName("Group")
        fun shapeLayer(name: String, l: Float, t: Float, r: Float, b: Float, color: Int): Layer {
            val o = ShapeObject(ShapeType.RECTANGLE, cx = (l + r) / 2f, cy = (t + b) / 2f, w = r - l, h = b - t, style = ShapeStyle.FILL, fillColor = color)
            return c.addLayerWithContent(name, "Add shape", shapeData = ShapeCodec.encode(o), draw = ArraySources.shapeDraw(o, ColorMode.RGB, 300, 200))!!
        }
        val a = shapeLayer("A", 20f, 20f, 100f, 100f, red)
        val b = shapeLayer("B", 70f, 40f, 150f, 120f, blue)
        assertEquals(listOf(folder.id, folder.id), listOf(a.parentId, b.parentId))
        val aData = a.shapeData
        val bData = b.shapeData
        val before = picture(c)
        val flatBefore = flat(c)

        c.selectTool(ToolId.PATHFINDER)
        val t = (c.currentTool as PathfinderTool).also { it.computeDispatcher = Dispatchers.Unconfined }
        for ((x, y) in listOf(30f to 30f, 140f to 110f)) {
            c.pointerDown(ToolPoint(x, y))
            c.pointerUp(ToolPoint(x, y))
        }
        assertEquals(listOf(a, b), t.operands.map { it.layer })
        val steps = c.undoManager.undoCount
        t.apply(PathfinderOp.UNITE)
        idle()
        assertEquals("ONE step", steps + 1, c.undoManager.undoCount)
        assertEquals(HistoryLabels.pathfinder("Unite"), c.undoManager.undoLabel)
        assertFoldersIntact(c, "united")
        val result = c.doc.layers.single { it.name == PathfinderLabels.resultLayer(1) }
        assertEquals("the result is in the folder", folder.id, result.parentId)
        assertTrue(result.isVectorLayer)
        assertEquals("both operands are gone", -1, c.doc.indexOf(a))
        assertEquals(-1, c.doc.indexOf(b))
        assertEquals(listOf("Background", "Base", result.name, "Group"), c.doc.layers.map { it.name })
        assertEquals("rendered", 255, Color.alpha(result.bitmap.getPixel(30, 30)))
        assertEquals(255, Color.alpha(result.bitmap.getPixel(140, 110)))
        val after = picture(c)
        val flatAfter = flat(c)
        assertNotEquals("the composite shows the result in the folder's colour", flatBefore.contentHashCode(), flatAfter.contentHashCode())

        c.undo()
        assertFoldersIntact(c, "undone")
        assertEquals("one undo: the tree, ids and pixels", before, picture(c))
        assertSame(a, c.byName("A"))
        assertSame(b, c.byName("B"))
        assertEquals(aData, a.shapeData)
        assertEquals(bData, b.shapeData)
        assertEquals(-1, c.doc.indexOf(result))
        assertArrayEquals("and the composite", flatBefore, flat(c))
        c.redo()
        assertFoldersIntact(c, "redone")
        assertEquals(after, picture(c))
        assertArrayEquals(flatAfter, flat(c))
        Smoke.assertQuiet(c, "pathfinder in a folder")
    }
}
