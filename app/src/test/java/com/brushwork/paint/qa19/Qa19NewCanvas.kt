package com.brushwork.paint.qa19

import android.content.Context
import androidx.compose.ui.geometry.Rect
import androidx.compose.ui.semantics.SemanticsActions
import androidx.compose.ui.semantics.SemanticsNode
import androidx.compose.ui.semantics.SemanticsProperties
import androidx.compose.ui.semantics.getOrNull
import androidx.compose.ui.unit.toSize
import androidx.test.core.app.ApplicationProvider
import com.brushwork.paint.AppSettings
import com.brushwork.paint.model.PaperSpec
import com.brushwork.paint.smoke.Smoke
import com.brushwork.paint.storage.CanvasStart
import com.brushwork.paint.storage.NewCanvasSpec
import com.brushwork.paint.storage.ProjectRepository
import com.brushwork.paint.ui.gallery.GalleryScreen
import com.brushwork.paint.ui.gallery.NewCanvasBackground
import com.brushwork.paint.ui.gallery.NewCanvasDialog
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import java.io.File

/**
 * v1.9 A8 (R15; I27): the cases of the New canvas tests ([Qa19NewCanvasUiTest] at 392 dp,
 * [Qa19NewCanvasNarrowUiTest] at 360 dp, each at font scale 1 and at [Qa19Ui.FONT_SCALES]). The
 * dialog's "Background" row has four choices, each found by its tag, on one line at font scale 1;
 * the selected one's caption shows under the row; the choice is remembered; and the gallery's
 * "New canvas" makes one layer.
 */
internal object Qa19NewCanvas {
    private val app: Context get() = ApplicationProvider.getApplicationContext()

    private fun clearSettings() {
        app.getSharedPreferences("brushwork_settings", Context.MODE_PRIVATE).edit().clear().commit()
    }

    private fun option(b: NewCanvasBackground): SemanticsNode =
        Qa19Ui.tagged(b.tag) ?: throw AssertionError("no option tagged ${b.tag}; shown: ${Qa19Ui.shown().take(80)}")

    private fun choose(b: NewCanvasBackground) {
        Smoke.step("choose $b")
        val action = option(b).config.getOrNull(SemanticsActions.OnClick)?.action ?: throw AssertionError("${b.tag} cannot be tapped")
        action.invoke()
        Qa19Ui.settle()
    }

    private fun selected(b: NewCanvasBackground): Boolean = option(b).config.getOrNull(SemanticsProperties.Selected) == true

    /** The selected choice shows [b]'s caption, and only [b] is selected. */
    private fun assertChosen(b: NewCanvasBackground, where: String) {
        for (other in NewCanvasBackground.entries) assertEquals("$other selected, $where", other == b, selected(other))
        assertTrue("the caption of $b, $where; shown: ${Qa19Ui.shown().take(80)}", Qa19Ui.has(b.caption, exact = true))
        for (other in NewCanvasBackground.entries) {
            if (other.caption != b.caption) assertTrue("no caption of $other, $where", !Qa19Ui.has(other.caption, exact = true))
        }
    }

