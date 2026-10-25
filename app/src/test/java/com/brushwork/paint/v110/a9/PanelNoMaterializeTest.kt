package com.brushwork.paint.v110.a9

import android.graphics.Bitmap
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.compose.ui.unit.dp
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PixelFacade
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.testing.EngineRule
import com.brushwork.paint.testing.FakeMemoryProbe
import com.brushwork.paint.testing.MemoryPreset
import com.brushwork.paint.ui.layers.FolderThumbnails
import com.brushwork.paint.ui.layers.LayerLabels
import com.brushwork.paint.ui.layers.LayerThumbnails
import com.brushwork.paint.ui.layers.LayersPanel
import com.brushwork.paint.ui.theme.BrushworkTheme
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotSame
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config

/**
 * v1.10 A9 step 4 (design 2.16 "Compositor and thumbnails": the panel case of the facade's
 * materialization tests; 4.4 A9 step 4, lazy thumbnail keys). With every layer parked (masked
 * layers and a folder among them) the layers panel never materializes a layer
 * (`PixelFacade.strict`): `LayerThumbnails` keys on (id, `contentVersion`, `pixelGeneration`),
 * keeps a picture made while the layer was flat across a park, and otherwise shows a placeholder
 * (exact for a blank layer); `FolderThumbnails.key` mixes `pixelGeneration`, `contentVersion` and
 * `hasMask`, so a park leaves a folder's picture current, and a folder's picture renders from the
 * tiles. The whole panel, composed at the user's phone size with its folder pictures rendered, then
 * materializes nothing.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.v110.a9.panelnomaterializesandbox"])
class PanelNoMaterializeTest {
    @get:Rule
    val engine = EngineRule(EngineLevel.TILES, memory = FakeMemoryProbe(MemoryPreset.T606), residency = ChaosResidency())

    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        PixelFacade.strict = false
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun keep(b: Bitmap) = b.also { made += it }

    /** A backdrop, a masked layer, a folder of two layers (one masked), a blank layer and a top layer. */
    private fun document(): Document {
        val d = Document("panel", "panel", A9Docs.W, A9Docs.H)
        fun layer(name: String, b: Bitmap, parent: Long = Layer.ROOT_ID) = Layer(d.newLayerId(), name, keep(b)).apply { parentId = parent }
        d.layers += layer("Backdrop", A9Docs.backdrop(1L))
        d.layers += layer("Masked", A9Docs.content(2L)).apply {
            mask = keep(A9Docs.mask(3L))
            maskEnabled = true
        }
        val f = Layer.newFolder(d.newLayerId(), "Folder", FolderSpec(passThrough = false))
        d.layers += layer("Child 1", A9Docs.content(4L), f.id)
        d.layers += layer("Child 2", A9Docs.content(5L), f.id).apply {
            mask = keep(A9Docs.mask(6L))
            maskEnabled = true
        }
        d.layers += f
        d.layers += Layer(d.newLayerId(), "Blank", BlankPixels(A9Docs.W, A9Docs.H))
        d.layers += layer("Top", A9Docs.content(7L))
        d.activeLayerIndex = d.layers.lastIndex
        return d
    }

    private fun park(d: Document, except: Layer? = null) {
        val chaos = ChaosResidency()
        for (l in d.layers) if (!l.isFolder && l.pixels !is BlankPixels && l !== except) {
            assertTrue("${l.name} parks", chaos.park(l))
            assertTrue(l.pixels is TiledPixels)
        }
    }

    private fun <T> strict(what: String, block: () -> T): T {
        val mats = PixelFacade.materializations
        PixelFacade.strict = true
        try {
            return block()
        } finally {
            PixelFacade.strict = false
            assertEquals("$what: nothing materialized", mats, PixelFacade.materializations)
        }
    }

    @Test
    fun thumbnailsNeverMaterializeAndSurviveAPark() {
        val d = document()
        val thumbs = LayerThumbnails(96)
        val masked = d.layers[1]
        val top = d.layers.last()
        // Made while flat.
        val maskedPicture = thumbs.content(masked)
        val maskPicture = thumbs.mask(masked)!!
        val made = thumbs.made
        val folders = FolderThumbnails()
        val compositor = Compositor(d) { null }
        val folder = d.layers.first { it.isFolder }
        assertTrue(folders.render(compositor, d, folder))
        val flatFolderPicture = folders.image(folder.id)!!.asAndroidBitmap().copy(Bitmap.Config.ARGB_8888, false).also { keep(it) }
        val listed = d.layers.mapTo(HashSet()) { it.id }
        park(d)
        strict("the thumbnails of parked layers") {
            assertSame("a park keeps the picture (same key)", maskedPicture, thumbs.content(masked))
            assertSame("and the mask's", maskPicture, thumbs.mask(masked))
            assertEquals("nothing was made again", made, thumbs.made)
            // A layer first seen parked: a placeholder of the thumbnail's size.
            val placeholder = thumbs.content(top)
            assertEquals(LayerThumbnails.fitSize(A9Docs.W, A9Docs.H, 96), placeholder.width to placeholder.height)
            assertEquals("a placeholder is not a picture", made, thumbs.made)
            assertSame("the placeholder stays while the key does", placeholder, thumbs.content(top))
            // A blank layer: its (transparent) placeholder is exact.
            thumbs.content(d.layers[5])
            assertTrue("the folder's picture is current after a park", folders.stale(d.layers, listed).isEmpty())
            assertTrue("a folder of parked layers renders", folders.render(compositor, d, folder))
            A9Docs.assertBitEqual("the folder's picture from tiles", flatFolderPicture, folders.image(folder.id)!!.asAndroidBitmap())
        }
        // Flat again (activated): the real picture replaces the placeholder.
        val placeholder = thumbs.content(top)
        ChaosResidency().unpark(top)
        assertNotSame("a flat layer gets its picture", placeholder, thumbs.content(top))
        assertEquals(made + 1, thumbs.made)
        // A replaced store (pixelGeneration) makes the folder's picture stale.
        d.layers[2].pixels = TiledPixels.fromBitmap(keep(A9Docs.content(40L)))
        assertEquals(listOf(folder), folders.stale(d.layers, listed))
    }

    @Test
    fun theLayersPanelNeverMaterializesAParkedLayer() {
        SmokeUi.installTestRecomposer()
        SmokeUi.markBaseline()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val d = document()
        val c = Smoke.controller(activity, d)
        park(d, except = c.activeLayer)
        strict("the layers panel") {
            activity.setContent {
                BrushworkTheme {
                    Box(Modifier.fillMaxSize()) {
                        LayersPanel(
                            c,
                            onDismiss = {},
                            onImportPicture = {},
                            modifier = Modifier.align(Alignment.BottomStart).padding(start = 5.dp, bottom = 5.dp).size(382.dp, 860.dp),
                        )
                    }
                }
            }
            SmokeUi.settle()
            // The folder pictures render 300 ms after the last change, one per frame.
            Smoke.pump(1_000)
            SmokeUi.settle()
        }
        assertTrue("the panel lists the layers", SmokeUi.has(LayerLabels.reorder(1), exact = true) && SmokeUi.has(LayerLabels.reorder(7), exact = true))
        for (l in d.layers) if (!l.isFolder && l !== c.activeLayer && l.pixels !is BlankPixels) {
            assertTrue("${l.name} is still parked", l.pixels is TiledPixels)
        }
    }
}
