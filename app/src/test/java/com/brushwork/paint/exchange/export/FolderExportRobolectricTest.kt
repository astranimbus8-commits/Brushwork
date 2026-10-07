package com.brushwork.paint.exchange.export

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Paint
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.Compositor
import com.brushwork.paint.engine.FolderComposite
import com.brushwork.paint.exchange.ExchangeFixtures
import com.brushwork.paint.exchange.VectorFormat
import com.brushwork.paint.exchange.pdf.ArrayPdfBytes
import com.brushwork.paint.exchange.pdf.OwnPdfReader
import com.brushwork.paint.exchange.pdf.PdfObj
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.FolderSpec
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.model.LayerTree
import com.brushwork.paint.tools.transform.ContentBounds
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.w3c.dom.Element
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import javax.xml.parsers.DocumentBuilderFactory

/**
 * v1.7 area A (item 8, §3.8, §5.1 A): folders export as nested groups, isolated or not exactly
 * as the canvas composites them; the adjustment rule merges per context (the top level or an
 * isolated folder) and keeps the folders around a merged adjustment layer; clip groups with a
 * folder base or a clipped folder are pictures equal to the canvas; SVG nests `<g>` (a
 * pass-through folder a plain one), PDF nests its optional content groups and `/Order`.
 */
@RunWith(RobolectricTestRunner::class)
class FolderExportRobolectricTest {
    private val w = 96
    private val h = 72

    // ------------------------------------------------------------------ building

