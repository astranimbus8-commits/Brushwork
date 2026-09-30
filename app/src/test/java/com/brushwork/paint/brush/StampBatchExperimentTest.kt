package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.BitmapShader
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.Shader
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.sin

/** TEMPORARY experiment: drawBitmap(matrix) per dab vs one drawVertices per batch. */
@RunWith(RobolectricTestRunner::class)
class StampBatchExperimentTest {

    private fun dabs(n: Int, spacing: Float): List<Dab> {
        val out = ArrayList<Dab>(n)
        for (i in 0 until n) {
            val t = i * spacing
            val x = 100f + (t % 800f)
            val y = 100f + (t / 800f).toInt() * 20f + 30f * sin(t / 60f)
            out += Dab(x, y, 1f, t, 0f, 0f, 0f, 0, 0.5f).also { it.diameter = 8f; it.alpha = 1f; it.cx = x; it.cy = y }
        }
        return out
    }

    private fun a8(w: Int, h: Int) = Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)

    private fun pixels(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    @Test
    fun compare() {
        val preset = BrushLibrary.defaultBrush
        val tips = TipCache()
        val stamper = DabStamper(tips)
        for (round in 0 until 3) {
            val list = dabs(9000, 0.5f)
            val a = a8(1080, 1200)
            val ca = Canvas(a)
            val t0 = System.nanoTime()
            for (d in list) stamper.stamp(ca, preset, d)
            val t1 = System.nanoTime()

            val b = a8(1080, 1200)
            val cb = Canvas(b)
            val tip = tips.get(preset, 8f, 0)
            val paint = Paint(Paint.FILTER_BITMAP_FLAG).apply { shader = BitmapShader(tip.bitmap, Shader.TileMode.CLAMP, Shader.TileMode.CLAMP) }
            val batch = 512
            val verts = FloatArray(batch * 8)
            val texs = FloatArray(batch * 8)
            val colors = IntArray(batch * 4)
            val idx = ShortArray(batch * 6)
            for (q in 0 until batch) {
                val v = (q * 4).toShort()
                idx[q * 6] = v; idx[q * 6 + 1] = (v + 1).toShort(); idx[q * 6 + 2] = (v + 2).toShort()
                idx[q * 6 + 3] = v; idx[q * 6 + 4] = (v + 2).toShort(); idx[q * 6 + 5] = (v + 3).toShort()
            }
            val t2 = System.nanoTime()
            var k = 0
            fun flush() {
                if (k == 0) return
                cb.drawVertices(Canvas.VertexMode.TRIANGLES, k * 8, verts, 0, texs, 0, colors, 0, idx, 0, k * 6, paint)
                k = 0
            }
            for (d in list) {
                val s = d.diameter / tip.diameter
                val half = tip.size / 2f * s
                val sz = tip.size.toFloat()
                val alpha = (d.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                val o = k * 8
                verts[o] = d.cx - half; verts[o + 1] = d.cy - half
                verts[o + 2] = d.cx + half; verts[o + 3] = d.cy - half
                verts[o + 4] = d.cx + half; verts[o + 5] = d.cy + half
                verts[o + 6] = d.cx - half; verts[o + 7] = d.cy + half
                texs[o] = 0f; texs[o + 1] = 0f; texs[o + 2] = sz; texs[o + 3] = 0f
                texs[o + 4] = sz; texs[o + 5] = sz; texs[o + 6] = 0f; texs[o + 7] = sz
                val col = (alpha shl 24) or 0xFFFFFF
                for (j in 0 until 4) colors[k * 4 + j] = col
                k++
                if (k == batch) flush()
            }
            flush()
            val t3 = System.nanoTime()
            // Sprite blits: integer position, no filtering, no matrix.
            val sprite = a8(1080, 1200)
            val cs = Canvas(sprite)
            val sp = Paint()
            val t4 = System.nanoTime()
            for (d in list) {
                sp.alpha = (d.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                cs.drawBitmap(tip.bitmap, Math.round(d.cx - tip.size / 2f).toFloat(), Math.round(d.cy - tip.size / 2f).toFloat(), sp)
            }
            val t5 = System.nanoTime()
            // Same with a translate-only matrix and filtering (sub-pixel position, no scale).
            val tr = a8(1080, 1200)
            val ct = Canvas(tr)
            val m = android.graphics.Matrix()
            val fp = Paint(Paint.FILTER_BITMAP_FLAG)
            val t6 = System.nanoTime()
            for (d in list) {
                fp.alpha = (d.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                m.setTranslate(d.cx - tip.size / 2f, d.cy - tip.size / 2f)
                ct.drawBitmap(tip.bitmap, m, fp)
            }
            val t7 = System.nanoTime()
            println(String.format("[exp] sprite %.2f ms, translate-filtered %.2f ms", (t5 - t4) / 1e6, (t7 - t6) / 1e6))
            val aaPaint = Paint().apply { isFilterBitmap = true }
            println("[exp] Paint() flags=${Paint().flags} aa=${Paint().isAntiAlias}")
            val t8 = System.nanoTime()
            val tr2 = a8(1080, 1200)
            val ct2 = Canvas(tr2)
            for (d in list) {
                aaPaint.alpha = (d.alpha * 255f + 0.5f).toInt().coerceIn(0, 255)
                m.setTranslate(d.cx - tip.size / 2f, d.cy - tip.size / 2f)
                ct2.drawBitmap(tip.bitmap, m, aaPaint)
            }
            val t9 = System.nanoTime()
            val q1 = pixels(tr); val q2 = pixels(tr2)
            println(String.format("[exp] AA-paint translate %.2f ms; differing px vs non-AA: %d", (t9 - t8) / 1e6, q1.indices.count { q1[it] != q2[it] }))
            val pa = pixels(a); val pb = pixels(b)
            var maxDiff = 0; var diffCount = 0; var sumA = 0L; var sumB = 0L
            for (i in pa.indices) {
                val x = pa[i] ushr 24; val y = pb[i] ushr 24
                sumA += x; sumB += y
                val dd = abs(x - y)
                if (dd > 0) diffCount++
                if (dd > maxDiff) maxDiff = dd
            }
            println(String.format("[exp] round %d: drawBitmap %.2f ms, drawVertices %.2f ms; maxDiff=%d diffPx=%d sumA=%d sumB=%d", round, (t1 - t0) / 1e6, (t3 - t2) / 1e6, maxDiff, diffCount, sumA, sumB))

            // Single isolated dab comparison at a sub-pixel position.
            val one = Dab(50.3f, 40.7f, 1f, 0f, 0f, 0f, 0f, 0, 0.5f).also { it.diameter = 8f; it.alpha = 0.6f; it.cx = 50.3f; it.cy = 40.7f }
            val x1 = a8(100, 100); stamper.stamp(Canvas(x1), preset, one)
            val x2 = a8(100, 100)
            run {
                val s = one.diameter / tip.diameter
                val half = tip.size / 2f * s
                val sz = tip.size.toFloat()
                val v = floatArrayOf(one.cx - half, one.cy - half, one.cx + half, one.cy - half, one.cx + half, one.cy + half, one.cx - half, one.cy + half)
                val tx = floatArrayOf(0f, 0f, sz, 0f, sz, sz, 0f, sz)
                val alpha = (one.alpha * 255f + 0.5f).toInt()
                val col = IntArray(4) { (alpha shl 24) or 0xFFFFFF }
                Canvas(x2).drawVertices(Canvas.VertexMode.TRIANGLES, 8, v, 0, tx, 0, col, 0, shortArrayOf(0, 1, 2, 0, 2, 3), 0, 6, paint)
            }
            val p1 = pixels(x1); val p2 = pixels(x2)
            var md = 0; var s1 = 0L; var s2 = 0L
            for (i in p1.indices) { s1 += p1[i] ushr 24; s2 += p2[i] ushr 24; md = maxOf(md, abs((p1[i] ushr 24) - (p2[i] ushr 24))) }
            println("[exp] single dab: maxDiff=$md sum1=$s1 sum2=$s2 center1=${p1[41 * 100 + 50] ushr 24} center2=${p2[41 * 100 + 50] ushr 24}")
        }
    }
}
