package com.brushwork.paint.qa17

import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.BrushworkApp
import com.brushwork.paint.EditorController
import com.brushwork.paint.EditorSession
import com.brushwork.paint.MainActivity
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.SymmetrySettings
import com.brushwork.paint.model.SymmetryType
import com.brushwork.paint.qa16.Finger
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.click
import com.brushwork.paint.smoke.SmokeUi.has
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.ui.common.FolderLabels
import com.brushwork.paint.ui.editor.CanvasView
import com.brushwork.paint.ui.editor.chrome.ChromeScreen
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.android.controller.ActivityController
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * v1.7 final QA, layers cluster (items 8 and 18; device checklist §6.4 rows 1 and 12) through
 * the real app (MainActivity, the user's phone size): a project with a folder (isolated, Multiply
 * 60 %, a layer clipped to it) and a rotation ruler is listed in the gallery and opens from it
 * with its tree, its ruler and its picture; a finger stroke on the layer inside the folder (picked
 * in the layer window) is one step; "Back to gallery" saves it on the way out (the project and its
 * thumbnail), and the project opens again from the gallery showing exactly what was left.
 */
internal class Qa17LayersGallery {
    private lateinit var ctl: ActivityController<MainActivity>
    private lateinit var app: BrushworkApp
    private val activity: MainActivity get() = ctl.get()
    private val c: EditorController get() = (app.editorSession!!.state as EditorSession.State.Ready).controller
    private var u: Qa17LayersUi? = null
    private val l: Qa17LayersUi get() = u!!
    private val failures = mutableListOf<Throwable>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
            runCatching { if (app.editorSession != null) backToGallery() }
        }
    }

    // ================================================================== the project

    private fun fill(l: Layer, color: Int, left: Int, top: Int, right: Int, bottom: Int) {
        Canvas(l.bitmap).drawRect(left.toFloat(), top.toFloat(), right.toFloat(), bottom.toFloat(), Paint().apply { this.color = color })
        l.markChanged()
    }

    /** [Layer 1 (backdrop), Layer 2 ‹Folder 1›, Folder 1 (isolated, Multiply 60 %), Layer 3 (clipped)], a rotation ruler. */
    private fun project(): Document {
        val doc = Document(ID, NAME, W, H)
        for (i in 1..3) doc.layers += Layer(doc.newLayerId(), "Layer $i", BitmapUtils.createLayerBitmap(W, H))
        fill(doc.layers[0], 0xFF8090A0.toInt(), 0, 0, W, H)
        fill(doc.layers[1], 0xFFE08020.toInt(), 30, 30, 170, 130)
        fill(doc.layers[2], 0xFF30A050.toInt(), 120, 20, 220, 160)
        doc.activeLayerIndex = 1
        val maker = Smoke.controller(app, doc)
        val f = maker.putInNewFolder(doc.layers[1])!!
        maker.setFolderPassThrough(f, false)
        f.blendMode = LayerBlendMode.MULTIPLY
        f.opacity = 0.6f
        maker.toggleClipping(doc.layers.single { it.name == "Layer 3" })
        maker.updateSymmetry(SymmetrySettings(SymmetryType.ROTATION, divisions = 4))
        maker.notifyLayersChanged()
        assertEquals(listOf("Layer 1", "Layer 2", "Folder 1", "Layer 3"), doc.layers.map { it.name })
        return doc
    }

    private fun tree(d: Document) = d.layers.map { listOf(it.id, it.parentId, it.name, it.folder, it.blendMode, it.opacity, it.clipping) }

    // ================================================================== the gallery

    private fun open() {
        assertTrue("the gallery lists \"$NAME\"", Smoke.pumpUntil { settle(1); has(NAME, exact = true) })
        click(NAME, exact = true)
        assertTrue("the editor opened", Smoke.pumpUntil(30_000) {
            settle(1)
            (app.editorSession?.state as? EditorSession.State.Ready) != null &&
                (Smoke.find(activity.window.decorView, CanvasView::class.java)?.width ?: 0) > 0
        })
        assertTrue(Smoke.pumpUntil(30_000) { settle(1); c.busyMessage == null })
        assertEquals(ID, c.doc.id)
        u?.release()
        val s = ChromeScreen(activity, c)
        u = Qa17LayersUi(s)
        settle()
        assertEquals("the phone is 392 dp wide", 392f, s.widthDp, 1f)
        assertEquals("opened without warnings", emptyList<String>(), c.doc.loadWarnings.toList())
    }

    private fun backToGallery() {
        Finger.tap(l.s, "Back to gallery")
        assertTrue("back in the gallery", Smoke.pumpUntil(30_000) { settle(1); app.editorSession == null && has("New canvas") })
        u?.release()
        u = null
    }

    private fun layerPixels(x: Layer): IntArray = IntArray(W * H).also { x.bitmap.getPixels(it, 0, W, 0, 0, W, H) }

    // ================================================================== the trip

    private lateinit var savedTree: List<List<Any?>>
    private lateinit var left: IntArray

    fun openPaintAndLeave() {
        File(app.filesDir, "projects").deleteRecursively()
        val doc = project()
        val picture = Compositor(doc) { null }.renderFlattened().let { b -> try { IntArray(W * H).also { b.getPixels(it, 0, W, 0, 0, W, H) } } finally { b.recycle() } }
        savedTree = tree(doc)
        runBlocking { app.repository.save(doc, null) }
        SmokeUi.markBaseline()
        ctl = Robolectric.buildActivity(MainActivity::class.java).setup()

        open()
        assertEquals("the same tree", savedTree, tree(c.doc))
        assertEquals("the same ruler", SymmetrySettings(SymmetryType.ROTATION, divisions = 4), c.symmetry)
        assertArrayEquals("the same picture", picture, l.pixels())
        val l2 = c.doc.layers.single { it.name == "Layer 2" }
        val folder = c.doc.layers.single { it.isFolder }

        // Layer 2 picked in the layer window (inside the open folder), a finger stroke on it.
        l.openLayers()
        assertTrue("the folder's row", has(FolderLabels.close(folder.name), exact = true))
        l.pick(l2)
        l.closeLayers()
        c.color = 0xFF2040C0.toInt()
        c.brush = c.brush.copy(size = 8f, opacity = 1f, pressureSize = false)
        val before = layerPixels(l2)
        l.oneStep("a stroke inside the folder", "Brush") { l.ui.stroke(60f to 50f, 90f to 60f, 110f to 75f) }
        val painted = layerPixels(l2)
        assertTrue("Layer 2 painted", painted.indices.count { painted[it] != before[it] } > 100)
        left = l.pixels()
        assertFalse("the picture changed", left.contentEquals(picture))
        assertEquals("the tree unchanged", savedTree, tree(c.doc))
        Smoke.assertQuiet(c, "painted")

        // "Back to gallery": saved on the way out, with its thumbnail.
        backToGallery()
        val saved = runBlocking { app.repository.load(ID) }
        assertEquals(emptyList<String>(), saved.loadWarnings.toList())
        assertEquals("saved: the same tree", savedTree, tree(saved))
        assertEquals("saved: the ruler", SymmetrySettings(SymmetryType.ROTATION, divisions = 4), saved.symmetry)
        assertArrayEquals("saved: Layer 2 as it was left", painted, layerPixels(saved.layers.single { it.name == "Layer 2" }))
        assertTrue("the thumbnail is written", File(app.filesDir, "projects/$ID/thumb.png").isFile)
    }

    fun reopen() {
        open()
        assertEquals("reopened: the same tree", savedTree, tree(c.doc))
        assertArrayEquals("reopened: the picture as it was left", left, l.pixels())
        l.openLayers()
        val folder = c.doc.layers.single { it.isFolder }
        assertTrue("the folder's row", has(FolderLabels.close(folder.name), exact = true) || has(FolderLabels.open(folder.name), exact = true))
        l.closeLayers()
        Qa17LayersShots.shoot(c, "folders-9-gallery-reopened")
        Smoke.assertQuiet(c, "reopened")
        backToGallery()
    }

    companion object {
        const val ID = "qa17-folder-trip"
        const val NAME = "Folder trip"
        const val W = 240
        const val H = 180

        fun run() {
            ShadowLog.stream = null
            SmokeUi.installTestRecomposer()
            val dog = Smoke.watchdog(limitMs = 90_000)
            val t = Qa17LayersGallery()
            t.app = RuntimeEnvironment.getApplication() as BrushworkApp
            val times = mutableListOf<String>()
            fun timed(name: String, block: () -> Unit) {
                val t0 = System.nanoTime()
                t.section(name, block)
                times += "$name ${(System.nanoTime() - t0) / 1_000_000} ms"
            }
            timed("1 open, paint, back to gallery") { t.openPaintAndLeave() }
            timed("2 reopen from the gallery") { t.reopen() }
            println("Qa17LayersGallery times: $times")
            t.u?.release()
            dog.interrupt()
            runCatching { t.ctl.pause().stop().destroy() }
            if (t.failures.isNotEmpty()) {
                val first = t.failures.first()
                t.failures.drop(1).forEach { first.addSuppressed(it) }
                throw first
            }
        }
    }
}

/** Items 8 and 18 through the real app on the user's phone (392 dp): a folder project from the gallery and back. */
// Own sandbox (the test recomposer policy and paused Choreographer are global); the user's phone size.
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w392dp-h873dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa17.layersgallerysandbox"])
class Qa17LayersGalleryUiTest {
    @Test
    fun aFolderProjectOpensFromTheGalleryIsPaintedAndSavedOnTheWayBack() = Qa17LayersGallery.run()
}
