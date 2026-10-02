package com.brushwork.paint.exchange

import android.app.Activity
import android.content.Intent
import android.graphics.Canvas
import android.graphics.Paint
import android.net.Uri
import com.brushwork.paint.EditorController
import com.brushwork.paint.core.Vec2
import com.brushwork.paint.engine.EditTarget
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PdfObj
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.tools.mask.MaskTool
import com.brushwork.paint.tools.text.TextPathSpec
import com.brushwork.paint.tools.text.TextPathType
import com.brushwork.paint.tools.text.TextTool
import com.brushwork.paint.tools.text.WrapSides
import com.brushwork.paint.tools.vector.CurveTool
import com.brushwork.paint.tools.vector.ShapeTool
import com.brushwork.paint.vector.VAnchor
import com.brushwork.paint.vector.VPaint
import com.brushwork.paint.vector.VPath
import com.brushwork.paint.vector.VStop
import com.brushwork.paint.vector.VStrokeStyle
import com.brushwork.paint.vector.VSubpath
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.robolectric.Shadows.shadowOf
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.XMLConstants
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Final QA (v1.5, SVG / PDF exchange): the system pickers answered like a user would, a document
 * with every kind of layer made with the real tools, and structural checks of the files written.
 */
internal object QaExchange {

    // ------------------------------------------------------------------ system pickers

    /**
     * The picker the app just started for a result (SAF CreateDocument / OpenDocument), answered
     * with [uri] (null = the user backed out). Returns the request intent.
     */
    fun answerPicker(activity: Activity, uri: Uri?, expectAction: String? = null): Intent {
        val shadow = shadowOf(activity)
        val started = shadow.nextStartedActivityForResult ?: throw AssertionError("no picker was started")
        if (expectAction != null) assertEquals("picker action", expectAction, started.intent.action)
        val result = if (uri != null) Intent().setData(uri) else null
        shadow.receiveResult(started.intent, if (uri != null) Activity.RESULT_OK else Activity.RESULT_CANCELED, result)
        return started.intent
    }

    /** A content Uri whose writes land in the returned buffer (a document the SAF picker created). */
    fun writableUri(activity: Activity, name: String): Pair<Uri, ByteArrayOutputStream> {
        val uri = Uri.parse("content://com.android.externalstorage.documents/document/primary%3ADownload%2F" + Uri.encode(name))
        val out = ByteArrayOutputStream()
        shadowOf(activity.contentResolver).registerOutputStreamSupplier(uri) { out }
        return uri to out
    }

    // ------------------------------------------------------------------ tools

    fun drag(c: EditorController, vararg pts: Pair<Float, Float>, steps: Int = 8) {
        c.pointerDown(ToolPoint(pts[0].first, pts[0].second))
        for (i in 1 until pts.size) {
            val (ax, ay) = pts[i - 1]
            val (bx, by) = pts[i]
            for (s in 1..steps) c.pointerMove(ToolPoint(ax + (bx - ax) * s / steps, ay + (by - ay) * s / steps))
        }
        c.pointerUp(ToolPoint(pts.last().first, pts.last().second))
    }

    /** One brush stroke on the active layer with [color]. */
    fun brush(c: EditorController, color: Int, vararg pts: Pair<Float, Float>, size: Float = 10f) {
        c.selectTool(ToolId.BRUSH)
        c.brush = c.brush.copy(size = size, opacity = 1f, hardness = 1f, taperStart = 0f, taperEnd = 0f)
        c.color = color
        drag(c, *pts)
    }

