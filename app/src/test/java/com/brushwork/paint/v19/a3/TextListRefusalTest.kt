package com.brushwork.paint.v19.a3

import android.graphics.Canvas
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.text.ListMarkers
import com.brushwork.paint.tools.text.TextCodec
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertSame
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import java.nio.ByteBuffer
import java.util.zip.CRC32

/**
 * v1.9 A3 step 5 (R7 b): only horizontal straight text has a list. Vertical text and text on a
 * path draw none ([ListMarkers.applies], the renderer) and lose it when sanitized or decoded;
 * horizontal text keeps it; out-of-range list values are brought into range.
 */
@RunWith(RobolectricTestRunner::class)
class TextListRefusalTest {

    private val list = TextListSpec(type = TextListType.NUMBERS, numbering = TextNumbering.UPPER_ROMAN, start = 3)
    private val circle = TextPathSpec(type = TextPathType.CIRCLE, cx = 150f, cy = 150f, radius = 80f)

    private fun crc(item: TextItem): Long {
        val bmp = BitmapUtils.createLayerBitmap(300, 300)
        TextRenderer.drawItem(Canvas(bmp), item, TextRenderer.prepare(item), null)
        val px = IntArray(300 * 300)
        bmp.getPixels(px, 0, 300, 0, 0, 300, 300)
        val buf = ByteBuffer.allocate(px.size * 4)
        buf.asIntBuffer().put(px)
        return CRC32().apply { update(buf.array()) }.value
    }

    @Test
    fun verticalAndPathTextHaveNoList() {
        val horizontal = TextItem("One\nTwo", TextSpec(sizePx = 28f, list = list), 150f, 150f)
        val vertical = horizontal.copy(spec = horizontal.spec.copy(vertical = true))
        val onPath = horizontal.copy(path = circle)
        assertTrue(ListMarkers.applies(horizontal.spec))
        assertFalse(ListMarkers.applies(vertical.spec))
        // Sanitized (as every stored text is): vertical and path text lose the list.
        assertSame("horizontal text keeps it", horizontal.spec.list, horizontal.sanitized().spec.list)
        assertEquals(TextListSpec(), vertical.sanitized().spec.list)
        assertEquals(TextListSpec(), onPath.sanitized().spec.list)
        assertTrue(vertical.sanitized().spec.vertical)
        // Decoded: the same.
        assertEquals(list, TextCodec.decode(TextCodec.encode(horizontal))!!.spec.list)
        assertEquals(TextListSpec(), TextCodec.decode(TextCodec.encode(vertical))!!.spec.list)
        assertEquals(TextListSpec(), TextCodec.decode(TextCodec.encode(onPath))!!.spec.list)
        // Drawn even unsanitized: no marker, the pixels without a list.
        assertNull(TextRenderer.prepare(vertical).block!!.listMarkers)
        assertEquals(crc(vertical.copy(spec = vertical.spec.copy(list = TextListSpec()))), crc(vertical))
        assertEquals(crc(onPath.copy(spec = onPath.spec.copy(list = TextListSpec()))), crc(onPath))
        assertNull(ListMarkers.plan(vertical.spec, vertical.text, null))
    }

    @Test
    fun listValuesAreBroughtIntoRange() {
        val wild = TextListSpec(
            type = TextListType.BULLETS, bullet = "  ★☆ ", start = -4, markerScale = 9f, markerWeight = Float.NaN, gapEm = -1f,
            markerFontId = "not a font id", markerFontName = "Mine",
        )
        val s = TextItem("x", TextSpec(list = wild)).sanitized().spec.list
        assertEquals("one grapheme", "★", s.bullet)
        assertEquals(0, s.start)
        assertEquals(TextListSpec.MAX_MARKER_SCALE, s.markerScale, 0f)
        assertEquals("not a number: the default", 0f, s.markerWeight, 0f)
        assertEquals(0f, s.gapEm, 0f)
        assertNull(s.markerFontId)
        assertNull(s.markerFontName)
        assertTrue(s.sameFontAsText)
        assertEquals("blank: the dot", TextListSpec.DEFAULT_BULLET, TextListSpec(type = TextListType.BULLETS, bullet = " ").sanitized().bullet)
        assertEquals(TextListSpec.MAX_START, TextListSpec(start = 1_000_000).sanitized().start)
        // A list that is fine is the same instance.
        assertSame(list, list.sanitized())
    }
}
