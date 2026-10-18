package com.brushwork.paint.v19.a3

import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.text.ListMarkers
import com.brushwork.paint.tools.text.TextBoxSpec
import com.brushwork.paint.tools.text.TextListSpec
import com.brushwork.paint.tools.text.TextListType
import com.brushwork.paint.tools.text.TextNumbering
import com.brushwork.paint.tools.text.TextRenderer
import com.brushwork.paint.tools.text.TextSpec
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 A3 step 5 (R7 c), the list markers' budget (a JVM proxy of the phone's): measuring and
 * placing the markers of 50 numbered paragraphs takes 0.5 ms at most (the median of 9 warm
 * runs), on top of the layout of their lines.
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class ListMarkersPerfTest {

    @Test
    fun fiftyParagraphsWithinTheBudget() {
        val text = (1..50).joinToString("\n") { "Paragraph $it of the list" }
        val spec = TextSpec(sizePx = 24f, box = TextBoxSpec(width = 2000f), list = TextListSpec(type = TextListType.NUMBERS, numbering = TextNumbering.UPPER_ROMAN))
        val lines = TextRenderer.layout(text, spec).wrapLines!!
        assertEquals(50, ListMarkers.plan(spec, text, null)!!.place(lines, 0)!!.markers.size)
        repeat(20) { ListMarkers.plan(spec, text, null)!!.place(lines, 0) }
        PerfBudget.measuredWithin(PerfBudget.ms(0.5), what = "Lists: the markers of 50 paragraphs (median of 9)") {
            val runs = DoubleArray(9) { PerfBudget.timeMs { ListMarkers.plan(spec, text, null)!!.place(lines, 0) } }
            runs.sort()
            runs[4]
        }
    }
}
