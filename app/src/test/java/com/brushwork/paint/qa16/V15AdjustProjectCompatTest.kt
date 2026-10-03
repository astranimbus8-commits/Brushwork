package com.brushwork.paint.qa16

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.Canvas
import android.graphics.Matrix
import android.graphics.PorterDuff
import android.graphics.RectF
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentScratch
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.AdjustmentStageV15Reference
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.CompositeTarget
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.util.Base64

/**
 * v1.6 final QA, I8 with adjustment layers: a project v1.5.0 wrote with four adjustment layers
 * (mask specs: linear, radial + subtracted linear, inverted linear with density; NORMAL, MULTIPLY,
 * partial opacities; a transparent hole below; see [V15Fixtures]) opens in v1.6 without warnings
 * and keeps its effects and mask specs. Rendered through the v1.5 Skia path it gives v1.5's
 * bytes; v1.6's fused NORMAL path (§3.1 C1, the faster adjustment masks the user asked for)
 * stays within one level of the v1.5 STAGE by design; stacked, the composite (screen, PNG / SVG
 * / PDF pictures) may differ from v1.5's by one level per fused stage (three here; two measured), not bit for bit.
 */
@RunWith(RobolectricTestRunner::class)
class V15AdjustProjectCompatTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val id = V15Fixtures.ADJUST

    private fun open(): Pair<Document, EditorController> {
        V15Fixtures.install(app, id)
        val doc = runBlocking { ProjectRepository(app).load(id) }
        val c = Smoke.controller(app, doc)
        assertTrue(Smoke.pumpUntil(30_000) { c.busyMessage == null && !c.vectors.isRendering })
        return doc to c
    }

    @Test
    fun opensWithoutWarningsAndKeepsEffectsAndMaskSpecs() {
        val (doc, _) = open()
        assertEquals("load warnings", emptyList<String>(), doc.loadWarnings)
        assertEquals(2, ProjectFormat.writtenVersion(doc.layers))
        val adj = doc.layers.filter { it.isAdjustmentLayer }
        assertEquals(
            listOf("adjust.tone", "adjust.hue_saturation", "adjust.brightness_contrast", "adjust.invert"),
            adj.map { it.adjustment!!.filterId },
        )
        val bc = adj[2]
        assertEquals(LayerBlendMode.MULTIPLY, bc.blendMode)
        assertEquals(0.6f, bc.opacity, 0f)
        assertEquals(0.8f, adj[1].opacity, 0f)
        assertEquals(0.5f, adj[3].opacity, 0f)
        assertEquals("radial + subtracted linear", 2, adj[1].maskSpec!!.components.size)
        assertTrue("inverted spec", bc.maskSpec!!.invert)
        assertEquals(0.9f, bc.maskSpec!!.density, 0f)
        // Each stored mask is the rendering of its spec (I1: data and cache together).
        for (l in adj) {
            val spec = l.maskSpec ?: continue
            val mask = assertNotNull("\"${l.name}\" has its mask", l.mask).let { l.mask!! }
            val fresh = MaskSpecs.newMask(spec, doc.width, doc.height)
            assertEquals("\"${l.name}\": the v1.6 rendering of the v1.5 spec is the stored mask", 0, V15Fixtures.maxDiff(fresh, mask))
        }
    }

    @Test
    fun theV15PathRendersV15sBytes() {
        assumeTrue("v1.5 goldens are Windows renders", V15Fixtures.isWindows)
        val (_, c) = open()
        val flat = V15Fixtures.flattenedV15Path(c)
        assertEquals("flat.png through the v1.5 path", V15Fixtures.golden.getValue(id).getValue("flat.png"), V15Fixtures.crc(V15Fixtures.png(flat)))
        Canvas(flat).drawColor(0xFFFFFFFF.toInt(), PorterDuff.Mode.DST_OVER)
        assertEquals("flat-white.png through the v1.5 path", V15Fixtures.golden.getValue(id).getValue("flat-white.png"), V15Fixtures.crc(V15Fixtures.png(flat)))
    }

    /**
     * §3.1 C1 promises one level per adjustment STAGE against the v1.5 stage. Each fused stage of
     * this document gets the same input as the v1.5 stage (the v1.5 composite below it) and must
     * stay within one level; the whole stack, where each fused stage reads the previous one's
     * output, may then differ by one level per fused stage (here three NORMAL adjustments).
     */
    @Test
    fun theFusedPathStaysWithinOneLevelOfV15PerStage() {
        val (doc, c) = open()
        val bounds = RectF(0f, 0f, doc.width.toFloat(), doc.height.toFloat())
        var fusedStages = 0
        val perStage = mutableListOf<String>()
        for ((k, layer) in doc.layers.withIndex()) {
            if (!layer.isAdjustmentLayer || !layer.visible || layer.opacity <= 0f || layer.blendMode != LayerBlendMode.NORMAL) continue
            fusedStages++
            val below = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            c.compositor.drawDocument(Canvas(below), null, useOverrides = false, target = CompositeTarget(below, Matrix(), directWrite = false), layerRange = 0 until k)
            val fused = below.copy(Bitmap.Config.ARGB_8888, true)
            AdjustmentStage.draw(Canvas(fused), layer, bounds, null, CompositeTarget.identity(fused), AdjustmentScratch())
            val v15 = below.copy(Bitmap.Config.ARGB_8888, true)
            AdjustmentStageV15Reference.draw(Canvas(v15), layer, bounds, null, CompositeTarget.identity(v15), doc.colorMode)
            val d = V15Fixtures.maxDiff(fused, v15)
            perStage += "${layer.name}: $d"
            assertTrue("stage \"${layer.name}\": fused vs v1.5 stage differ by $d", d <= 1)
        }
        assertEquals("Tone, Hue / Saturation and Invert take the fused path", 3, fusedStages)
        val v15 = V15Fixtures.flattenedV15Path(c)
        val fused = c.compositor.renderFlattened()
        val d = V15Fixtures.maxDiff(fused, v15)
        println("[qa16] v15-adjust: per stage $perStage; whole stack fused vs v1.5 path max diff $d")
        assertTrue("flattened: fused vs v1.5 path differ by $d (> one level per fused stage)", d <= fusedStages)
        assertTrue("the screen equals the export", V15Fixtures.tiles(c).sameAs(fused))
    }

    /**
     * The SVG exports: everything but the picture is v1.5's byte for byte (structure, layer
     * groups, the embedded Brushwork data with every adjustment and mask spec); the picture (this
     * document exports as one, adjustment layers being effects) is the screen's composite, within
     * one level per fused stage of v1.5's.
     */
    @Test
    fun svgExportsAreV15sButForThePictureWithinC1() {
        val (_, c) = open()
        val v15 = V15Fixtures.flattenedV15Path(c)
        val fused = c.compositor.renderFlattened()
        for ((name, skeleton) in v15Skeletons) {
            val svg = String(V15Fixtures.export(c, V15Fixtures.exports.getValue(name)), Charsets.UTF_8)
            val images = Regex("""xlink:href="data:image/png;base64,([^"]*)"""").findAll(svg).map { it.groupValues[1] }.toList()
            assertEquals("$name: one picture", 1, images.size)
            val rest = svg.replace(Regex("""(xlink:href="data:image/png;base64,)[^"]*""""), "$1\"").toByteArray(Charsets.UTF_8)
            assertEquals("$name without its picture is v1.5's", skeleton, V15Fixtures.crc(rest))
            val picture = BitmapFactory.decodeByteArray(Base64.getDecoder().decode(images[0]), 0, Base64.getDecoder().decode(images[0]).size)
            if (name == "export-hidden-white.svg") continue // over white, with the hidden layer
            assertEquals("$name: the picture is the screen's composite", 0, V15Fixtures.maxDiff(picture, fused))
            assertTrue("$name: the picture is within C1 of v1.5's", V15Fixtures.maxDiff(picture, v15) <= 3)
        }
    }

    /** CRC32 and length of v1.5's SVG exports of this project with the picture's data removed. */
    private val v15Skeletons = mapOf(
        "export.svg" to (1799815343L to 96998),
        "export-outlines.svg" to (1799815343L to 96998),
        "export-hidden-white.svg" to (580144733L to 97074),
    )

    @Test
    fun pdfExportsAreWellFormed() {
        val (_, c) = open()
        for (name in listOf("export.pdf", "export-a4-outlines.pdf")) {
            val r = com.brushwork.paint.exchange.QaExchange.checkPdf(V15Fixtures.export(c, V15Fixtures.exports.getValue(name)))
            assertEquals("$name: one page", 1, com.brushwork.paint.exchange.QaExchange.pdfPages(r).size)
        }
    }
}
