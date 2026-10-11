package com.brushwork.paint.exchange.export

import com.brushwork.paint.EditorController
import com.brushwork.paint.assist.SymmetryMaps
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.LayerDataTransforms
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeKind
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.VectorOps
import com.brushwork.paint.vector.select.ObjectTestKit
import com.brushwork.paint.vector.select.vec
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.8 F2 (I15, I17; design §3.1, §4.1 F2): export writes ONE part per copy, and perspective
 * copies never reach it. An object with live Mirror copies exports its fill and line once per
 * copy (the source first, then the copy where the canvas shows it); a brush line kept as a
 * picture is one picture of the source with its copies, after every copy's fill. A perspective
 * commit bakes plain objects (no `CopyMaps` anywhere, so the payload carries none) and so does a
 * homography applied to an object with copies (Distort, Free deform): each is exported as its
 * own part.
 */
@RunWith(RobolectricTestRunner::class)
class PerspectiveBakeExportTest {
    private val kit = ObjectTestKit()

    @After
    fun tearDown() = kit.close()

    private val mirror = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.MIRROR), kit.w, kit.h)
    private val box = kit.box(40f, 40f, 140f, 120f)

    private fun controllerWith(objects: List<VObject>): EditorController {
        val c = kit.controller()
        c.vectors.addObjects(c.vec, objects, "Add")
        c.vectors.flushPending()
        kit.idleUntil { !c.vectors.isRendering }
        return c
    }

    private fun scene(c: EditorController, strokes: StrokeExport = StrokeExport.OUTLINES): ExportScene = runBlocking {
        ExportSceneBuilder(c, ExportOptions(VectorFormat.SVG, strokes = strokes), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build()
    }

    private fun items(scene: ExportScene): List<SceneItem> = scene.layers.first { it.name == "Vector 1" }.items

    private fun shapeBounds(item: SceneItem) = (item as SceneItem.Shape).path.controlBounds()!!

    @Test
    fun anObjectWithCopiesExportsOnePartPerCopy() {
        val o = ObjectCopies.withCopies(box, CopyMaps.of(mirror))
        val kept = items(scene(controllerWith(listOf(o))))
        val pieces = items(scene(controllerWith(ObjectCopies.expanded(o))))
        assertEquals(2, kept.size)
        assertEquals(pieces.size, kept.size)
        assertTrue(kept.all { it is SceneItem.Shape })
        for (k in kept.indices) {
            val a = shapeBounds(kept[k]); val b = shapeBounds(pieces[k])
            assertEquals(b.left, a.left, 0.01f); assertEquals(b.right, a.right, 0.01f)
            assertEquals(b.top, a.top, 0.01f); assertEquals(b.bottom, a.bottom, 0.01f)
        }
        assertEquals(40f, shapeBounds(kept[0]).left, 0.01f)
        assertEquals(kit.w - 40f, shapeBounds(kept[1]).right, 0.01f)
    }

    @Test
    fun aBrushLineKeptAsAPictureIsOnePictureWithItsCopies() {
        val brushBox: VPath = box.copy(
            stroke = VStrokeStyle(VStrokeKind.BRUSH, color = 0xFF2050C0.toInt(), width = 8f, brushTool = ToolId.BRUSH, brush = BrushLibrary.defaultBrush.copy(size = 8f), seed = 3L),
        )
        val o = ObjectCopies.withCopies(brushBox, CopyMaps.of(mirror))
        val items = items(scene(controllerWith(listOf(o)), StrokeExport.PICTURES))
        // Every copy's fill, then the one picture of the brush line.
        assertEquals(3, items.size)
        assertTrue(items[0] is SceneItem.Shape && items[1] is SceneItem.Shape)
        assertTrue(items[2] is SceneItem.Image)
        val pic = (items[2] as SceneItem.Image).image
        assertTrue("the picture spans both copies", pic.left < 60 && pic.left + pic.width > kit.w - 60)
    }

    @Test
    fun perspectiveCopiesAreBakedBeforeTheyReachTheExport() {
        val maps = SymmetryMaps.transforms(SymmetrySettings(type = SymmetryType.PERSPECTIVE_ARRAY), kit.w, kit.h, kit.w / 2f, kit.h / 2f)
        assertTrue(maps.size >= 2)
        assertNull(ObjectCopies.fromSymmetry(maps))
        val small = kit.box(240f, 180f, 270f, 200f)
        val baked = ObjectCopies.baked(small, maps)
        val c = controllerWith(baked)
        assertTrue(c.vec.vector!!.objects.none { ObjectCopies.hasCopies(it) })
        val scene = scene(c)
        assertEquals(maps.size, items(scene).size)
        assertNoCopies(scene)
    }

    @Test
    fun aHomographyOnAnObjectWithCopiesExportsItsPieces() {
        val o = ObjectCopies.withCopies(box, CopyMaps.of(mirror))
        val h = floatArrayOf(1f, 0.05f, 4f, 0.02f, 1f, 2f, 0.0003f, 0.0001f, 1f)
        val distorted = LayerDataTransforms.mapped(VectorContent(objects = listOf(o.withId(1)), nextId = 2), h)
        assertEquals(listOf(1L, 2L), distorted.objects.map { it.id })
        assertTrue(distorted.objects.none { ObjectCopies.hasCopies(it) })
        val c = controllerWith(distorted.objects)
        val scene = scene(c)
        assertEquals(2, items(scene).size)
        assertNoCopies(scene)
        // An affine map keeps the copies (conjugated): still two parts.
        val moved = LayerDataTransforms.mapped(VectorContent(objects = listOf(o.withId(1)), nextId = 2), floatArrayOf(1f, 0f, 5f, 0f, 1f, 7f, 0f, 0f, 1f))
        assertTrue(ObjectCopies.hasCopies(moved.objects.single()))
        assertEquals(2, items(scene(controllerWith(moved.objects))).size)
        assertEquals(VectorOps.bounds(o).width(), VectorOps.bounds(moved.objects.single()).width(), 0.01f)
    }

    private fun assertNoCopies(scene: ExportScene) {
        val payload = scene.payload
        assertNotNull(payload)
        for (l in payload!!.layers) l.vector?.objects?.forEach { assertTrue(!ObjectCopies.hasCopies(it)) }
    }
}
