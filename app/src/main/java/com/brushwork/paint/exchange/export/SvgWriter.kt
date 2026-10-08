package com.brushwork.paint.exchange.export

import com.brushwork.paint.exchange.image.PngEncoder
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.BufferedOutputStream
import java.io.FilterOutputStream
import java.io.OutputStream
import java.util.Base64
import kotlin.coroutines.coroutineContext
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToLong

/**
 * Writes an [ExportScene] as SVG 1.1 (v1.5 §4.10): the document size in mm from its DPI with a
 * `viewBox` in document px; each layer an Inkscape layer group (`inkscape:groupmode="layer"`,
 * label, `opacity`, `mix-blend-mode` — Add is `plus-lighter` — and `isolation:isolate`; v1.7: a
 * folder's layers nested in its group, a pass-through folder a plain group); masks as
 * luminance `<mask>`s over the whole document; pictures as PNG data URIs (streamed base64);
 * paths with solid or gradient fills and plain strokes; text as `<text>` / `<tspan>`; the
 * Brushwork payload in `<metadata>`. Pure Kotlin.
 */
class SvgWriter(private val scene: ExportScene, private val onProgress: (Float) -> Unit = {}) {
    private lateinit var out: OutputStream
    private var gradients = 0
    private var imagesDone = 0
    private var cancelled: () -> Boolean = { false }

    /** Writes the document to [target] (not closed). Cancellable between pictures and bands. */
    suspend fun write(target: OutputStream) {
        val job = coroutineContext[Job]
        cancelled = { job?.isActive == false }
        out = BufferedOutputStream(NonClosing(target), 64 * 1024)
        val w = scene.width
        val h = scene.height
        val dpi = if (scene.dpi.isFinite() && scene.dpi > 0f) scene.dpi else 96f
        text("<?xml version=\"1.0\" encoding=\"UTF-8\" standalone=\"no\"?>\n<!-- Created with Brushwork -->\n")
        text("<svg xmlns=\"http://www.w3.org/2000/svg\" xmlns:xlink=\"http://www.w3.org/1999/xlink\"")
        text(" xmlns:inkscape=\"http://www.inkscape.org/namespaces/inkscape\" xmlns:bw=\"${Payload.SVG_NAMESPACE}\" version=\"1.1\"")
        text(" width=\"${num(w * 25.4 / dpi)}mm\" height=\"${num(h * 25.4 / dpi)}mm\" viewBox=\"0 0 $w $h\">\n")
        text("<title>${esc(scene.title)}</title>\n")
        scene.payload?.let { p ->
            text("<metadata id=\"brushwork-data\"><bw:payload version=\"${Payload.writtenVersion(p)}\" encoding=\"deflate+base64\">")
            text(Payload.toBase64(p))
            text("</bw:payload>")
            for (img in scene.payloadImages) {
                coroutineContext.ensureActive()
                text("\n<bw:image id=\"${esc(img.key)}\" x=\"${img.left}\" y=\"${img.top}\" width=\"${img.width}\" height=\"${img.height}\">")
                png(img)
                text("</bw:image>")
            }
            text("</metadata>\n")
        }
        scene.background?.let { bg ->
            text("<rect id=\"background\" x=\"0\" y=\"0\" width=\"$w\" height=\"$h\" fill=\"${color(bg)}\"/>\n")
        }
        for (layer in scene.layers) {
            coroutineContext.ensureActive()
            layer(layer)
        }
        text("</svg>\n")
        out.flush()
    }

