package com.brushwork.paint.engine.live

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.LinearGradient
import android.graphics.Matrix
import android.graphics.Paint
import android.graphics.PorterDuff
import android.graphics.Rect
import android.graphics.Shader
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.AdjustmentStage
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.engine.DisplayTiles
import com.brushwork.paint.filters.FilterRegistry
import com.brushwork.paint.masks.AdjustmentEffects
import com.brushwork.paint.masks.AdjustmentSpec
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.model.LayerBlendMode
import com.brushwork.paint.tools.mask.AdjustmentEdit
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import org.junit.After
import org.junit.Assert.assertArrayEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment

/**
 * v1.7 area A (§3.8 c): live adjustment with folders. The proxies split the flat stack at the
 * adjustment layer, so a session runs while every folder it is in draws its children as is
 * (pass-through, visible, 100 %, no clipping across its edge) and converges bit for bit (I7);
 * inside an isolated folder, below 100 % or under a clipping sibling folder the exact mode runs.
 * The below-cache key notices a layer below moving into a folder (only its parent changes).
 */
@RunWith(RobolectricTestRunner::class)
class LiveAdjustFoldersRobolectricTest {
    private val w = 512
    private val h = 384
    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate)
    private lateinit var c: EditorController
    private lateinit var photo: Layer
    private lateinit var adj: Layer
    private lateinit var folder: Layer
    private var now = 0L
    private val tone = FilterRegistry.byId("adjust.tone")!!

    @After
    fun tearDown() {
        if (::c.isInitialized) c.dispose()
        AdjustmentStage.safeCompositing = false
        scope.cancel()
    }

    /** Background, then a folder holding a photo and a Tone layer, then a Multiply layer above it. */
    private fun setup() {
        val app = RuntimeEnvironment.getApplication()
        val settings = AppSettings(app)
        settings.prefs.edit().clear().commit()
        val doc = Document("live", "live", w, h)
        doc.layers += Layer(doc.newLayerId(), "Background", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawPaint(Paint().apply { shader = LinearGradient(0f, 0f, w.toFloat(), h.toFloat(), 0xFF204070.toInt(), 0xFFE0B070.toInt(), Shader.TileMode.CLAMP) })
        }
        photo = Layer(doc.newLayerId(), "Photo", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawCircle(220f, 190f, 130f, Paint(Paint.ANTI_ALIAS_FLAG).apply { color = 0xC0E05030.toInt() })
        }
        doc.layers += photo
        doc.activeLayerIndex = 1
        c = EditorController(app, doc, scope, settings).also { it.viewTransform.set(Matrix()) }
        adj = c.addAdjustmentLayer(AdjustmentEffects.defaultSpec(tone), null)!!
        doc.layers += Layer(doc.newLayerId(), "Above", BitmapUtils.createLayerBitmap(w, h)).also {
            Canvas(it.bitmap).drawRect(50f, 250f, 450f, 320f, Paint().apply { color = 0xFF8090F0.toInt() })
            it.blendMode = LayerBlendMode.MULTIPLY
        }
        c.notifyLayersChanged()
        folder = c.putInNewFolder(adj)!!
        assertTrue(c.putIntoFolderAbove(photo))
        c.invalidateDoc(null)
        now = 0L
        c.liveAdjust.clock = { now }
        c.liveAdjust.policy = LiveAdjust.Policy.LIVE
        c.tiles.update(c.compositor, null)
    }

    private fun spec(exposure: Float): AdjustmentSpec = AdjustmentEffects.spec(tone, tone.defaultValues().set("exposure", exposure))

    private val screen = Bitmap.createBitmap(256, 192, Bitmap.Config.ARGB_8888)

    /** One canvas frame at 50 %; true when the session drew it. */
    private fun frame(): Boolean {
        val visible = Rect(0, 0, w, h)
        val cv = Canvas(screen)
        cv.drawColor(0, PorterDuff.Mode.CLEAR)
        val m = Matrix().apply { setScale(0.5f, 0.5f) }
        if (c.liveAdjust.drawFrame(cv, m, visible, true)) return true
        c.tiles.update(c.compositor, visible)
        cv.save(); cv.concat(m); c.tiles.draw(cv, visible, true); cv.restore()
        return false
    }

    private fun pixels(t: DisplayTiles): IntArray {
        val out = Bitmap.createBitmap(w, h, Bitmap.Config.ARGB_8888)
        t.draw(Canvas(out), null, smooth = false)
        return IntArray(w * h).also { out.getPixels(it, 0, w, 0, 0, w, h) }
    }

    private fun exact(): IntArray {
        val t = DisplayTiles(w, h)
        t.update(c.compositor, null)
        return pixels(t).also { t.release() }
    }

    private fun drag(): AdjustmentEdit = AdjustmentEdit(c, adj).also { it.preview(spec(0.6f)); it.preview(spec(1.2f)) }

    @Test
    fun aSessionRunsInsideAnAsIsFolderAndConvergesBitForBit() {
        setup()
        assertTrue("the photo and the Tone layer are in the folder", photo.parentId == folder.id && adj.parentId == folder.id)
        val edit = drag()
        assertTrue("a session started", c.liveAdjust.isActive)
        assertTrue("the session draws the frame", frame())
        edit.flush()
        var n = 0
        while (c.liveAdjust.refineStep()) assertTrue(++n < 64)
        assertFalse(c.liveAdjust.isActive)
        assertArrayEquals("I7 with a folder", exact(), pixels(c.tiles))
    }

    @Test
    fun anIsolatedFolderOrOneBelow100RunsTheExactMode() {
        setup()
        c.setFolderPassThrough(folder, false)
        drag().flush()
        assertFalse("isolated: no session", c.liveAdjust.isActive)
        assertFalse(frame())
        c.setFolderPassThrough(folder, true)
        c.setLayerProps(folder, folder.props().copy(opacity = 0.5f), "Opacity")
        drag().flush()
        assertFalse("below 100 %: no session", c.liveAdjust.isActive)
        assertArrayEquals(exact(), run { c.tiles.update(c.compositor, null); pixels(c.tiles) })
    }

    @Test
    fun aClippingSiblingFolderAboveRunsTheExactMode() {
        setup()
        // A non-empty folder directly above, clipping onto ours: ours is a clip base.
        val above = c.doc.layers.last()
        val clip = c.putInNewFolder(above)
        assertNotNull(clip)
        c.setLayerProps(clip!!, clip.props().copy(clipping = true), "Clipping")
        drag().flush()
        assertFalse("a clip base folder: no session", c.liveAdjust.isActive)
        assertArrayEquals(exact(), run { c.tiles.update(c.compositor, null); pixels(c.tiles) })
    }

    @Test
    fun theBelowKeyNoticesAMoveIntoAFolder() {
        setup()
        val doc = c.doc
        val key = BelowKey()
        val split = doc.indexOf(adj)
        key.capture(doc, split, safe = false)
        assertTrue(key.matches(doc, split, false, null))
        // Out of the folder (the flat order does not change, only the parent).
        assertTrue(c.takeOutOfFolder(photo))
        assertFalse(key.matches(doc, doc.indexOf(adj), false, null))
        key.capture(doc, doc.indexOf(adj), safe = false)
        c.setFolderPassThrough(folder, false)
        // The folder lies above the split: the key does not see it (the session stops instead).
        assertTrue(key.matches(doc, doc.indexOf(adj), false, null))
    }
}
