package com.brushwork.paint.ui.gallery

import com.brushwork.paint.core.LengthUnit
import com.brushwork.paint.core.Units
import java.util.Locale
import kotlin.math.abs
import kotlin.math.max
import kotlin.math.roundToInt
import kotlin.math.roundToLong

/** A canvas size given directly in pixels ("Screen & illustration" presets). */
data class PixelPreset(val width: Int, val height: Int) {
    val aspect: String get() = CanvasPresets.aspectLabel(width, height)
}

/** A paper size in physical units, stored in portrait orientation. */
data class PaperPreset(val name: String, val width: Double, val height: Double, val unit: LengthUnit) {
    /** Pixel size at [dpi], optionally rotated to landscape. */
    fun pixels(dpi: Double, landscape: Boolean = false): Pair<Int, Int> {
        val w = CanvasPresets.toPixels(width, unit, dpi)
        val h = CanvasPresets.toPixels(height, unit, dpi)
        return if (landscape) h to w else w to h
    }

    /** "210 × 297 mm", "8.5 × 11 in". */
    val sizeLabel: String get() = sizeLabel(landscape = false)

    /** Physical size label, width first ("297 × 210 mm" for landscape A4). */
    fun sizeLabel(landscape: Boolean): String {
        val a = Units.formatNumber(if (landscape) height else width, 2)
        val b = Units.formatNumber(if (landscape) width else height, 2)
        return "$a × $b ${unit.short}"
    }
}

/** Result of validating a canvas size. [message] explains why it can't be created. */
data class CanvasCheck(val ok: Boolean, val message: String? = null)

/**
 * Size being chosen in the new canvas dialog. Pixel sizes are kept as doubles so switching
 * units or resolutions doesn't accumulate rounding; [width]/[height] are the final pixels.
 * [paperName] remembers the paper preset the size came from while the size still matches it.
 * Serializable so the dialog can keep it in `rememberSaveable`.
 */
data class CanvasSize(
    val widthPx: Double = 2048.0,
    val heightPx: Double = 2048.0,
    val dpi: Double = CanvasPresets.DEFAULT_DPI.toDouble(),
    val landscape: Boolean = false,
    val paperName: String? = null,
) : java.io.Serializable {
    val width: Int get() = CanvasPresets.pixelsOf(widthPx)
    val height: Int get() = CanvasPresets.pixelsOf(heightPx)

    /** The paper preset the size came from, if the size still matches it. */
    val paper: PaperPreset?
        get() = CanvasPresets.paper.firstOrNull { it.name == paperName }?.takeIf { it.pixels(dpi, landscape) == (width to height) }

    /** A size typed by the user (forgets the paper preset). */
    fun withPixels(widthPx: Double, heightPx: Double) = copy(widthPx = widthPx, heightPx = heightPx, paperName = null)

    fun withPreset(p: PixelPreset) =
        copy(widthPx = p.width.toDouble(), heightPx = p.height.toDouble(), landscape = p.width > p.height, paperName = null)

    fun withPaper(p: PaperPreset): CanvasSize {
        val (w, h) = p.pixels(dpi, landscape)
        return copy(widthPx = w.toDouble(), heightPx = h.toDouble(), paperName = p.name)
    }

    /**
     * Changes the resolution (rounded to 0.1, clamped to 1..2400). A paper size keeps its
     * physical size; otherwise [keepPhysical] (print / physical units) scales the pixels, and a
     * pure pixel size stays as it is.
     */
    fun withDpi(value: Double, keepPhysical: Boolean): CanvasSize {
        val d = (Math.round(value * 10) / 10.0).coerceIn(CanvasPresets.MIN_DPI, CanvasPresets.MAX_DPI)
        if (d == dpi) return this
        val p = paper
        return when {
            p != null -> copy(dpi = d).withPaper(p)
            keepPhysical -> copy(widthPx = widthPx * d / dpi, heightPx = heightPx * d / dpi, dpi = d)
            else -> copy(dpi = d)
        }
    }

    /** Portrait / landscape: re-lays a paper preset, else swaps the sides when they disagree. */
    fun withOrientation(toLandscape: Boolean): CanvasSize {
        if (toLandscape == landscape) return this
        val p = paper
        val turned = copy(landscape = toLandscape)
        return when {
            p != null -> turned.withPaper(p)
            widthPx != heightPx && (widthPx > heightPx) != toLandscape -> turned.copy(widthPx = heightPx, heightPx = widthPx)
            else -> turned
        }
    }

    /** Swaps width and height (a paper preset stays selected in the other orientation). */
    fun swapped(): CanvasSize {
        val p = paper
        val s = copy(widthPx = heightPx, heightPx = widthPx, landscape = heightPx > widthPx)
        return if (p == null) s.copy(paperName = null) else s
    }
}

/**
 * New-canvas presets and the size / memory math behind the new canvas dialog.
 * Pure Kotlin (no Android types) so it is unit-tested on the JVM.
 */
object CanvasPresets {
    /** Longest allowed canvas side in pixels. */
    const val MAX_SIDE = 10_000
    /** A canvas must leave room for at least this many layers. */
    const val MIN_LAYERS = 3
    const val DEFAULT_DPI = 350
    const val MIN_DPI = 1.0
    const val MAX_DPI = 2400.0
    val dpiChoices: List<Int> = listOf(72, 150, 300, 350, 600)

    /** Whole pixels of a (possibly fractional) size, clamped to a sane range. */
    fun pixelsOf(v: Double): Int = if (v.isNaN()) 0 else v.roundToLong().coerceIn(0L, 1_000_000L).toInt()