    private suspend fun layer(layer: SceneLayer) {
        val mask = layer.mask
        if (mask != null) {
            text("<defs><mask id=\"${esc(mask.key)}\" maskUnits=\"userSpaceOnUse\" x=\"0\" y=\"0\" width=\"${scene.width}\" height=\"${scene.height}\" style=\"mask-type:luminance\">")
            text("<rect x=\"0\" y=\"0\" width=\"${scene.width}\" height=\"${scene.height}\" fill=\"${gray(mask.fillGray)}\"/>")
            // (Its id lets the Brushwork payload find the picture.)
            mask.image?.let { image(it, it.key) }
            text("</mask></defs>\n")
        }
        val style = StringBuilder()
        val opacity = layer.opacity.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        // v1.7 (item 8): a pass-through folder below 100 % (not isolated, PDF draws it exactly)
        // is written isolated: SVG group opacity always isolates (the export summary says so).
        if (layer.isolated || opacity < 1f) {
            if (opacity < 1f) style.append("opacity:").append(num(opacity)).append(';')
            blendCss(layer.blend)?.let { style.append("mix-blend-mode:").append(it).append(';') }
            style.append("isolation:isolate")
        }
        // v1.7 (item 8): a pass-through folder at 100 % is a plain group (no opacity, blend mode
        // or isolation: its layers blend with what is below it, as on the canvas).
        if (layer.hidden) style.append(if (style.isEmpty()) "display:none" else ";display:none")
        text("<g id=\"${esc(layer.key)}\" inkscape:groupmode=\"layer\" inkscape:label=\"${esc(layer.name)}\"")
        if (style.isNotEmpty()) text(" style=\"$style\"")
        if (mask != null) text(" mask=\"url(#${esc(mask.key)})\"")
        text(">\n")
        for (item in layer.items) {
            coroutineContext.ensureActive()
            when (item) {
                is SceneItem.Image -> { image(item.image, item.image.key); text("\n") }
                is SceneItem.Shape -> shape(item)
                is SceneItem.Text -> text(item)
            }
        }
        // v1.7 (item 8): a folder's layers, nested (Inkscape sublayers).
        for (child in layer.children) {
            coroutineContext.ensureActive()
            layer(child)
        }
        text("</g>\n")
    }

    private suspend fun image(img: SceneImage, id: String?) {
        if (img.width <= 0 || img.height <= 0) return
        text("<image")
        if (id != null) text(" id=\"${esc(id)}\"")
        text(" x=\"${img.left}\" y=\"${img.top}\" width=\"${img.width}\" height=\"${img.height}\" preserveAspectRatio=\"none\" xlink:href=\"data:image/png;base64,")
        png(img)
        text("\"/>")
    }

    /** The picture's PNG as base64, streamed. */
    private suspend fun png(img: SceneImage) {
        val pixels = img.source.load()
        try {
            out.flush()
            val b64 = Base64.getEncoder().wrap(NonClosing(out))
            PngEncoder.encode(pixels, b64, gray = img.gray, cancelled = cancelled)
            // Closing the encoder writes the padding (the underlying stream stays open).
            b64.close()
        } finally {
            imagesDone++
            onProgress((imagesDone.toFloat() / max(1, scene.treeImageCount)).coerceIn(0f, 1f))
        }
    }

    private fun shape(s: SceneItem.Shape) {
        val d = pathData(s.path) ?: return
        val opacity = s.opacity.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        if (opacity <= 0f) return
        val attrs = StringBuilder()
        when (val f = s.fill) {
            null -> attrs.append(" fill=\"none\"")
            is VPaint.Solid -> {
                attrs.append(" fill=\"").append(color(f.color)).append('"')
                val a = (f.color ushr 24) / 255f
                if (a < 1f) attrs.append(" fill-opacity=\"").append(num(a)).append('"')
            }
            is VPaint.Linear, is VPaint.Radial -> {
                val id = gradient(f) ?: return
                attrs.append(" fill=\"url(#").append(id).append(")\"")
            }
        }
        if (s.fill != null && s.evenOdd) attrs.append(" fill-rule=\"evenodd\"")
        s.stroke?.takeIf { it.width > 0f && it.width.isFinite() }?.let { st ->
            attrs.append(" stroke=\"").append(color(st.color)).append('"')
            val a = (st.color ushr 24) / 255f
            if (a < 1f) attrs.append(" stroke-opacity=\"").append(num(a)).append('"')
            attrs.append(" stroke-width=\"").append(num(st.width)).append('"')
            attrs.append(" stroke-linecap=\"").append(capCss(st.cap)).append('"')
            attrs.append(" stroke-linejoin=\"").append(joinCss(st.join)).append('"')
            if (st.join == JoinStyle.MITER) attrs.append(" stroke-miterlimit=\"").append(num(max(1f, st.miter))).append('"')
        }
        if (opacity < 1f) attrs.append(" opacity=\"").append(num(opacity)).append('"')
        text("<path d=\"$d\"$attrs/>\n")
    }