    /**
     * A 480 x 360 document at 300 dpi, made like a user makes it: Background (white) under a
     * Tone adjustment layer (linear mask), a raster layer with a painted mask, a clipping group,
     * a hidden layer, a vector layer (brush stroke, curve, shape, gradient path), and text layers
     * (horizontal, vertical, wrapped around the raster picture, on a circle).
     */
    fun allKinds(c: EditorController): Map<String, Layer> {
        val doc = c.doc
        val out = LinkedHashMap<String, Layer>()
        val bg = doc.layers[0]
        c.renameLayer(bg, "Background")
        out["background"] = bg
        // Raster with a mask.
        val raster = doc.layers[1]
        c.selectLayer(raster)
        c.renameLayer(raster, "Raster")
        brush(c, 0xFFCC2222.toInt(), 40f to 60f, 200f to 120f, 120f to 200f, size = 24f)
        c.addMask(raster, fromSelection = false)
        c.editWholeLayer(raster, "Hide part", EditTarget.MASK) { m -> Canvas(m).drawRect(40f, 150f, 90f, 220f, Paint().apply { color = 0xFF000000.toInt() }) }
        out["raster"] = raster
        // Clipping group.
        val base = c.addLayer("Base")!!
        brush(c, 0xFF2222CC.toInt(), 260f to 40f, 420f to 40f, size = 30f)
        val clipped = c.addLayer("Clipped")!!
        brush(c, 0xFFFFCC00.toInt(), 300f to 20f, 300f to 80f, size = 20f)
        c.toggleClipping(clipped)
        assertTrue(clipped.clipping)
        out["base"] = base
        out["clipped"] = clipped
        // Hidden.
        val hidden = c.addLayer("Hidden")!!
        brush(c, 0xFF00AA00.toInt(), 20f to 330f, 140f to 330f)
        c.toggleVisibility(hidden)
        out["hidden"] = hidden
        // Vector layer through the Vector button's action.
        c.toggleVectorMode()
        val vector = c.activeLayer
        assertTrue("Vector mode made a vector layer", vector.isVectorLayer)
        brush(c, 0xFF8800AA.toInt(), 250f to 150f, 330f to 170f, 420f to 150f, size = 8f)
        c.selectTool(ToolId.CURVE)
        val curve = c.tools.getValue(ToolId.CURVE) as CurveTool
        curve.update { it.copy(stroke = com.brushwork.paint.tools.vector.CurveStroke.PLAIN, useBrushSize = false, plainWidth = 5f) }
        curve.addAnchor(Vec2(250f, 220f)); curve.addAnchor(Vec2(330f, 260f)); curve.addAnchor(Vec2(430f, 220f))
        curve.commit()
        c.selectTool(ToolId.SHAPE)
        drag(c, 260f to 280f, 340f to 340f)
        (c.tools.getValue(ToolId.SHAPE) as ShapeTool).commit()
        val gradient = VPath(
            0,
            subpaths = listOf(VSubpath(listOf(VAnchor(360f, 280f, true), VAnchor(460f, 280f, true), VAnchor(460f, 340f, true), VAnchor(360f, 340f, true)), closed = true)),
            fill = VPaint.Linear(360f, 0f, 460f, 0f, listOf(VStop(0f, 0xFFFF0000.toInt()), VStop(1f, 0xFF0000FF.toInt()))),
            stroke = VStrokeStyle(color = 0xFF000000.toInt(), width = 2f),
        )
        c.vectors.addObjects(vector, listOf(gradient), "Add gradient")
        out["vector"] = vector
        // Text layers.
        c.selectTool(ToolId.TEXT)
        val text = c.tools.getValue(ToolId.TEXT) as TextTool
        fun newText(x: Float, y: Float, s: String, edit: TextTool.() -> Unit = {}): Layer {
            text.startTextAt(x, y)
            text.setText(s)
            // New texts take the last text's style (a vertical one stays vertical).
            text.updateSpec { it.copy(sizePx = 22f, color = 0xFF202020.toInt(), vertical = false) }
            text.confirmEditor()
            text.edit()
            assertTrue("text \"$s\" committed", text.commitItem())
            return c.activeLayer.also { assertTrue(it.isTextLayer) }
        }
        out["text"] = newText(120f, 30f, "Hello export")
        out["vertical"] = newText(460f, 120f, "Tate") { toggleVertical() }
        out["onPath"] = newText(380f, 100f, "Around and around") { setPath(TextPathSpec(type = TextPathType.CIRCLE, cx = 380f, cy = 100f, radius = 50f)) }
        out["wrapped"] = newText(130f, 140f, "Words flow around the red picture on this layer and keep going for a while.") {
            updateSpec { it.copy(sizePx = 14f, box = it.box.copy(width = 220f)) }
            setWrapSource(raster)
            setWrapSides(WrapSides.BOTH)
        }
        assertTrue("wrap is on", com.brushwork.paint.tools.text.TextCodec.decode(out["wrapped"]!!.textData)!!.wrapActive)
        // Tone adjustment layer right above the background (Masks tool, + Linear).
        c.selectLayer(bg)
        c.selectTool(ToolId.MASK)
        val mask = c.tools.getValue(ToolId.MASK) as MaskTool
        mask.arm(MaskTool.Kind.LINEAR)
        drag(c, 10f to 180f, 240f to 180f, 470f to 180f)
        val tone = c.activeLayer
        assertTrue("Masks made an adjustment layer", tone.isAdjustmentLayer)
        out["tone"] = tone
        c.selectTool(ToolId.BRUSH)
        c.selectLayer(vector)
        return out
    }

    // ------------------------------------------------------------------ fixtures

