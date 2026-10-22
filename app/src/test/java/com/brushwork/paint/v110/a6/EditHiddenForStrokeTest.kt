package com.brushwork.paint.v110.a6

import android.graphics.Matrix
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.StripTestKit
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.common.ObjectBarLabels110
import com.brushwork.paint.ui.common.V110Tags
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.vector.VectorObjectBar
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.select.ObjectActions
import com.brushwork.paint.vector.select.ObjectTestKit
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 A6 (R10, R11): the object bar shows [ObjectBarLabels110.EDIT] right after Transform only
 * while exactly one object with an editor is selected: a path or a shape, never a brush stroke
 * (it has no editor) nor several objects. "Turn into shape" waits on the More row only for one
 * path. On the user's 392 dp phone.
 */
// Own sandbox (the test recomposer policy and paused Choreographer are global).
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a6.edithiddensandbox"])
class EditHiddenForStrokeTest {

    @Test
    fun editShowsForOnePathOrShapeOnly() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val kit = ObjectTestKit(400, 300)
        val doc = Document("edit", "Edit", 400, 300)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(400, 300))
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(400, 300)).also { it.vector = VectorContent.EMPTY }
        doc.activeLayerIndex = 1
        val c = Smoke.controller(activity, doc)
        c.viewTransform.set(Matrix())
        val layer = doc.layers[1]
        // 1: a brush stroke, 2: a path, 3: a shape.
        c.vectors.addObjects(layer, listOf(kit.stroke(30f, 60f, 200f, 80f), kit.box(60f, 40f, 160f, 140f), kit.ellipse(280f, 200f)), "Add")
        activity.setContent {
            BrushworkTheme {
                Box(Modifier.fillMaxWidth()) {
                    if (c.vectors.selectedIds.isNotEmpty()) VectorObjectBar(c, Modifier.padding(horizontal = 8.dp))
                }
            }
        }
        fun select(vararg ids: Long) {
            c.vectors.setSelection(layer, ids.toSet())
            SmokeUi.settle()
        }

        // A brush stroke: no Edit; Front and Back keep their places on row 1.
        select(1L)
        assertFalse(ObjectActions.editShown(c))
        assertNull("no Edit for a brush stroke", tagged(V110Tags.OBJECTS_EDIT))
        assertNotNull(clickable("Bring to front"))
        assertFalse(ObjectActions.toShapeShown(c))
        assertTrue(StripTestKit.openObjectRow())
        assertNull("no Turn into shape for a brush stroke", tagged(V110Tags.OBJECTS_TO_SHAPE))
        assertTrue(StripTestKit.closeObjectRow())

        // A path: Edit right after Transform, before Deselect; Turn into shape on the More row.
        select(2L)
        assertTrue(ObjectActions.editShown(c))
        val edit = tagged(V110Tags.OBJECTS_EDIT) ?: throw AssertionError("no Edit for a path")
        val transform = clickable("Transform the selected objects")!!
        val deselect = clickable("Deselect the objects")!!
        assertTrue("after Transform: $transform, $edit", transform.right <= edit.left + 1f)
        assertTrue("before Deselect: $edit, $deselect", edit.right <= deselect.left + 1f)
        assertEquals("on row 1", transform.top, edit.top, 1f)
        assertNotNull("its click label", clickable(ObjectBarLabels110.EDIT_DESC))
        assertTrue(ObjectActions.toShapeShown(c))
        assertTrue(StripTestKit.openObjectRow())
        assertNotNull("Turn into shape for a path", tagged(V110Tags.OBJECTS_TO_SHAPE))
        assertTrue(StripTestKit.closeObjectRow())

        // A shape: Edit, but it is a shape already.
        select(3L)
        assertNotNull("Edit for a shape", tagged(V110Tags.OBJECTS_EDIT))
        assertFalse(ObjectActions.toShapeShown(c))
        assertTrue(StripTestKit.openObjectRow())
        assertNull(tagged(V110Tags.OBJECTS_TO_SHAPE))
        assertTrue(StripTestKit.closeObjectRow())

        // Several objects (the stroke among them or not): no Edit.
        select(2L, 3L)
        assertTrue(SmokeUi.has("2 objects selected", exact = true))
        assertNull("no Edit for several objects", tagged(V110Tags.OBJECTS_EDIT))
        select(1L, 2L)
        assertNull(tagged(V110Tags.OBJECTS_EDIT))
        assertFalse(ObjectActions.editShown(c))
        kit.close()
    }

    /** Bounds (window px) of the placed node tagged [tag], or null. */
    private fun tagged(tag: String): Rect? = RobolectricUi.elements()
        .lastOrNull { it.node.layoutInfo.isPlaced && it.node.config.getOrNull(SemanticsProperties.TestTag) == tag }?.bounds

    /** Bounds (window px) of the clickable whose click label is [clickLabel], or null. */
    private fun clickable(clickLabel: String): Rect? = RobolectricUi.elements()
        .lastOrNull { it.node.config.getOrNull(SemanticsActions.OnClick)?.label == clickLabel }?.bounds
}
