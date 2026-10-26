package com.brushwork.paint.v110.a11

import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.model.Document
import com.brushwork.paint.storage.LayerEntries
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.lang.management.ManagementFactory

/**
 * v1.10 (A11, design 2.9): the streamed load holds no Java buffer above its band (`w x 256 x 4`
 * bytes); v1.9 read every layer through one `ByteArray` of the whole layer. The counting seam
 * (`LayerEntries.loadScratchBytes`) records the largest buffer a load made: one band, made once
 * for a document of painted layers, at Classic and at TILES. Locally (a 2048² document, too big
 * for every CI run) the Java bytes the opener's thread allocates over a load of 3 painted layers
 * stay below half a layer, where v1.9 allocated a whole layer's buffer.
 */
abstract class StreamedLoadNoScratchBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    /** A project [id] of [layers] painted [side]² layers (the last one active), written from rows. */
    private fun project(id: String, side: Int, layers: Int) = A11Kit.writeProject(
        id, side, side,
        List(layers) { i -> { f: java.io.File -> A11Kit.writeRows(f, side, side, A11Kit.PaintedRows(side, side, seed = i + 1L)::fill) } },
        active = layers - 1,
    )

    /** Loads [id] ([side]²) on this thread; returns the document. */
    private fun load(id: String, side: Int): Document {
        val dir = A11Kit.dirOf(id)
        val doc = Document(id, id, side, side)
        LayerEntries.readLayers(dir, ProjectFormat.read(dir), doc)
        return doc
    }

    @Test
    fun theLargestJavaBufferIsOneBand() {
        A11Kit.freshRepo()
        val side = 512
        project("seam", side, 4)
        LayerEntries.loadScratchBytes = 0
        val doc = load("seam", side)
        try {
            assertEquals(4, doc.layers.size)
            val band = side * LayerEntries.BAND_ROWS * 4
            assertTrue("a band was made", LayerEntries.loadScratchBytes > 0)
            assertTrue("$level: at most one band (${LayerEntries.loadScratchBytes} > $band)", LayerEntries.loadScratchBytes <= band)
        } finally {
            A11Kit.dispose(doc)
        }
    }

    @Test
    fun aLoadAllocatesLessThanHalfALayerOfJavaBytes() {
        assumeTrue("a 2048² document: locally only", System.getenv("CI") == null)
        val mx = ManagementFactory.getThreadMXBean() as? com.sun.management.ThreadMXBean
        assumeTrue("this JVM counts allocated bytes", mx != null && mx.isThreadAllocatedMemorySupported)
        mx!!.isThreadAllocatedMemoryEnabled = true
        A11Kit.freshRepo()
        val side = 2048
        project("alloc", side, 3)
        // A first load warms the classes (their loading allocates too).
        A11Kit.dispose(load("alloc", side))
        val before = mx.currentThreadAllocatedBytes
        val doc = load("alloc", side)
        val allocated = mx.currentThreadAllocatedBytes - before
        A11Kit.dispose(doc)
        val layer = side.toLong() * side * 4
        println("v110 A11 StreamedLoadNoScratchTest: $level, 3 painted 2048² layers: $allocated Java bytes allocated (a layer is $layer)")
        assertTrue("$level: $allocated Java bytes allocated by a load of 3 layers of $layer bytes", allocated < layer / 2)
    }
}

@RunWith(RobolectricTestRunner::class)
class StreamedLoadNoScratchTest : StreamedLoadNoScratchBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class StreamedLoadNoScratchTilesTest : StreamedLoadNoScratchBase(EngineLevel.TILES)
