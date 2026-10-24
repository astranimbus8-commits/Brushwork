package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.budget.Budgets
import com.brushwork.paint.engine.tiles.BlankPixels
import com.brushwork.paint.engine.tiles.FolderPixels
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.ArrayPixels
import com.brushwork.paint.model.ArraySpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerArray
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.storage.ProjectRepository
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.int
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.io.File

/**
 * v1.10 F1 (design 2.9, I30, J11; lead, frozen): `ProjectFormat.writtenVersion(layers, w, h)` is
 * 4 exactly when v1.9 could not hold the artwork (its open count above v1.9's cap on a 512 MiB
 * heap, or above 100) and otherwise the v1.9 one-argument version (3 folders, 2 adjustment
 * layers, 1); it depends only on its arguments (not on the stores, the engine level or the
 * session); the one-argument form is unchanged; and the save writes it. The documents' stores are
 * tiny: the count, not the pixels, decides the format. A format-4 save is deterministic (J11, X6):
 * saved twice it writes the same bytes, and the same pixels in tiled stores write them too.
 */
@RunWith(RobolectricTestRunner::class)
class WrittenVersionTest {
    private val made = mutableListOf<Bitmap>()

    @After
    fun tearDown() {
        F1Pixels.resetEngine()
        made.forEach { if (!it.isRecycled) it.recycle() }
    }

    private fun bitmap(w: Int, h: Int): Bitmap = BitmapUtils.createLayerBitmap(w, h).also { made += it }

    private var nextId = 1L

    private fun plain(): Layer = Layer(nextId++, "L", BlankPixels(1, 1))

    private fun masked(): Layer = plain().apply { maskPixels = BlankPixels(1, 1) }

    private fun folder(): Layer = Layer(nextId++, "F", FolderPixels).apply { folder = FolderSpec() }

    private fun adjustment(): Layer = masked().apply { adjustment = AdjustmentSpec() }

    private fun plains(n: Int): List<Layer> = List(n) { plain() }

    /** v1.9's own formula (`EditorController.maxLayers`, integer arithmetic) on a 512 MiB heap. */
    private fun v19Formula(w: Int, h: Int): Int = ((512L shl 20) * 0.55).toLong().let { (it / (w.toLong() * h * 4) - 3).toInt().coerceIn(2, 100) }

    /** v1.9's `ProjectFormat.writtenVersion`, copied. */
    private fun v19Version(layers: List<Layer>): Int = when {
        layers.any { it.isFolder } -> 3
        layers.any { it.isAdjustmentLayer } -> 2
        else -> 1
    }

    @Test
    fun v19CapsAtTwelveSizes() {
        assertEquals("a phone screen", 25, ProjectFormat.v19Cap(1080, 2400))
        assertEquals("2048 x 2048", 14, ProjectFormat.v19Cap(2048, 2048))
        assertEquals("4000 x 5000", 2, ProjectFormat.v19Cap(4000, 5000))
        val sizes = listOf(
            1 to 1, 512 to 512, 1024 to 1024, 1080 to 2400, 1440 to 3200, 1920 to 1080,
            2048 to 2048, 2560 to 1440, 2480 to 3508, 3000 to 3000, 4096 to 4096, 4000 to 5000,
        )
        for ((w, h) in sizes) assertEquals("$w x $h", v19Formula(w, h), ProjectFormat.v19Cap(w, h))
        assertEquals("the heap the cap is computed for", 512L shl 20, ProjectFormat.V19_HEAP)
        assertEquals("the highest format read", 4, ProjectFormat.VERSION)
    }

    @Test
    fun formatFourExactlyAboveV19sCap() {
        assertEquals("25 phone layers: v1.9 holds them", 1, ProjectFormat.writtenVersion(plains(25), 1080, 2400))
        assertEquals("26 phone layers", 4, ProjectFormat.writtenVersion(plains(26), 1080, 2400))
        // A mask counts as a layer (v1.9's effectiveLayerCount); a folder does not, but makes format 3.
        assertEquals("13 layers and 12 masks", 1, ProjectFormat.writtenVersion(plains(1) + List(12) { masked() }, 1080, 2400))
        assertEquals("13 layers and 13 masks", 4, ProjectFormat.writtenVersion(List(13) { masked() }, 1080, 2400))
        assertEquals("25 layers and 3 folders", 3, ProjectFormat.writtenVersion(plains(25) + List(3) { folder() }, 1080, 2400))
        assertEquals("26 layers and a folder", 4, ProjectFormat.writtenVersion(plains(26) + folder(), 1080, 2400))
        assertEquals("an adjustment layer and its mask at the cap", 2, ProjectFormat.writtenVersion(plains(23) + adjustment(), 1080, 2400))
        // Array sources count by their bytes in layer-sized units, rounded up.
        val withArray = plains(13).also { it[0].array = LayerArray(ArraySpec(count = 4), ArrayPixels(bitmap(8, 8), 0, 0)) }
        assertEquals("13 layers and a small array source at 2048 x 2048", 1, ProjectFormat.writtenVersion(withArray, 2048, 2048))
        assertEquals("one more layer", 4, ProjectFormat.writtenVersion(withArray + plain(), 2048, 2048))
        // Above 100 even where the cap is 100.
        assertEquals("100 layers on 512 x 512", 1, ProjectFormat.writtenVersion(plains(100), 512, 512))
        assertEquals("101 layers on 512 x 512", 4, ProjectFormat.writtenVersion(plains(101), 512, 512))
        assertEquals("2 layers on 4000 x 5000", 1, ProjectFormat.writtenVersion(plains(2), 4000, 5000))
        assertEquals("3 layers on 4000 x 5000", 4, ProjectFormat.writtenVersion(plains(3), 4000, 5000))
    }

