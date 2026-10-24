package com.brushwork.paint.v110.a6

import com.brushwork.paint.testing.PerfBudget
import com.brushwork.paint.testing.Timing
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.convert.PathToShape
import org.junit.Assert.assertEquals
import org.junit.Test
import org.junit.experimental.categories.Category
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * v1.10 A6 (R11 (c)): turning a path of 1,000 anchors (the most a shape takes) into a shape takes
 * under 1 ms on the desktop JVM (median of 21 runs after 3 warm-ups; x 4 on CI). The median is
 * printed for the device row (the T606 is about 6 x slower).
 */
@RunWith(RobolectricTestRunner::class)
@Category(Timing::class)
class PathToShapeSpeedTest {

    @Test
    fun aThousandAnchorsConvertUnderAMillisecond() {
        val n = 1000
        val p = VPath(
            0,
            subpaths = listOf(VSubpath(List(n) { i -> (2.0 * PI * i / n).let { a -> VAnchor(200f + (100f + 20f * sin(i * 0.3f)) * cos(a).toFloat(), 150f + 100f * sin(a).toFloat(), sharp = i % 7 == 0) } }, closed = true)),
            stroke = VStrokeStyle(color = 0xFF203080.toInt(), width = 4f),
        )
        val ms = PerfBudget.measuredWithin(PerfBudget.ms(1.0), what = "1,000 anchors: Turn into shape") {
            repeat(3) { PathToShape.convert(p) }
            val runs = DoubleArray(21) { PerfBudget.timeMs { PathToShape.convert(p) } }
            runs.sorted()[runs.size / 2]
        }
        println("PathToShape 1,000 anchors: %.3f ms (median)".format(ms))
        assertEquals(n, PathToShape.convert(p).shape.shape.points!!.size)
    }
}
