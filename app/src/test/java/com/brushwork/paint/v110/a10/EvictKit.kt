package com.brushwork.paint.v110.a10

import android.graphics.Bitmap
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.SavedFileRef
import com.brushwork.paint.engine.tiles.TiledPixels
import com.brushwork.paint.model.Layer
import com.brushwork.paint.storage.LayerCodec
import com.brushwork.paint.storage.ProjectFormat
import com.brushwork.paint.v110.lead.F1Pixels
import org.junit.Assert.assertTrue
import java.io.File
import java.nio.ByteBuffer

/** The A10 eviction tests' pieces (design 2.0 P3 and 2.8): parked, saved layers and their files. */
internal object EvictKit {
    /** A parked layer [id] of [src]'s pixels (a copy), saved: `savedVersion == contentVersion`, its file written under [dir]. */
    fun savedParkedLayer(dir: File, id: Long, src: Bitmap): Pair<Layer, SavedFileRef> {
        val layer = Layer(id, "Layer $id", src.copy(Bitmap.Config.ARGB_8888, true))
        assertTrue("parked", ChaosResidency().park(layer))
        layer.savedVersion = layer.contentVersion
        val name = ProjectFormat.layerFile(id, 0)
        save(File(dir, name), src)
        return layer to SavedFileRef(dir, name, layer.contentVersion)
    }

    /** Writes [b]'s pixels as a layer file ([LayerCodec.write]). */
    fun save(file: File, b: Bitmap) {
        file.parentFile?.mkdirs()
        val raw = ByteBuffer.allocate(b.byteCount)
        b.copyPixelsToBuffer(raw)
        LayerCodec.write(file, b.width, b.height, raw.array())
    }

    /** Sets every tile of [layer]'s parked content as last read at [epoch] (main's `lastUse`). */
    fun readAt(layer: Layer, epoch: Long) {
        val s = layer.pixels as TiledPixels
        for (r in 0 until s.rows) for (c in 0 until s.cols) s.tileAt(c, r)?.lastUse = epoch
    }

    /** A picture of noise, transparency, solid colors and a disc, from [seed] (its tiles of every kind). */
    fun picture(w: Int, h: Int, seed: Long): Bitmap = F1Pixels.mixed(w, h, seed)
}
