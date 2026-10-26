package com.brushwork.paint.v110.a11

import android.graphics.Rect
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.testing.EngineRule
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.10 (A11, I30, design 2.9): the format a save writes depends only on the document, never on
 * the engine level or on where the pixels are. On a 512 x 512 canvas v1.9's cap is 100 open
 * layers: 100 layers save as format 1, 101 as format 4, and 100 layers with one mask as format 4
 * (every mask counts); the same document saved from bitmaps, from tiles and blank stores, and
 * after an open at the level (the stores that level builds) writes the same `project.json`.
 */
abstract class FormatFourDeterminismBase(private val level: EngineLevel) {
    @get:Rule
    val engine = EngineRule(level)

    private val side = 512

    /** [count] layers (two painted, the others blank), the first one masked when [mask]; bitmaps when [flat], else stores. */
    private fun document(id: String, count: Int, mask: Boolean, flat: Boolean): Document {
        val doc = Document(id, id, side, side)
        for (i in 0 until count) {
            val n = i + 1L
            val painted = i < 2
            val layer = when {
                flat -> Layer(n, "L$n", BitmapUtils.createLayerBitmap(side, side).also { if (painted) A11Kit.noise(it, Rect(0, 0, 64, 64), n) })
                painted -> {
                    val b = BitmapUtils.createLayerBitmap(side, side).also { A11Kit.noise(it, Rect(0, 0, 64, 64), n) }
                    Layer(n, "L$n", TiledPixels.fromBitmap(b)).also { b.recycle() }
                }
                else -> Layer(n, "L$n", BlankPixels(side, side))
            }
            if (mask && i == 0) layer.mask = BitmapUtils.createMaskBitmap(side, side)
            doc.layers += layer
        }
        doc.activeLayerIndex = count - 1
        return doc
    }

    /** Saves [doc] as a fresh project; returns its format version and its `project.json` text (dates and revision as the document's). */
    private fun save(doc: Document): Pair<Int, String> {
        val repo = A11Kit.freshRepo()
        try {
            runBlocking { repo.save(doc, null) }
        } finally {
            A11Kit.dispose(doc)
        }
        val dir = A11Kit.dirOf(doc.id)
        return ProjectFormat.read(dir).formatVersion to File(dir, "project.json").readText()
    }

    private fun versionOf(count: Int, mask: Boolean, flat: Boolean): Int = save(document("f4", count, mask, flat).also { it.createdAt = 1; it.modifiedAt = 2 }).first

    @Test
    fun theVersionDependsOnlyOnTheDocument() {
        for (flat in listOf(true, false)) {
            assertEquals("$level, flat=$flat: 100 layers", 1, versionOf(100, mask = false, flat = flat))
            assertEquals("$level, flat=$flat: 101 layers", 4, versionOf(101, mask = false, flat = flat))
            assertEquals("$level, flat=$flat: 100 layers and a mask", 4, versionOf(100, mask = true, flat = flat))
        }
    }

    @Test
    fun bitmapsStoresAndAnOpenWriteTheSameProjectFile() {
        fun stamped(flat: Boolean) = document("f4same", 101, mask = true, flat = flat).also { it.createdAt = 1; it.modifiedAt = 2 }
        val (version, fromBitmaps) = save(stamped(flat = true))
        assertEquals(4, version)
        val (_, fromStores) = save(stamped(flat = false))
        assertEquals("$level: stores write the project file bitmaps write", fromBitmaps, fromStores)
        // Opened at the level and saved again unchanged: the same entries (only the revision moves on).
        val saved = ProjectFormat.read(A11Kit.dirOf("f4same")).layers
        assertEquals(101, saved.size)
        val loaded = runBlocking { A11Kit.repo().load("f4same") }
        try {
            runBlocking { A11Kit.repo().save(loaded, null) }
        } finally {
            A11Kit.dispose(loaded)
        }
        val again = ProjectFormat.read(A11Kit.dirOf("f4same"))
        assertEquals("$level: reopened and saved", 4, again.formatVersion)
        assertEquals("unchanged layers keep their entries", saved, again.layers)
    }
}

@RunWith(RobolectricTestRunner::class)
class FormatFourDeterminismTest : FormatFourDeterminismBase(EngineLevel.FULL)

@RunWith(RobolectricTestRunner::class)
class FormatFourDeterminismClassicTest : FormatFourDeterminismBase(EngineLevel.CLASSIC)

@RunWith(RobolectricTestRunner::class)
class FormatFourDeterminismTilesTest : FormatFourDeterminismBase(EngineLevel.TILES)
