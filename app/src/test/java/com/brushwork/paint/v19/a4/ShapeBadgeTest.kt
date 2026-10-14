package com.brushwork.paint.v19.a4

import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Layer
import com.brushwork.paint.ui.layers.LayerKindBadge
import com.brushwork.paint.ui.layers.LayerRowModel
import com.brushwork.paint.vector.VShape
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 (R14, A4): a vector layer whose objects are all shapes ([VShape]) shows the shape badge in
 * the layers panel ([LayerRowModel.showsShapeBadge]); it is derived from the objects on every
 * build and never stored, so it follows undo, and the layer keeps its vector kind (its menus).
 */
@RunWith(RobolectricTestRunner::class)
class ShapeBadgeTest {
    private val kit = A4Kit()

    @After
    fun tearDown() = kit.close()

    private fun row(c: EditorController, layer: Layer): LayerRowModel =
        LayerRowModel.build(c.doc, c.doc.layers.asReversed()).first { it.layer === layer }

    private fun shape(o: com.brushwork.paint.tools.vector.ShapeObject) = VShape(0L, shape = o)

    @Test
    fun aVectorLayerOfShapesOnlyShowsTheShapeBadge() {
        val c = kit.controller()
        val layer = kit.vectorLayer(c, listOf(shape(kit.rect(60f, 50f, 70f, 50f)), shape(kit.rect(160f, 110f, 50f, 40f))), "Shapes 1")
        val r = row(c, layer)
        assertTrue(r.shapesOnly)
        assertTrue(r.showsShapeBadge)
        assertEquals("still a vector layer (its menus)", LayerKindBadge.VECTOR, r.kind)
        assertTrue(r.isVector)
        assertFalse(r.isShape)
    }

    @Test
    fun aPathOrNoObjectAtAllShowsTheVectorBadge() {
        val c = kit.controller()
        val empty = kit.vectorLayer(c, emptyList(), "Empty")
        assertFalse("no objects", row(c, empty).showsShapeBadge)
        val mixed = kit.vectorLayer(c, listOf(shape(kit.rect(60f, 50f, 70f, 50f)), kit.box(120f, 100f, 200f, 160f)), "Mixed")
        assertFalse("a path among the shapes", row(c, mixed).showsShapeBadge)
        val raster = c.addLayer()!!
        assertFalse("a raster layer", row(c, raster).showsShapeBadge)
    }

    @Test
    fun aLegacyShapeLayerKeepsItsOwnShapeKind() {
        val c = kit.controller()
        val legacy = kit.shapeLayer(c, kit.rect(60f, 50f, 70f, 50f))
        val r = row(c, legacy)
        assertTrue(r.isShape)
        assertTrue(r.showsShapeBadge)
        assertFalse("derived for vector layers only", r.shapesOnly)
    }

    @Test
    fun theBadgeFollowsTheObjectsThroughUndoAndIsNeverStored() {
        val c = kit.controller()
        val layer = kit.vectorLayer(c, listOf(shape(kit.rect(60f, 50f, 70f, 50f))), "Shapes 1")
        val data = layer.dataSnapshot()
        assertTrue(row(c, layer).showsShapeBadge)
        c.vectors.addObjects(layer, listOf(kit.box(120f, 100f, 200f, 160f)), "Add")
        kit.settle(c)
        assertFalse("a path joined", row(c, layer).showsShapeBadge)
        c.undo()
        kit.settle(c)
        assertTrue("undone", row(c, layer).showsShapeBadge)
        assertEquals("nothing stored for it", data, layer.dataSnapshot())
    }

    @Test
    fun twoMergedShapeLayersShowTheShapeBadge() {
        val c = kit.controller()
        kit.shapeLayer(c, kit.rect(60f, 50f, 70f, 50f), "Lower")
        val upper = kit.shapeLayer(c, kit.rect(160f, 110f, 50f, 40f), "Upper")
        c.mergeDown(upper)
        kit.settle(c)
        val merged = c.activeLayer
        assertTrue(merged.isVectorLayer)
        assertTrue(row(c, merged).showsShapeBadge)
    }
}