    /** Writes a gradient definition; its id (null without stops). */
    private fun gradient(p: VPaint): String? {
        val stops = when (p) {
            is VPaint.Linear -> p.stops
            is VPaint.Radial -> p.stops
            is VPaint.Solid -> return null
        }
        if (stops.isEmpty()) return null
        val id = "grad-${++gradients}"
        val sb = StringBuilder("<defs>")
        when (p) {
            is VPaint.Linear -> sb.append("<linearGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" x1=\"${num(p.x0)}\" y1=\"${num(p.y0)}\" x2=\"${num(p.x1)}\" y2=\"${num(p.y1)}\">")
            is VPaint.Radial -> {
                sb.append("<radialGradient id=\"$id\" gradientUnits=\"userSpaceOnUse\" cx=\"${num(p.cx)}\" cy=\"${num(p.cy)}\" r=\"${num(max(0f, p.r))}\"")
                p.matrix?.takeIf { it.size >= 6 && it.all { v -> v.isFinite() } }?.let { m ->
                    sb.append(" gradientTransform=\"matrix(").append(m.take(6).joinToString(" ") { num(it) }).append(")\"")
                }
                sb.append('>')
            }
            is VPaint.Solid -> {}
        }
        for (st in sortedStops(stops)) {
            sb.append("<stop offset=\"").append(num(st.offset)).append("\" stop-color=\"").append(color(st.color)).append('"')
            val a = (st.color ushr 24) / 255f
            if (a < 1f) sb.append(" stop-opacity=\"").append(num(a)).append('"')
            sb.append("/>")
        }
        sb.append(if (p is VPaint.Linear) "</linearGradient>" else "</radialGradient>").append("</defs>\n")
        text(sb.toString())
        return id
    }

    private fun text(t: SceneItem.Text) {
        if (t.lines.isEmpty()) return
        val s = t.style
        val sb = StringBuilder("<text xml:space=\"preserve\"")
        if (t.matrix.size >= 6) sb.append(" transform=\"matrix(").append(t.matrix.take(6).joinToString(" ") { num(it) }).append(")\"")
        sb.append(" font-family=\"").append(esc(s.family)).append("\" font-size=\"").append(num(s.sizePx)).append('"')
        if (s.bold) sb.append(" font-weight=\"bold\"")
        if (s.italic) sb.append(" font-style=\"italic\"")
        sb.append(" fill=\"").append(color(s.color)).append('"')
        val a = (s.color ushr 24) / 255f
        if (a < 1f) sb.append(" fill-opacity=\"").append(num(a)).append('"')
        if (s.strokeWidth > 0f) {
            sb.append(" stroke=\"").append(color(s.strokeColor)).append("\" stroke-width=\"").append(num(s.strokeWidth))
                .append("\" stroke-linejoin=\"round\" paint-order=\"stroke\"")
        }
        if (s.letterSpacing != 0f) sb.append(" letter-spacing=\"").append(num(s.letterSpacing)).append('"')
        sb.append('>')
        for (line in t.lines) {
            sb.append("<tspan x=\"").append(num(line.x)).append("\" y=\"").append(num(line.baseline)).append("\">").append(esc(line.text)).append("</tspan>")
        }
        sb.append("</text>\n")
        text(sb.toString())
    }

    private fun text(s: String) = out.write(s.toByteArray(Charsets.UTF_8))

    /** Keeps [out] open when a wrapper is closed. */
    private class NonClosing(out: OutputStream) : FilterOutputStream(out) {
        override fun write(b: ByteArray, off: Int, len: Int) = out.write(b, off, len)
        override fun close() = flush()
    }

