package com.brushwork.paint.vector

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArraySources
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.exchange.PayloadImport
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.ColorMode
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.model.LayerData
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.DataRender
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.tools.vector.ShapeTransforms
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.draw.EraseSession
import com.brushwork.paint.vector.draw.FillHits
import com.brushwork.paint.vector.draw.FillPart
import com.brushwork.paint.vector.draw.TargetCache
import com.brushwork.paint.vector.draw.VectorEraseMode
import com.brushwork.paint.vector.geom.ObjectIndex
import com.brushwork.paint.vector.pathfinder.PathConvert
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File
import kotlin.math.abs

/**
 * v1.8 F2, the lead test of I17 (design §4.3's reader table): every reader of a `VPath`, a
 * `VShape` or a shape layer's `ShapeObject` sees an object's live copies, and keeps, bakes or (as
 * documented) ignores them as the table says.
 *
 * - A vector-layer fixture (a filled box, a shape, an open line), each with Mirror copies and
 *   each source left of the axis, so only a copy is right of it: render, bounds, hit and object
 *   pick, the lasso, both erasers and the fill, Pathfinder, export, transform, flip, lift
 *   (affine and homography), import and the Array source and draw.
 * - A shape-layer fixture (a shape with Mirror copies): the Shape tool's and Pathfinder's pick,
 *   Pathfinder, the shape-layer transform, the lift to pixels, convert to vector layer, the Array
 *   source, export and the Shape tool's reopen.
 * - A source audit: a file of the main sources that reads object geometry directly goes through
 *   `ObjectCopies` (or the copies), or is listed below with what it does with the copies; a new
 *   reader without copies fails here.
 */
