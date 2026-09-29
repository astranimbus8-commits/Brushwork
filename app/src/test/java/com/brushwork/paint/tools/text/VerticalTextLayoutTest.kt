package com.brushwork.paint.tools.text

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.tools.text.VerticalGlyphKind.PUNCTUATION
import com.brushwork.paint.tools.text.VerticalGlyphKind.ROTATED
import com.brushwork.paint.tools.text.VerticalGlyphKind.SMALL_KANA
import com.brushwork.paint.tools.text.VerticalGlyphKind.TATE_CHU_YOKO
import com.brushwork.paint.tools.text.VerticalGlyphKind.UPRIGHT
import org.junit.Assert.assertEquals
import org.junit.Test

class VerticalTextLayoutTest {
    private val latin = { s: String -> 6f * s.length }

    @Test
    fun columnsRunRightToLeftAndCharsTopToBottom() {
        val r = VerticalTextLayout.layout("あい\nう", em = 10f, letterSpacingEm = 0f, lineSpacing = 1.5f, align = TextAlign.START, rotatedAdvance = latin)
        assertEquals(2, r.columns)
        assertEquals(25f, r.width, 1e-4f)   // em + pitch
        assertEquals(20f, r.height, 1e-4f)  // two cells
        val (a, i, u) = r.glyphs
        assertEquals("あ", a.text)
        assertEquals(20f, a.cx, 1e-4f)      // first column is the rightmost
        assertEquals(5f, a.cy, 1e-4f)
        assertEquals(20f, i.cx, 1e-4f)
        assertEquals(15f, i.cy, 1e-4f)
        assertEquals(1, u.column)
        assertEquals(5f, u.cx, 1e-4f)
        assertEquals(5f, u.cy, 1e-4f)
    }

    @Test
    fun alignmentWithinTallestColumn() {
        val center = VerticalTextLayout.layout("あい\nう", 10f, 0f, 1.5f, TextAlign.CENTER, latin)
        assertEquals(10f, center.glyphs[2].cy, 1e-4f)
        val end = VerticalTextLayout.layout("あい\nう", 10f, 0f, 1.5f, TextAlign.END, latin)
        assertEquals(15f, end.glyphs[2].cy, 1e-4f)
        assertEquals(5f, end.glyphs[0].cy, 1e-4f) // the tallest column is unaffected
    }

    @Test
    fun letterSpacingAndRotatedAdvance() {
        val spaced = VerticalTextLayout.layout("あい", 10f, 0.5f, 1f, TextAlign.START, latin)
        assertEquals(20f, spaced.glyphs[1].cy, 1e-4f) // 10 + 5 spacing + 5
        assertEquals(25f, spaced.height, 1e-4f)
        val rotated = VerticalTextLayout.layout("ab", 10f, 0f, 1f, TextAlign.START, latin)
        assertEquals(listOf(ROTATED, ROTATED), rotated.glyphs.map { it.kind })
        assertEquals(3f, rotated.glyphs[0].cy, 1e-4f)
        assertEquals(9f, rotated.glyphs[1].cy, 1e-4f)
        assertEquals(12f, rotated.height, 1e-4f)
    }

    @Test
    fun emptyTextHasOneEmptyColumn() {
        val r = VerticalTextLayout.layout("", 10f, 0f, 1.2f, TextAlign.START, latin)
        assertEquals(1, r.columns)
        assertEquals(10f, r.width, 1e-4f)
        assertEquals(0f, r.height, 1e-4f)
        assertEquals(0, r.glyphs.size)
    }

    @Test
    fun glyphOrientation() {
        assertEquals(UPRIGHT, VerticalTextLayout.kindOf("漢"))
        assertEquals(UPRIGHT, VerticalTextLayout.kindOf("カ"))
        assertEquals(UPRIGHT, VerticalTextLayout.kindOf("！"))
        assertEquals(UPRIGHT, VerticalTextLayout.kindOf("😀")) // emoji
        assertEquals(PUNCTUATION, VerticalTextLayout.kindOf("、"))
        assertEquals(PUNCTUATION, VerticalTextLayout.kindOf("。"))
        assertEquals(SMALL_KANA, VerticalTextLayout.kindOf("ょ"))
        assertEquals(SMALL_KANA, VerticalTextLayout.kindOf("ッ"))
        assertEquals(ROTATED, VerticalTextLayout.kindOf("ー"))
        assertEquals(ROTATED, VerticalTextLayout.kindOf("「"))
        assertEquals(ROTATED, VerticalTextLayout.kindOf("（"))
        assertEquals(ROTATED, VerticalTextLayout.kindOf("…"))
        assertEquals(ROTATED, VerticalTextLayout.kindOf("A"))
    }

    @Test
    fun tateChuYokoRuns() {
        fun kinds(s: String) = VerticalTextLayout.clusters(s).map { it.text to it.kind }
        assertEquals(listOf("12" to TATE_CHU_YOKO, "月" to UPRIGHT), kinds("12月"))
        assertEquals(listOf("5" to TATE_CHU_YOKO, "日" to UPRIGHT), kinds("5日"))
        assertEquals(listOf("1" to ROTATED, "2" to ROTATED, "3" to ROTATED), kinds("123"))
        assertEquals(listOf("え" to UPRIGHT, "!?" to TATE_CHU_YOKO), kinds("え!?"))
        assertEquals(listOf("!" to UPRIGHT), kinds("!"))
        assertEquals(listOf("!" to UPRIGHT, "!" to UPRIGHT, "!" to UPRIGHT), kinds("!!!"))
        // Surrogate pairs and combining marks stay one cell.
        assertEquals(1, VerticalTextLayout.clusters("😀").size)
        assertEquals(1, VerticalTextLayout.clusters("が").size)
        val r = VerticalTextLayout.layout("12月", 10f, 0f, 1f, TextAlign.START, latin)
        assertEquals(2, r.glyphs.size)
        assertEquals(20f, r.height, 1e-4f) // tate-chu-yoko takes one em
    }

    @Test
    fun textItemGeometryAndNaming() {
        val item = TextItem("Hello\nworld and more", TextSpec(), cx = 100f, cy = 50f, rotationDeg = 90f)
        assertEquals("Text: Hello world", item.layerName())
        assertEquals("Text: 漢字", TextItem("  漢字 ", TextSpec(), 0f, 0f).layerName())
        // A 40 x 20 block rotated 90° clockwise: its top-left corner lands at the top-right.
        val tl = item.localToDoc(0f, 0f, 40f, 20f)
        assertEquals(110f, tl.x, 1e-3f)
        assertEquals(30f, tl.y, 1e-3f)
        val back = item.docToLocal(tl, 40f, 20f)
        assertEquals(0f, back.x, 1e-3f)
        assertEquals(0f, back.y, 1e-3f)
        val c = item.corners(40f, 20f)
        assertEquals(Vec2(110f, 30f), Vec2(Math.round(c[0].x).toFloat(), Math.round(c[0].y).toFloat()))
        assertEquals(86f, TextItem.snapDegrees(86f), 0f)
        assertEquals(90f, TextItem.snapDegrees(88f), 0f)
        assertEquals(90f, TextItem.snapDegrees(92f), 0f)
        assertEquals(-135f, TextItem.snapDegrees(225f + 1f), 0f)
        assertEquals(180f, TextItem.normalizeDegrees(-180f), 0f)
    }
}
