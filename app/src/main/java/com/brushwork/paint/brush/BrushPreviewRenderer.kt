package com.brushwork.paint.brush

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.RectF
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.tools.ToolId
import kotlin.math.PI
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.math.sin

/**
 * Renders stroke previews of presets with the real brush engine (an S-shaped stylus stroke with
 * a pressure swell). Thread-safe: every call uses its own buffers, so it can run on
 * Dispatchers.Default.
 */
object BrushPreviewRenderer {
    /** Default ink: light, to read well on the dark panel. */
    const val INK = 0xFFE8EAED.toInt()

    private val stripeColors = intArrayOf(0xFFE57373.toInt(), 0xFFFFD54F.toInt(), 0xFF64B5F6.toInt(), 0xFF81C784.toInt())
    private const val ERASE_BAND = 0xFF8A8F98.toInt()

    /**
     * A [width] x [height] ARGB preview of [preset] used by [toolId], drawn with a brush of
     * [diameter] px. Aliased (pixel) brushes are rendered at 1/4 resolution and enlarged
     * without filtering so their pixels stay visible.
     */
    fun render(preset: BrushPreset, toolId: ToolId, width: Int, height: Int, diameter: Float, ink: Int = INK): Bitmap {
        val scale = if (preset.antiAlias) 1 else 4
        val w = max(8, width / scale)
        val h = max(8, height / scale)
        val d = (diameter / scale).coerceIn(1f, h * 0.9f)
        val p = preset.sanitized().copy(size = d)
        val kind = StrokeKind.of(toolId, p)
        val out = BitmapUtils.createLayerBitmap(w, h)
        drawBackground(out, kind)

        val tips = TipCache(8L shl 20)
        val stamper = DabStamper(tips)
        val dynamics = StrokeDynamics(p, isStylus = true, seed = 12345L)
        val coverage = if (kind.isDirect) null else Bitmap.createBitmap(w, h, Bitmap.Config.ALPHA_8)
        val covCanvas = coverage?.let { Canvas(it) }
        val direct = if (kind.isDirect) DirectPainter(kind, p, BitmapSurface(out), null, false, ink or 0xFF000000.toInt(), d) else null
        val sampler = StrokeSampler(
            spacingAt = { pr, dist -> dynamics.spacing(pr, dist) },
            onSample = { x, y, pr, dist ->
                val dab = dynamics.newDab(x, y, pr, dist)
                dynamics.resolve(dab, null)
                if (direct != null) direct.apply(dab) else stamper.stamp(covCanvas!!, p, dab)
            },
        )
        val margin = d / 2f + 2f
        val amp = max(0f, h / 2f - margin) * 0.75f
        val x0 = min(w * 0.08f + margin * 0.5f, w * 0.3f)
        val x1 = w - x0
        val steps = 48
        for (i in 0..steps) {
            val t = i.toFloat() / steps
            val x = x0 + (x1 - x0) * t
            val y = h / 2f - amp * sin(t * 2f * PI.toFloat())
            val pressure = 0.25f + 0.75f * sin(t * PI.toFloat())
            if (i == 0) sampler.begin(x, y, pressure) else sampler.add(x, y, pressure)
        }
        sampler.end()

        if (coverage != null) {
            val style = CoverageStyle(
                mode = if (kind == StrokeKind.ERASE) PorterDuff.Mode.DST_OUT else PorterDuff.Mode.SRC_OVER,
                color = if (kind == StrokeKind.ERASE) 0xFF000000.toInt() else ink or 0xFF000000.toInt(),
                opacity = p.opacity,
                grain = p.grain,
            )
            CoveragePainter().draw(Canvas(out), coverage, Rect(0, 0, w, h), style, null)
            coverage.recycle()
        }
        tips.clear()
        if (scale == 1) return out
        val big = Bitmap.createScaledBitmap(out, w * scale, h * scale, false)
        if (big !== out) out.recycle()
        return big
    }

    private fun drawBackground(out: Bitmap, kind: StrokeKind) {
        val w = out.width
        val h = out.height
        val c = Canvas(out)
        val paint = Paint(Paint.ANTI_ALIAS_FLAG)
        when (kind) {
            StrokeKind.ERASE -> {
                paint.color = ERASE_BAND
                val r = h * 0.18f
                c.drawRoundRect(RectF(w * 0.04f, h * 0.14f, w * 0.96f, h * 0.86f), r, r, paint)
            }
            StrokeKind.SMUDGE, StrokeKind.BLUR -> {
                val n = stripeColors.size * 2
                val sw = w.toFloat() / n
                for (i in 0 until n) {
                    paint.color = stripeColors[i % stripeColors.size]
                    c.drawRect(i * sw, 0f, (i + 1) * sw + 0.5f, h.toFloat(), paint)
                }
            }
            else -> {}
        }
    }

    /** Diameter that shows a preset's character in a thumbnail [height] px tall. */
    fun thumbnailDiameter(preset: BrushPreset, height: Int): Float {
        if (!preset.antiAlias) return (preset.size * 4f).coerceIn(4f, height * 0.3f)
        val k = height / 60f
        return (preset.size * k).coerceIn(height * 0.08f, height * 0.5f).let { max(2f, it) }.roundToInt().toFloat()
    }
}