    companion object {
        /** Largest coordinate written (geometry far off the canvas is clamped). */
        const val MAX_COORD = 1e6

        /** [v] with at most 3 decimals, no exponent, trailing zeros dropped; clamped to ±[MAX_COORD]. */
        fun num(v: Float): String = num(v.toDouble())

        fun num(v: Double): String {
            if (!v.isFinite()) return "0"
            val c = v.coerceIn(-MAX_COORD, MAX_COORD)
            val scaled = (c * 1000.0).roundToLong()
            if (scaled == 0L) return "0"
            val a = abs(scaled)
            val sb = StringBuilder()
            if (scaled < 0) sb.append('-')
            sb.append(a / 1000)
            var frac = a % 1000
            if (frac != 0L) {
                var digits = 3
                while (frac % 10 == 0L) { frac /= 10; digits-- }
                sb.append('.')
                val f = frac.toString()
                repeat(digits - f.length) { sb.append('0') }
                sb.append(f)
            }
            return sb.toString()
        }

        /** SVG path data of [p] (absolute commands), null when it has no segment. */
        fun pathData(p: VectorPath): String? {
            if (p.isEmpty) return null
            val sb = StringBuilder()
            for (op in p.ops) {
                if (sb.isNotEmpty()) sb.append(' ')
                when (op) {
                    is PathOp.MoveTo -> sb.append('M').append(num(op.p.x)).append(' ').append(num(op.p.y))
                    is PathOp.LineTo -> sb.append('L').append(num(op.p.x)).append(' ').append(num(op.p.y))
                    is PathOp.CubicTo -> sb.append('C').append(num(op.c1.x)).append(' ').append(num(op.c1.y)).append(' ')
                        .append(num(op.c2.x)).append(' ').append(num(op.c2.y)).append(' ').append(num(op.p.x)).append(' ').append(num(op.p.y))
                    PathOp.Close -> sb.append('Z')
                }
            }
            return sb.toString()
        }

        /** `#rrggbb` of an ARGB color (alpha is written separately). */
        fun color(c: Int): String = String.format(java.util.Locale.ROOT, "#%06x", c and 0xFFFFFF)

        private fun gray(g: Int): String = color((0xFF shl 24) or ((g and 0xFF) * 0x010101))

        /** XML-escaped text / attribute value (characters XML forbids are dropped). */
        fun esc(s: String): String {
            val sb = StringBuilder(s.length + 8)
            for (ch in s) {
                when {
                    ch == '&' -> sb.append("&amp;")
                    ch == '<' -> sb.append("&lt;")
                    ch == '>' -> sb.append("&gt;")
                    ch == '"' -> sb.append("&quot;")
                    ch == '\'' -> sb.append("&apos;")
                    ch == '\n' || ch == '\t' -> sb.append(ch)
                    ch.code < 0x20 || ch == '￾' || ch == '￿' -> {}
                    else -> sb.append(ch)
                }
            }
            return sb.toString()
        }

        /** CSS `mix-blend-mode` of [m] (null for normal). */
        fun blendCss(m: LayerBlendMode): String? = when (m) {
            LayerBlendMode.NORMAL -> null
            LayerBlendMode.MULTIPLY -> "multiply"
            LayerBlendMode.SCREEN -> "screen"
            LayerBlendMode.OVERLAY -> "overlay"
            LayerBlendMode.DARKEN -> "darken"
            LayerBlendMode.LIGHTEN -> "lighten"
            LayerBlendMode.COLOR_DODGE -> "color-dodge"
            LayerBlendMode.COLOR_BURN -> "color-burn"
            LayerBlendMode.HARD_LIGHT -> "hard-light"
            LayerBlendMode.SOFT_LIGHT -> "soft-light"
            LayerBlendMode.DIFFERENCE -> "difference"
            LayerBlendMode.EXCLUSION -> "exclusion"
            LayerBlendMode.HUE -> "hue"
            LayerBlendMode.SATURATION -> "saturation"
            LayerBlendMode.COLOR -> "color"
            LayerBlendMode.LUMINOSITY -> "luminosity"
            LayerBlendMode.ADD -> "plus-lighter"
        }

        fun capCss(c: LineCapStyle): String = when (c) { LineCapStyle.BUTT -> "butt"; LineCapStyle.ROUND -> "round"; LineCapStyle.SQUARE -> "square" }

        fun joinCss(j: JoinStyle): String = when (j) { JoinStyle.MITER -> "miter"; JoinStyle.ROUND -> "round"; JoinStyle.BEVEL -> "bevel" }

        private fun sortedStops(stops: List<VStop>): List<VStop> =
            stops.map { it.copy(offset = if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f) }.sortedBy { it.offset }
    }
}
