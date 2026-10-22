package com.brushwork.paint.v110.a1

import com.brushwork.paint.core.Vec2
import com.brushwork.paint.vector.CopyMaps
import com.brushwork.paint.vector.ObjectCopies
import com.brushwork.paint.vector.VObject
import kotlin.math.cos
import kotlin.math.sin

/**
 * Copy maps of the A1 (R1, mirror sides) tests on [com.brushwork.paint.vector.select.ObjectTestKit]'s
 * 512×384 document: each map kind the rulers make (Mirror, Kaleidoscope, Rotation, Array grid),
 * as 3×3 row-major affine maps about the document centre.
 */
internal object A1Kit {
    const val CX = 256f
    const val CY = 192f

    fun identity() = floatArrayOf(1f, 0f, 0f, 0f, 1f, 0f, 0f, 0f, 1f)

    /** x ↦ 2·CX − x (a vertical mirror line through the centre). */
    fun mirrorX() = floatArrayOf(-1f, 0f, 2f * CX, 0f, 1f, 0f, 0f, 0f, 1f)

    /** y ↦ 2·CY − y. */
    fun mirrorY() = floatArrayOf(1f, 0f, 0f, 0f, -1f, 2f * CY, 0f, 0f, 1f)

    fun translate(dx: Float, dy: Float) = floatArrayOf(1f, 0f, dx, 0f, 1f, dy, 0f, 0f, 1f)

    /** A turn by [deg] about the document centre. */
    fun turn(deg: Double): FloatArray = turnAbout(CX, CY, deg)

    /** A turn by [deg] about ([px], [py]). */
    fun turnAbout(px: Float, py: Float, deg: Double): FloatArray {
        val r = Math.toRadians(deg)
        val c = cos(r).toFloat()
        val s = sin(r).toFloat()
        return floatArrayOf(c, -s, px - c * px + s * py, s, c, py - s * px - c * py, 0f, 0f, 1f)
    }

    fun mul(a: FloatArray, b: FloatArray): FloatArray = FloatArray(9) { i ->
        val r = i / 3
        val col = i % 3
        a[r * 3] * b[col] + a[r * 3 + 1] * b[3 + col] + a[r * 3 + 2] * b[6 + col]
    }

    fun apply(m: FloatArray, p: Vec2) = Vec2(m[0] * p.x + m[1] * p.y + m[2], m[3] * p.x + m[4] * p.y + m[5])

    /** The four map kinds, named. */
    fun kinds(): List<Pair<String, List<FloatArray>>> = listOf(
        "Mirror" to listOf(identity(), mirrorX()),
        "Kaleidoscope" to listOf(identity(), mirrorX(), mirrorY(), mul(mirrorX(), mirrorY())),
        "Rotation" to listOf(identity(), turn(120.0), turn(240.0)),
        "Array" to listOf(identity(), translate(120f, 0f), translate(0f, 160f), translate(300f, 200f)),
    )

    fun withMaps(o: VObject, maps: List<FloatArray>): VObject = ObjectCopies.withCopies(o, CopyMaps.of(maps))
}
