package com.brushwork.paint.vector.draw

import android.graphics.Matrix
import android.os.Looper
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.brush.BrushLibrary
import com.brushwork.paint.core.PackedPoints
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.vector.VStroke
import com.brushwork.paint.vector.VectorContent
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf

/**
 * v1.5 A3 review: the per-editor state of the vector drawing tools lives in a weak map keyed by
 * the editor, so it must never hold that editor (a closed editor, its document and its layer
 * bitmaps would stay alive for the life of the app); jobs waiting for an update that a closing
 * editor abandons are dropped with it.
 */
@RunWith(RobolectricTestRunner::class)
class VectorDrawStateLifetimeTest {
    private val scopes = ArrayList<CoroutineScope>()

    @After
    fun tearDown() = scopes.forEach { it.cancel() }

    private val app get() = RuntimeEnvironment.getApplication()

    private fun setup(): Pair<EditorController, CoroutineScope> {
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate).also { scopes += it }
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("t", "t", 300, 200)
        doc.layers += Layer(doc.newLayerId(), "Vector 1", BitmapUtils.createLayerBitmap(300, 200)).also { it.vector = VectorContent.EMPTY }
        val c = EditorController(app, doc, scope, settings).also {
            it.viewTransform.set(Matrix())
            it.tools
        }
        return c to scope
    }

    private fun stroke(x0: Float, y0: Float, x1: Float, y1: Float, n: Int = 31): VStroke {
        val xs = FloatArray(n) { x0 + (x1 - x0) * it / (n - 1) }
        val ys = FloatArray(n) { y0 + (y1 - y0) * it / (n - 1) }
        return VStroke(0, preset = BrushLibrary.defaultBrush.copy(size = 8f), color = 0xFF000000.toInt(), seed = 5L, stylus = false, points = PackedPoints(xs, ys, FloatArray(n) { 1f }))
    }

    private fun EditorController.swipe(x: Float, y0: Float, y1: Float) {
        pointerDown(ToolPoint(x, y0))
        pointerMove(ToolPoint(x, (y0 + y1) / 2f))
        pointerUp(ToolPoint(x, y1))
    }

    /** Every field of [o]'s class and its superclasses that holds an editor. */
    private fun editorFields(o: Any): List<String> {
        val out = ArrayList<String>()
        var k: Class<*>? = o.javaClass
        while (k != null && k != Any::class.java) {
            for (f in k.declaredFields) {
                if (java.lang.reflect.Modifier.isStatic(f.modifiers)) continue
                f.isAccessible = true
                if (f.get(o) is EditorController) out += "${k.simpleName}.${f.name}"
            }
            k = k.superclass
        }
        return out
    }

    @Test
    fun theStateNeverHoldsItsEditor() {
        val (c, _) = setup()
        c.vectors.addObjects(c.doc.layers[0], listOf(stroke(100f, 20f, 100f, 180f)), "Add")
        // Use every seam once: the brush capture, the eraser (with its hint) and the bucket.
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        c.swipe(100f, 30f, 60f)
        c.selectTool(ToolId.FILL)
        c.pointerDown(ToolPoint(200f, 100f))
        c.pointerUp(ToolPoint(200f, 100f))
        val state = VectorDrawState.of(c)
        assertEquals(emptyList<String>(), editorFields(state))
        // The default update is no closure over an editor either.
        assertEquals(emptyList<String>(), editorFields(state.update))
    }

    @Test
    fun jobsWaitingForAnUpdateAreDroppedWhenTheEditorCloses() {
        val (c, scope) = setup()
        val layer = c.doc.layers[0]
        c.vectors.addObjects(layer, listOf(stroke(100f, 20f, 100f, 180f), stroke(200f, 20f, 200f, 180f)), "Add")
        val state = VectorDrawState.of(c)
        // Background renders that never report back (the editor closes before they land).
        val held = ArrayList<() -> Unit>()
        state.update = { cc, l, after, label, done -> held += { cc.vectors.update(l, after, label, onDone = done) } }
        c.selectTool(ToolId.ERASER)
        c.eraser = BrushLibrary.defaultEraser.copy(size = 12f)
        VectorEraserModes.setMode(c, VectorEraseMode.OBJECT)
        c.swipe(100f, 30f, 60f)
        c.swipe(200f, 30f, 60f)
        assertEquals(1, held.size)
        // While the first update is on its way, further jobs for the layer wait.
        var ran = false
        state.serial(layer) { finish -> ran = true; finish() }
        assertFalse(ran)
        // The editor closes: its scope ends, the waiting jobs (which hold the editor) are dropped
        // and the layer no longer counts as busy.
        scope.cancel()
        shadowOf(Looper.getMainLooper()).idle()
        state.serial(layer) { finish -> ran = true; finish() }
        assertTrue(ran)
        assertEquals(1, held.size)
    }
}
