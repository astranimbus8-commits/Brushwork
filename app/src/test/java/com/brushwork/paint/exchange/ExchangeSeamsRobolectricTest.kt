package com.brushwork.paint.exchange

import com.brushwork.paint.EditorController
import com.brushwork.paint.exchange.ExchangeFixtures.controller
import com.brushwork.paint.exchange.ExchangeFixtures.document
import com.brushwork.paint.exchange.ExchangeFixtures.pixels
import com.brushwork.paint.exchange.ExchangeFixtures.render
import com.brushwork.paint.exchange.export.ExportOptions
import com.brushwork.paint.exchange.export.ExportSceneBuilder
import com.brushwork.paint.exchange.export.SvgWriter
import com.brushwork.paint.exchange.export.TextSource
import com.brushwork.paint.exchange.image.PngDecoder
import com.brushwork.paint.exchange.svg.SvgParser
import com.brushwork.paint.masks.MaskSpecs
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.transform.TransformTool
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.lift.VectorLift
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.ByteArrayOutputStream

/**
 * v1.5 integration seams of the exchange (A8) with the merged areas: an SVG imported into an
 * open artwork is lifted by A2's real object provider (✓ moves the objects as objects: the layer
 * stays a vector layer), and a Brushwork payload placed on a canvas of another size keeps its
 * editable mask specs (A5's MaskSpecs.transformed) with masks that are exactly their rendering.
 */
@RunWith(RobolectricTestRunner::class)
class ExchangeSeamsRobolectricTest {

    private fun target(c: EditorController) = ImportTarget(c.doc.width, c.doc.height, c.doc.dpi, c.doc.colorMode, ImportLayers.room(c), c.maxLayers)

    @Test
    fun anImportedSvgIsLiftedAsObjectsAndCheckKeepsAVectorLayer() {
        val c = controller(Smoke.document(300, 200, layers = 1))
        val svg = """<svg xmlns="http://www.w3.org/2000/svg" width="300" height="200">
                <rect x="20" y="20" width="60" height="40" fill="#ff0000"/>
                <path d="M120 150 C 140 100 180 100 230 150 Z" fill="#00aa00" stroke="#003300" stroke-width="4"/>
            </svg>"""
        val before = c.undoManager.undoCount
        VectorImport.apply(c, VectorImport.prepare(SvgParser.parse(svg.toByteArray()), target(c), newArtwork = false))
        assertEquals("the import is one step", before + 1, c.undoManager.undoCount)
        val layer = c.doc.layers.first { it.isVectorLayer }
        val imported = layer.vector!!
        val ids = imported.objects.map { it.id }.toSet()
        assertEquals(ToolId.TRANSFORM, c.activeToolId)
        val tool = c.tools.getValue(ToolId.TRANSFORM) as TransformTool
        assertTrue("lifted", Smoke.pumpUntil { tool.transformState != null })
        // A2's provider lifted exactly the imported objects (not pixels).
        val lift = VectorLift.activeLift(c)
        assertNotNull("an object lift", lift)
        assertEquals(ids, lift!!.ids)
        val old = pixels(layer.bitmap)
        val shifts = c.vectors.shiftCount
        tool.moveBy(30f, 20f)
        tool.commit()
        assertTrue("still a vector layer", layer.isVectorLayer)
        assertEquals("✓ is its own step", before + 2, c.undoManager.undoCount)
        val moved = layer.vector!!
        assertEquals(ids, moved.objects.map { it.id }.toSet())
        val a0 = (imported.objects[0] as VPath).subpaths[0].anchors[0]
        val a1 = (moved.objects[0] as VPath).subpaths[0].anchors[0]
        assertEquals(a0.x + 30f, a1.x, 1e-3f)
        assertEquals(a0.y + 20f, a1.y, 1e-3f)
        // A whole-pixel move: the cache was shifted exactly (A1's ShiftHint path), which is the
        // moved objects' rendering but at anti-aliased edges (where the tile grid cuts a path).
        assertEquals(shifts + 1, c.vectors.shiftCount)
        val now = pixels(layer.bitmap)
        val shifted = IntArray(old.size)
        for (y in 20 until 200) for (x in 30 until 300) shifted[y * 300 + x] = old[(y - 20) * 300 + x - 30]
        assertArrayEquals(shifted, now)
        // (Skia anti-aliases a path's edge differently where a tile clip cuts it: the moved path
        // now crosses the x = 256 tile edge, so its edge pixels differ by an AA step or so.)
        val fresh = pixels(render(moved, 300, 200))
        var differing = 0
        for (i in now.indices) {
            if (now[i] == fresh[i]) continue
            differing++
            for (sh in 0..24 step 8) {
                val d = kotlin.math.abs(((now[i] ushr sh) and 0xFF) - ((fresh[i] ushr sh) and 0xFF))
                assertTrue("anti-aliasing only: $d at ${i % 300},${i / 300}", d <= 64)
            }
        }
        assertTrue("$differing edge pixels differ", differing <= now.count { it != 0 } / 10)
        c.undo()
        assertSame(imported, layer.vector)
    }

    @Test
    fun aPayloadOnAnotherCanvasKeepsItsEditableMasksExact() {
        val doc = document(adjustment = true)
        val scene = runBlocking { ExportSceneBuilder(controller(doc), ExportOptions(VectorFormat.SVG), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(scene).write(out) }
        val svg = SvgParser.parse(out.toByteArray())
        val payload = svg.payload()!!
        val source = doc.layers.first { it.maskSpec != null }
        // Half the size: placed at 90 % and centred.
        val small = Smoke.document(150, 100, layers = 1)
        val c = controller(small)
        PayloadImport.apply(c, PayloadImport.prepare(payload, { key -> svg.imageData(key)?.let { PngDecoder.decode(it) } }, target(c)))
        val placed = c.doc.layers.first { it.name == source.name }
        val spec = placed.maskSpec
        assertNotNull("the editable mask is kept", spec)
        assertNotEquals("its spec is placed with the layer", source.maskSpec, spec)
        // I1: the mask is exactly the placed spec's rendering (margins included).
        assertArrayEquals(pixels(MaskSpecs.newMask(spec!!, 150, 100)), pixels(placed.mask!!))
    }
}
