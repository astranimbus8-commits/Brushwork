package com.brushwork.paint.v19.a8

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.net.Uri
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.storage.CanvasCreate
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.common.CanvasLabels19
import com.brushwork.paint.ui.common.V19Tags
import com.brushwork.paint.ui.gallery.NewCanvasBackground
import com.brushwork.paint.ui.gallery.PaperSwatchArt
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream
import java.io.File

/**
 * v1.9 A8 (R15, design 2.4.8): every start of the New canvas dialog makes one layer, "Layer 1",
 * active: filled white or with the chosen colour ([CanvasStart.FILL]), transparent
 * ([CanvasStart.TRANSPARENT]) or transparent over white paper ([CanvasStart.PAPER], the only one
 * with a paper). "New from photo" gives the "Picture" layer alone, active. A null start stays
 * v1.8 (`storage/CanvasCreateV18Test`). The dialog's four choices ([NewCanvasBackground]) in
 * their order, with their captions, their remembered values and the start each makes; the Paper
 * swatch ([PaperSwatchArt]) is not the White one in a render.
 */
@RunWith(RobolectricTestRunner::class)
class NewCanvasStartTest {
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        File(context.filesDir, "projects").deleteRecursively()
    }

    private val white = 0xFFFFFFFF.toInt()
    private val tint = 0xFF3080C0.toInt()

    /** A start, the background the dialog passes with it, the layer's pixel and the thumbnail's. */
    private class Case(val name: String, val start: CanvasStart, val background: Int?, val layerPixel: Int, val thumbPixel: Int, val paper: PaperSpec?)

    private val cases = listOf(
        Case("White", CanvasStart.FILL, white, white, white, null),
        Case("Color", CanvasStart.FILL, tint, tint, tint, null),
        Case("Transparent", CanvasStart.TRANSPARENT, null, 0, 0, null),
        Case("Paper", CanvasStart.PAPER, null, 0, white, PaperSpec()),
        // A spec built by hand: the start decides, not the background.
        Case("Transparent with a background", CanvasStart.TRANSPARENT, tint, 0, 0, null),
        Case("Paper with a background", CanvasStart.PAPER, tint, 0, white, PaperSpec()),
        Case("Fill without a background", CanvasStart.FILL, null, 0, 0, null),
    )

    @Test
    fun everyStartSeedsOneLayer() {
        for (c in cases) {
            val seed = CanvasCreate.seed(NewCanvasSpec("n", 10, 10, 350f, c.background, c.start))
            assertEquals(c.name, listOf("Layer 1"), seed.layers.map { it.name })
            assertEquals(c.name, listOf(c.layerPixel), seed.layers.map { it.fill })
            assertEquals(c.name, listOf(null), seed.layers.map { it.picture })
            assertEquals(c.name, 0, seed.activeLayerIndex)
            assertEquals(c.name, c.paper, seed.paper)
        }
    }

    @Test
    fun everyStartWritesOneLayer() = runBlocking {
        val repo = ProjectRepository(context)
        for (c in cases) {
            val id = repo.create(NewCanvasSpec(c.name, 40, 30, 300f, c.background, c.start))
            val dir = File(context.filesDir, "projects/$id")
            assertEquals(c.name, setOf(ProjectFormat.PROJECT_FILE, ProjectFormat.THUMB_FILE, ProjectFormat.layerFile(1, 0)), dir.list()!!.toSet())
            val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
            assertEquals("${c.name}: the paper key only on paper", c.paper != null, json.contains("\"paper\""))
            val dto = ProjectFormat.read(dir)
            assertEquals(c.name, listOf("Layer 1"), dto.layers.map { it.name })
            assertEquals(c.name, 0, dto.activeLayerIndex)
            assertEquals(c.name, c.paper, dto.paper)
            val thumb = BitmapFactory.decodeFile(File(dir, ProjectFormat.THUMB_FILE).path)
            assertEquals(c.name, ProjectRepository.thumbnailSize(40, 30), thumb.width to thumb.height)
            assertEquals("${c.name}: the thumbnail", c.thumbPixel, thumb.getPixel(thumb.width / 2, thumb.height / 2))
            val doc = repo.load(id)
            assertEquals(c.name, listOf("Layer 1"), doc.layers.map { it.name })
            assertEquals(c.name, 0, doc.activeLayerIndex)
            assertEquals(c.name, c.paper, doc.paper)
            val layer = doc.layers.single().bitmap
            assertEquals("${c.name}: the layer", c.layerPixel, layer.getPixel(0, 0))
            assertEquals("${c.name}: the layer", c.layerPixel, layer.getPixel(39, 29))
            for (l in doc.layers) l.recycleBitmaps()
        }
    }

    // ------------------------------------------------------------------ the dialog's choices (step 2)

    @Test
    fun theChoicesTheirCaptionsAndTheirStarts() {
        assertEquals(
            "White first, Paper last (R15)",
            listOf(NewCanvasBackground.WHITE, NewCanvasBackground.TRANSPARENT, NewCanvasBackground.CUSTOM, NewCanvasBackground.PAPER),
            NewCanvasBackground.entries,
        )
        assertEquals(CanvasLabels19.WHITE_NOTE, NewCanvasBackground.WHITE.caption)
        assertEquals(CanvasLabels19.WHITE_NOTE, NewCanvasBackground.CUSTOM.caption)
        assertEquals(CanvasLabels19.TRANSPARENT_NOTE, NewCanvasBackground.TRANSPARENT.caption)
        assertEquals(CanvasLabels19.PAPER_NOTE, NewCanvasBackground.PAPER.caption)
        assertEquals(
            listOf(AppSettings.NEW_CANVAS_WHITE, AppSettings.NEW_CANVAS_TRANSPARENT, AppSettings.NEW_CANVAS_CUSTOM, AppSettings.NEW_CANVAS_PAPER),
            NewCanvasBackground.entries.map { it.setting },
        )
        assertEquals(
            listOf("WHITE", "TRANSPARENT", "CUSTOM", "PAPER").map { V19Tags.NEW_CANVAS_PREFIX + it },
            NewCanvasBackground.entries.map { it.tag },
        )
        for (b in NewCanvasBackground.entries) assertEquals(b, NewCanvasBackground.fromSetting(b.setting))
        assertEquals(NewCanvasBackground.WHITE, NewCanvasBackground.fromSetting(null))
        assertEquals(NewCanvasBackground.WHITE, NewCanvasBackground.fromSetting("SEPIA"))
        assertEquals("the first default", NewCanvasBackground.WHITE, NewCanvasBackground.fromSetting(AppSettings(context).newCanvasBackground))
        // What each choice makes: one layer, filled or not, over paper or not.
        assertEquals(listOf(CanvasStart.FILL, CanvasStart.TRANSPARENT, CanvasStart.FILL, CanvasStart.PAPER), NewCanvasBackground.entries.map { it.start })
        assertEquals(listOf(white, null, tint, null), NewCanvasBackground.entries.map { it.background(tint) })
        for (b in NewCanvasBackground.entries) {
            val seed = CanvasCreate.seed(NewCanvasSpec("n", 8, 8, 350f, b.background(tint), b.start))
            assertEquals("$b", 1, seed.layers.size)
            assertEquals("$b", b == NewCanvasBackground.PAPER, seed.paper != null)
        }
    }

    @Test
    fun thePaperSwatchDiffersFromWhite() {
        val side = 132
        val paper = Bitmap.createBitmap(side, side, Bitmap.Config.ARGB_8888)
        PaperSwatchArt.draw(Canvas(paper), side.toFloat(), side.toFloat(), 3f)
        val pixels = IntArray(side * side).also { paper.getPixels(it, 0, side, 0, 0, side, side) }
        val notWhite = pixels.count { it != white }
        assertTrue("the fold and its checker cover part of the swatch: $notWhite px", notWhite > side * side / 20)
        assertTrue("and leave most of the page white: $notWhite px", notWhite < side * side / 2)
        assertTrue("every pixel is opaque", pixels.all { it ushr 24 == 0xFF })
        assertEquals("the page", white, paper.getPixel(10, side - 10))
        assertTrue("the top-right corner is not white", paper.getPixel(side - 2, 1) != white || paper.getPixel(side - 2, 30) != white)
        // The checker's grey shows in the uncovered corner.
        val corner = (side - side * 42 / 100 until side).flatMap { x -> (0 until 20).map { y -> paper.getPixel(x, y) } }
        assertTrue("the checker's grey", corner.any { it == 0xFFCCCCCC.toInt() })
    }

    @Test
    fun aPhotoIsThePictureLayerAlone() = runBlocking {
        val picture = Bitmap.createBitmap(6, 4, Bitmap.Config.ARGB_8888).apply { eraseColor(tint) }
        val seed = CanvasCreate.seedFromImage(picture, "Holiday")
        assertEquals(listOf("Picture"), seed.layers.map { it.name })
        assertEquals(listOf(picture), seed.layers.map { it.picture })
        assertEquals(0, seed.activeLayerIndex)
        assertNull(seed.paper)

        val file = File(context.cacheDir, "photo.png")
        file.writeBytes(ByteArrayOutputStream().also { picture.compress(Bitmap.CompressFormat.PNG, 100, it) }.toByteArray())
        val repo = ProjectRepository(context)
        val id = repo.createFromImage(Uri.fromFile(file))
        val doc = repo.load(id)
        assertEquals(listOf("Picture"), doc.layers.map { it.name })
        assertEquals(0, doc.activeLayerIndex)
        assertNull(doc.paper)
        assertEquals(tint, doc.layers.single().bitmap.getPixel(3, 2))
        val json = File(context.filesDir, "projects/$id/${ProjectFormat.PROJECT_FILE}").readText()
        assertFalse(json.contains("\"paper\""))
        assertTrue(repo.list().any { it.id == id && it.layerCount == 1 })
    }
}
