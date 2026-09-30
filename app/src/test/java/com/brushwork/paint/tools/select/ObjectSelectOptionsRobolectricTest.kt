package com.brushwork.paint.tools.select

import android.content.Context
import android.graphics.Canvas
import android.graphics.Paint
import android.os.Looper
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.material3.Surface
import androidx.compose.ui.Modifier
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.engine.BitmapUtils
import com.brushwork.paint.model.Document
import com.brushwork.paint.model.Layer
import com.brushwork.paint.segmentation.LiteRtMagicTouch
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.ui.color.RobolectricUi
import com.brushwork.paint.ui.theme.BrushworkTheme
import com.brushwork.paint.ui.tools.ToolOptionsBar
import kotlinx.coroutines.CoroutineExceptionHandler
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import java.time.Duration
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import kotlin.concurrent.thread

/**
 * The object select options strip on a 360 dp wide phone (the narrowest the app supports): how
 * to use the tool, the four selection modes, and, while a selection is computed, the spinner and
 * the cancel button must all be on screen without scrolling the strip; cancel must work.
 *
 * Own sandbox (like the color picker UI tests): later tests in a shared sandbox get no frames.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-hdpi", instrumentedPackages = ["com.brushwork.paint.tools.select.objectoptionssandbox"])
class ObjectSelectOptionsRobolectricTest {
    private val errors = java.util.Collections.synchronizedList(mutableListOf<Throwable>())

    private fun controller(w: Int, h: Int): EditorController {
        val ctx = ApplicationProvider.getApplicationContext<Context>()
        val doc = Document("t", "t", w, h)
        val bmp = BitmapUtils.createLayerBitmap(w, h)
        bmp.eraseColor(-1)
        Canvas(bmp).drawCircle(w / 2f, h / 2f, h / 5f, Paint().apply { color = 0xFFDC2828.toInt() })
        doc.layers += Layer(doc.newLayerId(), "Layer 1", bmp)
        val scope = CoroutineScope(SupervisorJob() + Dispatchers.Main.immediate + CoroutineExceptionHandler { _, t -> errors += t })
        val settings = AppSettings(ctx).also { it.prefs.edit().clear().commit() }
        return EditorController(ctx, doc, scope, settings)
    }

    private fun pumpUntil(timeoutMs: Long = 60_000, done: () -> Boolean): Boolean {
        val end = System.currentTimeMillis() + timeoutMs
        while (System.currentTimeMillis() < end) {
            shadowOf(Looper.getMainLooper()).idleFor(Duration.ofMillis(20))
            if (done()) return true
            Thread.sleep(5)
        }
        return done()
    }

    @Test
    fun hintModesAndCancelFitANarrowPhoneAndCancelWorks() {
        // The busy spinner is an infinite animation: park it, or the paused looper never idles.
        SmokeUi.installTestRecomposer()
        val activity = Robolectric.buildActivity(ComponentActivity::class.java).setup().get()
        val c = controller(1600, 1200)
        c.selectTool(ToolId.OBJECT_SELECT)
        val tool = c.tools.getValue(ToolId.OBJECT_SELECT) as ObjectSelectTool
        activity.setContent { BrushworkTheme { Surface { ToolOptionsBar(c, Modifier.fillMaxWidth()) } } }
        RobolectricUi.settle(10, 50)
        val width = activity.resources.displayMetrics.widthPixels

        val hint = RobolectricUi.byText(HINT)
        assertTrue("hint at ${hint.bounds} on a $width px screen", hint.bounds.right <= width)
        for (m in listOf("New selection", "Add to selection", "Subtract from selection", "Intersect with selection")) {
            val b = RobolectricUi.byDescription(m).bounds
            assertTrue("$m at $b on a $width px screen", b.right <= width)
        }

        // Keep the selection busy for as long as the test needs: hold the object model's lock
        // (the job waits for it before its first inference).
        val model = LiteRtMagicTouch.get(activity)
        val lock = LiteRtMagicTouch::class.java.getDeclaredField("lock").apply { isAccessible = true }.get(model)!!
        val held = CountDownLatch(1)
        val release = CountDownLatch(1)
        val holder = thread(name = "hold-object-model") { synchronized(lock) { held.countDown(); release.await(60, TimeUnit.SECONDS) } }
        try {
            assertTrue(held.await(10, TimeUnit.SECONDS))
            tool.selectAt(800f, 600f)
            assertTrue(tool.busy)
            RobolectricUi.settle(4, 50)
            assertTrue("still waiting for the model", tool.busy)
            assertFalse("the hint makes room for the busy state", RobolectricUi.hasText(HINT))
            assertTrue(RobolectricUi.hasText("Selecting…"))
            val cancel = RobolectricUi.byDescription("Cancel object select")
            assertTrue("cancel at ${cancel.bounds} on a $width px screen", cancel.bounds.right <= width && cancel.bounds.left >= 0f)
            cancel.tap()
        } finally {
            release.countDown()
            holder.join(10_000)
        }
        assertTrue(pumpUntil { !tool.busy })
        assertNull("cancelled: nothing selected", c.selection)
        RobolectricUi.settle(4, 50)
        assertTrue("the hint is back", RobolectricUi.hasText(HINT))
        assertTrue(errors.isEmpty())
    }

    private companion object {
        const val HINT = "Tap or scribble on an object"
    }
}
