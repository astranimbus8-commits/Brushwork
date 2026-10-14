package com.brushwork.paint.v19.a7a

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.RenderScaled
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import org.junit.Assert.assertArrayEquals
import org.junit.Assume.assumeTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A7a step 6 (design 3.2 A7a, R9), the Timing guard of [RenderScaled] at PRINT (3000 x 4000,
 * 3 layers): the edge map's 1536 x 2048 render is never slower than v1.8's full-size flatten
 * then scale-down (`renderFlattened` + `createScaledBitmap`), which it replaces. The phone's own
 * budget (at most 60 ms, off the main thread) is a device check; the JVM's numbers are printed.
 * Locally only, never on CI (a T606-size document).
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class RenderScaledPrintTimingTest {

    private fun print(): Document {
        val doc = Document("render-scaled-print", "", 3000, 4000)
        doc.paper = PaperSpec(0xFFF2E6CC.toInt())
        val p = Paint(Paint.ANTI_ALIAS_FLAG)
        for (i in 0 until 3) {
            val bmp = BitmapUtils.createLayerBitmap(doc.width, doc.height)
            val c = Canvas(bmp)
            for (k in 0 until 40) {
                p.color = (0xFF000000.toInt() or (k * 0x2F1B07 + i * 0x4A0000))
                val x = (k * 173 + i * 311) % 2800f
                val y = (k * 251 + i * 97) % 3800f
                c.drawCircle(x + 100f, y + 100f, 60f + (k % 7) * 20f, p)
            }
            doc.layers += Layer(doc.newLayerId(), "Layer ${i + 1}", bmp)
        }
        return doc
    }

    @Test
    fun printRendersAtTheEdgeMapsSizeNoSlowerThanTheFullFlattenItReplaces() {
        assumeTrue("a T606-size document: locally only", System.getenv("CI") == null)
        val doc = print()
        val src = RenderScaled.Source.canvas(doc)
        val size = RenderScaled.size(doc.width, doc.height)
        assertArrayEquals(intArrayOf(1536, 2048), size)
        val compositor = Compositor(doc) { null }
        val (a, b) = PerfBudget.ratioWithin(
            limit = 1.0,
            a = { RenderScaled.render(src, withPaper = true).recycle() },
            b = {
                val full = compositor.renderFlattened(withPaper = true)
                val small = Bitmap.createScaledBitmap(full, size[0], size[1], true)
                if (small !== full) full.recycle()
                small.recycle()
            },
            slackMs = PerfBudget.ms(10.0),
            what = "RenderScaled at PRINT against v1.8's flatten then scale",
        )
        println("RenderScaled PRINT (3000 x 4000, 3 layers) to 1536 x 2048: %.1f ms; v1.8 flatten then scale: %.1f ms".format(a, b))
    }
}
