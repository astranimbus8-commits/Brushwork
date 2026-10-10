package com.brushwork.paint.probes

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.ColorMatrix
import android.graphics.ColorMatrixColorFilter
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.Path
import android.graphics.Shader
import com.brushwork.paint.engine.BitmapUtils
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.roundToInt

/**
 * v1.8 F0 capability probes (design §8, F0): the three canvas features the v1.8 renderers rely
 * on, checked pixel by pixel under Robolectric's NATIVE graphics so the areas can test them on
 * the JVM. Each prints its finding ("v18 F0 probe: ...").
 *
 * - A homography (`Matrix.setPolyToPoly` with 4 points) under `Canvas.concat` maps paths with
 *   true perspective: the mid line of a square lands on the diagonals' crossing, not halfway.
 *   Perspective arrays and Distort bake through it (D2).
 * - An ALPHA_8 coverage buffer drawn with a `BitmapShader` paint under `concat(M)` is placed by
 *   `M`, and the shader's local matrix `L` composes with it: a device pixel `p` shows the source
 *   at `(M·L)⁻¹·p`. With `L = T(o)·M⁻¹` a copy samples at `M·T(o)·M⁻¹`, the source offset seen
 *   through the copy's map (D1b: one coverage buffer, drawn once per copy). The shader also
 *   follows `drawBitmap`'s left / top, so the coverage is drawn at (0, 0), as `CoveragePainter`
 *   draws it.
 * - A `ColorMatrixColorFilter` on a `saveLayer` paint applies the Rec.709 tint
 *   `c' = (1 - s)·c + s·T·Y(c)` to the layer's composite, on unpremultiplied colors, alpha
 *   unchanged (D9: text and shape copies).
 */
@RunWith(RobolectricTestRunner::class)
class V18ProbesTest {
    private fun bitmap(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)

    private fun hex(c: Int) = "#%08X".format(c)

    @Test
    fun aHomographyUnderConcatMapsPathsWithPerspective() {
        // The square (0, 0)..(100, 100) onto a trapezoid narrow at the top.
        val m = Matrix()
        val ok = m.setPolyToPoly(
            floatArrayOf(0f, 0f, 100f, 0f, 100f, 100f, 0f, 100f), 0,
            floatArrayOf(20f, 10f, 80f, 10f, 100f, 90f, 0f, 90f), 0, 4,
        )
        assertTrue("setPolyToPoly with 4 points", ok)
        assertFalse("the map is a homography, not affine", m.isAffine)
        // The square's mid line y = 50 maps through the diagonals' crossing, (50, 40); an affine
        // or bilinear stand-in would put it at y = 50.
        val mid = floatArrayOf(50f, 50f).also { m.mapPoints(it) }
        assertEquals(50f, mid[0], 0.01f)
        assertEquals(40f, mid[1], 0.01f)

        val out = bitmap(100, 100)
        val canvas = Canvas(out)
        canvas.concat(m)
        val red = Paint().apply { color = Color.RED; isAntiAlias = false }
        val blue = Paint().apply { color = Color.BLUE; isAntiAlias = false }
        canvas.drawPath(Path().apply { addRect(0f, 0f, 100f, 50f, Path.Direction.CW) }, red)
        canvas.drawPath(Path().apply { moveTo(0f, 50f); lineTo(100f, 50f); lineTo(100f, 100f); lineTo(0f, 100f); close() }, blue)

        assertEquals("top half, above the crossing", Color.RED, out.getPixel(50, 36))
        assertEquals("bottom half, below the crossing (y 44 < 50: perspective)", Color.BLUE, out.getPixel(50, 44))
        assertEquals("left of the narrow top edge", 0, out.getPixel(10, 15))
        assertEquals("right of the narrow top edge", 0, out.getPixel(90, 15))
        assertEquals("above the trapezoid", 0, out.getPixel(50, 5))
        assertEquals("below the trapezoid", 0, out.getPixel(50, 95))
        assertEquals("the wide bottom edge", Color.BLUE, out.getPixel(3, 88))
        // The mid line's crossing, row by row, down the center column.
        val boundary = (0 until 100).first { out.getPixel(50, it) == Color.BLUE }
        assertEquals("the mid line at the center column", 40, boundary)
        println("v18 F0 probe: homography concat with paths: perspective-correct (mid line at y $boundary, an affine map would give 50)")
    }

