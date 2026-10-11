package com.brushwork.paint.array

import com.brushwork.paint.EditorController
import com.brushwork.paint.array.ArrayLookKit.GRAY
import com.brushwork.paint.array.ArrayLookKit.RED
import com.brushwork.paint.array.ArrayLookKit.RED_TO_BLUE
import com.brushwork.paint.array.ArrayLookKit.pixels
import com.brushwork.paint.engine.ArrayDraw
import com.brushwork.paint.model.ArrayEasing
import com.brushwork.paint.model.ArrayLook
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.text.TextItem
import com.brushwork.paint.tools.text.TextSpec
import com.brushwork.paint.tools.vector.ShapeObject
import com.brushwork.paint.tools.vector.ShapeStyle
import com.brushwork.paint.tools.vector.ShapeType
import com.brushwork.paint.vector.VStop
import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import java.io.File

/**
 * v1.8 (area B review, design §3.3 b): an Array look survives save and reopen on every source kind
 * (pixels, text, shape, vector), made as the user makes them: the spec comes back with its look
 * (every field, the Pace and the tint stops included), the cache shows the same pixels, and the
 * array stays live: the same edit of the look after reopening gives the same pixels as before
 * saving.
 */
@RunWith(RobolectricTestRunner::class)
class ArrayLookPersistenceTest {
    private val app get() = RuntimeEnvironment.getApplication()
    private val root get() = File(app.filesDir, "projects")

    @Before
    fun setUp() {
        root.deleteRecursively()
    }

    @After
    fun tearDown() = ArrayDraw.clearCaches()

    private fun save(doc: Document) = runBlocking { ProjectRepository(app).save(doc, null) }

    private fun load(id: String): Document = runBlocking { ProjectRepository(app).load(id) }

    /** The four arrays, each with every part of a look; by kind. */
    private fun arrays(c: EditorController): Map<String, Layer> {
        val px = ArrayLookKit.pixelArray(c)
        ArrayLookKit.edit(c, px, px.array!!.spec.copy(count = 4, look = LOOK))
        val text = ArrayLookKit.textArray(c, TextItem("Ab", spec = TextSpec(sizePx = 32f, color = GRAY), cx = 250f, cy = 50f))
        ArrayLookKit.edit(c, text, text.array!!.spec.copy(count = 4, look = LOOK))
        val shape = ArrayLookKit.shapeArray(c, ShapeObject(ShapeType.RECTANGLE, cx = 300f, cy = 200f, w = 40f, h = 30f, style = ShapeStyle.FILL))
        ArrayLookKit.edit(c, shape, shape.array!!.spec.copy(count = 4, look = LOOK))
        val vector = ArrayLookKit.vectorArray(c, listOf(ArrayLookKit.box(40f, 200f, 70f, 230f, stroke = RED)))
        ArrayLookKit.edit(c, vector, vector.array!!.spec.copy(count = 4, look = LOOK))
        c.vectors.flushPending()
        ArrayLookKit.settle(c)
        return mapOf("pixels" to px, "text" to text, "shape" to shape, "vector" to vector)
    }

    @Test
    fun aLookSurvivesSaveAndReopenAndStaysLive() {
        val c = ArrayLookKit.controller(app)
        val made = arrays(c)
        for ((kind, l) in made) assertEquals("$kind: the look as set", LOOK, l.array!!.spec.look)
        save(c.doc)
        val loaded = load(c.doc.id)
        assertEquals(emptyList<String>(), loaded.loadWarnings)
        val c2 = Smoke.controller(app, loaded)
        fun twin(l: Layer) = loaded.layers[c.doc.indexOf(l)]

        for ((kind, l) in made) {
            val back = twin(l)
            assertNotNull("$kind: still an array", back.array)
            val b = back.array!!
            assertEquals("$kind: the spec, its look included", l.array!!.spec, b.spec)
            assertEquals("$kind: the look", LOOK, b.spec.look)
            assertEquals(kind, l.textData, back.textData)
            assertEquals(kind, l.shapeData, back.shapeData)
            assertEquals(kind, l.vector, back.vector)
            assertArrayEquals("$kind: the cache, looks included", pixels(l.bitmap), pixels(back.bitmap))
        }

        // Live after reopening: the same edit of the look gives the same pixels.
        for (ctrl in listOf(c, c2)) {
            val layers = if (ctrl === c) made else made.mapValues { twin(it.value) }
            for (l in layers.values) {
                val s = l.array!!.spec
                assertTrue(ArrayOps.edit(ctrl, l, s.copy(count = s.count + 1, look = s.look!!.copy(tintStrength = 0.5f, opacityStart = 0.8f))))
            }
            ctrl.vectors.flushPending()
            ArrayLookKit.settle(ctrl)
        }
        for ((kind, l) in made) {
            val back = twin(l)
            assertEquals(kind, l.array!!.spec, back.array!!.spec)
            assertEquals(kind, 0.5f, back.array!!.spec.look!!.tintStrength, 0f)
            assertArrayEquals("$kind: edited after reopening", pixels(l.bitmap), pixels(back.bitmap))
            assertFalse("$kind: the copies show", pixels(back.bitmap).all { it == 0 })
        }
        Smoke.assertQuiet(c2, "reopened with looks")
    }

    private companion object {
        /** Every part of a look: a fade, a size and a turn gradient, a non-even Pace, a three-stop tint. */
        val LOOK = ArrayLook(
            opacityStart = 0.9f,
            opacityEnd = 0.4f,
            sizeStart = 0.9f,
            sizeEnd = 1.3f,
            turnStart = -10f,
            turnEnd = 25f,
            easing = ArrayEasing.EASE_IN_OUT,
            tint = listOf(RED_TO_BLUE[0], VStop(0.5f, 0xFF00C040.toInt()), RED_TO_BLUE[1]),
            tintStrength = 0.75f,
        )
    }
}
