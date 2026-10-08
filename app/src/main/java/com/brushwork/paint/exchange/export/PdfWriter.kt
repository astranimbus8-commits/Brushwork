package com.brushwork.paint.exchange.export

import com.brushwork.paint.exchange.image.ArgbImage
import com.brushwork.paint.exchange.image.FilteredZlib
import com.brushwork.paint.exchange.image.RowLayout
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.vector.JoinStyle
import com.brushwork.paint.tools.vector.LineCapStyle
import com.brushwork.paint.tools.vector.PathOp
import com.brushwork.paint.tools.vector.VectorPath
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.Job
import kotlinx.coroutines.ensureActive
import java.io.ByteArrayOutputStream
import java.io.OutputStream
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import java.util.UUID
import java.util.zip.Deflater
import java.util.zip.DeflaterOutputStream
import kotlin.coroutines.coroutineContext
import kotlin.math.max
import kotlin.math.min

/**
 * Writes an [ExportScene] as a one-page PDF (v1.5 §4.10), pure Kotlin (V9: the platform's
 * PdfDocument can neither express soft masks or blend modes nor run in unit tests):
 *
 * - the page shows the document through `cm [s 0 0 −s ox top]` (document px, y down), at the
 *   document's DPI or fitted on A4 / Letter;
 * - every layer is a transparency-group Form XObject drawn with an ExtGState (`/ca`, `/BM`; Add is
 *   written as Screen) and, for a mask, a luminosity `/SMask`; every layer is an optional content
 *   group (layers panel in Acrobat), hidden layers start OFF; v1.7: a folder's layers are nested
 *   in its group (a transparency group of them, or drawn straight through for a pass-through
 *   folder), nested in `/Order` too;
 * - pictures are `/FlateDecode` RGB images (PNG predictors) with a DeviceGray `/SMask`;
 * - paths are `m l c h` filled (`f` / `f*`) and stroked (`S`); gradients are axial / radial shadings
 *   with stitched exponential functions (stop opacity through a luminosity soft mask);
 * - the Brushwork payload is an embedded file "brushwork.json" (Catalog `/BrushworkPayload`), and
 *   `/BrushworkImages` names every picture the payload refers to.
 *
 * Pictures are loaded one at a time ([ImageSource]) and streamed. [onProgress] gets 0..1 as
 * pictures are written.
 */
class PdfWriter(private val scene: ExportScene, private val page: PdfPage = PdfPage.CANVAS, private val onProgress: (Float) -> Unit = {}) {

    private lateinit var file: PdfFile
    private val images = LinkedHashMap<String, Int>()
    private var imagesDone = 0

    /** Page size (pt) and the document-to-page transform, fixed for one write. */
    private class Placement(val pageW: Float, val pageH: Float, val scale: Float, val ox: Float, val oy: Float)

    private fun placement(): Placement {
        val w = scene.width.toFloat()
        val h = scene.height.toFloat()
        val dpi = if (scene.dpi.isFinite() && scene.dpi > 0f) scene.dpi else 72f
        if (page == PdfPage.CANVAS) {
            var s = 72f / dpi
            // Readers refuse pages over 200 in (14400 pt): a very large canvas at a low DPI is fitted.
            if (max(w, h) * s > MAX_PAGE_PT) s = MAX_PAGE_PT / max(w, h)
            return Placement(w * s, h * s, s, 0f, 0f)
        }
        val landscape = w > h
        val pw = if (landscape) page.heightPt else page.widthPt
        val ph = if (landscape) page.widthPt else page.heightPt
        val s = min(pw / w, ph / h)
        return Placement(pw, ph, s, (pw - w * s) / 2f, (ph - h * s) / 2f)
    }