    @Test
    fun theVersionDependsOnlyOnItsArguments() {
        val sets = listOf(plains(25), plains(26), plains(25) + folder(), plains(23) + adjustment(), List(13) { masked() })
        val before = sets.map { ProjectFormat.writtenVersion(it, 1080, 2400) }
        assertEquals("twice the same", before, sets.map { ProjectFormat.writtenVersion(it, 1080, 2400) })
        // The same structure in other stores (flat, parked) gives the same version.
        val flat = List(26) { Layer(nextId++, "Flat", bitmap(4, 4)) }
        val parked = List(26) { Layer(nextId++, "Parked", TiledPixels.fromBitmap(bitmap(4, 4))) }
        assertEquals("flat stores", 4, ProjectFormat.writtenVersion(flat, 1080, 2400))
        assertEquals("parked stores", 4, ProjectFormat.writtenVersion(parked, 1080, 2400))
        assertEquals("flat, under the cap", 1, ProjectFormat.writtenVersion(flat.take(25), 1080, 2400))
        // An open session at FULL with known memory changes nothing.
        EngineConfig.openSession(EngineLevel.FULL, Budgets(8L shl 30, 4L shl 30, known = true, stepDown = 0), null)
        try {
            assertEquals("at FULL", before, sets.map { ProjectFormat.writtenVersion(it, 1080, 2400) })
        } finally {
            EngineConfig.closeSession()
        }
        assertEquals("after the session", before, sets.map { ProjectFormat.writtenVersion(it, 1080, 2400) })
    }

    @Test
    fun theOneArgumentFormIsV19s() {
        val sets = listOf(plains(1), plains(26), plains(3) + folder(), plains(30) + folder(), plains(2) + adjustment(), plains(40) + adjustment())
        for (s in sets) assertEquals("${s.size} layers", v19Version(s), ProjectFormat.writtenVersion(s))
    }

    @Test
    fun theSaveWritesTheThreeArgumentVersion() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        File(context.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(context)
        suspend fun versionOf(n: Int): Int {
            val doc = Document("wv$n", "Written $n", 64, 48)
            repeat(n) { doc.layers += Layer(doc.newLayerId(), "Layer $it", bitmap(64, 48)) }
            repo.save(doc, null)
            val json = File(context.filesDir, "projects/${doc.id}/${ProjectFormat.PROJECT_FILE}").readText()
            return ProjectFormat.json.parseToJsonElement(json).jsonObject.getValue("formatVersion").jsonPrimitive.int
        }
        assertEquals("100 layers on 64 x 48: v1.9's format", 1, versionOf(100))
        assertEquals("101 layers: format 4", 4, versionOf(101))
    }

    /** Every file of the project folder of [id], by name. */
    private fun files(context: android.content.Context, id: String): Map<String, List<Byte>> =
        File(context.filesDir, "projects/$id").listFiles().orEmpty().filter { it.isFile }.associate { it.name to it.readBytes().toList() }

    /**
     * J11, I30, X6: a format-4 artwork saved twice (each time into a fresh folder, so both are
     * revision 1) writes the same bytes, and the same pixels in tiled stores (as a FULL session's
     * parked layers) write the same bytes as the flat ones.
     */
    @Test
    fun aFormatFourSaveIsTheSameBytesTwiceAndFromTiledStores() = runBlocking {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val dir = File(context.filesDir, "projects/wv4")
        dir.deleteRecursively()
        val repo = ProjectRepository(context)
        val doc = Document("wv4", "Format four", 64, 48)
        repeat(101) {
            doc.layers += Layer(doc.newLayerId(), "Layer $it", F1Pixels.noise(64, 48, 400L + it).also { b -> made += b }).apply {
                if (it % 10 == 0) mask = F1Pixels.grayNoise(64, 48, 600L + it).also { b -> made += b }
            }
        }
        doc.layers += Layer(doc.newLayerId(), "Folder", FolderPixels).apply { folder = FolderSpec() }
        doc.activeLayerIndex = 0

        repo.save(doc, null)
        val first = files(context, doc.id)
        val json = File(dir, ProjectFormat.PROJECT_FILE).readText()
        assertEquals("format 4", 4, ProjectFormat.json.parseToJsonElement(json).jsonObject.getValue("formatVersion").jsonPrimitive.int)
        assertEquals("project.json, 101 layer files and 11 mask files", 1 + 101 + 11, first.size)

        dir.deleteRecursively()
        repo.save(doc, null)
        assertEquals("saved again: the same names", first.keys, files(context, doc.id).keys)
        assertEquals("saved again: the same bytes", first, files(context, doc.id))

        // The same pixels in tiled stores, as parked layers.
        for (l in doc.layers) if (!l.isFolder) {
            val content = l.bitmap
            val mask = l.mask
            l.pixels = TiledPixels.fromBitmap(content)
            if (mask != null) l.maskPixels = TiledPixels.fromBitmap(mask)
        }
        // (Under -PengineMode a settle may have made the active layer flat again: the bytes are the same either way.)
        assertTrue("the layers tiled", doc.layers.count { it.pixels is TiledPixels } >= 100)
        dir.deleteRecursively()
        repo.save(doc, null)
        assertEquals("from tiled stores: the same bytes", first, files(context, doc.id))
        dir.deleteRecursively()
        doc.layers.forEach { if (!it.isFolder) it.recycleBitmaps() }
    }
}
