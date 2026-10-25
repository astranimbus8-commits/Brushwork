package com.brushwork.paint.v110.a9

import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Rect
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.engine.live.BelowProjection
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.FlatPixels
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.testing.PerfBudget

/**
 * v1.10 A9 step 3: the stroke-frame timings (`StrokeFrame200TimingTest`,
 * `StrokeFrame100PlainTimingTest`, `BelowRebuildTimingTest`). A stack of [WaitingTimingKit.stack]
 * at TILES (inactive layers parked, the active one flat), a display of 256 px tiles with the
 * below projection installed, and a stroke frame: a dab on the active layer and the composite of
 * its dirty area (about 256 x 256, design 2.5 "Frame cost").
 */
internal class StrokeFrameKit(val doc: Document, activeIndex: Int) {
    val active: Layer = doc.layers[activeIndex]
    val tiles = DisplayTiles(doc.width, doc.height, tileSize = 256)
    val compositor = Compositor(doc) { null }
    val projection = BelowProjection({ doc }, { active }, { null })
    private val paint = Paint().apply { isAntiAlias = true }
    private var step = 0

    init {
        ChaosResidency().unpark(active)
        projection.install(tiles)
        tiles.update(compositor)
    }

    /** One stroke frame: a dab on the active layer, its 256 x 256 dirty area composited. */
    fun frame() {
        val b = (active.pixels as FlatPixels).bitmap
        val x = 128 + (step * 37) % (doc.width - 256)
        val y = 128 + (step * 53) % (doc.height - 256)
        step++
        paint.color = Color.argb(200, step * 31 and 255, step * 17 and 255, 90)
        Canvas(b).drawCircle(x.toFloat(), y.toFloat(), 12f, paint)
        tiles.invalidate(Rect(x - 128, y - 128, x + 128, y + 128))
        tiles.update(compositor)
    }

    /** The median of [runs] stroke frames after 3 warm-ups (ms). */
    fun medianFrameMs(runs: Int = 15): Double {
        repeat(3) { frame() }
        val times = DoubleArray(runs) { PerfBudget.timeMs { frame() } }
        times.sort()
        return times[times.size / 2]
    }

    fun release() {
        projection.freeAll()
        tiles.release()
    }
}