    /** Writes the whole file to [out] (not closed). Cancellable between pictures and bands. */
    suspend fun write(out: OutputStream) {
        val job = coroutineContext[Job]
        val cancelled = { job?.isActive == false }
        file = PdfFile(out)
        val catalog = file.reserve()
        val pages = file.reserve()
        val pageNum = file.reserve()
        val place = placement()

        val pageRes = Resources()
        val content = StringBuilder()
        content.append("q ").append(Pdf.num(place.scale)).append(" 0 0 ").append(Pdf.num(-place.scale)).append(' ')
            .append(Pdf.num(place.ox)).append(' ').append(Pdf.num(place.pageH - place.oy)).append(" cm\n")
        scene.background?.let { bg ->
            content.append(rgb(bg)).append(" rg 0 0 ").append(scene.width).append(' ').append(scene.height).append(" re f\n")
        }
        val ocgs = placeAll(scene.layers, pageRes, content, cancelled)
        content.append("Q\n")

        // Pictures only the payload uses (raster layers merged into a flattened picture...).
        for (img in scene.payloadImages) {
            coroutineContext.ensureActive()
            writeImage(img, cancelled)
        }

        val contentNum = file.stream(PdfDict().apply { this["Filter"] = "/FlateDecode" }, Pdf.flate(content.toString().toByteArray(Charsets.ISO_8859_1)))
        file.obj(pageNum, PdfDict().apply {
            this["Type"] = "/Page"
            this["Parent"] = Pdf.ref(pages)
            this["MediaBox"] = "[0 0 ${Pdf.num(place.pageW)} ${Pdf.num(place.pageH)}]"
            this["Resources"] = pageRes.dict()
            this["Contents"] = Pdf.ref(contentNum)
            this["Group"] = "<</S /Transparency /CS /DeviceRGB>>"
        }.toString())
        file.obj(pages, "<</Type /Pages /Kids [${Pdf.ref(pageNum)}] /Count 1>>")

        // The Brushwork payload: an embedded file (also named by the catalog).
        var payloadNum: Int? = null
        var fileSpec: Int? = null
        scene.payload?.let { p ->
            val json = Payload.toJson(p)
            val stream = file.stream(PdfDict().apply {
                this["Type"] = "/EmbeddedFile"
                this["Subtype"] = Pdf.name("application/json")
                this["Filter"] = "/FlateDecode"
                this["Params"] = "<</Size ${json.size}>>"
            }, Pdf.flate(json))
            payloadNum = stream
            fileSpec = file.obj(PdfDict().apply {
                this["Type"] = "/Filespec"
                this["F"] = Pdf.text(Payload.PDF_FILE_NAME)
                this["UF"] = Pdf.text(Payload.PDF_FILE_NAME)
                this["Desc"] = Pdf.text("Brushwork layers")
                this["EF"] = "<</F ${Pdf.ref(stream)}>>"
            }.toString())
        }

        val info = file.obj(PdfDict().apply {
            this["Title"] = Pdf.text(scene.title)
            this["Creator"] = Pdf.text("Brushwork")
            this["Producer"] = Pdf.text("Brushwork")
            this["CreationDate"] = Pdf.text("D:" + SimpleDateFormat("yyyyMMddHHmmss", Locale.ROOT).format(Date()))
        }.toString())

        file.obj(catalog, PdfDict().apply {
            this["Type"] = "/Catalog"
            this["Pages"] = Pdf.ref(pages)
            if (ocgs.isNotEmpty()) {
                val flat = ArrayList<OcNode>()
                fun collect(nodes: List<OcNode>) { for (n in nodes) { flat += n; collect(n.children) } }
                collect(ocgs)
                val all = flat.joinToString(" ", "[", "]") { Pdf.ref(it.num) }
                // Top layer first, as layer panels list them; a folder's layers in an array after it.
                val order = order(ocgs)
                val on = flat.filter { !it.layer.hidden }.joinToString(" ", "[", "]") { Pdf.ref(it.num) }
                val off = flat.filter { it.layer.hidden }.joinToString(" ", "[", "]") { Pdf.ref(it.num) }
                this["OCProperties"] = "<</OCGs $all /D <</Name ${Pdf.text("Layers")} /Order $order /ON $on /OFF $off>>>>"
                this["PageMode"] = "/UseOC"
            }
            val spec = fileSpec
            if (spec != null) {
                this["Names"] = "<</EmbeddedFiles <</Names [${Pdf.text(Payload.PDF_FILE_NAME)} ${Pdf.ref(spec)}]>>>>"
                this["BrushworkPayload"] = Pdf.ref(payloadNum!!)
            }
            if (images.isNotEmpty() && scene.payload != null) {
                this["BrushworkImages"] = images.entries.joinToString(" ", "<<", ">>") { (k, v) -> Pdf.name(k) + " " + Pdf.ref(v) }
            }
        }.toString())
        file.finish(catalog, info, UUID.randomUUID().toString().replace("-", "").uppercase(Locale.ROOT))
    }

