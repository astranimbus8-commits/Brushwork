package com.brushwork.paint.exchange.export

import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VPaint

/*
 * What an SVG or PDF export writes (v1.5 §4.10), independent of the format and of Android: the
 * [ExportSceneBuilder] turns the document into an [ExportScene] on the main thread (cheap:
 * references to immutable data and lazy pixel sources), the writers stream it on IO, loading one
 * picture at a time ([ImageSource]) so memory stays about one cropped layer.
 */

/** How brush strokes of vector layers are exported. */
enum class StrokeExport(val label: String) {
    /** Solid brushes as filled outlines, textured ones as pictures. */
    OUTLINES("Outlines where possible"),

    /** Every stroke as a picture (the exact look). */
    PICTURES("Pictures (exact look)"),
}

/** How text layers are exported. */
enum class TextExportMode(val label: String) {
    /** Real text (SVG) where the text and font allow, else outlines. */
    EDITABLE("Editable where possible"),

    /** Glyph outlines. */
    OUTLINES("Outlines"),
}

/** Page of a PDF export. */
enum class PdfPage(val label: String, val widthPt: Float, val heightPt: Float) {
    /** The canvas at the document's DPI. */
    CANVAS("Canvas size", 0f, 0f),
    A4("A4", 595.2756f, 841.8898f),
    LETTER("Letter", 612f, 792f),
}

/** The options of the export sheet. */
data class ExportOptions(
    val format: VectorFormat,
    val strokes: StrokeExport = StrokeExport.OUTLINES,
    val text: TextExportMode = TextExportMode.EDITABLE,
    val includeHidden: Boolean = false,
    val whiteBackground: Boolean = false,
    val includePayload: Boolean = true,
    val page: PdfPage = PdfPage.CANVAS,
)

/** Loads the pixels of a picture when a writer reaches it (on the writer's thread; may switch threads). */
fun interface ImageSource {
    suspend fun load(): ArgbImage
}

/**
 * The document as an export: [layers] bottom first (only those written), an optional opaque
 * [background] under everything, and the [payload] with the pictures only it uses.
 */
class ExportScene(
    val width: Int,
    val height: Int,
    val dpi: Float,
    val title: String,
    val background: Int?,
    val layers: List<SceneLayer>,
    val payload: BrushworkPayload? = null,
    val payloadImages: List<SceneImage> = emptyList(),
    /** Notes for the user about approximations made (shown after the export). */
    val notes: List<String> = emptyList(),
) {
    /** Every picture of the scene (layers, masks, payload-only), for counting progress. */
    val imageCount: Int get() = payloadImages.size + layers.sumOf { l -> (if (l.mask?.image != null) 1 else 0) + l.items.count { it is SceneItem.Image } }
}

/**
 * One exported layer: a group with [opacity], [blend] and an optional luminance [mask]; [hidden]
 * layers are written invisible (SVG `display="none"`, PDF optional content off).
 */
class SceneLayer(
    /** Unique id in the file ("layer-12"). */
    val key: String,
    val name: String,
    val opacity: Float,
    val blend: LayerBlendMode,
    val hidden: Boolean,
    val mask: SceneMask?,
    val items: List<SceneItem>,
    /**
     * v1.7 (item 8): a folder's layers, bottom first, drawn as a group inside this one (above
     * [items], which a folder does not have); empty for every other layer.
     */
    val children: List<SceneLayer> = emptyList(),
    /**
     * v1.7 (item 8): a folder whose children are composited on their own and then drawn with
     * [blend] and [opacity] (`FolderSpec.passThrough` off). True for every other layer, whose
     * group always was isolated.
     */
    val isolated: Boolean = true,
)

/**
 * A layer mask: opaque gray [fillGray] (0..255) everywhere, [image] (gray pixels) over its rect.
 * Luminance: white shows the layer, black hides it.
 */
class SceneMask(val key: String, val fillGray: Int, val image: SceneImage?)

/** A picture placed at its rect in document px (1 image px = 1 document px). */
class SceneImage(
    val key: String,
    val left: Int,
    val top: Int,
    val width: Int,
    val height: Int,
    /** Gray (mask) pictures are stored as one luminance channel. */
    val gray: Boolean,
    val source: ImageSource,
)

/** A plain line along a path. */
data class SceneStroke(
    /** ARGB, non-premultiplied (alpha = stroke opacity). */
    val color: Int,
    val width: Float,
    val cap: LineCapStyle = LineCapStyle.ROUND,
    val join: JoinStyle = JoinStyle.ROUND,
    val miter: Float = 4f,
)

/** How a text run is styled (SVG `<text>`). */
data class SceneTextStyle(
    /** Generic CSS family (sans-serif, serif, monospace, cursive...). */
    val family: String,
    val sizePx: Float,
    val bold: Boolean,
    val italic: Boolean,
    val color: Int,
    /** Outline drawn behind the fill (0 = none), full stroke width. */
    val strokeWidth: Float = 0f,
    val strokeColor: Int = 0,
    /** Letter spacing in px. */
    val letterSpacing: Float = 0f,
)

/** One line of text with its left end at ([x], [baseline]) in the text's local coordinates. */
data class SceneTextLine(val text: String, val x: Float, val baseline: Float)

/** Something drawn inside a layer, in z-order. */
sealed class SceneItem {
    /** Pixels. */
    class Image(val image: SceneImage) : SceneItem()

    /** A filled and / or stroked path (document px); [opacity] fades fill and line together. */
    class Shape(
        val path: VectorPath,
        val evenOdd: Boolean = false,
        val fill: VPaint? = null,
        val stroke: SceneStroke? = null,
        val opacity: Float = 1f,
    ) : SceneItem()

    /** Real text (SVG only): [lines] in local coordinates mapped to the document by [matrix] (a, b, c, d, e, f). */
    class Text(val lines: List<SceneTextLine>, val style: SceneTextStyle, val matrix: List<Float>) : SceneItem()
}
