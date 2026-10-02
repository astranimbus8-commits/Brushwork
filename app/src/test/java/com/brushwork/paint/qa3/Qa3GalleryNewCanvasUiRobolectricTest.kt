package com.brushwork.paint.qa3

import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import com.brushwork.paint.AppSettings
import com.brushwork.paint.EditorController
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.smoke.SmokeUi
import com.brushwork.paint.smoke.SmokeUi.settle
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.tools.ToolId
import com.brushwork.paint.tools.ToolPoint
import com.brushwork.paint.ui.editor.EditorScreen
import com.brushwork.paint.ui.gallery.GalleryScreen
import com.brushwork.paint.ui.theme.BrushworkTheme
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowLog
import java.io.File

/**
 * Final QA (v1.5 §4.9, §4.11, §5.9) on a narrow phone (360 dp): the gallery's "New from SVG or
 * PDF" entry (empty gallery and with artworks) is on the screen next to "Import picture"; a new
 * canvas made by the repository, opened in the editor: Vector turns its empty "Layer 1" into
 * "Vector 1", a stroke is an object, and after saving and reopening the layer is still a vector
 * layer and vector mode is on.
 *
 * Own sandbox; all UI work in ONE test split into sections.
 */
@RunWith(RobolectricTestRunner::class)
@Config(qualifiers = "w360dp-h760dp-xxhdpi", instrumentedPackages = ["com.brushwork.paint.qa3.gallerysandbox"])
class Qa3GalleryNewCanvasUiRobolectricTest {

    private val failures = mutableListOf<Throwable>()
    private val activities = mutableListOf<org.robolectric.android.controller.ActivityController<*>>()

    private fun section(name: String, block: () -> Unit) {
        Smoke.scopeErrors.clear()
        Smoke.step("section $name")
        try {
            block()
            if (Smoke.scopeErrors.isNotEmpty()) throw AssertionError("coroutine errors: ${Smoke.scopeErrors}", Smoke.scopeErrors.first())
        } catch (t: Throwable) {
            System.err.println("=== SECTION FAILED: $name")
            t.printStackTrace()
            failures += AssertionError("[$name] $t", t)
        } finally {
            activities.forEach { runCatching { it.pause().stop().destroy() } }
            activities.clear()
            runCatching { settle() }
        }
    }

    private fun activity(): ComponentActivity {
        SmokeUi.markBaseline()
        val ctl = Robolectric.buildActivity(ComponentActivity::class.java).setup()
        activities += ctl
        return ctl.get()
    }

    private fun assertOnScreen(activity: ComponentActivity, label: String) {
        val e = SmokeUi.find(label, exact = true) ?: throw AssertionError("no \"$label\"; shown: ${SmokeUi.shown()}")
        val w = activity.window.decorView.width
        assertTrue("\"$label\" is on the screen: ${e.bounds} in 0..$w", e.bounds.left >= 0f && e.bounds.right <= w && e.bounds.width > 0f)
        assertTrue("\"$label\" can be tapped", SmokeUi.isEnabled(label))
    }

    @Test
    fun galleryAndNewCanvas() {
        ShadowLog.stream = null
        SmokeUi.installTestRecomposer()
        val dog = Smoke.watchdog()
        section("gallery entries") { gallery() }
        section("new canvas -> Vector -> reopen") { newCanvas() }
        dog.interrupt()
        if (failures.isNotEmpty()) {
            val first = failures.first()
            failures.drop(1).forEach { first.addSuppressed(it) }
            throw first
        }
    }

    private fun gallery() {
        val a = activity()
        File(a.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(a)
        a.setContent { BrushworkTheme { GalleryScreen(repo, onOpenProject = {}) } }
        Smoke.pumpUntil(10_000) { SmokeUi.has("New canvas") }
        settle()
        assertOnScreen(a, "New from SVG or PDF")
        assertOnScreen(a, "Import picture")
        runBlocking { repo.create(NewCanvasSpec("Art", 540, 960, 350f)) }
        Smoke.pumpUntil(10_000) { SmokeUi.has("Art", exact = true) }
        settle()
        assertTrue(SmokeUi.has("Art", exact = true))
        assertOnScreen(a, "New from SVG or PDF")
        assertOnScreen(a, "Import picture")
    }

    private fun newCanvas() {
        val a = activity()
        File(a.filesDir, "projects").deleteRecursively()
        a.getSharedPreferences("brushwork_settings", android.content.Context.MODE_PRIVATE).edit().clear().commit()
        val repo = ProjectRepository(a)
        val id = runBlocking { repo.create(NewCanvasSpec("Art", 540, 960, 350f)) }
        val doc = runBlocking { repo.load(id) }
        assertEquals(listOf("Background", "Layer 1"), doc.layers.map { it.name })
        val c = EditorController(a.applicationContext, doc, Smoke.newScope(), AppSettings(a))
        a.setContent { BrushworkTheme { EditorScreen(c, onExit = {}, onSaveNow = {}) } }
        settle()
        c.tools
        settle()
        assertFalse(c.isVectorMode)
        SmokeUi.click("Vector", exact = true)
        val layer = c.activeLayer
        assertEquals("the empty Layer 1 became the vector layer", "Vector 1", layer.name)
        assertEquals(2, doc.layers.size)
        assertTrue(layer.isVectorLayer)
        c.selectTool(ToolId.BRUSH)
        c.pointerDown(ToolPoint(100f, 300f))
        for (i in 1..20) c.pointerMove(ToolPoint(100f + 15f * i, 300f + 8f * i))
        c.pointerUp(ToolPoint(400f, 460f))
        Smoke.pump(100)
        assertEquals(1, layer.vector!!.objects.size)
        runBlocking { repo.save(c.doc, c.compositor.renderThumbnail(64)) }
        c.dispose()
        activities.forEach { runCatching { it.pause().stop().destroy() } }
        activities.clear()
        settle()

        val a2 = activity()
        val doc2 = runBlocking { repo.load(id) }
        val c2 = EditorController(a2.applicationContext, doc2, Smoke.newScope(), AppSettings(a2))
        a2.setContent { BrushworkTheme { EditorScreen(c2, onExit = {}, onSaveNow = {}) } }
        settle()
        c2.tools
        settle()
        val l2 = doc2.layers.first { it.id == layer.id }
        assertTrue("still a vector layer", l2.isVectorLayer)
        assertEquals(1, l2.vector!!.objects.size)
        assertTrue("vector mode is on when the artwork opens on its vector layer", c2.isVectorMode)
        assertTrue(SmokeUi.has("Vector mode is on"))
        c2.dispose()
    }
}
