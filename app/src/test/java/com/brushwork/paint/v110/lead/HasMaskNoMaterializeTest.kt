package com.brushwork.paint.v110.lead

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Matrix
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.tools.LayerToolRules
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.layers.LayerOps
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.10 F1 (design 2.2 and 3.3, finding 44; lead, frozen): the mask presence checks F1 moved to
 * `Layer.hasMask` answer without materializing a parked mask. Each runs under
 * [PixelFacade.strict] (a materialization would throw) on layers whose masks are parked
 * ([TiledPixels]), and the facade's count does not move: the layer budget's count and room, the
 * format's open count and version, the controller's edit target, empty-layer test, layer count
 * and editable check (with `admitWrite` on a blank layer), the tool rules, and the early returns
 * of "Add mask" on a layer that has one. The composable sites (the layer row, the resize tabs,
 * the share sheet's plain-layer test, the mask components sheet) use the same property.
 */
@RunWith(RobolectricTestRunner::class)
class HasMaskNoMaterializeTest {
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private val made = mutableListOf<Bitmap>()
    private val w = 64
    private val h = 48

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        scope.cancel()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    private fun parkedMask(seed: Long): TiledPixels = TiledPixels.fromBitmap(keep(F1Pixels.grayNoise(w, h, seed)))

    @Test
    fun presenceChecksNeverMaterializeAParkedMask() {
        val context = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("hasmask", "Has mask", w, h)
        val plain = Layer(doc.newLayerId(), "Plain", keep(BitmapUtils.createLayerBitmap(w, h)))
        val masked = Layer(doc.newLayerId(), "Masked", keep(F1Pixels.noise(w, h, 3L))).apply { maskPixels = parkedMask(4L); editingMask = true }
        val adjust = Layer(doc.newLayerId(), "Adjust", BlankPixels(w, h)).apply { adjustment = AdjustmentSpec(); maskPixels = parkedMask(5L) }
        val blank = Layer(doc.newLayerId(), "Blank", BlankPixels(w, h)).apply { maskPixels = parkedMask(6L) }
        doc.layers += listOf(plain, masked, adjust, blank)
        doc.activeLayerIndex = 1
        val settings = AppSettings(context)
        settings.prefs.edit().clear().commit()
        val c = EditorController(context, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        val masks = listOf(masked, adjust, blank).map { it.maskPixels }
        val before = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            // Four painted layers and three masks.
            assertEquals("LayerBudgetV19.used", 7, LayerBudgetV19.used(doc))
            assertTrue("LayerBudgetV19.canAdd", LayerBudgetV19.canAdd(doc))
            assertEquals("LayerBudgetV19.room", 93, LayerBudgetV19.room(doc))
            assertTrue("LayerBudgetV19.canAddAdjustment", LayerBudgetV19.canAddAdjustment(doc))
            assertEquals("ProjectFormat.v19OpenCount", 7, ProjectFormat.v19OpenCount(doc.layers, w, h))
            assertEquals("ProjectFormat.writtenVersion (an adjustment layer)", 2, ProjectFormat.writtenVersion(doc.layers, w, h))
            assertEquals("effectiveLayerCount", 7, c.effectiveLayerCount)
            assertTrue("canAddLayer", c.canAddLayer)
            assertEquals("editTargetOf, editing the mask", EditTarget.MASK, c.editTargetOf(masked))
            assertEquals("editTargetOf, an adjustment layer with a mask", EditTarget.MASK, c.editTargetOf(adjust))
            assertEquals("editTargetOf, no mask", EditTarget.CONTENT, c.editTargetOf(plain))
            assertFalse("isEmptyPlainLayer, a mask", c.isEmptyPlainLayer(blank))
            assertTrue("isEmptyPlainLayer, no mask and transparent", c.isEmptyPlainLayer(plain))
            assertTrue("checkEditable, the mask of a flat layer", c.checkEditable(masked))
            assertTrue("checkEditable, a blank layer (admitWrite admits without materializing)", c.checkEditable(blank))
            assertNull("LayerToolRules: the brush paints the adjustment layer's mask", LayerToolRules.refusal(ToolId.BRUSH, adjust))
            assertNull("LayerToolRules: the bucket too", LayerToolRules.refusal(ToolId.FILL, adjust))
            c.addMask(masked)
            LayerOps.addMask(c, masked, fromSelection = false)
        } finally {
            PixelFacade.strict = false
        }
        assertEquals("no materialization", before, PixelFacade.materializations)
        listOf(masked, adjust, blank).forEachIndexed { i, l -> assertSame("${l.name}: the same parked mask", masks[i], l.maskPixels) }
        assertTrue("the blank layer is still blank", blank.pixels is BlankPixels)
        assertEquals("Add mask changed nothing", 0, c.undoManager.undoCount)
    }
}
