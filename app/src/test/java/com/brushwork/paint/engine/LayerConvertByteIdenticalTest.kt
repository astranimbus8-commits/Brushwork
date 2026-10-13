package com.brushwork.paint.engine

import android.content.Context
import android.graphics.Paint
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditEvent
import com.brushwork.paint.EditorController
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.tools.vector.ShapeCodec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VShape
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/**
 * v1.9 F1b (design 2.4.7): `EditorController.convertToVectorLayer`, now converting through
 * [LayerConvert], is byte-identical to v1.8's (kept below as [v18Convert], verbatim but for the
 * controller's private helpers): the same result and message, the same undo step (one
 * `CompositeAction` of the rename and the data, or the data action alone, with the same labels
 * and contents), the same edit count, layer-list version and edit events, and the same undo and
 * redo. [LayerConvert.toVectorAction] converts the same way without a step, and `addVectorLayer`
 * names its layer after `baseName`.
 */
@RunWith(RobolectricTestRunner::class)
class LayerConvertByteIdenticalTest {
    private val w = 64
    private val h = 48
    private val label = "Convert to vector layer"

    private lateinit var context: Context
    private lateinit var scope: CoroutineScope

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        scope = CoroutineScope(SupervisorJob() + Dispatchers.Main)
    }

    @After
    fun tearDown() = scope.cancel()

    private val shape = ShapeObject(
        ShapeType.ELLIPSE, cx = 30f, cy = 20f, w = 24f, h = 16f, style = ShapeStyle.STROKE_FILL, strokeWidth = 3f,
        strokeColor = 0xFF006030.toInt(), fillColor = 0xFF60D090.toInt(),
    )

    /** A "Background" and the layer [target] makes (active), plus [extra] names of empty layers below it. */
    private fun controller(extra: List<String> = emptyList(), target: (Document) -> Layer): Pair<EditorController, Layer> {
        val doc = Document("t", "t", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h).also { it.eraseColor(-1) })
        for (n in extra) doc.layers += Layer(doc.newLayerId(), n, BitmapUtils.createLayerBitmap(w, h)).also { if (n.startsWith("Vector")) it.vector = VectorContent.EMPTY }
        val layer = target(doc)
        doc.layers += layer
        doc.activeLayerIndex = doc.layers.lastIndex
        return EditorController(context, doc, scope, AppSettings(context)) to layer
    }

    private fun plain(name: String) = { d: Document -> Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)) }
    private fun shaped(name: String, data: String = ShapeCodec.encode(shape)) = { d: Document ->
        Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also { it.shapeData = data }
    }
    private fun painted(name: String) = { d: Document ->
        Layer(d.newLayerId(), name, BitmapUtils.createLayerBitmap(w, h)).also {
            android.graphics.Canvas(it.bitmap).drawCircle(20f, 20f, 6f, Paint().apply { color = 0xFF000000.toInt() })
        }
    }

    // ------------------------------------------------------------------ v1.8, verbatim

    private fun v18UniqueLayerName(c: EditorController, base: String): String {
        val names = c.doc.layers.map { it.name }.toSet()
        if (base !in names) return base
        var n = 2
        while ("$base $n" in names) n++
        return "$base $n"
    }

    /** v1.8's convertToVectorLayer (its commitToolWork has nothing to commit here). */
    private fun v18Convert(c: EditorController, layer: Layer, label: String = this.label): Boolean {
        if (c.doc.indexOf(layer) < 0 || layer.isVectorLayer) return false
        if (!c.checkUsable(layer)) return false
        val before = layer.dataSnapshot()
        val content: VectorContent = when {
            layer.isShapeLayer -> {
                val shape = ShapeCodec.decode(layer.shapeData) ?: run { c.toast("This shape can't be converted"); return false }
                VectorContent.EMPTY.plus(listOf(VShape(id = 0, shape = shape))).first
            }
            c.isEmptyPlainLayer(layer) -> VectorContent.EMPTY
            else -> { c.toast("Only empty layers and shape layers can become vector layers"); return false }
        }
        val after = before.copy(text = null, shape = null, vector = content)
        val newName = Regex("Layer (\\d+)").matchEntire(layer.name)?.let { v18UniqueLayerName(c, "Vector ${it.groupValues[1]}") }
        c.groupUndo(label) {
            if (newName != null) c.setLayerProps(layer, layer.props().copy(name = newName), label)
            c.setLayerData(layer, after, label)
        }
        return layer.isVectorLayer
    }

    // ------------------------------------------------------------------ comparison

    /** An undo action's class, label and fields (layers by id; composites recursively). */
    private fun describe(a: UndoAction?): String {
        if (a == null) return "none"
        val fields = generateSequence<Class<*>>(a.javaClass) { it.superclass }.takeWhile { it != Any::class.java }
            .flatMap { it.declaredFields.asSequence() }
            .filter { !java.lang.reflect.Modifier.isStatic(it.modifiers) }
            .sortedBy { it.name }
            .joinToString(", ") { f ->
                f.isAccessible = true
                val v: Any? = f.get(a)
                val text = if (v is Layer) "layer#${v.id}"
                else if (v is List<*>) v.joinToString(prefix = "[", postfix = "]") { if (it is UndoAction) describe(it) else "$it" }
                else "$v"
                "${f.name}=$text"
            }
        return "${a.javaClass.simpleName}($fields)"
    }

    /** Everything observable of [layer] and the controller's history after a conversion. */
    private fun state(c: EditorController, layer: Layer, result: Boolean?, editsBefore: Int, versionBefore: Int, events: List<EditEvent>): List<Any?> = listOf(
        result, c.message, c.editCount - editsBefore, c.layersVersion - versionBefore,
        c.undoManager.undoCount, c.undoManager.undoLabel, describe(c.undoManager.undoAt(c.undoManager.undoCount - 1)),
        layer.name, layer.isVectorLayer, layer.isShapeLayer, layer.dataSnapshot(), layer.props(),
        events.map { "${it.layer.id} ${it.target} ${it.rect} ${it.label}" },
    )

    /** Converts the same fixture with v1.8's code and with today's, and compares everything, also after undo and redo. */
    private fun assertSameAsV18(why: String, extra: List<String> = emptyList(), paper: PaperSpec? = null, target: (Document) -> Layer): Boolean {
        val (old, oldLayer) = controller(extra, target)
        val (now, nowLayer) = controller(extra, target)
        old.doc.paper = paper
        now.doc.paper = paper
        val oldEvents = ArrayList<EditEvent>(); old.addEditListener { oldEvents += it }
        val nowEvents = ArrayList<EditEvent>(); now.addEditListener { nowEvents += it }
        val e0 = old.editCount; val v0 = old.layersVersion
        val e1 = now.editCount; val v1 = now.layersVersion
        val r0 = v18Convert(old, oldLayer)
        val r1 = now.convertToVectorLayer(nowLayer)
        assertEquals(why, state(old, oldLayer, r0, e0, v0, oldEvents), state(now, nowLayer, r1, e1, v1, nowEvents))
        old.undo(); now.undo()
        assertEquals("$why, undone", state(old, oldLayer, null, e0, v0, oldEvents), state(now, nowLayer, null, e1, v1, nowEvents))
        old.redo(); now.redo()
        assertEquals("$why, redone", state(old, oldLayer, null, e0, v0, oldEvents), state(now, nowLayer, null, e1, v1, nowEvents))
        return r1
    }

    // ------------------------------------------------------------------ convertToVectorLayer

    @Test
    fun anEmptyLayerNamedLayerNIsRenamedInOneStep() {
        assertTrue(assertSameAsV18("Layer 1", target = plain("Layer 1")))
        val (c, layer) = controller(target = plain("Layer 1"))
        val edits = c.editCount
        assertTrue(c.convertToVectorLayer(layer))
        assertEquals("Vector 1", layer.name)
        assertEquals(VectorContent.EMPTY, layer.vector)
        assertEquals("two actions pushed, as in v1.8", edits + 2, c.editCount)
        val top = c.undoManager.undoAt(c.undoManager.undoCount - 1)
        assertTrue(top is CompositeAction)
        assertEquals(label, top!!.label)
        c.undo()
        assertEquals("Layer 1", layer.name)
        assertNull(layer.vector)
    }

    @Test
    fun anotherNameIsKeptAndTheStepIsTheDataAlone() {
        assertTrue(assertSameAsV18("Sketch", target = plain("Sketch")))
        val (c, layer) = controller(target = plain("Sketch"))
        c.convertToVectorLayer(layer, "Make vector")
        assertEquals("Sketch", layer.name)
        val top = c.undoManager.undoAt(c.undoManager.undoCount - 1)
        assertTrue(top is LayerDataAction)
        assertEquals("Make vector", top!!.label)
    }

    @Test
    fun aShapeLayerBecomesOneShapeObject() {
        assertTrue(assertSameAsV18("shape layer", target = shaped("Layer 2")))
        val (c, layer) = controller(target = shaped("Layer 2"))
        c.convertToVectorLayer(layer)
        assertFalse(layer.isShapeLayer)
        assertEquals(1, layer.vector!!.objects.size)
        assertEquals(LayerConvert.shapeToVector(shaped("x")(c.doc)), layer.vector)
        c.undo()
        assertTrue(layer.isShapeLayer)
        assertEquals("Layer 2", layer.name)
    }

    @Test
    fun aTakenNameGetsANumber() {
        assertTrue(assertSameAsV18("Vector 3 taken", extra = listOf("Vector 3"), target = plain("Layer 3")))
        assertTrue(assertSameAsV18("Vector 3 and Vector 3 2 taken", extra = listOf("Vector 3", "Vector 3 2"), target = plain("Layer 3")))
    }

    @Test
    fun refusalsAreV18() {
        assertFalse(assertSameAsV18("painted", target = painted("Layer 4")))
        assertFalse(assertSameAsV18("undecodable shape", target = shaped("Layer 5", data = "{not a shape")))
        assertFalse(assertSameAsV18("already vector", target = { d -> plain("Vector 9")(d).also { it.vector = VectorContent.EMPTY } }))
        assertFalse(assertSameAsV18("locked", target = { d -> plain("Layer 6")(d).also { it.locked = true } }))
        assertFalse(assertSameAsV18("hidden", target = { d -> plain("Layer 7")(d).also { it.visible = false } }))
        val (c, _) = controller(target = plain("Layer 1"))
        val outside = plain("Layer 8")(Document("o", "o", w, h))
        assertFalse(c.convertToVectorLayer(outside))
        assertEquals(0, c.undoManager.undoCount)
    }

    @Test
    fun thePaperChangesNothing() {
        // I21: the paper is never pixels nor data; converting on a papered document is the same.
        assertTrue(assertSameAsV18("paper, Layer 1", paper = PaperSpec(), target = plain("Layer 1")))
        assertTrue(assertSameAsV18("paper, shape", paper = PaperSpec(0xFFF4ECD8.toInt()), target = shaped("Layer 2")))
    }

    // ------------------------------------------------------------------ toVectorAction

    @Test
    fun toVectorActionConvertsWithoutAStep() {
        val (c, layer) = controller(target = shaped("Layer 2"))
        val (ref, refLayer) = controller(target = shaped("Layer 2"))
        ref.convertToVectorLayer(refLayer)
        val count = c.undoManager.undoCount
        val action = LayerConvert.toVectorAction(c, layer, "Vector 2")
        assertNotNull(action)
        assertTrue(action is CompositeAction)
        assertEquals(label, action!!.label)
        assertEquals("no step of its own", count, c.undoManager.undoCount)
        assertEquals("Vector 2", layer.name)
        assertEquals(refLayer.dataSnapshot(), layer.dataSnapshot())
        action.undo(c)
        assertEquals("Layer 2", layer.name)
        assertTrue(layer.isShapeLayer)
        assertNull(layer.vector)
        action.redo(c)
        assertEquals("Vector 2", layer.name)
        assertEquals(refLayer.dataSnapshot(), layer.dataSnapshot())
    }

    @Test
    fun toVectorActionFoldsIntoTheCallersStep() {
        val (c, layer) = controller(target = plain("Layer 1"))
        c.groupUndo("Add shape") {
            val action = LayerConvert.toVectorAction(c, layer, label = "Add shape")
            assertTrue("no name: the data action alone", action is LayerDataAction)
            c.pushUndo(action!!)
            c.pushUndo(LambdaAction("Add shape", onUndo = {}, onRedo = {}))
        }
        assertEquals(1, c.undoManager.undoCount)
        assertEquals("Add shape", c.undoManager.undoLabel)
        assertEquals("Layer 1", layer.name)
        assertTrue(layer.isVectorLayer)
        c.undo()
        assertFalse(layer.isVectorLayer)
    }

    @Test
    fun toVectorActionIsNullWhenNothingConverts() {
        val (c, layer) = controller(target = painted("Layer 4"))
        assertNull(LayerConvert.toVectorAction(c, layer, "Vector 4"))
        assertEquals("unchanged", "Layer 4", layer.name)
        assertNull(LayerConvert.toVectorAction(c, plain("Layer 9")(Document("o", "o", w, h))))
        val (v, vector) = controller(target = { d -> plain("Vector 1")(d).also { it.vector = VectorContent.EMPTY } })
        assertNull(LayerConvert.toVectorAction(v, vector))
        val (s, broken) = controller(target = shaped("Layer 5", data = "{not a shape"))
        assertNull(LayerConvert.toVectorAction(s, broken))
        assertTrue(broken.isShapeLayer)
        assertNull(c.message)
        assertEquals(0, c.undoManager.undoCount)
        assertNull(LayerConvert.shapeToVector(layer))
    }

    // ------------------------------------------------------------------ addVectorLayer

    @Test
    fun addVectorLayerNamesItAfterItsBaseName() {
        val (c, _) = controller(target = plain("Layer 1"))
        assertEquals("Vector 1", c.addVectorLayer()!!.name)
        assertEquals("Curves 2", c.addVectorLayer(baseName = "Curves")!!.name)
        assertEquals("Shapes 3", c.addVectorLayer(baseName = "Shapes")!!.name)
        assertEquals("Mine", c.addVectorLayer(name = "Mine", baseName = "Curves")!!.name)
        assertEquals("Add vector layer", c.undoManager.undoLabel)
        val (d, _) = controller(extra = listOf("Curves 1"), target = plain("Layer 1"))
        assertEquals("a taken name gets a number", "Curves 1 2", d.addVectorLayer(baseName = "Curves")!!.name)
    }
}
