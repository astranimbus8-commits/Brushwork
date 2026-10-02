package com.brushwork.paint.exchange

import com.brushwork.paint.ui.exchange.ImportSummary
import org.junit.Assert.assertEquals
import org.junit.Test

/** v1.5 §4.11a (A8): the import summary toast. */
class ImportSummaryTest {
    @Test
    fun countsAndSkippedFeatures() {
        assertEquals(
            "Imported 342 shapes, 2 pictures · skipped: 3 clip paths, 1 filter",
            ImportSummary.format(ImportOutcome(shapes = 342, pictures = 2, skipped = linkedMapOf("clip paths" to 3, "filters" to 1))),
        )
        assertEquals("Imported 1 shape, 1 text", ImportSummary.format(ImportOutcome(shapes = 1, texts = 1)))
        assertEquals("Imported 3 pages", ImportSummary.format(ImportOutcome(pages = 3, layers = 3)))
        assertEquals("Imported 9 layers · left out: 1 layer (layer limit)", ImportSummary.format(ImportOutcome(layers = 9, dropped = mapOf("layers (layer limit)" to 1))))
        assertEquals("Nothing was imported", ImportSummary.format(ImportOutcome()))
        assertEquals("1 dashed line (drawn solid)", "1 " + ImportSummary.singular("dashed lines (drawn solid)", 1))
        assertEquals("embedded HTML", ImportSummary.singular("embedded HTML", 1))
        assertEquals("CSS selector", ImportSummary.singular("CSS selectors", 1))
        assertEquals("circular or too deep reference", ImportSummary.singular("circular or too deep references", 1))
    }
}