    // ------------------------------------------------------------------ layers

    /** The optional content group [num] written for [layer], with its children's groups (bottom first). */
    private class OcNode(val num: Int, val layer: SceneLayer, val children: List<OcNode>)

    /** `/Order` of [nodes]: top first, a folder's layers in an array right after it. */
    private fun order(nodes: List<OcNode>): String = nodes.asReversed().joinToString(" ", "[", "]") { n ->
        if (n.children.isEmpty()) Pdf.ref(n.num) else Pdf.ref(n.num) + " " + order(n.children)
    }

    /** Draws [layers] bottom first into [content] (whose resources are [res]); their groups. */
    private suspend fun placeAll(layers: List<SceneLayer>, res: Resources, content: StringBuilder, cancelled: () -> Boolean): List<OcNode> {
        val out = ArrayList<OcNode>()
        for (layer in layers) {
            coroutineContext.ensureActive()
            place(layer, res, content, cancelled)?.let { out += it }
        }
        return out
    }

    /**
     * Draws [layer] into [content] (whose resources are [res]) inside its optional content group;
     * null (nothing written) when it draws nothing:
     *
     * - a layer: its transparency-group form, with an ExtGState for its opacity, blend mode and mask;
     * - v1.7 (item 8), an isolated folder: one transparency-group form holding its layers (each
     *   inside its own group, nested optional content: shown only while every group around it is
     *   on), drawn like a layer;
     * - a pass-through folder: its layers drawn straight into [content], so they blend with what
     *   is below the folder (its blend mode and mask are not used: it has none);
     * - a pass-through folder below 100 %: those layers in a NON-isolated transparency-group
     *   form drawn at the folder's opacity, which is exactly the canvas's o·C + (1 − o)·B (they
     *   still blend with what is below the folder).
     */
    private suspend fun place(layer: SceneLayer, res: Resources, content: StringBuilder, cancelled: () -> Boolean): OcNode? {
        if (layer.children.isEmpty()) {
            val form = writeLayerForm(layer, cancelled) ?: return null
            val ocg = ocg(layer)
            draw(layer, form, ocg, res, content, cancelled)
            return OcNode(ocg, layer, emptyList())
        }
        // A group of its own: isolated, or a pass-through folder below 100 % (non-isolated).
        val grouped = layer.isolated || layer.opacity < 1f
        val innerRes = if (grouped) Resources() else res
        val inner = StringBuilder()
        // Items under the layers (a folder has none).
        writeLayerForm(layer, cancelled)?.let { inner.append("q ").append(innerRes.xobject(it)).append(" Do Q\n") }
        val children = placeAll(layer.children, innerRes, inner, cancelled)
        if (inner.isEmpty()) return null
        if (!grouped) {
            val ocg = ocg(layer)
            content.append("/OC ").append(res.property(ocg)).append(" BDC\n").append(inner).append("EMC\n")
            return OcNode(ocg, layer, children)
        }
        val form = form(inner, innerRes, gray = false, isolated = layer.isolated)
        val ocg = ocg(layer)
        draw(layer, form, ocg, res, content, cancelled)
        return OcNode(ocg, layer, children)
    }

    /** A new optional content group named like [layer]. */
    private fun ocg(layer: SceneLayer): Int = file.obj(PdfDict().apply {
        this["Type"] = "/OCG"
        this["Name"] = Pdf.text(layer.name)
    }.toString())

