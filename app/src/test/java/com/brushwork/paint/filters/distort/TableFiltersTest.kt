package com.brushwork.paint.filters.distort

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import org.junit.Assert.assertEquals
import org.junit.Test

class TableFiltersTest {
    private val black = 0xFF000000.toInt()

    @Test
    fun countDrawsSharedLinesAtTheCellEdges() {
        val f = TableCountFilter()
        val v = f.defaultValues().set("cols", 2f).set("rows", 2f).set("margin", 10f).set("space", 0f).set("thickness", 2f).set("color", black)
        val out = f.apply(PixelBuffer(100, 100), v, FilterContext())
        // Outer border at x = 10 and x = 90, middle line at x = 50 (area 80 px wide, 2 columns).
        assertEquals(black, out[10, 50])
        assertEquals(black, out[50, 30])
        assertEquals(black, out[89, 50])
        assertEquals(black, out[30, 50])  // middle horizontal line at y = 50
        assertEquals(0, out[30, 30])      // cell interior stays transparent
        assertEquals(0, out[3, 3])        // margin stays transparent
    }

    @Test
    fun spaceSeparatesTheBoxes() {
        val f = TableCountFilter()
        val v = f.defaultValues().set("cols", 2f).set("rows", 1f).set("margin", 0f).set("space", 20f).set("thickness", 2f).set("color", black)
        val out = f.apply(PixelBuffer(100, 40), v, FilterContext())
        // Cells are 40 px wide with a 20 px gap: the gap's middle has no line.
        assertEquals(0, out[50, 20])
        assertEquals(black, out[40, 20])
        assertEquals(black, out[60, 20])
    }

    @Test
    fun sizeFitsWholeCellsAndCentersThem() {
        val f = TableSizeFilter()
        val v = f.defaultValues().set("cellW", 30f).set("cellH", 30f).set("margin", 0f).set("space", 0f).set("thickness", 2f).set("color", black)
        val out = f.apply(PixelBuffer(100, 100), v, FilterContext())
        // 3 cells of 30 px fit (90 px), centered with 5 px on each side.
        assertEquals(0, out[1, 50])
        assertEquals(black, out[5, 50])
        assertEquals(black, out[35, 50])
        assertEquals(black, out[94, 50])
    }
}
