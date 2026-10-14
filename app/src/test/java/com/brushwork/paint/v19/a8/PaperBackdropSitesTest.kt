package com.brushwork.paint.v19.a8

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.RectF
import androidx.compose.ui.graphics.asAndroidBitmap
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.PaperBackdrop
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.select.EyedropperTool
import com.brushwork.paint.tools.select.PixelSnapshot
import com.brushwork.paint.tools.select.SampleSource
import com.brushwork.paint.ui.layers.LayerThumbnails
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.9 A8 (R15, I21, design 2.4.9, F1b G5): the paper is drawn at a fixed list of sites and nowhere
 * else. The list is pinned by a search of the main sources: each site's file and what it does
 * there (draws the paper, samples its colour, flattens on it, sets it); a file outside the list
 * that reaches the paper fails, and so does a site that is gone. Then, on a paper document: the
 * paper shows in the thumbnail, in a flatten for export and in canvas sampling, and not in a
 * merge, a layer thumbnail, the clipboard or an undo snapshot. `engine/PaperHookNoOpTest` (the
 * lead's) shows every site is v1.8 while `paper == null`.
 */
@RunWith(RobolectricTestRunner::class)
class PaperBackdropSitesTest {
    private val cream = 0xFFF4ECD8.toInt()
    private val blue = 0xFF2050C0.toInt()

    /** What a source line does with the paper. */
    private enum class Kind(val pattern: Regex) {
        DRAW(Regex("""PaperBackdrop\.draw\(""")),
        SAMPLE(Regex("""PaperBackdrop\.sampleColor\(""")),
        FLATTEN(Regex("""withPaper = true""")),
        SET(Regex("""\bdoc\.paper = """)),
        PAYLOAD(Regex("""\bpaper = doc\.paper\b""")),
    }

    /** The sites (design 2.4.9, frozen-api 4.3), relative to `com/brushwork/paint`. */
    private val sites: Map<String, Set<Kind>> = mapOf(
        "ui/editor/CanvasView.kt" to setOf(Kind.DRAW),
        "engine/Compositor.kt" to setOf(Kind.SAMPLE), // renderFlattened(withPaper) and renderThumbnail
        "ui/editor/EditorActions.kt" to setOf(Kind.FLATTEN), // export and share
        "storage/ProjectRepository.kt" to setOf(Kind.FLATTEN, Kind.SET, Kind.PAYLOAD), // the gallery's flatten; load; save
        "tools/select/SelectionSupport.kt" to setOf(Kind.FLATTEN),
        "tools/select/SelectionEdits.kt" to setOf(Kind.SAMPLE),
        "tools/select/EyedropperTool.kt" to setOf(Kind.SAMPLE),
        "exchange/export/ExportSceneBuilder.kt" to setOf(Kind.SAMPLE, Kind.PAYLOAD),
        "ui/exchange/ExchangeUi.kt" to setOf(Kind.SET),
    )

    /**
     * Sites another area adds (design 2.4.9, R9: A7a's `engine/RenderScaled.kt` takes `withPaper`,
     * the Magnetic lasso and Color select pass true). Allowed, not required.
     */
    private val planned = setOf(
        "engine/RenderScaled.kt",
        "tools/select/magnetic/MagneticLassoTool.kt",
        "tools/select/magnetic/EdgeMap.kt",
        "tools/select/colorrange/ColorSelectTool.kt",
        "tools/select/colorrange/ColorSamples.kt",
    )

    /** Never (I21): merges, the payload's layers, layer thumbnails, the clipboard, undo. */
    private val never = listOf(
        "engine/LayerMerge.kt", "exchange/PayloadImport.kt", "engine/Undo.kt", "EditorController.kt",
        "ui/layers/LayerThumbnails.kt", "ui/layers/FolderThumbnails.kt",
    )

    @Test
    fun theSitesAreTheList() {
        val root = mainSources() ?: throw AssertionError("main sources not found from ${System.getProperty("user.dir")}")
        val found = HashMap<String, MutableSet<Kind>>()
        val lines = ArrayList<String>()
        var files = 0
        root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { f ->
            files++
            val rel = f.relativeTo(root).invariantSeparatorsPath
            if (rel == "engine/PaperBackdrop.kt") return@forEach
            f.readLines().forEachIndexed { i, line ->
                val code = line.substringBefore("//").trim()
                if (code.startsWith("*") || code.startsWith("/*")) return@forEachIndexed
                for (k in Kind.entries) if (k.pattern.containsMatchIn(code)) {
                    found.getOrPut(rel) { HashSet() } += k
                    lines += "$rel:${i + 1} $k: $code"
                }
            }
        }
        assertTrue("too few main sources scanned ($files)", files > 100)
        val report = lines.joinToString("\n")
        for ((file, kinds) in sites) assertEquals("$file reaches the paper as listed\n$report", kinds, found[file].orEmpty().toSet())
        val unlisted = found.keys - sites.keys - planned
        assertTrue("the paper is reached outside the list: $unlisted\n$report", unlisted.isEmpty())
        for (file in never) assertNull("$file never draws the paper (I21)", found[file])
    }

    @Test
    fun thePatternsFindTheSitesOnly() {
        assertTrue(Kind.DRAW.pattern.containsMatchIn("PaperBackdrop.draw(canvas, doc)"))
        assertTrue(Kind.SAMPLE.pattern.containsMatchIn("val bg = PaperBackdrop.sampleColor(doc) ?: background"))
        assertTrue(Kind.FLATTEN.pattern.containsMatchIn("compositor.renderFlattened(withPaper = true)"))
        assertTrue(Kind.SET.pattern.containsMatchIn("if (newArtwork) controller.doc.paper = payload.paper"))
        assertTrue(Kind.PAYLOAD.pattern.containsMatchIn("paper = doc.paper,"))
        // The New canvas dialog's paper sizes (CanvasPresets) are not the paper under the artwork.
        for (k in Kind.entries) {
            assertTrue(!k.pattern.containsMatchIn("onPick = { size = size.withPaper(it) },"))
            assertTrue(!k.pattern.containsMatchIn("selectedPaper = size.paper?.name,"))
            assertTrue(!k.pattern.containsMatchIn("fun renderFlattened(background: Int? = null, withPaper: Boolean = false): Bitmap {"))
        }
    }

    // ------------------------------------------------------------------ on a paper document

    /** Cream paper; "Bottom" has a blue square, "Top" a disc; both transparent in the corners. */
    private fun paperController(): EditorController {
        val d = Document("p", "Paper", 96, 64)
        val bottom = Layer(d.newLayerId(), "Bottom", BitmapUtils.createLayerBitmap(96, 64))
        Canvas(bottom.bitmap).drawRect(40f, 10f, 70f, 40f, Paint().apply { color = blue })
        val top = Layer(d.newLayerId(), "Top", BitmapUtils.createLayerBitmap(96, 64))
        Canvas(top.bitmap).drawCircle(30f, 32f, 16f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xFFC03020.toInt() })
        d.layers += bottom
        d.layers += top
        d.activeLayerIndex = 1
        d.paper = PaperSpec(cream)
        return Smoke.controller(ApplicationProvider.getApplicationContext(), d).also { it.viewTransform.set(Matrix()) }
    }

    @Test
    fun thePaperShowsInTheThumbnailTheExportAndSampling() {
        val c = paperController()
        val comp = Compositor(c.doc) { null }
        assertEquals("the thumbnail", cream, comp.renderThumbnail(48).getPixel(0, 0))
        assertEquals("a flatten for export", cream, comp.renderFlattened(0xFFFFFFFF.toInt(), withPaper = true).getPixel(0, 0))
        assertEquals("the default flatten has none", 0, comp.renderFlattened().getPixel(0, 0))
        val dropper = c.tools.getValue(ToolId.EYEDROPPER) as EyedropperTool
        assertEquals("the Eyedropper on a bare spot", cream, dropper.sample(2f, 2f))
        assertEquals(cream, PixelSnapshot.take(c, SampleSource.CANVAS, c.doc.layers[1].bitmap).getPixel(0, 0))
        c.dispose()
    }

    @Test
    fun thePaperIsNeverInAMergeALayerThumbnailTheClipboardOrUndo() {
        val c = paperController()
        val top = c.doc.layers[1]
        // A layer thumbnail is the layer's own pixels.
        val thumb = LayerThumbnails(32).content(top).asAndroidBitmap()
        assertEquals("a layer thumbnail", 0, thumb.getPixel(0, 0))
        // The clipboard: the disc's square bounds, transparent in its corners.
        c.selectLayer(top)
        assertTrue(c.copySelection())
        val clip = assertNotNullAnd(c.clipboard).bitmap
        assertEquals("the clipboard", 0, clip.getPixel(0, 0))
        // A merge, and the undo snapshot it leaves.
        c.mergeDown(top)
        assertTrue(Smoke.pumpUntil(5_000) { c.doc.layers.size == 1 })
        val merged = c.doc.layers.single().bitmap
        assertEquals("a merge", 0, merged.getPixel(0, 0))
        assertEquals("the square is merged", blue, merged.getPixel(60, 20))
        assertEquals(PaperSpec(cream), c.doc.paper)
        c.undo()
        assertTrue(Smoke.pumpUntil(5_000) { c.doc.layers.size == 2 })
        assertEquals("an undo snapshot", 0, c.doc.layers[0].bitmap.getPixel(0, 0))
        assertEquals(0, c.doc.layers[1].bitmap.getPixel(0, 0))
        assertEquals("undo leaves the paper", PaperSpec(cream), c.doc.paper)
        c.dispose()
    }

    @Test
    fun theBackdropIsOneClippedFillThatLeavesTheCanvasAsItWas() {
        val d = Document("p", "Paper", 40, 30).also { it.paper = PaperSpec(cream) }
        val b = Bitmap.createBitmap(60, 50, Bitmap.Config.ARGB_8888)
        val canvas = Canvas(b)
        val saves = canvas.saveCount
        canvas.translate(5f, 5f)
        PaperBackdrop.draw(canvas, d)
        assertEquals("the save count", saves, canvas.saveCount)
        assertEquals("inside the document", cream, b.getPixel(5, 5))
        assertEquals(cream, b.getPixel(44, 34))
        assertEquals("outside it", 0, b.getPixel(46, 36))
        assertEquals(0, b.getPixel(2, 2))
        // The clip does not stay: a later fill reaches the whole bitmap.
        canvas.drawColor(blue, android.graphics.PorterDuff.Mode.DST_OVER)
        assertEquals(blue, b.getPixel(55, 45))
        // Into dst only, under any transform.
        val e = Bitmap.createBitmap(60, 50, Bitmap.Config.ARGB_8888)
        Canvas(e).apply { scale(2f, 2f); PaperBackdrop.draw(this, d, RectF(0f, 0f, 10f, 10f)) }
        assertEquals(cream, e.getPixel(19, 19))
        assertEquals(0, e.getPixel(21, 21))
    }

    private fun <T> assertNotNullAnd(v: T?): T {
        assertNotNull(v)
        return v!!
    }

    /** The module's `src/main/java/com/brushwork/paint`, searched upwards from the working directory. */
    private fun mainSources(): File? {
        var dir: File? = File(System.getProperty("user.dir") ?: return null).absoluteFile
        while (dir != null) {
            for (c in listOf(File(dir, "src/main/java/com/brushwork/paint"), File(dir, "app/src/main/java/com/brushwork/paint"))) {
                if (c.isDirectory) return c
            }
            dir = dir.parentFile
        }
        return null
    }
}
