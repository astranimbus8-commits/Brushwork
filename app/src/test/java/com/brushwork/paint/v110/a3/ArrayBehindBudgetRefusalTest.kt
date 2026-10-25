package com.brushwork.paint.v110.a3

import com.brushwork.paint.array.ArrayBehind
import com.brushwork.paint.array.ArrayLookKit
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.engine.LayerBudget
import com.brushwork.paint.engine.LayerBudgetV19
import com.brushwork.paint.model.Document
import com.brushwork.paint.ui.common.ArrayLabels110
import com.brushwork.paint.v110.a3.BehindKit.oneStep
import com.brushwork.paint.v110.a3.BehindKit.refused
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.10 (R4, A3 step 2): "array behind" asks `LayerBudget.current` for room, with the source
 * bytes of a pixel array (`extraBytes`), and a refusal shows the budget's own message and records
 * nothing.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayBehindBudgetRefusalTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val saved = LayerBudget.current

    /** LayerBudgetV19 except [canAdd], which answers [allow] and remembers the bytes asked for. */
    private class Fake(val allow: (Long) -> Boolean) : LayerBudget by LayerBudgetV19 {
        val asked = ArrayList<Long>()
        override fun canAdd(doc: Document, layers: Int, extraBytes: Long): Boolean {
            asked += extraBytes
            return allow(extraBytes)
        }
        override fun refusal(doc: Document, suffix: String?): String = MESSAGE
    }

    @After
    fun tearDown() {
        LayerBudget.current = saved
        ArrayDraw.clearCaches()
    }

    @Test
    fun everyBehindIsRefusedWhenNoLayerFits() {
        val c = ArrayLookKit.controller(app)
        val text = BehindKit.textLayer(c)
        val v = BehindKit.twoBoxes(c)
        BehindKit.selectedSquare(c)
        LayerBudget.current = Fake { false }
        refused(c, MESSAGE) { ArrayBehind.fromSelectionBelow(c) }
        refused(c, MESSAGE) { ArrayBehind.fromLayerBelow(c, text) }
        val ids = setOf(v.vector!!.objects[0].id)
        c.vectors.setSelection(v, ids)
        refused(c, MESSAGE) { ArrayBehind.fromObjectsBelow(c, ids) }
    }

    @Test
    fun theSourcePixelsCountForAPixelArray() {
        val c = ArrayLookKit.controller(app)
        val text = BehindKit.textLayer(c)
        BehindKit.selectedSquare(c, size = 40)
        val fake = Fake { it <= 0L }
        LayerBudget.current = fake
        refused(c, MESSAGE) { ArrayBehind.fromSelectionBelow(c) }
        assertEquals("the 40 x 40 source pixels are asked for", 40L * 40L * 4L, fake.asked.last())
        // A whole text layer brings no source pixels: it fits.
        oneStep(c, ArrayLabels110.ARRAY_BEHIND_STEP) { assertTrue(ArrayBehind.fromLayerBelow(c, text)) }
    }

    private companion object {
        const val MESSAGE = "test budget refusal"
    }
}