    @Test
    fun anAlpha8CoverageDrawnWithABitmapShaderUnderConcat() {
        // The source: a gradient, so every pixel tells where it was sampled.
        val src = bitmap(128, 128)
        for (y in 0 until 128) for (x in 0 until 128) src.setPixel(x, y, Color.argb(255, x * 2, y * 2, 77))
        // The coverage, document sized like the brush's: full on x 30..39, y 40..59, empty elsewhere.
        val coverage = BitmapUtils.bytesToAlpha8(ByteArray(128 * 128) { if (it % 128 in 30..39 && it / 128 in 40..59) 255.toByte() else 0 }, 128, 128)
        val o = Matrix().apply { setTranslate(10f, 5f) } // T(o): device p samples the source at p - o.

        // The copy's map M: a mirror about x = 60.
        val m = Matrix().apply { setScale(-1f, 1f, 60f, 0f) }
        val mInv = Matrix().also { assertTrue(m.invert(it)) }
        // The shader's local matrix T(o)·M⁻¹ (M⁻¹ applied first).
        val local = Matrix().apply { setConcat(o, mInv) }

        fun draw(ctm: Matrix?, shaderLocal: Matrix, cover: Bitmap = coverage, left: Float = 0f, top: Float = 0f): Bitmap {
            val out = bitmap(128, 128)
            val canvas = Canvas(out)
            if (ctm != null) canvas.concat(ctm)
            val paint = Paint().apply {
                isFilterBitmap = false
                shader = BitmapShader(src, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP).apply { setLocalMatrix(shaderLocal) }
            }
            canvas.drawBitmap(cover, left, top, paint)
            return out
        }

        // Today's single draw: coverage at x 30..39, sampling p - o.
        val plain = draw(null, o)
        assertEquals("plain: covered pixel samples p - o", src.getPixel(25, 45), plain.getPixel(35, 50))
        assertEquals("plain: outside the coverage stays empty", 0, plain.getPixel(45, 50))
        // The shader follows drawBitmap's left / top: a 20 × 20 coverage drawn at (30, 40) samples
        // p - (30, 40) - o, so (35, 50) shows the clamped source (0, 5), not (25, 45).
        val small = BitmapUtils.bytesToAlpha8(ByteArray(20 * 20) { 255.toByte() }, 20, 20)
        val offset = draw(null, o, small, 30f, 40f)
        assertEquals("the shader moves with the draw's offset", hex(src.getPixel(0, 5)), hex(offset.getPixel(35, 50)))

        // The copy: coverage x 30..39 mirrored to 80..89; device p samples (M·L)⁻¹·p.
        val copy = draw(m, local)
        val total = Matrix().apply { setConcat(m, local) }
        val totalInv = Matrix().also { assertTrue(total.invert(it)) }
        var checked = 0
        for (y in 40 until 60) for (x in 70 until 100) {
            val got = copy.getPixel(x, y)
            if (x in 80..89) {
                val s = floatArrayOf(x + 0.5f, y + 0.5f).also { totalInv.mapPoints(it) }
                val want = src.getPixel(s[0].toInt().coerceIn(0, 127), s[1].toInt().coerceIn(0, 127))
                assertEquals("copy: pixel ($x, $y) samples the source at (${s[0]}, ${s[1]})", hex(want), hex(got))
                checked++
            } else {
                assertEquals("copy: pixel ($x, $y) outside the mirrored coverage", 0, got)
            }
        }
        assertEquals("the copy is not at the coverage's own place", 0, copy.getPixel(35, 50))
        // M·T(o)·M⁻¹ is T(-10, 5): the mirrored copy samples p + (10, -5).
        assertEquals("the mirrored offset", hex(src.getPixel(95, 45)), hex(copy.getPixel(85, 50)))
        println("v18 F0 probe: ALPHA_8 coverage + BitmapShader under concat: placed by M, sampled at (M·L)⁻¹·p with L = T(o)·M⁻¹ ($checked pixels exact); the shader also follows drawBitmap's left/top, so draw the coverage at (0, 0)")
    }