    /** `/OC … BDC q gs form Do Q EMC`: [form] drawn with [layer]'s opacity, blend mode and mask. */
    private suspend fun draw(layer: SceneLayer, form: Int, ocg: Int, res: Resources, content: StringBuilder, cancelled: () -> Boolean) {
        val gs = PdfDict().apply {
            this["Type"] = "/ExtGState"
            val a = Pdf.num(layer.opacity.coerceIn(0f, 1f))
            this["ca"] = a
            this["CA"] = a
            this["BM"] = blendName(layer.blend)
        }
        layer.mask?.let { m -> gs["SMask"] = PdfDict().apply {
            this["Type"] = "/Mask"
            this["S"] = "/Luminosity"
            this["G"] = Pdf.ref(writeMaskForm(m, cancelled))
        } }
        val gsName = res.gs(gs.toString())
        val formName = res.xobject(form)
        val ocName = res.property(ocg)
        content.append("/OC ").append(ocName).append(" BDC q ").append(gsName).append(" gs ").append(formName).append(" Do Q EMC\n")
    }

    /** The layer's content as a transparency-group form (null when it draws nothing). */
    private suspend fun writeLayerForm(layer: SceneLayer, cancelled: () -> Boolean): Int? {
        if (layer.items.isEmpty()) return null
        val res = Resources()
        val c = StringBuilder()
        // Thousands of outlined strokes make tens of MB of operators: they are compressed as
        // they come instead of being held as text (and again as bytes) until the end.
        val packed = PackedContent()
        try {
            for (item in layer.items) {
                coroutineContext.ensureActive()
                when (item) {
                    is SceneItem.Image -> {
                        val num = writeImage(item.image, cancelled) ?: continue
                        val im = item.image
                        c.append("q ").append(im.width).append(" 0 0 ").append(-im.height).append(' ').append(im.left).append(' ')
                            .append(im.top + im.height).append(" cm ").append(res.xobject(num)).append(" Do Q\n")
                    }
                    is SceneItem.Shape -> shape(item, res, c)
                    // Text is always written as outlines in PDF (the scene builder converts it).
                    is SceneItem.Text -> {}
                }
                if (c.length >= PackedContent.SPILL_CHARS) packed.add(c)
            }
            packed.add(c)
            if (packed.isEmpty) return null
            return formOf(packed.finish(), res, gray = false)
        } finally {
            packed.release()
        }
    }

    /**
     * A transparency-group form of [content] over the whole document; [isolated] false (v1.7: a
     * pass-through folder below 100 %) makes it a non-isolated group, composited onto what is
     * below it.
     */
    private fun form(content: CharSequence, res: Resources, gray: Boolean, isolated: Boolean = true): Int =
        formOf(Pdf.flate(content.toString().toByteArray(Charsets.ISO_8859_1)), res, gray, isolated)

    /** A transparency-group form of the zlib-compressed content [deflated] (see [form]). */
    private fun formOf(deflated: ByteArray, res: Resources, gray: Boolean, isolated: Boolean = true): Int = file.stream(PdfDict().apply {
        this["Type"] = "/XObject"
        this["Subtype"] = "/Form"
        this["BBox"] = "[0 0 ${scene.width} ${scene.height}]"
        this["Group"] = when {
            gray -> "<</S /Transparency /CS /DeviceGray>>"
            isolated -> "<</S /Transparency /CS /DeviceRGB /I true /K false>>"
            else -> "<</S /Transparency /CS /DeviceRGB /I false /K false>>"
        }
        this["Resources"] = res.dict()
        this["Filter"] = "/FlateDecode"
    }, deflated)

    /** A content stream compressed while it is written (text moved in with [add]). */
    private class PackedContent {
        private val bytes = ByteArrayOutputStream()
        private val deflater = Deflater(6)
        private val out = DeflaterOutputStream(bytes, deflater, 64 * 1024)
        private var written = 0L

        val isEmpty: Boolean get() = written == 0L

        /** Moves [text] (operators, Latin-1) into the stream and clears it. */
        fun add(text: StringBuilder) {
            if (text.isEmpty()) return
            out.write(text.toString().toByteArray(Charsets.ISO_8859_1))
            written += text.length
            text.setLength(0)
        }

