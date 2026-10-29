package com.brushwork.paint.qa110

import com.brushwork.paint.exchange.export.BrushworkPayload
import com.brushwork.paint.exchange.export.ExportScene
import com.brushwork.paint.exchange.export.PayloadLayer
import com.brushwork.paint.exchange.export.PayloadRect
import com.brushwork.paint.exchange.export.PdfWriter
import com.brushwork.paint.exchange.export.SceneImage
import com.brushwork.paint.exchange.export.SceneItem
import com.brushwork.paint.exchange.export.SceneLayer
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerProps
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.io.BufferedOutputStream
import java.io.File
import java.io.FileOutputStream
import kotlin.math.PI
import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt
import kotlin.random.Random

/**
 * v1.10 Gate 2 (design 7, lane docs): the check artworks of `docs/device-checks-2.0.md`, written as
 * Brushwork PDF share files (what Share > Export PDF writes: one picture per layer and the Brushwork
 * data that brings every layer back). The user imports them on the phone with the gallery's "New
 * from SVG or PDF". PDF, not SVG: the SVG import reads the whole file into memory (at most 200 MB),
 * while a PDF is copied to the cache and its pictures are decoded from the file one at a time.
 *
 * Every layer is a full-canvas raster layer of seeded random-walk strokes (anti-aliased, one solid
 * color per stroke) covering about [COVERAGE] of the canvas, the rest transparent. The layers are
 * streamed: [PdfWriter] loads one layer's pixels when it reaches its picture, so the JVM holds one
 * layer at a time (80 MB at 4000 x 5000).
 *
 * [DeviceCheckArtworksGenerator] writes the four files when BW_DEVICE_ARTWORKS is set;
 * [DeviceCheckArtworksTest] runs the same code at 64 x 64 and imports the result back.
 */
internal object DeviceCheckArtworks {

    /**
     * One check artwork: [layers] layers on a [width] x [height] canvas. Layers above layer
     * [blendAbove] (1-based, bottom first) alternate Multiply and Screen; the others are Normal.
     */
    class Spec(val name: String, val width: Int, val height: Int, val layers: Int, val blendAbove: Int = Int.MAX_VALUE) {
        val fileName: String get() = "$name.pdf"
    }

    /** Design 7: the four check artworks. */
    val CHECK_200 = Spec("check-200", 1080, 2408, 200)
    val CHECK_500 = Spec("check-500", 1080, 2408, 500)
    val CHECK_100_BLEND = Spec("check-100-blend", 1080, 2408, 100, blendAbove = 50)
    val CHECK_4000X5000_50 = Spec("check-4000x5000-50", 4000, 5000, 50)
    val ALL: List<Spec> = listOf(CHECK_200, CHECK_500, CHECK_100_BLEND, CHECK_4000X5000_50)

    /** The share of a layer's pixels its strokes cover (alpha above 0). */
    const val COVERAGE = 0.35

    /** The DPI the files carry (a new canvas's default). */
    const val DPI = 350f

    /** Where [DeviceCheckArtworksGenerator] writes them (the test JVM runs in the app module). */
    val OUT_DIR: File get() = File("build/device-check-artworks")

    fun nameOf(n: Int): String = "Layer $n"

    /** The blend mode of layer [n] (1-based, bottom first) of [spec]. */
    fun blendOf(spec: Spec, n: Int): LayerBlendMode = when {
        n <= spec.blendAbove -> LayerBlendMode.NORMAL
        (n - spec.blendAbove) % 2 == 1 -> LayerBlendMode.MULTIPLY
        else -> LayerBlendMode.SCREEN
    }

    /** Layer [n]'s pixels (1-based), non-premultiplied ARGB, the same on every run. */
    fun pixels(spec: Spec, n: Int): IntArray = strokes(spec.width, spec.height, spec.name.hashCode() * 1_000_003L + n)

    /** Writes [spec] to [file]; [onLayer] hears each layer number as its pixels are made. */
    fun write(spec: Spec, file: File, onLayer: (Int) -> Unit = {}) {
        val w = spec.width
        val h = spec.height
        val rect = PayloadRect(0, 0, w, h)
        val payloadLayers = ArrayList<PayloadLayer>(spec.layers)
        val sceneLayers = ArrayList<SceneLayer>(spec.layers)
        for (n in 1..spec.layers) {
            val blend = blendOf(spec, n)
            val key = "img-$n"
            val props = LayerProps(nameOf(n), 1f, blend, visible = true, clipping = false, alphaLocked = false, locked = false, maskEnabled = true)
            payloadLayers += PayloadLayer(id = n.toLong(), props = props, imageRef = key, imageRect = rect)
            val image = SceneImage(key, 0, 0, w, h, gray = false) {
                onLayer(n)
                ArgbImage(w, h, pixels(spec, n))
            }
            sceneLayers += SceneLayer("layer-$n", nameOf(n), 1f, blend, hidden = false, mask = null, items = listOf(SceneItem.Image(image)))
        }
        val payload = BrushworkPayload(width = w, height = h, dpi = DPI, activeLayer = spec.layers - 1, layers = payloadLayers)
        val scene = ExportScene(w, h, DPI, spec.name, background = null, layers = sceneLayers, payload = payload)
        file.absoluteFile.parentFile?.mkdirs()
        BufferedOutputStream(FileOutputStream(file), 1 shl 16).use { out -> runBlocking { PdfWriter(scene).write(out) } }
    }

    /** The share of [px] with alpha above 0. */
    fun coverage(px: IntArray): Double = px.count { (it ushr 24) != 0 }.toDouble() / px.size

