package com.brushwork.paint.v110.lead

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.os.Build
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.EngineConfig
import com.brushwork.paint.engine.EngineLevel
import com.brushwork.paint.engine.EngineNotices
import com.brushwork.paint.engine.tiles.ChaosResidency
import com.brushwork.paint.engine.tiles.ClassicResidency
import com.brushwork.paint.engine.tiles.ResidencyHooks
import com.brushwork.paint.engine.tiles.TILE_SIZE
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.PixelFacade
import java.util.Random
import org.junit.Assert.assertEquals
import org.junit.Assert.fail
import org.robolectric.util.ReflectionHelpers

/**
 * v1.10 F1 lead tests (frozen with them): deterministic test pictures, bitwise comparison, the
 * API level switch for the API 26-28 paths, and the reset of the engine's static state every F1
 * lead test runs in its `@After`.
 */
internal object F1Pixels {
    /** Every pixel of [b], as `getPixels` gives them. */
    fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    /** [actual] equals [expected] bit for bit (the first differing pixel in the message). */
    fun assertBitEqual(message: String, expected: Bitmap, actual: Bitmap) {
        assertEquals("$message: width", expected.width, actual.width)
        assertEquals("$message: height", expected.height, actual.height)
        val e = pixels(expected)
        val a = pixels(actual)
        for (i in e.indices) {
            if (e[i] != a[i]) {
                fail("$message: pixel (${i % expected.width}, ${i / expected.width}) is ${hex(a[i])}, expected ${hex(e[i])}")
            }
        }
    }

    /** The largest channel difference between [a] and [b] (same size). */
    fun maxDiff(a: Bitmap, b: Bitmap): Int {
        val pa = pixels(a)
        val pb = pixels(b)
        var m = 0
        for (i in pa.indices) for (s in 0..24 step 8) m = maxOf(m, kotlin.math.abs((pa[i] ushr s and 0xFF) - (pb[i] ushr s and 0xFF)))
        return m
    }

    fun hex(c: Int): String = "#%08X".format(c)

    /** A [w] x [h] picture of random colors and alphas ([opaque]: alpha 255), from [seed]. */
    fun noise(w: Int, h: Int, seed: Long, opaque: Boolean = false): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h) { if (opaque) r.nextInt() or (0xFF shl 24) else r.nextInt() }
        return BitmapUtils.createLayerBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /** A grayscale random picture (a painted mask), from [seed]. */
    fun grayNoise(w: Int, h: Int, seed: Long): Bitmap {
        val r = Random(seed)
        val px = IntArray(w * h) { val v = r.nextInt(256); Color.argb(255, v, v, v) }
        return BitmapUtils.createLayerBitmap(w, h).apply { setPixels(px, 0, w, 0, 0, w, h) }
    }

    /**
     * A [w] x [h] picture whose 256 px tiles are, in turn: random noise, transparent, one opaque
     * color, one translucent color, and partly painted (a disc on transparency), so a cut makes
     * ordinary, null, solid and edge tiles.
     */
    fun mixed(w: Int, h: Int, seed: Long): Bitmap {
        val out = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(out)
        val cols = (w + TILE_SIZE - 1) / TILE_SIZE
        val rows = (h + TILE_SIZE - 1) / TILE_SIZE
        val n = noise(TILE_SIZE, TILE_SIZE, seed)
        val paint = android.graphics.Paint().apply { isAntiAlias = true; color = 0xCC3366AA.toInt() }
        for (row in 0 until rows) for (col in 0 until cols) {
            val x = col * TILE_SIZE
            val y = row * TILE_SIZE
            c.save()
            c.clipRect(x, y, x + TILE_SIZE, y + TILE_SIZE)
            when ((row * cols + col) % 5) {
                0 -> c.drawBitmap(n, x.toFloat(), y.toFloat(), null)
                1 -> Unit
                2 -> c.drawColor(0xFF20A040.toInt())
                3 -> c.drawColor(0x80402010.toInt())
                else -> c.drawCircle(x + 90f, y + 70f, 60f, paint)
            }
            c.restore()
        }
        n.recycle()
        return out
    }

    /** A mask whose tiles are, in turn: white (a never-painted mask), transparent, gray noise, one gray, partly black. */
    fun mixedMask(w: Int, h: Int, seed: Long): Bitmap {
        val out = BitmapUtils.createLayerBitmap(w, h)
        val c = Canvas(out)
        val cols = (w + TILE_SIZE - 1) / TILE_SIZE
        val rows = (h + TILE_SIZE - 1) / TILE_SIZE
        val g = grayNoise(TILE_SIZE, TILE_SIZE, seed)
        val black = android.graphics.Paint().apply { isAntiAlias = true; color = Color.BLACK }
        for (row in 0 until rows) for (col in 0 until cols) {
            val x = col * TILE_SIZE
            val y = row * TILE_SIZE
            c.save()
            c.clipRect(x, y, x + TILE_SIZE, y + TILE_SIZE)
            when ((row * cols + col) % 5) {
                0 -> c.drawColor(Color.WHITE)
                1 -> Unit
                2 -> c.drawBitmap(g, x.toFloat(), y.toFloat(), null)
                3 -> c.drawColor(0xFF7F7F7F.toInt())
                else -> { c.drawColor(Color.WHITE); c.drawCircle(x + 120f, y + 100f, 80f, black) }
            }
            c.restore()
        }
        g.recycle()
        return out
    }

    /** [doc] flattened by the real compositor (no overrides, no background). */
    fun flatten(doc: Document): Bitmap = Compositor(doc) { null }.renderFlattened()

    /** Runs [block] with `Build.VERSION.SDK_INT` = [level] (the API 26-28 paths), then puts it back. */
    fun <T> atSdk(level: Int, block: () -> T): T {
        val saved = Build.VERSION.SDK_INT
        ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", level)
        try {
            return block()
        } finally {
            ReflectionHelpers.setStaticField(Build.VERSION::class.java, "SDK_INT", saved)
        }
    }

    /**
     * Puts the engine's static state back: no session, the facade lenient, no notice, and the
     * residency the JVM started with: [ClassicResidency], or [ChaosResidency] under a test level
     * above CLASSIC (`-PengineMode`, unit-full), so a lead test never turns a unit-full shard Classic.
     */
    fun resetEngine() {
        PixelFacade.strict = false
        EngineConfig.closeSession()
        EngineConfig.residency = defaultResidency()
        EngineNotices.current = null
    }

    /** The residency outside a session: the test level's (as `EngineConfig` starts with it). */
    fun defaultResidency(): ResidencyHooks {
        val level = EngineConfig.testMode()
        return if (level != null && level != EngineLevel.CLASSIC) ChaosResidency(EngineConfig.parkChaos()) else ClassicResidency
    }
}