        fun finish(): ByteArray {
            out.finish()
            return bytes.toByteArray()
        }

        fun release() = deflater.end()

        companion object {
            const val SPILL_CHARS = 256 * 1024
        }
    }

    /** A luminosity group for [m]: its fill gray over the document, then its picture. */
    private suspend fun writeMaskForm(m: SceneMask, cancelled: () -> Boolean): Int {
        val res = Resources()
        val c = StringBuilder()
        c.append(Pdf.channel(m.fillGray)).append(" g 0 0 ").append(scene.width).append(' ').append(scene.height).append(" re f\n")
        m.image?.let { im ->
            val num = writeImage(im, cancelled)
            if (num != null) {
                c.append("q ").append(im.width).append(" 0 0 ").append(-im.height).append(' ').append(im.left).append(' ')
                    .append(im.top + im.height).append(" cm ").append(res.xobject(num)).append(" Do Q\n")
            }
        }
        return form(c, res, gray = true)
    }

    // ------------------------------------------------------------------ pictures

    /** Writes [img] once (RGB + alpha soft mask, or gray); its object number (null when empty). */
    private suspend fun writeImage(img: SceneImage, cancelled: () -> Boolean): Int? {
        images[img.key]?.let { return it }
        if (img.width <= 0 || img.height <= 0) return null
        val pixels = img.source.load()
        try {
            val w = pixels.width
            val h = pixels.height
            if (w <= 0 || h <= 0) return null
            val num = file.reserve()
            var smask: Int? = null
            if (!img.gray && !pixels.isOpaque()) {
                smask = file.reserve()
                streamImage(smask, pixels, RowLayout.ALPHA, "/DeviceGray", null, cancelled)
            }
            streamImage(num, pixels, if (img.gray) RowLayout.GRAY else RowLayout.RGB, if (img.gray) "/DeviceGray" else "/DeviceRGB", smask, cancelled)
            images[img.key] = num
            return num
        } finally {
            imagesDone++
            onProgress((imagesDone.toFloat() / max(1, scene.treeImageCount)).coerceIn(0f, 1f))
        }
    }

    private fun streamImage(num: Int, img: ArgbImage, layout: RowLayout, colorSpace: String, smask: Int?, cancelled: () -> Boolean) {
        val colors = layout.bytesPerPixel
        val dict = PdfDict().apply {
            this["Type"] = "/XObject"
            this["Subtype"] = "/Image"
            this["Width"] = img.width.toString()
            this["Height"] = img.height.toString()
            this["ColorSpace"] = colorSpace
            this["BitsPerComponent"] = "8"
            this["Filter"] = "/FlateDecode"
            this["DecodeParms"] = "<</Predictor 15 /Colors $colors /BitsPerComponent 8 /Columns ${img.width}>>"
            if (smask != null) this["SMask"] = Pdf.ref(smask)
        }
        file.streaming(num, dict) { out ->
            FilteredZlib.write(img, layout, 6, { b, o, n -> out.write(b, o, n) }, cancelled)
        }
    }

    // ------------------------------------------------------------------ paths

    private fun shape(s: SceneItem.Shape, res: Resources, c: StringBuilder) {
        val ops = pathOps(s.path) ?: return
        val opacity = s.opacity.let { if (it.isFinite()) it.coerceIn(0f, 1f) else 1f }
        if (opacity <= 0f) return
        val fill = s.fill
        val stroke = s.stroke?.takeIf { it.width > 0f && it.width.isFinite() }
        if (fill == null && stroke == null) return
        if (opacity < 1f && fill != null && stroke != null) {
            // Fill and line fade together: a transparency group drawn at the object's opacity.
            val inner = Resources()
            val ic = StringBuilder()
            paintShape(ops, s.evenOdd, fill, stroke, 1f, inner, ic)
            val form = form(ic, inner, gray = false)
            c.append("q ").append(res.gs(alphaGs(opacity, opacity))).append(" gs ").append(res.xobject(form)).append(" Do Q\n")
            return
        }
        paintShape(ops, s.evenOdd, fill, stroke, opacity, res, c)
    }

