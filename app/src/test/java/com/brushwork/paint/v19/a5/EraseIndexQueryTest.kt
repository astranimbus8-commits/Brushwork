package com.brushwork.paint.v19.a5

import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VObject
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import com.brushwork.paint.vector.VectorContent
import com.brushwork.paint.vector.draw.EraseSession
import com.brushwork.paint.vector.draw.VectorEraseMode
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.random.Random

/**
 * v1.9 (A5 step 2, design 2.4.4): the vector eraser finds the objects near each capsule through
 * the content's `ObjectIndex`; what it erases is exactly what a pass over every object erases
 * (v1.8), in every mode, over strokes, open, closed and filled paths, an invisible path, shapes and
 * objects with live copies.
 */
@RunWith(RobolectricTestRunner::class)
class EraseIndexQueryTest {

    private fun stroke(rnd: Random, w: Int, h: Int): VStroke {
        val n = 6 + rnd.nextInt(20)
        var x = rnd.nextFloat() * w; var y = rnd.nextFloat() * h
        val xs = FloatArray(n); val ys = FloatArray(n)
        for (i in 0 until n) { xs[i] = x; ys[i] = y; x += rnd.nextFloat() * 24f - 12f; y += rnd.nextFloat() * 24f - 12f }
        val preset = BrushLibrary.defaultBrush.copy(size = 3f + rnd.nextFloat() * 12f)
        return VStroke(0, preset = preset, color = 0xFF000000.toInt(), seed = rnd.nextLong(), stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun path(rnd: Random, w: Int, h: Int, closed: Boolean, fill: Boolean, outline: Boolean = true): VPath {
        val cx = rnd.nextFloat() * w; val cy = rnd.nextFloat() * h
        val anchors = List(3 + rnd.nextInt(3)) { VAnchor(cx + rnd.nextFloat() * 60f - 30f, cy + rnd.nextFloat() * 60f - 30f) }
        return VPath(
            0, subpaths = listOf(VSubpath(anchors, closed = closed)),
            fill = if (fill) VPaint.Solid(0xFF2060A0.toInt()) else null,
            stroke = if (outline) VStrokeStyle(color = 0xFF802000.toInt(), width = 1f + rnd.nextFloat() * 6f) else null,
        )
    }

    private fun shape(rnd: Random, w: Int, h: Int): VShape {
        val style = ShapeStyle.entries[rnd.nextInt(ShapeStyle.entries.size)]
        return VShape(0, shape = ShapeObject(ShapeType.ELLIPSE, cx = rnd.nextFloat() * w, cy = rnd.nextFloat() * h, w = 20f + rnd.nextFloat() * 50f, h = 20f + rnd.nextFloat() * 50f, style = style, strokeWidth = 4f))
    }

    /** A mirror about x = [axis] (row-major 3x3), the identity first. */
    private fun mirror(axis: Float): CopyMaps = CopyMaps.of(listOf(floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f), floatArrayOf(-1f, 0f, 2f * axis, 0f, 1f, 0f, 0f, 0f, 1f)))

    private fun content(seed: Int, w: Int = 512, h: Int = 512): VectorContent {
        val rnd = Random(seed)
        val list = ArrayList<VObject>()
        repeat(120) { k ->
            list += when (k % 7) {
                0, 1, 2 -> stroke(rnd, w, h)
                3 -> path(rnd, w, h, closed = false, fill = false)
                4 -> path(rnd, w, h, closed = true, fill = rnd.nextBoolean())
                5 -> shape(rnd, w, h)
                else -> path(rnd, w, h, closed = true, fill = true).copy(copies = mirror(w / 2f))
            }
        }
        // A path that paints nothing (no fill, no outline): the index has no box for it.
        list += path(rnd, w, h, closed = false, fill = false, outline = false)
        return VectorContent.EMPTY.plus(list).first
    }

    private fun drag(s: EraseSession, seed: Int, w: Int = 512, h: Int = 512) {
        val rnd = Random(seed)
        var x = rnd.nextFloat() * w; var y = rnd.nextFloat() * h
        repeat(160) {
            s.add(x, y, 4f + rnd.nextFloat() * 10f)
            x = (x + rnd.nextFloat() * 40f - 20f).coerceIn(-20f, w + 20f)
            y = (y + rnd.nextFloat() * 40f - 20f).coerceIn(-20f, h + 20f)
        }
    }

    private fun state(s: EraseSession): List<String> = List(s.size) { i ->
        val set = s.removedOf(i)
        val cut = if (set == null) "" else (0 until set.size).joinToString { "%.4f..%.4f".format(set.start(it), set.end(it)) }
        "${s.isWhole(i)} $cut"
    }

    @Test
    fun theIndexFindsWhatAPassOverEveryObjectFinds() {
        var erased = 0
        for (seed in 1..6) {
            val v = content(seed)
            for (mode in VectorEraseMode.entries) {
                val indexed = EraseSession(v, mode)
                val every = EraseSession(v, mode, indexed = false)
                drag(indexed, seed * 31)
                drag(every, seed * 31)
                assertEquals("seed $seed, $mode", state(every), state(indexed))
                assertEquals("seed $seed, $mode", every.result(), indexed.result())
                if (indexed.hasEffect) erased++
            }
        }
        assertTrue("the drags erased something ($erased)", erased >= 12)
    }

    @Test
    fun anInvisiblePathIsStillErased() {
        val p = VPath(0, subpaths = listOf(VSubpath(listOf(VAnchor(10f, 10f), VAnchor(90f, 10f)))))
        val v = VectorContent.EMPTY.plus(listOf(p)).first
        val s = EraseSession(v, VectorEraseMode.OBJECT)
        s.add(50f, 0f, 4f)
        s.add(50f, 20f, 4f)
        assertEquals(VectorContent.EMPTY.objects, s.result()?.objects)
    }
}
