package com.brushwork.paint.filters.art

import com.brushwork.paint.core.PixelBuffer
import com.brushwork.paint.filters.FilterContext
import kotlin.random.Random

/** v1.8 (item 5): the pictures and contexts of the fringe-color tests. */
internal object FringeFixtures {
    const val R = FringeColors.RED
    const val G = FringeColors.GREEN
    const val B = FringeColors.BLUE
    const val NONE = FringeColors.NONE

    /** The scales of design §5.1 A: full size and two previews. */
    val contexts = listOf(FilterContext(), FilterContext(scale = 0.5f), FilterContext(scale = 0.37f))

    /** Random colors with a transparent left strip and translucent pixels scattered over it. */
    fun random(w: Int, h: Int, seed: Int): PixelBuffer {
        val r = Random(seed)
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = when {
                x < 4 -> 0
                (x * 3 + y) % 7 == 0 -> 1 + r.nextInt(254)
                else -> 255
            }
            b[x, y] = if (a == 0) 0 else (a shl 24) or (r.nextInt(0x1000000))
        }
        return b
    }

    /** A smooth opaque gradient in all three channels with a translucent band. */
    fun gradient(w: Int, h: Int): PixelBuffer {
        val b = PixelBuffer(w, h)
        for (y in 0 until h) for (x in 0 until w) {
            val a = if (y in h / 3 until h / 2) 140 else 255
            b[x, y] = (a shl 24) or ((20 + x * 200 / w) shl 16) or ((30 + y * 190 / h) shl 8) or (220 - (x + y) * 180 / (w + h))
        }
        return b
    }

    /** [src] with its channels moved: output channel c holds channel `from[c]` of [src] (0 R, 1 G, 2 B). */
    fun permute(src: PixelBuffer, from: IntArray): PixelBuffer {
        val out = PixelBuffer(src.width, src.height)
        for (i in src.pixels.indices) {
            val c = src.pixels[i]
            fun ch(k: Int) = (c shr (16 - 8 * k)) and 0xFF
            out.pixels[i] = (c and 0xFF000000.toInt()) or (ch(from[0]) shl 16) or (ch(from[1]) shl 8) or ch(from[2])
        }
        return out
    }

    /** The inverse of [from] as a channel map. */
    fun inverse(from: IntArray): IntArray = IntArray(3).also { inv -> for (c in 0 until 3) inv[from[c]] = c }

    /** The channel (0 R, 1 G, 2 B) of a primary color. */
    fun channelOf(primary: Int): Int = when (primary) {
        R -> 0
        G -> 1
        B -> 2
        else -> error("not a primary: ${Integer.toHexString(primary)}")
    }
}