    /**
     * Seeded random-walk strokes on a transparent [w] x [h] canvas until [COVERAGE] of it is
     * covered: each stroke one opaque color, a radius of 0.6 to 2 % of the short side (at least
     * 1 px), segments of 1.5 radii that bounce off the edges, drawn as anti-aliased capsules over
     * what is there.
     */
    private fun strokes(w: Int, h: Int, seed: Long): IntArray {
        val px = IntArray(w * h)
        val target = ceil(COVERAGE * w * h).toLong()
        var covered = 0L
        val rnd = Random(seed)
        val short = min(w, h).toFloat()
        val rMin = max(1f, short * 0.006f)
        val rMax = max(rMin, short * 0.02f)
        while (covered < target) {
            val color = (0xFF shl 24) or (rnd.nextInt() and 0xFFFFFF)
            val r = rMin + rnd.nextFloat() * (rMax - rMin)
            var x = rnd.nextFloat() * w
            var y = rnd.nextFloat() * h
            var a = rnd.nextFloat() * 2f * PI.toFloat()
            val segments = 12 + rnd.nextInt(36)
            for (s in 0 until segments) {
                a += (rnd.nextFloat() - 0.5f) * 0.9f
                var nx = x + cos(a) * r * 1.5f
                var ny = y + sin(a) * r * 1.5f
                if (nx < 0f || nx > w - 1f) {
                    nx = nx.coerceIn(0f, w - 1f)
                    a = PI.toFloat() - a
                }
                if (ny < 0f || ny > h - 1f) {
                    ny = ny.coerceIn(0f, h - 1f)
                    a = -a
                }
                covered += capsule(px, w, h, x, y, nx, ny, r, color)
                x = nx
                y = ny
                if (covered >= target) break
            }
        }
        return px
    }

    /** Draws the capsule ([x0], [y0])-([x1], [y1]) of radius [r]; the number of pixels it newly covered. */
    private fun capsule(px: IntArray, w: Int, h: Int, x0: Float, y0: Float, x1: Float, y1: Float, r: Float, color: Int): Int {
        val left = max(0, floor(min(x0, x1) - r - 1f).toInt())
        val right = min(w - 1, ceil(max(x0, x1) + r + 1f).toInt())
        val top = max(0, floor(min(y0, y1) - r - 1f).toInt())
        val bottom = min(h - 1, ceil(max(y0, y1) + r + 1f).toInt())
        val dx = x1 - x0
        val dy = y1 - y0
        val len2 = dx * dx + dy * dy
        var added = 0
        for (yy in top..bottom) {
            val py = yy + 0.5f
            for (xx in left..right) {
                val pxf = xx + 0.5f
                val t = if (len2 > 0f) (((pxf - x0) * dx + (py - y0) * dy) / len2).coerceIn(0f, 1f) else 0f
                val ex = pxf - (x0 + t * dx)
                val ey = py - (y0 + t * dy)
                val cov = r + 0.5f - sqrt(ex * ex + ey * ey)
                if (cov <= 0f) continue
                val sa = if (cov >= 1f) 255 else (cov * 255f + 0.5f).toInt()
                if (sa == 0) continue
                val i = yy * w + xx
                val dst = px[i]
                if (dst == 0) added++
                px[i] = over(color, sa, dst)
            }
        }
        return added
    }

    /** [color]'s RGB at alpha [sa] over [dst], all non-premultiplied. */
    private fun over(color: Int, sa: Int, dst: Int): Int {
        val da = dst ushr 24
        if (sa == 255 || da == 0) return (sa shl 24) or (color and 0xFFFFFF)
        val keep = da * (255 - sa)
        val outA255 = sa * 255 + keep
        val outA = (outA255 + 127) / 255
        fun ch(shift: Int): Int {
            val sc = (color shr shift) and 0xFF
            val dc = (dst shr shift) and 0xFF
            return ((sc * sa * 255 + dc * keep + outA255 / 2) / outA255).coerceIn(0, 255)
        }
        return (outA shl 24) or (ch(16) shl 16) or (ch(8) shl 8) or ch(0)
    }
}

/**
 * Writes the four check artworks to app/build/device-check-artworks/ (design 7). It runs only when
 * the environment variable BW_DEVICE_ARTWORKS is set, so CI never runs it:
 * `$env:BW_DEVICE_ARTWORKS='1'; gradlew testDebugUnitTest --tests "*.DeviceCheckArtworksGenerator" --rerun`.
 */
class DeviceCheckArtworksGenerator {
    @Test
    fun writesTheFourCheckArtworks() {
        assumeTrue("set BW_DEVICE_ARTWORKS to write the device check artworks", System.getenv("BW_DEVICE_ARTWORKS") != null)
        val dir = DeviceCheckArtworks.OUT_DIR
        val start = System.currentTimeMillis()
        for (spec in DeviceCheckArtworks.ALL) {
            val t0 = System.currentTimeMillis()
            val file = File(dir, spec.fileName)
            DeviceCheckArtworks.write(spec, file)
            val p = OwnPdfReader.open(file).use { r -> r.payload() }
            assertEquals(spec.name, spec.layers, p!!.layers.size)
            assertEquals(spec.name, spec.width to spec.height, p.width to p.height)
            assertTrue(spec.name, file.length() > 0)
            println("DeviceCheckArtworks ${spec.fileName}: ${file.length()} bytes, ${System.currentTimeMillis() - t0} ms")
        }
        println("DeviceCheckArtworks all four in ${System.currentTimeMillis() - start} ms, in ${dir.absolutePath}")
    }
}