    /** A test resource under `svg/` (the Robolectric sandbox's class loader may not see resources). */
    fun svgFixture(name: String): ByteArray {
        val path = "svg/$name"
        val stream = QaExchange::class.java.classLoader?.getResourceAsStream(path)
            ?: ClassLoader.getSystemResourceAsStream(path)
            ?: Thread.currentThread().contextClassLoader?.getResourceAsStream(path)
        if (stream != null) return stream.use { it.readBytes() }
        val file = listOf(java.io.File("src/test/resources/$path"), java.io.File("app/src/test/resources/$path")).firstOrNull { it.isFile }
            ?: throw AssertionError("no test resource $path")
        return file.readBytes()
    }

    // ------------------------------------------------------------------ file checks

    /** Parses [bytes] with the platform XML parser (DTDs refused): well-formed or it throws. */
    fun parseXml(bytes: ByteArray): org.w3c.dom.Document {
        val f = DocumentBuilderFactory.newInstance()
        f.isNamespaceAware = true
        f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true)
        f.setFeature(XMLConstants.FEATURE_SECURE_PROCESSING, true)
        return f.newDocumentBuilder().parse(ByteArrayInputStream(bytes))
    }

    const val SVG_NS = "http://www.w3.org/2000/svg"
    const val INKSCAPE_NS = "http://www.inkscape.org/namespaces/inkscape"

    /** The Inkscape layer groups of an SVG, in file order (bottom first). */
    fun layerGroups(svg: org.w3c.dom.Document): List<Element> {
        val all = svg.getElementsByTagNameNS(SVG_NS, "g")
        return (0 until all.length).map { all.item(it) as Element }.filter { it.getAttributeNS(INKSCAPE_NS, "groupmode") == "layer" }
    }

    fun elements(parent: Element, local: String): List<Element> {
        val all = parent.getElementsByTagNameNS(SVG_NS, local)
        return (0 until all.length).map { all.item(it) as Element }
    }

    /**
     * A structurally valid PDF (the project's own reader): every xref offset lands on its object,
     * every object parses, every stream inflates and every reference resolves. Returns the reader.
     */
    fun checkPdf(bytes: ByteArray): OwnPdfReader {
        val text = String(bytes, Charsets.ISO_8859_1)
        assertTrue("PDF header", text.startsWith("%PDF-"))
        assertTrue("PDF trailer", text.trimEnd().endsWith("%%EOF"))
        val r = OwnPdfReader(ArrayPdfBytes(bytes))
        for ((num, off) in r.offsets) {
            assertTrue("object $num at $off", text.startsWith("$num 0 obj", off.toInt()))
            val o = r.obj(num)
            assertNotNull("object $num parses", o)
            if (o is PdfObj.Stream) r.streamData(o)
        }
        fun visit(o: PdfObj?) {
            when (o) {
                is PdfObj.Ref -> assertNotNull("ref ${o.num}", r.obj(o.num))
                is PdfObj.Arr -> o.items.forEach(::visit)
                is PdfObj.Dict -> o.map.values.forEach(::visit)
                is PdfObj.Stream -> o.dict.map.values.forEach(::visit)
                else -> {}
            }
        }
        for (num in r.offsets.keys) visit(r.obj(num))
        return r
    }

    /** The PDF's pages (dictionaries). */
    fun pdfPages(r: OwnPdfReader): List<PdfObj.Dict> {
        val pages = r.resolve(r.catalog()!!["Pages"]) as PdfObj.Dict
        return (pages["Kids"] as PdfObj.Arr).items.map { r.resolve(it) as PdfObj.Dict }
    }

    fun mediaBox(page: PdfObj.Dict): List<Double> = (page["MediaBox"] as PdfObj.Arr).items.map { (it as PdfObj.Num).value }

    /** Names of the optional content groups (layers) and the names of those off by default. */
    fun ocgs(r: OwnPdfReader): Pair<List<String>, List<String>> {
        val ocp = r.resolve(r.catalog()!!["OCProperties"]) as PdfObj.Dict
        val refs = (ocp["OCGs"] as PdfObj.Arr).items.map { it as PdfObj.Ref }
        fun name(ref: PdfObj.Ref) = ((r.obj(ref.num) as PdfObj.Dict)["Name"] as PdfObj.Str).text
        val d = ocp["D"] as PdfObj.Dict
        val off = (d["OFF"] as? PdfObj.Arr)?.items?.map { name(it as PdfObj.Ref) }.orEmpty()
        return refs.map(::name) to off
    }

    /** Waits until the editor's busy overlay is gone (and [extra] holds). */
    fun waitIdle(c: EditorController, extra: () -> Boolean = { true }): Boolean =
        Smoke.pumpUntil(30_000) { c.busyMessage == null && extra() }
}
