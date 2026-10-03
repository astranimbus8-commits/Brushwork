package com.brushwork.paint.qa16

import android.graphics.Canvas
import android.graphics.Rect
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.TipCache
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.render.VectorLayerRenderer
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.6 final QA, I8: a project v1.5.0 wrote (no adjustment layers, every other kind of layer, see
 * [V15Fixtures]) opens in v1.6 without warnings, keeps every datum, and renders and exports
 * exactly what v1.5.0 rendered and exported from it, byte for byte (PNG, SVG, PDF but its date
 * and random id), as long as nothing new is touched.
 */
@RunWith(RobolectricTestRunner::class)
class V15PlainProjectCompatTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val id = V15Fixtures.PLAIN

    private fun open(): Pair<Document, EditorController> {
        V15Fixtures.install(app, id)
        val doc = runBlocking { ProjectRepository(app).load(id) }
        val c = Smoke.controller(app, doc)
        assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering })
        return doc to c
    }

    @Test
    fun opensWithoutWarningsAndKeepsEveryLayer() {
        val (doc, _) = open()
        assertEquals("load warnings", emptyList<String>(), doc.loadWarnings)
        assertEquals(600 to 420, doc.width to doc.height)
        assertEquals(
            listOf("Background", "Raster", "Star", "Base", "Clipped", "Hidden", "Vector 1"),
            doc.layers.take(7).map { it.name },
        )
        assertEquals(14, doc.layers.size)
        assertTrue("no adjustment layer", doc.layers.none { it.isAdjustmentLayer })
        val texts = doc.layers.filter { it.isTextLayer }
        assertEquals(7, texts.size)
        for (l in texts) {
            val item = TextCodec.decode(l.textData)
            assertNotNull("\"${l.name}\" decodes", item)
            // v1.6 fields of a v1.5 text: no letter scaling, no thread.
            assertTrue("\"${l.name}\" has no letter scaling", !item!!.spec.letterScale.isOn)
            assertTrue("\"${l.name}\" is not a frame", !item.thread.isOn)
        }
        assertNotNull("the star stays an editable shape", doc.layers.first { it.name == "Star" }.shapeData)
        val vector = doc.layers.first { it.isVectorLayer }.vector!!
        val paths = vector.objects.filterIsInstance<VPath>()
        assertTrue("paths kept (${paths.size})", paths.size >= 5)
        assertTrue("v1.5 paths carry no spline", paths.all { it.spline == null })
        assertTrue("the raster keeps its painted mask", doc.layers.first { it.name == "Raster" }.mask != null)
        assertTrue(doc.layers.first { it.name == "Clipped" }.clipping)
        assertTrue(!doc.layers.first { it.name == "Hidden" }.visible)
    }

    @Test
    fun cachesEqualFreshRenderings() {
        assumeTrue("the caches were drawn with the Windows host's fonts and Skia", V15Fixtures.isWindows)
        val (doc, _) = open()
        val bad = mutableListOf<String>()
        for (l in doc.layers) {
            val fresh = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            when {
                l.isTextLayer -> {
                    val item = TextCodec.decode(l.textData)!!
                    TextRenderer.drawItem(Canvas(fresh), item, TextRenderer.prepare(item), null)
                }
                l.isVectorLayer -> VectorLayerRenderer.render(Canvas(fresh), l.vector!!, Rect(0, 0, doc.width, doc.height), tips = TipCache(), document = Rect(0, 0, doc.width, doc.height))
                else -> continue
            }
            if (!fresh.sameAs(l.bitmap)) bad += "${l.name}: max diff ${V15Fixtures.maxDiff(fresh, l.bitmap)}"
        }
        assertEquals("layers whose v1.6 rendering differs from the v1.5 cache", emptyList<String>(), bad)
    }

    @Test
    fun pngExportsAreV15Bytes() {
        assumeTrue("v1.5 goldens are Windows renders", V15Fixtures.isWindows)
        val (_, c) = open()
        assertSameAsV15(c, listOf("flat.png", "flat-white.png", "tiles.png", "thumb-256.png"))
    }

    @Test
    fun svgAndPdfExportsAreV15Bytes() {
        assumeTrue("v1.5 goldens are Windows renders", V15Fixtures.isWindows)
        val (_, c) = open()
        assertSameAsV15(c, V15Fixtures.exports.keys.toList())
    }

    private fun assertSameAsV15(c: EditorController, names: List<String>) {
        val bad = mutableListOf<String>()
        for (name in names) {
            val bytes = V15Fixtures.output(c, name)
            val got = V15Fixtures.crc(bytes)
            val want = V15Fixtures.golden.getValue(id).getValue(name)
            if (got != want) {
                bad += "$name: $got, v1.5 $want"
                val dump = File("build/qa16-out/$id").apply { mkdirs() }
                File(dump, name).writeBytes(bytes)
            }
        }
        assertEquals("files that differ from v1.5's (written to build/qa16-out)", emptyList<String>(), bad)
    }
}