@RunWith(RobolectricTestRunner::class)
class CopiesReadersAuditTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    /** The Mirror ruler of the kit's 512 × 384 canvas: x ↦ 512 − x. */
    private val mirror = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), kit.w, kit.h)
    private val copies = CopyMaps.of(mirror)
    private val axis = kit.w / 2f

    private val box = ObjectCopies.withCopies(kit.box(40f, 40f, 140f, 120f).withId(1), copies)
    private val shape = ObjectCopies.withCopies(kit.ellipse(90f, 250f).withId(2), copies)
    private val line = ObjectCopies.withCopies(
        VPath(3, subpaths = listOf(VSubpath(listOf(VAnchor(30f, 330f, true), VAnchor(180f, 340f, true)), closed = false)), stroke = VStrokeStyle(color = 0xFF101010.toInt(), width = 4f)),
        copies,
    )
    private val content = VectorContent(objects = listOf(box, shape, line), nextId = 4)

    /** A point on each object's copy only (right of the axis). */
    private val onBoxCopy = Vec2(kit.w - 90f, 80f)
    private val onShapeCopy = Vec2(kit.w - 90f, 250f)
    private val onLineCopy = Vec2(kit.w - 105f, 335f)

    private val layerShape = ShapeObject(
        ShapeType.ELLIPSE, cx = 90f, cy = 250f, w = 60f, h = 40f, style = ShapeStyle.STROKE_FILL, strokeWidth = 5f,
        strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt(), copies = copies,
    )

    // ------------------------------------------------------------------ vector-layer fixture

    private fun vectorController(objects: List<VObject> = content.objects): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, objects, "Add")
        settle(c)
        return c
    }

    private fun settle(c: EditorController) {
        c.vectors.flushPending()
        kit.idleUntil { !c.vectors.isRendering }
    }

    @Test
    fun renderDrawsEveryCopy() {
        val c = vectorController()
        val pix = kit.pixels(c.vec.bitmap)
        assertArrayEquals(kit.render(c.vec.vector!!), pix)
        val pieces = content.objects.flatMap { ObjectCopies.expanded(it) }.mapIndexed { i, p -> p.withId(10L + i) }
        assertNearlySame(kit.render(VectorContent(objects = pieces, nextId = 20)), pix)
        for (p in listOf(onBoxCopy, onShapeCopy, onLineCopy)) assertTrue("painted at $p", pix[p.y.toInt() * kit.w + p.x.toInt()] != 0)
    }

    @Test
    fun boundsHitPickAndLassoSeeEveryCopy() {
        val c = vectorController()
        val v = c.vec.vector!!
        for (o in v.objects) assertTrue("${o.id}: bounds reach the copy", VectorOps.bounds(o).right > axis + 100f)
        assertTrue(ObjectIndex.of(v).unionBounds().right >= kit.w - 40f)
        assertEquals(1L, c.vectors.hitTest(c.vec, onBoxCopy, 2f)?.id)
        assertEquals(2L, c.vectors.hitTest(c.vec, onShapeCopy, 2f)?.id)
        assertEquals(3L, c.vectors.hitTest(c.vec, onLineCopy, 3f)?.id)
        assertEquals(setOf(1L, 2L, 3L), VectorOps.touching(v, kit.rectSelection(kit.w / 2 + 20, 0, kit.w, kit.h)))
    }

    @Test
    fun theObjectEraserTakesTheWholeObjectFromAnyCopy() {
        val s = EraseSession(content, VectorEraseMode.OBJECT, TargetCache())
        s.add(onBoxCopy.x, onBoxCopy.y, 4f)
        val after = s.result()
        assertNotNull(after)
        assertEquals(listOf(2L, 3L), after!!.objects.map { it.id })
        assertSame(shape, after.objects[0])
    }

    @Test
    fun thePartialEraserCutsTheTouchedCopyOnly() {
        val s = EraseSession(content, VectorEraseMode.PARTIAL, TargetCache())
        s.add(onLineCopy.x, onLineCopy.y, 4f)
        val after = s.result()
        assertNotNull(after)
        val objs = after!!.objects
        // The box and the shape are untouched (with their copies); the line is now its source
        // and the two pieces of its cut copy, all plain.
        assertSame(box, objs[0])
        assertSame(shape, objs[1])
        assertEquals(ObjectCopies.source(line), objs[2])
        assertEquals(5, objs.size)
        assertTrue(objs.drop(2).none { ObjectCopies.hasCopies(it) })
        assertTrue(objs.drop(3).all { VectorOps.bounds(it).left > axis })
    }

    @Test
    fun theFillHitsAnyCopy() {
        val hit = FillHits.find(content, onBoxCopy, 2f, TargetCache())
        assertNotNull(hit)
        assertEquals(1L, hit!!.obj.id)
        assertEquals(FillPart.FILL, hit.part)
        assertEquals(2L, FillHits.find(content, onShapeCopy, 2f, TargetCache())?.obj?.id)
    }

    @Test
    fun pathfinderOperandsUniteTheCopies() {
        for (o in listOf<VObject>(box, shape)) {
            val one = PathConvert.area(PathConvert.region(ObjectCopies.source(o))!!)
            val all = PathConvert.area(PathConvert.region(o)!!)
            assertEquals(2f * one, all, one * 0.01f)
        }
    }

    @Test
    fun exportWritesOnePartPerCopy() {
        val kept = exportItems(vectorController(), "Vector 1")
        val sources = exportItems(vectorController(content.objects.map { ObjectCopies.source(it) }), "Vector 1")
        assertEquals(2 * sources.size, kept.size)
        assertTrue(kept.all { it is SceneItem.Shape })
    }

    @Test
    fun transformsKeepConjugateOrBakeTheCopies() {
        val t = floatArrayOf(1f, 0f, 12f, 0f, 1f, -6f, 0f, 0f, 1f)
        val h = floatArrayOf(1f, 0.05f, 4f, 0.02f, 1f, 2f, 0.0003f, 0.0001f, 1f)
        for (o in content.objects) {
            // Transform (and the lift's affine maps): kept, conjugated.
            val moved = VectorOps.transformed(o, t)
            assertTrue(ObjectCopies.hasCopies(moved))
            assertEquals(VectorOps.bounds(o).width(), VectorOps.bounds(moved).width(), 0.05f)
            // Distort / Free deform: expanded, every piece mapped.
            assertEquals(2, ObjectCopies.transformedAll(o, h).size)
        }
        // A folder lift (LayerDataTransforms) and Flip map the whole content the same way.
        val lifted = LayerDataTransforms.mapped(content, h)
        assertEquals(6, lifted.objects.size)
        assertTrue(lifted.objects.none { ObjectCopies.hasCopies(it) })
        val flipped = VectorLayerOps.flipped(content, kit.w, kit.h, horizontal = true)!!
        for (k in content.objects.indices) {
            assertTrue(ObjectCopies.hasCopies(flipped.objects[k]))
            // (A Mirror-symmetric object flipped about the same axis covers the same area.)
            val a = VectorOps.bounds(content.objects[k]); val b = VectorOps.bounds(flipped.objects[k])
            assertEquals(a.left, b.left, 0.05f); assertEquals(a.right, b.right, 0.05f)
        }
    }

    @Test
    fun importAndArraysKeepTheCopies() {
        assertEquals(content, PayloadImport.sound(content))
        assertTrue(ArrayDraw.sourceBounds(LayerData(vector = content))!!.right >= kit.w - 40f)
        val arrayed = ArrayDraw.effectiveVector(
            LayerData(vector = content, array = LayerArray(ArraySpec(count = 2, relativeX = 0f, relativeY = 0f, constantX = 0f, constantY = 6f))),
        )!!
        assertEquals(6, arrayed.objects.size)
        // Every array copy repeats every mirror copy.
        assertTrue(arrayed.objects.all { ObjectCopies.hasCopies(it) })
    }

    // ------------------------------------------------------------------ shape-layer fixture

    private fun shapeLayerController(s: ShapeObject = layerShape): Pair<EditorController, Layer> {
        val c = kit.controller()
        val layer = Layer(c.doc.newLayerId(), "Shape 1", BitmapUtils.createLayerBitmap(kit.w, kit.h)).also { it.shapeData = ShapeCodec.encode(s) }
        c.doc.layers += layer
        c.notifyLayersChanged()
        return c to layer
    }

    @Test
    fun aShapeLayerKeepsItsCopiesThroughItsCodecAndPicks() {
        val data = ShapeCodec.encode(layerShape)
        assertEquals(copies, ShapeCodec.decode(data)!!.copies)
        val (c, layer) = shapeLayerController()
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertSame(layer, tool.shapeLayerAt(onShapeCopy))
        assertTrue(ObjectCopies.hits(layerShape, onShapeCopy, 2f))
        val one = PathConvert.area(PathConvert.region(ObjectCopies.source(layerShape)))
        assertEquals(2f * one, PathConvert.area(PathConvert.region(layerShape)), one * 0.01f)
    }

    @Test
    fun aShapeLayerTransformConjugatesAndItsLiftDrawsEveryCopy() {
        val t = floatArrayOf(1f, 0f, 10f, 0f, 1f, 4f, 0f, 0f, 1f)
        val mapped = ShapeCodec.decode(ShapeTransforms.mapped(ShapeCodec.encode(layerShape), t)!!)!!
        assertEquals(2, mapped.copies.count)
        val expected = ObjectCopies.expanded(layerShape).map { VectorOps.bounds(VectorOps.transformed(it, t)) }
        val actual = ObjectCopies.expanded(mapped).map { VectorOps.bounds(it) }
        for (k in expected.indices) {
            assertEquals(expected[k].left, actual[k].left, 0.05f); assertEquals(expected[k].right, actual[k].right, 0.05f)
        }
        val (c, layer) = shapeLayerController()
        val data = LayerData(shape = layer.shapeData)
        assertTrue(DataRender.paintBounds(data)!!.right > axis + 100f)
        assertTrue(DataRender.apply(c, layer, data, "Lift", Rect(), allowHidden = false))
        val pix = kit.pixels(layer.bitmap)
        assertArrayEquals(kit.render(VectorContent(objects = listOf(VShape(1, shape = layerShape)), nextId = 2)), pix)
        assertTrue(pix[onShapeCopy.y.toInt() * kit.w + onShapeCopy.x.toInt()] != 0)
    }

    @Test
    fun aShapeLayerArraySourceAndItsConversionKeepTheCopies() {
        val b = Bitmap.createBitmap(kit.w, kit.h, Bitmap.Config.ARGB_8888)
        ArraySources.shapeDraw(layerShape, ColorMode.RGB, kit.w, kit.h)(Canvas(b))
        assertTrue(b.getPixel(onShapeCopy.x.toInt(), onShapeCopy.y.toInt()) != 0)
        assertTrue(b.getPixel(90, 250) != 0)
        b.recycle()
        assertTrue(ArrayDraw.sourceBounds(LayerData(shape = ShapeCodec.encode(layerShape)))!!.right > axis + 100f)
        val (c, layer) = shapeLayerController()
        assertTrue(c.convertToVectorLayer(layer))
        assertEquals(copies, (layer.vector!!.objects.single() as VShape).shape.copies)
    }

    @Test
    fun aShapeLayerExportsOnePartPerCopy() {
        val (c, _) = shapeLayerController()
        val (plain, _) = shapeLayerController(ObjectCopies.source(layerShape))
        val kept = exportItems(c, "Shape 1")
        val one = exportItems(plain, "Shape 1")
        assertTrue(one.isNotEmpty())
        assertEquals(2 * one.size, kept.size)
    }

    @Test
    fun theShapeToolReopensAShapeLayerWithItsStoredMaps() {
        val (c, layer) = shapeLayerController()
        c.selectTool(ToolId.SHAPE)
        kit.idle()
        val tool = c.tools.getValue(ToolId.SHAPE) as ShapeTool
        assertTrue(tool.editLayer(layer))
        assertEquals(copies, tool.pendingObject()!!.copies)
        tool.discard()
    }

    // ------------------------------------------------------------------ source audit

    @Test
    fun everyReaderGoesThroughObjectCopiesOrIsDocumented() {
        val root = sourceRoot() ?: return
        val readers = root.walkTopDown().filter { it.isFile && it.name.endsWith(".kt") }.mapNotNull { f ->
            val code = withoutComments(f.readText())
            if (!READER.containsMatchIn(code)) null else f.relativeTo(root).invariantSeparatorsPath to code
        }.toMap()
        val unrouted = readers.filter { (_, code) -> !ROUTED.containsMatchIn(code) }.keys
        val undocumented = unrouted - DOCUMENTED.keys
        if (undocumented.isNotEmpty()) fail("readers of object geometry without copies (route them through ObjectCopies or document them): $undocumented")
        val stale = DOCUMENTED.keys - unrouted
        if (stale.isNotEmpty()) fail("documented readers that no longer read geometry without copies (drop them from the list): $stale")
        for (path in ROUTED_SITES) {
            val code = readers[path] ?: withoutComments(File(root, path).readText())
            assertTrue("$path goes through ObjectCopies", code.contains("ObjectCopies"))
        }
        // The Object bar detaches through ObjectActions, under the frozen label.
        val bar = withoutComments(File(root, "ui/vector/VectorObjectBar.kt").readText())
        assertTrue("the Object bar detaches copies", bar.contains("ObjectActions.detachCopies(") && bar.contains("MirrorLabels18.DETACH"))
    }

    // ------------------------------------------------------------------ helpers

    private fun exportItems(c: EditorController, layerName: String): List<SceneItem> = runBlocking {
        ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
    }.layers.first { it.name == layerName }.items

    private fun assertNearlySame(expected: IntArray, actual: IntArray) {
        var off = 0
        for (i in expected.indices) {
            val a = expected[i]; val b = actual[i]
            if (a == b) continue
            var d = 0
            for (s in intArrayOf(0, 8, 16, 24)) d = maxOf(d, abs((a ushr s and 0xFF) - (b ushr s and 0xFF)))
            if (d > 8) off++
        }
        assertTrue("$off pixels differ", off <= expected.size / 2000)
    }

    private fun sourceRoot(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        return null
    }

    /** [s] without its `//` and block comments (string contents are left alone; good enough here). */
    private fun withoutComments(s: String): String = s.replace(BLOCK_COMMENT, " ").replace(LINE_COMMENT, "")

    private companion object {
        val BLOCK_COMMENT = Regex("/\\*[\\s\\S]*?\\*/")
        val LINE_COMMENT = Regex("(?m)(?<![:\"])//.*$")

        /** Reading an object's geometry directly (not through `VectorOps.bounds` / `hit` / `transformed`, which read the copies). */
        val READER = Regex(
            "ShapeOutlines\\.(hits|paintSpec|outline|brushOutline|arrow|reach|join)\\(|ShapeCodec\\.decode\\(|" +
                "VectorOps\\.(toVectorPath|toPaths|curveAnchors)\\(|\\.subpaths\\b",
        )

        /** Code that reads the copies. */
        val ROUTED = Regex("ObjectCopies\\.|\\.copies\\b")

        /** Readers that don't read the copies themselves, and what they do with them (§4.3). */
        val DOCUMENTED: Map<String, String> = linkedMapOf(
            "EditorController.kt" to "convertToVectorLayer: kept, the VShape takes the decoded ShapeObject with its copies",
            "array/ArrayOps.kt" to "applyShape: kept, VectorOps.transformed of the decoded shape conjugates them",
            "array/ArrayTransforms.kt" to "kept, ShapeAffine.mapped conjugates them",
            "tools/array/GuideEditor.kt" to "source only (§4.3): a guide is the source path; its copies are not guides",
            "tools/transform/DataLift.kt" to "kept: drawn by VectorLayerRenderer, measured by VectorOps.bounds",
            "tools/vector/ShapeToSpline.kt" to "a producer (§4.3); Turn into path with copies is area F's",
            "tools/vector/ShapeTransforms.kt" to "kept, ShapeAffine.mapped conjugates them",
            "tools/vector/spline/SplineBezier.kt" to "a producer (§4.3): one subpath's spline form",
            "vector/draw/VectorFill.kt" to "through EraseTargets: EraseTarget.parts holds one part per copy",
            "vector/pathfinder/PathfinderStyles.kt" to "the style of a result (colors, widths), not an operand's geometry",
        )

        /** Sites of §4.3's table that F2 routed through `ObjectCopies` (a revert fails here). */
        val ROUTED_SITES = listOf(
            "vector/VectorOps.kt",
            "vector/render/VectorLayerRenderer.kt",
            "vector/geom/TileSet.kt",
            "vector/draw/EraseSession.kt",
            "vector/draw/EraseTargets.kt",
            "vector/lift/VectorObjectLift.kt",
            "vector/LayerDataTransforms.kt",
            "vector/VectorLayerOps.kt",
            "vector/select/ObjectActions.kt",
            "exchange/PayloadImport.kt",
            "exchange/export/ExportSceneBuilder.kt",
            "tools/pathfinder/PathfinderTool.kt",
            "array/ArraySources.kt",
            "tools/vector/ShapeTool.kt",
        )
    }
}