    val screen: List<PixelPreset> = listOf(
        PixelPreset(500, 500),
        PixelPreset(1080, 2408),
        PixelPreset(2048, 2048),
        PixelPreset(1536, 2048),
        PixelPreset(1080, 1920),
        PixelPreset(1920, 1080),
        PixelPreset(2048, 1536),
        PixelPreset(3000, 3000),
        PixelPreset(1200, 1800),
    )

    val paper: List<PaperPreset> = listOf(
        PaperPreset("A3", 297.0, 420.0, LengthUnit.MM),
        PaperPreset("A4", 210.0, 297.0, LengthUnit.MM),
        PaperPreset("A5", 148.0, 210.0, LengthUnit.MM),
        PaperPreset("A6", 105.0, 148.0, LengthUnit.MM),
        PaperPreset("B4 (JIS)", 257.0, 364.0, LengthUnit.MM),
        PaperPreset("B5 (JIS)", 182.0, 257.0, LengthUnit.MM),
        PaperPreset("US Letter", 8.5, 11.0, LengthUnit.IN),
        PaperPreset("US Legal", 8.5, 14.0, LengthUnit.IN),
        PaperPreset("Tabloid", 11.0, 17.0, LengthUnit.IN),
        PaperPreset("Postcard", 100.0, 148.0, LengthUnit.MM),
        PaperPreset("Manga manuscript (B4)", 257.0, 364.0, LengthUnit.MM),
    )

    /** Converts a physical length to whole pixels (at least 1). */
    fun toPixels(value: Double, unit: LengthUnit, dpi: Double): Int =
        max(1L, unit.toPx(value, dpi).roundToLong()).coerceAtMost(Int.MAX_VALUE.toLong()).toInt()

    /** Bytes of one ARGB_8888 layer of this size. */
    fun layerBytes(width: Int, height: Int): Long = width.toLong() * height * 4

    /**
     * Layers that fit in the heap, using the same formula as `EditorController.maxLayers`
     * (55% of the heap, minus 3 for undo/composite buffers), NOT clamped.
     */
    fun rawMaxLayers(width: Int, height: Int, maxHeapBytes: Long): Long {
        val per = max(1L, layerBytes(width, height))
        return (maxHeapBytes * 0.55).toLong() / per - 3
    }

    /** Layer limit shown to the user (clamped to 2..100 like the editor). */
    fun maxLayers(width: Int, height: Int, maxHeapBytes: Long): Int =
        rawMaxLayers(width, height, maxHeapBytes).coerceIn(2L, 100L).toInt()

    /** Checks that a canvas can be created on a device with [maxHeapBytes] of heap. */
    fun check(width: Int, height: Int, maxHeapBytes: Long): CanvasCheck = when {
        width < 1 || height < 1 -> CanvasCheck(false, "Width and height must be at least 1 px")
        width > MAX_SIDE || height > MAX_SIDE -> CanvasCheck(false, "Each side can be at most ${"%,d".format(Locale.US, MAX_SIDE)} px")
        rawMaxLayers(width, height, maxHeapBytes) < MIN_LAYERS ->
            CanvasCheck(false, "Too large for this device's memory (fewer than $MIN_LAYERS layers would fit)")
        else -> CanvasCheck(true)
    }

    /**
     * Friendly aspect ratio: exact ("16:9") when the reduced terms are small, else the simplest
     * fraction within 0.5% ("≈9:20"), else a decimal ("1:2.37").
     */
    fun aspectLabel(width: Int, height: Int): String {
        if (width <= 0 || height <= 0) return ""
        val g = gcd(width, height)
        val a = width / g
        val b = height / g
        if (a <= 32 && b <= 32) return "$a:$b"
        val r = width.toDouble() / height
        for (sum in 2..48) {
            for (n in 1 until sum) {
                val d = sum - n
                if (abs(n.toDouble() / d / r - 1.0) <= 0.005) return "≈$n:$d"
            }
        }
        return if (r >= 1.0) "${Units.formatNumber(r, 2)}:1" else "1:${Units.formatNumber(1.0 / r, 2)}"
    }

    /** "512 KB", "16 MB", "33.2 MB", "1.25 GB" (binary units). */
    fun formatBytes(bytes: Long): String {
        val kb = 1024.0
        val mb = kb * 1024
        val gb = mb * 1024
        return when {
            bytes >= gb -> "${Units.formatNumber(bytes / gb, 2)} GB"
            bytes >= mb -> "${Units.formatNumber(bytes / mb, 1)} MB"
            else -> "${max(1L, (bytes / kb).roundToInt().toLong())} KB"
        }
    }

    /** Unit used to show physical sizes when the user works in pixels. */
    fun defaultPhysicalUnit(locale: Locale = Locale.getDefault()): LengthUnit =
        if (locale.country in setOf("US", "LR", "MM")) LengthUnit.IN else LengthUnit.CM

    /** "21 × 29.7 cm" for a pixel size at [dpi]. */
    fun physicalLabel(width: Int, height: Int, dpi: Double, unit: LengthUnit): String {
        val u = if (unit == LengthUnit.PX) defaultPhysicalUnit() else unit
        val w = Units.formatNumber(u.fromPx(width.toDouble(), dpi), u.decimals)
        val h = Units.formatNumber(u.fromPx(height.toDouble(), dpi), u.decimals)
        return "$w × $h ${u.short}"
    }

    private tailrec fun gcd(a: Int, b: Int): Int = if (b == 0) a else gcd(b, a % b)
}