    /**
     * The dialog at the device's [fontScale] on a [widthDp] phone: the four options inside the
     * screen's width (on one line at font scale 1), White first by default with its caption, each
     * tap moves the caption, "Create" passes the choice's start, and the next dialog opens on it.
     */
    fun dialog(h: Qa19Harness, fontScale: Float, widthDp: Float) {
        val where = "$widthDp dp at font scale $fontScale"
        clearSettings()
        var made: NewCanvasSpec? = null
        val a = h.show(fontScale) { NewCanvasDialog(creating = false, onDismiss = {}, onCreate = { made = it }) }
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(NewCanvasBackground.PAPER.tag) != null }
        val width = a.window.decorView.width
        assertEquals("the phone's width", widthDp, width / a.resources.displayMetrics.density, 1f)
        // Unclipped (boundsInWindow is clipped by the dialog's scroller, and a large font pushes the row below the screen).
        val boxes = NewCanvasBackground.entries.map { option(it).let { n -> Rect(n.positionInWindow, n.size.toSize()) } }
        for ((b, r) in NewCanvasBackground.entries.zip(boxes)) {
            assertTrue("$b inside the screen's width, $where: $r in 0..$width", r.left >= 0f && r.right <= width && r.width > 0f)
            val dp = r.height / a.resources.displayMetrics.density
            assertTrue("$b is at least the 44 dp swatch tall, $where: $dp dp", dp >= 44f)
        }
        if (fontScale == 1f) {
            assertEquals("one line at font scale 1, $where", 1, boxes.map { it.top }.distinct().size)
            assertTrue("in order, $where", boxes.zipWithNext().all { (l, r) -> l.right <= r.left })
        }
        assertChosen(NewCanvasBackground.WHITE, "first, $where")
        for (b in listOf(NewCanvasBackground.PAPER, NewCanvasBackground.TRANSPARENT, NewCanvasBackground.WHITE, NewCanvasBackground.PAPER)) {
            choose(b)
            assertChosen(b, where)
        }
        Qa19Ui.assertNoTextOverflow(where)
        Qa19Ui.click("Create", exact = true)
        val spec = made ?: throw AssertionError("Create made no canvas, $where")
        assertEquals(where, CanvasStart.PAPER, spec.start)
        assertEquals(where, null, spec.background)
        assertEquals("remembered, $where", AppSettings.NEW_CANVAS_PAPER, AppSettings(app).newCanvasBackground)

        // The next dialog opens on the remembered choice.
        val b = h.show(fontScale) { NewCanvasDialog(creating = false, onDismiss = {}, onCreate = { made = it }) }
        assertNotNull(b)
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(NewCanvasBackground.PAPER.tag) != null }
        assertChosen(NewCanvasBackground.PAPER, "reopened, $where")
        choose(NewCanvasBackground.TRANSPARENT)
        Qa19Ui.click("Create", exact = true)
        assertEquals(where, CanvasStart.TRANSPARENT, made!!.start)
        assertEquals("remembered, $where", AppSettings.NEW_CANVAS_TRANSPARENT, AppSettings(app).newCanvasBackground)
    }

    /** The gallery's "New canvas" with "Paper": the project has one empty "Layer 1" over the paper. */
    fun gallery(h: Qa19Harness, widthDp: Float) {
        val where = "the gallery at $widthDp dp"
        clearSettings()
        File(app.filesDir, "projects").deleteRecursively()
        val repo = ProjectRepository(app)
        var opened: String? = null
        h.show { GalleryScreen(repo, onOpenProject = { opened = it }) }
        Smoke.pumpUntil(10_000) { Qa19Ui.has("New canvas") }
        Qa19Ui.click("New canvas", exact = true)
        Smoke.pumpUntil(10_000) { Qa19Ui.tagged(NewCanvasBackground.PAPER.tag) != null }
        assertChosen(NewCanvasBackground.WHITE, where)
        choose(NewCanvasBackground.PAPER)
        Qa19Ui.click("Create", exact = true)
        Smoke.pumpUntil(10_000) { opened != null }
        val id = opened ?: throw AssertionError("no canvas was opened, $where")
        val doc = runBlocking { repo.load(id) }
        assertEquals(where, listOf("Layer 1"), doc.layers.map { it.name })
        assertEquals(where, 0, doc.activeLayerIndex)
        assertEquals(where, PaperSpec(), doc.paper)
        assertEquals("transparent over the paper, $where", 0, doc.layers.single().bitmap.getPixel(0, 0))
        for (l in doc.layers) l.recycleBitmaps()
        assertEquals("remembered, $where", AppSettings.NEW_CANVAS_PAPER, AppSettings(app).newCanvasBackground)
    }
}