    private fun paintShape(ops: String, evenOdd: Boolean, fill: VPaint?, stroke: SceneStroke?, opacity: Float, res: Resources, c: StringBuilder) {
        when (fill) {
            null -> {}
            is VPaint.Solid -> {
                val a = alphaOf(fill.color) * opacity
                if (a > 0f) {
                    c.append("q ")
                    if (a < 1f) c.append(res.gs(alphaGs(a, 1f))).append(" gs ")
                    c.append(rgb(fill.color)).append(" rg\n").append(ops).append(if (evenOdd) "f*" else "f").append("\nQ\n")
                }
            }
            is VPaint.Linear, is VPaint.Radial -> gradient(ops, evenOdd, fill, opacity, res, c)
        }
        if (stroke != null) {
            val a = alphaOf(stroke.color) * opacity
            if (a > 0f) {
                c.append("q ")
                if (a < 1f) c.append(res.gs(alphaGs(1f, a))).append(" gs ")
                c.append(rgb(stroke.color)).append(" RG ").append(Pdf.num(stroke.width)).append(" w ")
                    .append(capOf(stroke.cap)).append(" J ").append(joinOf(stroke.join)).append(" j ")
                    .append(Pdf.num(max(1f, stroke.miter))).append(" M\n").append(ops).append("S\nQ\n")
            }
        }
    }

    /** A gradient fill: the path as a clip, then the shading (stop opacity through a soft mask). */
    private fun gradient(ops: String, evenOdd: Boolean, paint: VPaint, opacity: Float, res: Resources, c: StringBuilder) {
        val stops = when (paint) {
            is VPaint.Linear -> paint.stops
            is VPaint.Radial -> paint.stops
            is VPaint.Solid -> return
        }
        val sorted = normalizedStops(stops) ?: return
        val clip = ops + (if (evenOdd) "W* n\n" else "W n\n")
        val matrix = (paint as? VPaint.Radial)?.matrix?.takeIf { it.size >= 6 && it.all { v -> v.isFinite() } }
        val cm = matrix?.let { m -> m.take(6).joinToString(" ") { Pdf.num(it) } + " cm\n" } ?: ""
        val alphas = sorted.map { alphaOf(it.color) }
        val uniform = alphas.all { it == alphas[0] }
        c.append("q ")
        if (uniform) {
            val a = alphas[0] * opacity
            if (a <= 0f) { c.setLength(c.length - 2); return }
            if (a < 1f) c.append(res.gs(alphaGs(a, 1f))).append(" gs ")
        } else {
            // The alphas as a gray shading in a luminosity group, clipped like the fill.
            val maskRes = Resources()
            val maskSh = maskRes.shading(shading(paint, sorted, gray = true))
            val mc = StringBuilder().append(clip).append(cm).append(maskSh).append(" sh\n")
            val maskForm = form(mc, maskRes, gray = true)
            val gs = PdfDict().apply {
                this["Type"] = "/ExtGState"
                this["SMask"] = "<</Type /Mask /S /Luminosity /G ${Pdf.ref(maskForm)}>>"
                if (opacity < 1f) this["ca"] = Pdf.num(opacity)
            }
            c.append(res.gs(gs.toString())).append(" gs ")
        }
        val sh = res.shading(shading(paint, sorted, gray = false))
        c.append('\n').append(clip).append(cm).append(sh).append(" sh\nQ\n")
    }