    private fun layer(doc: Document, name: String, draw: (Canvas) -> Unit = {}): Layer =
        Layer(doc.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { draw(Canvas(it.bitmap)) }

    private fun rect(doc: Document, name: String, color: Int, r: Rect): Layer = layer(doc, name) { it.drawRect(r, Paint().apply { this.color = color }) }

    private fun disc(doc: Document, name: String, color: Int, cx: Float, cy: Float, r: Float): Layer =
        layer(doc, name) { it.drawCircle(cx, cy, r, Paint(Paint.ANTI_ALIAS_FLAG).apply { this.color = color }) }

    private fun gradient(doc: Document, name: String = "Background"): Layer = layer(doc, name) {
        it.drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF2050C0.toInt(), 0xFFF0C020.toInt(), Shader.TileMode.CLAMP) })
    }

    private fun invert(doc: Document, name: String = "Invert"): Layer = layer(doc, name).also { it.adjustment = AdjustmentSpec(filterId = "adjust.invert") }

    private fun folder(doc: Document, name: String, passThrough: Boolean = true, vararg children: Layer): Layer =
        Layer.newFolder(doc.newLayerId(), name, FolderSpec(passThrough = passThrough)).also { f -> for (c in children) c.parentId = f.id }

    private fun docOf(build: (Document) -> List<Layer>): Document = Document("folders", "folders", w, h).also { d ->
        d.layers += build(d)
        d.activeLayerIndex = d.layers.lastIndex
        assertNull(LayerTree.check(d.layers))
    }

    private fun scene(doc: Document, format: VectorFormat = VectorFormat.SVG, includeHidden: Boolean = false): ExportScene {
        val c = ExchangeFixtures.controller(doc)
        return runBlocking { ExportSceneBuilder(c, ExportOptions(format, includeHidden = includeHidden), TextSource.Default, Dispatchers.Unconfined, Dispatchers.Unconfined).build() }
    }

    private fun names(layers: List<SceneLayer>): List<String> = layers.map { it.name }

    private fun List<SceneLayer>.named(name: String): SceneLayer = first { it.name == name }

    private fun Document.byName(name: String): Layer = layers.first { it.name == name }

    private fun argb(b: Bitmap): IntArray = IntArray(b.width * b.height).also { b.getPixels(it, 0, b.width, 0, 0, b.width, b.height) }

    private fun flattened(doc: Document): IntArray = Compositor(doc) { null }.renderFlattened().let { b -> try { argb(b) } finally { b.recycle() } }

    /** The single picture of [l], loaded; with where it lies. */
    private fun picture(l: SceneLayer): Pair<Rect, IntArray> {
        val img = (l.items.single() as SceneItem.Image).image
        return Rect(img.left, img.top, img.left + img.width, img.top + img.height) to runBlocking { img.source.load() }.pixels
    }

    private fun crop(px: IntArray, r: Rect): IntArray = IntArray(r.width() * r.height()).also { out ->
        for (y in 0 until r.height()) for (x in 0 until r.width()) out[y * r.width() + x] = px[(y + r.top) * w + x + r.left]
    }

    /** Background, F1 (pass-through: A, F2 (isolated, Multiply at 80 %: B)), an empty folder, Top. */
    private fun nested(): Document = docOf { d ->
        val bg = layer(d, "Background") { it.drawColor(-1) }
        val a = rect(d, "A", 0xFFE03020.toInt(), Rect(10, 10, 40, 30))
        val b = rect(d, "B", 0xFF20C040.toInt(), Rect(30, 20, 60, 50))
        val f2 = folder(d, "F2", passThrough = false, b).also { it.blendMode = LayerBlendMode.MULTIPLY; it.opacity = 0.8f }
        val f1 = folder(d, "F1", passThrough = true, a, f2).also { it.blendMode = LayerBlendMode.SCREEN }
        f2.parentId = f1.id
        val empty = folder(d, "Empty")
        val top = rect(d, "Top", 0xFF2040E0.toInt(), Rect(70, 5, 90, 25))
        listOf(bg, a, b, f2, f1, empty, top)
    }

    // ------------------------------------------------------------------ the scene

    @Test
    fun foldersAreNestedGroupsIsolatedAsTheCanvasDrawsThem() {
        val s = scene(nested())
        assertEquals("an empty folder is left out", listOf("Background", "F1", "Top"), names(s.layers))
        val f1 = s.layers.named("F1")
        assertEquals(listOf("A", "F2"), names(f1.children))
        assertFalse("pass-through at 100 %: its layers blend through", f1.isolated)
        assertEquals("a pass-through folder's own blend mode is not used", LayerBlendMode.NORMAL, f1.blend)
        assertEquals(1f, f1.opacity, 0f)
        assertTrue(f1.items.isEmpty())
        val f2 = f1.children.named("F2")
        assertTrue(f2.isolated)
        assertEquals(LayerBlendMode.MULTIPLY, f2.blend)
        assertEquals(0.8f, f2.opacity, 1e-6f)
        assertEquals(listOf("B"), names(f2.children))
        assertTrue(f2.children.single().items.single() is SceneItem.Image)
        // The payload still lists every layer flat, with the tree.
        assertEquals(7, s.payload!!.layers.size)
        assertEquals(s.payload!!.layers.count { it.kind == PayloadKind.FOLDER }, 3)
    }

    @Test
    fun aHiddenFolderHidesItsLayersAndIncludeHiddenKeepsTheirOwnEyes() {
        val doc = docOf { d ->
            val bg = layer(d, "Background") { it.drawColor(-1) }
            val a = rect(d, "A", 0xFFE03020.toInt(), Rect(10, 10, 40, 30))
            val b = rect(d, "B", 0xFF20C040.toInt(), Rect(30, 20, 60, 50))
            val inv = invert(d, "Invert")
            val iso = folder(d, "Iso", passThrough = false, b, inv).also { it.visible = false }
            val c = rect(d, "C", 0xFF2040E0.toInt(), Rect(5, 40, 30, 70))
            val inv2 = invert(d, "Invert 2")
            val pass = folder(d, "Pass", passThrough = true, c, inv2).also { it.visible = false }
            listOf(bg, a, b, inv, iso, c, inv2, pass)
        }
        assertEquals(listOf("Background", "A"), names(scene(doc).layers))
        val s = scene(doc, includeHidden = true)
        assertEquals(listOf("Background", "A", "Iso", "Pass"), names(s.layers))
        val iso = s.layers.named("Iso")
        assertTrue(iso.hidden)
        // Inside an isolated folder its adjustment layer merges what is below it there.
        assertEquals(listOf("Invert (merged with the layers below)"), names(iso.children))
        assertFalse("its own eye", iso.children.single().hidden)
        val pass = s.layers.named("Pass")
        assertTrue(pass.hidden)
        assertFalse(pass.isolated)
        // A hidden pass-through folder's adjustment layer draws nothing (as a hidden one above the merged part).
        assertEquals(listOf("C"), names(pass.children))
        assertFalse(pass.children.single().hidden)
        // A layer's own eye inside a shown folder.
        doc.byName("Iso").visible = true
        doc.byName("B").visible = false
        val s2 = scene(doc, includeHidden = true)
        assertFalse(s2.layers.named("Iso").hidden)
    }

    @Test
    fun anAdjustmentLayerInAnIsolatedFolderMergesOnlyTheFoldersLayers() {
        val doc = docOf { d ->
            val bg = gradient(d)
            val p = disc(d, "Photo", 0xC0E05030.toInt(), 40f, 34f, 26f)
            val inv = invert(d)
            val f = folder(d, "F", passThrough = false, p, inv)
            val top = rect(d, "Top", 0xFF2040E0.toInt(), Rect(70, 5, 90, 25))
            listOf(bg, p, inv, f, top)
        }
        val s = scene(doc)
        assertEquals(listOf("Background", "F", "Top"), names(s.layers))
        val f = s.layers.named("F")
        assertEquals(listOf("Invert (merged with the layers below)"), names(f.children))
        val (r, px) = picture(f.children.single())
        assertEquals(Rect(0, 0, w, h), r)
        val block = FolderComposite.renderBlock(doc, doc.byName("F"))
        assertArrayEquals("the folder's own composite", argb(block), px)
        block.recycle()
        assertTrue(s.notes.contains("Layers below an adjustment layer are exported as one picture"))
    }

    @Test
    fun anAdjustmentLayerInAPassThroughFolderMergesWithTheFoldersAroundIt() {
        val doc = docOf { d ->
            // Only the left half: a clipping layer clipped to it would lose its right half.
            val bg = rect(d, "Background", 0xFF30A0F0.toInt(), Rect(0, 0, w / 2, h))
            val clip = disc(d, "Clip", 0xD0E04020.toInt(), 48f, 36f, 30f).also { it.clipping = true }
            val inv = invert(d)
            val above = rect(d, "Above", 0xFF20C040.toInt(), Rect(60, 10, 90, 40))
            val p = folder(d, "P", passThrough = true, clip, inv, above)
            val top = rect(d, "Top", 0xFF2040E0.toInt(), Rect(70, 50, 90, 70))
            listOf(bg, clip, inv, above, p, top)
        }
        val s = scene(doc)
        assertEquals(listOf("Invert (merged with the layers below)", "P", "Top"), names(s.layers))
        val p = s.layers.named("P")
        assertFalse(p.isolated)
        assertEquals("what is left of the folder above the merged part", listOf("Above"), names(p.children))
        val (r, px) = picture(s.layers.first())
        assertEquals(Rect(0, 0, w, h), r)
        // The canvas without the layers above the adjustment layer: the clipping layer at the
        // bottom of the folder draws unclipped there.
        doc.byName("Above").visible = false
        doc.byName("Top").visible = false
        assertArrayEquals(flattened(doc), px)
    }

    @Test
    fun clipGroupsWithFoldersArePicturesAndAHiddenClipLeavesAnIsolatedFolder() {
        val doc = docOf { d ->
            val bg = gradient(d)
            val a = disc(d, "A", 0xFFE05030.toInt(), 30f, 30f, 20f)
            val f = folder(d, "F", passThrough = true, a)
            val c = rect(d, "C", 0xC02040E0.toInt(), Rect(20, 0, 96, 40)).also { it.clipping = true }
            listOf(bg, a, f, c)
        }
        val s = scene(doc)
        assertEquals(listOf("Background", "F"), names(s.layers))
        val group = s.layers.named("F")
        assertTrue("a picture, no group", group.children.isEmpty())
        val (r, px) = picture(group)
        assertEquals(ContentBounds.of(doc.byName("A").bitmap), r)
        doc.byName("Background").visible = false
        assertArrayEquals("the canvas's clip group", crop(flattened(doc), r), px)
        doc.byName("Background").visible = true
        assertTrue(s.notes.contains("Clipping groups are exported as pictures"))

        // Its only clip hidden: still a clip base, composited isolated with its own blend mode.
        doc.byName("C").visible = false
        doc.byName("F").blendMode = LayerBlendMode.MULTIPLY
        val f = scene(doc).layers.named("F")
        assertTrue(f.isolated)
        assertEquals(LayerBlendMode.MULTIPLY, f.blend)
        assertEquals(listOf("A"), names(f.children))
    }

    @Test
    fun aClippedFolderIsPartOfItsBasesPicture() {
        val doc = docOf { d ->
            val bg = gradient(d)
            val base = rect(d, "Base", 0xFFE05030.toInt(), Rect(10, 10, 60, 50))
            val x = disc(d, "X", 0xC02040E0.toInt(), 50f, 40f, 24f)
            val g = folder(d, "G", passThrough = true, x).also { it.clipping = true }
            listOf(bg, base, x, g)
        }
        val s = scene(doc)
        assertEquals(listOf("Background", "Base"), names(s.layers))
        val (r, px) = picture(s.layers.named("Base"))
        assertEquals(Rect(10, 10, 60, 50), r)
        doc.byName("Background").visible = false
        assertArrayEquals(crop(flattened(doc), r), px)
    }

    @Test
    fun aPassThroughFolderBelow100IsAnIsolatedNormalGroupWithANote() {
        val doc = docOf { d ->
            val bg = gradient(d)
            val a = disc(d, "A", 0xFFE05030.toInt(), 30f, 30f, 20f).also { it.blendMode = LayerBlendMode.MULTIPLY }
            val p = folder(d, "P", passThrough = true, a).also { it.opacity = 0.5f; it.blendMode = LayerBlendMode.SCREEN }
            listOf(bg, a, p)
        }
        val s = scene(doc)
        val p = s.layers.named("P")
        assertTrue(p.isolated)
        assertEquals(LayerBlendMode.NORMAL, p.blend)
        assertEquals(0.5f, p.opacity, 1e-6f)
        assertTrue(s.notes.contains("Pass-through folders below 100 % are exported as isolated groups"))
        assertFalse(scene(nested()).notes.any { it.startsWith("Pass-through") })
    }

    // ------------------------------------------------------------------ the writers

    private fun svgOf(s: ExportScene, progress: MutableList<Float>): org.w3c.dom.Document {
        val out = ByteArrayOutputStream()
        runBlocking { SvgWriter(s) { progress += it }.write(out) }
        val f = DocumentBuilderFactory.newInstance().apply { isNamespaceAware = true }
        return f.newDocumentBuilder().parse(ByteArrayInputStream(out.toByteArray()))
    }

    private fun groups(x: org.w3c.dom.Document): Map<String, Element> {
        val out = HashMap<String, Element>()
        val list = x.getElementsByTagNameNS("http://www.w3.org/2000/svg", "g")
        for (i in 0 until list.length) {
            val e = list.item(i) as Element
            out[e.getAttributeNS("http://www.inkscape.org/namespaces/inkscape", "label")] = e
        }
        return out
    }

    /** Every progress value rises, the last is 1, one per picture at every level. */
    private fun assertProgress(s: ExportScene, progress: List<Float>) {
        assertEquals(s.treeImageCount, progress.size)
        assertTrue("more pictures than the top level has", s.treeImageCount > s.imageCount)
        for (i in 1 until progress.size) assertTrue("$progress", progress[i] > progress[i - 1])
        assertEquals(1f, progress.last(), 1e-6f)
    }

    @Test
    fun svgNestsTheGroupsAndAPassThroughFolderIsAPlainGroup() {
        val doc = nested()
        val s = scene(doc)
        val progress = ArrayList<Float>()
        val g = groups(svgOf(s, progress))
        val f1 = g.getValue("F1")
        val f2 = g.getValue("F2")
        assertEquals("svg", (f1.parentNode as Element).localName)
        assertEquals(f1, g.getValue("A").parentNode)
        assertEquals(f1, f2.parentNode)
        assertEquals(f2, g.getValue("B").parentNode)
        assertFalse("no opacity, blend mode or isolation", f1.hasAttribute("style"))
        assertEquals("opacity:0.8;mix-blend-mode:multiply;isolation:isolate", f2.getAttribute("style"))
        assertEquals("isolation:isolate", g.getValue("Top").getAttribute("style"))
        // A folder's layers come in order, below its next sibling.
        val kids = (0 until f1.childNodes.length).map { f1.childNodes.item(it) }.filterIsInstance<Element>().map { it.getAttributeNS("http://www.inkscape.org/namespaces/inkscape", "label") }
        assertEquals(listOf("A", "F2"), kids)
        assertProgress(s, progress)

        // Hidden folders (exported with "Include hidden layers").
        doc.byName("F1").visible = false
        doc.byName("F2").visible = false
        val h = groups(svgOf(scene(doc, includeHidden = true), ArrayList()))
        assertEquals("display:none", h.getValue("F1").getAttribute("style"))
        assertEquals("opacity:0.8;mix-blend-mode:multiply;isolation:isolate;display:none", h.getValue("F2").getAttribute("style"))
        assertEquals("isolation:isolate", h.getValue("B").getAttribute("style"))
    }

    @Test
    fun pdfNestsTheOptionalContentGroupsAndAnIsolatedFolderIsATransparencyGroup() {
        val doc = nested()
        doc.byName("F2").visible = false
        val s = scene(doc, VectorFormat.PDF, includeHidden = true)
        val out = ByteArrayOutputStream()
        val progress = ArrayList<Float>()
        runBlocking { PdfWriter(s) { progress += it }.write(out) }
        assertProgress(s, progress)
        val r = OwnPdfReader(ArrayPdfBytes(out.toByteArray()))
        val ocp = r.resolve(r.catalog()!!["OCProperties"]) as PdfObj.Dict
        fun nameOf(o: PdfObj): String = ((r.obj((o as PdfObj.Ref).num) as PdfObj.Dict)["Name"] as PdfObj.Str).text
        val all = (ocp["OCGs"] as PdfObj.Arr).items
        assertEquals(setOf("Background", "A", "B", "F2", "F1", "Top"), all.map { nameOf(it) }.toSet())
        val d = ocp["D"] as PdfObj.Dict
        // Top first, a folder's layers in an array after it.
        fun shape(o: PdfObj): Any = if (o is PdfObj.Arr) o.items.map { shape(it) } else nameOf(o)
        assertEquals(listOf("Top", "F1", listOf("F2", listOf("B"), "A"), "Background"), shape(d["Order"]!!))
        assertEquals(listOf("F2"), (d["OFF"] as PdfObj.Arr).items.map { nameOf(it) })
        assertEquals(5, (d["ON"] as PdfObj.Arr).items.size)

        // The page draws F1's layers straight (a marked group, no form), F2 as one form.
        val cat = r.catalog()!!
        val pageRef = ((r.resolve(cat["Pages"]) as PdfObj.Dict)["Kids"] as PdfObj.Arr).items[0] as PdfObj.Ref
        val page = r.obj(pageRef.num) as PdfObj.Dict
        val content = String(r.streamData(r.resolve(page["Contents"]) as PdfObj.Stream), Charsets.ISO_8859_1)
        assertTrue(content, Regex("/OC /OC\\d+ BDC\n/OC /OC\\d+ BDC q /G\\d+ gs /X\\d+ Do Q EMC\n/OC /OC\\d+ BDC q /G\\d+ gs /X\\d+ Do Q EMC\nEMC\n").containsMatchIn(content))
        val res = page["Resources"] as PdfObj.Dict
        val forms = (res["XObject"] as PdfObj.Dict).map.values.map { r.resolve(it) as PdfObj.Stream }
        // Background, A, F2 and Top: F2's form holds B's, inside B's group.
        assertEquals(4, forms.size)
        val f2 = forms.single { f -> ((f.dict["Resources"] as PdfObj.Dict)["Properties"]) != null }
        assertTrue((f2.dict["Group"] as PdfObj.Dict)["I"].toString().contains("true"))
        val inner = String(r.streamData(f2), Charsets.ISO_8859_1)
        assertTrue(inner, Regex("^/OC /OC\\d+ BDC q /G\\d+ gs /X\\d+ Do Q EMC\n$").matches(inner))
        val gs = ((res["ExtGState"] as PdfObj.Dict).map.values.map { r.resolve(it) as PdfObj.Dict })
        assertTrue("F2 multiplies at 80 %", gs.any { (it["BM"] as? PdfObj.Name)?.name == "Multiply" && (it["ca"] as PdfObj.Num).value in 0.79..0.81 })
    }
}
