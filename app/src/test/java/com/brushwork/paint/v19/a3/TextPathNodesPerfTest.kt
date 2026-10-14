package com.brushwork.paint.v19.a3

import android.graphics.Paint
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.tools.text.TextOnPath
import com.brushwork.paint.tools.text.TextPathGeometry
import com.brushwork.paint.tools.text.TextPathNodes
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.vector.VSpline
import com.brushwork.paint.vector.VSplinePoint
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.sin

/**
 * v1.9 A3 step 9 (R12 c), the node guide's budgets (JVM proxies of the phone's): the arc-length
 * table of [TextPathSpec.MAX_NODES] path nodes is built in 3 ms at most, and one frame of a node
 * drag (the node moved, then 1000 characters laid along the new guide) takes 16 ms at most (the
 * median of 9 runs, each on node lists the guide cache has not seen).
 */
@Category(Timing::class)
@RunWith(RobolectricTestRunner::class)
class TextPathNodesPerfTest {

    /** [TextPathSpec.MAX_NODES] path nodes on a wave, shifted by [shift] so the cache misses. */
    private fun wave(shift: Float): VSpline =
        VSpline(List(TextPathSpec.MAX_NODES) { VSplinePoint(it * 10f + shift, 300f + 120f * sin(it * 0.25f)) }, order = 4)

    @Test
    fun theGuideOfTwoHundredNodesAndADragFrameWithinTheirBudgets() {
        var round = 0
        PerfBudget.measuredWithin(PerfBudget.ms(3.0), what = "Text path: the guide of 200 path nodes (median of 9)") {
            val runs = DoubleArray(9) {
                val nodes = wave(0.001f * ++round)
                PerfBudget.timeMs { TextPathNodes.guide(nodes) }
            }
            runs.sorted()[runs.size / 2]
        }

        val text = buildString { while (length < 1000) append("The quick brown fox jumps over the lazy dog. ") }.substring(0, 1000)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG).apply { textSize = 24f }
        val start = TextPathNodes.withNodes(TextPathSpec(type = TextPathType.CURVE), wave(0f))
        assertTrue("the nodes are the guide", TextPathNodes.of(start) != null)
        // Warm: fonts and glyph widths measured once.
        assertFalse(TextOnPath.bounds(text, paint, null, start).isEmpty)
        PerfBudget.measuredWithin(PerfBudget.ms(16.0), what = "Text path: a node drag frame, 1000 characters re-laid (median of 9)") {
            val runs = DoubleArray(9) {
                val to = Vec2(1000f, 200f + 0.01f * ++round)
                PerfBudget.timeMs {
                    val moved = TextPathGeometry.moveHandle(start, 100, to)
                    TextOnPath.bounds(text, paint, null, moved)
                }
            }
            runs.sorted()[runs.size / 2]
        }
    }
}