    /** An axial / radial shading of [stops] (RGB, or the stop alphas as gray). */
    private fun shading(paint: VPaint, stops: List<VStop>, gray: Boolean): Int {
        val fn = function(stops, gray)
        val dict = PdfDict()
        when (paint) {
            is VPaint.Linear -> {
                dict["ShadingType"] = "2"
                dict["Coords"] = "[${Pdf.num(paint.x0)} ${Pdf.num(paint.y0)} ${Pdf.num(paint.x1)} ${Pdf.num(paint.y1)}]"
            }
            is VPaint.Radial -> {
                dict["ShadingType"] = "3"
                dict["Coords"] = "[${Pdf.num(paint.cx)} ${Pdf.num(paint.cy)} 0 ${Pdf.num(paint.cx)} ${Pdf.num(paint.cy)} ${Pdf.num(max(1e-3f, paint.r))}]"
            }
            is VPaint.Solid -> {}
        }
        dict["ColorSpace"] = if (gray) "/DeviceGray" else "/DeviceRGB"
        dict["Function"] = fn
        dict["Extend"] = "[true true]"
        return file.obj(dict.toString())
    }

    /** A type 2 function between two stops, or a type 3 stitching of them for more. */
    private fun function(stops: List<VStop>, gray: Boolean): String {
        fun color(s: VStop): String = if (gray) "[${Pdf.num(alphaOf(s.color))}]" else
            "[${Pdf.channel(s.color shr 16)} ${Pdf.channel(s.color shr 8)} ${Pdf.channel(s.color)}]"
        fun segment(a: VStop, b: VStop) = "<</FunctionType 2 /Domain [0 1] /C0 ${color(a)} /C1 ${color(b)} /N 1>>"
        if (stops.size == 2) return segment(stops[0], stops[1])
        val fns = (0 until stops.size - 1).joinToString(" ", "[", "]") { segment(stops[it], stops[it + 1]) }
        val bounds = (1 until stops.size - 1).joinToString(" ", "[", "]") { Pdf.num(stops[it].offset) }
        val encode = (0 until stops.size - 1).joinToString(" ", "[", "]") { "0 1" }
        return "<</FunctionType 3 /Domain [0 1] /Functions $fns /Bounds $bounds /Encode $encode>>"
    }

    private fun alphaGs(fill: Float, stroke: Float): String = "<</Type /ExtGState /ca ${Pdf.num(fill)} /CA ${Pdf.num(stroke)}>>"

    /** Resource names used by one content stream. */
    private inner class Resources {
        private val xobjects = LinkedHashMap<Int, String>()
        private val states = LinkedHashMap<String, Pair<String, Int>>()
        private val shadings = LinkedHashMap<Int, String>()
        private val properties = LinkedHashMap<Int, String>()

        fun xobject(num: Int): String = xobjects.getOrPut(num) { "/X${xobjects.size}" }

        /** The name of the ExtGState [dict] (written once per content stream). */
        fun gs(dict: String): String = states.getOrPut(dict) { "/G${states.size}" to file.obj(dict) }.first

        fun shading(num: Int): String = shadings.getOrPut(num) { "/S${shadings.size}" }

        fun property(num: Int): String = properties.getOrPut(num) { "/OC${properties.size}" }

        fun dict(): String {
            val d = PdfDict()
            if (xobjects.isNotEmpty()) d["XObject"] = xobjects.entries.joinToString(" ", "<<", ">>") { (n, k) -> "$k ${Pdf.ref(n)}" }
            if (states.isNotEmpty()) d["ExtGState"] = states.values.joinToString(" ", "<<", ">>") { (k, n) -> "$k ${Pdf.ref(n)}" }
            if (shadings.isNotEmpty()) d["Shading"] = shadings.entries.joinToString(" ", "<<", ">>") { (n, k) -> "$k ${Pdf.ref(n)}" }
            if (properties.isNotEmpty()) d["Properties"] = properties.entries.joinToString(" ", "<<", ">>") { (n, k) -> "$k ${Pdf.ref(n)}" }
            return d.toString()
        }
    }