    @Test
    fun aColorMatrixFilterOnASaveLayerPaintTintsTheComposite() {
        val s = 0.6f
        val tint = Color.rgb(255, 160, 0)
        val tr = Color.red(tint) / 255f
        val tg = Color.green(tint) / 255f
        val tb = Color.blue(tint) / 255f
        // c' = (1 - s)·c + s·T·Y(c), Y = 0.2126 R + 0.7152 G + 0.0722 B; alpha unchanged.
        fun row(identity: Int, t: Float) = FloatArray(5) { i ->
            when (i) {
                in 0..2 -> (if (i == identity) 1f - s else 0f) + s * t * floatArrayOf(0.2126f, 0.7152f, 0.0722f)[i]
                else -> 0f
            }
        }
        val matrix = ColorMatrix(row(0, tr) + row(1, tg) + row(2, tb) + floatArrayOf(0f, 0f, 0f, 1f, 0f))

        fun scene(canvas: Canvas) {
            canvas.drawRect(10f, 10f, 60f, 60f, Paint().apply { color = Color.argb(255, 30, 200, 90) })
            canvas.drawRect(40f, 40f, 90f, 90f, Paint().apply { color = Color.argb(170, 220, 40, 180) })
            canvas.drawRect(5f, 70f, 30f, 95f, Paint().apply { color = Color.argb(200, 250, 250, 250) })
        }
        val plain = bitmap(100, 100).also { scene(Canvas(it)) }
        val tinted = bitmap(100, 100)
        val canvas = Canvas(tinted)
        canvas.saveLayer(null, Paint().apply { colorFilter = ColorMatrixColorFilter(matrix) })
        scene(canvas)
        canvas.restore()

        var worst = 0
        var checked = 0
        for (y in 0 until 100 step 3) for (x in 0 until 100 step 3) {
            val c = plain.getPixel(x, y)
            val got = tinted.getPixel(x, y)
            assertEquals("alpha unchanged at ($x, $y)", Color.alpha(c), Color.alpha(got))
            if (Color.alpha(c) == 0) continue
            val r = Color.red(c) / 255f
            val g = Color.green(c) / 255f
            val b = Color.blue(c) / 255f
            val lum = 0.2126f * r + 0.7152f * g + 0.0722f * b
            val want = intArrayOf(
                (((1 - s) * r + s * tr * lum) * 255f).roundToInt().coerceIn(0, 255),
                (((1 - s) * g + s * tg * lum) * 255f).roundToInt().coerceIn(0, 255),
                (((1 - s) * b + s * tb * lum) * 255f).roundToInt().coerceIn(0, 255),
            )
            val have = intArrayOf(Color.red(got), Color.green(got), Color.blue(got))
            for (i in 0..2) worst = maxOf(worst, abs(want[i] - have[i]))
            checked++
        }
        assertTrue("pixels checked ($checked)", checked > 400)
        assertTrue("the tint matches the formula within 3 levels (worst $worst)", worst <= 3)
        // The overlap is tinted once, as a composite (a copy's objects are one group, R12).
        assertTrue("the overlap differs from the untinted composite", plain.getPixel(50, 50) != tinted.getPixel(50, 50))
        println("v18 F0 probe: ColorMatrixColorFilter on a saveLayer paint: the Rec.709 tint on the composite, unpremultiplied, alpha unchanged ($checked pixels, worst ${worst} levels)")
    }
}