    companion object {
        /** Largest page side most readers accept (200 in). */
        const val MAX_PAGE_PT = 14400f

        /** The path as `m l c h` operators (null when it has no segment). */
        fun pathOps(p: VectorPath): String? {
            if (p.isEmpty) return null
            val sb = StringBuilder()
            for (op in p.ops) {
                when (op) {
                    is PathOp.MoveTo -> sb.append(Pdf.num(op.p.x)).append(' ').append(Pdf.num(op.p.y)).append(" m\n")
                    is PathOp.LineTo -> sb.append(Pdf.num(op.p.x)).append(' ').append(Pdf.num(op.p.y)).append(" l\n")
                    is PathOp.CubicTo -> sb.append(Pdf.num(op.c1.x)).append(' ').append(Pdf.num(op.c1.y)).append(' ')
                        .append(Pdf.num(op.c2.x)).append(' ').append(Pdf.num(op.c2.y)).append(' ')
                        .append(Pdf.num(op.p.x)).append(' ').append(Pdf.num(op.p.y)).append(" c\n")
                    PathOp.Close -> sb.append("h\n")
                }
            }
            return sb.toString()
        }

        /** The PDF blend mode of [m] (Add has no PDF equivalent: Screen is the closest). */
        fun blendName(m: LayerBlendMode): String = when (m) {
            LayerBlendMode.NORMAL -> "/Normal"
            LayerBlendMode.MULTIPLY -> "/Multiply"
            LayerBlendMode.SCREEN -> "/Screen"
            LayerBlendMode.OVERLAY -> "/Overlay"
            LayerBlendMode.DARKEN -> "/Darken"
            LayerBlendMode.LIGHTEN -> "/Lighten"
            LayerBlendMode.COLOR_DODGE -> "/ColorDodge"
            LayerBlendMode.COLOR_BURN -> "/ColorBurn"
            LayerBlendMode.HARD_LIGHT -> "/HardLight"
            LayerBlendMode.SOFT_LIGHT -> "/SoftLight"
            LayerBlendMode.DIFFERENCE -> "/Difference"
            LayerBlendMode.EXCLUSION -> "/Exclusion"
            LayerBlendMode.HUE -> "/Hue"
            LayerBlendMode.SATURATION -> "/Saturation"
            LayerBlendMode.COLOR -> "/Color"
            LayerBlendMode.LUMINOSITY -> "/Luminosity"
            LayerBlendMode.ADD -> "/Screen"
        }

        fun capOf(c: LineCapStyle): Int = when (c) { LineCapStyle.BUTT -> 0; LineCapStyle.ROUND -> 1; LineCapStyle.SQUARE -> 2 }

        fun joinOf(j: JoinStyle): Int = when (j) { JoinStyle.MITER -> 0; JoinStyle.ROUND -> 1; JoinStyle.BEVEL -> 2 }

        fun rgb(c: Int): String = "${Pdf.channel(c shr 16)} ${Pdf.channel(c shr 8)} ${Pdf.channel(c)}"

        fun alphaOf(c: Int): Float = (c ushr 24) / 255f

        /**
         * Stops sorted by offset in 0..1, starting at 0 and ending at 1 (the end colors repeated:
         * pad), each interval longer than zero (a hard step is nudged by 1e-5); null without stops.
         */
        fun normalizedStops(stops: List<VStop>): List<VStop>? {
            if (stops.isEmpty()) return null
            val s = stops.map { it.copy(offset = if (it.offset.isFinite()) it.offset.coerceIn(0f, 1f) else 0f) }.sortedBy { it.offset }.toMutableList()
            if (s.first().offset > 0f) s.add(0, s.first().copy(offset = 0f))
            if (s.last().offset < 1f) s.add(s.last().copy(offset = 1f))
            if (s.size == 1) s.add(s[0].copy(offset = 1f))
            val out = ArrayList<VStop>(s.size)
            for (st in s) {
                val prev = out.lastOrNull()
                if (prev != null && st.offset <= prev.offset) {
                    val o = (prev.offset + 1e-5f).coerceAtMost(1f)
                    if (o <= prev.offset) {
                        // Already at 1: the last color wins.
                        out[out.lastIndex] = st.copy(offset = prev.offset)
                        continue
                    }
                    out += st.copy(offset = o)
                } else {
                    out += st
                }
            }
            if (out.size == 1) out += out[0].copy(offset = 1f)
            out[0] = out[0].copy(offset = 0f)
            out[out.lastIndex] = out[out.lastIndex].copy(offset = 1f)
            return out
        }
    }
}
